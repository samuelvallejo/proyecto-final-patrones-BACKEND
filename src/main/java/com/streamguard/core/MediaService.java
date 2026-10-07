package com.streamguard.core;

import com.streamguard.i18n.Messages;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MediaService {
  private final Path root;
  private final Path workRoot;
  private final String ffmpeg, ffprobe;
  private final Db db;
  private final boolean databaseStorage;

  public MediaService(
      Db db,
      @Value("${app.media-dir}") String directory,
      @Value("${app.media-work-dir:}") String workDirectory,
      @Value("${app.media-storage:filesystem}") String storage,
      @Value("${FFMPEG_BIN:ffmpeg}") String ffmpeg,
      @Value("${FFPROBE_BIN:ffprobe}") String ffprobe)
      throws IOException {
    this.db = db;
    if (!Set.of("filesystem", "database").contains(storage)) throw new IllegalArgumentException("Invalid media storage mode");
    this.databaseStorage = storage.equals("database");
    this.root = Path.of(directory).toAbsolutePath().normalize();
    this.workRoot = workDirectory.isBlank() ? root : Path.of(workDirectory).toAbsolutePath().normalize();
    this.ffmpeg = ffmpeg;
    this.ffprobe = ffprobe;
    Files.createDirectories(root);
    Files.createDirectories(workRoot);
  }

  public Path path(String key) {
    if (!key.matches("[a-f0-9-]+\\.webm"))
      throw new ApiError(404, Messages.text("mediaServicePathText01"));
    Path p = root.resolve(key).normalize();
    if (!p.startsWith(root)) throw new ApiError(404, Messages.text("mediaServicePathText01"));
    return p;
  }

  public record Saved(UUID asset, String key, double duration) {}

  private Path workingPath(String key) {
    path(key);
    return workRoot.resolve(key);
  }

  private void persist(Path workingFile, String key, UUID asset) throws IOException {
    if (databaseStorage) {
      db.exec("INSERT INTO media_asset_contents(asset_id,content) VALUES (?,?)", asset, Files.readAllBytes(workingFile));
      return;
    }
    Path stored = path(key);
    if (!workingFile.equals(stored)) Files.copy(workingFile, stored);
  }

  private void release(Path workingFile) {
    if (databaseStorage || !workRoot.equals(root)) delete(workingFile);
  }

  @Transactional
  public Saved save(UUID owner, MultipartFile file) {
    if (file.isEmpty() || file.getSize() > 30 * 1024 * 1024)
      throw new ApiError(400, Messages.text("mediaServiceSaveText03"));
    String key = UUID.randomUUID() + ".webm";
    Path destination = workingPath(key);
    try {
      boolean mp4;
      try (InputStream input = file.getInputStream()) {
        byte[] magic = input.readNBytes(12);
        boolean webm =
            magic.length >= 4
                && magic[0] == (byte) 0x1A
                && magic[1] == (byte) 0x45
                && magic[2] == (byte) 0xDF
                && magic[3] == (byte) 0xA3;
        mp4 =
            magic.length >= 8
                && new String(magic, 4, 4, java.nio.charset.StandardCharsets.US_ASCII)
                    .equals("ftyp");
        if (!webm && !mp4) throw new ApiError(400, Messages.text("mediaServiceSaveText04"));
      }
      if (mp4) {
        Path original = workRoot.resolve(UUID.randomUUID() + ".mp4");
        try {
          file.transferTo(original);
          run(
              List.of(
                  ffmpeg,
                  "-v",
                  "error",
                  "-y",
                  "-i",
                  original.toString(),
                  "-c:v",
                  "libvpx-vp9",
                  "-deadline",
                  "realtime",
                  "-cpu-used",
                  "8",
                  "-c:a",
                  "libopus",
                  destination.toString()),
              45);
        } finally {
          delete(original);
        }
      } else file.transferTo(destination);
      double duration = probe(destination);
      if (duration <= 0 || duration > 65)
        throw new ApiError(400, Messages.text("mediaServiceSaveText05"));
      UUID asset =
          db.insert(
              "INSERT INTO media_assets(owner_id,storage_key,mime_type,size_bytes,duration_seconds)"
                  + " VALUES (?,?,'video/webm',?,?) RETURNING id",
              owner,
              key,
              Files.size(destination),
              duration);
      // Persist completed bytes transactionally, independently of the host's ephemeral filesystem.
      persist(destination, key, asset);
      return new Saved(asset, key, duration);
    } catch (ApiError e) {
      delete(destination);
      delete(path(key));
      throw e;
    } catch (Exception e) {
      delete(destination);
      delete(path(key));
      throw new ApiError(503, Messages.text("mediaServiceSaveText06"));
    } finally {
      release(destination);
    }
  }

  private double probe(Path p) throws Exception {
    String output =
        run(
            List.of(
                ffprobe,
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                p.toString()),
            15);
    // MediaRecorder may omit duration; ffmpeg remux repairs timestamps/metadata.
    if (output.strip().equals("N/A") || output.isBlank()) {
      Path fixed = workingPath(UUID.randomUUID() + ".webm");
      try {
        run(
            List.of(
                ffmpeg, "-v", "error", "-y", "-i", p.toString(), "-c", "copy", fixed.toString()),
            30);
        Files.move(fixed, p, StandardCopyOption.REPLACE_EXISTING);
        output =
            run(
                List.of(
                    ffprobe,
                    "-v",
                    "error",
                    "-show_entries",
                    "format=duration",
                    "-of",
                    "default=noprint_wrappers=1:nokey=1",
                    p.toString()),
                15);
      } finally {
        delete(fixed);
      }
    }
    double d = Double.parseDouble(output.strip());
    if (!Double.isFinite(d)) throw new IllegalArgumentException();
    return d;
  }

  @Transactional
  public Saved trim(UUID owner, String sourceKey, double start, double end) {
    String key = UUID.randomUUID() + ".webm";
    Path dest = workingPath(key);
    try {
      run(
          List.of(
              ffmpeg,
              "-v",
              "error",
              "-y",
              "-ss",
              Double.toString(start),
              "-i",
              materialize(sourceKey).toString(),
              "-t",
              Double.toString(end - start),
              "-c:v",
              "libvpx-vp9",
              "-deadline",
              "realtime",
              "-cpu-used",
              "8",
              "-c:a",
              "libopus",
              dest.toString()),
          45);
      double d = probe(dest);
      UUID id =
          db.insert(
              "INSERT INTO media_assets(owner_id,storage_key,mime_type,size_bytes,duration_seconds)"
                  + " VALUES (?,?,'video/webm',?,?) RETURNING id",
              owner,
              dest.getFileName().toString(),
              Files.size(dest),
              d);
      persist(dest, key, id);
      return new Saved(id, dest.getFileName().toString(), d);
    } catch (Exception e) {
      delete(dest);
      delete(path(key));
      throw new ApiError(503, Messages.text("mediaServiceTrimText07"));
    } finally {
      release(dest);
    }
  }

  private String run(List<String> args, int timeout) throws Exception {
    var process = new ProcessBuilder(args).redirectErrorStream(true).start();
    // Consume output while waiting so a filled pipe cannot deadlock the encoder.
    var output = new ByteArrayOutputStream();
    Thread reader =
        Thread.startVirtualThread(
            () -> {
              try {
                process.getInputStream().transferTo(output);
              } catch (IOException ignored) {
              }
            });
    if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IOException("Timeout");
    }
    reader.join(Duration.ofSeconds(2));
    if (process.exitValue() != 0) throw new IOException("Invalid media");
    return output.toString(java.nio.charset.StandardCharsets.UTF_8);
  }

  private void delete(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException ignored) {
    }
  }

  private synchronized Path materialize(String key) throws IOException {
    Path stored = path(key);
    if (!databaseStorage || Files.exists(stored)) return stored;
    byte[] content = db.jdbc.queryForObject(
        "SELECT b.content FROM media_asset_contents b JOIN media_assets a ON a.id=b.asset_id WHERE a.storage_key=?",
        byte[].class, key);
    if (content == null) throw new IOException("Stored media has no content");
    // Stage in the same directory so readers never observe a partially downloaded file.
    Path staged = Files.createTempFile(root, "download-", ".webm");
    try {
      Files.write(staged, content);
      Files.move(staged, stored, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally { delete(staged); }
    return stored;
  }

  public Path authorized(UUID asset, UUID user) {
    var row =
        db.one(
            "SELECT a.storage_key,a.owner_id,EXISTS(SELECT 1 FROM clips c WHERE c.asset_id=a.id AND"
                + " c.status='APPROVED') AS published FROM media_assets a WHERE a.id=?",
            asset);
    if (!Objects.equals(row.get("owner_id"), user) && !(boolean) row.get("published"))
      throw new ApiError(403, Messages.text("mediaServiceAuthorizedText08"));
    Path p;
    try { p = materialize(row.get("storage_key").toString()); }
    catch (IOException e) { throw new ApiError(503, Messages.text("mediaServiceLoadFailed")); }
    if (!Files.exists(p)) throw new ApiError(404, Messages.text("mediaServiceAuthorizedText09"));
    return p;
  }
}
