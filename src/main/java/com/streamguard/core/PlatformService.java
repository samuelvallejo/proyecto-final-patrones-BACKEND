package com.streamguard.core;

import com.streamguard.i18n.Messages;
import com.streamguard.live.LiveHub;
import com.streamguard.patterns.ModerationPolicy;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlatformService {
  public final Db db;
  private final LiveHub hub;

  public PlatformService(Db db, LiveHub hub) {
    this.db = db;
    this.hub = hub;
  }

  public UUID ownChannel(UUID user) {
    return Db.id(db.one("SELECT id FROM channels WHERE owner_id=?", user).get("id"));
  }

  public Map<String, Object> stream(UUID id) {
    var row =
        db.one(
            "SELECT s.*,c.name AS channel_name,c.slug,c.owner_id,p.display_name,cat.name AS"
                + " category FROM streams s JOIN channels c ON c.id=s.channel_id JOIN user_profiles"
                + " p ON p.user_id=c.owner_id LEFT JOIN categories cat ON cat.id=s.category_id"
                + " WHERE s.id=?",
            id);
    row.put("viewers", hub.viewers(id));
    return row;
  }

  public void owner(UUID channel, UUID user) {
    if (db.count("SELECT count(*) FROM channels WHERE id=? AND owner_id=?", channel, user) == 0)
      throw new ApiError(403, Messages.text("platformServiceOwnerText01"));
  }

  public void manager(UUID channel, UUID user) {
    if (db.count(
            "SELECT count(*) FROM channels c WHERE c.id=? AND (c.owner_id=? OR EXISTS(SELECT 1 FROM"
                + " channel_moderators m WHERE m.channel_id=c.id AND m.user_id=?))",
            channel,
            user,
            user)
        == 0) throw new ApiError(403, Messages.text("platformServiceManagerText02"));
  }

  @Transactional
  public Map<String, Object> createChannel(
      UUID user, String name, String slug, String description) {
    UUID id =
        db.insert(
            "INSERT INTO channels(owner_id,name,slug,description) VALUES (?,?,?,?) RETURNING id",
            user,
            name,
            slug,
            description);
    db.exec("INSERT INTO channel_settings(channel_id) VALUES (?)", id);
    db.exec("INSERT INTO moderation_policies(channel_id) VALUES (?)", id);
    db.exec(
        "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name='STREAMER' ON"
            + " CONFLICT DO NOTHING",
        user);
    return db.one("SELECT * FROM channels WHERE id=?", id);
  }

  @Transactional
  public Map<String, Object> start(UUID user, String title, String description, UUID category) {
    UUID channel = ownChannel(user);
    UUID id =
        db.insert(
            "INSERT INTO streams(channel_id,title,description,category_id) VALUES (?,?,?,?)"
                + " RETURNING id",
            channel,
            title,
            description,
            category);
    db.exec("INSERT INTO chat_rooms(stream_id) VALUES (?)", id);
    db.exec(
        "INSERT INTO stream_events(stream_id,actor_id,event_type) VALUES (?,?,'START')", id, user);
    return stream(id);
  }

  public void end(UUID stream, UUID user) {
    var row = stream(stream);
    owner(Db.id(row.get("channel_id")), user);
    db.exec(
        "UPDATE streams SET status='ENDED',ended_at=now() WHERE id=? AND status='LIVE'", stream);
    hub.broadcast(
        stream, Map.of("type", "ended", "message", Messages.text("clipServiceHighlightText01")));
  }

  public List<Map<String, Object>> explore(String search, String category) {
    var rows =
        db.list(
            "SELECT s.id,s.title,s.status,s.started_at,c.name AS"
                + " channel_name,c.slug,p.display_name,cat.name AS category FROM streams s JOIN"
                + " channels c ON c.id=s.channel_id JOIN user_profiles p ON p.user_id=c.owner_id"
                + " LEFT JOIN categories cat ON cat.id=s.category_id WHERE s.status='LIVE' AND"
                + " (s.title ILIKE ? OR c.name ILIKE ?) AND (?='' OR cat.slug=?) ORDER BY"
                + " s.started_at DESC LIMIT 50",
            "%" + search + "%",
            "%" + search + "%",
            category,
            category);
    rows.forEach(r -> r.put("viewers", hub.viewers(Db.id(r.get("id")))));
    return rows;
  }

  public ModerationPolicy policy(UUID channel) {
    var p =
        db.one(
            "SELECT p.*,s.allow_links FROM moderation_policies p JOIN channel_settings s ON"
                + " s.channel_id=p.channel_id WHERE p.channel_id=?",
            channel);
    return new ModerationPolicy.Builder()
        .level(p.get("level").toString())
        .autoHide((boolean) p.get("auto_hide"))
        .autoMute((boolean) p.get("auto_mute"))
        .muteSeconds((int) p.get("mute_seconds"))
        .thresholds(
            ((Number) p.get("review_threshold")).doubleValue(),
            ((Number) p.get("block_threshold")).doubleValue())
        .allowLinks((boolean) p.get("allow_links"))
        .blockedWords(
            db.list("SELECT word FROM blocked_words WHERE channel_id=?", channel).stream()
                .map(x -> x.get("word").toString())
                .toList())
        .blockedTopics(
            db.list("SELECT topic FROM blocked_topics WHERE channel_id=?", channel).stream()
                .map(x -> x.get("topic").toString())
                .toList())
        .build();
  }

  @Transactional
  public void savePolicy(
      UUID channel, UUID user, ModerationPolicy p, int slowMode, boolean autoClips) {
    owner(channel, user);
    if (slowMode < 0 || slowMode > 120)
      throw new ApiError(400, Messages.text("platformServiceSavePolicyText04"));
    db.exec(
        "UPDATE moderation_policies SET"
            + " level=?,auto_hide=?,auto_mute=?,mute_seconds=?,review_threshold=?,block_threshold=?"
            + " WHERE channel_id=?",
        p.level(),
        p.autoHide(),
        p.autoMute(),
        p.muteSeconds(),
        p.reviewThreshold(),
        p.blockThreshold(),
        channel);
    db.exec(
        "UPDATE channel_settings SET allow_links=?,slow_mode_seconds=?,auto_clips=? WHERE"
            + " channel_id=?",
        p.allowLinks(),
        slowMode,
        autoClips,
        channel);
    db.exec("DELETE FROM blocked_words WHERE channel_id=?", channel);
    db.exec("DELETE FROM blocked_topics WHERE channel_id=?", channel);
    for (String word : p.blockedWords()) {
      if (word.isBlank() || word.length() > 80)
        throw new ApiError(400, Messages.text("platformServiceSavePolicyText05"));
      db.exec(
          "INSERT INTO blocked_words(channel_id,word) VALUES (?,?) ON CONFLICT DO NOTHING",
          channel,
          word.strip());
    }
    for (String topic : p.blockedTopics()) {
      if (topic.isBlank() || topic.length() > 120)
        throw new ApiError(400, Messages.text("platformServiceSavePolicyText06"));
      db.exec(
          "INSERT INTO blocked_topics(channel_id,topic) VALUES (?,?) ON CONFLICT DO NOTHING",
          channel,
          topic.strip());
    }
    db.exec(
        "INSERT INTO audit_logs(actor_id,entity_type,entity_id,action) VALUES"
            + " (?,'MODERATION_POLICY',?,'UPDATE')",
        user,
        channel);
  }

  public Map<String, Object> dashboard(UUID user, UUID channel) {
    manager(channel, user);
    var s =
        db.optional(
            "SELECT * FROM streams WHERE channel_id=? ORDER BY started_at DESC LIMIT 1", channel);
    Map<String, Object> out = new HashMap<>();
    out.put("channel", db.one("SELECT * FROM channels WHERE id=?", channel));
    out.put("stream", s.orElse(null));
    out.put("policy", policy(channel));
    out.put("settings", db.one("SELECT * FROM channel_settings WHERE channel_id=?", channel));
    out.put(
        "stats",
        db.one(
            "SELECT (SELECT count(*) FROM channel_follows WHERE channel_id=?) AS followers,(SELECT"
                + " count(*) FROM chat_messages m JOIN chat_rooms r ON r.id=m.room_id JOIN streams"
                + " s ON s.id=r.stream_id WHERE s.channel_id=?) AS messages,(SELECT count(*) FROM"
                + " chat_messages m JOIN chat_rooms r ON r.id=m.room_id JOIN streams s ON"
                + " s.id=r.stream_id WHERE s.channel_id=? AND m.status IN ('HIDDEN','REVIEW')) AS"
                + " blocked,(SELECT count(*) FROM clips c JOIN streams s ON s.id=c.stream_id WHERE"
                + " s.channel_id=? AND c.status='PENDING') AS pending_clips",
            channel,
            channel,
            channel,
            channel));
    out.put(
        "queue",
        db.list(
            "SELECT q.id,q.status AS queue_status,m.id AS"
                + " message_id,m.content,m.status,m.user_id,u.username,mc.category,mc.confidence,mc.reason,mc.provider,m.created_at"
                + " FROM moderation_queue q JOIN chat_messages m ON m.id=q.message_id JOIN"
                + " chat_rooms r ON r.id=m.room_id JOIN streams s ON s.id=r.stream_id JOIN users u"
                + " ON u.id=m.user_id LEFT JOIN message_classifications mc ON mc.message_id=m.id"
                + " WHERE s.channel_id=? AND q.status='PENDING' ORDER BY q.created_at DESC LIMIT"
                + " 100",
            channel));
    out.put(
        "sanctions",
        db.list(
            "SELECT s.*,u.username FROM user_sanctions s JOIN users u ON u.id=s.user_id WHERE"
                + " s.channel_id=? AND s.revoked_at IS NULL AND (s.expires_at IS NULL OR"
                + " s.expires_at>now()) ORDER BY s.created_at DESC LIMIT 100",
            channel));
    out.put(
        "clips",
        db.list(
            "SELECT c.*,s.title AS stream_title FROM clips c JOIN streams s ON s.id=c.stream_id"
                + " WHERE s.channel_id=? ORDER BY c.created_at DESC LIMIT 100",
            channel));
    out.put(
        "moderators",
        db.list(
            "SELECT m.user_id,u.username FROM channel_moderators m JOIN users u ON u.id=m.user_id"
                + " WHERE m.channel_id=?",
            channel));
    out.put(
        "ai",
        s.isEmpty()
            ? Map.of()
            : Map.of(
                "summaries",
                db.list(
                    "SELECT * FROM stream_summaries WHERE stream_id=? ORDER BY created_at DESC"
                        + " LIMIT 1",
                    s.get().get("id")),
                "topics",
                db.list("SELECT label FROM stream_topics WHERE stream_id=?", s.get().get("id")),
                "faqs",
                db.list(
                    "SELECT question,answer FROM chat_faqs WHERE stream_id=?", s.get().get("id"))));
    return out;
  }

  @Scheduled(fixedDelay = 60000)
  public void sample() {
    for (var row :
        db.list(
            "SELECT id,started_at<now()-interval '90 seconds' AS stale FROM streams WHERE"
                + " status='LIVE'")) {
      UUID stream = Db.id(row.get("id"));
      if (!hub.hosting(stream) && (boolean) row.get("stale")) {
        db.exec(
            "UPDATE streams SET status='ENDED',ended_at=now() WHERE id=? AND status='LIVE'",
            stream);
        hub.broadcast(
            stream,
            Map.of("type", "ended", "message", Messages.text("platformServiceSampleText07")));
        continue;
      }
      db.exec(
          "INSERT INTO stream_analytics(stream_id,bucket_at,viewers,messages,blocked) SELECT"
              + " ?,date_trunc('minute',now()),?,count(*),count(*) FILTER(WHERE m.status IN"
              + " ('HIDDEN','REVIEW')) FROM chat_messages m JOIN chat_rooms r ON r.id=m.room_id"
              + " WHERE r.stream_id=? AND m.created_at>now()-interval '1 minute' ON"
              + " CONFLICT(stream_id,bucket_at) DO UPDATE SET"
              + " viewers=excluded.viewers,messages=excluded.messages,blocked=excluded.blocked",
          stream,
          hub.viewers(stream),
          stream);
    }
    db.exec("DELETE FROM auth_sessions WHERE expires_at<now()");
  }
}
