package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.Mockito;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S017 T1 — Builder.aclPatterns setter + SIMILARITY_SEARCH_SQL_ACL constant +
 * buildPgArrayLiteral helper（pure unit；無 Spring Context）。
 *
 * <p>對應 spec §3 AC-1：Builder API + SQL constant 字面 + helper edge case。
 * Integration（doSimilaritySearch 分流）由 T2 補上。
 */
class SkillshubPgVectorStoreAclSearchTest {

    @Test
    @DisplayName("AC-1: Builder.aclPatterns(...) 接受 List<String> + 回 self for chaining")
    @Tag("AC-1")
    void builderAclPatternsChain() {
        var jdbc = Mockito.mock(JdbcTemplate.class);
        var em = Mockito.mock(EmbeddingModel.class);

        var builder = SkillshubPgVectorStore.builder(jdbc, em)
                .aclPatterns(List.of("user:alice:read"));

        assertThat(builder).isNotNull();
        // 確認鏈式回 self（不是 null/不同物件）
        assertThat(builder.aclPatterns(List.of("user:bob:read"))).isSameAs(builder);
    }

    @Test
    @DisplayName("AC-1: 不傳 aclPatterns 時實例 aclPatterns field = null（既有行為）")
    @Tag("AC-1")
    void builderAclPatternsDefaultsToNull() {
        var jdbc = Mockito.mock(JdbcTemplate.class);
        var em = Mockito.mock(EmbeddingModel.class);

        var store = SkillshubPgVectorStore.builder(jdbc, em).build();

        assertThat(store.aclPatternsForTest()).isNull();
    }

    @Test
    @DisplayName("AC-1: aclPatterns(empty list) 與 aclPatterns(non-empty) 都正確存於實例（語意分流由 T2 處理）")
    @Tag("AC-1")
    void builderAclPatternsRetained() {
        var jdbc = Mockito.mock(JdbcTemplate.class);
        var em = Mockito.mock(EmbeddingModel.class);

        var emptyStore = SkillshubPgVectorStore.builder(jdbc, em)
                .aclPatterns(List.of()).build();
        assertThat(emptyStore.aclPatternsForTest()).isEmpty();

        var multiStore = SkillshubPgVectorStore.builder(jdbc, em)
                .aclPatterns(List.of("user:alice:read", "group:eng:read")).build();
        assertThat(multiStore.aclPatternsForTest())
                .containsExactly("user:alice:read", "group:eng:read");
    }

    @Test
    @DisplayName("AC-1: SIMILARITY_SEARCH_SQL_ACL 字面包含 ??| escape + ORDER BY distance + LIMIT placeholder")
    @Tag("AC-1")
    void aclSqlConstantHasRequiredKeywords() {
        var sql = SkillshubPgVectorStore.SIMILARITY_SEARCH_SQL_ACL;

        assertThat(sql).contains("??|");
        assertThat(sql).contains("acl_entries");
        assertThat(sql).contains("?::text[]");
        assertThat(sql).contains("ORDER BY distance");
        assertThat(sql).contains("LIMIT ?");
        assertThat(sql).contains("embedding <=> ?");
    }

    @Test
    @DisplayName("AC-1: OVERSAMPLE_FACTOR = 5（per spec §2.1 #3）")
    @Tag("AC-1")
    void oversampleFactorIsFive() {
        assertThat(SkillshubPgVectorStore.OVERSAMPLE_FACTOR).isEqualTo(5);
    }

    @Test
    @DisplayName("AC-1: buildPgArrayLiteral — empty list → \"{}\"")
    @Tag("AC-1")
    void buildPgArrayLiteral_empty() {
        assertThat(SkillshubPgVectorStore.buildPgArrayLiteral(List.of())).isEqualTo("{}");
    }

    @Test
    @DisplayName("AC-1: buildPgArrayLiteral — 單元素 → \"{user:alice:read}\"")
    @Tag("AC-1")
    void buildPgArrayLiteral_single() {
        assertThat(SkillshubPgVectorStore.buildPgArrayLiteral(List.of("user:alice:read")))
                .isEqualTo("{user:alice:read}");
    }

    @Test
    @DisplayName("AC-1: buildPgArrayLiteral — 多元素 comma-separated 無空白")
    @Tag("AC-1")
    void buildPgArrayLiteral_multiple() {
        assertThat(SkillshubPgVectorStore.buildPgArrayLiteral(
                List.of("user:alice:read", "role:admin:read", "group:engineering:read")))
                .isEqualTo("{user:alice:read,role:admin:read,group:engineering:read}");
    }

    /**
     * S017 T2 — Testcontainer integration：doSimilaritySearch 分流 + oversample slice
     * 真 PostgreSQL `??|` 行為驗證。`@Nested` 與 outer pure-unit cases 共存於同 class。
     */
    @Nested
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    class DoSimilaritySearchAclRoutingIntegration {

        @Autowired private JdbcTemplate jdbc;
        @Autowired private SkillRepository skillRepo;
        @MockitoBean private EmbeddingModel embeddingModel;

        @BeforeEach
        void setUp() {
            // 隔離：跨 test class 共享 Testcontainer，其他 test 留 vector_store row 會 poison topK ranking。
            // TRUNCATE skills CASCADE 自動清 vector_store（FK ON DELETE CASCADE）。
            jdbc.update("TRUNCATE TABLE skills RESTART IDENTITY CASCADE");

            // mock embed 回固定 768-dim 隨機向量；T2 不驗 ranking 正確性，只驗 ACL filter 正確
            when(embeddingModel.embed(any(Document.class))).thenAnswer(inv -> randomVector(768));
            when(embeddingModel.embed(anyString())).thenAnswer(inv -> randomVector(768));
            when(embeddingModel.embed(any(List.class), any(), any())).thenAnswer(inv -> {
                List<?> docs = inv.getArgument(0);
                return docs.stream().map(d -> randomVector(768)).toList();
            });
        }

        @Test
        @DisplayName("AC-2: aclPatterns=[user:alice:read] → 只回 alice owned row（不含 bob/group）")
        @Tag("AC-2")
        void aclFilter_onlyMatchingRowsReturned() {
            var rowA = seedVector("alice", List.of("user:alice:read"));
            var rowB = seedVector("bob", List.of("user:bob:read"));
            var rowC = seedVector("eng", List.of("group:engineering:read"));

            var docs = SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                    .aclPatterns(List.of("user:alice:read"))
                    .build()
                    .similaritySearch(SearchRequest.builder()
                            .query("test").topK(10).similarityThreshold(0.0).build());

            assertThat(docs).extracting(Document::getId)
                    .contains(rowA)
                    .doesNotContain(rowB, rowC);
        }

        @Test
        @DisplayName("AC-3: aclPatterns=[] → empty result（fail-secure；不 fallback）")
        @Tag("AC-3")
        void emptyAclPatterns_failSecure() {
            seedVector("alice", List.of("user:alice:read"));

            var docs = SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                    .aclPatterns(List.of())
                    .build()
                    .similaritySearch(SearchRequest.builder()
                            .query("test").topK(10).similarityThreshold(0.0).build());

            assertThat(docs).isEmpty();
        }

        @Test
        @DisplayName("AC-4: aclPatterns=null → 走既有 SIMILARITY_SEARCH_SQL（回所有命中 row）")
        @Tag("AC-4")
        void nullAclPatterns_legacyPath() {
            var rowA = seedVector("alice", List.of("user:alice:read"));
            var rowB = seedVector("bob", List.of("user:bob:read"));

            var docs = SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                    // 故意不 .aclPatterns(...)
                    .build()
                    .similaritySearch(SearchRequest.builder()
                            .query("test").topK(10).similarityThreshold(0.0).build());

            assertThat(docs).extracting(Document::getId).contains(rowA, rowB);
        }

        @Test
        @DisplayName("AC-5: group: 命名空間命中 — carol via group:engineering:read")
        @Tag("AC-5")
        void groupNamespace_matches() {
            var rowC = seedVector("eng", List.of("user:alice:read", "group:engineering:read"));

            var carolPatterns = List.of(
                    "user:carol:read", "role:user:read", "group:engineering:read");

            var docs = SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                    .aclPatterns(carolPatterns)
                    .build()
                    .similaritySearch(SearchRequest.builder()
                            .query("test").topK(10).similarityThreshold(0.0).build());

            assertThat(docs).extracting(Document::getId).contains(rowC);
        }

        @Test
        @DisplayName("AC-6: oversample 5x → SQL LIMIT topK*5；Java slice 至 topK")
        @Tag("AC-6")
        void oversampleLimit_javaSlice() {
            // 種 12 row matching alice + 1 row 不 match → topK=2 應 slice 至 2 row
            for (int i = 0; i < 12; i++) {
                seedVector("alice-" + i, List.of("user:alice:read"));
            }
            seedVector("bob", List.of("user:bob:read"));

            var docs = SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                    .aclPatterns(List.of("user:alice:read"))
                    .build()
                    .similaritySearch(SearchRequest.builder()
                            .query("test").topK(2).similarityThreshold(0.0).build());

            // topK=2，oversample=10（topK*5）；SQL fetched 10 rows，Java slice 至 2
            assertThat(docs).hasSize(2);
            // 全部都應該是 alice owned（不含 bob — ACL filter 在 SQL 端已擋）
            for (var doc : docs) {
                var aclJson = jdbc.queryForObject(
                        "SELECT acl_entries::text FROM vector_store WHERE id = ?::uuid",
                        String.class, doc.getId());
                assertThat(aclJson).contains("user:alice:read");
            }
        }

        private String seedVector(String owner, List<String> aclEntries) {
            // FK 前置 — vector_store.skill_id REFERENCES skills(id)
            var skillId = UUID.randomUUID().toString();
            var now = Instant.now();
            skillRepo.save(Skill.fromRow(
                    skillId, "vec-acl-search-" + skillId.substring(0, 8),
                    "T2 ACL search fixture", owner, "Testing",
                    null, null, "DRAFT", 0L, now, now, List.of(), null));

            // 寫 vector_store row 帶 acl_entries（用 SkillshubPgVectorStore writer 路徑）
            SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                    .owner(owner)
                    .skillId(skillId)
                    .aclEntries(aclEntries)
                    .build()
                    .add(List.of(Document.builder()
                            .id(skillId)
                            .text("body")
                            .metadata(Map.of("skillId", skillId))
                            .build()));

            return skillId;
        }
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
