package com.streamguard.core;

import com.streamguard.auth.AuthService;
import com.streamguard.i18n.Messages;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Save independent playable fragments; ownership and a shared storage budget remain authoritative. */
@RestController
@RequestMapping("/api")
public class RecordingController {
  private final Db db; private final PlatformService platform; private final MediaService media; private final long budget;
  public RecordingController(Db db, PlatformService platform, MediaService media, @Value("${app.archive-budget-bytes}") long budget) {
    this.db=db; this.platform=platform; this.media=media; this.budget=budget;
  }
  @GetMapping("/users/me/streams")
  public List<?> history() {
    return db.list("SELECT s.id,s.title,s.description,s.status,s.started_at,s.ended_at,c.name AS channel_name,"
        + "(SELECT count(*) FROM stream_recording_parts p WHERE p.stream_id=s.id) AS recording_parts "
        + "FROM streams s JOIN channels c ON c.id=s.channel_id WHERE c.owner_id=? ORDER BY s.started_at DESC LIMIT 100", AuthService.current());
  }
  @GetMapping("/streams/{stream}/recording")
  public List<?> parts(@PathVariable UUID stream) {
    platform.owner(Db.id(platform.stream(stream).get("channel_id")), AuthService.current());
    return db.list("SELECT asset_id,start_seconds,end_seconds FROM stream_recording_parts WHERE stream_id=? ORDER BY start_seconds", stream);
  }
  @PostMapping(value="/streams/{stream}/recording", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  @Transactional
  public Map<?, ?> upload(@PathVariable UUID stream, @RequestParam int start, @RequestParam int end, @RequestPart MultipartFile file) {
    UUID user=AuthService.current(); var row=platform.stream(stream); platform.owner(Db.id(row.get("channel_id")), user);
    if (start<0 || end<=start || end-start>65 || start>864000 || file.isEmpty()) throw new ApiError(400, Messages.text("operationFailed"));
    if (db.count("SELECT count(*) FROM stream_recording_parts WHERE stream_id=? AND start_seconds=?",stream,start)>0) return Map.of("ok",true);
    // Serialize uploads across users so parallel uploads cannot race the quota check.
    db.jdbc.queryForObject("SELECT pg_advisory_xact_lock(73421891)", Object.class);
    long used=db.count("SELECT coalesce(sum(size_bytes),0) FROM media_assets");
    if (used + file.getSize() > budget) throw new ApiError(507, Messages.text("archiveFull"));
    if ("ENDED".equals(row.get("status")) && db.count("SELECT count(*) FROM streams WHERE id=? AND ended_at<now()-interval '2 minutes'",stream)>0)
      throw new ApiError(409,Messages.text("operationFailed"));
    var saved=media.save(user,file);
    db.exec("INSERT INTO stream_recording_parts(stream_id,asset_id,start_seconds,end_seconds) VALUES (?,?,?,?)",stream,saved.asset(),start,end);
    return Map.of("ok",true);
  }
}
