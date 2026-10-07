package com.streamguard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;

/** Spanish input samples used to exercise localization and normalization. */
final class TestFixtures {
  private static final Map<String, String> VALUES = load();

  private TestFixtures() {}

  private static Map<String, String> load() {
    try (var input = TestFixtures.class.getResourceAsStream("/fixtures/es.json")) {
      if (input == null) throw new IllegalStateException("Test fixture catalog not found");
      return new ObjectMapper().readValue(input, new TypeReference<Map<String, String>>() {});
    } catch (IOException error) {
      throw new IllegalStateException("Could not load test fixtures", error);
    }
  }

  static String text(String key) {
    String value = VALUES.get(key);
    if (value == null) throw new IllegalArgumentException("Missing test fixture: " + key);
    return value;
  }
}
