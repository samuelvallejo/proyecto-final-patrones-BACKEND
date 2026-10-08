package com.streamguard.auth;

import com.streamguard.core.Db;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** All the SQL about users. It has no login rules, it only reads and writes rows. */
@Repository
public class UserRepository {
  private final Db db;

  public UserRepository(Db db) {
    this.db = db;
  }

  /** Insert the user and the rows every new account starts with. Returns the new user id. */
  public UUID createAccount(String username, String email, String passwordHash) {
    var id =
        db.insert(
            "INSERT INTO users(username,email,password_hash) VALUES (?,?,?) RETURNING id",
            username.toLowerCase(Locale.ROOT),
            email.toLowerCase(Locale.ROOT),
            passwordHash);
    db.exec("INSERT INTO user_profiles(user_id,display_name) VALUES (?,?)", id, username);
    db.exec(
        "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name='VIEWER'", id);
    db.exec("INSERT INTO notification_preferences(user_id) VALUES (?)", id);
    db.exec(
        "INSERT INTO user_consents(user_id,purpose,version) VALUES (?,'AI_CHAT_ANALYSIS','1.0')",
        id);
    return id;
  }

  public Optional<Map<String, Object>> findActiveByEmail(String email) {
    return db.optional(
        "SELECT * FROM users WHERE email=? AND status='ACTIVE'", email.toLowerCase(Locale.ROOT));
  }

  public Map<String, Object> profile(UUID user) {
    return db.one(
        "SELECT u.id,u.username,u.email,p.display_name,p.bio,c.id AS channel_id FROM users u JOIN"
            + " user_profiles p ON p.user_id=u.id LEFT JOIN channels c ON c.owner_id=u.id WHERE"
            + " u.id=?",
        user);
  }
}
