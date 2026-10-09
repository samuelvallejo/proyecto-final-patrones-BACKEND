package com.streamguard.auth;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FieldCryptoTest {
  private final FieldCrypto crypto = new FieldCrypto("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
  @Test void privateFieldsHaveRandomNoncesAndRejectTampering() {
    String input="private@gmail.com";
    String first=crypto.encrypt(input), second=crypto.encrypt(input);
    assertNotEquals(input,first); assertNotEquals(first,second); assertEquals(input,crypto.decrypt(first));
    byte[] payload=java.util.Base64.getDecoder().decode(first); payload[15]^=1;
    assertThrows(IllegalStateException.class,()->crypto.decrypt(java.util.Base64.getEncoder().encodeToString(payload)));
    assertEquals(crypto.lookup(input),crypto.lookup(input)); assertNotEquals(crypto.lookup(input),crypto.lookup("other@gmail.com"));
  }
  @Test void secretsMustBeProvisionedExplicitly() {
    assertThrows(IllegalStateException.class,()->new FieldCrypto(""));
    assertThrows(IllegalStateException.class,()->new FieldCrypto("short"));
  }
}
