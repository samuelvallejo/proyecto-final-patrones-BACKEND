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
    boolean allowLinks)
    implements Prototype<ModerationPolicy> {
  public ModerationPolicy {
    if (level == null
        || !Set.of("RELAXED", "BALANCED", "STRICT").contains(level)
        || muteSeconds < 30
        || muteSeconds > 86400
        || !Double.isFinite(reviewThreshold)
        || !Double.isFinite(blockThreshold)
        || reviewThreshold < 0
        || blockThreshold > 1
        || reviewThreshold > blockThreshold) throw new IllegalArgumentException("Invalid policy");
    blockedWords = restrictions(blockedWords, 50, 80);
    blockedTopics = restrictions(blockedTopics, 30, 120);
  }

  private static List<String> restrictions(List<String> values, int count, int length) {
    if (values == null
        || values.size() > count
        || values.stream()
            .anyMatch(value -> value == null || value.isBlank() || value.length() > length))
      throw new IllegalArgumentException("Invalid policy restrictions");
    return List.copyOf(values);
  }

  @Override
  public ModerationPolicy copy() {
    return new ModerationPolicy(
        level,
        autoHide,
        autoMute,
        muteSeconds,
        reviewThreshold,
        blockThreshold,
        blockedWords,
        blockedTopics,
        allowLinks);
  }

  public Builder toBuilder() {
    return new Builder()
        .level(level)
        .autoHide(autoHide)
        .autoMute(autoMute)
        .muteSeconds(muteSeconds)
        .thresholds(reviewThreshold, blockThreshold)
        .blockedWords(blockedWords)
        .blockedTopics(blockedTopics)
        .allowLinks(allowLinks);
  }

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
      words = restrictions(value, 50, 80);
      return this;
    }

    public Builder blockedTopics(List<String> value) {
      topics = restrictions(value, 30, 120);
      return this;
    }

    public Builder allowLinks(boolean value) {
      links = value;
      return this;
    }

    public ModerationPolicy build() {
      return new ModerationPolicy(level, hide, mute, seconds, review, block, words, topics, links);
    }
  }
}
