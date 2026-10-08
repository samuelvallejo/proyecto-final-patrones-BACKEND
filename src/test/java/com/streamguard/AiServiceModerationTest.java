package com.streamguard;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.streamguard.ai.*;
import com.streamguard.core.Db;
import com.streamguard.patterns.ModerationPolicy;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiServiceModerationTest {
  @ParameterizedTest
  @ValueSource(strings = {"hello", "hahaha", "great stream"})
  void providerOutageKeepsOrdinaryMessagesSafeWithAValidDatabaseStatus(String message) {
    Db db = mock(Db.class);
    OllamaAdapter ollama = mock(OllamaAdapter.class);
    GeminiAdapter gemini = mock(GeminiAdapter.class);
    UUID request = UUID.randomUUID();
    List<String> savedStatuses = new ArrayList<>();
    when(db.insert(anyString(), any(Object[].class))).thenReturn(request);
    doAnswer(call -> {
      String sql = call.getArgument(0);
      Object[] values = (Object[]) call.getRawArguments()[1];
      if (sql.startsWith("UPDATE ai_requests")) {
        String status = values[0].toString();
        assertTrue(Set.of("PENDING", "SUCCEEDED", "FAILED", "LOCAL").contains(status),
            "The persisted status must satisfy PostgreSQL's constraint");
        savedStatuses.add(status);
      }
      return 1;
    }).when(db).exec(anyString(), any(Object[].class));
    when(ollama.model()).thenReturn("test-model");
    when(ollama.configured()).thenReturn(true);
    when(ollama.generate(anyString(), any(JsonNode.class), any(JsonNode.class)))
        .thenThrow(new IllegalStateException("Local model is offline"));

    AiService service = new AiService(db, gemini, ollama, new ObjectMapper(), "OLLAMA");
    var result = service.moderate(UUID.randomUUID(), message, new ModerationPolicy.Builder().build());

    assertEquals(request, result.requestId());
    assertEquals("SAFE", result.verdict().category());
    assertEquals(0, result.verdict().confidence());
    assertEquals("LOCAL_RULES", result.verdict().provider());
    assertEquals(List.of("LOCAL"), savedStatuses);
    verify(db, times(2)).exec(anyString(), any(Object[].class));
  }
}
