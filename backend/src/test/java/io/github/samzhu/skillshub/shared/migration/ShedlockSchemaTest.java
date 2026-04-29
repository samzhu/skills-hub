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
 * S023-T01 — 驗證 V5 migration 建立 ShedLock 表。
 *
 * <p>對應 S023 spec §3 AC-2。採 {@code @SpringBootTest} 觸發 Flyway 自動執行 V1~V5，
 * 再以 {@code information_schema.columns} query 驗證 shedlock 表 4 欄位。
 *
 * <p>本 test 不直接運行 ShedLock lock 操作（屬 T05 範圍）；僅驗 schema。
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ShedlockSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("AC-2: V5 建立 shedlock 表（4 欄位）")
    @Tag("AC-2")
    void v5_createsShedlockTable() {
        var columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns " +
                "WHERE table_name = 'shedlock' ORDER BY ordinal_position",
                String.class);

        assertThat(columns).containsExactly("name", "lock_until", "locked_at", "locked_by");
    }

    // S023-T07：原本 `v5_shedlockStartsEmpty` 斷言「shedlock COUNT(*) = 0」，
    // 但 IncompleteEventRepublishTask @Scheduled 在 context 啟動秒級內就觸發，
    // ShedLock INSERT 一筆 row（name='republish-incomplete-events'）→ assertion 不成立。
    // schema 正確性由其他兩個 test（columns + PK）已覆蓋；row count 是 runtime state 不該硬斷言。

    @Test
    @DisplayName("AC-2: V5 shedlock.name 為 PRIMARY KEY")
    @Tag("AC-2")
    void v5_shedlockNameIsPrimaryKey() {
        var pkColumn = jdbc.queryForObject(
                "SELECT a.attname FROM pg_index i " +
                "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) " +
                "WHERE i.indrelid = 'shedlock'::regclass AND i.indisprimary",
                String.class);

        assertThat(pkColumn).isEqualTo("name");
    }
}
