package com.streamguard.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
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

  @Test
  void generatedTokensAreUrlSafeAndDifferent() {
    var generator = new TokenGenerator();
    var seen = new HashSet<String>();
    for (int i = 0; i < 50; i++) {
      String token = generator.generate();
      assertEquals(43, token.length());
      assertTrue(token.matches("[A-Za-z0-9_-]+"));
      assertTrue(seen.add(token));
    }
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
