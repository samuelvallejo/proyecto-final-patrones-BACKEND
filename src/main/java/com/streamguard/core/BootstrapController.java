package com.streamguard.core;

import com.streamguard.auth.AuthService;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/**
 * Startup data in a single round trip. Each request from Colombia to the host costs about a third
 * of a second, so the profile, configuration, categories and followed channels arrive together.
 */
@RestController
@RequestMapping("/api")
public class BootstrapController {
  private final AuthService auth;
  private final PlatformController platform;
  private final DiscoveryController discovery;

  public BootstrapController(AuthService auth, PlatformController platform, DiscoveryController discovery) {
    this.auth = auth;
    this.platform = platform;
    this.discovery = discovery;
  }

  @GetMapping("/bootstrap")
  public Map<String, Object> bootstrap() {
    return Map.of(
        "user", auth.profile(AuthService.current()),
        "config", platform.config(),
        "categories", platform.categories(),
        "following", discovery.following());
  }

  /** Public and database-free: lets the page wake a sleeping server while the user types. */
  @GetMapping("/ping")
  public Map<String, Object> ping() {
    return Map.of("ok", true);
  }
}
