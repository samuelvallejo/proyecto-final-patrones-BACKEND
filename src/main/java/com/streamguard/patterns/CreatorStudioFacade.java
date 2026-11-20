package com.streamguard.patterns;

import com.streamguard.ai.AiService;
import com.streamguard.core.PlatformService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Facade: one authorized entry point coordinates channel data, policy templates and AI status.
 * Plain Java: {@link PatternAssembly} creates it with {@code new}.
 */
public class CreatorStudioFacade {
  private final PlatformService platform;
  private final AiService ai;
  private final ModerationPresets presets;

  public CreatorStudioFacade(PlatformService platform, AiService ai, ModerationPresets presets) {
    this.platform = platform;
    this.ai = ai;
    this.presets = presets;
  }

  public Map<String, Object> dashboard(UUID user, UUID channel) {
    // PlatformService performs the manager check before any dashboard or AI data is returned.
    Map<String, Object> dashboard = new HashMap<>(platform.dashboard(user, channel));
    dashboard.put("moderationPresets", presets.all());
    dashboard.put(
        "assistantCapabilities",
        Map.of("provider", ai.provider(), "model", ai.model(), "configured", ai.configured()));
    return dashboard;
  }

  public ModerationPolicy previewPreset(UUID user, UUID channel, String level) {
    platform.owner(channel, user);
    ModerationPolicy current = platform.policy(channel);
    // Cloning the template must never remove a channel's existing restrictions.
    return presets.copy(level).toBuilder()
        .blockedWords(current.blockedWords())
        .blockedTopics(current.blockedTopics())
        .allowLinks(current.allowLinks())
        .build();
  }
}
