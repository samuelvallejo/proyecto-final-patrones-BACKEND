package com.streamguard.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Platform-wide switches: maintenance mode, open registration and the global announcement.
 * Read on every request during maintenance, so the values are cached for a few seconds and
 * refreshed immediately when an administrator changes them.
 */
@Service
public class PlatformSettings implements com.streamguard.patterns.AiGatewayProxy.AccessRules {
  private static final long CACHE_MILLIS = 15000;
  private final Db db;
  private volatile Map<String, String> cache;
  private volatile long loadedAt;

  public PlatformSettings(Db db) {
    this.db = db;
  }

  private Map<String, String> values() {
    long now = System.currentTimeMillis();
    Map<String, String> current = cache;
    if (current == null || now - loadedAt > CACHE_MILLIS) {
      Map<String, String> fresh = new HashMap<>();
      for (var row : db.list("SELECT name,value FROM platform_settings"))
        fresh.put(row.get("name").toString(), row.get("value").toString());
      current = Map.copyOf(fresh);
      cache = current;
      loadedAt = now;
    }
    return current;
  }

  public boolean maintenance() {
    return "true".equals(values().get("maintenance"));
  }

  public boolean registrationOpen() {
    return !"false".equals(values().get("registration_open"));
  }

  /** Rule read by the AI gateway proxy: whether any request may reach the AI provider. */
  @Override
  public boolean enabled() {
    return !"false".equals(values().get("ai_enabled"));
  }

  /** Rule read by the AI gateway proxy: provider requests allowed per hour. */
  @Override
  public int hourlyLimit() {
    try {
      return Math.max(0, Integer.parseInt(values().getOrDefault("ai_hourly_limit", "600")));
    } catch (NumberFormatException e) {
      return 600;
    }
  }

  /** The active announcement, or null when there is none. */
  public Map<String, Object> announcement() {
    var values = values();
    String message = values.get("announcement_message");
    if (message == null || message.isBlank()) return null;
    Map<String, Object> announcement = new LinkedHashMap<>();
    announcement.put("id", values.getOrDefault("announcement_id", ""));
    announcement.put("message", message);
    announcement.put("level", values.getOrDefault("announcement_level", "INFO"));
    announcement.put("createdAt", values.getOrDefault("announcement_at", ""));
    return announcement;
  }

  /** What every visitor may know, even before logging in. */
  public Map<String, Object> status() {
    Map<String, Object> status = new LinkedHashMap<>();
    status.put("maintenance", maintenance());
    status.put("registrationOpen", registrationOpen());
    status.put("announcement", announcement());
    return status;
  }

  /** Saves the given settings; a null value removes that setting. */
  public void set(Map<String, String> changes, UUID admin) {
    for (var change : changes.entrySet()) {
      if (change.getValue() == null) db.exec("DELETE FROM platform_settings WHERE name=?", change.getKey());
      else
        db.exec(
            "INSERT INTO platform_settings(name,value,updated_by) VALUES (?,?,?) ON CONFLICT (name)"
                + " DO UPDATE SET value=EXCLUDED.value,updated_by=EXCLUDED.updated_by,updated_at=now()",
            change.getKey(),
            change.getValue(),
            admin);
    }
    cache = null;
  }
}
