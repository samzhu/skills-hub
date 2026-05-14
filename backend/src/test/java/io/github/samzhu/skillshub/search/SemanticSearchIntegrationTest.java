package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.shared.security.testsupport.TestUserSeed;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * E2E integration test for the semantic search HTTP endpoint — verifies AC-1 / AC-2
 * (基本契約) + AC-7~AC-10 (ACL e2e) against the full Spring application context with real
 * {@link SkillshubPgVectorStore} + Testcontainers pgvector PostgreSQL.
 *
 * <p>Uses {@link Autowired} {@link EmbeddingModel} from {@link TestcontainersConfiguration}'s
 * {@link org.springframework.context.annotation.Primary} mock — fixed seed 42 produces the
 * same 768-dimensional vector for both document and query embeddings, so cosine similarity
 * equals 1.0, guaranteeing seeded documents are returned regardless of the query text.
 *
 * <p><b>S025b T04 absorption</b>（per spec §4.8 / §5.5）— 吸收 {@code SemanticSearchAclTest}
 * 4 個 ACL e2e test 為單一 SpringBootTest cache key（原 ACL test 不是 RANDOM_PORT 但仍佔
 * cache key；併入後共用同一 context）。共用 {@link MockMvc} 走 {@code .with(jwt())}
 * 注入 OAuth2 Resource Server expected {@code JwtAuthenticationToken}；既有 2 test 用
 * {@link TestRestTemplate}（anonymous → CurrentUserProvider lab fallback path）保留 — 覆蓋
 * lab user search 行為。
 *
 * @see SemanticSearchService
 * @see SkillshubPgVectorStore
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Inline properties have the highest priority (override external config files).
        // Prevents config/application-secrets.properties from enabling googleGenAiTextEmbedding
        // alongside the @Primary mockEmbeddingModel from TestcontainersConfiguration.
        properties = {
                "spring.ai.model.embedding.text=none",
                "spring.ai.google.genai.embedding.api-key=TEST-DISABLED"
        })
@AutoConfigureTestRestTemplate
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SemanticSearchIntegrationTest {

    /** Stable UUID used in basic AC-1; deleted in @BeforeEach to isolate AC-2. */
    private static final String TEST_DOC_ID = "11111111-1111-1111-1111-111111111111";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SkillRepository skillRepo;
    // S025a-T03: 從 @MockitoBean 改 @Autowired — TestcontainersConfiguration.@Bean @Primary
    // mockEmbeddingModel() 是該 bean 的真實實例；test 需要把它傳給 SkillshubPgVectorStore.builder()。
    @Autowired private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        // S025b T04 absorb: 跨 test class 共享 Testcontainer，TRUNCATE skills CASCADE 自動清 vector_store
        // （FK ON DELETE CASCADE）— 取代原 IT class 個別 row delete 與 ACL test class 各自 TRUNCATE。
        jdbc.update("TRUNCATE TABLE skills RESTART IDENTITY CASCADE");
        // S154-T06: pre-seed (google, alice/bob/...) → 穩定 platform user_id；JWT auth 走
        // upsertFromOidc 找到既有 row 直接返 ALICE_ID/BOB_ID；ACL filter 對得上 user:ALICE_ID:read。
        TestUserSeed.seedDefaults(jdbc);
    }

    @Test
    @DisplayName("AC-1: GET /api/v1/search/semantic returns HTTP 200 with results containing all required fields")
    void semanticSearchReturnsResultsWithAllRequiredFields() {
        // FK 前置：skills row 必須存在（vector_store.skill_id REFERENCES skills.id）
        var now = Instant.now();
        // S059: status 改 PUBLISHED — semantic search SQL 加 JOIN skills WHERE status='PUBLISHED'
        skillRepo.save(Skill.fromRow(
                TEST_DOC_ID, "docker-compose-helper", "管理 Docker Compose 多容器部署",
                "sam", "devops", "1.0.0", "LOW", "PUBLISHED", 0L, now, now,
                List.of(), null));

        // S017：TestRestTemplate 不帶 JWT → CurrentUserProvider fallback (labUserId="lab-user", ["admin"], [])
        //   → expand = ["user:lab-user:read", "role:admin:read"]
        // 為讓既有 IT（不驗 ACL）搜得到 seeded doc，acl_entries 需含 "role:admin:read"（lab user 的 admin role 命中）。
        SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                .owner("integration-test-owner")
                .skillId(TEST_DOC_ID)
                .aclEntries(List.of("user:integration-test-owner:read", "public:*:read"))
                .build()
                .add(List.of(Document.builder()
                        .id(TEST_DOC_ID)
                        .text("docker-compose-helper 管理 Docker Compose 多容器部署")
                        .metadata(Map.of(
                                "skillId", TEST_DOC_ID,
                                "name", "docker-compose-helper",
                                "description", "管理 Docker Compose 多容器部署",
                                "author", "sam",
                                "category", "devops",
                                "downloadCount", 0L))
                        .build()));

        var response = restTemplate.getForEntity(
                "/api/v1/search/semantic?q=" + URLEncoder.encode("部署容器應用", StandardCharsets.UTF_8),
                SemanticSearchResult[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isNotEmpty();

        var result = Arrays.stream(response.getBody())
                .filter(r -> TEST_DOC_ID.equals(r.id()))
                .findFirst();
        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("docker-compose-helper");
        assertThat(result.get().description()).isEqualTo("管理 Docker Compose 多容器部署");
        assertThat(result.get().author()).isEqualTo("sam");
        assertThat(result.get().category()).isEqualTo("devops");
        // score = 1 - cosine distance；fixed-seed embeddings → distance ≈ 0 → score ≈ 1.0
        assertThat(result.get().score()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("AC-2: GET /api/v1/search/semantic returns HTTP 200 with empty array when no documents match")
    void semanticSearchReturnsEmptyArrayWhenNoDocumentsMatch() {
        var response = restTemplate.getForEntity(
                "/api/v1/search/semantic?q=" + URLEncoder.encode("量子力學計算", StandardCharsets.UTF_8),
                SemanticSearchResult[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull().isEmpty();
    }

    // === S025b T04 absorbed from SemanticSearchAclTest ===

    @Test
    @DisplayName("AC-7: alice JWT GET /search/semantic → 只回 alice 有權限的 skill（不含 bob's）")
    @Tag("AC-7")
    void aliceSeesOnlyOwnSkills() throws Exception {
        // S154-T06: ACL principal id 是 platform user_id（ALICE_ID），不是 OAuth sub "alice"
        var skillA = seedVectorWithAcl("alice",
                List.of("user:" + TestUserSeed.ALICE_ID + ":read"), "docker-compose-helper");
        seedVectorWithAcl("bob",
                List.of("user:" + TestUserSeed.BOB_ID + ":read"), "kubernetes-deploy");

        mockMvc.perform(get("/api/v1/search/semantic")
                .param("q", URLEncoder.encode("docker", StandardCharsets.UTF_8))
                .with(jwtFor("alice", List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + skillA + "')]").exists())
                .andExpect(jsonPath("$[?(@.author=='bob')]").doesNotExist());
    }

    @Test
    @DisplayName("AC-8: carol JWT(groups=engineering) → 看到 group:engineering:read row")
    @Tag("AC-8")
    void groupNamespace_endToEnd() throws Exception {
        var rowC = seedVectorWithAcl("dept",
                List.of("user:dept-admin:read", "group:engineering:read"),
                "engineering-only-tool");
        seedActiveGroupMembership("engineering", TestUserSeed.CAROL_ID);

        mockMvc.perform(get("/api/v1/search/semantic")
                .param("q", "engineering tool")
                .with(jwtFor("carol", List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + rowC + "')]").exists());
    }

    @Test
    @DisplayName("S169 AC-4: Bob 透過 S170 group_members/group_closure principal 看到 group ACL skill")
    @Tag("S169")
    @Tag("AC-4")
    void groupPrincipalContext_endToEnd() throws Exception {
        var row = seedVectorWithAcl("dept",
                List.of("group:g_d4e5f6:read"),
                "s170-group-only-tool");
        seedActiveGroupMembership("g_d4e5f6", TestUserSeed.BOB_ID);

        mockMvc.perform(get("/api/v1/search/semantic")
                .param("q", "group only")
                .with(jwtFor("bob", List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + row + "')]").exists());
    }

    @Test
    @DisplayName("AC-9: 未認證 anonymous → empty result（vector_store 無 lab user matches；fail-secure）")
    @Tag("AC-9")
    void anonymous_failSecure() throws Exception {
        // 種 row：owner 不是 lab user（application.yaml `lab.user-id: lab-user`）
        seedVectorWithAcl("alice", List.of("user:" + TestUserSeed.ALICE_ID + ":read"), "private-skill");

        mockMvc.perform(get("/api/v1/search/semantic")
                .param("q", "private"))
                .andExpect(status().isOk())
                // anonymous → CurrentUserProvider fallback (labUserId="lab-user", ["admin"], [])
                // → patterns = ["user:lab-user:read", "role:admin:read"]
                // → 不命中 acl_entries=["user:ALICE_ID:read"] → empty result
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("AC-10: SemanticSearchResult JSON 合約 — id/name/description/author/category/score 欄位齊全")
    @Tag("AC-10")
    void responseShapeUnchanged() throws Exception {
        var skillA = seedVectorWithAcl("alice",
                List.of("user:" + TestUserSeed.ALICE_ID + ":read"), "shape-test");

        mockMvc.perform(get("/api/v1/search/semantic")
                .param("q", "shape")
                .with(jwtFor("alice", List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + skillA + "')].name").exists())
                .andExpect(jsonPath("$[?(@.id=='" + skillA + "')].description").exists())
                .andExpect(jsonPath("$[?(@.id=='" + skillA + "')].author").exists())
                .andExpect(jsonPath("$[?(@.id=='" + skillA + "')].category").exists())
                .andExpect(jsonPath("$[?(@.id=='" + skillA + "')].score").exists());
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

    /**
     * 種 vector_store row：先 seed skills row（FK 前置），再用 builder.aclEntries 寫
     * vector_store 含指定 ACL。回傳 skillId 給 caller 做 jsonPath 過濾。
     */
    private String seedVectorWithAcl(String owner, List<String> aclEntries, String name) {
        var skillId = UUID.randomUUID().toString();
        var now = Instant.now();
        skillRepo.save(Skill.fromRow(
                skillId, name + "-" + skillId.substring(0, 8),
                "S017 ACL E2E test fixture", owner, "testing",
                "1.0.0", "LOW", "PUBLISHED", 0L, now, now, List.of(), null));

        SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                .owner(owner)
                .skillId(skillId)
                .aclEntries(aclEntries)
                .build()
                .add(List.of(Document.builder()
                        .id(skillId)
                        .text(name + " body")
                        .metadata(Map.of(
                                "skillId", skillId,
                                "name", name,
                                "description", "S017 ACL E2E test",
                                "author", owner,
                                "category", "testing"))
                        .build()));

        // 確認 acl_entries 已寫入
        var aclJson = jdbc.queryForObject(
                "SELECT acl_entries::text FROM vector_store WHERE id = ?::uuid",
                String.class, skillId);
        assertThat(aclJson).contains(aclEntries.get(0));

        return skillId;
    }

    private void seedActiveGroupMembership(String groupId, String userId) {
        jdbc.update("""
                INSERT INTO groups (id, parent_id, kind, display_name, slug, status,
                    sort_order, created_at, updated_at)
                VALUES (?, NULL, 'TEAM', 'Group ' || ?, ?, 'ACTIVE', 0, NOW(), NOW())
                ON CONFLICT (id) DO NOTHING
                """, groupId, groupId, groupId);
        jdbc.update("""
                INSERT INTO group_closure (ancestor_id, descendant_id, depth)
                VALUES (?, ?, 0)
                ON CONFLICT DO NOTHING
                """, groupId, groupId);
        jdbc.update("""
                INSERT INTO group_members (group_id, user_id, created_at)
                VALUES (?, ?, NOW())
                ON CONFLICT DO NOTHING
                """, groupId, userId);
    }
}
