package com.streamguard.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamguard.core.ApiError;
import com.streamguard.i18n.Messages;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Keeps login and registration credentials out of readable request bodies.
 *
 * <p>The browser first asks for a one-use token shaped {@code XXXXX-YYYYY-ZZZZZ} and this server's
 * RSA public key. It encrypts the form fields with a fresh AES-256-GCM key, binds the ciphertext
 * to the token as additional authenticated data, and encrypts the AES key with RSA-OAEP (SHA-256).
 * The request therefore carries only the token and ciphertext. A token is consumed on first use and
 * expires after two minutes, so a captured request cannot be replayed.
 *
 * <p>The RSA key pair lives only in memory and is replaced on restart; outstanding tokens simply
 * expire. HTTPS still protects the transport, and the owner's own browser necessarily sees what the
 * owner types before it is encrypted.
 */
@Component
public class CredentialEnvelope {
  /** Crockford-style alphabet without 0/O/1/I to avoid confusing characters. */
  private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

  public static final Pattern TOKEN = Pattern.compile("[A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5}");
  static final long LIFETIME_MILLIS = 120_000;
  private static final int MAX_OPEN_TOKENS = 10_000;
  private static final int MAX_DATA_BYTES = 4096;
  private static final OAEPParameterSpec OAEP =
      new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

  /** What the browser sends instead of the plain form. */
  public record Sealed(String token, String key, String iv, String data) {}

  private final SecureRandom random = new SecureRandom();
  private final Map<String, Long> tokens = new ConcurrentHashMap<>();
  private final KeyPair keys;
  private final String publicKey;
  private final ObjectMapper json;

  public CredentialEnvelope(ObjectMapper json) {
    this.json = json;
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048, random);
      keys = generator.generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("RSA is unavailable", e);
    }
    publicKey = Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
  }

  /** Issue a fresh one-use token together with the public key that seals the form. */
  public Map<String, Object> issue() {
    long now = System.currentTimeMillis();
    tokens.entrySet().removeIf(entry -> entry.getValue() < now);
    if (tokens.size() >= MAX_OPEN_TOKENS) throw new ApiError(429, Messages.text("operationFailed"));
    String token = newToken();
    tokens.put(token, now + LIFETIME_MILLIS);
    return Map.of(
        "token", token,
        "publicKey", publicKey,
        "algorithm", "RSA-OAEP-256+A256GCM",
        "expiresInSeconds", LIFETIME_MILLIS / 1000);
  }

  /** Consume the token and return the decrypted form fields as JSON. */
  public JsonNode open(Sealed sealed) {
    if (sealed == null || sealed.token() == null || !TOKEN.matcher(sealed.token()).matches())
      throw rejected();
    Long expires = tokens.remove(sealed.token());
    if (expires == null || expires < System.currentTimeMillis()) throw rejected();
    try {
      Base64.Decoder decoder = Base64.getDecoder();
      byte[] wrappedKey = decoder.decode(required(sealed.key(), 512));
      byte[] iv = decoder.decode(required(sealed.iv(), 24));
      byte[] data = decoder.decode(required(sealed.data(), MAX_DATA_BYTES * 2));
      if (iv.length != 12 || data.length > MAX_DATA_BYTES) throw rejected();

      Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
      rsa.init(Cipher.DECRYPT_MODE, keys.getPrivate(), OAEP);
      byte[] aesKey = rsa.doFinal(wrappedKey);
      if (aesKey.length != 32) throw rejected();

      Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
      aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
      aes.updateAAD(sealed.token().getBytes(StandardCharsets.US_ASCII));
      JsonNode fields = json.readTree(aes.doFinal(data));
      if (fields == null || !fields.isObject()) throw rejected();
      return fields;
    } catch (ApiError e) {
      throw e;
    } catch (Exception e) {
      // Tampered, truncated or mismatched envelopes all look the same to the caller.
      throw rejected();
    }
  }

  String publicKey() {
    return publicKey;
  }

  private String newToken() {
    StringBuilder token = new StringBuilder(17);
    for (int i = 0; i < 15; i++) {
      if (i > 0 && i % 5 == 0) token.append('-');
      token.append(ALPHABET[random.nextInt(ALPHABET.length)]);
    }
    return token.toString();
  }

  private static String required(String value, int maxLength) {
    if (value == null || value.isEmpty() || value.length() > maxLength) throw rejected();
    return value;
  }

  private static ApiError rejected() {
    return new ApiError(400, Messages.text("authFailed"));
  }
}
