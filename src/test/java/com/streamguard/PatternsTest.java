package com.streamguard;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.streamguard.patterns.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PatternsTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void builderRejectsInconsistentThresholds() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModerationPolicy.Builder().thresholds(.9, .5).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModerationPolicy.Builder().thresholds(Double.NaN, .8).build());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ModerationPolicy.Builder().muteSeconds(5).build());
  }

  @Test
  void builderProtectsImmutableRules() {
    var words = new ArrayList<>(List.of(TestFixtures.text("patternsTestText01")));
    var p = new ModerationPolicy.Builder().blockedWords(words).build();
    words.clear();
    assertEquals(List.of(TestFixtures.text("patternsTestText02")), p.blockedWords());
    assertThrows(UnsupportedOperationException.class, () -> p.blockedWords().clear());
  }

  @Test
  void localFactoryUsesWordBoundariesAndNormalizesAccents() {
    var p =
        new ModerationPolicy.Builder()
            .blockedWords(List.of(TestFixtures.text("patternsTestText03")))
            .build();
    var analyzer = new AiToolkitFactory.LocalToolkit(json).moderation();
    assertEquals(
        "RESTRICTED", analyzer.analyze(TestFixtures.text("patternsTestText04"), p).category());
    assertEquals("SAFE", analyzer.analyze(TestFixtures.text("patternsTestText05"), p).category());
    assertEquals("LINK", analyzer.analyze("see https://example.com", p).category());
    assertEquals(
        "SAFE",
        analyzer
            .analyze(
                "see https://example.com", new ModerationPolicy.Builder().allowLinks(true).build())
            .category());
  }

  @Test
  void geminiFactoryValidatesProviderResponses() {
    var factory =
        new AiToolkitFactory.GeminiToolkit(
            (instruction, input, schema) ->
                json.valueToTree(Map.of("category", "HATE", "confidence", 2, "reason", "Invalid")),
            json);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            factory
                .moderation()
                .analyze(
                    TestFixtures.text("patternsTestText06"),
                    new ModerationPolicy.Builder().build()));
    var editor =
        new AiToolkitFactory.GeminiToolkit(
            (instruction, input, schema) -> json.valueToTree(Map.of("summary", "Incomplete")),
            json);
    assertThrows(
        IllegalArgumentException.class, () -> editor.editorial().compose(json.createObjectNode()));
  }

  @Test
  void bridgeSeparatesMessageTypeFromDelivery() {
    List<String> sent = new ArrayList<>();
    NotificationBridge.Delivery delivery = (u, type, title, body) -> sent.add(type + ":" + body);
    var user = UUID.randomUUID();
    new NotificationBridge.ClipNotice(delivery).send(user, "clip ready");
    new NotificationBridge.ModerationNotice(delivery)
        .send(user, TestFixtures.text("patternsTestText07"));
    assertEquals(List.of("CLIP:clip ready", TestFixtures.text("patternsTestText08")), sent);
  }

  @Test
  void editorialRejectsInventedFaqsAndRetainsOnlyRepeatedChatQuestions() {
    var factory =
        new AiToolkitFactory.OllamaToolkit(
            (instruction, input, schema) ->
                json.valueToTree(
                    Map.of(
                        "summary",
                        "Fixture",
                        "title",
                        "Fixture",
                        "description",
                        "Fixture",
                        "topics",
                        List.of(),
                        "faqs",
                        List.of(
                            Map.of("question", "Where?", "answer", "Here"),
                            Map.of("question", "Invented?", "answer", "Unknown")))),
            json);
    var context =
        json.valueToTree(
            Map.of("messages", List.of(Map.of("content", "Where?"), Map.of("content", "Where?"))));
    var result = factory.editorial().compose(context);
    assertEquals(1, result.path("faqs").size());
    assertEquals("Where?", result.path("faqs").get(0).path("question").asText());
  }
}
