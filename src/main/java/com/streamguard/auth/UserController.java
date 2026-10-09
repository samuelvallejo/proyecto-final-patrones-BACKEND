package com.streamguard.auth;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** Data of the logged-in user. Kept apart from AuthController, which only opens and closes sessions. */
@RestController
@RequestMapping("/api/users")
public class UserController {
  private final AuthService auth;

  public UserController(AuthService auth) {
    this.auth = auth;
  }

  @GetMapping("/me")
  public Map<String, Object> me() {
    return auth.profile(AuthService.current());
  }
}
