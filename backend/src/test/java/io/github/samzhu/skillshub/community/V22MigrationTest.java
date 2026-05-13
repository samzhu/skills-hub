package io.github.samzhu.skillshub.community;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S156c — V22 migration verification（Flyway clean migrate via Testcontainers）。
 *
 * <p>涵蓋 spec AC-1：{@code requests} 表無 status/claimer_id/fulfilled_skill_id；
 * {@code request_comments} 表存在 + 正確 FK + CASCADE 設定。
 *
 * <p>跑在 full Spring Boot context（Testcontainers 已驗 Flyway 自動 migrate；本 test 只
 * 用 JdbcTemplate 走 information_schema 驗 schema state）。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class V22MigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: V22 後 requests 表無 status / claimer_id / fulfilled_skill_id")
    void droppedColumns_absent() {
        var columns = listColumns("requests");

        assertThat(columns).doesNotContain("status", "claimer_id", "fulfilled_skill_id");
        // 應保留的 columns
        assertThat(columns).contains("id", "title", "description", "requester_id", "vote_count",
                "created_at", "updated_at", "version");
    }

    @Test
    @Tag("AC-1")
    @DisplayName("AC-1: V22 後 idx_requests_status 索引已不存在")
    void statusIndex_dropped() {
        var indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'requests'",
                String.class);
        assertThat(indexes).doesNotContain("idx_requests_status");
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: V22 後 request_comments 表存在 + columns 對齊 spec")
    void requestCommentsTable_exists() {
        var columns = listColumns("request_comments");

        // version column 為 Spring Data JDBC @Version optimistic lock + INSERT/UPDATE 區分器
        assertThat(columns).containsExactlyInAnyOrder(
                "id", "request_id", "author_id", "content", "created_at", "deleted_at", "version");
    }

    @Test
    @Tag("AC-7")
    @DisplayName("AC-7 cascade: request_comments.request_id FK 設定 ON DELETE CASCADE")
    void requestCommentsForeignKey_cascade() {
        // information_schema.referential_constraints 查 delete_rule
        var rules = jdbc.queryForList("""
                SELECT rc.delete_rule
                FROM information_schema.referential_constraints rc
                JOIN information_schema.table_constraints tc
                  ON tc.constraint_name = rc.constraint_name
                WHERE tc.table_name = 'request_comments'
                """, String.class);

        assertThat(rules).contains("CASCADE");
    }

    @Test
    @Tag("AC-5")
    @DisplayName("AC-5: idx_request_comments_request 索引存在 (request_id, created_at)")
    void requestCommentsIndex_exists() {
        var indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'request_comments'",
                String.class);
        assertThat(indexes).contains("idx_request_comments_request");
    }

    @SuppressWarnings("unchecked")
    private List<String> listColumns(String tableName) {
        // Postgres information_schema.columns 走 lower-case；jdbc bind 走 ?；返回 column_name 直接 cast
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?", tableName);
        return rows.stream().map(r -> (String) r.get("column_name")).toList();
    }
}
