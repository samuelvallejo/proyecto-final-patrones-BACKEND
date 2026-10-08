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
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain chain(
      HttpSecurity http, AuthService auth, @Value("${app.origins}") String origins)
      throws Exception {
    CorsConfiguration cors = new CorsConfiguration();
    cors.setAllowedOrigins(Arrays.asList(origins.split(",")));
    cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
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
                        "/api/auth/login",
                        "/api/config",
                        "/api/categories",
                        "/api/explore",
                        "/api/streams/*",
                        "/api/streams/*/collaboration",
                        "/api/streams/*/messages",
                        "/api/clips/public",
                        "/api/media/*")
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
                String bearer = req.getHeader("Authorization");
                if (bearer != null && bearer.startsWith("Bearer ")) {
                  var id = auth.resolve(bearer.substring(7));
                  if (id != null)
                    SecurityContextHolder.getContext()
                        .setAuthentication(
                            new UsernamePasswordAuthenticationToken(id, null, List.of()));
                }
                chain.doFilter(req, res);
              }
            },
            UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
