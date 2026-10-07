package com.streamguard.ai;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Adapter: translates the application's AI contract into Gemini REST envelopes. */
@Component
public class GeminiAdapter implements AiGateway {
  private final ObjectMapper json;
  private final String key, model;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

  public GeminiAdapter(
      ObjectMapper json,
      @Value("${app.gemini-key}") String key,
      @Value("${app.gemini-model}") String model) {
    this.json = json;
    this.key = key;
    this.model = model;
  }

  public boolean configured() {
    return !key.isBlank();
  }

  public String model() {
    return model;
  }

  @Override
  public JsonNode generate(String instruction, JsonNode input, JsonNode schema) {
    if (!configured()) throw new IllegalStateException("Gemini is not configured");
    if (!model.matches("[A-Za-z0-9._-]+")) throw new IllegalStateException("Invalid model");
    try {
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
              Map.of(
                  "temperature",
                  0.1,
                  "responseMimeType",
                  "application/json",
                  "responseSchema",
                  schema));
      var req =
          HttpRequest.newBuilder(
                  URI.create(
                      "https://generativelanguage.googleapis.com/v1beta/models/"
                          + model
                          + ":generateContent"))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", "application/json")
              .header("x-goog-api-key", key)
              .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
              .build();
      var res = client.send(req, HttpResponse.BodyHandlers.ofString());
      if (res.statusCode() != 200)
        throw new IllegalStateException("AI provider unavailable (HTTP " + res.statusCode() + ")");
      var envelope = json.readTree(res.body());
      var parts = envelope.path("candidates").path(0).path("content").path("parts");
      StringBuilder text = new StringBuilder();
      for (var p : parts)
        if (!p.path("thought").asBoolean(false)) text.append(p.path("text").asText(""));
      return json.readTree(text.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("AI request interrupted");
    } catch (Exception e) {
      throw new IllegalStateException("Could not obtain a valid Gemini response", e);
    }
  }
}
