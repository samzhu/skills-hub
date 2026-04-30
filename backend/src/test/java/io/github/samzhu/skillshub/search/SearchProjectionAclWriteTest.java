package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
import io.github.samzhu.skillshub.shared.events.TestEventTxHelper;
import io.github.samzhu.skillshub.shared.security.CurrentUserProvider;
import io.github.samzhu.skillshub.skill.domain.Skill;
import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;
import io.github.samzhu.skillshub.skill.domain.SkillRepository;

/**
 * S016 T6 — SearchProjection 處理 SkillCreatedEvent 時把 owner 衍生 acl_entries 寫入 vector_store。
 *
 * <p>對應 spec §2.3 + §4.16：{@code aclEntries = List.of("user:" + owner + ":read")}
 * 從 owner 衍生（與 V2 backfill vector_store 邏輯一致）。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SearchProjectionAclWriteTest {

    // S023-T07: 改用 TestEventTxHelper 以便 @ApplicationModuleListener 在 publisher TX commit 後觸發
    @Autowired private TestEventTxHelper txHelper;
    @Autowired private JdbcTemplate jdbc;
    // S024 T05B: vector_store.skill_id FK 前置 — SearchProjection 寫入時需要 skills row 存在
    @Autowired private SkillRepository skillRepo;

    @MockitoBean private EmbeddingModel embeddingModel;
    // S023-T07: SecurityContextHolder 在 async thread 為空（test 無 JWT），
    // 必須 mock CurrentUserProvider 否則 listener 拿到 null owner 拋例外
    @MockitoBean private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void setUp() {
        when(embeddingModel.embed(any(Document.class))).thenAnswer(inv -> randomVector(768));
        when(embeddingModel.embed(anyString())).thenAnswer(inv -> randomVector(768));
        when(embeddingModel.embed(any(List.class), any(), any())).thenAnswer(inv -> {
            List<?> docs = inv.getArgument(0);
            return docs.stream().map(d -> randomVector(768)).toList();
        });
        when(currentUserProvider.userId()).thenReturn("alice");
    }

    @Test
    @DisplayName("AC-1 vector_store: SearchProjection 處理 SkillCreatedEvent → vector_store.acl_entries 衍生自 author")
    @Tag("AC-1")
    void searchProjectionWritesAclEntriesFromAuthor() {
        var skillId = UUID.randomUUID().toString();
        var name = "search-acl-" + skillId.substring(0, 8);
        // FK 前置：vector_store.skill_id REFERENCES skills.id；S024 T05B 後 SearchProjection
        // 不再依賴 SkillProjection 預先寫 skills row（已刪除），test 自行 seed
        var now = Instant.now();
        skillRepo.save(Skill.fromRow(skillId, name, "search projection ACL write fixture",
                "alice", "Testing", null, null, "DRAFT", 0L, now, now, List.of(), null));

        txHelper.publishInTx(new SkillCreatedEvent(
                skillId, name,
                "search projection ACL write fixture", "alice", "Testing"));

        // S023-T07: SearchProjection.onSkillCreated 改 @ApplicationModuleListener async；用 Awaitility 等
        // S024 T05B：用 queryForList 避免 EmptyResultDataAccessException 中斷 polling
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var rows = jdbc.queryForList(
                    "SELECT acl_entries::text AS acl FROM vector_store WHERE id = ?::uuid",
                    skillId);
            assertThat(rows).hasSize(1);
            assertThat((String) rows.get(0).get("acl")).contains("user:alice:read");
        });
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
