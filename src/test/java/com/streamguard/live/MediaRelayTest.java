package com.streamguard.live;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamguard.auth.AuthService;
import com.streamguard.core.Db;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.web.socket.*;

class MediaRelayTest {
  Db db;
  AuthService auth;
  MediaRelay relay;
  UUID stream = UUID.randomUUID(), owner = UUID.randomUUID();

  @BeforeEach
  void setup() {
    db = mock(Db.class);
    auth = mock(AuthService.class);
    when(db.one(anyString(), eq(stream))).thenReturn(Map.of("status", "LIVE", "owner_id", owner));
    when(db.count(anyString(), eq(stream))).thenReturn(1L);
    when(auth.resolve("owner-token")).thenReturn(owner);
    relay = new MediaRelay(db, auth, new ObjectMapper(), 2);
  }

  @AfterEach
  void cleanup() {
    relay.close();
  }

  WebSocketSession socket(String id) {
    var socket = mock(WebSocketSession.class);
    when(socket.getId()).thenReturn(id);
    when(socket.isOpen()).thenReturn(true);
    return socket;
  }

  void join(WebSocketSession socket, boolean host, String token) throws Exception {
    relay.handleTextMessage(
        socket,
        new TextMessage(
            new ObjectMapper()
                .writeValueAsString(
                    Map.of(
                        "type",
                        "join",
                        "streamId",
                        stream.toString(),
                        "host",
                        host,
                        "token",
                        token,
                        "format",
                        "video/webm;codecs=vp8,opus"))));
  }

  @Test
  void ownerCanPublishAndGuestReceivesFragmentsWithoutAuthentication() throws Exception {
    var host = socket("host");
    var viewer = socket("viewer");
    join(host, true, "owner-token");
    join(viewer, false, "");
    byte[] fragment = {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 1};
    relay.handleBinaryMessage(host, new BinaryMessage(fragment));
    verify(viewer, timeout(2000)).sendMessage(isA(BinaryMessage.class));
    var late = socket("late");
    join(late, false, "");
    verify(late, timeout(2000)).sendMessage(isA(BinaryMessage.class));
    relay.afterConnectionClosed(viewer, CloseStatus.NORMAL);
    relay.afterConnectionClosed(late, CloseStatus.NORMAL);
    verify(host, atLeastOnce())
        .sendMessage(
            argThat(
                message ->
                    message instanceof TextMessage
                        && ((TextMessage) message).getPayload().contains("relay-demand")));
  }

  @Test
  void guestCannotImpersonatePublisherOrSendMedia() throws Exception {
    var intruder = socket("intruder");
    join(intruder, true, "");
    verify(intruder).close(CloseStatus.POLICY_VIOLATION);
    var viewer = socket("viewer");
    join(viewer, false, "");
    relay.handleBinaryMessage(viewer, new BinaryMessage(new byte[] {1}));
    verify(viewer).close(CloseStatus.POLICY_VIOLATION);
  }

  @Test
  void stopsPublishingWhenStreamEndsAndRejectsOversizedFragments() throws Exception {
    var host = socket("host");
    join(host, true, "owner-token");
    relay.handleBinaryMessage(host, new BinaryMessage(new byte[1024 * 1024 + 1]));
    verify(host).close(CloseStatus.POLICY_VIOLATION);
    when(db.count(anyString(), eq(stream))).thenReturn(0L);
    relay.handleBinaryMessage(host, new BinaryMessage(new byte[] {1}));
    verify(host).close(CloseStatus.NORMAL);
  }

  @Test
  void capsViewersAndAllowsPublisherToReconnect() throws Exception {
    var first = socket("first");
    var second = socket("second");
    var third = socket("third");
    join(first, false, "");
    join(second, false, "");
    join(third, false, "");
    verify(third).close(CloseStatus.POLICY_VIOLATION);
    var host = socket("host");
    join(host, true, "owner-token");
    relay.afterConnectionClosed(host, CloseStatus.NORMAL);
    var replacement = socket("replacement");
    join(replacement, true, "owner-token");
    verify(replacement, never()).close(any());
  }

  @Test
  void delayedPublisherCloseCannotEvictItsAuthenticatedReplacement() throws Exception {
    var original = socket("original");
    var replacement = socket("replacement");
    var viewer = socket("viewer");
    join(original, true, "owner-token");
    join(viewer, false, "");
    join(replacement, true, "owner-token");
    verify(original).close(CloseStatus.POLICY_VIOLATION);
    relay.afterConnectionClosed(original, CloseStatus.POLICY_VIOLATION);
    relay.handleBinaryMessage(replacement, new BinaryMessage(new byte[] {1}));
    verify(viewer, timeout(2000)).sendMessage(isA(BinaryMessage.class));
    verify(replacement, never()).close(any());
  }
}
