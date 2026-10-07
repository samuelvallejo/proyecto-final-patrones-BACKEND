package com.streamguard.auth;

import com.streamguard.core.*;
import com.streamguard.i18n.Messages;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private final Db db;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

  public AuthService(Db db) {
    this.db = db;
  }

  public static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public UUID resolve(String token) {
    if (token == null || token.length() > 200) return null;
    return db.optional(
            "SELECT s.user_id FROM auth_sessions s JOIN users u ON u.id=s.user_id WHERE"
                + " s.token_hash=? AND s.expires_at>now() AND u.status='ACTIVE'",
            hash(token))
        .map(x -> Db.id(x.get("user_id")))
        .orElse(null);
  }

  public static UUID current() {
    var a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !(a.getPrincipal() instanceof UUID id))
      throw new ApiError(401, Messages.text("authServiceCurrentText01"));
    return id;
  }

  @Transactional
  public Map<String, Object> register(String username, String email, String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ApiError(400, Messages.text("authServiceRegisterText02"));
    var id =
        db.insert(
            "INSERT INTO users(username,email,password_hash) VALUES (?,?,?) RETURNING id",
            username.toLowerCase(Locale.ROOT),
            email.toLowerCase(Locale.ROOT),
            encoder.encode(password));
    db.exec("INSERT INTO user_profiles(user_id,display_name) VALUES (?,?)", id, username);
    db.exec(
        "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name='VIEWER'", id);
    db.exec("INSERT INTO notification_preferences(user_id) VALUES (?)", id);
    db.exec(
        "INSERT INTO user_consents(user_id,purpose,version) VALUES (?,'AI_CHAT_ANALYSIS','1.0')",
        id);
    return session(id);
  }

  public Map<String, Object> login(String email, String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new ApiError(401, Messages.text("authServiceLoginText03"));
    var user =
        db.optional(
            "SELECT * FROM users WHERE email=? AND status='ACTIVE'",
            email.toLowerCase(Locale.ROOT));
    if (user.isEmpty() || !encoder.matches(password, user.get().get("password_hash").toString()))
      throw new ApiError(401, Messages.text("authServiceLoginText03"));
    return session(Db.id(user.get().get("id")));
  }

  private Map<String, Object> session(UUID user) {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    db.exec(
        "INSERT INTO auth_sessions(user_id,token_hash,expires_at) VALUES (?,?,now()+interval '7"
            + " days')",
        user,
        hash(token));
    return Map.of("token", token, "user", profile(user));
  }

  public Map<String, Object> profile(UUID user) {
    return db.one(
        "SELECT u.id,u.username,u.email,p.display_name,p.bio,c.id AS channel_id FROM users u JOIN"
            + " user_profiles p ON p.user_id=u.id LEFT JOIN channels c ON c.owner_id=u.id WHERE"
            + " u.id=?",
        user);
  }

  public void logout(String bearer) {
    if (bearer != null && bearer.startsWith("Bearer "))
      db.exec("DELETE FROM auth_sessions WHERE token_hash=?", hash(bearer.substring(7)));
  }
}
