package io.github.samzhu.skillshub.search;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S017 T3 — 端對端 ACL 語意搜尋驗證。
 *
 * <p>對應 spec §3 AC-7 / AC-8 / AC-9 / AC-10：
 * <ul>
 *   <li>alice JWT 登入 → 只看到 alice owned skill（不含 bob's）</li>
 *   <li>carol JWT(groups=["engineering"]) → 看到 group:engineering:read row</li>
 *   <li>未認證 anonymous → empty result（fail-secure；vector_store.acl_entries 不含 lab user）</li>
 *   <li>SemanticSearchResult JSON 合約不變（id/name/score 等欄位完整）</li>
 * </ul>
 *
 * <p>使用 MockMvc {@code .with(jwt())} 合成 JwtAuthenticationToken；JwtAuthenticationConverter
 * 不過此 path（生產 path 由真 JWT 整合測試 cover），故 authorities 顯式 set 對齊 production。
 */
@SpringBootTest(properties = {
        "spring.ai.model.embedding.text=none",
        "spring.ai.google.genai.embedding.api-key=TEST-DISABLED"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SemanticSearchAclTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SkillRepository skillRepo;

    @MockitoBean private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        // 隔離：跨 test class 共享 Testcontainer，TRUNCATE skills CASCADE 自動清 vector_store
        jdbc.update("TRUNCATE TABLE skills RESTART IDENTITY CASCADE");

        // 固定 seed 768-dim 向量；query 與 doc 同向量 → cosine sim ≈ 1.0 > 0.3 threshold
        when(embeddingModel.embed(any(Document.class))).thenAnswer(inv -> randomVector(768));
        when(embeddingModel.embed(anyString())).thenAnswer(inv -> randomVector(768));
        when(embeddingModel.embed(any(List.class), any(), any())).thenAnswer(inv -> {
            List<?> docs = inv.getArgument(0);
            return docs.stream().map(d -> randomVector(768)).toList();
        });
    }

    @Test
    @DisplayName("AC-7: alice JWT GET /search/semantic → 只回 alice 有權限的 skill（不含 bob's）")
    @Tag("AC-7")
    void aliceSeesOnlyOwnSkills() throws Exception {
        var skillA = seedVectorWithAcl("alice", List.of("user:alice:read"), "docker-compose-helper");
        seedVectorWithAcl("bob", List.of("user:bob:read"), "kubernetes-deploy");

        mockMvc.perform(get("/api/v1/search/semantic")
                .param("q", URLEncoder.encode("docker", StandardCharsets.UTF_8))
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + skillA + "')]").exists())
                // bob 的 skill id 不應出現
                .andExpect(jsonPath("$[?(@.author=='bob')]").doesNotExist());
    }

    @Test
    @DisplayName("AC-8: carol JWT(groups=engineering) → 看到 group:engineering:read row")
    @Tag("AC-8")
    void groupNamespace_endToEnd() throws Exception {
        var rowC = seedVectorWithAcl("dept",
                List.of("user:dept-admin:read", "group:engineering:read"),
                "engineering-only-tool");

        mockMvc.perform(get("/api/v1/search/semantic")
                .param("q", "engineering tool")
                .with(jwt()
                        .jwt(j -> j.subject("carol")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.of("engineering")))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + rowC + "')]").exists());
    }

    @Test
    @DisplayName("AC-9: 未認證 anonymous → empty result（vector_store 無 lab user matches；fail-secure）")
    @Tag("AC-9")
    void anonymous_failSecure() throws Exception {
        // 種 row：owner 不是 lab user（application.yaml `lab.user-id: lab-user`）
        seedVectorWithAcl("alice", List.of("user:alice:read"), "private-skill");

        mockMvc.perform(get("/api/v1/search/semantic")
                .param("q", "private"))
                .andExpect(status().isOk())
                // anonymous → CurrentUserProvider fallback (labUserId="lab-user", ["admin"], [])
                // → patterns = ["user:lab-user:read", "role:admin:read"]
                // → 不命中 acl_entries=["user:alice:read"] → empty result
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("AC-10: SemanticSearchResult JSON 合約 — id/name/description/author/category/score 欄位齊全")
    @Tag("AC-10")
    void responseShapeUnchanged() throws Exception {
        var skillA = seedVectorWithAcl("alice", List.of("user:alice:read"), "shape-test");

        mockMvc.perform(get("/api/v1/search/semantic")
                .param("q", "shape")
                .with(jwt()
                        .jwt(j -> j.subject("alice")
                                .claim("roles", List.of("user"))
                                .claim("groups", List.<String>of()))
                        .authorities(new SimpleGrantedAuthority("ROLE_user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + skillA + "')].name").exists())
                .andExpect(jsonPath("$[?(@.id=='" + skillA + "')].description").exists())
                .andExpect(jsonPath("$[?(@.id=='" + skillA + "')].author").exists())
                .andExpect(jsonPath("$[?(@.id=='" + skillA + "')].category").exists())
                .andExpect(jsonPath("$[?(@.id=='" + skillA + "')].score").exists());
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
                "S017 ACL E2E test fixture", owner, "Testing",
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
                                "category", "Testing"))
                        .build()));

        // 確認 acl_entries 已寫入
        var aclJson = jdbc.queryForObject(
                "SELECT acl_entries::text FROM vector_store WHERE id = ?::uuid",
                String.class, skillId);
        assertThat(aclJson).contains(aclEntries.get(0));

        return skillId;
    }

    private static float[] randomVector(int dim) {
        var v = new float[dim];
        var r = new Random(42);
        for (int i = 0; i < dim; i++) {
            v[i] = r.nextFloat() * 2 - 1;
        }
        return v;
    }
}
