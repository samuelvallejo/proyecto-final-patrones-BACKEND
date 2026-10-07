package com.streamguard.core;

import com.streamguard.ai.AiService;
import com.streamguard.auth.AuthService;
import com.streamguard.i18n.Messages;
import com.streamguard.patterns.ModerationPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class PlatformController {
  private final PlatformService platform;
  private final ChatService chat;
  private final ClipService clips;
  private final MediaService media;
  private final Db db;
  private final AiService ai;

  @Value("${app.max-viewers}")
  private int maxViewers;

  @Value("${app.turn-url}")
  private String turnUrl;

  @Value("${app.turn-user}")
  private String turnUser;

  @Value("${app.turn-password}")
  private String turnPassword;

  public PlatformController(
      PlatformService platform,
      ChatService chat,
      ClipService clips,
      MediaService media,
      Db db,
      AiService ai) {
    this.platform = platform;
    this.chat = chat;
    this.clips = clips;
    this.media = media;
    this.db = db;
    this.ai = ai;
  }

  public record Channel(
      @NotBlank @Size(max = 80) String name,
      @NotNull @Pattern(regexp = "[a-z0-9-]{3,40}") String slug,
      @NotNull @Size(max = 2000) String description) {}

  public record Start(
      @NotBlank @Size(max = 140) String title,
      @NotNull @Size(max = 2000) String description,
      UUID categoryId) {}

  public record Message(@NotBlank @Size(max = 1000) String content) {}

  public record Decision(boolean approve) {}

  public record Policy(
      @NotNull @Pattern(regexp = "RELAXED|BALANCED|STRICT") String level,
      boolean autoHide,
      boolean autoMute,
      @Min(30) @Max(86400) int muteSeconds,
      @DecimalMin("0") @DecimalMax("1") double reviewThreshold,
      @DecimalMin("0") @DecimalMax("1") double blockThreshold,
      @NotNull @Size(max = 50) List<@NotBlank @Size(max = 80) String> blockedWords,
      @NotNull @Size(max = 30) List<@NotBlank @Size(max = 120) String> blockedTopics,
      boolean allowLinks,
      @Min(0) @Max(120) int slowMode,
      boolean autoClips) {}

  public record Sanction(
      @NotNull UUID userId,
      @NotNull @Pattern(regexp = "MUTE|BAN") String type,
      @Min(30) @Max(86400) int seconds,
      @NotBlank @Size(max = 500) String reason) {}

  public record Marker(
      @NotNull @Pattern(regexp = "MANUAL|AUDIO_PEAK") String source,
      @NotBlank @Size(max = 500) String reason) {}

  public record EditClip(
      @NotBlank @Size(max = 140) String title,
      @NotNull @Size(max = 2000) String description,
      @DecimalMin("0") double start,
      @DecimalMin("0") double end) {}

  public record Subtitle(
      @DecimalMin("0") double start,
      @DecimalMin("0") double end,
      @NotBlank @Size(max = 1000) String text) {}

  public record Moderator(@NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,32}") String username) {}

  @GetMapping("/config")
  public Map<String, Object> config() {
    List<Map<String, Object>> servers = new ArrayList<>();
    servers.add(Map.of("urls", "stun:stun.l.google.com:19302"));
    if (!turnUrl.isBlank())
      servers.add(Map.of("urls", turnUrl, "username", turnUser, "credential", turnPassword));
    return Map.of(
        "geminiConfigured",
        ai.provider().equals("GEMINI") && ai.configured(),
        "aiConfigured",
        ai.configured(),
        "aiModel",
        ai.model(),
        "aiMode",
        ai.provider(),
        "maxViewers",
        maxViewers,
        "mediaRelayConfigured",
        true,
        "iceServers",
        servers,
        "turnConfigured",
        !turnUrl.isBlank());
  }

  @GetMapping("/categories")
  public List<?> categories() {
    return db.list("SELECT * FROM categories ORDER BY name");
  }

  @GetMapping("/explore")
  public List<?> explore(
      @RequestParam(defaultValue = "") @Size(max = 100) String q,
      @RequestParam(defaultValue = "") String category) {
    return platform.explore(q, category);
  }

  @PostMapping("/channels")
  public Map<?, ?> channel(@Valid @RequestBody Channel c) {
    return platform.createChannel(AuthService.current(), c.name(), c.slug(), c.description());
  }

  @GetMapping("/channels/mine")
  public List<?> mine() {
    UUID user = AuthService.current();
    return db.list(
        "SELECT c.*,c.owner_id=? AS is_owner FROM channels c WHERE c.owner_id=? OR EXISTS(SELECT 1"
            + " FROM channel_moderators m WHERE m.channel_id=c.id AND m.user_id=?)",
        user,
        user,
        user);
  }

  @GetMapping("/channels/{channel}/dashboard")
  public Map<?, ?> dashboard(@PathVariable UUID channel) {
    return platform.dashboard(AuthService.current(), channel);
  }

  @PutMapping("/channels/{channel}/policy")
  public Map<?, ?> policy(@PathVariable UUID channel, @Valid @RequestBody Policy p) {
    var policy =
        new ModerationPolicy.Builder()
            .level(p.level())
            .autoHide(p.autoHide())
            .autoMute(p.autoMute())
            .muteSeconds(p.muteSeconds())
            .thresholds(p.reviewThreshold(), p.blockThreshold())
            .blockedWords(p.blockedWords())
            .blockedTopics(p.blockedTopics())
            .allowLinks(p.allowLinks())
            .build();
    platform.savePolicy(channel, AuthService.current(), policy, p.slowMode(), p.autoClips());
    return Map.of("ok", true);
  }

  @PostMapping("/channels/{channel}/moderators")
  public Map<?, ?> moderator(@PathVariable UUID channel, @Valid @RequestBody Moderator m) {
    UUID actor = AuthService.current();
    platform.owner(channel, actor);
    UUID user =
        Db.id(
            db.one("SELECT id FROM users WHERE username=?", m.username().toLowerCase(Locale.ROOT))
                .get("id"));
    db.exec(
        "INSERT INTO channel_moderators(channel_id,user_id,added_by) VALUES (?,?,?) ON CONFLICT DO"
            + " NOTHING",
        channel,
        user,
        actor);
    return Map.of("ok", true);
  }

  @DeleteMapping("/channels/{channel}/moderators/{user}")
  public Map<?, ?> removeModerator(@PathVariable UUID channel, @PathVariable UUID user) {
    platform.owner(channel, AuthService.current());
    db.exec("DELETE FROM channel_moderators WHERE channel_id=? AND user_id=?", channel, user);
    return Map.of("ok", true);
  }

  @PostMapping("/channels/{channel}/follow")
  public Map<?, ?> follow(@PathVariable UUID channel) {
    db.exec(
        "INSERT INTO channel_follows(channel_id,user_id) VALUES (?,?) ON CONFLICT DO NOTHING",
        channel,
        AuthService.current());
    return Map.of("ok", true);
  }

  @DeleteMapping("/channels/{channel}/follow")
  public Map<?, ?> unfollow(@PathVariable UUID channel) {
    db.exec(
        "DELETE FROM channel_follows WHERE channel_id=? AND user_id=?",
        channel,
        AuthService.current());
    return Map.of("ok", true);
  }

  @PostMapping("/streams")
  public Map<?, ?> start(@Valid @RequestBody Start s) {
    return platform.start(AuthService.current(), s.title(), s.description(), s.categoryId());
  }

  @GetMapping("/streams/{stream}")
  public Map<?, ?> stream(@PathVariable UUID stream) {
    return platform.stream(stream);
  }

  @PostMapping("/streams/{stream}/end")
  public Map<?, ?> end(@PathVariable UUID stream) {
    platform.end(stream, AuthService.current());
    return Map.of("ok", true);
  }

  @GetMapping("/streams/{stream}/messages")
  public List<?> messages(@PathVariable UUID stream) {
    return chat.messages(stream);
  }

  @PostMapping("/streams/{stream}/messages")
  public Map<?, ?> message(@PathVariable UUID stream, @Valid @RequestBody Message m) {
    return chat.send(stream, AuthService.current(), m.content().strip());
  }

  @PostMapping("/moderation/{queue}/review")
  public Map<?, ?> review(@PathVariable UUID queue, @RequestBody Decision d) {
    chat.review(queue, AuthService.current(), d.approve());
    return Map.of("ok", true);
  }

  @PostMapping("/channels/{channel}/sanctions")
  public Map<?, ?> sanction(@PathVariable UUID channel, @Valid @RequestBody Sanction s) {
    chat.sanction(channel, s.userId(), AuthService.current(), s.type(), s.seconds(), s.reason());
    return Map.of("ok", true);
  }

  @DeleteMapping("/sanctions/{id}")
  public Map<?, ?> revoke(@PathVariable UUID id) {
    chat.revoke(id, AuthService.current());
    return Map.of("ok", true);
  }

  @PostMapping("/streams/{stream}/highlights")
  public Map<?, ?> highlight(@PathVariable UUID stream, @Valid @RequestBody Marker m) {
    return clips.highlight(stream, AuthService.current(), m.source(), m.reason());
  }

  @PostMapping(value = "/streams/{stream}/segments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Map<?, ?> upload(
      @PathVariable UUID stream,
      @RequestParam UUID highlightId,
      @RequestParam int start,
      @RequestParam int end,
      @RequestPart MultipartFile file) {
    return clips.upload(stream, AuthService.current(), highlightId, start, end, file);
  }

  @PostMapping("/clips/{clip}/review")
  public Map<?, ?> clipReview(@PathVariable UUID clip, @RequestBody Decision d) {
    clips.review(clip, AuthService.current(), d.approve());
    return Map.of("ok", true);
  }

  @PutMapping("/clips/{clip}")
  public Map<?, ?> edit(@PathVariable UUID clip, @Valid @RequestBody EditClip e) {
    return clips.edit(clip, AuthService.current(), e.title(), e.description(), e.start(), e.end());
  }

  @GetMapping("/clips/public")
  public List<?> publicClips() {
    return db.list(
        "SELECT c.*,ch.name AS channel_name,ch.slug FROM clips c JOIN streams s ON s.id=c.stream_id"
            + " JOIN channels ch ON ch.id=s.channel_id WHERE c.status='APPROVED' ORDER BY"
            + " c.created_at DESC LIMIT 50");
  }

  @GetMapping("/media/{asset}")
  public ResponseEntity<FileSystemResource> file(@PathVariable UUID asset) {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    UUID user =
        authentication != null && authentication.getPrincipal() instanceof UUID u ? u : null;
    var path = media.authorized(asset, user);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("video/webm"))
        .header("Cache-Control", "private, no-store")
        .body(new FileSystemResource(path));
  }

  @PostMapping("/streams/{stream}/summary")
  public Map<?, ?> summary(@PathVariable UUID stream) {
    return clips.generateSummary(stream, AuthService.current());
  }

  @PostMapping("/streams/{stream}/subtitles")
  public Map<?, ?> subtitle(@PathVariable UUID stream, @Valid @RequestBody Subtitle s) {
    if (!Double.isFinite(s.start())
        || !Double.isFinite(s.end())
        || s.end() <= s.start()
        || s.end() - s.start() > 60)
      throw new ApiError(400, Messages.text("platformControllerSubtitleText01"));
    clips.subtitle(stream, AuthService.current(), s.start(), s.end(), s.text());
    return Map.of("ok", true);
  }

  @GetMapping("/notifications")
  public List<?> notifications() {
    return db.list(
        "SELECT * FROM notifications WHERE user_id=? ORDER BY created_at DESC LIMIT 30",
        AuthService.current());
  }

  @PostMapping("/notifications/{id}/read")
  public Map<?, ?> read(@PathVariable UUID id) {
    db.exec(
        "UPDATE notifications SET read_at=now() WHERE id=? AND user_id=?",
        id,
        AuthService.current());
    return Map.of("ok", true);
  }

  @GetMapping("/streams/{stream}/analytics")
  public List<?> analytics(@PathVariable UUID stream) {
    platform.manager(Db.id(platform.stream(stream).get("channel_id")), AuthService.current());
    return db.list(
        "SELECT * FROM stream_analytics WHERE stream_id=? ORDER BY bucket_at LIMIT 500", stream);
  }
}
