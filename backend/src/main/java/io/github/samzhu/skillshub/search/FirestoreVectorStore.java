package io.github.samzhu.skillshub.search;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.AbstractVectorStoreBuilder;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.observation.AbstractObservationVectorStore;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;

import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.VectorQuery;
import com.google.cloud.firestore.VectorQueryOptions;

/**
 * Firestore 實作的 VectorStore — 生產環境語意搜尋後端。
 *
 * <p>透過 Google Cloud Firestore 原生 SDK 的 {@code findNearest()} 執行向量相似度搜尋。
 * 與 MongoDB wire protocol（用於 CRUD 和 Event Store）並行運作，各自使用不同的連線協定。
 *
 * <p>啟用條件：{@code skillshub.search.vector-store=firestore}（見 {@link SearchConfig}）。
 * 開發環境使用 {@link org.springframework.ai.vectorstore.SimpleVectorStore}。
 *
 * <p>Firestore 向量索引需在部署時預先建立：
 * <pre>
 * gcloud firestore indexes composite create \
 *   --collection-group=skill_embeddings \
 *   --field-config field-path=embedding,vector-config='{"dimension":"768","flat":"{}"}'
 * </pre>
 *
 * @see SearchConfig
 * @see SemanticSearchService
 */
class FirestoreVectorStore extends AbstractObservationVectorStore {

    private static final Logger log = LoggerFactory.getLogger(FirestoreVectorStore.class);

    /** Firestore document 中儲存向量和原始文字的欄位名 — 非 skill metadata，不回傳給前端 */
    private static final String EMBEDDING_FIELD = "embedding";
    private static final String TEXT_FIELD = "text";

    /** 讀回 Firestore 向量搜尋距離時使用的欄位名（由 VectorQueryOptions 設定） */
    private static final String DISTANCE_RESULT_FIELD = "distance";

    /** 從 Firestore document 提取 metadata 時排除的內部欄位 */
    private static final Set<String> INTERNAL_FIELDS = Set.of(EMBEDDING_FIELD, TEXT_FIELD, DISTANCE_RESULT_FIELD);

    private final Firestore firestore;
    private final String collectionName;

    private FirestoreVectorStore(Builder builder) {
        super(builder);
        this.firestore = builder.firestore;
        this.collectionName = builder.collectionName;
    }

    /**
     * 建立 {@link FirestoreVectorStore} 的 builder。
     *
     * @param firestore      Firestore 原生 SDK client（由 Spring Cloud GCP 自動配置）
     * @param embeddingModel 用於計算向量的 EmbeddingModel（生產環境為 Gemini）
     * @return Builder 實例
     */
    static Builder builder(Firestore firestore, EmbeddingModel embeddingModel) {
        return new Builder(firestore, embeddingModel);
    }

    /**
     * 批次計算 embedding 並將 Document 寫入 Firestore。
     * 向量以 {@link FieldValue#vector(double[])} 格式儲存，供 {@code findNearest()} 使用。
     * float[] → double[] 轉換是 Firestore SDK 的 API 要求。
     */
    @Override
    public void doAdd(List<Document> documents) {
        log.info("FirestoreVectorStore doAdd count={} collection={}", documents.size(), collectionName);
        // Batch embed: embed(docs, options, batchingStrategy) is more efficient than per-doc embed()
        List<float[]> embeddings = this.embeddingModel.embed(
                documents, EmbeddingOptions.builder().build(), this.batchingStrategy);

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            float[] embedding = embeddings.get(i);

            // Store metadata + text + embedding (as Firestore VectorValue) in single document
            Map<String, Object> data = new HashMap<>(doc.getMetadata());
            data.put(TEXT_FIELD, doc.getText());
            // Firestore SDK requires double[] for VectorValue; float[] → double[] conversion needed
            data.put(EMBEDDING_FIELD, FieldValue.vector(toDoubleArray(embedding)));

            try {
                firestore.collection(collectionName)
                        .document(doc.getId())
                        .set(data)
                        .get();   // block — acceptable in async event listener context
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("FirestoreVectorStore doAdd interrupted docId={}", doc.getId());
                throw new IllegalStateException("Firestore write interrupted", e);
            } catch (ExecutionException e) {
                log.warn("FirestoreVectorStore doAdd failed docId={}", doc.getId(), e.getCause());
                throw new IllegalStateException("Firestore write failed for doc " + doc.getId(), e.getCause());
            }
        }
        log.info("FirestoreVectorStore doAdd done count={}", documents.size());
    }

    /**
     * 從 Firestore 中刪除指定 ID 的 document。
     * 對應 SearchProjection 的 delete-then-add 更新模式。
     */
    @Override
    public void doDelete(List<String> idList) {
        log.info("FirestoreVectorStore doDelete count={} collection={}", idList.size(), collectionName);
        for (String id : idList) {
            try {
                firestore.collection(collectionName).document(id).delete().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("FirestoreVectorStore doDelete interrupted id={}", id);
                throw new IllegalStateException("Firestore delete interrupted", e);
            } catch (ExecutionException e) {
                log.warn("FirestoreVectorStore doDelete failed id={}", id, e.getCause());
                throw new IllegalStateException("Firestore delete failed for id " + id, e.getCause());
            }
        }
    }

    /**
     * 執行向量相似度搜尋，回傳語意相關文件。
     *
     * <p>流程：embed(query) → findNearest(COSINE) → 讀取 distance → score = 1.0 - distance
     * → 依 similarityThreshold 過濾。
     *
     * <p>COSINE 距離範圍 [0, 2]，距離 0 = 完全相同；Score = 1.0 - distance，
     * 轉換後範圍 [-1, 1]，典型有效範圍 [0, 1]。
     */
    @Override
    public List<Document> doSimilaritySearch(SearchRequest request) {
        log.info("FirestoreVectorStore doSimilaritySearch query={} topK={}", request.getQuery(), request.getTopK());
        float[] queryEmbedding = this.embeddingModel.embed(request.getQuery());
        double[] queryVector = toDoubleArray(queryEmbedding);

        // Request distance result so we can compute score = 1.0 - cosine_distance
        var options = VectorQueryOptions.newBuilder()
                .setDistanceResultField(DISTANCE_RESULT_FIELD)
                .build();

        try {
            var snapshot = firestore.collection(collectionName)
                    .findNearest(EMBEDDING_FIELD, queryVector, request.getTopK(),
                            VectorQuery.DistanceMeasure.COSINE, options)
                    .get()
                    .get();

            return snapshot.getDocuments().stream()
                    .map(fsDoc -> {
                        // Cosine distance [0, 2] → similarity score; null-safe guard if distance absent
                        Double distance = fsDoc.getDouble(DISTANCE_RESULT_FIELD);
                        double score = distance != null ? (1.0 - distance) : 0.0;
                        return Document.builder()
                                .id(fsDoc.getId())
                                .text(fsDoc.getString(TEXT_FIELD))
                                .metadata(extractMetadata(fsDoc.getData()))
                                .score(score)
                                .build();
                    })
                    // Filter by similarity threshold AFTER computing score
                    .filter(doc -> doc.getScore() >= request.getSimilarityThreshold())
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("FirestoreVectorStore doSimilaritySearch interrupted");
            throw new IllegalStateException("Firestore search interrupted", e);
        } catch (ExecutionException e) {
            log.warn("FirestoreVectorStore doSimilaritySearch failed", e.getCause());
            throw new IllegalStateException("Firestore search failed", e.getCause());
        }
    }

    /**
     * Provides observability context for Micrometer tracing.
     * Required by AbstractObservationVectorStore for instrumentation.
     */
    @Override
    public VectorStoreObservationContext.Builder createObservationContextBuilder(String operation) {
        return new VectorStoreObservationContext.Builder(operation, "firestore")
                .collectionName(collectionName)
                .dimensions(768)
                .similarityMetric("cosine");
    }

    /**
     * 從 Firestore document 資料中提取 skill metadata，排除向量儲存的內部欄位。
     * 內部欄位（embedding, text, distance）不屬於 skill metadata，不應回傳給前端。
     */
    private static Map<String, Object> extractMetadata(Map<String, Object> fsData) {
        var meta = new HashMap<String, Object>();
        fsData.forEach((k, v) -> {
            if (!INTERNAL_FIELDS.contains(k)) {
                meta.put(k, v);
            }
        });
        return meta;
    }

    /** float[] → double[] 轉換：Firestore SDK 的 FieldValue.vector() 要求 double[]。 */
    private static double[] toDoubleArray(float[] floats) {
        double[] doubles = new double[floats.length];
        for (int i = 0; i < floats.length; i++) {
            doubles[i] = floats[i];
        }
        return doubles;
    }

    /**
     * {@link FirestoreVectorStore} 的 Builder。
     *
     * <p>繼承 {@link AbstractVectorStoreBuilder} 以獲得 EmbeddingModel、
     * ObservationRegistry 和 BatchingStrategy 的標準配置能力。
     */
    static final class Builder extends AbstractVectorStoreBuilder<Builder> {

        private final Firestore firestore;
        private String collectionName = "skill_embeddings";

        private Builder(Firestore firestore, EmbeddingModel embeddingModel) {
            super(embeddingModel);
            this.firestore = firestore;
            // Default batching strategy; can be overridden via batchingStrategy()
            this.batchingStrategy = new TokenCountBatchingStrategy();
        }

        /**
         * 設定 Firestore collection 名稱（預設 "skill_embeddings"）。
         *
         * @param collectionName Firestore collection 名稱
         * @return this builder
         */
        Builder collectionName(String collectionName) {
            this.collectionName = collectionName;
            return this;
        }

        /** 建立 {@link FirestoreVectorStore} 實例。 */
        @Override
        public FirestoreVectorStore build() {
            return new FirestoreVectorStore(this);
        }
    }
}
