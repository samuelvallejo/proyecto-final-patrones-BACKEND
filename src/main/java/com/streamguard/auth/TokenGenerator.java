package com.streamguard.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Creates and checks the JWTs that identify a logged-in session.
 *
 * <p>A token has three parts separated by dots: {@code header.payload.signature}. The signature is
 * HMAC-SHA-256 (HS256) over the first two parts, made with a secret only the server knows, so a
 * changed token is rejected. The payload carries {@code sub} (the user), {@code iat}, {@code exp}
 * (when it stops being valid) and {@code jti} (a unique id).
 */
@Component
public class TokenGenerator {
  /** How long a token is valid. The session row in the database must use the same time. */
  public static final Duration LIFETIME = Duration.ofHours(2);

  private static final System.Logger LOG = System.getLogger(TokenGenerator.class.getName());
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final String HEADER =
      ENCODER.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
  private static final int MIN_SECRET_BYTES = 32;

  private final byte[] key;

  /**
   * @param secret the signing secret (JWT_SECRET), at least 32 characters. If it is empty a random
   *     one is made at startup: it works, but every restart logs everybody out.
   */
  public TokenGenerator(@Value("${app.jwt-secret:}") String secret) {
    if (secret == null || secret.isBlank()) {
      LOG.log(System.Logger.Level.WARNING, "JWT_SECRET is not set: using a random secret for this run");
      key = new byte[MIN_SECRET_BYTES];
      new SecureRandom().nextBytes(key);
    } else {
      key = secret.getBytes(StandardCharsets.UTF_8);
      if (key.length < MIN_SECRET_BYTES)
        throw new IllegalArgumentException("JWT_SECRET needs at least 32 characters");
    }
  }

  public String generate(UUID user) {
    return generate(user, Instant.now());
  }

  String generate(UUID user, Instant now) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("sub", user.toString());
    claims.put("iat", now.getEpochSecond());
    claims.put("exp", now.plus(LIFETIME).getEpochSecond());
    claims.put("jti", UUID.randomUUID().toString());
    try {
      String signingInput = HEADER + "." + ENCODER.encodeToString(JSON.writeValueAsBytes(claims));
      return signingInput + "." + ENCODER.encodeToString(sign(signingInput));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The user of a token whose signature is right and that has not expired, otherwise empty. */
  public Optional<UUID> userOf(String token) {
    return userOf(token, Instant.now());
  }

  Optional<UUID> userOf(String token, Instant now) {
    try {
      String[] parts = token.split("\\.", -1);
      if (parts.length != 3 || !parts[0].equals(HEADER)) return Optional.empty();
      byte[] expected = sign(parts[0] + "." + parts[1]);
      if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2])))
        return Optional.empty();
      JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
      if (now.getEpochSecond() >= claims.path("exp").asLong(0)) return Optional.empty();
      return Optional.of(UUID.fromString(claims.path("sub").asText()));
    } catch (java.io.IOException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private byte[] sign(String signingInput) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
