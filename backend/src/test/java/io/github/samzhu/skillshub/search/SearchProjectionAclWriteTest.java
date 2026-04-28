package io.github.samzhu.skillshub.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.samzhu.skillshub.TestcontainersConfiguration;
import io.github.samzhu.skillshub.skill.domain.SkillCreatedEvent;

/**
 * S016 T6 — SearchProjection 處理 SkillCreatedEvent 時把 owner 衍生 acl_entries 寫入 vector_store。
 *
 * <p>對應 spec §2.3 + §4.16：{@code aclEntries = List.of("user:" + owner + ":read")}
 * 從 owner 衍生（與 V2 backfill vector_store 邏輯一致）。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SearchProjectionAclWriteTest {

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        when(embeddingModel.embed(any(Document.class))).thenAnswer(inv -> randomVector(768));
        when(embeddingModel.embed(anyString())).thenAnswer(inv -> randomVector(768));
        when(embeddingModel.embed(any(List.class), any(), any())).thenAnswer(inv -> {
            List<?> docs = inv.getArgument(0);
            return docs.stream().map(d -> randomVector(768)).toList();
        });
    }

    @Test
    @DisplayName("AC-1 vector_store: SearchProjection 處理 SkillCreatedEvent → vector_store.acl_entries 衍生自 author")
    @Tag("AC-1")
    void searchProjectionWritesAclEntriesFromAuthor() {
        var skillId = UUID.randomUUID().toString();

        publisher.publishEvent(new SkillCreatedEvent(
                skillId, "search-acl-" + skillId.substring(0, 8),
                "search projection ACL write fixture", "alice", "Testing"));

        var aclJson = jdbc.queryForObject(
                "SELECT acl_entries::text FROM vector_store WHERE id = ?::uuid",
                String.class, skillId);
        assertThat(aclJson).contains("user:alice:read");
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
