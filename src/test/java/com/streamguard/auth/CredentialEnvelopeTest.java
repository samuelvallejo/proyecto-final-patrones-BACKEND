package com.streamguard.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamguard.SealedCredentials;
import com.streamguard.core.ApiError;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CredentialEnvelopeTest {
  private final CredentialEnvelope envelope = new CredentialEnvelope(new ObjectMapper());

  private CredentialEnvelope.Sealed sealed(Map<String, String> parts) {
    return new CredentialEnvelope.Sealed(parts.get("token"), parts.get("key"), parts.get("iv"), parts.get("data"));
  }

  @Test
  void tokensUseTheGroupedFormatAndAreUnique() {
    var seen = new HashSet<String>();
    for (int i = 0; i < 200; i++) {
      String token = envelope.issue().get("token").toString();
      assertTrue(CredentialEnvelope.TOKEN.matcher(token).matches(), token);
      assertTrue(seen.add(token));
    }
  }

  @Test
  void opensSealedFieldsOnceAndHidesThePassword() {
    var challenge = envelope.issue();
    String token = challenge.get("token").toString();
    var parts =
        SealedCredentials.seal(
            token,
            challenge.get("publicKey").toString(),
            Map.of("email", "someone@gmail.com", "password", "Synthetic-Only-42!"));
    assertFalse(parts.toString().contains("Synthetic-Only-42!"));
    assertFalse(parts.toString().contains("someone@gmail.com"));

    var fields = envelope.open(sealed(parts));
    assertEquals("someone@gmail.com", fields.path("email").asText());
    assertEquals("Synthetic-Only-42!", fields.path("password").asText());
    // Replaying the same request fails because the token was consumed.
    assertThrows(ApiError.class, () -> envelope.open(sealed(parts)));
  }

  @Test
  void rejectsUnknownTokensAndTokensSwappedBetweenEnvelopes() {
    var first = envelope.issue();
    var second = envelope.issue();
    var parts =
        SealedCredentials.seal(
            first.get("token").toString(), first.get("publicKey").toString(), Map.of("email", "a@gmail.com"));
    // The ciphertext is bound to the first token, so presenting it with the second fails.
    var swapped = new CredentialEnvelope.Sealed(second.get("token").toString(), parts.get("key"), parts.get("iv"), parts.get("data"));
    assertThrows(ApiError.class, () -> envelope.open(swapped));
    assertThrows(ApiError.class, () -> envelope.open(new CredentialEnvelope.Sealed("AAAAA-BBBBB-CCCCC", parts.get("key"), parts.get("iv"), parts.get("data"))));
    assertThrows(ApiError.class, () -> envelope.open(new CredentialEnvelope.Sealed("not-a-token", "x", "y", "z")));
    assertThrows(ApiError.class, () -> envelope.open(null));
  }

  @Test
  void rejectsTamperedCiphertext() {
    var challenge = envelope.issue();
    var parts =
        SealedCredentials.seal(
            challenge.get("token").toString(), challenge.get("publicKey").toString(), Map.of("email", "a@gmail.com"));
    byte[] data = java.util.Base64.getDecoder().decode(parts.get("data"));
    data[0] ^= 1;
    var tampered =
        new CredentialEnvelope.Sealed(parts.get("token"), parts.get("key"), parts.get("iv"), java.util.Base64.getEncoder().encodeToString(data));
    assertThrows(ApiError.class, () -> envelope.open(tampered));
  }
}
