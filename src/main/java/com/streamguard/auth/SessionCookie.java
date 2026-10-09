package com.streamguard.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.ResponseCookie;

/** Persistent first-party session. The API reverse proxy keeps this cookie out of JavaScript. */
public final class SessionCookie {
  public static final String NAME = "streamguard_session";
  private SessionCookie() {}

  public static String token(HttpServletRequest request) {
    var cookies = request.getCookies();
    if (cookies != null) for (var cookie : cookies) if (NAME.equals(cookie.getName())) return cookie.getValue();
    return null;
  }

  public static String header(String value, HttpServletRequest request) {
    boolean secure = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
    return ResponseCookie.from(NAME, value).httpOnly(true).secure(secure).sameSite("Lax")
        .path("/api").maxAge(value.isEmpty() ? Duration.ZERO : TokenGenerator.LIFETIME).build().toString();
  }
}
