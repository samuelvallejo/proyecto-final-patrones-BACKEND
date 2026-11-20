package com.streamguard;

import static org.junit.jupiter.api.Assertions.*;

import com.streamguard.core.Db;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;

/** Takedown and AI rules through real HTTP, sessions, the admin proxy and PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ContentModerationIntegrationTest {
  @Autowired TestRestTemplate http;
  @Autowired Db db;

  record User(String token, String id, String username) {}

  private ResponseEntity<Map> call(HttpMethod method, String path, Object body, String token) {
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) h.set("Cookie", token);
    return http.exchange("/api" + path, method, new HttpEntity<>(body, h), Map.class);
  }

  private ResponseEntity<List> list(String path, String token) {
    HttpHeaders h = new HttpHeaders();
    h.set("Cookie", token);
    return http.exchange("/api" + path, HttpMethod.GET, new HttpEntity<>(h), List.class);
  }

  private User user() {
    String n = "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    Map challenge = call(HttpMethod.POST, "/auth/challenge", Map.of(), null).getBody();
    var body =
        SealedCredentials.seal(
            challenge.get("token").toString(),
            challenge.get("publicKey").toString(),
            Map.of(
                "username", n,
                "email", n + "@gmail.com",
                "password", "TestPassword123!",
                "aiConsent", true));
    var res = call(HttpMethod.POST, "/auth/register", body, null);
    assertEquals(200, res.getStatusCode().value());
    String cookie = res.getHeaders().getFirst("Set-Cookie").split(";", 2)[0];
    Map me = call(HttpMethod.GET, "/users/me", null, cookie).getBody();
    return new User(cookie, me.get("id").toString(), n);
  }

  private User admin() {
    User u = user();
    db.exec(
        "INSERT INTO user_roles(user_id,role_id) SELECT ?::uuid,id FROM roles WHERE name='ADMIN'",
        u.id());
    return u;
  }

  private String liveStream(User owner) {
    var channel =
        call(
            HttpMethod.POST,
            "/channels",
            Map.of("name", "Canal " + owner.username(), "slug", owner.username(), "description", ""),
            owner.token());
    assertEquals(200, channel.getStatusCode().value());
    var stream =
        call(HttpMethod.POST, "/streams", Map.of("title", "Directo", "description", ""), owner.token());
    assertEquals(200, stream.getStatusCode().value());
    return stream.getBody().get("id").toString();
  }

  @Test
  void onlyAdministratorsReachContentModeration() {
    User viewer = user();
    assertEquals(403, call(HttpMethod.GET, "/admin/content", null, viewer.token()).getStatusCode().value());
    assertEquals(403, call(HttpMethod.GET, "/admin/ai", null, viewer.token()).getStatusCode().value());
    assertEquals(401, call(HttpMethod.GET, "/admin/content", null, null).getStatusCode().value());
  }

  @Test
  void takedownIsLogicalReversibleAndAudited() {
    User admin = admin(), owner = user(), viewer = user();
    String stream = liveStream(owner);

    assertEquals(
        200,
        call(HttpMethod.POST, "/admin/content/STREAM/" + stream + "/takedown",
                Map.of("reason", "Contenido prohibido"), admin.token())
            .getStatusCode().value());
    var row = db.one("SELECT status,removed_at,removed_reason FROM streams WHERE id=?::uuid", stream);
    assertEquals("ENDED", row.get("status"));
    assertNotNull(row.get("removed_at"));
    assertEquals("Contenido prohibido", row.get("removed_reason"));
    assertEquals(404, call(HttpMethod.GET, "/streams/" + stream, null, viewer.token()).getStatusCode().value());
    assertEquals(200, call(HttpMethod.GET, "/streams/" + stream, null, owner.token()).getStatusCode().value());
    assertEquals(
        1,
        db.count(
            "SELECT count(*) FROM audit_logs WHERE entity_id=?::uuid AND action='ADMIN_TAKEDOWN'"
                + " AND actor_id=?::uuid AND details->>'label' LIKE '%Contenido prohibido%'",
            stream, admin.id()));
    assertEquals(
        409,
        call(HttpMethod.POST, "/admin/content/STREAM/" + stream + "/takedown",
                Map.of("reason", "otra vez"), admin.token())
            .getStatusCode().value());
    var removed = list("/admin/content?type=STREAM&state=REMOVED", admin.token());
    assertTrue(removed.getBody().stream().anyMatch(item -> stream.equals(((Map<?, ?>) item).get("id"))));

    // The takedown shows up in the existing panel's action log too.
    var actions = list("/admin/actions", admin.token());
    assertTrue(actions.getBody().stream().anyMatch(item -> "ADMIN_TAKEDOWN".equals(((Map<?, ?>) item).get("action"))));

    assertEquals(
        200,
        call(HttpMethod.POST, "/admin/content/STREAM/" + stream + "/restore",
                Map.of("reason", "Apelación aceptada"), admin.token())
            .getStatusCode().value());
    assertNull(db.one("SELECT removed_at FROM streams WHERE id=?::uuid", stream).get("removed_at"));
    assertEquals(200, call(HttpMethod.GET, "/streams/" + stream, null, viewer.token()).getStatusCode().value());
    assertEquals(
        409,
        call(HttpMethod.POST, "/admin/content/STREAM/" + stream + "/restore",
                Map.of("reason", "otra vez"), admin.token())
            .getStatusCode().value());
  }

  @Test
  void aiRulesAreSavedAndReportedByTheGatewayProxy() {
    User admin = admin();
    Map before = call(HttpMethod.GET, "/admin/ai", null, admin.token()).getBody();
    try {
      Map after =
          call(HttpMethod.PUT, "/admin/ai", Map.of("enabled", false, "hourlyLimit", 25), admin.token()).getBody();
      assertEquals(false, after.get("enabled"));
      assertEquals(25, after.get("hourlyLimit"));
      assertEquals(false, ((Map<?, ?>) after.get("gateway")).get("enabled"));
      assertEquals(
          400,
          call(HttpMethod.PUT, "/admin/ai", Map.of("enabled", true, "hourlyLimit", -1), admin.token())
              .getStatusCode().value());
      assertEquals(
          1,
          db.count("SELECT count(*) FROM audit_logs WHERE actor_id=?::uuid AND action='ADMIN_AI_RULES'", admin.id()));
    } finally {
      call(HttpMethod.PUT, "/admin/ai",
          Map.of("enabled", before.get("enabled"), "hourlyLimit", before.get("hourlyLimit")), admin.token());
    }
  }
}
