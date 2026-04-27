package io.github.samzhu.skillshub.search;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.ai.vectorstore.AbstractVectorStoreBuilder;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.observation.AbstractObservationVectorStore;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.StatementCreatorUtils;

import com.pgvector.PGvector;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Skills Hub 自訂 PgVectorStore — 寫入 vector_store 表 6 欄
 * （id, content, metadata, embedding, owner, skill_id），而非官方
 * {@code org.springframework.ai.vectorstore.pgvector.PgVectorStore} 的 4 欄。
 *
 * <p><strong>Per-request instantiation 模式</strong>（不註冊為 Spring Bean）：
 * 每次寫入由呼叫端用 builder 建構新 instance，操作完即可被 GC，無 thread-safety 顧慮、
 * 無 singleton state leak。owner / skillId 鎖在 builder 建構出來的 instance 裡，避免
 * 跨請求污染。
 *
 * <pre>{@code
 * // 寫入：附 owner / skillId
 * SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
 *     .owner(currentUserProvider.userId())
 *     .skillId(event.aggregateId())
 *     .build()
 *     .add(List.of(doc));
 *
 * // 查詢：不需 owner / skillId
 * var docs = SkillshubPgVectorStore.builder(jdbcTemplate, embeddingModel)
 *     .build()
 *     .similaritySearch(SearchRequest.builder().query(q).topK(10).build());
 * }</pre>
 *
 * <p>繼承 {@link AbstractObservationVectorStore} — Micrometer observation 由父類自動 wrap
 * （與官方 PgVectorStore 行為等同）。
 *
 * <p><strong>SQL 設計重點</strong>：
 * <ul>
 *   <li>{@code INSERT_SQL} 走 {@code ON CONFLICT (id) DO UPDATE} 上採 {@code COALESCE}
 *       保留既有非 null 的 owner / skill_id — 防禦後續 ingest 不帶 context（如 batch sync）
 *       時被 null 蓋掉。</li>
 *   <li>id 用 {@code ?::uuid} cast — vector_store.id 為 PostgreSQL UUID type，避免
 *       Java {@link String} 預設綁 VARCHAR 觸發 type mismatch。</li>
 *   <li>doSimilaritySearch 走 {@code <=>} cosine distance operator；
 *       {@code Document.score} = {@code 1 - distance}（與 SimpleVectorStore 慣例一致）。</li>
 * </ul>
 *
 * @see AbstractObservationVectorStore
 * @see SearchProjection
 * @see SemanticSearchService
 */
class SkillshubPgVectorStore extends AbstractObservationVectorStore {

    private static final Logger log = LoggerFactory.getLogger(SkillshubPgVectorStore.class);

    /** 6-欄 INSERT；ON CONFLICT 用 COALESCE 保留既有 owner/skill_id。 */
    static final String INSERT_SQL = """
            INSERT INTO vector_store (id, content, metadata, embedding, owner, skill_id)
            VALUES (?::uuid, ?, ?::jsonb, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE
              SET content = EXCLUDED.content,
                  metadata = EXCLUDED.metadata,
                  embedding = EXCLUDED.embedding,
                  owner = COALESCE(EXCLUDED.owner, vector_store.owner),
                  skill_id = COALESCE(EXCLUDED.skill_id, vector_store.skill_id)
            """;

    static final String DELETE_SQL = "DELETE FROM vector_store WHERE id = ?::uuid";

    /** Cosine distance similarity SQL — {@code <=>} 為 pgvector 餘弦距離 operator。 */
    static final String SIMILARITY_SEARCH_SQL = """
            SELECT id, content, metadata, embedding <=> ? AS distance
              FROM vector_store
             WHERE embedding <=> ? < ?
             ORDER BY distance
             LIMIT ?
            """;

    private static final String VECTOR_STORE_PROVIDER = "skillshub-pgvector";

    private static final JsonMapper JSON = JsonMapper.builder()
            .addModules(JacksonUtils.instantiateAvailableModules())
            .build();

    private final JdbcTemplate jdbcTemplate;
    private final @Nullable String owner;
    private final @Nullable String skillId;

    private SkillshubPgVectorStore(Builder builder) {
        super(builder);
        this.jdbcTemplate = builder.jdbcTemplate;
        this.owner = builder.owner;
        this.skillId = builder.skillId;
    }

    /** 建構新 builder；呼叫端透過 {@code .owner(...).skillId(...).build()} 取得 instance。 */
    public static Builder builder(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return new Builder(jdbcTemplate, embeddingModel);
    }

    @Override
    public void doAdd(List<Document> documents) {
        if (documents.isEmpty()) {
            return;
        }
        // 一次 batch 計算 embedding（沿用 Spring AI BatchingStrategy；父類 protected field）
        List<float[]> embeddings = this.embeddingModel.embed(documents,
                EmbeddingOptions.builder().build(), this.batchingStrategy);

        // 用 batchUpdate 走 PreparedStatement — 與官方 PgVectorStore.insertOrUpdateBatch 同模式
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Document doc = documents.get(i);
                String id = doc.getId();
                String content = doc.getText();
                String metadataJson = JSON.writeValueAsString(doc.getMetadata());
                PGvector embedding = new PGvector(embeddings.get(i));

                StatementCreatorUtils.setParameterValue(ps, 1, SqlTypeValue.TYPE_UNKNOWN, id);
                StatementCreatorUtils.setParameterValue(ps, 2, SqlTypeValue.TYPE_UNKNOWN, content);
                StatementCreatorUtils.setParameterValue(ps, 3, SqlTypeValue.TYPE_UNKNOWN, metadataJson);
                StatementCreatorUtils.setParameterValue(ps, 4, SqlTypeValue.TYPE_UNKNOWN, embedding);
                StatementCreatorUtils.setParameterValue(ps, 5, SqlTypeValue.TYPE_UNKNOWN, owner);
                StatementCreatorUtils.setParameterValue(ps, 6, SqlTypeValue.TYPE_UNKNOWN, skillId);
            }

            @Override
            public int getBatchSize() {
                return documents.size();
            }
        });
        log.atDebug()
                .addKeyValue("count", documents.size())
                .addKeyValue("owner", owner)
                .addKeyValue("skillId", skillId)
                .log("SkillshubPgVectorStore doAdd");
    }

    @Override
    public void doDelete(List<String> idList) {
        if (idList.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(DELETE_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                StatementCreatorUtils.setParameterValue(ps, 1, SqlTypeValue.TYPE_UNKNOWN, idList.get(i));
            }

            @Override
            public int getBatchSize() {
                return idList.size();
            }
        });
    }

    @Override
    public void doDelete(Filter.Expression filterExpression) {
        // S014 不支援 filter-based delete；S017 ACL filter 階段才實作（待時走 acl_entries GIN filter）
        throw new UnsupportedOperationException(
                "Filter-based delete not supported in S014 (will be added in S017 ACL filter)");
    }

    @Override
    public List<Document> doSimilaritySearch(SearchRequest request) {
        PGvector queryEmbedding = new PGvector(this.embeddingModel.embed(request.getQuery()));
        // similarityThreshold 0..1（1=完全相同）→ distance threshold = 1 - similarity
        double maxDistance = 1 - request.getSimilarityThreshold();
        return jdbcTemplate.query(SIMILARITY_SEARCH_SQL,
                new DocumentRowMapper(),
                queryEmbedding, queryEmbedding, maxDistance, request.getTopK());
    }

    /**
     * 暴露內部 {@link JdbcTemplate} 給高階 SQL 操作（如 S017 ACL filter
     * 自寫帶 acl_entries 的查詢）。與官方 {@code PgVectorStore.getNativeClient()}
     * 介面對齊。
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getNativeClient() {
        return Optional.of((T) this.jdbcTemplate);
    }

    @Override
    public VectorStoreObservationContext.Builder createObservationContextBuilder(String operationName) {
        return VectorStoreObservationContext.builder(VECTOR_STORE_PROVIDER, operationName);
    }

    /**
     * Builder — per-request 建構 {@link SkillshubPgVectorStore}。owner / skillId 為
     * optional（讀取場景不需設定；寫入場景由呼叫端注入 {@code CurrentUserProvider.userId()}
     * 與 {@code event.aggregateId()}）。
     */
    public static class Builder extends AbstractVectorStoreBuilder<Builder> {

        private final JdbcTemplate jdbcTemplate;
        private @Nullable String owner;
        private @Nullable String skillId;

        Builder(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
            super(embeddingModel);
            this.jdbcTemplate = jdbcTemplate;
        }

        public Builder owner(@Nullable String owner) {
            this.owner = owner;
            return self();
        }

        public Builder skillId(@Nullable String skillId) {
            this.skillId = skillId;
            return self();
        }

        public SkillshubPgVectorStore build() {
            return new SkillshubPgVectorStore(this);
        }
    }

    /**
     * RowMapper — 把 {@code SELECT ... embedding <=> ? AS distance} 結果轉回
     * {@link Document}。{@code score} = {@code 1 - distance}（cosine distance 0..2 →
     * similarity 接近 1 為最相關），與 SimpleVectorStore 慣例一致。
     */
    private static class DocumentRowMapper implements RowMapper<Document> {

        private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};

        @Override
        public Document mapRow(ResultSet rs, int rowNum) throws SQLException {
            String id = rs.getString("id");
            String content = rs.getString("content");
            double distance = rs.getDouble("distance");
            String metadataJson = rs.getString("metadata");
            Map<String, Object> metadata = metadataJson == null || metadataJson.isBlank()
                    ? Map.of()
                    : JSON.readValue(metadataJson, METADATA_TYPE);
            return Document.builder()
                    .id(id)
                    .text(content)
                    .metadata(metadata)
                    .score(1.0 - distance)
                    .build();
        }
    }
}
