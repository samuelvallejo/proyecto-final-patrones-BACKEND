package com.streamguard.live;

import com.fasterxml.jackson.databind.*;
import com.streamguard.auth.AuthService;
import com.streamguard.core.*;
import com.streamguard.i18n.Messages;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Authenticated signaling only; media travels peer-to-peer, never through this socket. */
@Component
public class LiveHub extends TextWebSocketHandler {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LiveHub.class);

  private static final class HostAlreadyOnline extends ApiError {
    HostAlreadyOnline() {
      super(409, Messages.text("liveHubHandleTextMessageText04"));
    }
  }

  private final Db db;
  private final AuthService auth;
  private final ObjectMapper json;
  private final int maxViewers;
  private final Map<String, Peer> peers = new ConcurrentHashMap<>();
  private final Map<UUID, Room> rooms = new ConcurrentHashMap<>();
  private final ScheduledExecutorService disconnects =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("host-disconnect").factory());

  private record Peer(
      WebSocketSession socket,
      UUID stream,
      UUID user,
      boolean host,
      boolean moderator,
      UUID viewerSession,
      AtomicLong lastPing) {}

  private static class Room {
    String host;
    ScheduledFuture<?> expiry;
    final Set<String> viewers = ConcurrentHashMap.newKeySet();
  }

  public LiveHub(
      Db db, AuthService auth, ObjectMapper json, @Value("${app.max-viewers}") int maxViewers) {
    this.db = db;
    this.auth = auth;
    this.json = json;
    this.maxViewers = maxViewers;
    disconnects.scheduleAtFixedRate(
        () -> {
          try {
            expireUnresponsiveHosts();
          } catch (Exception error) {
            log.warn("Could not check streaming host heartbeats: {}", error.getMessage());
          }
        },
        20,
        10,
        TimeUnit.SECONDS);
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession s) {
    s.setTextMessageSizeLimit(32768);
  }

  @Override
  protected synchronized void handleTextMessage(WebSocketSession socket, TextMessage message)
      throws Exception {
    try {
      var data = json.readTree(message.getPayload());
      String type = data.path("type").asText();
      if (type.equals("join")) {
        if (peers.containsKey(socket.getId()))
          throw new ApiError(409, Messages.text("liveHubHandleTextMessageText01"));
        UUID stream = UUID.fromString(data.path("streamId").asText());
        var row =
            db.one(
                "SELECT s.status,c.id AS channel_id,c.owner_id FROM streams s JOIN channels c ON"
                    + " c.id=s.channel_id WHERE s.id=?",
                stream);
        if (!row.get("status").equals("LIVE"))
          throw new ApiError(409, Messages.text("clipServiceHighlightText01"));
        UUID user = auth.resolve(data.path("token").asText(null));
        boolean host = data.path("host").asBoolean(false);
        boolean owner = Objects.equals(user, row.get("owner_id"));
        if (host && !owner)
          throw new ApiError(403, Messages.text("liveHubHandleTextMessageText03"));
        boolean moderator =
            owner
                || (user != null
                    && db.count(
                            "SELECT count(*) FROM channel_moderators WHERE channel_id=? AND"
                                + " user_id=?",
                            row.get("channel_id"),
                            user)
                        > 0);
        Room room = rooms.computeIfAbsent(stream, k -> new Room());
        if (host && room.host != null) throw new HostAlreadyOnline();
        if (!host && room.viewers.size() >= maxViewers)
          throw new ApiError(429, Messages.text("liveHubHandleTextMessageText05"));
        var wrapped = new ConcurrentWebSocketSessionDecorator(socket, 10000, 256 * 1024);
        UUID view =
            host
                ? null
                : db.insert(
                    "INSERT INTO stream_viewer_sessions(stream_id,user_id) VALUES (?,?) RETURNING"
                        + " id",
                    stream,
                    user);
        peers.put(
            socket.getId(),
            new Peer(
                wrapped,
                stream,
                user,
                host,
                moderator,
                view,
                new AtomicLong(System.currentTimeMillis())));
        if (host) {
          if (room.expiry != null) {
            room.expiry.cancel(false);
            room.expiry = null;
          }
          room.host = socket.getId();
        } else room.viewers.add(socket.getId());
        send(
            wrapped,
            Map.of(
                "type",
                "joined",
                "peerId",
                socket.getId(),
                "moderator",
                moderator,
                "hostOnline",
                room.host != null));
        if (host) {
          for (String viewer : room.viewers)
            send(wrapped, Map.of("type", "viewer-joined", "peerId", viewer));
        } else if (room.host != null)
          send(
              peers.get(room.host).socket(),
              Map.of("type", "viewer-joined", "peerId", socket.getId()));
        broadcast(
            stream,
            Map.of(
                "type",
                "presence",
                "viewers",
                room.viewers.size(),
                "hostOnline",
                room.host != null));
      } else {
        Peer sender = peers.get(socket.getId());
        if (sender == null)
          throw new ApiError(401, Messages.text("liveHubHandleTextMessageText06"));
        if (type.equals("signal")) {
          Peer target = peers.get(data.path("to").asText());
          if (target == null
              || !target.stream().equals(sender.stream())
              || sender.host() == target.host())
            throw new ApiError(403, Messages.text("liveHubHandleTextMessageText07"));
          send(
              target.socket(),
              Map.of("type", "signal", "from", socket.getId(), "payload", data.path("payload")));
        } else if (type.equals("ping")) {
          sender.lastPing().set(System.currentTimeMillis());
          send(sender.socket(), Map.of("type", "pong"));
        } else throw new ApiError(400, Messages.text("liveHubUnknownEvent"));
      }
    } catch (Exception e) {
      send(
          socket,
          Map.of(
              "type",
              "error",
              "message",
              e instanceof ApiError
                  ? e.getMessage()
                  : Messages.text("liveHubHandleTextMessageText08"),
              "retryable",
              e instanceof HostAlreadyOnline));
    }
  }

  @Override
  public synchronized void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
    Peer peer = peers.remove(socket.getId());
    if (peer == null) return;
    Room room = rooms.get(peer.stream());
    if (room == null) return;
    if (peer.viewerSession() != null)
      db.exec("UPDATE stream_viewer_sessions SET left_at=now() WHERE id=?", peer.viewerSession());
    room.viewers.remove(socket.getId());
    if (peer.host()) {
      room.host = null;
      // Keep short network interruptions from ending a broadcast before the owner reconnects.
      room.expiry =
          disconnects.schedule(() -> expireHost(peer.stream(), room), 45, TimeUnit.SECONDS);
    } else if (room.host != null && peers.containsKey(room.host))
      send(peers.get(room.host).socket(), Map.of("type", "viewer-left", "peerId", socket.getId()));
    broadcast(
        peer.stream(),
        Map.of(
            "type", "presence", "viewers", room.viewers.size(), "hostOnline", room.host != null));
    if (room.host == null && room.expiry == null && room.viewers.isEmpty())
      rooms.remove(peer.stream());
  }

  private synchronized void expireHost(UUID stream, Room room) {
    if (rooms.get(stream) != room || room.host != null) return;
    room.expiry = null;
    db.exec(
        "UPDATE streams SET status='ENDED',ended_at=now() WHERE id=? AND status='LIVE'", stream);
    db.exec(
        "UPDATE stream_collaborations SET status='ENDED',ended_at=now() WHERE"
            + " primary_stream_id=? AND status='ACTIVE'",
        stream);
    db.exec(
        "UPDATE collaboration_members SET left_at=now() WHERE left_at IS NULL AND"
            + " (stream_id=? OR room_id IN (SELECT id FROM stream_collaborations WHERE"
            + " primary_stream_id=?))",
        stream,
        stream);
    broadcast(
        stream,
        Map.of("type", "ended", "message", Messages.text("liveHubAfterConnectionClosedText09")));
    if (room.viewers.isEmpty()) rooms.remove(stream);
  }

  private synchronized void expireUnresponsiveHosts() {
    long cutoff = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(35);
    var staleHosts =
        peers.values().stream()
            .filter(peer -> peer.host() && peer.lastPing().get() < cutoff)
            .toList();
    for (Peer peer : staleHosts) {
      Room room = rooms.get(peer.stream());
      if (room == null || !peer.socket().getId().equals(room.host)) continue;
      if (db.count("SELECT count(*) FROM streams WHERE id=? AND status='LIVE'", peer.stream()) == 0)
        continue;
      if (!peers.remove(peer.socket().getId(), peer)) continue;
      room.host = null;
      if (room.expiry != null) room.expiry.cancel(false);
      room.expiry = null;
      db.exec(
          "UPDATE streams SET status='ENDED',ended_at=now() WHERE id=? AND status='LIVE'",
          peer.stream());
      db.exec(
          "UPDATE stream_collaborations SET status='ENDED',ended_at=now() WHERE"
              + " primary_stream_id=? AND status='ACTIVE'",
          peer.stream());
      db.exec(
          "UPDATE collaboration_members SET left_at=now() WHERE left_at IS NULL AND"
              + " (stream_id=? OR room_id IN (SELECT id FROM stream_collaborations WHERE"
              + " primary_stream_id=?))",
          peer.stream(),
          peer.stream());
      broadcast(
          peer.stream(),
          Map.of("type", "ended", "message", Messages.text("liveHubAfterConnectionClosedText09")));
      try {
        if (peer.socket().isOpen()) peer.socket().close(CloseStatus.SESSION_NOT_RELIABLE);
      } catch (Exception error) {
        log.debug("Could not close an unresponsive host socket: {}", error.getMessage());
      }
      if (room.viewers.isEmpty()) rooms.remove(peer.stream());
    }
  }

  @PreDestroy
  void close() {
    disconnects.shutdownNow();
  }

  public int viewers(UUID stream) {
    Room r = rooms.get(stream);
    return r == null ? 0 : r.viewers.size();
  }

  public boolean hosting(UUID stream) {
    Room r = rooms.get(stream);
    return r != null && (r.host != null || r.expiry != null);
  }

  public void broadcast(UUID stream, Object payload) {
    peers.values().stream()
        .filter(p -> p.stream().equals(stream))
        .forEach(p -> send(p.socket(), payload));
  }

  public void toModerators(UUID stream, Object payload) {
    peers.values().stream()
        .filter(p -> p.stream().equals(stream) && p.moderator())
        .forEach(p -> send(p.socket(), payload));
  }

  public void toHost(UUID stream, Object payload) {
    peers.values().stream()
        .filter(p -> p.stream().equals(stream) && p.host())
        .forEach(p -> send(p.socket(), payload));
  }

  public void toUser(UUID user, Object payload) {
    peers.values().stream()
        .filter(p -> user.equals(p.user()))
        .forEach(p -> send(p.socket(), payload));
  }

  private void send(WebSocketSession socket, Object payload) {
    try {
      if (socket.isOpen()) socket.sendMessage(new TextMessage(json.writeValueAsString(payload)));
    } catch (Exception ignored) {
      /* disconnect cleanup occurs through lifecycle */
    }
  }
}
