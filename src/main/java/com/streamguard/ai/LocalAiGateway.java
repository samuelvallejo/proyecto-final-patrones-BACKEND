package com.streamguard.ai;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** A separate Java process: ngrok can reach inference but never Ollama administration. */
public final class LocalAiGateway {
  private final ObjectMapper json = new ObjectMapper();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final Semaphore slot = new Semaphore(1);
  private final byte[] authorization;
  private final String model;
  private final URI upstream;
  private final HttpServer server;
  private long minute = -1;
  private int requests;

  public LocalAiGateway(int port, String token, String model, URI upstream) throws IOException {
    if (!token.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("A 256-bit gateway token is required");
    if (!Set.of("localhost", "127.0.0.1", "::1").contains(upstream.getHost())
        || !"http".equals(upstream.getScheme())
        || upstream.getUserInfo() != null)
      throw new IllegalArgumentException("Ollama must be on loopback");
    this.authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    this.model = model;
    this.upstream = upstream;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
    server.createContext("/", this::handle);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  public void stop() {
    server.stop(1);
  }

  private synchronized boolean admit() {
    long now = System.currentTimeMillis() / 60000;
    if (minute != now) {
      minute = now;
      requests = 0;
    }
    return ++requests <= 60;
  }

  private JsonNode convertSchema(JsonNode value) {
    if (value.isObject()) {
      var result = json.createObjectNode();
      value
          .fields()
          .forEachRemaining(
              entry ->
                  result.set(
                      entry.getKey(),
                      "type".equals(entry.getKey()) && entry.getValue().isTextual()
                          ? new TextNode(entry.getValue().asText().toLowerCase(Locale.ROOT))
                          : convertSchema(entry.getValue())));
      if ("object".equals(result.path("type").asText())) result.put("additionalProperties", false);
      return result;
    }
    if (value.isArray()) {
      var result = json.createArrayNode();
      value.forEach(item -> result.add(convertSchema(item)));
      return result;
    }
    return value;
  }

  private void reply(HttpExchange exchange, int status, Object value) throws IOException {
    byte[] bytes = json.writeValueAsBytes(value);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  private void handle(HttpExchange exchange) throws IOException {
    boolean acquired = false;
    try {
      String provided =
          Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), "");
      if (!MessageDigest.isEqual(authorization, provided.getBytes(StandardCharsets.UTF_8))) {
        reply(exchange, 401, Map.of("error", "Unauthorized"));
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if ("/health".equals(path) && "GET".equals(exchange.getRequestMethod())) {
        var check =
            http.send(
                HttpRequest.newBuilder(upstream.resolve("/api/tags"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        boolean loaded =
            check.statusCode() == 200
                && json.readTree(check.body())
                    .path("models")
                    .findValuesAsText("name")
                    .contains(model);
        reply(
            exchange, loaded ? 200 : 503, Map.of("status", loaded ? "UP" : "DOWN", "model", model));
        return;
      }
      if (!"/generate".equals(path)) {
        reply(exchange, 404, Map.of("error", "Not found"));
        return;
      }
      if (!"POST".equals(exchange.getRequestMethod())) {
        reply(exchange, 405, Map.of("error", "Method not allowed"));
        return;
      }
      if (!Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), "")
          .toLowerCase(Locale.ROOT)
          .startsWith("application/json")) {
        reply(exchange, 415, Map.of("error", "JSON required"));
        return;
      }
      byte[] bytes = exchange.getRequestBody().readNBytes(98305);
      if (bytes.length > 98304) {
        reply(exchange, 413, Map.of("error", "Request too large"));
        return;
      }
      JsonNode input = json.readTree(bytes);
      if (input == null
          || !input.isObject()
          || !input.path("instruction").isTextual()
          || input.path("instruction").asText().length() > 12000
          || !input.path("input").isObject()
          || !input.path("schema").isObject()
          || !model.equals(input.path("model").asText())) {
        reply(exchange, 400, Map.of("error", "Invalid inference contract"));
        return;
      }
      acquired = slot.tryAcquire();
      if (!acquired || !admit()) {
        reply(exchange, 429, Map.of("error", "Inference capacity reached"));
        return;
      }
      var body =
          json.valueToTree(
              Map.of(
                  "model",
                  model,
                  "stream",
                  false,
                  "think",
                  false,
                  "keep_alive",
                  "30m",
                  "format",
                  convertSchema(input.path("schema")),
                  "options",
                  Map.of("temperature", 0.1, "num_ctx", 8192, "num_predict", 1024),
                  "messages",
                  List.of(
                      Map.of("role", "system", "content", input.path("instruction").asText()),
                      Map.of(
                          "role",
                          "user",
                          "content",
                          json.writeValueAsString(input.path("input"))))));
      var response =
          http.send(
              HttpRequest.newBuilder(upstream.resolve("/api/chat"))
                  .timeout(Duration.ofSeconds(45))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      try (var stream = response.body()) {
        byte[] output = stream.readNBytes(65537);
        if (response.statusCode() != 200 || output.length > 65536)
          throw new IOException("Ollama inference failed");
        var envelope = json.readTree(output);
        if (!envelope.path("done").asBoolean() || !model.equals(envelope.path("model").asText()))
          throw new IOException("Incomplete Ollama response");
        var result = json.readTree(envelope.path("message").path("content").asText());
        if (result == null || !result.isObject()) throw new IOException("Invalid model JSON");
        reply(exchange, 200, Map.of("model", model, "output", result));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      reply(exchange, 503, Map.of("error", "Inference interrupted"));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      reply(exchange, 400, Map.of("error", "Invalid JSON"));
    } catch (Exception e) {
      reply(exchange, 503, Map.of("error", "Local model unavailable"));
    } finally {
      if (acquired) slot.release();
      exchange.close();
    }
  }

  public static void main(String[] args) throws Exception {
    String tokenFile = System.getenv("LOCAL_AI_TOKEN_FILE");
    if (tokenFile == null) throw new IllegalArgumentException("LOCAL_AI_TOKEN_FILE is required");
    var gateway =
        new LocalAiGateway(
            Integer.parseInt(System.getenv().getOrDefault("LOCAL_AI_PORT", "11435")),
            Files.readString(Path.of(tokenFile)).trim(),
            System.getenv().getOrDefault("OLLAMA_MODEL", "qwen3:4b-instruct"),
            URI.create("http://127.0.0.1:11434"));
    Runtime.getRuntime().addShutdownHook(new Thread(gateway::stop));
    gateway.start();
    System.out.println(
        "Authenticated local AI gateway listening on loopback port " + gateway.port());
  }
}
