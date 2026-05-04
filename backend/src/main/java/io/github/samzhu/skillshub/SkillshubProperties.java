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
        @DefaultValue Scanner scanner,
        @DefaultValue Security security) {

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
     * <p>T8 起唯一後端為自寫 {@link io.github.samzhu.skillshub.search.SkillshubPgVectorStore}
     * 子類（HNSW + cosine + 6-欄 INSERT），不再有切換選項。
     *
     * @param collection 向量搜尋集合名稱；
     *                   可透過 env var {@code SKILLSHUB_SEARCH_COLLECTION} 覆蓋
     */
    public record Search(
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
     * 全部引擎（pattern / secret / metadata / llm / meta）預設 {@code enabled=true}（fail-on
     * 姿態，貼近正式環境）。LLM 引擎雖預設啟用，但 bean 還由 {@code @ConditionalOnProperty}
     * 對 {@code skillshub.genai.api-key} 把關 — 缺 api-key 時 bean 不建立，效果等同關閉，
     * 不會產生額外 API 成本。
     *
     * @param engines 各引擎開關設定
     */
    public record Scanner(@DefaultValue Engines engines) {}

    /**
     * 5 引擎開關集合 — 每個欄位對應一個 {@link io.github.samzhu.skillshub.security.scan.SecurityAnalyzer}
     * 實作 bean 的 {@code @ConditionalOnProperty} 條件。
     *
     * <p>所有 nested record 預設 {@code enabled=true}（per {@link Engine#enabled()} 的
     * {@code @DefaultValue("true")}）；如某環境特殊需求要關閉個別引擎，於該 profile yaml
     * 顯式設定 {@code skillshub.scanner.engines.<name>.enabled=false} 即可。
     *
     * @param pattern  PatternScanner（regex 危險指令 / 敏感路徑 / pipe-to-shell）
     * @param secret   SecretScanner（API key / token / private key 偵測 + 遮罩）
     * @param metadata MetadataValidator（agentskills.io frontmatter 規則驗證）
     * @param llm      LlmJudge（Gemini 語意判斷；缺 api-key 時 bean 不建立）
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

    /**
     * Security 設定 — OAuth Resource Server 開關 + LAB 模式預設值（S012）。
     *
     * <p>透過 {@code skillshub.security.oauth.enabled} 控制 OAuth2 Resource Server 鏈路是否啟用：
     * <ul>
     *   <li>{@code true}（預設、production / 對外開發）— SecurityFilterChain 啟用 OAuth2 RS、
     *       JwtDecoder + JwtAuthenticationConverter beans 透過 {@code @ConditionalOnProperty}
     *       建立、{@code /api/v1/me} 與 {@code /api/v1/admin/**} 須帶 JWT。</li>
     *   <li>{@code false}（LAB / 純功能測試）— SecurityFilterChain anyRequest permitAll、
     *       {@code LabSecurityFilter} 注入預設 lab user Authentication（{@code lab.user-id} 帶
     *       {@code ROLE_admin}），所有 endpoint 在無 JWT 情境下仍可訪問且帶 admin 權限。</li>
     * </ul>
     *
     * <p>{@code lab.user-id} 同時提供給 {@link io.github.samzhu.skillshub.shared.security.CurrentUserProvider}
     * 作為 LAB 模式 / 安全 fallback 情境下的固定 userId（未來 audit 欄位的 createdBy）。
     *
     * @param oauth OAuth Resource Server 開關
     * @param lab   LAB 模式專屬設定（預設 user 識別）
     */
    public record Security(
            @DefaultValue OAuth oauth,
            @DefaultValue Lab lab,
            @DefaultValue Cors cors) {}

    /**
     * @param enabled OAuth2 Resource Server 是否啟用；預設 {@code true}（fail-secure）。
     *                LAB 環境須以 env var {@code SKILLSHUB_SECURITY_OAUTH_ENABLED=false} 顯式關閉。
     */
    public record OAuth(@DefaultValue("true") boolean enabled) {}

    /**
     * @param userId LAB 模式下 {@code LabSecurityFilter} 注入的 principal 值與
     *               {@link io.github.samzhu.skillshub.shared.security.CurrentUserProvider}
     *               的 fallback userId；預設 {@code "lab-user"}（一眼識別非真實 user）。
     */
    public record Lab(@DefaultValue("lab-user") String userId) {}

    /**
     * S128：CORS 設定（per Mode B Round 40 Bug AZ fix）。
     *
     * <p>LAB / production 部署 frontend 與 backend 不同 origin 時需 CORS allowlist；dev 環境
     * vite proxy 同 origin 不依賴此設定但仍會 echo 回 Access-Control headers（無傷）。
     *
     * <p>{@code allowed-origins} 可由 env var {@code SKILLSHUB_SECURITY_CORS_ALLOWED_ORIGINS}
     * 注入逗號分隔多 origin（per Spring Boot {@code List<String>} bind 慣例）。
     *
     * @param allowedOrigins  允許的 origin list；預設含 dev vite (localhost:5173) 與 backend
     *                         self (localhost:8080)；production 須顯式覆蓋為 LAB / 對外 host。
     * @param allowCredentials 是否允許 credentials（cookie / Authorization header）；預設 true 以
     *                         支援 OAuth bearer token 跨 origin 流程。
     */
    public record Cors(
            @DefaultValue({"http://localhost:5173", "http://localhost:8080"}) java.util.List<String> allowedOrigins,
            @DefaultValue("true") boolean allowCredentials) {}
}
