package io.github.samzhu.skillshub.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import io.github.samzhu.skillshub.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Tag("S186")
class SkillEmbeddingMigrationTest {

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @DisplayName("AC-S186-1: V27 creates skills embedding columns and drops vector_store")
    @Tag("AC-S186-1")
    void v27CreatesSkillEmbeddingColumnsAndDropsVectorStore() {
        var columns = jdbc.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'skills'
                  AND column_name IN (
                    'embedding_content',
                    'embedding',
                    'embedding_model',
                    'embedding_updated_at'
                  )
                """, Map.of(), String.class);

        assertThat(columns).containsExactlyInAnyOrder(
                "embedding_content",
                "embedding",
                "embedding_model",
                "embedding_updated_at");
        assertThat(columnType("embedding")).isEqualTo("vector(768)");
        assertThat(columnType("embedding_model")).isEqualTo("character varying(64)");
        assertThat(indexCount("idx_skills_embedding_hnsw")).isEqualTo(1);
        assertThat(toRegclass("public.vector_store")).isNull();
    }

    private String columnType(String column) {
        return jdbc.queryForObject("""
                SELECT format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                  AND c.relname = 'skills'
                  AND a.attname = :column
                """, Map.of("column", column), String.class);
    }

    private Integer indexCount(String indexName) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'skills'
                  AND indexname = :indexName
                """, Map.of("indexName", indexName), Integer.class);
    }

    private String toRegclass(String name) {
        return jdbc.queryForObject("SELECT to_regclass(:name)", Map.of("name", name), String.class);
    }
}
