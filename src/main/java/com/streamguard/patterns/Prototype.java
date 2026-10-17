package com.streamguard.patterns;

/** A domain object that can produce an independent copy without exposing its constructor. */
public interface Prototype<T> {
  T copy();
}
