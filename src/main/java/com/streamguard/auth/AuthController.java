package com.streamguard.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthService auth;
  private final FieldCrypto crypto;

  public AuthController(AuthService auth, FieldCrypto crypto) {
    this.auth = auth;
    this.crypto=crypto;
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

  public record Login(@NotBlank @Email @Size(max = 254) @Pattern(regexp = "(?i)^[^@\\s]+@(gmail\\.com|hotmail\\.com)$") String email, @NotBlank @Size(max = 72) String password) {}

  @PostMapping("/register")
  public ResponseEntity<?> register(@Valid @RequestBody Register r, HttpServletRequest request) {
    return opened(auth.register(r.username(), r.email(), r.password()), request);
  }

  @PostMapping("/login")
  public ResponseEntity<?> login(@Valid @RequestBody Login r, HttpServletRequest request) {
    return opened(auth.login(r.email(), r.password()), request);
  }

  @PostMapping("/logout")
  public ResponseEntity<?> logout(HttpServletRequest request) {
    String token=SessionCookie.token(request);
    if (token!=null) auth.logout("Bearer " + crypto.decrypt(token));
    return ResponseEntity.ok().header("Set-Cookie", SessionCookie.header("", request)).header("Cache-Control", "no-store").body(Map.of("ok", true));
  }

  @PostMapping("/connection-ticket")
  public Map<String, String> ticket(HttpServletRequest request) {return Map.of("ticket", auth.issueSocketTicket(AuthService.current(),crypto.decrypt(SessionCookie.token(request))));}

  private ResponseEntity<?> opened(Map<String, Object> session, HttpServletRequest request) {
    return ResponseEntity.ok().header("Set-Cookie", SessionCookie.header(crypto.encrypt(session.get("token").toString()), request))
        .header("Cache-Control", "no-store").body(Map.of("ok", true));
  }
}
