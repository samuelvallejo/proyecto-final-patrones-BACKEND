package com.streamguard.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamguard.auth.AuthService;
import com.streamguard.core.Db;
import com.streamguard.i18n.Messages;
import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/** Bounded, transient WSS fallback when peer-to-peer video is blocked by a network. */
@Component
public class MediaRelay extends AbstractWebSocketHandler {
  private static final int MAX_FRAGMENT = 1024 * 1024;
  private static final Set<String> FORMATS =
      Set.of("video/webm;codecs=vp8,opus", "video/webm", "video/mp4");
  private final Db db;
  private final AuthService auth;
  private final ObjectMapper json;
  private final int maxViewers;
  private final Map<String, Peer> peers = new ConcurrentHashMap<>();
  private final Map<UUID, Room> rooms = new ConcurrentHashMap<>();
  private final ExecutorService deliveries = Executors.newVirtualThreadPerTaskExecutor();

  private record Peer(WebSocketSession socket, UUID stream, boolean host, AtomicBoolean sending) {}

  private static class Room {
    String host;
    String format;
    byte[] latest;
    long updated;
    final Set<String> viewers = new HashSet<>();
  }

  public MediaRelay(
      Db db, AuthService auth, ObjectMapper json, @Value("${app.max-viewers}") int maxViewers) {
    this.db = db;
    this.auth = auth;
    this.json = json;
    this.maxViewers = maxViewers;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession socket) {
    socket.setBinaryMessageSizeLimit(MAX_FRAGMENT);
    socket.setTextMessageSizeLimit(4096);
  }

  @Override
  protected synchronized void handleTextMessage(WebSocketSession socket, TextMessage message)
      throws Exception {
    try {
      var data = json.readTree(message.getPayload());
      if ("ping".equals(data.path("type").asText()) && peers.containsKey(socket.getId())) return;
      if (!"join".equals(data.path("type").asText()) || peers.containsKey(socket.getId()))
        throw new IllegalArgumentException();
      UUID stream = UUID.fromString(data.path("streamId").asText());
      var row =
          db.one(
              "SELECT s.status,c.owner_id FROM streams s JOIN channels c ON c.id=s.channel_id WHERE"
                  + " s.id=?",
              stream);
      if (!"LIVE".equals(row.get("status"))) throw new IllegalArgumentException();
      boolean host = data.path("host").asBoolean(false);
      UUID user = auth.resolve(data.path("token").asText(null));
      if (host && !Objects.equals(user, row.get("owner_id"))) throw new IllegalArgumentException();
      String format = data.path("format").asText();
      if (host && !FORMATS.contains(format)) throw new IllegalArgumentException();
      if (!rooms.containsKey(stream) && rooms.size() >= 16) throw new IllegalStateException();
      Room room = rooms.computeIfAbsent(stream, id -> new Room());
      if (!host && room.viewers.size() >= maxViewers) throw new IllegalStateException();
      Peer peer =
          new Peer(
              new ConcurrentWebSocketSessionDecorator(socket, 5000, MAX_FRAGMENT * 2),
              stream,
              host,
              new AtomicBoolean());
      peers.put(socket.getId(), peer);
      if (host) {
        // The authenticated owner can reconnect before a delayed close callback arrives.
        // Remove the old publisher first so its callback cannot evict the replacement.
        Peer previous = room.host == null ? null : peers.remove(room.host);
        room.host = socket.getId();
        room.format = format;
        room.latest = null;
        if (previous != null) {
          try {
            previous.socket().close(CloseStatus.POLICY_VIOLATION);
          } catch (Exception ignored) {
            /* A half-closed connection must not prevent the owner from recovering. */
          }
        }
        send(peer, Map.of("type", "relay-ready", "viewers", room.viewers.size()));
        for (String viewer : room.viewers)
          send(peers.get(viewer), Map.of("type", "relay-format", "format", format));
      } else {
        room.viewers.add(socket.getId());
        if (room.format != null) send(peer, Map.of("type", "relay-format", "format", room.format));
        if (room.latest != null && System.currentTimeMillis() - room.updated < 8000)
          deliver(peer, room.latest);
        demand(room);
      }
    } catch (Exception error) {
      socket.sendMessage(
          new TextMessage(
              json.writeValueAsString(
                  Map.of("type", "error", "message", Messages.text("mediaRelayUnavailable")))));
      socket.close(CloseStatus.POLICY_VIOLATION);
    }
  }

  @Override
  protected synchronized void handleBinaryMessage(WebSocketSession socket, BinaryMessage message)
      throws Exception {
    Peer publisher = peers.get(socket.getId());
    if (publisher == null || !publisher.host() || message.getPayloadLength() > MAX_FRAGMENT) {
      socket.close(CloseStatus.POLICY_VIOLATION);
      return;
    }
    Room room = rooms.get(publisher.stream());
    if (room == null || !socket.getId().equals(room.host)) return;
    if (db.count("SELECT count(*) FROM streams WHERE id=? AND status='LIVE'", publisher.stream())
        != 1) {
      socket.close(CloseStatus.NORMAL);
      return;
    }
    ByteBuffer payload = message.getPayload();
    byte[] fragment = new byte[payload.remaining()];
    payload.get(fragment);
    room.latest = fragment;
    room.updated = System.currentTimeMillis();
    for (String viewer : room.viewers) deliver(peers.get(viewer), fragment);
  }

  private void deliver(Peer peer, byte[] fragment) {
    // At most one pending delivery per viewer; slow clients cannot grow server memory.
    if (peer == null || !peer.sending().compareAndSet(false, true)) return;
    deliveries.execute(
        () -> {
          try {
            if (peer.socket().isOpen()) peer.socket().sendMessage(new BinaryMessage(fragment));
          } catch (Exception ignored) {
            try {
              peer.socket().close(CloseStatus.SERVER_ERROR);
            } catch (Exception unused) {
            }
          } finally {
            peer.sending().set(false);
          }
        });
  }

  private void send(Peer peer, Map<String, Object> data) {
    if (peer == null) return;
    try {
      if (peer.socket().isOpen())
        peer.socket().sendMessage(new TextMessage(json.writeValueAsString(data)));
    } catch (Exception ignored) {
      /* The socket lifecycle removes disconnected clients. */
    }
  }

  private void demand(Room room) {
    if (room.host != null)
      send(peers.get(room.host), Map.of("type", "relay-demand", "viewers", room.viewers.size()));
  }

  @Override
  public synchronized void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
    Peer peer = peers.remove(socket.getId());
    if (peer == null) return;
    Room room = rooms.get(peer.stream());
    if (room == null) return;
    if (peer.host() && socket.getId().equals(room.host)) {
      room.host = null;
      room.latest = null;
    } else {
      room.viewers.remove(socket.getId());
      demand(room);
    }
    if (room.host == null && room.viewers.isEmpty()) rooms.remove(peer.stream());
  }

  @PreDestroy
  void close() {
    deliveries.shutdownNow();
  }
}
