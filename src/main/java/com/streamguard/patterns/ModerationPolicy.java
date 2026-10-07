package com.streamguard.patterns;

import java.util.*;

/** Builder: assembles validated immutable per-channel rules. */
public record ModerationPolicy(
    String level,
    boolean autoHide,
    boolean autoMute,
    int muteSeconds,
    double reviewThreshold,
    double blockThreshold,
    List<String> blockedWords,
    List<String> blockedTopics,
    boolean allowLinks) {
  public static class Builder {
    private String level = "BALANCED";
    private boolean hide = true, mute = false, links = false;
    private int seconds = 300;
    private double review = .55, block = .85;
    private List<String> words = List.of(), topics = List.of();

    public Builder level(String value) {
      level = value;
      return this;
    }

    public Builder autoHide(boolean value) {
      hide = value;
      return this;
    }

    public Builder autoMute(boolean value) {
      mute = value;
      return this;
    }

    public Builder muteSeconds(int value) {
      seconds = value;
      return this;
    }

    public Builder thresholds(double r, double b) {
      review = r;
      block = b;
      return this;
    }

    public Builder blockedWords(List<String> value) {
      words = List.copyOf(value);
      return this;
    }

    public Builder blockedTopics(List<String> value) {
      topics = List.copyOf(value);
      return this;
    }

    public Builder allowLinks(boolean value) {
      links = value;
      return this;
    }

    public ModerationPolicy build() {
      if (!Set.of("RELAXED", "BALANCED", "STRICT").contains(level)
          || seconds < 30
          || seconds > 86400
          || !Double.isFinite(review)
          || !Double.isFinite(block)
          || review < 0
          || block > 1
          || review > block) throw new IllegalArgumentException("Invalid policy");
      return new ModerationPolicy(level, hide, mute, seconds, review, block, words, topics, links);
    }
  }
}
