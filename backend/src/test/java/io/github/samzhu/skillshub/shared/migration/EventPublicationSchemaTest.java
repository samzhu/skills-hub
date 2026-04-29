package io.github.samzhu.skillshub.shared.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S023-T01 — 驗證 V4 migration 建立 Spring Modulith Event Publication Registry schema。
 *
 * <p>對應 S023 spec §3 AC-1 + AC-3。採 {@code @SpringBootTest} 觸發 Flyway
 * 自動執行 V1~V4，再以 {@code information_schema} query 驗證 schema 結果。
 *
 * <p>無需 stub event publish — 僅驗 DDL 套用結果，與 listener 行為解耦。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EventPublicationSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("AC-1: V4 建立 event_publication 表（V2 schema 9 欄位）")
    @Tag("AC-1")
    void v4_createsEventPublicationTable() {
        // information_schema.columns query — 反映 Spring Modulith 2.0.6 V2 schema
        var columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_name = 'event_publication' ORDER BY ordinal_position",
                String.class);

        assertThat(columns).containsExactly(
                "id",
                "listener_id",
                "event_type",
                "serialized_event",
                "publication_date",
                "completion_date",
                "status",
                "completion_attempts",
                "last_resubmission_date");
    }

    @Test
    @DisplayName("AC-1: V4 建立 event_publication_archive 表（同 schema）")
    @Tag("AC-1")
    void v4_createsArchiveTable() {
        var count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_name = 'event_publication_archive'",
                Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-1: V4 建立 hash + btree indexes")
    @Tag("AC-1")
    void v4_createsExpectedIndexes() {
        var indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes " +
                "WHERE tablename = 'event_publication'",
                String.class);

        assertThat(indexes).contains(
                "event_publication_pkey",
                "event_publication_serialized_event_hash_idx",
                "event_publication_by_completion_date_idx");
    }

    @Test
    @DisplayName("AC-3: V4 為 download_events 加 event_id NOT NULL 欄位")
    @Tag("AC-3")
    void v4_addsEventIdColumnToDownloadEvents() {
        var columnInfo = jdbc.queryForMap(
                "SELECT data_type, is_nullable FROM information_schema.columns " +
                "WHERE table_name = 'download_events' AND column_name = 'event_id'");

        assertThat(columnInfo.get("data_type")).isEqualTo("character varying");
        assertThat(columnInfo.get("is_nullable")).isEqualTo("NO");
    }

    @Test
    @DisplayName("AC-3: V4 建立 download_events.event_id UNIQUE index")
    @Tag("AC-3")
    void v4_createsDownloadEventsEventIdUniqueIndex() {
        var unique = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes " +
                "WHERE indexname = 'uq_download_events_event_id'",
                String.class);

        assertThat(unique).contains("UNIQUE");
        assertThat(unique).contains("event_id");
    }
}
