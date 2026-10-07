package com.streamguard.i18n;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;

/** Spanish user-facing copy, separate from implementation and diagnostic messages. */
public final class Messages {
  private static final Map<String, String> COPY = load();

  private Messages() {}

  private static Map<String, String> load() {
    try (var input = Messages.class.getResourceAsStream("/locales/es.json")) {
      if (input == null) throw new IllegalStateException("Spanish message catalog not found");
      return Map.copyOf(
          new ObjectMapper().readValue(input, new TypeReference<Map<String, String>>() {}));
    } catch (IOException error) {
      throw new IllegalStateException("Could not load the Spanish message catalog", error);
    }
  }

  public static String text(String key) {
    String value = COPY.get(key);
    if (value == null) throw new IllegalArgumentException("Missing message translation: " + key);
    return value;
  }
}
