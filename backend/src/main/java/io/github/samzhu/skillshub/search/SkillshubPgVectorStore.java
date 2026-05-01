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
 * Skills Hub 自訂 PgVectorStore — 寫入 vector_store 表 7 欄
 * （id, content, metadata, embedding, owner, skill_id, acl_entries），而非官方
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

    /**
     * 7-欄 INSERT，ON CONFLICT 保留 owner/skill_id/acl_entries（per S016 §4.16）。
     *
     * <p>{@code acl_entries} 綁兩次（position 7 + 8）解 NOT NULL constraint × COALESCE preservation 矛盾：
     * <ul>
     *   <li>position 7 = INSERT VALUES — 必須非 null（{@code acl_entries JSONB NOT NULL}）；
     *       caller 未設定時 builder 端用 {@code "[]"} 兜底</li>
     *   <li>position 8 = UPDATE COALESCE — caller 未設定時為 SQL null，COALESCE → 保留既有；
     *       caller 有設定時 = 與 position 7 同值，UPDATE 套用</li>
     * </ul>
     */
    static final String INSERT_SQL = """
            INSERT INTO vector_store (id, content, metadata, embedding, owner, skill_id, acl_entries)
            VALUES (?::uuid, ?, ?::jsonb, ?, ?, ?, ?::jsonb)
            ON CONFLICT (id) DO UPDATE
              SET content = EXCLUDED.content,
                  metadata = EXCLUDED.metadata,
                  embedding = EXCLUDED.embedding,
                  owner = COALESCE(EXCLUDED.owner, vector_store.owner),
                  skill_id = COALESCE(EXCLUDED.skill_id, vector_store.skill_id),
                  acl_entries = COALESCE(?::jsonb, vector_store.acl_entries)
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

    /**
     * S017：ACL-aware similarity search SQL — 加 {@code WHERE acl_entries ??| ?::text[]} filter，
     * 並 oversample {@code LIMIT topK * OVERSAMPLE_FACTOR} 解 HNSW post-filter recall 問題（per spec §2.5 Challenge #1）。
     *
     * <p>{@code ??} escape：pgJDBC PgPreparedStatement 在 NamedParameterJdbcTemplate 之下會
     * 重新 parse {@code ?} 為 placeholder（per S016 §2.4 #2）。{@code ??|} 字面送入 driver。
     *
     * <p>placeholder bind 順序（per spec §4.2）：
     * <ol>
     *   <li>queryEmbedding（PGvector）— ORDER BY distance</li>
     *   <li>aclPatternsArrayLiteral（{@code text[]} cast string，如 {@code {user:alice:read,role:admin:read}}）</li>
     *   <li>queryEmbedding（PGvector）— WHERE distance threshold</li>
     *   <li>maxDistance（double）</li>
     *   <li>topK * OVERSAMPLE_FACTOR（int）</li>
     * </ol>
     */
    // S059: JOIN skills + status='PUBLISHED' filter — 與 S031 list/categories visibility 一致；
    // DRAFT/SUSPENDED 即使有 vector embedding 也不公開呈現。idx_skills_status btree 加 JOIN 成本可忽略。
    static final String SIMILARITY_SEARCH_SQL_ACL = """
            SELECT vs.id, vs.content, vs.metadata, vs.embedding <=> ? AS distance
              FROM vector_store vs
              JOIN skills s ON s.id = vs.skill_id
             WHERE s.status = 'PUBLISHED'
               AND vs.acl_entries ??| ?::text[]
               AND vs.embedding <=> ? < ?
             ORDER BY distance
             LIMIT ?
            """;

    /**
     * S017：oversample factor — ACL filter + HNSW post-filter 可能少於 topK rows
     * （per spec §2.5 Challenge #1 + research Q1 Validated）。LIMIT 取 {@code topK * OVERSAMPLE_FACTOR}，
     * Java 端 sublist 至 topK。值 5 為 pgvector docs + Supabase blog 推薦慣例；
     * 若實作期 EXPLAIN ANALYZE 揭露大資料集 recall 不足可升級為 application.yaml knob。
     */
    static final int OVERSAMPLE_FACTOR = 5;

    private static final String VECTOR_STORE_PROVIDER = "skillshub-pgvector";

    private static final JsonMapper JSON = JsonMapper.builder()
            .addModules(JacksonUtils.instantiateAvailableModules())
            .build();

    private final JdbcTemplate jdbcTemplate;
    private final @Nullable String owner;
    private final @Nullable String skillId;
    private final @Nullable List<String> aclEntries;
    private final @Nullable List<String> aclPatterns;

    private SkillshubPgVectorStore(Builder builder) {
        super(builder);
        this.jdbcTemplate = builder.jdbcTemplate;
        this.owner = builder.owner;
        this.skillId = builder.skillId;
        this.aclEntries = builder.aclEntries;
        this.aclPatterns = builder.aclPatterns;
    }

    /** Test accessor — 驗 builder 設定的 aclPatterns 正確下放至 instance（per S017 T1 AC-1）。 */
    @org.jspecify.annotations.Nullable
    List<String> aclPatternsForTest() {
        return aclPatterns;
    }

    /**
     * S017：把 List<String> 序列化為 PostgreSQL {@code text[]} literal {@code {a,b,c}}。
     * 元素已通過 spec §4.1 regex 驗證（type:principal:permission 格式），不含逗號或大括號或雙引號，
     * 不需 escape。空 list → {@code "{}"}。
     */
    static String buildPgArrayLiteral(List<String> items) {
        if (items.isEmpty()) {
            return "{}";
        }
        return "{" + String.join(",", items) + "}";
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

                // S016：acl_entries 雙綁解 NOT NULL × COALESCE preservation 矛盾（per INSERT_SQL Javadoc）
                //  - aclJsonForInsert：給 position 7（INSERT VALUES），caller 未設定則用 "[]" 兜底
                //  - aclJsonForUpdate：給 position 8（UPDATE COALESCE），caller 未設定則用 null 觸發保留
                String aclSerialized = aclEntries == null ? null : JSON.writeValueAsString(aclEntries);
                String aclJsonForInsert = aclSerialized == null ? "[]" : aclSerialized;

                StatementCreatorUtils.setParameterValue(ps, 1, SqlTypeValue.TYPE_UNKNOWN, id);
                StatementCreatorUtils.setParameterValue(ps, 2, SqlTypeValue.TYPE_UNKNOWN, content);
                StatementCreatorUtils.setParameterValue(ps, 3, SqlTypeValue.TYPE_UNKNOWN, metadataJson);
                StatementCreatorUtils.setParameterValue(ps, 4, SqlTypeValue.TYPE_UNKNOWN, embedding);
                StatementCreatorUtils.setParameterValue(ps, 5, SqlTypeValue.TYPE_UNKNOWN, owner);
                StatementCreatorUtils.setParameterValue(ps, 6, SqlTypeValue.TYPE_UNKNOWN, skillId);
                StatementCreatorUtils.setParameterValue(ps, 7, SqlTypeValue.TYPE_UNKNOWN, aclJsonForInsert);
                StatementCreatorUtils.setParameterValue(ps, 8, SqlTypeValue.TYPE_UNKNOWN, aclSerialized);
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

        // S017：ACL 分流（per spec §4.3）
        //  - aclPatterns == null  → 走既有 SIMILARITY_SEARCH_SQL（向後相容）
        //  - aclPatterns 非 null → 走 SIMILARITY_SEARCH_SQL_ACL + oversample 5x + Java slice
        //    （空 list 仍走 ACL 路徑：?| ARRAY[]::text[] 永遠 false → empty result，fail-secure）
        if (this.aclPatterns == null) {
            return jdbcTemplate.query(SIMILARITY_SEARCH_SQL,
                    new DocumentRowMapper(),
                    queryEmbedding, queryEmbedding, maxDistance, request.getTopK());
        }

        int oversampleK = request.getTopK() * OVERSAMPLE_FACTOR;
        String aclArrayLiteral = buildPgArrayLiteral(this.aclPatterns);

        var oversampled = jdbcTemplate.query(SIMILARITY_SEARCH_SQL_ACL,
                new DocumentRowMapper(),
                queryEmbedding, aclArrayLiteral, queryEmbedding, maxDistance, oversampleK);

        log.atDebug()
                .addKeyValue("aclPatternsSize", this.aclPatterns.size())
                .addKeyValue("oversampleK", oversampleK)
                .addKeyValue("oversampledSize", oversampled.size())
                .addKeyValue("topK", request.getTopK())
                .log("SkillshubPgVectorStore doSimilaritySearch ACL path");

        // Math.min 兜底：oversampled 已 < topK 時 subList 會 IndexOutOfBoundsException
        int actualTopK = Math.min(oversampled.size(), request.getTopK());
        return oversampled.subList(0, actualTopK);
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
        private @Nullable List<String> aclEntries;
        private @Nullable List<String> aclPatterns;

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

        /**
         * S016：設定本次寫入的 ACL pattern list（如 {@code ["user:alice:read"]}）。
         *
         * <p>不設定時 INSERT 端帶 SQL NULL，搭配 {@code ON CONFLICT ... COALESCE(EXCLUDED.acl_entries,
         * vector_store.acl_entries)} 行為：
         * <ul>
         *   <li>新 row：acl_entries 預設 {@code []}（schema default）</li>
         *   <li>既有 row：保留現有 acl_entries 不被覆寫（re-embed scenario）</li>
         * </ul>
         */
        public Builder aclEntries(@Nullable List<String> aclEntries) {
            this.aclEntries = aclEntries;
            return self();
        }

        /**
         * S017：查詢端 — 設定本次 similaritySearch 的 ACL pattern list（讀路徑專用，與寫路徑
         * {@link #aclEntries(List)} 設計平行但語意分流）。
         *
         * <p>非 null 且非空 → SQL 走 {@link SkillshubPgVectorStore#SIMILARITY_SEARCH_SQL_ACL}
         * （含 {@code WHERE acl_entries ??| ?::text[]} filter）+ oversample
         * {@code LIMIT topK * OVERSAMPLE_FACTOR}（per spec §4.3 doSimilaritySearch 分流）。
         * <p>null → 走既有 {@link SkillshubPgVectorStore#SIMILARITY_SEARCH_SQL}（向後相容；S016 ship 前的 caller 行為不變）。
         * <p>空 list → 走 ACL 路徑但 patterns array 為空 — {@code ??|} 永遠 false，
         * 回 empty list（fail-secure；per spec §2.1 #5）。
         */
        public Builder aclPatterns(@Nullable List<String> aclPatterns) {
            this.aclPatterns = aclPatterns;
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
