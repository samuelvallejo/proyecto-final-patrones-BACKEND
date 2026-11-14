package com.streamguard.core;

import com.streamguard.auth.AuthService;
import com.streamguard.i18n.Messages;
import com.streamguard.live.LiveHub;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Data for the home dashboard: followed channels, featured channels, popular categories and
 * upcoming events. Every list is bounded and returns only public channel information.
 */
@RestController
@Validated
@RequestMapping("/api")
public class DiscoveryController {
  static final int MAX_FUTURE_EVENTS_PER_CHANNEL = 20;
  private final Db db;
  private final LiveHub hub;

  public DiscoveryController(Db db, LiveHub hub) {
    this.db = db;
    this.hub = hub;
  }

  public record NewEvent(
      @NotBlank @Size(max = 140) String title,
      @NotNull OffsetDateTime startsAt,
      UUID categoryId) {}

  /** Channels the current user follows, live ones first, with their current audience. */
  @GetMapping("/discover/following")
  public List<Map<String, Object>> following() {
    var rows =
        db.list(
            "SELECT c.id AS channel_id,c.name,c.slug,s.id AS stream_id,s.title,cat.name AS category"
                + " FROM channel_follows f JOIN channels c ON c.id=f.channel_id LEFT JOIN streams s"
                + " ON s.channel_id=c.id AND s.status='LIVE' LEFT JOIN categories cat ON"
                + " cat.id=s.category_id WHERE f.user_id=? ORDER BY (s.id IS NULL),c.name LIMIT 12",
            AuthService.current());
    return withViewers(rows);
  }

  /** Most followed channels, live ones first, flagged when the current user follows them. */
  @GetMapping("/discover/featured")
  public List<Map<String, Object>> featured() {
    var rows =
        db.list(
            "SELECT c.id AS channel_id,c.name,c.slug,s.id AS stream_id,s.title,"
                + " (SELECT count(*) FROM channel_follows f WHERE f.channel_id=c.id) AS followers,"
                + " EXISTS(SELECT 1 FROM channel_follows f WHERE f.channel_id=c.id AND f.user_id=?)"
                + " AS following,c.owner_id=? AS mine FROM channels c LEFT JOIN streams s ON"
                + " s.channel_id=c.id AND s.status='LIVE' ORDER BY (s.id IS NULL),followers DESC,"
                + " c.created_at DESC LIMIT 6",
            AuthService.current(),
            AuthService.current());
    return withViewers(rows);
  }

  /** Every category with how many streams are live in it and their combined audience. */
  @GetMapping("/discover/categories")
  public List<Map<String, Object>> categories() {
    var rows =
        db.list(
            "SELECT cat.id,cat.name,cat.slug FROM categories cat ORDER BY cat.name");
    var live =
        db.list(
            "SELECT s.id,cat.slug FROM streams s JOIN categories cat ON cat.id=s.category_id WHERE"
                + " s.status='LIVE'");
    for (var row : rows) {
      int streams = 0, viewers = 0;
      for (var stream : live)
        if (Objects.equals(stream.get("slug"), row.get("slug"))) {
          streams++;
          viewers += hub.viewers(Db.id(stream.get("id")));
        }
      row.put("live_streams", streams);
      row.put("viewers", viewers);
    }
    rows.sort(
        Comparator.comparingInt((Map<String, Object> r) -> (int) r.get("viewers"))
            .thenComparingInt(r -> (int) r.get("live_streams"))
            .reversed());
    return rows;
  }

  /** Events that start soon or began within the last two hours. */
  @GetMapping("/events/upcoming")
  public List<Map<String, Object>> upcoming() {
    return db.list(
        "SELECT e.id,e.title,e.starts_at,c.id AS channel_id,c.name AS channel_name,cat.name AS"
            + " category,cat.slug AS category_slug,c.owner_id=? AS mine FROM scheduled_events e JOIN channels c ON"
            + " c.id=e.channel_id LEFT JOIN categories cat ON cat.id=e.category_id WHERE"
            + " e.starts_at > now() - interval '2 hours' ORDER BY e.starts_at LIMIT 8",
        AuthService.current());
  }

  /** Announce an upcoming broadcast on the caller's own channel. */
  @PostMapping("/events")
  public Map<String, Object> create(@Valid @RequestBody NewEvent event) {
    UUID user = AuthService.current();
    var channel =
        db.optional("SELECT id FROM channels WHERE owner_id=?", user)
            .orElseThrow(() -> new ApiError(409, Messages.text("eventChannelRequired")));
    OffsetDateTime now = OffsetDateTime.now();
    if (event.startsAt().isBefore(now.minusMinutes(5)) || event.startsAt().isAfter(now.plus(Duration.ofDays(365))))
      throw new ApiError(400, Messages.text("eventInvalidTime"));
    UUID channelId = Db.id(channel.get("id"));
    if (db.count(
            "SELECT count(*) FROM scheduled_events WHERE channel_id=? AND starts_at > now()",
            channelId)
        >= MAX_FUTURE_EVENTS_PER_CHANNEL) throw new ApiError(429, Messages.text("eventLimit"));
    if (event.categoryId() != null
        && db.count("SELECT count(*) FROM categories WHERE id=?", event.categoryId()) == 0)
      throw new ApiError(400, Messages.text("eventInvalidCategory"));
    UUID id =
        db.insert(
            "INSERT INTO scheduled_events(channel_id,category_id,title,starts_at) VALUES"
                + " (?,?,?,?) RETURNING id",
            channelId,
            event.categoryId(),
            event.title().strip(),
            event.startsAt());
    return Map.of("id", id);
  }

  /** Only the channel owner can cancel an event. */
  @DeleteMapping("/events/{id}")
  public Map<String, Object> delete(@PathVariable UUID id) {
    int removed =
        db.exec(
            "DELETE FROM scheduled_events e USING channels c WHERE e.id=? AND c.id=e.channel_id"
                + " AND c.owner_id=?",
            id,
            AuthService.current());
    if (removed == 0) throw new ApiError(404, Messages.text("eventNotFound"));
    return Map.of("ok", true);
  }

  private List<Map<String, Object>> withViewers(List<Map<String, Object>> rows) {
    rows.forEach(
        r -> r.put("viewers", r.get("stream_id") == null ? 0 : hub.viewers(Db.id(r.get("stream_id")))));
    return rows;
  }
}
