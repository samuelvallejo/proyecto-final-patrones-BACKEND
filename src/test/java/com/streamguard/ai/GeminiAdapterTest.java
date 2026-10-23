package com.streamguard.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GeminiAdapterTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  @SuppressWarnings("unchecked")
  void selectsAvailableTextModelsAndRecoversFromOverloadWithPlainText() throws Exception {
    HttpClient client = mock(HttpClient.class);
    doReturn(catalog(), response(503, "{\"error\":{\"status\":\"UNAVAILABLE\"}}"), answer())
        .when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    var adapter = new GeminiAdapter(json, "private-key", "unavailable-model", "other-model", client);
    var result = adapter.generateChat("Reply in Spanish", input(), 4096);
    assertEquals("gemini-2.5-flash-lite", result.model());
    assertEquals("Una idea: juega con los retos que proponga tu comunidad.",
        result.content().path("answer").asText());
    assertEquals("gemini-2.5-flash-lite", adapter.chatModel());
    ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client, times(3)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
    assertEquals("GET", requests.getAllValues().getFirst().method());
    assertTrue(requests.getAllValues().get(1).uri().getPath().contains("gemini-3.5-flash-lite"));
    assertTrue(requests.getAllValues().get(2).uri().getPath().contains("gemini-2.5-flash-lite"));
    JsonNode body = requestBody(requests.getAllValues().get(1));
    assertFalse(body.path("generationConfig").has("responseSchema"));
    assertFalse(body.path("generationConfig").has("responseMimeType"));
    assertEquals("LOW", body.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asText());
    assertEquals("Give me an idea", body.path("contents").path(2).path("parts").path(0).path("text").asText());
    assertEquals("model", body.path("contents").path(1).path("role").asText());
  }

  @Test
  @SuppressWarnings("unchecked")
  void invalidRequestsDoNotTriggerAnotherGeneration() throws Exception {
    HttpClient client = mock(HttpClient.class);
    doReturn(catalog(), response(400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\"}}"))
        .when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    var adapter = new GeminiAdapter(json, "private-key", "gemini-3.8-flash", "gemini-3.6-flash", client);
    var failure = assertThrows(GeminiAdapter.ProviderException.class,
        () -> adapter.generateChat("Reply in Spanish", input(), 4096));
    assertEquals(400, failure.status());
    assertEquals("INVALID_ARGUMENT", failure.reason());
    verify(client, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  private JsonNode input() {
    return json.valueToTree(Map.of("message", "Give me an idea", "history", List.of(
        Map.of("role", "user", "text", "Earlier question"),
        Map.of("role", "assistant", "text", "Earlier answer"))));
  }

  private HttpResponse<String> catalog() throws Exception {
    return response(200, json.writeValueAsString(Map.of("models", List.of(
        Map.of("name", "models/gemini-3.5-flash-lite", "supportedGenerationMethods", List.of("generateContent")),
        Map.of("name", "models/gemini-2.5-flash-lite", "supportedGenerationMethods", List.of("generateContent")),
        Map.of("name", "models/gemini-3.5-flash-lite-image", "supportedGenerationMethods", List.of("generateContent"))))));
  }

  private HttpResponse<String> answer() throws Exception {
    return response(200, json.writeValueAsString(Map.of("candidates", List.of(
        Map.of("content", Map.of("parts", List.of(Map.of("text", "Una idea: juega con los retos que proponga tu comunidad."))))))));
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<String> response(int status, String body) {
    HttpResponse<String> result = mock(HttpResponse.class);
    when(result.statusCode()).thenReturn(status);
    when(result.body()).thenReturn(body);
    return result;
  }

  private JsonNode requestBody(HttpRequest request) throws Exception {
    var collected = new ByteArrayOutputStream();
    var complete = new CompletableFuture<String>();
    request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
      public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
      public void onNext(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); collected.writeBytes(bytes);
      }
      public void onError(Throwable failure) { complete.completeExceptionally(failure); }
      public void onComplete() { complete.complete(collected.toString(StandardCharsets.UTF_8)); }
    });
    return json.readTree(complete.get(2, TimeUnit.SECONDS));
  }
}
