package com.streamguard.auth;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** Creates the random tokens that identify a logged-in session. */
@Component
public class TokenGenerator {
  private static final int TOKEN_BYTES = 32;
  private final SecureRandom random = new SecureRandom();

  public String generate() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
