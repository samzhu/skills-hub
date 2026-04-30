package io.github.samzhu.skillshub.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.ObjectMapper;

/**
 * S016-T1 — {@code List<String>} ↔ PostgreSQL JSONB array round-trip 驗證。
 *
 * <p>對應 spec §4.18 — {@code StringListJsonbConverter} 的 Writing / Reading
 * pair 必須與既有 {@code JdbcConfiguration.MapToPGobjectConverter} 行為一致：
 * null / 空字串還原為 {@link List#of()}（不可變空 list；fail-secure 對齊 ACL 預設拒絕）。
 *
 * <p>S025b T02 — extends {@link RepositorySliceTestBase}：{@code @DataJdbcTest} slice
 * 共用 cache key；本 test 純 converter 邏輯（無 DB I/O 也無 service dep），slice
 * Flyway V1-V6 啟用後仍正常（converter test 不依 schema）。
 */
class StringListJsonbConverterTest extends RepositorySliceTestBase {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("AC-1: List<String> → JSONB → List<String> round-trip 保留順序與內容")
    @Tag("AC-1")
    void roundTrip_preservesOrderAndValues() {
        var input = List.of("user:alice:read", "role:admin:read", "group:eng:write");

        var writer = new StringListJsonbConverter.Writing(objectMapper);
        PGobject pgo = writer.convert(input);

        assertThat(pgo.getType()).isEqualTo("jsonb");
        assertThat(pgo.getValue())
                .as("Jackson 應序列化為 JSON array 字串")
                .isEqualTo("[\"user:alice:read\",\"role:admin:read\",\"group:eng:write\"]");

        var reader = new StringListJsonbConverter.Reading(objectMapper);
        List<String> result = reader.convert(pgo);

        assertThat(result).containsExactly("user:alice:read", "role:admin:read", "group:eng:write");
    }

    @Test
    @DisplayName("AC-1: 空 List 序列化為 '[]' 並可正確還原")
    @Tag("AC-1")
    void emptyList_roundTrip() {
        var writer = new StringListJsonbConverter.Writing(objectMapper);
        PGobject pgo = writer.convert(List.of());

        assertThat(pgo.getValue()).isEqualTo("[]");

        var reader = new StringListJsonbConverter.Reading(objectMapper);
        assertThat(reader.convert(pgo))
                .as("空陣列應還原為 empty list 而非 null")
                .isEmpty();
    }

    @Test
    @DisplayName("AC-1: null source 寫成 '[]'（fail-secure 對齊 NOT NULL DEFAULT '[]'）")
    @Tag("AC-1")
    void nullSource_writesEmptyArray() {
        var writer = new StringListJsonbConverter.Writing(objectMapper);
        PGobject pgo = writer.convert(null);

        assertThat(pgo.getValue())
                .as("null 應 fail-secure 落地為 '[]' 而非 'null' literal")
                .isEqualTo("[]");
    }

    @Test
    @DisplayName("AC-1: 空 PGobject value 還原為 List.of()（不 NPE）")
    @Tag("AC-1")
    void blankValue_readsAsEmptyList() throws java.sql.SQLException {
        var blank = new PGobject();
        blank.setType("jsonb");
        blank.setValue("");

        var reader = new StringListJsonbConverter.Reading(objectMapper);
        assertThat(reader.convert(blank)).isEmpty();
    }
}
