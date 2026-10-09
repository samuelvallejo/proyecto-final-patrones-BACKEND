package com.streamguard.auth;

import com.streamguard.core.Db;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** All the SQL about login sessions. Tokens are never stored, only their hash. */
@Repository
public class SessionRepository {
  private final Db db;

  public SessionRepository(Db db) {
    this.db = db;
  }

  /** Save a session that expires in two hours (the same time as {@link TokenGenerator#LIFETIME}). */
  public void create(UUID user, String tokenHash) {
    db.exec(
        "INSERT INTO auth_sessions(user_id,token_hash,expires_at) VALUES (?,?,now()+interval '2"
            + " hours')",
        user,
        tokenHash);
  }

  /** The owner of a session that has not expired, if the owner is still active. */
  public Optional<UUID> findActiveUser(String tokenHash) {
    return db.optional(
            "SELECT s.user_id FROM auth_sessions s JOIN users u ON u.id=s.user_id WHERE"
                + " s.token_hash=? AND s.expires_at>now() AND u.status='ACTIVE'",
            tokenHash)
        .map(row -> Db.id(row.get("user_id")));
  }

  public void delete(String tokenHash) {
    db.exec("DELETE FROM auth_sessions WHERE token_hash=?", tokenHash);
  }
}
