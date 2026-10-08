package com.streamguard.live;

import com.streamguard.auth.TokenHasher;
import com.streamguard.core.ApiError;
import com.streamguard.core.Db;
import com.streamguard.i18n.Messages;
import java.security.SecureRandom;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Coordinates invite-only groups of independent live streams. */
@Service
public class CollaborationService {
  private static final int MAX_PARTICIPANTS = 4;
  private static final SecureRandom RANDOM = new SecureRandom();
  private final Db db;

  public CollaborationService(Db db) {
    this.db = db;
  }

  @Transactional
  public Map<String, Object> create(UUID stream, UUID user) {
    var live = ownedLiveStream(stream, user);
    removeEndedMemberships();
    requireUnjoinedStream(stream);
    String code = inviteCode();
    UUID room = db.insert(
        "INSERT INTO stream_collaborations(primary_stream_id,invite_hash,max_participants)"
            + " VALUES (?,?,?) RETURNING id",
        stream, TokenHasher.hash(code), MAX_PARTICIPANTS);
    db.exec("INSERT INTO collaboration_members(room_id,stream_id,user_id) VALUES (?,?,?)", room, stream, user);
    db.exec("INSERT INTO stream_events(stream_id,actor_id,event_type,payload) VALUES (?,?,?,?::jsonb)",
        stream, user, "COLLABORATION_CREATED", "{\"roomId\":\"" + room + "\"}");
    Map<String, Object> result = snapshot(room);
    result.put("inviteCode", code);
    result.put("primaryStreamId", live.get("id"));
    return result;
  }

  @Transactional
  public Map<String, Object> join(String code, UUID user) {
    if (code == null || code.isBlank() || code.length() > 80)
      throw new ApiError(400, Messages.text("collaborationInvalidCode"));
    var room = db.optional(
        "SELECT id,primary_stream_id,max_participants FROM stream_collaborations"
            + " WHERE invite_hash=? AND status='ACTIVE'"
            + " AND primary_stream_id IN (SELECT id FROM streams WHERE status='LIVE') FOR UPDATE",
        TokenHasher.hash(code.strip()));
    if (room.isEmpty()) throw new ApiError(404, Messages.text("collaborationInviteMissing"));
    UUID roomId = Db.id(room.get().get("id"));
    var live = ownedLiveStream(null, user);
    UUID streamId = Db.id(live.get("id"));
    long alreadyJoined = db.count(
        "SELECT count(*) FROM collaboration_members WHERE room_id=? AND stream_id=? AND left_at IS NULL",
        roomId, streamId);
    if (alreadyJoined == 0) {
      removeEndedMemberships();
      requireUnjoinedStream(streamId);
      long participants = db.count(
          "SELECT count(*) FROM collaboration_members m JOIN streams s ON s.id=m.stream_id"
              + " WHERE m.room_id=? AND m.left_at IS NULL AND s.status='LIVE'",
          roomId);
      if (participants >= ((Number) room.get().get("max_participants")).intValue())
        throw new ApiError(409, Messages.text("collaborationFull"));
      db.exec(
          "INSERT INTO collaboration_members(room_id,stream_id,user_id) VALUES (?,?,?)"
              + " ON CONFLICT (room_id,stream_id) DO UPDATE SET user_id=EXCLUDED.user_id,"
              + "joined_at=now(),left_at=NULL",
          roomId, streamId, user);
      db.exec("INSERT INTO stream_events(stream_id,actor_id,event_type,payload) VALUES (?,?,?,?::jsonb)",
          streamId, user, "COLLABORATION_JOINED", "{\"roomId\":\"" + roomId + "\"}");
    }
    return snapshot(roomId);
  }

  public Map<String, Object> forStream(UUID stream) {
    var room = db.optional(
        "SELECT c.id FROM stream_collaborations c JOIN collaboration_members m ON m.room_id=c.id"
            + " WHERE m.stream_id=? AND m.left_at IS NULL AND c.status='ACTIVE'"
            + " AND c.primary_stream_id IN (SELECT id FROM streams WHERE status='LIVE') LIMIT 1",
        stream);
    if (room.isEmpty()) return Map.of("active", false, "participants", List.of());
    return snapshot(Db.id(room.get().get("id")));
  }

  private void requireUnjoinedStream(UUID stream) {
    if (db.count("SELECT count(*) FROM collaboration_members WHERE stream_id=? AND left_at IS NULL", stream) > 0)
      throw new ApiError(409, Messages.text("collaborationAlreadyJoined"));
  }

  private void removeEndedMemberships() {
    db.exec("UPDATE stream_collaborations SET status='ENDED',ended_at=now() WHERE status='ACTIVE'"
        + " AND primary_stream_id IN (SELECT id FROM streams WHERE status<>'LIVE')");
    db.exec("UPDATE collaboration_members SET left_at=now() WHERE left_at IS NULL AND"
        + " (stream_id IN (SELECT id FROM streams WHERE status<>'LIVE') OR room_id IN"
        + " (SELECT id FROM stream_collaborations WHERE status='ENDED'))");
  }

  @Transactional
  public Map<String, Object> leave(UUID roomId, UUID user) {
    var room = db.one("SELECT primary_stream_id,status FROM stream_collaborations WHERE id=? FOR UPDATE", roomId);
    if (!"ACTIVE".equals(room.get("status"))) throw new ApiError(409, Messages.text("collaborationEnded"));
    UUID primaryStream = Db.id(room.get("primary_stream_id"));
    var member = db.optional(
        "SELECT stream_id FROM collaboration_members WHERE room_id=? AND user_id=? AND left_at IS NULL",
        roomId, user);
    if (member.isEmpty()) throw new ApiError(403, Messages.text("collaborationNotMember"));
    UUID memberStream = Db.id(member.get().get("stream_id"));
    if (memberStream.equals(primaryStream)) {
      db.exec("UPDATE stream_collaborations SET status='ENDED',ended_at=now() WHERE id=?", roomId);
      db.exec("UPDATE collaboration_members SET left_at=now() WHERE room_id=? AND left_at IS NULL", roomId);
    } else {
      db.exec("UPDATE collaboration_members SET left_at=now() WHERE room_id=? AND stream_id=?", roomId, memberStream);
    }
    return snapshot(roomId);
  }

  private Map<String, Object> ownedLiveStream(UUID stream, UUID user) {
    var live = stream == null
        ? db.optional("SELECT s.id FROM streams s JOIN channels c ON c.id=s.channel_id"
            + " WHERE c.owner_id=? AND s.status='LIVE' FOR UPDATE OF s", user)
        : db.optional("SELECT s.id FROM streams s JOIN channels c ON c.id=s.channel_id"
            + " WHERE s.id=? AND c.owner_id=? AND s.status='LIVE' FOR UPDATE OF s", stream, user);
    if (live.isEmpty()) throw new ApiError(409, Messages.text("collaborationRequiresLive"));
    return live.get();
  }

  private Map<String, Object> snapshot(UUID room) {
    var summary = db.one(
        "SELECT id,primary_stream_id,max_participants,status,created_at FROM stream_collaborations WHERE id=?",
        room);
    var participants = db.list(
        "SELECT s.id AS stream_id,s.title,s.started_at,c.name AS channel_name,c.slug,u.username,"
            + "(s.id=co.primary_stream_id) AS is_host FROM collaboration_members m"
            + " JOIN stream_collaborations co ON co.id=m.room_id JOIN streams s ON s.id=m.stream_id"
            + " JOIN channels c ON c.id=s.channel_id JOIN users u ON u.id=m.user_id"
            + " WHERE m.room_id=? AND m.left_at IS NULL AND s.status='LIVE' ORDER BY m.joined_at",
        room);
    summary.put("active", "ACTIVE".equals(summary.get("status")));
    summary.put("participants", participants);
    return summary;
  }

  private String inviteCode() {
    byte[] bytes = new byte[18];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
