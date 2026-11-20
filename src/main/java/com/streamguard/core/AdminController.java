package com.streamguard.core;

import com.streamguard.auth.RoleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** The administration panel. Every endpoint checks the ADMIN role on the server. */
@RestController
@Validated
@RequestMapping("/api/admin")
public class AdminController {
  private final RoleService roles;
  private final AdminService admin;

  public AdminController(RoleService roles, AdminService admin) {
    this.roles = roles;
    this.admin = admin;
  }

  public record RoleChange(boolean admin) {}

  public record StatusChange(@NotNull @Pattern(regexp = "ACTIVE|SUSPENDED") String status) {}

  public record CategoryName(@NotBlank @Size(max = 64) String name) {}

  public record Platform(boolean maintenance, boolean registrationOpen) {}

  public record Announcement(
      @NotBlank @Size(max = 280) String message,
      @NotNull @Pattern(regexp = "INFO|IMPORTANT") String level,
      boolean sendNotification) {}

  @GetMapping("/overview")
  public Map<String, Object> overview() {
    roles.requireAdmin();
    return admin.overview();
  }

  @GetMapping("/users")
  public List<Map<String, Object>> users(
      @RequestParam(defaultValue = "") @Size(max = 64) String search) {
    roles.requireAdmin();
    return admin.users(search.strip());
  }

  @PutMapping("/users/{user}/role")
  public Map<String, Object> role(@PathVariable UUID user, @RequestBody RoleChange change) {
    admin.setAdmin(roles.requireAdmin(), user, change.admin());
    return Map.of("ok", true);
  }

  @PutMapping("/users/{user}/status")
  public Map<String, Object> status(
      @PathVariable UUID user, @Valid @RequestBody StatusChange change) {
    admin.setStatus(roles.requireAdmin(), user, change.status());
    return Map.of("ok", true);
  }

  @GetMapping("/streams")
  public List<Map<String, Object>> streams() {
    roles.requireAdmin();
    return admin.liveStreams();
  }

  @PostMapping("/streams/{stream}/end")
  public Map<String, Object> end(@PathVariable UUID stream) {
    admin.endStream(roles.requireAdmin(), stream);
    return Map.of("ok", true);
  }

  @PostMapping("/categories")
  public Map<String, Object> createCategory(@Valid @RequestBody CategoryName body) {
    return admin.createCategory(roles.requireAdmin(), body.name());
  }

  @PutMapping("/categories/{category}")
  public Map<String, Object> renameCategory(
      @PathVariable UUID category, @Valid @RequestBody CategoryName body) {
    admin.renameCategory(roles.requireAdmin(), category, body.name());
    return Map.of("ok", true);
  }

  @PutMapping("/platform")
  public Map<String, Object> platform(@RequestBody Platform body) {
    return admin.setPlatform(roles.requireAdmin(), body.maintenance(), body.registrationOpen());
  }

  @PostMapping("/announcement")
  public Map<String, Object> announce(@Valid @RequestBody Announcement body) {
    return admin.announce(roles.requireAdmin(), body.message(), body.level(), body.sendNotification());
  }

  @DeleteMapping("/announcement")
  public Map<String, Object> clearAnnouncement() {
    admin.clearAnnouncement(roles.requireAdmin());
    return Map.of("ok", true);
  }

  @GetMapping("/actions")
  public List<Map<String, Object>> actions() {
    roles.requireAdmin();
    return admin.actions();
  }
}
