package com.streamguard.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Turns a session token into the SHA-256 text that is stored in the database.
 *
 * <p>Only the hash is saved, so someone who reads the sessions table cannot use the tokens.
 */
public final class TokenHasher {
  private TokenHasher() {}

  public static String hash(String token) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
