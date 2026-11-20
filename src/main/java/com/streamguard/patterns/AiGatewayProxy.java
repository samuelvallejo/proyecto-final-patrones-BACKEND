package com.streamguard.patterns;

import com.fasterxml.jackson.databind.JsonNode;
import com.streamguard.ai.AiGateway;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Proxy (protection): stands in front of the real AI adapter with the same {@link AiGateway}
 * contract. It decides whether a request may reach the provider at all: an administrator can
 * switch the AI off or cap how many requests per hour the platform sends. Callers cannot tell the
 * proxy from the adapter; a refused call fails like an unavailable provider, so the existing local
 * fallbacks answer instead.
 */
public final class AiGatewayProxy implements AiGateway {
  static final long HOUR_MILLIS = 3_600_000L;

  /** The rules the proxy enforces; the platform settings implement them. */
  public interface AccessRules {
    boolean enabled();

    int hourlyLimit();

    AccessRules OPEN =
        new AccessRules() {
          public boolean enabled() {
            return true;
          }

          public int hourlyLimit() {
            return Integer.MAX_VALUE;
          }
        };
  }

  /** Thrown instead of contacting the provider. */
  public static final class AccessDenied extends IllegalStateException {
    public final String reason;

    AccessDenied(String reason) {
      super("AI request refused by the gateway proxy: " + reason);
      this.reason = reason;
    }
  }

  public record Stats(long forwarded, long refused, int lastHour, int hourlyLimit, boolean enabled) {}

  private final AiGateway real;
  private final AccessRules rules;
  private final LongSupplier clock;
  private final ArrayDeque<Long> window = new ArrayDeque<>();
  private final AtomicLong forwarded = new AtomicLong();
  private final AtomicLong refused = new AtomicLong();

  public AiGatewayProxy(AiGateway real, AccessRules rules) {
    this(real, rules, System::currentTimeMillis);
  }

  public AiGatewayProxy(AiGateway real, AccessRules rules, LongSupplier clock) {
    this.real = Objects.requireNonNull(real);
    this.rules = Objects.requireNonNull(rules);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public JsonNode generate(String instruction, JsonNode input, JsonNode schema) {
    if (!rules.enabled()) throw refuse("DISABLED");
    synchronized (window) {
      long now = clock.getAsLong();
      while (!window.isEmpty() && window.peekFirst() <= now - HOUR_MILLIS) window.pollFirst();
      if (window.size() >= rules.hourlyLimit()) throw refuse("HOURLY_LIMIT");
      window.addLast(now);
    }
    forwarded.incrementAndGet();
    return real.generate(instruction, input, schema);
  }

  public Stats stats() {
    int lastHour;
    synchronized (window) {
      long now = clock.getAsLong();
      while (!window.isEmpty() && window.peekFirst() <= now - HOUR_MILLIS) window.pollFirst();
      lastHour = window.size();
    }
    return new Stats(forwarded.get(), refused.get(), lastHour, rules.hourlyLimit(), rules.enabled());
  }

  private AccessDenied refuse(String reason) {
    refused.incrementAndGet();
    return new AccessDenied(reason);
  }
}
