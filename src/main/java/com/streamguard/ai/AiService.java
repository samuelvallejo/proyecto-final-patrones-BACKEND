package com.streamguard.ai;

import com.fasterxml.jackson.databind.*;
import com.streamguard.core.*;
import com.streamguard.i18n.Messages;
import com.streamguard.patterns.*;
import com.streamguard.patterns.AiToolkitFactory.Verdict;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

@Service
public class AiService {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiService.class);
  private final Db db;
  private final GeminiAdapter gemini;
  private final OllamaAdapter ollama;
  private final String provider;
  private final ObjectMapper json;
  private final Semaphore slots = new Semaphore(1);

  public record Analysis(UUID requestId, Verdict verdict) {}

  public record Editorial(UUID requestId, JsonNode output, String provider) {}

  public AiService(
      Db db,
      GeminiAdapter gemini,
      OllamaAdapter ollama,
      ObjectMapper json,
      @org.springframework.beans.factory.annotation.Value("${app.ai-provider}") String provider) {
    this.db = db;
    this.gemini = gemini;
    this.ollama = ollama;
    this.provider = provider.toUpperCase(Locale.ROOT).replace('-', '_');
    if (!Set.of("GEMINI", "OLLAMA", "LOCAL_RULES").contains(this.provider))
      throw new IllegalArgumentException("Unsupported AI provider");
    this.json = json;
  }

  public boolean configured() {
    return provider.equals("OLLAMA")
        ? ollama.configured()
        : provider.equals("GEMINI") && gemini.configured();
  }

  public String provider() {
    return provider;
  }

  public String model() {
    return provider.equals("OLLAMA")
        ? ollama.model()
        : provider.equals("GEMINI") ? gemini.model() : "local-rules";
  }

  private AiToolkitFactory toolkit() {
    return switch (provider) {
      case "OLLAMA" -> new AiToolkitFactory.OllamaToolkit(ollama, json);
      case "GEMINI" -> new AiToolkitFactory.GeminiToolkit(gemini, json);
      default -> new AiToolkitFactory.LocalToolkit(json);
    };
  }

  private UUID request(UUID stream, String task, Object input) {
    return db.insert(
        "INSERT INTO ai_requests(stream_id,model_name,task,input) VALUES (?,?,?,?::jsonb) RETURNING"
            + " id",
        stream,
        model(),
        task,
        serialize(input));
  }

  private String serialize(Object v) {
    try {
      return json.writeValueAsString(v);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private void response(UUID request, Object output, long start, String status) {
    db.exec(
        "INSERT INTO ai_responses(request_id,output,latency_ms) VALUES (?,?::jsonb,?)",
        request,
        serialize(output),
        (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - start));
    db.exec("UPDATE ai_requests SET status=? WHERE id=?", status, request);
  }

  public Analysis moderate(UUID stream, String text, ModerationPolicy policy) {
    UUID id = request(stream, "MODERATION", Map.of("message", text, "level", policy.level()));
    long start = System.currentTimeMillis();
    // Local restrictions remain authoritative and save an unnecessary provider request.
    Verdict local = new AiToolkitFactory.LocalToolkit(json).moderation().analyze(text, policy);
    if (!local.category().equals("SAFE")) {
      response(id, local, start, "LOCAL");
      return new Analysis(id, local);
    }
    boolean acquired = slots.tryAcquire();
    try {
      if (!acquired) throw new IllegalStateException("AI concurrency limit reached");
      Verdict result = toolkit().moderation().analyze(text, policy);
      response(id, result, start, configured() ? "SUCCEEDED" : "LOCAL");
      return new Analysis(id, result);
    } catch (Exception e) {
      log.warn("Moderation provider {} failed: {}", provider, e.getMessage());
      Verdict result =
          new Verdict(local.category(), local.confidence(), local.reason(), "LOCAL_RULES");
      // The database constraint only accepts PENDING, SUCCEEDED, FAILED, or LOCAL.
      // This response is produced by the local rules after the provider fails.
      response(id, result, start, "LOCAL");
      return new Analysis(id, result);
    } finally {
      if (acquired) slots.release();
    }
  }

  public Editorial editorial(UUID stream, JsonNode input) {
    UUID id = request(stream, "EDITORIAL", input);
    long start = System.currentTimeMillis();
    boolean acquired = slots.tryAcquire();
    try {
      if (!acquired) throw new IllegalStateException("AI concurrency limit reached");
      var factory = toolkit();
      JsonNode result = factory.editorial().compose(input);
      response(id, result, start, configured() ? "SUCCEEDED" : "LOCAL");
      return new Editorial(id, result, factory.provider());
    } catch (Exception e) {
      log.warn("Editorial provider {} failed: {}", provider, e.getMessage());
      var result = new AiToolkitFactory.LocalToolkit(json).editorial().compose(input);
      ((com.fasterxml.jackson.databind.node.ObjectNode) result)
          .put("summary", Messages.text("aiServiceEditorialText04"));
      response(id, result, start, "FAILED");
      return new Editorial(id, result, "UNAVAILABLE");
    } finally {
      if (acquired) slots.release();
    }
  }

  public JsonNode context(UUID stream) {
    return json.valueToTree(
        Map.of(
            "title",
            db.one("SELECT title FROM streams WHERE id=?", stream).get("title"),
            "messages",
            db.list(
                "SELECT m.content FROM chat_messages m JOIN chat_rooms r ON r.id=m.room_id WHERE"
                    + " r.stream_id=? AND m.status='VISIBLE' ORDER BY m.created_at DESC LIMIT 150",
                stream),
            "highlights",
            db.list(
                "SELECT at_seconds,reason,source FROM stream_highlights WHERE stream_id=? ORDER BY"
                    + " at_seconds DESC LIMIT 20",
                stream),
            "transcript",
            db.list(
                "SELECT c.text,c.start_seconds FROM subtitle_cues c JOIN transcripts t ON"
                    + " t.id=c.transcript_id WHERE t.stream_id=? ORDER BY c.start_seconds DESC"
                    + " LIMIT 100",
                stream)));
  }
}
