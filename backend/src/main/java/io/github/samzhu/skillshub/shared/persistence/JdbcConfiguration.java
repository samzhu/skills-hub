package io.github.samzhu.skillshub.shared.persistence;

import java.util.List;
import java.util.Map;

import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.core.dialect.JdbcPostgresDialect;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring Data JDBC 配置 — 註冊 {@code Map<String, Object>} 與 PostgreSQL JSONB 欄位的雙向 converter。
 *
 * <p>用於 {@code DomainEvent.payload} / {@code DomainEvent.metadata} /
 * {@code SkillVersionReadModel.frontmatter} / {@code SkillVersionReadModel.riskAssessment} /
 * {@code DownloadEventReadModel.metadata} 等 Map 欄位的 JSONB 持久化。
 *
 * <p>採 Spring Boot auto-configured {@link ObjectMapper} bean — 與其他模組（OpenAPI
 * spec、API response 序列化、SARIF parsing）共用同一份 Jackson 設定，
 * 確保 nested 結構（{@code List<String>}、{@code Map<String, String>}）的序列化行為一致。
 *
 * <p>本配置 extends {@link AbstractJdbcConfiguration} 並 override
 * {@link #userConverters()} — Spring Data JDBC 標準擴充點，在
 * {@code @ConfigurationPropertiesScan} 啟動時自動發現。
 *
 * @see io.github.samzhu.skillshub.shared.events.DomainEvent
 * @see <a href="https://docs.spring.io/spring-data/relational/reference/4.0/jdbc/mapping.html">Spring Data JDBC Mapping</a>
 */
@Configuration
public class JdbcConfiguration extends AbstractJdbcConfiguration {

    private final ObjectMapper objectMapper;

    public JdbcConfiguration(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Override 預設 dialect 偵測 — {@link AbstractJdbcConfiguration#jdbcDialect}
     * 預設透過 {@link NamedParameterJdbcOperations} 跑 connection metadata query
     * 自動偵測 dialect。S132 起改顯式回 {@link JdbcPostgresDialect#INSTANCE}：
     * 我們 100% PostgreSQL（never MySQL/Oracle），不需 auto-detect；
     * 同時解決 Spring Boot AOT processing 階段（無真實 DB 連線）這條 path 會炸的問題。
     */
    @Bean
    @Override
    public JdbcDialect jdbcDialect(NamedParameterJdbcOperations operations) {
        return JdbcPostgresDialect.INSTANCE;
    }

    @Override
    protected List<?> userConverters() {
        return List.of(
                new MapToPGobjectConverter(objectMapper),
                new PGobjectToMapConverter(objectMapper),
                // S016: ACL 用 List<String> ↔ JSONB array converter（與 Map converter 並列；
                // Spring Data JDBC 依 generic 型別參數區分路由，不會衝突）
                new StringListJsonbConverter.Writing(objectMapper),
                new StringListJsonbConverter.Reading(objectMapper)
        );
    }

    /**
     * 寫入端 — {@code Map<String, Object>} → PostgreSQL JSONB（{@link PGobject} type=jsonb）。
     *
     * <p>null Map 序列化為 {@code "{}"}（避免 NOT NULL JSONB 欄位寫入失敗；
     * 設計選擇：缺值在資料庫端視為「空 metadata」而非 NULL，與 read model 預設值一致）。
     */
    @WritingConverter
    public static final class MapToPGobjectConverter implements Converter<Map<String, Object>, PGobject> {

        private final ObjectMapper mapper;

        public MapToPGobjectConverter(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public PGobject convert(Map<String, Object> source) {
            try {
                var pgo = new PGobject();
                pgo.setType("jsonb");
                pgo.setValue(source == null ? "{}" : mapper.writeValueAsString(source));
                return pgo;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize Map to JSONB", e);
            }
        }
    }

    /**
     * 讀取端 — PostgreSQL JSONB（{@link PGobject}）→ {@code Map<String, Object>}。
     *
     * <p>Jackson {@link TypeReference} 反序列化保留 nested 結構：
     * {@code List<String>} 還原為 {@link java.util.ArrayList}，
     * {@code Map<String, ?>} 還原為 {@link java.util.LinkedHashMap}；
     * primitive 還原為對應 wrapper（String / Integer / Long / Double / Boolean）。
     *
     * <p>null / 空字串 PGobject value 還原為 {@link Map#of()}（不可變空 Map），
     * 避免下游 NPE。
     */
    @ReadingConverter
    public static final class PGobjectToMapConverter implements Converter<PGobject, Map<String, Object>> {

        private static final TypeReference<Map<String, Object>> TYPE = new TypeReference<>() {};

        private final ObjectMapper mapper;

        public PGobjectToMapConverter(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public Map<String, Object> convert(PGobject source) {
            try {
                var v = source.getValue();
                return v == null || v.isBlank() ? Map.of() : mapper.readValue(v, TYPE);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to deserialize JSONB to Map", e);
            }
        }
    }
}
