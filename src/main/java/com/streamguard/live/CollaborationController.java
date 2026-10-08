package com.streamguard.live;

import com.streamguard.auth.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CollaborationController {
  private final CollaborationService collaborations;

  public CollaborationController(CollaborationService collaborations) {
    this.collaborations = collaborations;
  }

  public record Invitation(@NotBlank @Size(max = 80) String code) {}

  @PostMapping("/streams/{stream}/collaborations")
  public Map<String, Object> create(@PathVariable UUID stream) {
    return collaborations.create(stream, AuthService.current());
  }

  @GetMapping("/streams/{stream}/collaboration")
  public Map<String, Object> forStream(@PathVariable UUID stream) {
    return collaborations.forStream(stream);
  }

  @PostMapping("/collaborations/join")
  public Map<String, Object> join(@Valid @RequestBody Invitation invitation) {
    return collaborations.join(invitation.code(), AuthService.current());
  }

  @PostMapping("/collaborations/{room}/leave")
  public Map<String, Object> leave(@PathVariable UUID room) {
    return collaborations.leave(room, AuthService.current());
  }
}
