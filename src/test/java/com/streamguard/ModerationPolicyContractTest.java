package com.streamguard;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.streamguard.patterns.ModerationPolicy;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Cross-language policy contracts cover valid combinations and invalid boundary values. */
class ModerationPolicyContractTest {
  private final ObjectMapper json = new ObjectMapper();

  @TestFactory
  Stream<DynamicTest> policyContracts() throws Exception {
    JsonNode cases;
    try (var input = getClass().getResourceAsStream("/patterns/moderation-contracts.json")) {
      cases = json.readTree(input);
    }
    return java.util.stream.StreamSupport.stream(cases.spliterator(), false)
        .map(
            test ->
                DynamicTest.dynamicTest(
                    test.path("id").asText(),
                    () -> {
                      JsonNode value = test.path("policy");
                      if (test.path("valid").asBoolean()) {
                        var policy = read(value);
                        assertEquals(value, json.valueToTree(policy));
                        assertNotSame(policy, policy.copy());
                        assertEquals(policy, policy.copy());
                        assertEquals(policy, policy.toBuilder().build());
                        assertThrows(
                            UnsupportedOperationException.class,
                            () -> policy.copy().blockedWords().add("changed"));
                        assertThrows(
                            UnsupportedOperationException.class,
                            () -> policy.copy().blockedTopics().add("changed"));
                      } else assertThrows(IllegalArgumentException.class, () -> read(value));
                    }));
  }

  private ModerationPolicy read(JsonNode value) {
    return new ModerationPolicy(
        value.path("level").isNull() ? null : value.path("level").asText(),
        value.path("autoHide").asBoolean(),
        value.path("autoMute").asBoolean(),
        value.path("muteSeconds").asInt(),
        value.path("reviewThreshold").asDouble(),
        value.path("blockThreshold").asDouble(),
        json.convertValue(value.get("blockedWords"), new TypeReference<List<String>>() {}),
        json.convertValue(value.get("blockedTopics"), new TypeReference<List<String>>() {}),
        value.path("allowLinks").asBoolean());
  }
}
