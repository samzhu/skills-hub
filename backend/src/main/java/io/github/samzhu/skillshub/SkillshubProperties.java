package io.github.samzhu.skillshub;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Skills Hub 應用程式屬性集中管理。
 *
 * <p>透過 {@code @ConfigurationPropertiesScan} 在 {@link SkillshubApplication} 中自動掃描。
 * Spring Boot relaxed binding 確保 env var（如 {@code SKILLSHUB_STORAGE_BUCKET}）
 * 可直接覆蓋對應屬性，不需在 YAML 中加額外 placeholder。
 *
 * <p>各 nested record 均加上空白 {@code @DefaultValue}，確保即使設定檔未配置對應區塊，
 * nested record 也不為 null（Spring Boot 官方建議做法）。
 * 例外：{@link GenAI#apiKey()} 無預設值，為 null 時代表未設定 API key；
 * 由 {@code @ConditionalOnProperty(name = "skillshub.genai.api-key")} 控制 bean 是否建立。
 *
 * @see <a href="https://docs.spring.io/spring-boot/reference/features/external-config.html">
 *     Spring Boot External Configuration</a>
 * @see io.github.samzhu.skillshub.search.SearchConfig
 */
@ConfigurationProperties(prefix = "skillshub")
public record SkillshubProperties(
        @DefaultValue Storage storage,
        @DefaultValue Search search,
        @DefaultValue GenAI genai,
        @DefaultValue Scanner scanner) {

    /**
     * GCS / 本機儲存設定。
     *
     * @param bucket    GCS bucket 名稱；可透過 env var {@code SKILLSHUB_STORAGE_BUCKET} 覆蓋
     * @param localPath 本機開發儲存根目錄（{@code local} profile 使用）；
     *                  可透過 env var {@code SKILLSHUB_STORAGE_LOCAL_PATH} 覆蓋
     */
    public record Storage(
            @DefaultValue("skillshub-packages") String bucket,
            @DefaultValue("./storage-local") String localPath) {}

    /**
     * 向量搜尋後端設定。
     *
     * @param vectorStore 後端實作：{@code simple}（記憶體，本機開發）或
     *                    {@code firestore}（持久化，GCP 部署）；
     *                    可透過 env var {@code SKILLSHUB_SEARCH_VECTOR_STORE} 覆蓋
     * @param collection  向量搜尋集合名稱；
     *                    可透過 env var {@code SKILLSHUB_SEARCH_COLLECTION} 覆蓋
     */
    public record Search(
            @DefaultValue("simple") String vectorStore,
            @DefaultValue("skill_embeddings") String collection) {}

    /**
     * Google GenAI Embedding 設定。
     *
     * <p>{@code model} 和 {@code dimensions} 為固定值（所有環境共用），集中於此。
     * {@code apiKey} 為 null 時代表未設定，
     * {@link io.github.samzhu.skillshub.search.SearchConfig} 的
     * {@code @ConditionalOnProperty(name = "skillshub.genai.api-key")}
     * 會跳過真實 bean 建立，改用 NoOpEmbeddingModel（語意搜尋停用）。
     *
     * @param model      Embedding model 名稱
     * @param dimensions Embedding 向量維度
     * @param apiKey     Google AI Studio API key；設定於
     *                   {@code config/application-secrets.properties}（本機開發）
     *                   或 GCP 環境變數 {@code SKILLSHUB_GENAI_API_KEY}（Cloud Run 部署）
     */
    public record GenAI(
            @DefaultValue("gemini-embedding-2") String model,
            @DefaultValue("768") int dimensions,
            String apiKey) {}

    /**
     * 多引擎安全掃描 Pipeline 設定（S010）。
     *
     * <p>每個引擎透過獨立的 {@code skillshub.scanner.engines.<name>.enabled} 屬性控制。
     * 預設靜態引擎（pattern, secret, metadata, meta）開啟、LLM 引擎關閉
     * （需 {@code skillshub.genai.api-key} 才能啟用）。GCP profile 將 LLM 引擎打開。
     *
     * @param engines 各引擎開關設定
     */
    public record Scanner(@DefaultValue Engines engines) {}

    /**
     * 5 引擎開關集合 — 每個欄位對應一個 {@link io.github.samzhu.skillshub.security.scan.SecurityAnalyzer}
     * 實作 bean 的 {@code @ConditionalOnProperty} 條件。
     *
     * <p>所有 nested record 預設 {@code enabled=true}；LLM 引擎需要在 {@code application.yaml}
     * 顯式設定 {@code skillshub.scanner.engines.llm.enabled=false}（base profile）以關閉，
     * 並在 GCP profile 重新打開（要求 {@code skillshub.genai.api-key} 同時提供）。
     *
     * @param pattern  PatternScanner（regex 危險指令 / 敏感路徑 / pipe-to-shell）
     * @param secret   SecretScanner（API key / token / private key 偵測 + 遮罩）
     * @param metadata MetadataValidator（agentskills.io frontmatter 規則驗證）
     * @param llm      LlmJudge（Gemini 語意判斷；base profile 預設關閉）
     * @param meta     MetaAnalyzer（跨引擎合併規則）
     */
    public record Engines(
            @DefaultValue Engine pattern,
            @DefaultValue Engine secret,
            @DefaultValue Engine metadata,
            @DefaultValue Engine llm,
            @DefaultValue Engine meta) {}

    /**
     * 單一引擎開關記錄。
     *
     * @param enabled 引擎是否啟用（預設 {@code true}；個別引擎在 application.yaml 可覆寫）
     */
    public record Engine(@DefaultValue("true") boolean enabled) {}
}
