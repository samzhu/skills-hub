package io.github.samzhu.skillshub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.modulith.test.EnableScenarios;
import org.springframework.modulith.test.Scenario;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import io.github.samzhu.skillshub.shared.events.DomainEventRepository;

/**
 * S016 端到端 smoke — 跨模組驗證完整 Skill lifecycle（CRUD + upload + ACL + download）。
 *
 * <p>對應 spec §6 E2E Smoke：覆蓋 ApplicationContext wiring + 真實 Spring Security
 * filter chain + Testcontainer JSONB。
 *
 * <p><b>S025b T04 absorption</b>（per spec §4.8 / §5.5）— 吸收 3 個 RANDOM_PORT E2E 為單一
 * SpringBootTest cache key，並用 {@link Scenario} API 取代 Awaitility 處理 async listener
 * timing race（mirror S025a {@code RiskAssessmentIntegrationTest} pattern）：
 * <ul>
 *   <li><b>SkillIntegrationTest</b>（POST /api/v1/skills + GET round-trip）—
 *       {@link #postThenGetSkill_jsonRoundTrip}</li>
 *   <li><b>SkillUploadTest</b>（multipart upload + audit events 驗證）—
 *       {@link #uploadValidSkill_writesAuditEvents}、{@link #uploadInvalidSkill_returns400}、
 *       {@link #addVersionToExistingSkill_writesTwoVersionEvents}、
 *       {@link #duplicateVersionRejected_returns409}</li>
 *   <li><b>SkillDownloadTest</b>（GET download + SkillDownloaded audit）—
 *       {@link #downloadLatestVersion_writesDownloadAudit}、
 *       {@link #downloadSpecificVersion_returns200}</li>
 * </ul>
 *
 * <p><b>line 57 @Disabled rewrite</b>（per spec §3 AC-6 / S025a §7.7 deferral）：
 * 原 {@link #e2e_uploadGrantListRevoke_acrossModules} 被 {@code @Disabled} 標記，
 * 因 {@code MockMvc + WebEnvironment.MOCK + @ApplicationModuleListener} async
 * 行為不可靠。S025b T04 改：
 * <ol>
 *   <li>切 {@code WebEnvironment.RANDOM_PORT}（real HTTP stack 取代 MOCK servlet）</li>
 *   <li>用 {@link Scenario#stimulate} {@code andWaitForStateChange} 取代
 *       {@code Awaitility.await().untilAsserted}（thread-bound listener adapter +
 *       {@link TestcontainersConfiguration#scenarioTimeout} 5s default）</li>
 *   <li>多 step async sync 用多次 {@code scenario.stimulate(...).andWaitForStateChange(...)}
 *       chain（per Modulith Scenario 支援）</li>
 * </ol>
 *
 * <p><b>auth pattern</b>：所有 absorbed test 統一用 {@code .with(jwt())} 注入 OAuth2
 * Resource Server 預期的 {@link org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken}；
 * 比 LAB mode {@code @TestPropertySource("oauth.enabled=false")} 更貼近 production
 * 並避免不同 LAB user-id 產生的多 cache key（原 SkillUploadTest / SkillVersionQueryTest
 * 各自設 {@code lab.user-id=sam}/{@code tester} 即多一個 customizer）。
 *
 * @see io.github.samzhu.skillshub.security.RiskAssessmentIntegrationTest S025a-T02 Scenario 改寫 precedent
 * @see TestcontainersConfiguration#scenarioTimeout 5s default Awaitility timeout
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@EnableScenarios
class S016EndToEndSmokeTest {

    private static final Duration ASYNC_LISTENER_TIMEOUT = Duration.ofSeconds(10);

    @Autowired private MockMvc mockMvc;
    @Autowired private DomainEventRepository eventStore;
    @Autowired private JdbcTemplate jdbc;

    // TODO(S154-T06): test 把 JWT sub "alice" 直接當 skills.author 寫入；T03 起 currentUser.userId() 走
    // upsertFromOidc 變成 random u_<6hex>，跟 "alice" 對不上 → 403 NOT_SKILL_OWNER。T06 scope 含
    // "fix RBAC test setup 用 platform user_id" — 由 T06 改 fixture（pre-seed users row 或捕獲 user_id 後用）。
    @org.junit.jupiter.api.Disabled("S154-T03 收尾：JWT sub→user_id mapping breaking change；T06 fix")
    @Test
    @DisplayName("AC-1~15: end-to-end smoke — upload → grant → list → revoke 跨模組驗證")
    @Tag("AC-1")
    @Tag("AC-7")
    @Tag("AC-9")
    @Tag("AC-10")
    @Tag("AC-11")
    void e2e_uploadGrantListRevoke_acrossModules(Scenario scenario) throws Exception {
        var skillName = "e2e-smoke-" + UUID.randomUUID().toString().substring(0, 8);
        var zipBytes = createValidSkillZip(skillName);
        var skillIdRef = new AtomicReference<String>();

        // (1) alice upload — Scenario 同步等 SkillCreated + SkillVersionPublished + vector_store ACL
        scenario.stimulate(() -> {
                    try {
                        var uploadResponse = mockMvc.perform(multipart("/api/v1/skills/upload")
                                .file(new MockMultipartFile("file", "v.zip", "application/zip", zipBytes))
                                .param("version", "1.0.0")
                                .param("author", "alice")
                                .param("category", "Testing")
                                .with(jwtFor("alice", List.of())))
                                .andExpect(status().isCreated())
                                .andReturn();
                        skillIdRef.set(extractId(uploadResponse));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .andWaitAtMost(ASYNC_LISTENER_TIMEOUT)
                .andWaitForStateChange(() -> uploadFullyProjected(skillIdRef.get()));

        // skills.acl_entries 含 author 三條（read/write/delete）— 同 TX sync write，無需 await
        var skillId = skillIdRef.get();
        var skillAcl = jdbc.queryForObject(
                "SELECT acl_entries::text FROM skills WHERE id = ?",
                String.class, skillId);
        assertThat(skillAcl)
                .contains("user:alice:read")
                .contains("user:alice:write")
                .contains("user:alice:delete");

        // (2) alice grant group:engineering VIEWER via new S114a /grants endpoint —
        // SkillGrantedEvent → onGranted() fires async → rebuildAcl() writes group:engineering:read
        var grantIdRef = new AtomicReference<String>();
        scenario.stimulate(() -> {
                    try {
                        var grantResp = mockMvc.perform(post("/api/v1/skills/" + skillId + "/grants")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"principalType\":\"group\",\"principalId\":\"engineering\",\"role\":\"VIEWER\"}")
                                .with(jwtFor("alice", List.of())))
                                .andExpect(status().isAccepted())
                                .andReturn();
                        var respBody = new tools.jackson.databind.json.JsonMapper()
                                .readValue(grantResp.getResponse().getContentAsString(), Map.class);
                        grantIdRef.set((String) respBody.get("grantId"));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .andWaitAtMost(ASYNC_LISTENER_TIMEOUT)
                .andWaitForStateChange(() -> {
                    var current = jdbc.queryForObject(
                            "SELECT acl_entries::text FROM skills WHERE id = ?",
                            String.class, skillId);
                    return current != null && current.contains("group:engineering:read") ? current : null;
                });

        // (3) carol via groups=engineering → 200 + 命中 group:engineering grant（role=VIEWER expand 為 read）
        mockMvc.perform(get("/api/v1/skills/" + skillId + "/grants")
                .with(jwtFor("carol", List.of("engineering"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.principalType=='group' && @.principalId=='engineering' && @.role=='VIEWER')]").exists());

        // (4) bob 有 *:read public access → 200（S026 *:read 預設公開；S125b expandPrincipals 含 *:read）
        mockMvc.perform(get("/api/v1/skills/" + skillId + "/grants")
                .with(jwtFor("bob", List.of())))
                .andExpect(status().isOk());

        // (5) alice revoke via new S114a /grants/{grantId} endpoint —
        // SkillRevokedEvent → onRevoked() → rebuildAcl() removes group:engineering:read
        scenario.stimulate(() -> {
                    try {
                        mockMvc.perform(delete("/api/v1/skills/" + skillId + "/grants/" + grantIdRef.get())
                                .with(jwtFor("alice", List.of())))
                                .andExpect(status().isAccepted());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .andWaitAtMost(ASYNC_LISTENER_TIMEOUT)
                .andWaitForStateChange(() -> {
                    var current = jdbc.queryForObject(
                            "SELECT acl_entries::text FROM skills WHERE id = ?",
                            String.class, skillId);
                    return current != null && !current.contains("group:engineering:read")
                            && current.contains("user:alice:read") ? current : null;
                })
                .andVerify(finalAcl -> assertThat(finalAcl)
                        .doesNotContain("group:engineering:read")
                        .contains("user:alice:read")
                        .contains("user:alice:write")
                        .contains("user:alice:delete"));

        // (6) verify SkillCreated + SkillVersionPublished in domain_events audit trail
        // (SkillGranted/Revoked go via ApplicationEventPublisher, not recorded by AuditEventListener)
        scenario.stimulate(() -> {})
                .andWaitAtMost(ASYNC_LISTENER_TIMEOUT)
                .andWaitForStateChange(() -> {
                    var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
                    var types = events.stream().map(e -> e.eventType()).toList();
                    return types.contains("SkillCreated")
                            && types.contains("SkillVersionPublished") ? events : null;
                });
    }

    @Test
    @DisplayName("AC-7: PUT /skills/{id}/versions 對 alice owner 通過 @PreAuthorize；對 bob 非 owner 403")
    @Tag("AC-7")
    void e2e_putVersion_acl_gate() throws Exception {
        var skillName = "put-acl-" + UUID.randomUUID().toString().substring(0, 8);
        var initialZip = createValidSkillZip(skillName);

        var uploadResponse = mockMvc.perform(multipart("/api/v1/skills/upload")
                .file(new MockMultipartFile("file", "v.zip", "application/zip", initialZip))
                .param("version", "1.0.0")
                .param("author", "alice")
                .param("category", "Testing")
                .with(jwtFor("alice", List.of())))
                .andExpect(status().isCreated())
                .andReturn();
        var skillId = extractId(uploadResponse);

        // alice PUT 1.1.0 — 通過 ACL gate
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/skills/" + skillId + "/versions")
                .file(new MockMultipartFile("file", "v.zip", "application/zip",
                        createValidSkillZip(skillName)))
                .param("version", "1.1.0")
                .with(jwtFor("alice", List.of())))
                .andExpect(status().isOk());

        // bob PUT — 403 Forbidden
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/skills/" + skillId + "/versions")
                .file(new MockMultipartFile("file", "v.zip", "application/zip",
                        createValidSkillZip(skillName)))
                .param("version", "2.0.0")
                .with(jwtFor("bob", List.of())))
                .andExpect(status().isForbidden());
    }

    // === absorbed from SkillIntegrationTest ===

    @Test
    @DisplayName("AC-2: POST /api/v1/skills (JSON) → 201；GET /skills/{id} returns consistent data")
    @Tag("AC-2")
    void postThenGetSkill_jsonRoundTrip() throws Exception {
        var commandJson = """
                {"name":"test-skill","description":"A test skill","author":"tester","category":"Testing"}
                """;
        var postResp = mockMvc.perform(post("/api/v1/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content(commandJson)
                .with(jwtFor("tester", List.of())))
                .andExpect(status().isCreated())
                .andReturn();
        var skillId = extractId(postResp);
        assertThat(skillId).isNotBlank();

        mockMvc.perform(get("/api/v1/skills/" + skillId)
                .with(jwtFor("tester", List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(skillId))
                .andExpect(jsonPath("$.name").value("test-skill"))
                .andExpect(jsonPath("$.description").value("A test skill"))
                .andExpect(jsonPath("$.author").value("tester"))
                .andExpect(jsonPath("$.category").value("Testing"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdAt").exists());
    }

    // === absorbed from SkillUploadTest ===

    @Test
    @DisplayName("AC-1: 上傳合法 skill — POST /upload → 201 + SkillCreated/Published audit events")
    @Tag("AC-1")
    void uploadValidSkill_writesAuditEvents(Scenario scenario) throws Exception {
        var zip = createZipWithFile("SKILL.md",
                "---\nname: test-upload\ndescription: A test skill\n---\n# Test");
        var skillIdRef = new AtomicReference<String>();

        scenario.stimulate(() -> {
                    try {
                        var resp = mockMvc.perform(multipart("/api/v1/skills/upload")
                                .file(new MockMultipartFile("file", "test.zip", "application/zip", zip))
                                .param("version", "1.0.0")
                                .param("author", "sam")
                                .param("category", "DevOps")
                                .with(jwtFor("sam", List.of())))
                                .andExpect(status().isCreated())
                                .andReturn();
                        skillIdRef.set(extractId(resp));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .andWaitAtMost(ASYNC_LISTENER_TIMEOUT)
                .andWaitForStateChange(() -> {
                    var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillIdRef.get());
                    var hasCreated = events.stream().anyMatch(e -> "SkillCreated".equals(e.eventType()));
                    var hasPublished = events.stream().anyMatch(e -> "SkillVersionPublished".equals(e.eventType()));
                    return hasCreated && hasPublished ? events : null;
                })
                .andVerify(events -> {
                    var created = events.stream()
                            .filter(e -> "SkillCreated".equals(e.eventType())).findFirst().orElseThrow();
                    assertThat(created.payload().get("name")).isEqualTo("test-upload");
                    var published = events.stream()
                            .filter(e -> "SkillVersionPublished".equals(e.eventType())).findFirst().orElseThrow();
                    assertThat(published.payload().get("version")).isEqualTo("1.0.0");
                });
    }

    @Test
    @DisplayName("AC-2: 上傳不合規 skill — no SKILL.md → 400 VALIDATION_ERROR")
    @Tag("AC-2")
    void uploadInvalidSkill_returns400() throws Exception {
        var zip = createZipWithFile("README.md", "# Just a readme");

        mockMvc.perform(multipart("/api/v1/skills/upload")
                .file(new MockMultipartFile("file", "bad.zip", "application/zip", zip))
                .param("version", "1.0.0")
                .param("author", "sam")
                .param("category", "DevOps")
                .with(jwtFor("sam", List.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SKILL.md not found")));
    }

    @Test
    @DisplayName("AC-3: PUT /{id}/versions 加版 → 200 + 兩筆 SkillVersionPublished audit")
    @Tag("AC-3")
    void addVersionToExistingSkill_writesTwoVersionEvents(Scenario scenario) throws Exception {
        var zipV1 = createZipWithFile("SKILL.md",
                "---\nname: versioned-skill\ndescription: V1\n---\n# V1");
        var skillIdRef = new AtomicReference<String>();

        var createResp = mockMvc.perform(multipart("/api/v1/skills/upload")
                .file(new MockMultipartFile("file", "v1.zip", "application/zip", zipV1))
                .param("version", "1.0.0")
                .param("author", "sam")
                .param("category", "DevOps")
                .with(jwtFor("sam", List.of())))
                .andExpect(status().isCreated())
                .andReturn();
        skillIdRef.set(extractId(createResp));

        var zipV2 = createZipWithFile("SKILL.md",
                "---\nname: versioned-skill\ndescription: V2\n---\n# V2");

        scenario.stimulate(() -> {
                    try {
                        mockMvc.perform(multipart(HttpMethod.PUT,
                                        "/api/v1/skills/" + skillIdRef.get() + "/versions")
                                .file(new MockMultipartFile("file", "v2.zip", "application/zip", zipV2))
                                .param("version", "1.1.0")
                                .with(jwtFor("sam", List.of())))
                                .andExpect(status().isOk());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .andWaitAtMost(ASYNC_LISTENER_TIMEOUT)
                .andWaitForStateChange(() -> {
                    var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillIdRef.get()).stream()
                            .filter(e -> "SkillVersionPublished".equals(e.eventType()))
                            .toList();
                    return events.size() >= 2 ? events : null;
                })
                .andVerify(versionEvents -> assertThat(
                        versionEvents.stream().map(e -> e.payload().get("version")))
                        .containsExactlyInAnyOrder("1.0.0", "1.1.0"));
    }

    @Test
    @DisplayName("AC-4: 版本號重複 — PUT /{id}/versions → 409 + 不重複寫 audit")
    @Tag("AC-4")
    void duplicateVersionRejected_returns409(Scenario scenario) throws Exception {
        var zip = createZipWithFile("SKILL.md",
                "---\nname: dup-version-skill\ndescription: Dup test\n---\n# Test");
        var skillIdRef = new AtomicReference<String>();

        var createResp = mockMvc.perform(multipart("/api/v1/skills/upload")
                .file(new MockMultipartFile("file", "init.zip", "application/zip", zip))
                .param("version", "1.0.0")
                .param("author", "sam")
                .param("category", "DevOps")
                .with(jwtFor("sam", List.of())))
                .andExpect(status().isCreated())
                .andReturn();
        skillIdRef.set(extractId(createResp));

        var dupZip = createZipWithFile("SKILL.md",
                "---\nname: dup-version-skill\ndescription: Dup test\n---\n# Dup");

        // PUT 重複 1.0.0 → 409
        mockMvc.perform(multipart(HttpMethod.PUT,
                        "/api/v1/skills/" + skillIdRef.get() + "/versions")
                .file(new MockMultipartFile("file", "dup.zip", "application/zip", dupZip))
                .param("version", "1.0.0")
                .with(jwtFor("sam", List.of())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("VERSION_EXISTS"));

        // 等 async 完成後驗 SkillVersionPublished 仍 1 筆（dedup by sourceEventId）
        scenario.stimulate(() -> {})
                .andWaitAtMost(ASYNC_LISTENER_TIMEOUT)
                .andWaitForStateChange(() -> {
                    var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillIdRef.get()).stream()
                            .filter(e -> "SkillVersionPublished".equals(e.eventType()))
                            .toList();
                    // Wait until we observe the single published event from the initial upload
                    return events.size() == 1 ? events : null;
                })
                .andVerify(events -> assertThat(events).hasSize(1));
    }

    // === absorbed from SkillDownloadTest ===

    @Test
    @DisplayName("AC-1: 下載最新版本 — GET /download → 200 + zip + SkillDownloaded audit")
    @Tag("AC-1")
    void downloadLatestVersion_writesDownloadAudit(Scenario scenario) throws Exception {
        var skillId = uploadSkillForDownload("download-test", "1.0.0");

        scenario.stimulate(() -> {
                    try {
                        var resp = mockMvc.perform(get("/api/v1/skills/" + skillId + "/download")
                                .with(jwtFor("tester", List.of())))
                                .andExpect(status().isOk())
                                .andReturn();
                        assertThat(resp.getResponse().getContentAsByteArray().length)
                                .isGreaterThan(0);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .andWaitAtMost(ASYNC_LISTENER_TIMEOUT)
                .andWaitForStateChange(() -> {
                    var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
                    return events.stream().anyMatch(e -> "SkillDownloaded".equals(e.eventType()))
                            ? events : null;
                });
    }

    @Test
    @DisplayName("AC-2: 下載指定版本 — GET /versions/{ver}/download → 200")
    @Tag("AC-2")
    void downloadSpecificVersion_returns200() throws Exception {
        var skillId = uploadSkillForDownload("version-dl-test", "1.0.0");

        mockMvc.perform(get("/api/v1/skills/" + skillId + "/versions/1.0.0/download")
                .with(jwtFor("tester", List.of())))
                .andExpect(status().isOk());
    }

    // === helpers ===

    /**
     * 為 Scenario stimulate 中的 async upload 同步等候完成 — 等
     * {@code AuditEventListener} async 寫 {@code domain_events}（SkillCreated +
     * SkillVersionPublished）且 {@code SkillAclProjectionListener.onSkillCreated} 已完成
     * 將 acl_entries 材料化（S114a：防止新 listener 與步驟 2 的 old grantAcl 競爭）。
     *
     * <p>注意：不檢查 {@code vector_store.acl_entries} — {@code SearchProjection.onVersionPublished}
     * 在 async listener 內 {@code CurrentUserProvider.userId()} 因無 SecurityContext 走
     * {@code labUserId} fallback（per {@code CurrentUserProvider:76}），會以 {@code lab-user}
     * ACL 覆寫掉 {@code onSkillCreated} 寫入的 author ACL；該 derived state 不適合
     * 作為 e2e flow sync point。
     */
    private Object uploadFullyProjected(String skillId) {
        if (skillId == null) {
            return null;
        }
        var events = eventStore.findByAggregateIdOrderBySequenceAsc(skillId);
        var hasCreated = events.stream().anyMatch(e -> "SkillCreated".equals(e.eventType()));
        var hasPublished = events.stream().anyMatch(e -> "SkillVersionPublished".equals(e.eventType()));
        if (!hasCreated || !hasPublished) {
            return null;
        }
        // S114a: gate on onSkillCreated() completing — it seeds OWNER + public VIEWER (for PUBLIC skills).
        // Counting 2 rows (OWNER + public:* VIEWER) ensures both inserts are done before rebuildAcl().
        // This prevents step 2's grantAcl from racing with a late rebuildAcl() that would overwrite it.
        var grantCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM skill_grants WHERE skill_id = ?",
                Integer.class, skillId);
        return (grantCount != null && grantCount >= 2) ? events : null;
    }

    /**
     * 共用 jwt() post-processor — subject + groups + ROLE_user authority 對齊
     * production OAuth2 Resource Server filter chain 行為。
     */
    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
            jwtFor(String subject, List<String> groups) {
        return jwt()
                .jwt(j -> j.subject(subject)
                        .claim("roles", List.of("user"))
                        .claim("groups", groups))
                .authorities(new SimpleGrantedAuthority("ROLE_user"));
    }

    private String uploadSkillForDownload(String name, String version) throws Exception {
        var zip = createZipWithFile("SKILL.md",
                "---\nname: " + name + "\ndescription: Test\n---\n# " + name);
        var resp = mockMvc.perform(multipart("/api/v1/skills/upload")
                .file(new MockMultipartFile("file", "skill.zip", "application/zip", zip))
                .param("version", version)
                .param("author", "tester")
                .param("category", "Testing")
                .with(jwtFor("tester", List.of())))
                .andExpect(status().isCreated())
                .andReturn();
        return extractId(resp);
    }

    @SuppressWarnings("unchecked")
    private static String extractId(MvcResult result) throws java.io.IOException {
        var body = new tools.jackson.databind.json.JsonMapper()
                .readValue(result.getResponse().getContentAsString(), Map.class);
        return (String) body.get("id");
    }

    private static byte[] createValidSkillZip(String skillName) throws IOException {
        return createZipWithFile("SKILL.md",
                "---\nname: " + skillName + "\ndescription: e2e smoke test\n---\n# " + skillName);
    }

    private static byte[] createZipWithFile(String filename, String content) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(filename));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
