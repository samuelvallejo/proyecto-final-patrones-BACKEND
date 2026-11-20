package com.streamguard.core;

public class ApiError extends RuntimeException {
  public final int status;
  /** When set, the message is safe to show even on login/registration and the code goes to the client. */
  public final String code;

  public ApiError(int status, String message) {
    this(status, message, null);
  }

  public ApiError(int status, String message, String code) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
