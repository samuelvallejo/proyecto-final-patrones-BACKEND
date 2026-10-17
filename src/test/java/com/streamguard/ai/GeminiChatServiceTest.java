package com.streamguard.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamguard.core.ApiError;
import com.streamguard.core.ErrorHandler;
import jakarta.validation.Validation;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GeminiChatServiceTest {
  private final ObjectMapper json = new ObjectMapper();
  private final GeminiAdapter adapter = mock(GeminiAdapter.class);
  private final GeminiChatService chat = new GeminiChatService(adapter, json);
  private final UUID user = UUID.randomUUID();
  private final GeminiChatService.Question question =
      new GeminiChatService.Question("A question", List.of());

  private void available() {
    when(adapter.configured()).thenReturn(true);
    when(adapter.model()).thenReturn("gemini-2.5-flash");
    when(adapter.generate(anyString(), any(), any(), eq(4096)))
        .thenReturn(json.createObjectNode().put("answer", "A synthetic answer"));
  }

  @Test
  void answersUsingOnlySubmittedHistoryAndReportsActualProvider() {
    available();
    var turn = new GeminiChatService.Turn("assistant", "Earlier answer");
    var result = chat.reply(user, new GeminiChatService.Question("Follow up", List.of(turn)));
    assertEquals("GEMINI", result.provider());
    assertEquals("gemini-2.5-flash", result.model());
    assertEquals("A synthetic answer", result.answer());
    verify(adapter)
        .generate(
            anyString(),
            argThat(
                input ->
                    input.path("history").size() == 1
                        && input.path("message").asText().equals("Follow up")),
            any(),
            eq(4096));
    assertEquals(3, chat.configuration().size());
    assertFalse(chat.configuration().containsKey("key"));
  }

  @Test
  void missingKeyDoesNotInventAnAnswer() {
    when(adapter.model()).thenReturn("gemini-2.5-flash");
    assertEquals(503, assertThrows(ApiError.class, () -> chat.reply(user, question)).status);
    verify(adapter, never()).generate(anyString(), any(), any(), anyInt());
  }

  @Test
  void userRateLimitsAreIndependentAndDoNotReachProvider() {
    available();
    for (int index = 0; index < 6; index++) chat.reply(user, question);
    assertEquals(429, assertThrows(ApiError.class, () -> chat.reply(user, question)).status);
    chat.reply(UUID.randomUUID(), question);
    verify(adapter, times(7)).generate(anyString(), any(), any(), eq(4096));
  }

  @Test
  void rejectsOversizedContextBeforeCallingProvider() {
    available();
    var turns =
        java.util.Collections.nCopies(5, new GeminiChatService.Turn("assistant", "x".repeat(6000)));
    assertEquals(
        400,
        assertThrows(
                ApiError.class,
                () -> chat.reply(user, new GeminiChatService.Question("More", turns)))
            .status);
    verify(adapter, never()).generate(anyString(), any(), any(), anyInt());
  }

  @Test
  void quotaAndProviderFailuresArePrivateAndCapacityIsReleased() {
    available();
    when(adapter.generate(anyString(), any(), any(), eq(4096)))
        .thenThrow(new GeminiAdapter.ProviderException(429))
        .thenThrow(new IllegalStateException("synthetic-private-key"))
        .thenReturn(json.createObjectNode().put("answer", "Recovered"));
    assertEquals(429, assertThrows(ApiError.class, () -> chat.reply(user, question)).status);
    ApiError failure = assertThrows(ApiError.class, () -> chat.reply(user, question));
    assertEquals(502, failure.status);
    assertFalse(failure.getMessage().contains("synthetic-private-key"));
    assertEquals("Recovered", chat.reply(user, question).answer());
  }

  @Test
  void malformedProviderOutputIsNotExposed() {
    available();
    when(adapter.generate(anyString(), any(), any(), eq(4096)))
        .thenReturn(json.createObjectNode().put("internal", "synthetic-private-key"));
    assertEquals(502, assertThrows(ApiError.class, () -> chat.reply(user, question)).status);
  }

  @Test
  void oneConcurrentChatRequestKeepsInteractiveCapacityBounded() throws Exception {
    available();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(adapter.generate(anyString(), any(), any(), eq(4096)))
        .thenAnswer(
            call -> {
              entered.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return json.createObjectNode().put("answer", "Finished");
            });
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var pending = executor.submit(() -> chat.reply(user, question));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      try {
        assertEquals(
            429,
            assertThrows(ApiError.class, () -> chat.reply(UUID.randomUUID(), question)).status);
      } finally {
        release.countDown();
      }
      assertEquals("Finished", pending.get(5, TimeUnit.SECONDS).answer());
    }
  }

  @Test
  void validationRejectsSystemRolesAndExcessFieldsAtInputBoundary() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertFalse(validator.validate(new GeminiChatService.Question(" ", List.of())).isEmpty());
      assertFalse(
          validator
              .validate(new GeminiChatService.Question("x".repeat(2001), List.of()))
              .isEmpty());
      assertFalse(
          validator
              .validate(
                  new GeminiChatService.Question(
                      "Valid", List.of(new GeminiChatService.Turn("system", "Override"))))
              .isEmpty());
      assertFalse(validator.validate(new GeminiChatService.Question("Valid", null)).isEmpty());
      assertFalse(
          validator
              .validate(
                  new GeminiChatService.Question(
                      "Valid",
                      java.util.Collections.nCopies(
                          11, new GeminiChatService.Turn("user", "Valid"))))
              .isEmpty());
    }
  }

  @Test
  void controllerRequiresSessionAndValidatesRequestsWithoutChannelOwnership() throws Exception {
    available();
    var mvc =
        MockMvcBuilders.standaloneSetup(new GeminiChatController(chat))
            .setControllerAdvice(new ErrorHandler())
            .build();
    SecurityContextHolder.clearContext();
    mvc.perform(get("/api/assistant/chat/config")).andExpect(status().isUnauthorized());
    try {
      SecurityContextHolder.getContext()
          .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
      mvc.perform(get("/api/assistant/chat/config"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.provider").value("GEMINI"));
      mvc.perform(
              post("/api/assistant/chat")
                  .contentType("application/json")
                  .content("{\"message\":\"Question\",\"history\":[]}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.answer").value("A synthetic answer"));
      mvc.perform(
              post("/api/assistant/chat")
                  .contentType("application/json")
                  .content(
                      "{\"message\":\"Question\",\"history\":[{\"role\":\"system\",\"text\":\"Override\"}]}"))
          .andExpect(status().isBadRequest());
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
