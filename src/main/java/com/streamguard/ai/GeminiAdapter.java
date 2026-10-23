package com.streamguard.ai;

import com.fasterxml.jackson.databind.*;
import com.streamguard.patterns.SharedAiHttpClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Adapter: translates the application's AI contract into Gemini REST envelopes. */
@Component
public class GeminiAdapter implements AiGateway {
  private static final URI API_BASE =
      URI.create("https://generativelanguage.googleapis.com/v1beta/models/");
  private final ObjectMapper json;
  private final String key, model, fallbackModel;
  private final HttpClient client;

  public GeminiAdapter(
      ObjectMapper json,
      @Value("${app.gemini-key}") String key,
      @Value("${app.gemini-model}") String model,
      @Value("${app.gemini-fallback-model:gemini-3.6-flash}") String fallbackModel) {
    this(json, key, model, fallbackModel, SharedAiHttpClient.getInstance().client());
  }

  GeminiAdapter(
      ObjectMapper json, String key, String model, String fallbackModel, HttpClient client) {
    this.json = json;
    this.key = key;
    this.model = model;
    this.fallbackModel = fallbackModel;
    this.client = client;
  }

  public boolean configured() {
    return !key.isBlank();
  }

  public String model() {
    return model;
  }

  @Override
  public JsonNode generate(String instruction, JsonNode input, JsonNode schema) {
    return generate(instruction, input, schema, 0);
  }

  /** Bounded generation for structured AI requests. */
  public JsonNode generate(
      String instruction, JsonNode input, JsonNode schema, int maxOutputTokens) {
    try {
      return generateWithModel(model, instruction, input, schema, maxOutputTokens);
    } catch (TransientException error) {
      throw new IllegalStateException("Could not obtain a valid Gemini response", error);
    }
  }

  /** Uses a secondary Gemini model for chat when the primary is overloaded or times out. */
  public ChatGeneration generateChat(
      String instruction, JsonNode input, JsonNode schema, int maxOutputTokens) {
    try {
      return new ChatGeneration(
          generateWithModel(model, instruction, input, schema, maxOutputTokens), model);
    } catch (ProviderException error) {
      if (error.status() < 500 || error.status() > 599 || !validFallback()) throw error;
    } catch (TransientException error) {
      if (!validFallback()) throw new ProviderException(503);
    }
    return new ChatGeneration(
        generateWithModel(fallbackModel, instruction, input, schema, maxOutputTokens), fallbackModel);
  }

  private boolean validFallback() {
    return fallbackModel != null
        && !fallbackModel.isBlank()
        && !fallbackModel.equals(model)
        && fallbackModel.matches("[A-Za-z0-9._-]+");
  }

  private JsonNode generateWithModel(
      String selectedModel,
      String instruction,
      JsonNode input,
      JsonNode schema,
      int maxOutputTokens) {
    if (!configured()) throw new IllegalStateException("Gemini is not configured");
    if (selectedModel == null || !selectedModel.matches("[A-Za-z0-9._-]+"))
      throw new IllegalStateException("Invalid model");
    try {
      var generation = new HashMap<String, Object>();
      boolean gemini3 = selectedModel.toLowerCase(Locale.ROOT).startsWith("gemini-3");
      if (gemini3) {
        // Low thinking keeps conversational answers responsive and avoids wasting output tokens.
        generation.put("thinkingConfig", Map.of("thinkingLevel", "LOW"));
      } else {
        generation.put("temperature", 0.1);
      }
      generation.put("responseMimeType", "application/json");
      generation.put("responseSchema", schema);
      if (maxOutputTokens > 0) generation.put("maxOutputTokens", maxOutputTokens);
      var body =
          Map.of(
              "systemInstruction",
              Map.of("parts", List.of(Map.of("text", instruction))),
              "contents",
              List.of(
                  Map.of(
                      "role",
                      "user",
                      "parts",
                      List.of(Map.of("text", json.writeValueAsString(input))))),
              "generationConfig",
              generation);
      var req =
          HttpRequest.newBuilder(URI.create(API_BASE + selectedModel + ":generateContent"))
              .timeout(Duration.ofSeconds(24))
              .header("Content-Type", "application/json")
              .header("x-goog-api-key", key)
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
              .build();
      var res = client.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200) throw new ProviderException(res.statusCode());
      var envelope = json.readTree(res.body());
      var parts = envelope.path("candidates").path(0).path("content").path("parts");
      StringBuilder text = new StringBuilder();
      for (var p : parts)
        if (!p.path("thought").asBoolean(false)) text.append(p.path("text").asText(""));
      if (text.isEmpty()) throw new TransientException();
      JsonNode result = json.readTree(text.toString());
      if (result == null || result.isNull()) throw new TransientException();
      return result;
    } catch (ProviderException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("AI request interrupted");
    } catch (IOException e) {
      throw new TransientException();
    } catch (Exception e) {
      throw new IllegalStateException("Could not obtain a valid Gemini response", e);
    }
  }

  public record ChatGeneration(JsonNode content, String model) {}

  private static final class TransientException extends RuntimeException {
    private TransientException() {
      super("Temporary Gemini connection failure");
    }
  }

  /** Contains a status only, never the provider response body or credentials. */
  public static final class ProviderException extends RuntimeException {
    private final int status;

    public ProviderException(int status) {
      super("AI provider unavailable (HTTP " + status + ")");
      this.status = status;
    }

    public int status() {
      return status;
    }
  }
}
