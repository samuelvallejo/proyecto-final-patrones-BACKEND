package com.streamguard.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamguard.core.ApiError;
import com.streamguard.i18n.Messages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthService auth;
  private final FieldCrypto crypto;
  private final CredentialEnvelope envelope;
  private final Validator validator;
  private final ObjectMapper json;

  public AuthController(
      AuthService auth,
      FieldCrypto crypto,
      CredentialEnvelope envelope,
      Validator validator,
      ObjectMapper json) {
    this.auth = auth;
    this.crypto = crypto;
    this.envelope = envelope;
    this.validator = validator;
    this.json = json;
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

  /** One-use XXXXX-YYYYY-ZZZZZ token and public key; credentials never travel as readable JSON. */
  @PostMapping("/challenge")
  public Map<String, Object> challenge() {
    return envelope.issue();
  }

  @PostMapping("/register")
  public ResponseEntity<?> register(@RequestBody CredentialEnvelope.Sealed sealed, HttpServletRequest request) {
    Register r = unseal(sealed, Register.class);
    return opened(auth.register(r.username(), r.email(), r.password()), request);
  }

  @PostMapping("/login")
  public ResponseEntity<?> login(@RequestBody CredentialEnvelope.Sealed sealed, HttpServletRequest request) {
    Login r = unseal(sealed, Login.class);
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

  /** Decrypt the envelope, then apply the same bean validation the plain records always had. */
  private <T> T unseal(CredentialEnvelope.Sealed sealed, Class<T> type) {
    T fields;
    try {
      fields = json.treeToValue(envelope.open(sealed), type);
    } catch (ApiError e) {
      throw e;
    } catch (Exception e) {
      throw new ApiError(400, Messages.text("authFailed"));
    }
    if (fields == null || !validator.validate(fields).isEmpty())
      throw new ApiError(400, Messages.text("authFailed"));
    return fields;
  }

  private ResponseEntity<?> opened(Map<String, Object> session, HttpServletRequest request) {
    return ResponseEntity.ok().header("Set-Cookie", SessionCookie.header(crypto.encrypt(session.get("token").toString()), request))
        .header("Cache-Control", "no-store").body(Map.of("ok", true));
  }
}
