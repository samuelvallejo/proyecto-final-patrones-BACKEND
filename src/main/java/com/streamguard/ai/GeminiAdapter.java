package com.streamguard.ai;

import com.fasterxml.jackson.databind.*;
import com.streamguard.patterns.SharedAiHttpClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Adapter: translates the application's AI contract into Gemini REST envelopes. */
@Component
public class GeminiAdapter implements AiGateway {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(GeminiAdapter.class);
  private static final URI API_BASE =
      URI.create("https://generativelanguage.googleapis.com/v1beta/models/");
  private final ObjectMapper json;
  private final String key, model, fallbackModel;
  private final HttpClient client;
  private volatile ModelCatalog catalog = new ModelCatalog(List.of(), 0);
  private volatile String workingChatModel = "";

  private record ModelCatalog(List<String> names, long expiresAt) {}

  @Autowired
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

  public String chatModel() {
    return configured() ? chatCandidates().getFirst() : model;
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

  /** Uses Google's available text models and the native conversation format for plain-text chat. */
  public ChatGeneration generateChat(
      String instruction, JsonNode input, int maxOutputTokens) {
    if (!configured()) throw new IllegalStateException("Gemini is not configured");
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    var contents = new ArrayList<Map<String, Object>>();
    for (JsonNode turn : input.path("history"))
      contents.add(
          Map.of(
              "role", turn.path("role").asText().equals("assistant") ? "model" : "user",
              "parts", List.of(Map.of("text", turn.path("text").asText()))));
    contents.add(
        Map.of("role", "user", "parts", List.of(Map.of("text", input.path("message").asText()))));
    ProviderException lastFailure = new ProviderException(503);
    for (String candidate : chatCandidates().stream().limit(3).toList()) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) break;
      try {
        var generation = new HashMap<String, Object>();
        generation.put("maxOutputTokens", maxOutputTokens);
        if (candidate.startsWith("gemini-3"))
          generation.put("thinkingConfig", Map.of("thinkingLevel", "LOW"));
        else if (candidate.startsWith("gemini-2.5"))
          generation.put("thinkingConfig", Map.of("thinkingBudget", 0));
        var body =
            Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", instruction))),
                "contents", contents,
                "generationConfig", generation);
        var request =
            HttpRequest.newBuilder(URI.create(API_BASE + candidate + ":generateContent"))
                .timeout(Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(18).toNanos())))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", key)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw providerFailure(response);
        var envelope = json.readTree(response.body());
        String answer = answerText(envelope);
        if (answer.isBlank()) throw new TransientException();
        workingChatModel = candidate;
        return new ChatGeneration(json.createObjectNode().put("answer", answer), candidate);
      } catch (ProviderException failure) {
        log.warn(
            "Gemini chat attempt failed; model={}, status={}, reason={}",
            candidate, failure.status(), failure.reason());
        lastFailure = failure;
        if (failure.status() != 404 && failure.status() != 429 && failure.status() < 500)
          throw failure;
      } catch (IOException | TransientException failure) {
        log.warn("Gemini chat attempt failed; model={}, reason=TRANSPORT_OR_EMPTY_OUTPUT", candidate);
        lastFailure = new ProviderException(503);
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("AI request interrupted");
      }
    }
    throw lastFailure;
  }

  private List<String> chatCandidates() {
    ModelCatalog current = catalog;
    if (current.expiresAt() <= System.currentTimeMillis()) current = refreshCatalog();
    var candidates = new LinkedHashSet<String>();
    var preferred = List.of(
        workingChatModel, "gemini-3.5-flash-lite", "gemini-3.1-flash-lite",
        "gemini-2.5-flash-lite", "gemini-2.5-flash", model, fallbackModel);
    if (!current.names().isEmpty()) {
      for (String name : preferred) if (current.names().contains(name)) candidates.add(name);
      current.names().stream().sorted(Comparator.reverseOrder()).forEach(candidates::add);
    } else {
      for (String name : preferred)
        if (name != null && name.matches("gemini-[A-Za-z0-9._-]+")) candidates.add(name);
    }
    if (candidates.isEmpty()) throw new ProviderException(404);
    return List.copyOf(candidates);
  }

  private synchronized ModelCatalog refreshCatalog() {
    if (catalog.expiresAt() > System.currentTimeMillis()) return catalog;
    try {
      var request =
          HttpRequest.newBuilder(
                  URI.create("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000"))
              .timeout(Duration.ofSeconds(6))
              .header("x-goog-api-key", key)
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) throw providerFailure(response);
      var names = new ArrayList<String>();
      for (JsonNode entry : json.readTree(response.body()).path("models")) {
        String name = entry.path("name").asText().replaceFirst("^models/", "");
        boolean generatesText = false;
        for (JsonNode method : entry.path("supportedGenerationMethods"))
          if (method.asText().equals("generateContent")) generatesText = true;
        if (generatesText
            && name.matches("gemini-[A-Za-z0-9._-]+")
            && name.contains("flash")
            && !name.matches(".*(image|tts|audio|live|native|robotics|computer-use).*"))
          names.add(name);
      }
      catalog = new ModelCatalog(List.copyOf(names), System.currentTimeMillis() + 600000);
      log.info("Gemini text model discovery succeeded; models={}", names);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("AI model discovery interrupted");
    } catch (Exception failure) {
      catalog = new ModelCatalog(List.of(), System.currentTimeMillis() + 60000);
      log.warn("Gemini model discovery unavailable; failureType={}", failure.getClass().getSimpleName());
    }
    return catalog;
  }

  private String answerText(JsonNode envelope) {
    var text = new StringBuilder();
    for (JsonNode part : envelope.path("candidates").path(0).path("content").path("parts"))
      if (!part.path("thought").asBoolean(false)) text.append(part.path("text").asText(""));
    return text.toString().strip();
  }

  private ProviderException providerFailure(HttpResponse<String> response) {
    String reason = "UNSPECIFIED";
    try {
      String status = json.readTree(response.body()).path("error").path("status").asText();
      if (status.matches("[A-Z_]{1,60}")) reason = status;
    } catch (Exception ignored) {
      // Provider bodies can contain private details and must never be logged or returned.
    }
    return new ProviderException(response.statusCode(), reason);
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
    private final String reason;

    public ProviderException(int status) {
      this(status, "UNSPECIFIED");
    }

    private ProviderException(int status, String reason) {
      super("AI provider unavailable (HTTP " + status + ")");
      this.status = status;
      this.reason = reason;
    }

    public int status() {
      return status;
    }

    public String reason() {
      return reason;
    }
  }
}
