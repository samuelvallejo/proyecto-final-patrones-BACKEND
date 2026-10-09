package com.streamguard.auth;

import com.streamguard.i18n.Messages;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain chain(
      HttpSecurity http, AuthService auth, FieldCrypto crypto, @Value("${app.origins}") String origins)
      throws Exception {
    CorsConfiguration cors = new CorsConfiguration();
    cors.setAllowedOrigins(Arrays.asList(origins.split(",")));
    cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    cors.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", cors);
    return http.csrf(c -> c.disable())
        .cors(c -> c.configurationSource(source))
        .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            c ->
                c.requestMatchers(
                        "/actuator/health",
                        "/ws",
                        "/ws/media",
                        "/api/auth/register",
                        "/api/auth/login")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            c ->
                c.authenticationEntryPoint(
                    (req, res, e) -> {
                      res.setStatus(401);
                      res.setCharacterEncoding("UTF-8");
                      res.setContentType("application/json");
                      res.getWriter().write(Messages.text("securityConfigMessageText01"));
                    }))
        .addFilterBefore(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                  throws ServletException, IOException {
                res.setHeader("Cache-Control", "no-store");
                String origin = req.getHeader("Origin");
                if (!Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod()) && origin != null
                    && !Arrays.asList(origins.split(",")).contains(origin)) {
                  res.setStatus(403); res.setContentType("application/json");
                  res.getWriter().write("{\"error\":\"" + Messages.text("operationFailed") + "\"}"); return;
                }
                String token = SessionCookie.token(req);
                if (token != null) {
                  UUID id=null;
                  try {id=auth.resolve(crypto.decrypt(token));} catch (RuntimeException invalid) { /* Invalid encrypted cookies are anonymous. */ }
                  if (id != null)
                    SecurityContextHolder.getContext()
                        .setAuthentication(
                            new UsernamePasswordAuthenticationToken(id, null, List.of()));
                }
                chain.doFilter(req, res);
              }
            },
            CorsFilter.class)
        .build();
  }
}
