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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.query.SkillReadModel;
import io.github.samzhu.skillshub.skill.query.SkillReadModelRepository;

/**
 * AC-10 + AC-13 整合驗證 — {@link SkillshubPgVectorStore} 一次 6-欄 INSERT
 * （id, content, metadata, embedding, owner, skill_id）在真實 PostgreSQL（Testcontainers
 * pgvector/pgvector:pg16）上正確寫入。
 *
 * <p>**T8 重寫**：
 * <ul>
 *   <li>不再透過 {@link SearchProjection}（兩步驟驗證移到 SearchProjectionTest）</li>
 *   <li>直接 {@link SkillshubPgVectorStore#builder} 建構並呼叫 add；驗 SELECT 6 欄都對</li>
 *   <li>EmbeddingModel mock 回固定 768-dim 隨機向量（避免依賴外部 GenAI API）</li>
 *   <li>schema introspection test 保留（驗 V1 migration 的 owner/skill_id 欄位 + HNSW index）</li>
 * </ul>
 *
 * <p>FK 前置：vector_store.skill_id REFERENCES skills(id) ON DELETE CASCADE。
 *
 * @see SkillshubPgVectorStore#doAdd
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PgVectorStoreOwnerWriteTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private SkillReadModelRepository skillRepo;

    @MockitoBean private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        // mock embed 回固定 768-dim 隨機向量（任何 input 都同一向量）
        when(embeddingModel.embed(any(Document.class))).thenAnswer(inv -> randomVector(768));
        when(embeddingModel.embed(anyString())).thenAnswer(inv -> randomVector(768));
        when(embeddingModel.embed(any(List.class), any(), any())).thenAnswer(inv -> {
            List<?> docs = inv.getArgument(0);
            return docs.stream().map(d -> randomVector(768)).toList();
        });
    }

    @Test
    @DisplayName("AC-10/AC-13: SkillshubPgVectorStore.add() 一次 6-欄 INSERT 寫入完整 row")
    @Tag("AC-10")
    @Tag("AC-13")
    void singleInsertWritesAll6Columns() {
        // FK 前置
        var skillId = UUID.randomUUID().toString();
        var now = Instant.now();
        skillRepo.save(new SkillReadModel(
                skillId, "test-skill", "tests 6-col single INSERT", "qa-author", "Testing",
                null, null, "DRAFT", 0L, now, now));

        // Act：用 builder 直接寫入
        SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                .owner("test-owner-42")
                .skillId(skillId)
                .build()
                .add(List.of(Document.builder()
                        .id(skillId)
                        .text("test-skill content body")
                        .metadata(Map.of("skillId", skillId, "name", "test-skill"))
                        .build()));

        // Assert：6 欄全寫入（content, metadata, embedding, owner, skill_id 非 NULL；id 為 UUID）
        var row = jdbc.queryForMap(
                "SELECT id, content, metadata, "
                        + "embedding IS NOT NULL AS has_embedding, "
                        + "owner, skill_id FROM vector_store WHERE id = ?::uuid", skillId);
        assertThat(row.get("id")).asString().isEqualTo(skillId);
        assertThat(row.get("content")).isEqualTo("test-skill content body");
        assertThat(row.get("metadata")).asString().contains("\"skillId\"").contains("\"name\"");
        assertThat(row.get("has_embedding")).isEqualTo(true);
        assertThat(row.get("owner")).isEqualTo("test-owner-42");
        assertThat(row.get("skill_id")).isEqualTo(skillId);
    }

    @Test
    @DisplayName("AC-10: ON CONFLICT DO UPDATE 用 COALESCE 保留既有 owner（防後續 ingest 不帶 context 時被 NULL 蓋掉）")
    @Tag("AC-10")
    void onConflictPreservesExistingOwnerViaCoalesce() {
        var skillId = UUID.randomUUID().toString();
        var now = Instant.now();
        skillRepo.save(new SkillReadModel(
                skillId, "preserve-skill", "tests COALESCE preservation", "qa-author", "Testing",
                null, null, "DRAFT", 0L, now, now));

        // 第 1 次寫入：帶 owner
        SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                .owner("first-owner")
                .skillId(skillId)
                .build()
                .add(List.of(Document.builder().id(skillId).text("v1").metadata(Map.of()).build()));

        // 第 2 次寫入：不帶 owner（builder 略過 .owner(...)）— 模擬 batch sync 場景
        SkillshubPgVectorStore.builder(jdbc, embeddingModel)
                .skillId(skillId)
                .build()
                .add(List.of(Document.builder().id(skillId).text("v2").metadata(Map.of()).build()));

        // owner 仍應保留 first-owner（COALESCE 防護）；content 已更新為 v2
        var row = jdbc.queryForMap(
                "SELECT content, owner FROM vector_store WHERE id = ?::uuid", skillId);
        assertThat(row.get("content")).isEqualTo("v2");
        assertThat(row.get("owner")).isEqualTo("first-owner");
    }

    @Test
    @DisplayName("AC-10: vector_store schema 含 owner / skill_id 欄位 + HNSW index（V1 migration 驗證）")
    @Tag("AC-10")
    void vectorStoreSchemaHasOwnerAndSkillIdColumns() {
        // 直接走 SQL information_schema 驗證 V1 migration 在 Testcontainers 上跑出對的 schema
        var ownerColumnNullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'vector_store' AND column_name = 'owner'",
                String.class);
        assertThat(ownerColumnNullable).isEqualTo("YES");

        var skillIdColumnNullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'vector_store' AND column_name = 'skill_id'",
                String.class);
        assertThat(skillIdColumnNullable).isEqualTo("YES");

        // HNSW vector cosine index — 對應 V1 schema vs_emb_idx
        var hnswIndexExists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND tablename = 'vector_store' "
                        + "AND indexname = 'vs_emb_idx' AND indexdef LIKE '%hnsw%'",
                Integer.class);
        assertThat(hnswIndexExists).isEqualTo(1);
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
