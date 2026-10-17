package com.streamguard.patterns;

import com.streamguard.patterns.AiToolkitFactory.ModerationAnalyzer;
import java.util.Objects;

/** Decorator: additional moderation behavior keeps the same analyzer contract. */
public abstract class ModerationAnalyzerDecorator implements ModerationAnalyzer {
  protected final ModerationAnalyzer delegate;

  protected ModerationAnalyzerDecorator(ModerationAnalyzer delegate) {
    this.delegate = Objects.requireNonNull(delegate);
  }
}
