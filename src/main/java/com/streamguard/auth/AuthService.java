package com.streamguard.auth;

import com.streamguard.core.ApiError;
import com.streamguard.core.Db;
import com.streamguard.i18n.Messages;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The login rules. It decides what is allowed and delegates the details:
 * passwords to {@link PasswordHasher}, tokens to {@link TokenGenerator} and {@link TokenHasher},
 * and the database to {@link UserRepository} and {@link SessionRepository}.
 */
@Service
public class AuthService {
  private final UserRepository users;
  private final SessionRepository sessions;
  private final PasswordHasher passwords;
  private final TokenGenerator tokens;

  public AuthService(
      UserRepository users,
      SessionRepository sessions,
      PasswordHasher passwords,
      TokenGenerator tokens) {
    this.users = users;
    this.sessions = sessions;
    this.passwords = passwords;
    this.tokens = tokens;
  }

  public UUID resolve(String token) {
    if (token == null || token.length() > 200) return null;
    return sessions.findActiveUser(TokenHasher.hash(token)).orElse(null);
  }

  public static UUID current() {
    var a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !(a.getPrincipal() instanceof UUID id))
      throw new ApiError(401, Messages.text("authServiceCurrentText01"));
    return id;
  }

  @Transactional
  public Map<String, Object> register(String username, String email, String password) {
    if (passwords.isTooLong(password))
      throw new ApiError(400, Messages.text("authServiceRegisterText02"));
    UUID id = users.createAccount(username, email, passwords.encode(password));
    return openSession(id);
  }

  public Map<String, Object> login(String email, String password) {
    if (passwords.isTooLong(password))
      throw new ApiError(401, Messages.text("authServiceLoginText03"));
    var user = users.findActiveByEmail(email);
    if (user.isEmpty() || !passwords.matches(password, user.get().get("password_hash").toString()))
      throw new ApiError(401, Messages.text("authServiceLoginText03"));
    return openSession(Db.id(user.get().get("id")));
  }

  public Map<String, Object> profile(UUID user) {
    return users.profile(user);
  }

  public void logout(String bearer) {
    if (bearer != null && bearer.startsWith("Bearer "))
      sessions.delete(TokenHasher.hash(bearer.substring(7)));
  }

  /** Create a session for the user and return the token (shown once) with the profile. */
  private Map<String, Object> openSession(UUID user) {
    String token = tokens.generate();
    sessions.create(user, TokenHasher.hash(token));
    return Map.of("token", token, "user", users.profile(user));
  }
}
