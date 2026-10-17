package com.streamguard.patterns;

import com.streamguard.patterns.AiToolkitFactory.ModerationAnalyzer;
import com.streamguard.patterns.AiToolkitFactory.Verdict;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Recovers provider failures using local rules without converting ordinary chat into review cases.
 */
public final class FallbackModerationDecorator extends ModerationAnalyzerDecorator {
  private final ModerationAnalyzer fallback;
  private final Consumer<RuntimeException> onFailure;

  public FallbackModerationDecorator(
      ModerationAnalyzer delegate,
      ModerationAnalyzer fallback,
      Consumer<RuntimeException> onFailure) {
    super(delegate);
    this.fallback = Objects.requireNonNull(fallback);
    this.onFailure = Objects.requireNonNull(onFailure);
  }

  @Override
  public Verdict analyze(String text, ModerationPolicy policy) {
    try {
      return Objects.requireNonNull(delegate.analyze(text, policy));
    } catch (RuntimeException failure) {
      onFailure.accept(failure);
      return fallback.analyze(text, policy);
    }
  }
}
