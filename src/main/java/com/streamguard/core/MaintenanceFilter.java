package com.streamguard.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamguard.auth.RoleService;
import com.streamguard.i18n.Messages;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * During maintenance only administrators reach the API. It runs after Spring Security, so the
 * user is already known; anonymous calls were already answered with 401 by then.
 */
@Component
public class MaintenanceFilter extends OncePerRequestFilter {
  /** Still open during maintenance: waking the server, reading the status and logging in or out. */
  private static final Set<String> OPEN =
      Set.of(
          "/api/ping",
          "/api/platform/status",
          "/api/auth/challenge",
          "/api/auth/login",
          "/api/auth/logout",
          "/api/auth/register");

  private final PlatformSettings settings;
  private final RoleService roles;
  private final ObjectMapper json = new ObjectMapper();

  public MaintenanceFilter(PlatformSettings settings, RoleService roles) {
    this.settings = settings;
    this.roles = roles;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !path.startsWith("/api/") || OPEN.contains(path) || "OPTIONS".equals(request.getMethod());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (settings.maintenance()) {
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication != null
          && authentication.getPrincipal() instanceof UUID user
          && !roles.isAdmin(user)) {
        response.setStatus(503);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response
            .getWriter()
            .write(json.writeValueAsString(Map.of("error", Messages.text("maintenanceActive"), "code", "MAINTENANCE")));
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
