package com.streamguard.auth;

import com.streamguard.core.Db;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** All the SQL about users. It has no login rules, it only reads and writes rows. */
@Repository
public class UserRepository implements org.springframework.boot.ApplicationRunner {
  private final Db db;
  private final FieldCrypto crypto;

  public UserRepository(Db db, FieldCrypto crypto) {
    this.db = db;
    this.crypto = crypto;
  }

  @Override
  public void run(org.springframework.boot.ApplicationArguments arguments) {
    for (var row : db.list("SELECT id,email FROM users WHERE email_encrypted IS NULL")) {
      String email = row.get("email").toString().strip().toLowerCase(Locale.ROOT);
      db.exec("UPDATE users SET email_encrypted=?,email_lookup=?,email=? WHERE id=? AND email_encrypted IS NULL",
          crypto.encrypt(email), crypto.lookup(email), row.get("id") + "@private.invalid", row.get("id"));
    }
  }

  /** Insert the user and the rows every new account starts with. Returns the new user id. */
  public UUID createAccount(String username, String email, String passwordHash) {
    UUID userId = UUID.randomUUID();
    String normalized = email.strip().toLowerCase(Locale.ROOT);
    var id =
        db.insert(
            "INSERT INTO users(id,username,email,email_encrypted,email_lookup,password_hash) VALUES (?,?,?,?,?,?) RETURNING id",
            userId,
            username.toLowerCase(Locale.ROOT),
            userId + "@private.invalid",
            crypto.encrypt(normalized),
            crypto.lookup(normalized),
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
        "SELECT id,password_hash FROM users WHERE email_lookup=? AND status='ACTIVE'", crypto.lookup(email.strip().toLowerCase(Locale.ROOT)));
  }

  public Map<String, Object> profile(UUID user) {
    return db.one(
        "SELECT u.id,u.username,p.display_name,p.bio,c.id AS channel_id FROM users u JOIN"
            + " user_profiles p ON p.user_id=u.id LEFT JOIN channels c ON c.owner_id=u.id WHERE"
            + " u.id=?",
        user);
  }
}
