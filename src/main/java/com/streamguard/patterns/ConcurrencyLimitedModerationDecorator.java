package com.streamguard.patterns;

import com.streamguard.patterns.AiToolkitFactory.ModerationAnalyzer;
import com.streamguard.patterns.AiToolkitFactory.Verdict;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/** Shares the provider's capacity with editorial requests, always returning its permit. */
public final class ConcurrencyLimitedModerationDecorator extends ModerationAnalyzerDecorator {
  private final Semaphore slots;

  public ConcurrencyLimitedModerationDecorator(ModerationAnalyzer delegate, Semaphore slots) {
    super(delegate);
    this.slots = Objects.requireNonNull(slots);
  }

  @Override
  public Verdict analyze(String text, ModerationPolicy policy) {
    if (!slots.tryAcquire()) throw new IllegalStateException("AI concurrency limit reached");
    try {
      return delegate.analyze(text, policy);
    } finally {
      slots.release();
    }
  }
}
