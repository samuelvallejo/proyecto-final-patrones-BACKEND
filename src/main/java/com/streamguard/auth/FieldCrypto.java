package com.streamguard.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Authenticated encryption for private fields; the key belongs in server secret storage. */
@Component
public class FieldCrypto {
  private final byte[] key;
  private final SecureRandom random = new SecureRandom();
  public FieldCrypto(@Value("${app.data-encryption-key:}") String encoded) {
    try {key = Base64.getDecoder().decode(encoded);} catch (IllegalArgumentException e) {throw new IllegalStateException("Invalid DATA_ENCRYPTION_KEY");}
    if (key.length != 32) throw new IllegalStateException("DATA_ENCRYPTION_KEY must encode 32 random bytes");
  }
  public String encrypt(String value) {
    byte[] nonce = new byte[12]; random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
      byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
      byte[] payload = new byte[nonce.length + ciphertext.length];
      System.arraycopy(nonce, 0, payload, 0, nonce.length); System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
      return Base64.getEncoder().encodeToString(payload);
    } catch (GeneralSecurityException e) {throw new IllegalStateException("Private field encryption failed");}
  }
  public String decrypt(String value) {
    try {
      byte[] payload = Base64.getDecoder().decode(value);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, payload, 0, 12));
      return new String(cipher.doFinal(payload, 12, payload.length - 12), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException e) {throw new IllegalStateException("Private field decryption failed");}
  }
  public String lookup(String normalizedEmail) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return java.util.HexFormat.of().formatHex(mac.doFinal(("email:" + normalizedEmail).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {throw new IllegalStateException("Private field lookup failed");}
  }
}
