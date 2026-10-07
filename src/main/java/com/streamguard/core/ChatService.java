package com.streamguard.core;

import com.streamguard.ai.AiService;
import com.streamguard.i18n.Messages;
import com.streamguard.live.LiveHub;
import com.streamguard.patterns.*;
import com.streamguard.patterns.AiToolkitFactory.Verdict;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatService {
  private final Db db;
  private final PlatformService platform;
  private final AiService ai;
  private final LiveHub hub;
  private final NotificationBridge.Delivery delivery;
  private final TransactionTemplate tx;

  public ChatService(
      Db db,
      PlatformService platform,
      AiService ai,
      LiveHub hub,
      NotificationBridge.RealtimeDelivery delivery,
      PlatformTransactionManager tm) {
    this.db = db;
    this.platform = platform;
    this.ai = ai;
    this.hub = hub;
    this.delivery = delivery;
    this.tx = new TransactionTemplate(tm);
  }

  public List<Map<String, Object>> messages(UUID stream) {
    return db.list(
        "SELECT m.id,m.content,m.created_at,m.user_id,u.username FROM chat_messages m JOIN"
            + " chat_rooms r ON r.id=m.room_id JOIN users u ON u.id=m.user_id WHERE r.stream_id=?"
            + " AND m.status='VISIBLE' ORDER BY m.created_at DESC LIMIT 100",
        stream);
  }

  public Map<String, Object> send(UUID stream, UUID user, String text) {
    var s = platform.stream(stream);
    UUID channel = Db.id(s.get("channel_id"));
    if (!s.get("status").equals("LIVE"))
      throw new ApiError(409, Messages.text("chatServiceSendText01"));
    var policy = platform.policy(channel);
    UUID message =
        tx.execute(
            status -> {
              db.one("SELECT id FROM users WHERE id=? FOR UPDATE", user);
              if (db.count(
                      "SELECT count(*) FROM user_sanctions WHERE channel_id=? AND user_id=? AND"
                          + " revoked_at IS NULL AND (expires_at IS NULL OR expires_at>now())",
                      channel,
                      user)
                  > 0) throw new ApiError(403, Messages.text("chatServiceSendText02"));
              int slow =
                  (int)
                      db.one(
                              "SELECT slow_mode_seconds FROM channel_settings WHERE channel_id=?",
                              channel)
                          .get("slow_mode_seconds");
              if (db.count(
                      "SELECT count(*) FROM chat_messages m JOIN chat_rooms r ON r.id=m.room_id"
                          + " WHERE r.stream_id=? AND m.user_id=? AND"
                          + " m.created_at>now()-make_interval(secs => ?)",
                      stream,
                      user,
                      Math.max(1, slow))
                  > 0) throw new ApiError(429, Messages.text("chatServiceSendText03"));
              return db.insert(
                  "INSERT INTO chat_messages(room_id,user_id,content) SELECT id,?,? FROM chat_rooms"
                      + " WHERE stream_id=? RETURNING id",
                  user,
                  text,
                  stream);
            });
    var analysis = ai.moderate(stream, text, policy);
    Verdict verdict = analysis.verdict();
    if (db.count(
            "SELECT count(*) FROM chat_messages m JOIN chat_rooms r ON r.id=m.room_id WHERE"
                + " r.stream_id=? AND m.user_id=? AND lower(m.content)=lower(?) AND"
                + " m.created_at>now()-interval '1 minute'",
            stream,
            user,
            text)
        >= 3)
      verdict = new Verdict("SPAM", 1, Messages.text("chatServiceSendText04"), "LOCAL_RULES");
    Verdict finalVerdict = verdict;
    String status =
        tx.execute(
            txStatus -> {
              db.exec(
                  "INSERT INTO"
                      + " message_classifications(message_id,request_id,category,confidence,reason,provider)"
                      + " VALUES (?,?,?,?,?,?)",
                  message,
                  analysis.requestId(),
                  finalVerdict.category(),
                  finalVerdict.confidence(),
                  finalVerdict.reason(),
                  finalVerdict.provider());
              ModerationActionCreator creator;
              if (finalVerdict.category().equals("UNCERTAIN")
                  && finalVerdict.confidence() >= policy.reviewThreshold())
                creator = new ModerationActionCreator.ReviewCreator();
              else if (!finalVerdict.category().equals("SAFE")
                  && finalVerdict.confidence() >= policy.blockThreshold())
                creator =
                    !policy.autoHide()
                        ? new ModerationActionCreator.ReviewCreator()
                        : policy.autoMute()
                            ? new ModerationActionCreator.MuteCreator()
                            : new ModerationActionCreator.HideCreator();
              else if (!finalVerdict.category().equals("SAFE")
                  && finalVerdict.confidence() >= policy.reviewThreshold())
                creator = new ModerationActionCreator.ReviewCreator();
              else creator = new ModerationActionCreator.AllowCreator();
              return creator.execute(
                  new ModerationActionCreator.Context(
                      db,
                      channel,
                      message,
                      user,
                      null,
                      finalVerdict.reason(),
                      true,
                      policy.muteSeconds()));
            });
    var result =
        db.one(
            "SELECT m.id,m.content,m.created_at,m.status,m.user_id,u.username FROM chat_messages m"
                + " JOIN users u ON u.id=m.user_id WHERE m.id=?",
            message);
    if (status.equals("VISIBLE")) {
      hub.broadcast(stream, Map.of("type", "message", "message", result));
      detectSpike(stream, channel);
    } else {
      new NotificationBridge.ModerationNotice(delivery).send(user, verdict.reason());
      hub.toModerators(stream, Map.of("type", "queue-updated"));
    }
    result.put("reason", verdict.reason());
    result.put("provider", verdict.provider());
    return result;
  }

  private void detectSpike(UUID stream, UUID channel) {
    if (!(boolean)
        db.one("SELECT auto_clips FROM channel_settings WHERE channel_id=?", channel)
            .get("auto_clips")) return;
    tx.executeWithoutResult(
        status -> {
          db.one("SELECT id FROM streams WHERE id=? FOR UPDATE", stream);
          if (db.count(
                      "SELECT count(*) FROM chat_messages m JOIN chat_rooms r ON r.id=m.room_id"
                          + " WHERE r.stream_id=? AND m.status='VISIBLE' AND"
                          + " m.created_at>now()-interval '10 seconds'",
                      stream)
                  >= 8
              && db.count(
                      "SELECT count(*) FROM stream_highlights WHERE stream_id=? AND"
                          + " created_at>now()-interval '45 seconds'",
                      stream)
                  == 0) {
            UUID h =
                db.insert(
                    "INSERT INTO stream_highlights(stream_id,source,at_seconds,score,reason) SELECT"
                        + " id,'CHAT_SPIKE',GREATEST(0,extract(epoch FROM"
                        + " now()-started_at)::int),1,? FROM streams WHERE id=? RETURNING id",
                    Messages.text("chatSpikeReason"),
                    stream);
            hub.toHost(stream, Map.of("type", "capture", "highlightId", h));
            hub.toModerators(stream, Map.of("type", "highlight", "source", "CHAT_SPIKE"));
          }
        });
  }

  @Transactional
  public void review(UUID queue, UUID reviewer, boolean approve) {
    var q =
        db.one(
            "SELECT q.status AS queue_status,m.id,m.user_id,r.stream_id,s.channel_id FROM"
                + " moderation_queue q JOIN chat_messages m ON m.id=q.message_id JOIN chat_rooms r"
                + " ON r.id=m.room_id JOIN streams s ON s.id=r.stream_id WHERE q.id=? FOR UPDATE OF"
                + " q",
            queue);
    UUID channel = Db.id(q.get("channel_id"));
    platform.manager(channel, reviewer);
    if (!q.get("queue_status").equals("PENDING"))
      throw new ApiError(409, Messages.text("chatServiceReviewText05"));
    db.exec(
        "UPDATE moderation_queue SET status=?,reviewer_id=?,reviewed_at=now() WHERE id=?",
        approve ? "APPROVED" : "REJECTED",
        reviewer,
        queue);
    db.exec(
        "UPDATE chat_messages SET status=? WHERE id=?",
        approve ? "VISIBLE" : "HIDDEN",
        q.get("id"));
    db.exec(
        "INSERT INTO moderation_actions(channel_id,message_id,actor_id,target_id,action,reason)"
            + " VALUES (?,?,?,?,?,?)",
        channel,
        q.get("id"),
        reviewer,
        q.get("user_id"),
        approve ? "APPROVE" : "HIDE",
        Messages.text("humanReviewReason"));
    if (approve)
      hub.broadcast(
          Db.id(q.get("stream_id")),
          Map.of(
              "type",
              "message",
              "message",
              db.one(
                  "SELECT m.id,m.content,m.created_at,m.user_id,u.username FROM chat_messages m"
                      + " JOIN users u ON u.id=m.user_id WHERE m.id=?",
                  q.get("id"))));
    hub.toModerators(Db.id(q.get("stream_id")), Map.of("type", "queue-updated"));
  }

  @Transactional
  public void sanction(
      UUID channel, UUID target, UUID actor, String type, int seconds, String reason) {
    platform.manager(channel, actor);
    if (db.count("SELECT count(*) FROM channels WHERE id=? AND owner_id=?", channel, target) > 0)
      throw new ApiError(400, Messages.text("chatServiceSanctionText06"));
    UUID action =
        db.insert(
            "INSERT INTO moderation_actions(channel_id,actor_id,target_id,action,reason) VALUES"
                + " (?,?,?,?,?) RETURNING id",
            channel,
            actor,
            target,
            type,
            reason);
    db.exec(
        "INSERT INTO user_sanctions(channel_id,user_id,action_id,type,expires_at) VALUES"
            + " (?,?,?,?,CASE WHEN ?='BAN' THEN NULL ELSE now()+make_interval(secs => ?) END)",
        channel,
        target,
        action,
        type,
        type,
        seconds);
    new NotificationBridge.ModerationNotice(delivery).send(target, reason);
  }

  @Transactional
  public void revoke(UUID sanction, UUID actor) {
    var row = db.one("SELECT * FROM user_sanctions WHERE id=?", sanction);
    platform.manager(Db.id(row.get("channel_id")), actor);
    db.exec("UPDATE user_sanctions SET revoked_at=now() WHERE id=?", sanction);
    db.exec(
        "INSERT INTO moderation_actions(channel_id,actor_id,target_id,action,reason) VALUES"
            + " (?,?,?,'REVOKE',?)",
        row.get("channel_id"),
        actor,
        row.get("user_id"),
        Messages.text("sanctionRevokedReason"));
  }
}
