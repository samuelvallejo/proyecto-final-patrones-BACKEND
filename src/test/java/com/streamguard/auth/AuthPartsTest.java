package com.streamguard.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Tests the small classes of the login module without a database. */
class AuthPartsTest {
  @Test
  void tokenHasherGivesTheKnownSha256OfAbc() {
    assertEquals(
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        TokenHasher.hash("abc"));
  }

  @Test
  void tokenHasherIsStableAndSeparatesDifferentTokens() {
    assertEquals(TokenHasher.hash("one"), TokenHasher.hash("one"));
    assertNotEquals(TokenHasher.hash("one"), TokenHasher.hash("two"));
  }

  private static final String SECRET = "a-test-secret-with-more-than-32-characters";

  @Test
  void tokenIsAJwtWithThreePartsAndCarriesTheUser() {
    var generator = new TokenGenerator(SECRET);
    var user = UUID.randomUUID();
    String token = generator.generate(user);
    assertEquals(3, token.split("\\.").length);
    assertTrue(token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"));
    assertEquals(Optional.of(user), generator.userOf(token));
  }

  @Test
  void twoTokensForTheSameUserAreDifferent() {
    var generator = new TokenGenerator(SECRET);
    var user = UUID.randomUUID();
    assertNotEquals(generator.generate(user), generator.generate(user));
  }

  @Test
  void tokenStopsBeingValidWhenItExpires() {
    var generator = new TokenGenerator(SECRET);
    var user = UUID.randomUUID();
    Instant now = Instant.now();
    String token = generator.generate(user, now);
    assertTrue(generator.userOf(token, now.plus(TokenGenerator.LIFETIME).minusSeconds(1)).isPresent());
    assertTrue(generator.userOf(token, now.plus(TokenGenerator.LIFETIME)).isEmpty());
  }

  @Test
  void aChangedOrForeignTokenIsRejected() {
    var generator = new TokenGenerator(SECRET);
    var user = UUID.randomUUID();
    String token = generator.generate(user);
    String[] parts = token.split("\\.");
    var other = Base64.getUrlEncoder().withoutPadding().encodeToString(
        ("{\"sub\":\"" + UUID.randomUUID() + "\",\"exp\":9999999999}").getBytes(StandardCharsets.UTF_8));
    assertTrue(generator.userOf(parts[0] + "." + other + "." + parts[2]).isEmpty());
    assertTrue(new TokenGenerator(SECRET + "-another").userOf(token).isEmpty());
    assertTrue(generator.userOf(parts[0] + "." + parts[1]).isEmpty());
    assertTrue(generator.userOf(parts[0] + "." + parts[1] + ".").isEmpty());
    assertTrue(generator.userOf("not-a-token").isEmpty());
    assertTrue(generator.userOf("").isEmpty());
  }

  @Test
  void aTokenWithoutSignatureAlgorithmIsRejected() {
    var generator = new TokenGenerator(SECRET);
    var enc = Base64.getUrlEncoder().withoutPadding();
    String header = enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload = enc.encodeToString(
        ("{\"sub\":\"" + UUID.randomUUID() + "\",\"exp\":9999999999}").getBytes(StandardCharsets.UTF_8));
    assertTrue(generator.userOf(header + "." + payload + ".").isEmpty());
  }

  @Test
  void aShortSecretIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> new TokenGenerator("too-short"));
  }

  @Test
  void passwordHasherChecksTheRightPasswordOnly() {
    var hasher = new PasswordHasher();
    String stored = hasher.encode("a-long-secret-1");
    assertNotEquals("a-long-secret-1", stored);
    assertTrue(hasher.matches("a-long-secret-1", stored));
    assertFalse(hasher.matches("another-secret-1", stored));
  }

  @Test
  void passwordLengthIsMeasuredInBytesNotCharacters() {
    var hasher = new PasswordHasher();
    assertFalse(hasher.isTooLong("a".repeat(72)));
    assertTrue(hasher.isTooLong("a".repeat(73)));
    // The euro sign takes 3 bytes in UTF-8: 24 of them are 72 bytes, 25 are 75.
    assertFalse(hasher.isTooLong("\u20ac".repeat(24)));
    assertTrue(hasher.isTooLong("\u20ac".repeat(25)));
  }
}
