package com.streamguard;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/** Seals form fields exactly like the browser's WebCrypto code (RSA-OAEP-256 + AES-256-GCM). */
public final class SealedCredentials {
  private static final SecureRandom RANDOM = new SecureRandom();

  private SealedCredentials() {}

  public static Map<String, String> seal(String token, String publicKey, Map<String, ?> fields) {
    try {
      byte[] aesKey = new byte[32];
      byte[] iv = new byte[12];
      RANDOM.nextBytes(aesKey);
      RANDOM.nextBytes(iv);
      Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
      aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
      aes.updateAAD(token.getBytes(StandardCharsets.US_ASCII));
      byte[] data = aes.doFinal(new ObjectMapper().writeValueAsBytes(fields));
      var key =
          KeyFactory.getInstance("RSA")
              .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
      Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
      rsa.init(
          Cipher.ENCRYPT_MODE,
          key,
          new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
      var encoder = Base64.getEncoder();
      return Map.of(
          "token", token,
          "key", encoder.encodeToString(rsa.doFinal(aesKey)),
          "iv", encoder.encodeToString(iv),
          "data", encoder.encodeToString(data));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
