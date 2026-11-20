package com.streamguard.core;

import com.streamguard.patterns.AdminAuditProxy.Audited;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Content moderation for administrators: logical takedown of streams and clips, and the rules of
 * the AI gateway proxy. The first argument is always the acting user; the admin proxy checks the
 * role with it and records the methods marked {@link Audited}.
 */
public interface ContentModeration {
  List<Map<String, Object>> content(UUID actor, String type, String state, String query);

  @Audited(entity = "CONTENT", action = "TAKEDOWN")
  void takeDown(UUID actor, String type, UUID id, String reason);

  @Audited(entity = "CONTENT", action = "RESTORE")
  void restore(UUID actor, String type, UUID id, String reason);

  Map<String, Object> ai(UUID actor);

  @Audited(entity = "PLATFORM", action = "AI_RULES")
  void setAiRules(UUID actor, boolean enabled, int hourlyLimit);
}
