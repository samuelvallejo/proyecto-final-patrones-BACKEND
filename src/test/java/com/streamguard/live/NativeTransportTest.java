package com.streamguard.live;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamguard.auth.AuthService;
import com.streamguard.core.Db;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;

class NativeTransportTest {
  @Test
  void authenticatedNativeHostAdvertisesRelayWithoutCountingAsAViewer() throws Exception {
    var db = mock(Db.class);
    var auth = mock(AuthService.class);
    UUID stream = UUID.randomUUID(), owner = UUID.randomUUID();
    when(db.one(anyString(), eq(stream)))
        .thenReturn(Map.of("status", "LIVE", "owner_id", owner, "channel_id", UUID.randomUUID()));
    when(auth.resolveSocket("native-ticket")).thenReturn(owner);
    when(auth.resolveSocket("viewer-ticket")).thenReturn(owner);
    when(db.insert(anyString(), any(), any())).thenReturn(UUID.randomUUID());
    var hub = new LiveHub(db, auth, new ObjectMapper(), 6);
    try {
      var host = mock(WebSocketSession.class);
      var viewer = mock(WebSocketSession.class);
      when(host.getId()).thenReturn("host");
      when(viewer.getId()).thenReturn("viewer");
      when(host.isOpen()).thenReturn(true);
      when(viewer.isOpen()).thenReturn(true);
      hub.handleTextMessage(
          host,
          new TextMessage(
              new ObjectMapper()
                  .writeValueAsString(
                      Map.of(
                          "type",
                          "join",
                          "streamId",
                          stream,
                          "token",
                          "native-ticket",
                          "host",
                          true,
                          "mediaTransport",
                          "relay"))));
      org.junit.jupiter.api.Assertions.assertEquals(0, hub.viewers(stream));
      hub.handleTextMessage(
          viewer,
          new TextMessage(
              new ObjectMapper()
                  .writeValueAsString(
                      Map.of(
                          "type",
                          "join",
                          "streamId",
                          stream,
                          "token",
                          "viewer-ticket",
                          "host",
                          false))));
      org.junit.jupiter.api.Assertions.assertEquals(1, hub.viewers(stream));
      verify(viewer, atLeastOnce())
          .sendMessage(
              argThat(
                  message ->
                      message instanceof TextMessage
                          && ((TextMessage) message)
                              .getPayload()
                              .contains("\"mediaTransport\":\"relay\"")));
    } finally {
      hub.close();
    }
  }
}
