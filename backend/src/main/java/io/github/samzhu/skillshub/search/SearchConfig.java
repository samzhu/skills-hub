package io.github.samzhu.skillshub.search;

import java.util.List;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.cloud.firestore.Firestore;

/**
 * 語意搜尋基礎設施配置 — 根據 {@code skillshub.search.vector-store} 屬性切換
 * VectorStore 後端實作，並在缺少 Google GenAI 憑證時提供無操作的備用 EmbeddingModel。
 *
 * <p>配置切換：
 * <ul>
 *   <li>{@code simple}（預設）：{@link SimpleVectorStore}（記憶體，重啟後清除）
 *   <li>{@code firestore}：{@link FirestoreVectorStore}（持久化，正式環境）
 * </ul>
 *
 * <p>相依模組：{@code search} — 不依賴任何業務模組。
 */
@Configuration
class SearchConfig {

    private static final Logger log = LoggerFactory.getLogger(SearchConfig.class);

    /**
     * 備用 EmbeddingModel — 當環境中未配置 Google GenAI 憑證時自動啟用（本機開發模式）。
     *
     * <p>回傳全零的 768 維向量，語意搜尋無法產生有意義的結果，但應用程式可正常啟動。
     * 正式環境（gcp profile）由 Spring AI 自動配置的 GoogleGenAiTextEmbeddingModel 取代此 bean。
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    EmbeddingModel noOpEmbeddingModel() {
        log.warn("No EmbeddingModel configured — semantic search disabled. "
                + "Configure spring.ai.google.genai.embedding for production use.");
        return new NoOpEmbeddingModel();
    }

    /**
     * 無操作 EmbeddingModel — 本機開發模式使用，所有文件回傳 768 維零向量。
     *
     * <p>此實作讓應用程式在缺少 GCP 憑證時仍可啟動。
     * 語意搜尋功能不可用（結果無意義），但其他功能正常運作。
     *
     * <p>正式環境（gcp profile）由 Google GenAI autoconfiguration 的 bean 取代。
     */
    private static final class NoOpEmbeddingModel implements EmbeddingModel {

        private static final float[] ZERO_VECTOR = new float[768];

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            // Return zero-vector embedding for each instruction text
            List<Embedding> embeddings = IntStream.range(0, request.getInstructions().size())
                    .mapToObj(i -> new Embedding(ZERO_VECTOR, i))
                    .toList();
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(@Nullable Document document) {
            return ZERO_VECTOR;
        }
    }

    /**
     * 記憶體 VectorStore — 本機開發與測試環境使用。
     *
     * <p>使用 Apache Commons Math 的 cosine similarity 計算。
     * 不持久化，應用程式重啟後所有 embedding 遺失。
     * 由 {@link SearchProjection} 監聽 domain events 時重新填充。
     *
     * <p>若 {@code skillshub.search.vector-store} 未設定，預設使用此 bean（matchIfMissing）。
     */
    @Bean
    @ConditionalOnProperty(name = "skillshub.search.vector-store",
            havingValue = "simple", matchIfMissing = true)
    VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        log.info("Initialising SimpleVectorStore (in-memory, non-persistent)");
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    /**
     * 生產環境 VectorStore — 使用 Firestore 原生 SDK 進行向量相似度搜尋。
     *
     * <p>僅在 {@code skillshub.search.vector-store=firestore} 時啟用（gcp profile）。
     * 需要預先在 Firestore 建立向量索引（見 {@link FirestoreVectorStore} 的 Javadoc）。
     */
    @Bean
    @ConditionalOnProperty(name = "skillshub.search.vector-store", havingValue = "firestore")
    VectorStore firestoreVectorStore(Firestore firestore, EmbeddingModel embeddingModel) {
        log.info("Initialising FirestoreVectorStore (persistent, collection=skill_embeddings)");
        return FirestoreVectorStore.builder(firestore, embeddingModel)
                .collectionName("skill_embeddings")
                .build();
    }
}
