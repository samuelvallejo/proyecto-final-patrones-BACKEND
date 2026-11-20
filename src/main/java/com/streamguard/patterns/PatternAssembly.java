package com.streamguard.patterns;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamguard.ai.AiGateway;
import com.streamguard.ai.AiService;
import com.streamguard.core.Db;
import com.streamguard.core.PlatformService;
import com.streamguard.live.LiveHub;
import com.streamguard.patterns.AiToolkitFactory.ModerationAnalyzer;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * Hand-written assembly of the design patterns. Spring only creates the application services
 * (controllers, repositories, services); every pattern object is built here with {@code new}, so
 * none of the nine patterns depends on the framework or on its dependency injection.
 */
public final class PatternAssembly {
  private PatternAssembly() {}

  /** Facade: the creator studio, built over the shared Prototype registry of policy templates. */
  public static CreatorStudioFacade creatorStudio(PlatformService platform, AiService ai) {
    return new CreatorStudioFacade(platform, ai, ModerationPresets.shared());
  }

  /** Bridge: the delivery that stores a notice and also pushes it to the user in real time. */
  public static NotificationBridge.Delivery realtimeDelivery(Db db, LiveHub hub) {
    return new NotificationBridge.RealtimeDelivery(db, hub);
  }

  /**
   * Abstract Factory: chooses the family of AI tools (moderation + editorial) that belong to the
   * configured provider. The Adapters for Gemini and Ollama are passed in as gateways.
   */
  public static AiToolkitFactory toolkit(
      String provider, AiGateway gemini, AiGateway ollama, ObjectMapper json) {
    return switch (provider.toUpperCase(Locale.ROOT)) {
      case "OLLAMA" -> new AiToolkitFactory.OllamaToolkit(ollama, json);
      case "GEMINI" -> new AiToolkitFactory.GeminiToolkit(gemini, json);
      default -> new AiToolkitFactory.LocalToolkit(json);
    };
  }

  /**
   * Decorator chain for each chat message, from the outside in: the channel's local rules, then a
   * fallback to those rules if the provider fails, then a concurrency limit, then the provider.
   */
  public static ModerationAnalyzer moderationChain(
      ModerationAnalyzer provider,
      ModerationAnalyzer localRules,
      Semaphore slots,
      Consumer<RuntimeException> onFailure) {
    ModerationAnalyzer limited = new ConcurrencyLimitedModerationDecorator(provider, slots);
    ModerationAnalyzer resilient = new FallbackModerationDecorator(limited, localRules, onFailure);
    return new LocalRulesModerationDecorator(resilient, localRules);
  }
}
