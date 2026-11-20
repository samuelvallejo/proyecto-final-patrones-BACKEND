package com.streamguard.core;

import com.streamguard.ai.AiService;
import com.streamguard.auth.AuthService;
import com.streamguard.auth.RoleService;
import com.streamguard.patterns.PatternAssembly;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * Takedown of streams and clips, and the AI gateway rules. It only translates HTTP: the role check,
 * the transaction and the audit record come from the admin proxy built in {@link PatternAssembly}.
 */
@RestController
@RequestMapping("/api/admin")
public class ContentModerationController {
  private final ContentModeration moderation;

  public ContentModerationController(
      Db db,
      RoleService roles,
      PlatformService platform,
      PlatformSettings settings,
      AiService ai,
      PlatformTransactionManager transactions) {
    this.moderation =
        PatternAssembly.contentModeration(
            new ContentModerationService(db, platform, settings, ai),
            roles::isAdmin,
            db,
            work -> new TransactionTemplate(transactions).execute(status -> work.get()));
  }

  public record Reason(@NotBlank @Size(max = 500) String reason) {}

  public record AiRules(boolean enabled, @Min(0) @Max(100000) int hourlyLimit) {}

  @GetMapping("/content")
  public List<Map<String, Object>> content(
      @RequestParam(defaultValue = "CLIP") @Pattern(regexp = "STREAM|CLIP") String type,
      @RequestParam(defaultValue = "ALL") @Pattern(regexp = "ACTIVE|REMOVED|ALL") String state,
      @RequestParam(defaultValue = "") @Size(max = 100) String q) {
    return moderation.content(AuthService.current(), type, state, q);
  }

  @PostMapping("/content/{type}/{id}/takedown")
  public Map<String, Object> takedown(
      @PathVariable @Pattern(regexp = "STREAM|CLIP") String type,
      @PathVariable UUID id,
      @Valid @RequestBody Reason body) {
    moderation.takeDown(AuthService.current(), type, id, body.reason().strip());
    return Map.of("ok", true);
  }

  @PostMapping("/content/{type}/{id}/restore")
  public Map<String, Object> restore(
      @PathVariable @Pattern(regexp = "STREAM|CLIP") String type,
      @PathVariable UUID id,
      @Valid @RequestBody Reason body) {
    moderation.restore(AuthService.current(), type, id, body.reason().strip());
    return Map.of("ok", true);
  }

  @GetMapping("/ai")
  public Map<String, Object> ai() {
    return moderation.ai(AuthService.current());
  }

  @PutMapping("/ai")
  public Map<String, Object> aiRules(@Valid @RequestBody AiRules body) {
    moderation.setAiRules(AuthService.current(), body.enabled(), body.hourlyLimit());
    return moderation.ai(AuthService.current());
  }
}
