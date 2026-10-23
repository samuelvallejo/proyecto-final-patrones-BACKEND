package com.streamguard.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GeminiAdapterTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  @SuppressWarnings("unchecked")
  void chatFallsBackToAnotherGeminiModelWhenPrimaryIsOverloaded() throws Exception {
    HttpClient client = mock(HttpClient.class);
    HttpResponse<String> overloaded = mock(HttpResponse.class);
    HttpResponse<String> answer = mock(HttpResponse.class);
    when(overloaded.statusCode()).thenReturn(503);
    when(answer.statusCode()).thenReturn(200);
    when(answer.body())
        .thenReturn(
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"answer\\\":\\\"Prueba una sesión de preguntas y respuestas.\\\"}\"}]}}]}");
    doReturn(overloaded)
        .doReturn(answer)
        .when(client)
        .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

    var adapter =
        new GeminiAdapter(json, "private-key", "gemini-3.8-flash", "gemini-3.6-flash", client);
    var result =
        adapter.generateChat(
            "Reply in Spanish",
            json.createObjectNode().put("message", "Give me an idea"),
            json.readTree(
                "{\"type\":\"OBJECT\",\"properties\":{\"answer\":{\"type\":\"STRING\"}}}"),
            4096);

    assertEquals("gemini-3.6-flash", result.model());
    assertEquals("Prueba una sesión de preguntas y respuestas.", result.content().path("answer").asText());
    ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
    assertTrue(requests.getAllValues().get(0).uri().getPath().contains("gemini-3.8-flash"));
    assertTrue(requests.getAllValues().get(1).uri().getPath().contains("gemini-3.6-flash"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void chatDoesNotRetryInvalidRequestsAgainstTheFallback() throws Exception {
    HttpClient client = mock(HttpClient.class);
    HttpResponse<String> invalid = mock(HttpResponse.class);
    when(invalid.statusCode()).thenReturn(400);
    doReturn(invalid).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    var adapter =
        new GeminiAdapter(json, "private-key", "gemini-3.8-flash", "gemini-3.6-flash", client);

    var error =
        assertThrows(
            GeminiAdapter.ProviderException.class,
            () ->
                adapter.generateChat(
                    "Reply in Spanish",
                    json.createObjectNode().put("message", "Question"),
                    json.createObjectNode(),
                    4096));

    assertEquals(400, error.status());
    verify(client, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }
}
