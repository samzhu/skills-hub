package io.github.samzhu.skillshub.shared.persistence;

import java.util.List;

import org.postgresql.util.PGobject;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code List<String>} ↔ PostgreSQL JSONB array 雙向 converter（S016 ACL 用）。
 *
 * <p>對應 {@code SkillReadModel.aclEntries} / {@code skills.acl_entries}
 * 等 flat string array JSONB 欄位 — schema 形如 {@code ["user:alice:read",
 * "role:admin:read"]}，搭配 GIN(jsonb_ops) index 的 {@code ?|} operator 實現
 * 任意 principal 的 row-level 過濾。
 *
 * <p>fail-secure 設計：
 * <ul>
 *   <li>{@code Writing.convert(null)} → {@code "[]"}（不寫 {@code "null"} literal；
 *       對齊 NOT NULL DEFAULT '[]' schema 約束）
 *   <li>{@code Reading.convert(空字串)} → {@link List#of()}（不可變空 list；下游不會 NPE）
 * </ul>
 *
 * <p>採 {@link tools.jackson.databind.ObjectMapper}（Jackson 3） — 與
 * {@link JdbcConfiguration} 既有 Map converter 共用同一份 Spring Boot
 * auto-configured ObjectMapper bean，避免 nested 型別行為飄移。
 *
 * @see JdbcConfiguration#userConverters()
 */
public class StringListJsonbConverter {

    private StringListJsonbConverter() {
    }

    @WritingConverter
    public static final class Writing implements Converter<List<String>, PGobject> {

        private final ObjectMapper mapper;

        public Writing(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public PGobject convert(List<String> source) {
            try {
                var pgo = new PGobject();
                pgo.setType("jsonb");
                pgo.setValue(source == null ? "[]" : mapper.writeValueAsString(source));
                return pgo;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize List<String> to JSONB", e);
            }
        }
    }

    @ReadingConverter
    public static final class Reading implements Converter<PGobject, List<String>> {

        private static final TypeReference<List<String>> TYPE = new TypeReference<>() {
        };

        private final ObjectMapper mapper;

        public Reading(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public List<String> convert(PGobject source) {
            try {
                var v = source.getValue();
                return (v == null || v.isBlank()) ? List.of() : mapper.readValue(v, TYPE);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize JSONB to List<String>", e);
            }
        }
    }
}
