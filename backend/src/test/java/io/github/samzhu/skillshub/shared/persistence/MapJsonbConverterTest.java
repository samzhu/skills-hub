package io.github.samzhu.skillshub.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.ObjectMapper;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

/**
 * S014-T1 POC + AC-2 回歸測試 — 驗證 {@code Map<String,Object>} 透過自訂
 * Jackson Converter 與 PostgreSQL JSONB 欄位雙向 round-trip 時 nested
 * 結構（String / List / Map）的型別保留行為。
 *
 * <p>本測試是 S014 規劃階段唯一被列為 Hypothesis 的設計決策的驗證點 —
 * 失敗即代表整套 Converter 設計需要 fallback 為 String wrapper。POC 失敗
 * 應 halt T2 之前的所有後續 task。
 *
 * <p>採 {@code @SpringBootTest} 載入完整 ApplicationContext — 因 Spring Modulith
 * 在 Spring Boot 4 AOT 處理階段需要 {@code ApplicationModulesRuntime} bean，
 * {@code @DataJdbcTest} slice 不包含此 bean 會 AOT 失敗。
 *
 * <p>{@code spring.flyway.enabled=false} 暫時關閉 Flyway 以隔離本測試 —
 * 我們只在 setup 動態建立 {@code jsonb_test} 臨時表，不需 V1 migration。
 *
 * @see JdbcConfiguration.MapToPGobjectConverter
 * @see JdbcConfiguration.PGobjectToMapConverter
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.flyway.enabled=false")
class MapJsonbConverterTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setupTable() {
        jdbc.getJdbcTemplate().execute("""
            CREATE TABLE IF NOT EXISTS jsonb_test (
                id VARCHAR(36) PRIMARY KEY,
                payload JSONB NOT NULL
            )
            """);
        jdbc.getJdbcTemplate().execute("DELETE FROM jsonb_test");
    }

    @Test
    @DisplayName("AC-2: Map<String,Object> nested round-trip — String / List / Map 三層型別保留")
    @Tag("AC-2")
    void roundTrip_preservesNestedTypes() {
        // Given — 含三層 nested 結構（top-level Map、nested List<String>、nested Map<String,String>）
        Map<String, Object> input = Map.of(
                "name", "test-skill",
                "version", "1.0.0",
                "tags", List.of("docker", "k8s"),
                "metadata", Map.of("author", "sam", "license", "MIT")
        );

        // When — 透過 Converter 寫入 JSONB
        var writer = new JdbcConfiguration.MapToPGobjectConverter(objectMapper);
        PGobject pgo = writer.convert(input);
        jdbc.update("INSERT INTO jsonb_test (id, payload) VALUES (:id, :p)",
                new MapSqlParameterSource()
                        .addValue("id", "t1")
                        .addValue("p", pgo));

        // 讀回 — 透過 Converter 反序列化
        var reader = new JdbcConfiguration.PGobjectToMapConverter(objectMapper);
        Map<String, Object> result = jdbc.queryForObject(
                "SELECT payload FROM jsonb_test WHERE id = :id",
                Map.of("id", "t1"),
                (rs, rowNum) -> reader.convert((PGobject) rs.getObject("payload")));

        // Then — 三層型別均正確還原
        assertThat(result).isNotNull();
        assertThat(result).containsEntry("name", "test-skill");
        assertThat(result).containsEntry("version", "1.0.0");

        assertThat(result.get("tags"))
                .as("nested List<String> 應還原為 List instance")
                .isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Object> tags = (List<Object>) result.get("tags");
        assertThat(tags).containsExactly("docker", "k8s");

        assertThat(result.get("metadata"))
                .as("nested Map<String,String> 應還原為 Map instance")
                .isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) result.get("metadata");
        assertThat(metadata)
                .containsEntry("author", "sam")
                .containsEntry("license", "MIT");
    }
}
