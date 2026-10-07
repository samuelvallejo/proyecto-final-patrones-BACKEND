package com.streamguard;

import static org.junit.jupiter.api.Assertions.*;

import com.streamguard.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PlatformIntegrationTest {
  @Autowired TestRestTemplate http;
  @Autowired Db db;
  @Autowired MediaService media;

  @Value("${FFMPEG_BIN:ffmpeg}")
  String ffmpeg;

  @TempDir Path temp;

  record User(String token, String id, String username) {}

  private ResponseEntity<Map> call(HttpMethod method, String path, Object body, String token) {
    HttpHeaders h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) h.setBearerAuth(token);
    return http.exchange("/api" + path, method, new HttpEntity<>(body, h), Map.class);
  }

  private User user() {
    String n = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    var res =
        call(
            HttpMethod.POST,
            "/auth/register",
            Map.of(
                "username",
                n,
                "email",
                n + "@example.com",
                "password",
                "TestPassword123!",
                "aiConsent",
                true),
            null);
    assertEquals(200, res.getStatusCode().value());
    Map u = (Map) res.getBody().get("user");
    return new User(res.getBody().get("token").toString(), u.get("id").toString(), n);
  }

  private String channel(User u) {
    var r =
        call(
            HttpMethod.POST,
            "/channels",
            Map.of(
                "name",
                TestFixtures.text("platformIntegrationTestText01"),
                "slug",
                u.username(),
                "description",
                TestFixtures.text("platformIntegrationTestText02")),
            u.token());
    assertEquals(200, r.getStatusCode().value());
    return r.getBody().get("id").toString();
  }

  private String stream(User u) {
    var r =
        call(
            HttpMethod.POST,
            "/streams",
            Map.of("title", TestFixtures.text("platformIntegrationTestText03"), "description", ""),
            u.token());
    assertEquals(200, r.getStatusCode().value());
    return r.getBody().get("id").toString();
  }

  @Test
  void schemaHas65DomainTablesAndForeignKeys() {
    assertEquals(
        65,
        db.count(
            "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND"
                + " table_type='BASE TABLE' AND table_name<>'flyway_schema_history'"));
    assertTrue(
        db.count(
                "SELECT count(*) FROM information_schema.table_constraints WHERE"
                    + " constraint_schema='public' AND constraint_type='FOREIGN KEY'")
            > 85);
  }

  @Test
  void authenticationRequiresConsentAndRejectsWrongPassword() {
    assertEquals(
        400,
        call(
                HttpMethod.POST,
                "/auth/register",
                Map.of(
                    "username",
                    "invalid",
                    "email",
                    "invalid@example.com",
                    "password",
                    "TestPassword123!",
                    "aiConsent",
                    false),
                null)
            .getStatusCode()
            .value());
    User u = user();
    assertEquals(
        401,
        call(
                HttpMethod.POST,
                "/auth/login",
                Map.of("email", u.username() + "@example.com", "password", "wrong-password"),
                null)
            .getStatusCode()
            .value());
    assertEquals(200, call(HttpMethod.GET, "/auth/me", null, u.token()).getStatusCode().value());
    assertEquals(
        200, call(HttpMethod.POST, "/auth/logout", Map.of(), u.token()).getStatusCode().value());
    assertEquals(401, call(HttpMethod.GET, "/auth/me", null, u.token()).getStatusCode().value());
  }

  @Test
  void channelValidationReturnsSpanishWithoutExposingInternalFieldNames() {
    User owner = user();
    var response =
        call(
            HttpMethod.POST,
            "/channels",
            Map.of("name", "Validation sample", "slug", "Uppercase", "description", "Sample"),
            owner.token());
    assertEquals(400, response.getStatusCode().value());
    assertEquals(
        com.streamguard.i18n.Messages.text("fieldSlug")
            + ": "
            + com.streamguard.i18n.Messages.text("validationSlug"),
        response.getBody().get("error"));
    assertFalse(response.getBody().get("error").toString().contains("slug"));
    var blank =
        call(
            HttpMethod.POST,
            "/channels",
            Map.of("name", "", "slug", owner.username(), "description", "Sample"),
            owner.token());
    assertEquals(400, blank.getStatusCode().value());
    assertEquals(
        com.streamguard.i18n.Messages.text("fieldName")
            + ": "
            + com.streamguard.i18n.Messages.text("validationRequired"),
        blank.getBody().get("error"));
  }

  @Test
  void ownerRulesModerationReviewAndSanctionsWorkEndToEnd() {
    User owner = user(), viewer = user(), moderator = user(), stranger = user();
    String c = channel(owner), s = stream(owner);
    var policy =
        Map.ofEntries(
            Map.entry("level", "STRICT"),
            Map.entry("autoHide", true),
            Map.entry("autoMute", false),
            Map.entry("muteSeconds", 300),
            Map.entry("reviewThreshold", .4),
            Map.entry("blockThreshold", .7),
            Map.entry("blockedWords", List.of(TestFixtures.text("platformIntegrationTestText04"))),
            Map.entry("blockedTopics", List.of()),
            Map.entry("allowLinks", false),
            Map.entry("slowMode", 0),
            Map.entry("autoClips", true));
    assertEquals(
        403,
        call(HttpMethod.PUT, "/channels/" + c + "/policy", policy, stranger.token())
            .getStatusCode()
            .value());
    assertEquals(
        200,
        call(HttpMethod.PUT, "/channels/" + c + "/policy", policy, owner.token())
            .getStatusCode()
            .value());
    assertEquals(
        401,
        call(HttpMethod.POST, "/streams/" + s + "/messages", Map.of("content", "hola"), null)
            .getStatusCode()
            .value());
    var visible =
        call(
            HttpMethod.POST,
            "/streams/" + s + "/messages",
            Map.of("content", TestFixtures.text("platformIntegrationTestText05")),
            viewer.token());
    assertEquals("VISIBLE", visible.getBody().get("status"));
    var hidden =
        call(
            HttpMethod.POST,
            "/streams/" + s + "/messages",
            Map.of("content", TestFixtures.text("platformIntegrationTestText06")),
            stranger.token());
    assertEquals("HIDDEN", hidden.getBody().get("status"));
    var queue =
        db.one(
                "SELECT id FROM moderation_queue WHERE message_id=?",
                UUID.fromString(hidden.getBody().get("id").toString()))
            .get("id");
    assertEquals(
        403,
        call(
                HttpMethod.POST,
                "/moderation/" + queue + "/review",
                Map.of("approve", true),
                viewer.token())
            .getStatusCode()
            .value());
    assertEquals(
        200,
        call(
                HttpMethod.POST,
                "/channels/" + c + "/moderators",
                Map.of("username", moderator.username()),
                owner.token())
            .getStatusCode()
            .value());
    assertEquals(
        200,
        call(
                HttpMethod.POST,
                "/moderation/" + queue + "/review",
                Map.of("approve", true),
                moderator.token())
            .getStatusCode()
            .value());
    assertEquals(
        409,
        call(
                HttpMethod.POST,
                "/moderation/" + queue + "/review",
                Map.of("approve", false),
                owner.token())
            .getStatusCode()
            .value());
    assertEquals(
        "VISIBLE",
        db.one(
                "SELECT status FROM chat_messages WHERE id=?",
                UUID.fromString(hidden.getBody().get("id").toString()))
            .get("status"));
    assertEquals(
        200,
        call(
                HttpMethod.POST,
                "/channels/" + c + "/sanctions",
                Map.of(
                    "userId",
                    viewer.id(),
                    "type",
                    "MUTE",
                    "seconds",
                    300,
                    "reason",
                    TestFixtures.text("platformIntegrationTestText07")),
                moderator.token())
            .getStatusCode()
            .value());
    assertEquals(
        403,
        call(
                HttpMethod.POST,
                "/streams/" + s + "/messages",
                Map.of("content", TestFixtures.text("platformIntegrationTestText08")),
                viewer.token())
            .getStatusCode()
            .value());
    var sanction =
        db.one("SELECT id FROM user_sanctions WHERE user_id=?", UUID.fromString(viewer.id()))
            .get("id");
    assertEquals(
        200,
        call(HttpMethod.DELETE, "/sanctions/" + sanction, null, owner.token())
            .getStatusCode()
            .value());
    assertEquals(
        403,
        call(HttpMethod.POST, "/streams/" + s + "/end", Map.of(), viewer.token())
            .getStatusCode()
            .value());
    assertEquals(
        200,
        call(HttpMethod.POST, "/streams/" + s + "/end", Map.of(), owner.token())
            .getStatusCode()
            .value());
    assertEquals(
        409,
        call(
                HttpMethod.POST,
                "/streams/" + s + "/messages",
                Map.of("content", TestFixtures.text("platformIntegrationTestText09")),
                viewer.token())
            .getStatusCode()
            .value());
  }

  @Test
  void onlyOneLiveStreamAndPrivateClipsStayPrivate() throws Exception {
    User owner = user(), other = user();
    String c = channel(owner), s = stream(owner);
    assertEquals(
        409,
        call(
                HttpMethod.POST,
                "/streams",
                Map.of("title", "Duplicate", "description", ""),
                owner.token())
            .getStatusCode()
            .value());
    var h =
        call(
                HttpMethod.POST,
                "/streams/" + s + "/highlights",
                Map.of(
                    "source",
                    "MANUAL",
                    "reason",
                    TestFixtures.text("platformIntegrationTestText10")),
                owner.token())
            .getBody()
            .get("id")
            .toString();
    Path video = temp.resolve("test.webm");
    var p =
        new ProcessBuilder(
                ffmpeg,
                "-v",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc=size=320x180:rate=15",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440",
                "-t",
                "3",
                "-c:v",
                "libvpx-vp9",
                "-deadline",
                "realtime",
                "-c:a",
                "libopus",
                video.toString())
            .inheritIO()
            .start();
    assertTrue(p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(0, p.exitValue());
    MultiValueMap<String, Object> data = new LinkedMultiValueMap<>();
    data.add("highlightId", h);
    data.add("start", "0");
    data.add("end", "3");
    data.add("file", new FileSystemResource(video));
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(owner.token());
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    var clip =
        http.exchange(
            "/api/streams/" + s + "/segments",
            HttpMethod.POST,
            new HttpEntity<>(data, headers),
            Map.class);
    assertEquals(200, clip.getStatusCode().value());
    String clipId = clip.getBody().get("id").toString(),
        asset = clip.getBody().get("asset_id").toString();
    assertEquals(1, db.count("SELECT count(*) FROM media_asset_contents WHERE asset_id=?", UUID.fromString(asset)));
    String storageKey = db.one("SELECT storage_key FROM media_assets WHERE id=?", UUID.fromString(asset)).get("storage_key").toString();
    assertEquals(
        403, http.getForEntity("/api/media/" + asset, String.class).getStatusCode().value());
    assertEquals(
        403,
        call(
                HttpMethod.POST,
                "/clips/" + clipId + "/review",
                Map.of("approve", true),
                other.token())
            .getStatusCode()
            .value());
    assertEquals(
        200,
        call(
                HttpMethod.POST,
                "/clips/" + clipId + "/review",
                Map.of("approve", true),
                owner.token())
            .getStatusCode()
            .value());
    assertEquals(
        200, http.getForEntity("/api/media/" + asset, byte[].class).getStatusCode().value());
    byte[] initialDownload = http.getForEntity("/api/media/" + asset, byte[].class).getBody();
    Files.delete(media.path(storageKey));
    assertArrayEquals(initialDownload, http.getForEntity("/api/media/" + asset, byte[].class).getBody(),
        "Published clips must survive loss of the host's local cache");
    var edit =
        call(
            HttpMethod.PUT,
            "/clips/" + clipId,
            Map.of(
                "title",
                "Trimmed clip",
                "description",
                TestFixtures.text("platformIntegrationTestText11"),
                "start",
                .5,
                "end",
                2),
            owner.token());
    assertEquals(200, edit.getStatusCode().value());
    assertEquals("PENDING", edit.getBody().get("status"));
    assertNotEquals(asset, edit.getBody().get("asset_id"));
    String editedAsset = edit.getBody().get("asset_id").toString();
    assertEquals(
        403, http.getForEntity("/api/media/" + editedAsset, String.class).getStatusCode().value());
    assertEquals(
        1, db.count("SELECT count(*) FROM clip_versions WHERE clip_id=?", UUID.fromString(clipId)));
    assertEquals(
        200,
        call(HttpMethod.POST, "/streams/" + s + "/summary", Map.of(), owner.token())
            .getStatusCode()
            .value());
    assertEquals(
        1, db.count("SELECT count(*) FROM stream_summaries WHERE stream_id=?", UUID.fromString(s)));
    call(HttpMethod.POST, "/streams/" + s + "/end", Map.of(), owner.token());
  }
}
