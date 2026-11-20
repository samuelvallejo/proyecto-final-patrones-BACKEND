package com.streamguard.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AdminRulesTest {
  @Test
  void categorySlugsDropAccentsAndSymbols() {
    assertEquals("cafe-resume", AdminService.slug("  Café & Résumé "));
    assertEquals("naive-live-music", AdminService.slug("Naïve   live music!"));
    assertEquals("", AdminService.slug("?!*"));
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
