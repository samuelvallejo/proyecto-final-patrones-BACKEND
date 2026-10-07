package com.streamguard.core;

import com.fasterxml.jackson.databind.*;
import com.streamguard.ai.AiService;
import com.streamguard.i18n.Messages;
import com.streamguard.live.LiveHub;
import com.streamguard.patterns.NotificationBridge;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ClipService {
  private final Db db;
  private final PlatformService platform;
  private final AiService ai;
  private final MediaService media;
  private final LiveHub hub;
  private final NotificationBridge.Delivery delivery;

  public ClipService(
      Db db,
      PlatformService platform,
      AiService ai,
      MediaService media,
      LiveHub hub,
      NotificationBridge.RealtimeDelivery delivery) {
    this.db = db;
    this.platform = platform;
    this.ai = ai;
    this.media = media;
    this.hub = hub;
    this.delivery = delivery;
  }

  @Transactional
  public Map<String, Object> highlight(UUID stream, UUID actor, String source, String reason) {
    var row = platform.stream(stream);
    UUID channel = Db.id(row.get("channel_id"));
    platform.owner(channel, actor);
    if (!row.get("status").equals("LIVE"))
      throw new ApiError(409, Messages.text("clipServiceHighlightText01"));
    db.one("SELECT id FROM streams WHERE id=? FOR UPDATE", stream);
    int cooldown = source.equals("MANUAL") ? 5 : 45;
    if (!source.equals("MANUAL")
        && !(boolean)
            db.one("SELECT auto_clips FROM channel_settings WHERE channel_id=?", channel)
                .get("auto_clips"))
      throw new ApiError(409, Messages.text("clipServiceHighlightText02"));
    if (db.count(
            "SELECT count(*) FROM stream_highlights WHERE stream_id=? AND"
                + " created_at>now()-make_interval(secs => ?)",
            stream,
            cooldown)
        > 0) throw new ApiError(429, Messages.text("clipServiceHighlightText03"));
    UUID id =
        db.insert(
            "INSERT INTO stream_highlights(stream_id,marked_by,source,at_seconds,reason) SELECT"
                + " id,?,?,greatest(0,extract(epoch FROM now()-started_at)::int),? FROM streams"
                + " WHERE id=? RETURNING id",
            actor,
            source,
            reason,
            stream);
    hub.toHost(stream, Map.of("type", "capture", "highlightId", id));
    return db.one("SELECT * FROM stream_highlights WHERE id=?", id);
  }

  public Map<String, Object> upload(
      UUID stream, UUID actor, UUID highlight, int start, int end, MultipartFile file) {
    var s = platform.stream(stream);
    platform.owner(Db.id(s.get("channel_id")), actor);
    db.one("SELECT id FROM stream_highlights WHERE id=? AND stream_id=?", highlight, stream);
    if (start < 0 || end <= start || end - start > 65)
      throw new ApiError(400, Messages.text("clipServiceUploadText04"));
    if (db.count("SELECT count(*) FROM clips WHERE highlight_id=?", highlight) > 0)
      throw new ApiError(409, Messages.text("clipServiceUploadText05"));
    var saved = media.save(actor, file);
    int duration = Math.max(1, (int) Math.ceil(saved.duration()));
    int actualEnd = start + duration;
    db.exec(
        "INSERT INTO recording_segments(stream_id,asset_id,start_seconds,end_seconds) VALUES"
            + " (?,?,?,?)",
        stream,
        saved.asset(),
        start,
        actualEnd);
    var editorial = ai.editorial(stream, ai.context(stream));
    UUID clip =
        db.insert(
            "INSERT INTO"
                + " clips(stream_id,highlight_id,asset_id,title,description,start_seconds,end_seconds)"
                + " VALUES (?,?,?,?,?,?,?) RETURNING id",
            stream,
            highlight,
            saved.asset(),
            editorial.output().path("title").asText(Messages.text("clipServiceUploadText06")),
            editorial.output().path("description").asText(""),
            start,
            actualEnd);
    new NotificationBridge.ClipNotice(delivery)
        .send(actor, Messages.text("clipServiceUploadText07"));
    hub.toModerators(stream, Map.of("type", "clips-updated"));
    return db.one("SELECT * FROM clips WHERE id=?", clip);
  }

  @Transactional
  public void review(UUID clip, UUID actor, boolean approve) {
    var row =
        db.one(
            "SELECT c.*,s.channel_id FROM clips c JOIN streams s ON s.id=c.stream_id WHERE c.id=?"
                + " FOR UPDATE OF c",
            clip);
    platform.owner(Db.id(row.get("channel_id")), actor);
    if (row.get("asset_id") == null)
      throw new ApiError(409, Messages.text("clipServiceReviewText08"));
    db.exec("UPDATE clips SET status=? WHERE id=?", approve ? "APPROVED" : "REJECTED", clip);
    db.exec(
        "INSERT INTO clip_reviews(clip_id,reviewer_id,decision) VALUES (?,?,?)",
        clip,
        actor,
        approve ? "APPROVED" : "REJECTED");
  }

  @Transactional
  public Map<String, Object> edit(
      UUID clip, UUID actor, String title, String description, double start, double end) {
    var row =
        db.one(
            "SELECT c.*,s.channel_id,a.storage_key,a.duration_seconds FROM clips c JOIN streams s"
                + " ON s.id=c.stream_id JOIN media_assets a ON a.id=c.asset_id WHERE c.id=? FOR"
                + " UPDATE OF c",
            clip);
    platform.owner(Db.id(row.get("channel_id")), actor);
    double duration = ((Number) row.get("duration_seconds")).doubleValue();
    if (!Double.isFinite(start)
        || !Double.isFinite(end)
        || start < 0
        || end <= start
        || end > duration + .1) throw new ApiError(400, Messages.text("clipServiceEditText09"));
    UUID asset = Db.id(row.get("asset_id"));
    int absoluteStart = ((Number) row.get("start_seconds")).intValue();
    if (start > .1 || end < duration - .1)
      asset = media.trim(actor, row.get("storage_key").toString(), start, end).asset();
    db.exec(
        "INSERT INTO"
            + " clip_versions(clip_id,asset_id,editor_id,title,description,start_seconds,end_seconds)"
            + " VALUES (?,?,?,?,?,?,?)",
        clip,
        row.get("asset_id"),
        actor,
        row.get("title"),
        row.get("description"),
        row.get("start_seconds"),
        row.get("end_seconds"));
    db.exec(
        "UPDATE clips SET"
            + " asset_id=?,title=?,description=?,start_seconds=?,end_seconds=?,status='PENDING'"
            + " WHERE id=?",
        asset,
        title,
        description,
        absoluteStart + (int) start,
        absoluteStart + Math.max((int) start + 1, (int) Math.ceil(end)),
        clip);
    return db.one("SELECT * FROM clips WHERE id=?", clip);
  }

  public Map<String, Object> generateSummary(UUID stream, UUID actor) {
    var s = platform.stream(stream);
    platform.manager(Db.id(s.get("channel_id")), actor);
    var e = ai.editorial(stream, ai.context(stream));
    db.exec(
        "INSERT INTO stream_summaries(stream_id,request_id,content) VALUES (?,?,?)",
        stream,
        e.requestId(),
        e.output().path("summary").asText());
    db.exec("DELETE FROM stream_topics WHERE stream_id=?", stream);
    db.exec("DELETE FROM chat_faqs WHERE stream_id=?", stream);
    int count = 0;
    for (JsonNode topic : e.output().path("topics")) {
      if (++count > 8) break;
      String label = topic.asText();
      if (!label.isBlank() && label.length() <= 120)
        db.exec(
            "INSERT INTO stream_topics(stream_id,label,relevance) VALUES (?,?,0.8) ON CONFLICT DO"
                + " NOTHING",
            stream,
            label);
    }
    count = 0;
    for (JsonNode faq : e.output().path("faqs")) {
      if (++count > 8) break;
      if (faq.path("question").isTextual() && faq.path("answer").isTextual())
        db.exec(
            "INSERT INTO chat_faqs(stream_id,question,answer,request_id) VALUES (?,?,?,?)",
            stream,
            faq.path("question").asText(),
            faq.path("answer").asText(),
            e.requestId());
    }
    return Map.of("provider", e.provider(), "analysis", e.output());
  }

  public void subtitle(UUID stream, UUID actor, double start, double end, String text) {
    var s = platform.stream(stream);
    platform.owner(Db.id(s.get("channel_id")), actor);
    if (!s.get("status").equals("LIVE"))
      throw new ApiError(409, Messages.text("clipServiceHighlightText01"));
    UUID transcript =
        db.optional(
                "SELECT id FROM transcripts WHERE stream_id=? AND source='BROWSER' LIMIT 1", stream)
            .map(x -> Db.id(x.get("id")))
            .orElseGet(
                () ->
                    db.insert(
                        "INSERT INTO transcripts(stream_id,source) VALUES (?,'BROWSER') RETURNING"
                            + " id",
                        stream));
    db.exec(
        "INSERT INTO subtitle_cues(transcript_id,start_seconds,end_seconds,text) VALUES (?,?,?,?)",
        transcript,
        start,
        end,
        text);
    hub.broadcast(stream, Map.of("type", "subtitle", "text", text));
  }
}
