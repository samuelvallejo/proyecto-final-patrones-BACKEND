package com.streamguard.core;

import com.streamguard.ai.AiService;
import com.streamguard.auth.RoleService;
import com.streamguard.i18n.Messages;
import com.streamguard.live.LiveHub;
import java.lang.management.ManagementFactory;
import java.text.Normalizer;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What only administrators can see or change: platform figures, technical status, accounts,
 * every live broadcast and the category catalogue. Each change is written to audit_logs.
 */
@Service
public class AdminService {
  private final Db db;
  private final RoleService roles;
  private final AiService ai;
  private final PlatformService platform;
  private final LiveHub hub;
  private final ObjectProvider<Flyway> flyway;
  private final PlatformSettings settings;

  public AdminService(
      Db db,
      RoleService roles,
      AiService ai,
      PlatformService platform,
      LiveHub hub,
      ObjectProvider<Flyway> flyway,
      PlatformSettings settings) {
    this.settings = settings;
    this.db = db;
    this.roles = roles;
    this.ai = ai;
    this.platform = platform;
    this.hub = hub;
    this.flyway = flyway;
  }

  public Map<String, Object> overview() {
    Map<String, Object> totals =
        db.one(
            "SELECT (SELECT count(*) FROM users WHERE status<>'DELETED') AS users,(SELECT count(*)"
                + " FROM users WHERE status='SUSPENDED') AS suspended,(SELECT count(DISTINCT"
                + " ur.user_id) FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE"
                + " r.name='ADMIN') AS admins,(SELECT count(*) FROM channels) AS channels,(SELECT"
                + " count(*) FROM streams WHERE status='LIVE') AS live,(SELECT count(*) FROM"
                + " streams) AS streams,(SELECT count(*) FROM chat_messages) AS messages,(SELECT"
                + " count(*) FROM moderation_queue WHERE status='PENDING') AS pending_reviews,"
                + "(SELECT count(*) FROM clips) AS clips,(SELECT count(*) FROM scheduled_events"
                + " WHERE starts_at>now()) AS upcoming_events");
    return Map.of("totals", totals, "system", system(), "platform", settings.status());
  }

  private Map<String, Object> system() {
    Runtime runtime = Runtime.getRuntime();
    Map<String, Object> database =
        db.one(
            "SELECT current_setting('server_version') AS version,"
                + " pg_database_size(current_database())/1048576 AS size_mb");
    String schema = "";
    try {
      Flyway migrations = flyway.getIfAvailable();
      var current = migrations == null ? null : migrations.info().current();
      if (current != null && current.getVersion() != null) schema = current.getVersion().toString();
    } catch (RuntimeException unavailable) {
      // The figure is informative only; the panel still loads without it.
    }
    Map<String, Object> system = new LinkedHashMap<>();
    system.put("aiProvider", ai.provider());
    system.put("aiModel", ai.model());
    system.put("aiConfigured", ai.configured());
    system.put("geminiConfigured", ai.provider().equals("GEMINI") && ai.configured());
    system.put("javaVersion", Runtime.version().toString());
    system.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
    system.put("memoryUsedMb", (runtime.totalMemory() - runtime.freeMemory()) >> 20);
    system.put("memoryMaxMb", runtime.maxMemory() >> 20);
    system.put("processors", runtime.availableProcessors());
    system.put("databaseVersion", database.get("version"));
    system.put("databaseSizeMb", database.get("size_mb"));
    system.put("schemaVersion", schema);
    return system;
  }

  public List<Map<String, Object>> users(String search) {
    String like = "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    var rows =
        db.list(
            "SELECT u.id,u.username,p.display_name,u.status,u.created_at,c.name AS channel_name,"
                + " EXISTS(SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE"
                + " ur.user_id=u.id AND r.name='ADMIN') AS admin, EXISTS(SELECT 1 FROM streams s"
                + " WHERE s.channel_id=c.id AND s.status='LIVE') AS live FROM users u JOIN"
                + " user_profiles p ON p.user_id=u.id LEFT JOIN channels c ON c.owner_id=u.id WHERE"
                + " u.status<>'DELETED' AND (u.username ILIKE ? OR p.display_name ILIKE ?) ORDER BY"
                + " u.created_at DESC LIMIT 100",
            like,
            like);
    for (var row : rows) row.put("protected", roles.isConfigured(row.get("username").toString()));
    return rows;
  }

  @Transactional
  public void setAdmin(UUID admin, UUID target, boolean makeAdmin) {
    var user = account(target);
    String username = user.get("username").toString();
    if (makeAdmin) {
      if (!"ACTIVE".equals(user.get("status"))) throw new ApiError(400, Messages.text("adminPromoteSuspended"));
      roles.grant(target);
    } else {
      if (target.equals(admin)) throw new ApiError(400, Messages.text("adminSelfDemote"));
      if (roles.isConfigured(username)) throw new ApiError(400, Messages.text("adminConfigured"));
      roles.revoke(target);
    }
    audit(admin, "USER", target, makeAdmin ? "ADMIN_GRANT" : "ADMIN_REVOKE", "@" + username);
  }

  @Transactional
  public void setStatus(UUID admin, UUID target, String status) {
    var user = account(target);
    if (target.equals(admin)) throw new ApiError(400, Messages.text("adminSelfSuspend"));
    if ("SUSPENDED".equals(status) && roles.isAdmin(target))
      throw new ApiError(400, Messages.text("adminSuspendAdmin"));
    db.exec("UPDATE users SET status=? WHERE id=?", status, target);
    if ("SUSPENDED".equals(status)) {
      db.exec("DELETE FROM auth_sessions WHERE user_id=?", target);
      for (var stream :
          db.list(
              "SELECT s.id FROM streams s JOIN channels c ON c.id=s.channel_id WHERE c.owner_id=?"
                  + " AND s.status='LIVE'",
              target)) platform.finish(Db.id(stream.get("id")));
    }
    audit(
        admin,
        "USER",
        target,
        "SUSPENDED".equals(status) ? "ADMIN_SUSPEND" : "ADMIN_REACTIVATE",
        "@" + user.get("username"));
  }

  public List<Map<String, Object>> liveStreams() {
    var rows =
        db.list(
            "SELECT s.id,s.title,s.started_at,c.name AS channel_name,u.username,cat.name AS"
                + " category FROM streams s JOIN channels c ON c.id=s.channel_id JOIN users u ON"
                + " u.id=c.owner_id LEFT JOIN categories cat ON cat.id=s.category_id WHERE"
                + " s.status='LIVE' ORDER BY s.started_at DESC LIMIT 100");
    for (var row : rows) row.put("viewers", hub.viewers(Db.id(row.get("id"))));
    return rows;
  }

  @Transactional
  public void endStream(UUID admin, UUID stream) {
    var row =
        db.optional("SELECT title FROM streams WHERE id=? AND status='LIVE'", stream)
            .orElseThrow(() -> new ApiError(404, Messages.text("adminStreamNotLive")));
    platform.finish(stream);
    audit(admin, "STREAM", stream, "ADMIN_END_STREAM", row.get("title").toString());
  }

  @Transactional
  public Map<String, Object> createCategory(UUID admin, String name) {
    String clean = name.strip(), slug = slug(clean);
    if (slug.isEmpty()) throw new ApiError(400, Messages.text("adminCategoryInvalid"));
    try {
      UUID id = db.insert("INSERT INTO categories(name,slug) VALUES (?,?) RETURNING id", clean, slug);
      audit(admin, "CATEGORY", id, "ADMIN_CATEGORY_CREATE", clean);
      return db.one("SELECT * FROM categories WHERE id=?", id);
    } catch (DuplicateKeyException exists) {
      throw new ApiError(409, Messages.text("adminCategoryExists"));
    }
  }

  @Transactional
  public void renameCategory(UUID admin, UUID category, String name) {
    String clean = name.strip();
    try {
      if (db.exec("UPDATE categories SET name=? WHERE id=?", clean, category) == 0)
        throw new ApiError(404, Messages.text("eventInvalidCategory"));
    } catch (DuplicateKeyException exists) {
      throw new ApiError(409, Messages.text("adminCategoryExists"));
    }
    audit(admin, "CATEGORY", category, "ADMIN_CATEGORY_RENAME", clean);
  }

  /** Publishes the global announcement: a banner for everyone and, optionally, a notification per active account. */
  @Transactional
  public Map<String, Object> announce(UUID admin, String message, String level, boolean notify) {
    String clean = message.strip();
    Map<String, String> changes = new HashMap<>();
    changes.put("announcement_id", UUID.randomUUID().toString());
    changes.put("announcement_message", clean);
    changes.put("announcement_level", level);
    changes.put("announcement_at", java.time.Instant.now().toString());
    settings.set(changes, admin);
    int notified =
        notify
            ? db.exec(
                "INSERT INTO notifications(user_id,type,title,body) SELECT id,'ANNOUNCEMENT',?,? FROM"
                    + " users WHERE status='ACTIVE'",
                Messages.text("announcementTitle"),
                clean)
            : 0;
    audit(admin, "PLATFORM", null, "ADMIN_ANNOUNCE", clean);
    return Map.of("notified", notified, "platform", settings.status());
  }

  @Transactional
  public void clearAnnouncement(UUID admin) {
    var current = settings.announcement();
    if (current == null) return;
    Map<String, String> changes = new HashMap<>();
    for (String name : List.of("announcement_id", "announcement_message", "announcement_level", "announcement_at"))
      changes.put(name, null);
    settings.set(changes, admin);
    audit(admin, "PLATFORM", null, "ADMIN_ANNOUNCE_REMOVE", current.get("message").toString());
  }

  public List<Map<String, Object>> actions() {
    return db.list(
        "SELECT a.action,a.details->>'label' AS label,a.created_at,u.username AS admin FROM"
            + " audit_logs a LEFT JOIN users u ON u.id=a.actor_id WHERE a.action LIKE 'ADMIN\\_%'"
            + " ORDER BY a.created_at DESC LIMIT 30");
  }

  private Map<String, Object> account(UUID user) {
    return db.optional("SELECT username,status FROM users WHERE id=? AND status<>'DELETED'", user)
        .orElseThrow(() -> new ApiError(404, Messages.text("adminUserMissing")));
  }

  private void audit(UUID admin, String type, UUID entity, String action, String label) {
    db.exec(
        "INSERT INTO audit_logs(actor_id,entity_type,entity_id,action,details) VALUES"
            + " (?,?,?,?,jsonb_build_object('label',?::text))",
        admin,
        type,
        entity,
        action,
        label.length() > 160 ? label.substring(0, 160) : label);
  }

  static String slug(String name) {
    String plain =
        Normalizer.normalize(name, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
    return plain.length() > 64 ? plain.substring(0, 64).replaceAll("-+$", "") : plain;
  }
}
