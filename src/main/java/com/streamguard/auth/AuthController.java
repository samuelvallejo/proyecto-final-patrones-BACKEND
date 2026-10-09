package com.streamguard.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  public record Register(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,32}") String username,
      @NotBlank
          @Email
          @Pattern(regexp = "(?i)^[^@\\s]+@(gmail\\.com|hotmail\\.com)$")
          @Size(max = 254)
          String email,
      @NotBlank @Size(min = 10, max = 72) String password,
      @AssertTrue boolean aiConsent) {}

  public record Login(@NotBlank @Email String email, @NotBlank @Size(max = 72) String password) {}

  @PostMapping("/register")
  public Map<String, Object> register(@Valid @RequestBody Register r) {
    return auth.register(r.username(), r.email(), r.password());
  }

  @PostMapping("/login")
  public Map<String, Object> login(@Valid @RequestBody Login r) {
    return auth.login(r.email(), r.password());
  }

  @PostMapping("/logout")
  public Map<String, Boolean> logout(
      @RequestHeader(value = "Authorization", required = false) String token) {
    auth.logout(token);
    return Map.of("ok", true);
  }
}
