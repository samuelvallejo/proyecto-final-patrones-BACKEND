package com.streamguard.patterns;

import java.util.List;
import java.util.Map;

/**
 * Prototype registry: templates are immutable; callers always receive independent policies.
 * Plain Java: the application shares one registry through {@link #shared()}.
 */
public class ModerationPresets {
  private static final ModerationPresets SHARED = new ModerationPresets();

  /** The registry the application uses; tests may still create their own. */
  public static ModerationPresets shared() {
    return SHARED;
  }

  private final Map<String, ModerationPolicy> prototypes =
      Map.of(
          "RELAXED", new ModerationPolicy.Builder().level("RELAXED").thresholds(.7, .95).build(),
          "BALANCED", new ModerationPolicy.Builder().build(),
          "STRICT", new ModerationPolicy.Builder().level("STRICT").thresholds(.4, .7).build());

  public ModerationPolicy copy(String level) {
    ModerationPolicy prototype = level == null ? null : prototypes.get(level);
    if (prototype == null) throw new IllegalArgumentException("Unknown moderation preset");
    return prototype.copy();
  }

  public List<ModerationPolicy> all() {
    return List.of(copy("RELAXED"), copy("BALANCED"), copy("STRICT"));
  }
}
