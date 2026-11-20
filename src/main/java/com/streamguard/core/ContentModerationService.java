package com.streamguard.core;

import com.streamguard.ai.AiService;
import com.streamguard.i18n.Messages;
import java.util.*;

/**
 * Real subject behind the admin proxy: plain queries and rules, with no role check and no audit
 * code. Removals are logical, so a stream or clip keeps its data and can be restored.
 */
public class ContentModerationService implements ContentModeration {
  static final Set<String> STATES = Set.of("ACTIVE", "REMOVED", "ALL");

  private final Db db;
  private final PlatformService platform;
  private final PlatformSettings settings;
  private final AiService ai;

  public ContentModerationService(
      Db db, PlatformService platform, PlatformSettings settings, AiService ai) {
    this.db = db;
    this.platform = platform;
    this.settings = settings;
    this.ai = ai;
  }

  @Override
  public List<Map<String, Object>> content(UUID actor, String type, String state, String query) {
    String table = table(type);
    if (!STATES.contains(state)) throw new ApiError(400, Messages.text("adminInvalidContent"));
    String like = "%" + (query == null ? "" : query.strip()) + "%";
    String sql =
        table.equals("streams")
            ? "SELECT s.id,'STREAM' AS type,s.title,s.status,s.started_at AS created_at,s.removed_at,"
                + "s.removed_reason,c.name AS channel_name,u.username,NULL AS asset_id FROM streams s"
                + " JOIN channels c ON c.id=s.channel_id JOIN users u ON u.id=c.owner_id WHERE"
                + " (?='ALL' OR (?='REMOVED')=(s.removed_at IS NOT NULL)) AND s.title ILIKE ? ORDER"
                + " BY s.started_at DESC LIMIT 100"
            : "SELECT cl.id,'CLIP' AS type,cl.title,cl.status,cl.created_at,cl.removed_at,"
                + "cl.removed_reason,c.name AS channel_name,u.username,cl.asset_id FROM clips cl JOIN"
                + " streams s ON s.id=cl.stream_id JOIN channels c ON c.id=s.channel_id JOIN users u"
                + " ON u.id=c.owner_id WHERE (?='ALL' OR (?='REMOVED')=(cl.removed_at IS NOT NULL))"
                + " AND cl.title ILIKE ? ORDER BY cl.created_at DESC LIMIT 100";
    return db.list(sql, state, state, like);
  }

  @Override
  public void takeDown(UUID actor, String type, UUID id, String reason) {
    String table = table(type);
    int changed =
        db.exec(
            "UPDATE " + table + " SET removed_at=now(),removed_by=?,removed_reason=? WHERE id=? AND"
                + " removed_at IS NULL",
            actor,
            reason,
            id);
    if (changed == 0) missingOr(table, id, "adminAlreadyRemoved");
    if (table.equals("streams")) platform.finish(id);
  }

  @Override
  public void restore(UUID actor, String type, UUID id, String reason) {
    String table = table(type);
    int changed =
        db.exec(
            "UPDATE " + table + " SET removed_at=NULL,removed_by=NULL,removed_reason=NULL WHERE"
                + " id=? AND removed_at IS NOT NULL",
            id);
    if (changed == 0) missingOr(table, id, "adminNotRemoved");
  }

  @Override
  public Map<String, Object> ai(UUID actor) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("provider", ai.provider());
    out.put("model", ai.model());
    out.put("enabled", settings.enabled());
    out.put("hourlyLimit", settings.hourlyLimit());
    out.put("gateway", ai.gatewayStats());
    return out;
  }

  @Override
  public void setAiRules(UUID actor, boolean enabled, int hourlyLimit) {
    if (hourlyLimit < 0 || hourlyLimit > 100000)
      throw new ApiError(400, Messages.text("adminInvalidAiRules"));
    settings.set(
        Map.of("ai_enabled", String.valueOf(enabled), "ai_hourly_limit", String.valueOf(hourlyLimit)),
        actor);
  }

  /** Only two tables can be taken down; the name never comes from the request directly. */
  private static String table(String type) {
    return switch (type == null ? "" : type) {
      case "STREAM" -> "streams";
      case "CLIP" -> "clips";
      default -> throw new ApiError(400, Messages.text("adminInvalidContent"));
    };
  }

  private void missingOr(String table, UUID id, String conflictKey) {
    if (db.count("SELECT count(*) FROM " + table + " WHERE id=?", id) == 0)
      throw new ApiError(404, Messages.text("dbOneText01"));
    throw new ApiError(409, Messages.text(conflictKey));
  }
}
