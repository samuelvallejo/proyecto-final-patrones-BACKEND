package com.streamguard.auth;

import com.streamguard.core.ApiError;
import com.streamguard.core.Db;
import com.streamguard.i18n.Messages;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The two account profiles: every account is a regular user, and an administrator also has the
 * ADMIN role. The first administrators come from the ADMIN_USERNAMES setting on the server (so
 * nobody can make themselves an administrator from the browser); after that, administrators can
 * promote other accounts from the administration panel.
 */
@Service
public class RoleService {
  public static final String ADMIN = "ADMIN";
  public static final String USER = "USER";

  private final Db db;
  private final Set<String> configured;

  public RoleService(Db db, @Value("${app.admin-usernames:}") String usernames) {
    this.db = db;
    this.configured = parse(usernames);
  }

  static Set<String> parse(String usernames) {
    return Arrays.stream(usernames == null ? new String[0] : usernames.split("[,;\\s]+"))
        .map(name -> name.strip().toLowerCase(Locale.ROOT))
        .filter(name -> !name.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
  }

  /** True when the server setting names this account; such administrators cannot be demoted. */
  public boolean isConfigured(String username) {
    return username != null && configured.contains(username.toLowerCase(Locale.ROOT));
  }

  public boolean isAdmin(UUID user) {
    if (db.count(
            "SELECT count(*) FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=?"
                + " AND r.name='ADMIN'",
            user)
        > 0) return true;
    if (configured.isEmpty()) return false;
    var row = db.optional("SELECT username FROM users WHERE id=? AND status='ACTIVE'", user);
    if (row.isEmpty() || !isConfigured(row.get().get("username").toString())) return false;
    grant(user);
    return true;
  }

  public String roleOf(UUID user) {
    return isAdmin(user) ? ADMIN : USER;
  }

  public void grant(UUID user) {
    db.exec(
        "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name='ADMIN' ON"
            + " CONFLICT DO NOTHING",
        user);
  }

  public void revoke(UUID user) {
    db.exec(
        "DELETE FROM user_roles WHERE user_id=? AND role_id=(SELECT id FROM roles WHERE"
            + " name='ADMIN')",
        user);
  }

  /** The current user, when that user is an administrator; otherwise 403. */
  public UUID requireAdmin() {
    UUID user = AuthService.current();
    if (!isAdmin(user)) throw new ApiError(403, Messages.text("adminOnly"));
    return user;
  }
}
