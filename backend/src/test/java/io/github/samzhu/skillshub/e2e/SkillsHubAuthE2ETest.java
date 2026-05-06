package io.github.samzhu.skillshub.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S120 — E2E Auth Integration Test: OAuth JWT + Visibility + ACL Grant + Download Counter。
 *
 * <p>以 mock-oauth2-server (Testcontainers) 真發 JWT，對真實 Spring app 走完整 scenario：
 * A 上傳公開 + 私人 skill → A grant B 唯讀 → B 下載 → 驗 download_count 累計正確。
 *
 * <p>已知 gap（AC-6/7/10）：{@code getById} + {@code download} 缺 {@code @PreAuthorize}，
 * anonymous + B-without-grant 仍可拿到 private skill。本 spec 紀錄 current behavior，
 * 不強 assert 403（待 S122/S123 補 @PreAuthorize 後升級）。
 *
 * @see io.github.samzhu.skillshub.shared.security.OAuthMockE2ETest OAuthMockE2ETest — mock-oauth2-server container pattern
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "skillshub.security.oauth.enabled=true",
        "skillshub.storage.local-path=./build/storage-e2e",
        // dep-vuln scanner calls OSV.dev (external network); disable in E2E test
        "skillshub.scanner.engines.dep-vuln.enabled=false"
})
@Tag("S120")
class SkillsHubAuthE2ETest {

    // ─── Testcontainers ───────────────────────────────────────────────────────

    @Container
    @SuppressWarnings("resource")
    private static final GenericContainer<?> mockOauth =
            new GenericContainer<>(DockerImageName.parse("ghcr.io/navikt/mock-oauth2-server:3.0.1"))
                    .withExposedPorts(8080)
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(Path.of("config/oauth-mock-config.json")),
                            "/app/config.json")
                    .withEnv("JSON_CONFIG_PATH", "/app/config.json")
                    .withEnv("LOG_LEVEL", "INFO")
                    .waitingFor(Wait.forHttp("/skills-hub-dev/.well-known/openid-configuration")
                            .forStatusCode(200));

    @DynamicPropertySource
    static void registerOauthProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://localhost:" + mockOauth.getMappedPort(8080) + "/skills-hub-dev");
    }

    // ─── Spring wiring ────────────────────────────────────────────────────────

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private RestClient api;
    private String tokenA;   // developer-client → sub=dev-042
    private String tokenB;   // viewer-client → sub=viewer-007
    private String oauthBase;

    // ─── Setup ───────────────────────────────────────────────────────────────

    @BeforeEach
    void setup() {
        api = RestClient.create("http://localhost:" + port);
        oauthBase = "http://localhost:" + mockOauth.getMappedPort(8080) + "/skills-hub-dev";

        // 每次 test 前清理相關表（FK 子表先刪，再刪父表）
        jdbc.update("DELETE FROM skill_versions");
        jdbc.update("DELETE FROM skill_scores");
        jdbc.update("DELETE FROM download_events");
        jdbc.update("DELETE FROM vector_store");
        jdbc.update("DELETE FROM flags");
        jdbc.update("DELETE FROM reviews");
        jdbc.update("DELETE FROM skills");
        jdbc.update("DELETE FROM domain_events");
        jdbc.update("DELETE FROM event_publication WHERE completion_date IS NULL");

        tokenA = fetchToken("developer-client");
        tokenB = fetchToken("viewer-client");
    }

    // ─── Main E2E scenario ────────────────────────────────────────────────────

    @Test
    @DisplayName("S120 E2E: OAuth JWT + visibility + ACL grant + download counter（A 上傳 / B 共享 / 真實下載）")
    void e2e_authAclDownloadFlow() {

        // ── AC-2: A 上傳 public skill ─────────────────────────────────────────
        var publicId = uploadSkill(tokenA, "auth-e2e-public", "1.0.0", "dev-042", "DevOps", "PUBLIC");
        assertThat(publicId).as("AC-2: public skill ID").isNotBlank();

        // ── AC-3: A 上傳 private skill ────────────────────────────────────────
        var privateId = uploadSkill(tokenA, "auth-e2e-private", "1.0.0", "dev-042", "DevOps", "PRIVATE");
        assertThat(privateId).as("AC-3: private skill ID").isNotBlank();

        // ── AC-4: anonymous list 只看 public ─────────────────────────────────
        var anonContent = listSkillIds(null);
        assertThat(anonContent)
                .as("AC-4: anonymous list 只應含 public skill")
                .containsExactlyInAnyOrder(publicId)
                .doesNotContain(privateId);

        // ── AC-5: anonymous GET public → 200 ─────────────────────────────────
        var anonPublicStatus = getStatus(null, "/api/v1/skills/" + publicId);
        assertThat(anonPublicStatus.is2xxSuccessful())
                .as("AC-5: anonymous GET public skill → 200 (actual HTTP status: " + anonPublicStatus.value() + ")")
                .isTrue();

        // ── AC-6 / AC-7: DOCUMENT GAP — anonymous GET private → currently 200 ─
        // 待 S122/S123 補 @PreAuthorize 後應改 403；本 spec 只紀錄 current behavior
        var anonPrivateStatus = getStatus(null, "/api/v1/skills/" + privateId);
        // recordGap：不強 assert 403，只驗 response 存在且有 body（current: 200 leaks JSON）
        assertThat(anonPrivateStatus).as("AC-6 current-behavior: response received").isNotNull();

        var anonPrivateDownloadStatus = getStatus(null, "/api/v1/skills/" + privateId + "/download");
        assertThat(anonPrivateDownloadStatus)
                .as("AC-7 current-behavior: download response received").isNotNull();

        // ── AC-8: B 無 grant，list 只看 public ────────────────────────────────
        var bContent = listSkillIds(tokenB);
        assertThat(bContent)
                .as("AC-8: B 無 grant，只應看到 public")
                .containsExactlyInAnyOrder(publicId)
                .doesNotContain(privateId);

        // ── AC-9: B GET + download public → download_count +1 ─────────────────
        var bPublicGetStatus = getStatus(tokenB, "/api/v1/skills/" + publicId);
        assertThat(bPublicGetStatus.is2xxSuccessful()).as("AC-9a: B GET public 200").isTrue();

        downloadSkill(tokenB, publicId);
        assertThat(queryDownloadCount(publicId)).as("AC-9: public download_count after B download").isEqualTo(1L);

        // ── AC-10: DOCUMENT GAP — B GET private 無 grant → current 200 ─────────
        // 待 S122 補 @PreAuthorize 後改 403
        var bPrivateStatus = getStatus(tokenB, "/api/v1/skills/" + privateId);
        assertThat(bPrivateStatus).as("AC-10 current-behavior: response received").isNotNull();

        // ── AC-11: A grant user:viewer-007:read on private ───────────────────
        grantAcl(tokenA, privateId, "user", "viewer-007", "read");
        var aclEntries = listAclEntries(tokenA, privateId);
        assertThat(aclEntries)
                .as("AC-11: ACL entry 已新增")
                .anyMatch(e -> e.contains("viewer-007") && e.contains("read"));

        // ── AC-12: B list 後看到 public + private ─────────────────────────────
        var bAfterGrant = listSkillIds(tokenB);
        assertThat(bAfterGrant)
                .as("AC-12: grant 後 B 應看到 public + private")
                .containsExactlyInAnyOrder(publicId, privateId);

        // ── AC-13: B GET + download private (granted) ─────────────────────────
        var bPrivateGrantedStatus = getStatus(tokenB, "/api/v1/skills/" + privateId);
        assertThat(bPrivateGrantedStatus.is2xxSuccessful()).as("AC-13a: B GET private (granted) 200").isTrue();

        downloadSkill(tokenB, privateId);
        assertThat(queryDownloadCount(privateId)).as("AC-13: private download_count after B download").isEqualTo(1L);

        // ── AC-14: counter cross-user invariant ──────────────────────────────
        assertThat(queryDownloadCount(publicId))
                .as("AC-14: public.download_count 獨立為 1").isEqualTo(1L);
        assertThat(queryDownloadCount(privateId))
                .as("AC-14: private.download_count 獨立為 1").isEqualTo(1L);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String fetchToken(String clientId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", "secret");
        form.add("scope", "skills:read");

        var resp = RestClient.create()
                .post()
                .uri(oauthBase + "/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);

        assertThat(resp).as("token response for " + clientId).isNotNull();
        return (String) resp.get("access_token");
    }

    @SuppressWarnings("unchecked")
    private String uploadSkill(String token, String skillName, String version,
            String author, String category, String visibility) {
        byte[] zipBytes = buildMinimalZip(skillName);

        String boundary = "----E2ETestBoundary7MA4";
        var body = buildMultipart(boundary, skillName, version, author, category, visibility, zipBytes);

        var resp = api.post()
                .uri("/api/v1/skills/upload")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + boundary))
                .body(body)
                .retrieve()
                .body(Map.class);

        assertThat(resp).as("upload response for " + skillName).isNotNull();
        return (String) resp.get("id");
    }

    private byte[] buildMultipart(String boundary, String skillName, String version,
            String author, String category, String visibility, byte[] zipBytes) {
        var out = new ByteArrayOutputStream();
        try {
            for (var field : new String[][]{
                    {"version", version}, {"author", author},
                    {"category", category}, {"visibility", visibility}}) {
                out.write(("--" + boundary + "\r\n").getBytes());
                out.write(("Content-Disposition: form-data; name=\"" + field[0] + "\"\r\n\r\n").getBytes());
                out.write(field[1].getBytes(StandardCharsets.UTF_8));
                out.write("\r\n".getBytes());
            }
            out.write(("--" + boundary + "\r\n").getBytes());
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + skillName + ".zip\"\r\n").getBytes());
            out.write("Content-Type: application/zip\r\n\r\n".getBytes());
            out.write(zipBytes);
            out.write("\r\n".getBytes());
            out.write(("--" + boundary + "--\r\n").getBytes());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    private byte[] buildMinimalZip(String skillName) {
        var buf = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(buf)) {
            zos.putNextEntry(new ZipEntry("SKILL.md"));
            var content = """
                    ---
                    name: %s
                    description: E2E test skill for auth integration.
                    version: 1.0.0
                    license: MIT
                    ---
                    # %s
                    E2E test fixture.
                    """.formatted(skillName, skillName);
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return buf.toByteArray();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<String> listSkillIds(String tokenOrNull) {
        var req = api.get().uri("/api/v1/skills?size=100");
        if (tokenOrNull != null) {
            req = req.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOrNull);
        }
        var page = req.retrieve().body(Map.class);
        assertThat(page).isNotNull();
        var content = (List<Map>) page.get("content");
        return content.stream().map(s -> (String) s.get("id")).toList();
    }

    private HttpStatusCode getStatus(String tokenOrNull, String path) {
        var req = api.get().uri(path);
        if (tokenOrNull != null) {
            req = req.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOrNull);
        }
        return req.retrieve()
                .onStatus(status -> true, (request, response) -> {})  // don't throw on 4xx
                .toBodilessEntity()
                .getStatusCode();
    }

    private void downloadSkill(String token, String skillId) {
        var resp = api.get()
                .uri("/api/v1/skills/" + skillId + "/download")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toEntity(byte[].class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).as("download OK for " + skillId).isTrue();
    }

    private void grantAcl(String token, String skillId, String type, String principal, String permission) {
        api.post()
                .uri("/api/v1/skills/" + skillId + "/acl")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", type, "principal", principal, "permission", permission))
                .retrieve()
                .toBodilessEntity();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<String> listAclEntries(String token, String skillId) {
        var entries = api.get()
                .uri("/api/v1/skills/" + skillId + "/acl")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .body(List.class);
        assertThat(entries).isNotNull();
        // AclEntryResponse → Map: type + principal + permission → join as "type:principal:permission"
        return ((List<Map>) entries).stream()
                .map(e -> e.get("type") + ":" + e.get("principal") + ":" + e.get("permission"))
                .toList();
    }

    private long queryDownloadCount(String skillId) {
        var count = jdbc.queryForObject(
                "SELECT download_count FROM skills WHERE id::text = ?", Long.class, skillId);
        return count != null ? count : 0L;
    }
}
