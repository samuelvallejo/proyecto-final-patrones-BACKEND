package com.streamguard.patterns;

import com.streamguard.patterns.AiToolkitFactory.ModerationAnalyzer;
import com.streamguard.patterns.AiToolkitFactory.Verdict;
import java.util.Objects;

/** Channel restrictions take precedence and avoid sending blocked messages to an AI provider. */
public final class LocalRulesModerationDecorator extends ModerationAnalyzerDecorator {
  private final ModerationAnalyzer local;

  public LocalRulesModerationDecorator(ModerationAnalyzer delegate, ModerationAnalyzer local) {
    super(delegate);
    this.local = Objects.requireNonNull(local);
  }

  @Override
  public Verdict analyze(String text, ModerationPolicy policy) {
    Verdict verdict = local.analyze(text, policy);
    return verdict.category().equals("SAFE") ? delegate.analyze(text, policy) : verdict;
  }
}
