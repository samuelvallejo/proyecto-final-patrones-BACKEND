package com.streamguard.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AdminRulesTest {
  @Test
  void categorySlugsDropAccentsAndSymbols() {
    assertEquals("ciencia-tecnologia", AdminService.slug("  Ciencia & Tecnología "));
    assertEquals("musica-en-vivo", AdminService.slug("Música   en vivo!"));
    assertEquals("", AdminService.slug("¡¿?!"));
    assertTrue(AdminService.slug("a".repeat(80)).length() <= 64);
  }

  @Test
  void configuredAdministratorsAreCaseInsensitiveAndIgnoreBlanks() {
    var roles = new com.streamguard.auth.RoleService(null, " Samuel, johan ;;  ");
    assertTrue(roles.isConfigured("SAMUEL"));
    assertTrue(roles.isConfigured("johan"));
    assertFalse(roles.isConfigured("jesus"));
    assertFalse(new com.streamguard.auth.RoleService(null, "").isConfigured(""));
  }
}
