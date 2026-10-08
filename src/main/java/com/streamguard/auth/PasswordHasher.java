package com.streamguard.auth;

import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** Everything about passwords: the length rule, hashing and checking. */
@Component
public class PasswordHasher {
  /** BCrypt only reads the first 72 bytes, so longer passwords are rejected. */
  private static final int MAX_BYTES = 72;

  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

  public boolean isTooLong(String password) {
    return password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
  }

  public String encode(String password) {
    return encoder.encode(password);
  }

  public boolean matches(String password, String passwordHash) {
    return encoder.matches(password, passwordHash);
  }
}
