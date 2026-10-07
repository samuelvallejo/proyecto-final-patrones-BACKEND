package com.streamguard.live;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
  private final LiveHub hub;
  private final MediaRelay relay;
  private final String origins;

  public WebSocketConfig(LiveHub hub, MediaRelay relay, @Value("${app.origins}") String origins) {
    this.hub = hub;
    this.relay = relay;
    this.origins = origins;
  }

  public void registerWebSocketHandlers(WebSocketHandlerRegistry r) {
    r.addHandler(hub, "/ws").setAllowedOrigins(origins.split(","));
    r.addHandler(relay, "/ws/media").setAllowedOrigins(origins.split(","));
  }
}
