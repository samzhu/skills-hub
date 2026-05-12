# S157: Semantic Search Not Functional in LAB — Hero Feature 實質失靈

> Spec: S157 | Size: M(8) → S(6) — root cause 鎖定（2026-05-11 研究），fix pattern 同 QualityJudgeConfig (S135a) proven precedent | Status: 📐 in-design
> Date: 2026-05-08（origin）/ 2026-05-11（root cause 鎖定）
> Origin: deployment audit 2026-05-08（LAB）— 平台主打「語意搜尋」hero feature 但 LAB 實測 `/search?q=terraform` 對名稱含 `terraform` 的 skill 回 0 results；連帶 `/api/v1/search/intent` 回 fallback `{"summary":"terraform","concepts":[]}`。

---

## 1. Goal

讓「語意搜尋」在 LAB 環境真的能用：搜「terraform」找到 `auditing-terraform-...`、搜「容器部署」找到 docker / k8s 類 skill；同時 `/api/v1/search/intent` 回真實 LLM 解析的中文 summary + 英文 concept tags（不再 echo query）。

**為什麼重要：** LandingPage hero + `/docs/semantic-search` 都明確標榜「Gemini embedding + pgvector」是核心差異化；UI 完整但 LAB 永遠 0 結果 → user 首次體驗對平台價值打問號。

**非目標：** 不重做語意搜尋 UI；不調整 cosine threshold tuning（先讓功能 work）；不重複處理 S148 已 ship 的 `JudgeResponse` reflection hint。

---

## 2. Root Cause — Validated

### 2.1 一句話

LAB 跑的是 **GraalVM native image**。Spring AOT 在 **build time** 評估 `@ConditionalOnProperty(name="skillshub.genai.api-key")`，那個時間點 api-key 還沒注入（runtime 才從 Secret Manager 透過 `${sm@...}` placeholder 拉），AOT 把「bean 不要建」這個決定 baked 進 native binary。Runtime 即使 env var 已 resolve，bean factory 也沒辦法再造出 bean，fallback 路徑（NoOp embedder + `Optional<ChatClient>` empty）接管。

跟 S148（reflection metadata）/ S168（Boolean wrapper）同屬「runtime-only 資訊被 AOT freeze 在 build time」家族 bug，不同切面。

### 2.2 證據鏈（5 個獨立 validated signal）

1. **LAB 確實是 native image**：
   - `cloudbuild.yaml:79-97` step 3 跑 `bootBuildImage`；`org.graalvm.buildtools.native` 0.11.5 plugin 在 `META-INF/native-image/` 寫 metadata，Paketo `noble-java-tiny` order group 自動選 `java-native-image` buildpack（per `architecture.md:566-572`）
   - LAB Cloud Run revision `skillshub-00019-wvz` startup log：`Starting AOT-processed SkillshubApplication ... Started in 5.497 seconds`（native ~5s；JVM bootJar 典型 30-90s）+ working dir `cnb in /workspace`（Paketo CNB user）

2. **api-key 確實有寫進 Secret Manager 並 runtime resolve OK**：
   - `gcloud secrets versions list skillshub-genai-api-key --project=cfh-vibe-lab` → `1 enabled 2026-05-06`
   - `service.yaml:116` env var `skillshub.genai.api-key=${sm@skillshub-genai-api-key}` 接線正確
   - 同 revision `QualityJudgeConfig` 14:30:06.241 log 顯示 `Initialising quality judge ChatModel (API key mode, model=GEMINI_2_5_FLASH)` — runtime sm@ 已 resolve 到真 key

3. **同 property 兩個 bean 行為分歧**（startup log 直接對比）：
   ```
   14:30:06.241  INFO  QualityJudgeConfig   : Initialising quality judge ChatModel (API key mode, model=GEMINI_2_5_FLASH)
   14:30:06.259  WARN  SearchConfig         : No EmbeddingModel configured — semantic search disabled. Set skillshub.genai.api-key to enable.
   14:30:06.261  INFO  SearchIntentService  : SearchIntent: LLM ChatClient unavailable — running in fallback mode (echo query, no concepts)
   ```
   QualityJudgeConfig 拿到、SearchConfig 跟 ScannerAiConfig 拿不到 — 差別不在 runtime property 值，**只在 condition 寫法**。

4. **`QualityJudgeConfig.java:17-23` Javadoc 已紀錄此 bug 與修法**：
   > 「不再對 `skillshub.genai.api-key` 做 build-time 條件 — Spring AOT 會在 build time 評估 `@ConditionalOnProperty`，CI/AOT 階段 api-key 缺席會把整個 bean 從 baked context 排除……改由 factory method runtime 觸發時讀取 api-key（沒設則 client builder 失敗，fail-fast）。」
   
   S135a (v3.14.0) ship 時已踩過這坑修過一次；S157 是同坑在 `search` + `security.scan` 兩個 module 沒被修。

5. **直打 LAB endpoint 重現症狀，完美對應 fallback 程式碼路徑**：
   - `POST /api/v1/search/intent {"query":"terraform"}` → `{"summary":"terraform","concepts":[]}` ← `SearchIntentService.java:42-43, 56-58` chatClient.isEmpty() fallback
   - `GET /api/v1/search/semantic?q=terraform` → `[]` ← NoOpEmbeddingModel 回 768 維零向量 → cosine 距離永遠 1 > maxDistance 0.7（`SemanticSearchService:55, 94`）→ 全 row 被 SQL `WHERE distance < ?` filter 掉

### 2.3 AOT bake-out 機制

```
Build time (Cloud Build container, CI 無 Secret Manager access)
├── ./gradlew bootBuildImage -Pspring.profiles.active=gcp,aot,lab
├── processAot task
│   ├── Spring 評估 @Conditional* annotations 對應 BeanDefinition
│   │   ├── Environment.getProperty("skillshub.genai.api-key") 來源：
│   │   │   ├── application*.yaml — 全文無此 key 字面值（grep 確認）
│   │   │   ├── CI env var — 無（CI 容器無 sm@ resolver）
│   │   │   └── → null
│   │   └── @ConditionalOnProperty(name="skillshub.genai.api-key") → false
│   └── BeanDefinition 排除 googleGenAiEmbeddingModel / scannerChatModel / scannerChatClient
└── 產出 native binary 內含 baked BeanFactory；其餘永久排除

Runtime (Cloud Run, 容器啟動)
├── native ELF 執行
├── env var skillshub.genai.api-key=${sm@skillshub-genai-api-key} 注入
├── spring-cloud-gcp 啟動時 resolve sm@ → 真 API key string ← QualityJudgeConfig 證實此步成功
├── @Conditional* 已 freeze（baked BeanDefinition 不再重評估）
└── 三個 bean 永遠不存在 → @ConditionalOnMissingBean fallback (NoOp) 接管
                       → Optional<ChatClient> 永遠 empty
```

### 2.4 ScannerAiConfig 不只 api-key 條件壞 — `EngineEnabled` 也 bake-out

`ScannerAiConfig.LlmEnabledCondition` 是 `AllNestedConditions(EngineEnabled, ApiKeyPresent)`，兩個 nested 都要 true bean 才建：

```java
@ConditionalOnProperty(name = "skillshub.scanner.engines.llm.enabled", havingValue = "true")
static class EngineEnabled {}   // ← 沒 matchIfMissing
```

`@DefaultValue("true")` 在 `SkillshubProperties.Engine.enabled` 只影響 `@ConfigurationProperties` 綁定後的 record value，**不會寫進 Environment property source**。AOT 階段 `Environment.getProperty("skillshub.scanner.engines.llm.enabled")` 看到的還是 null → false → bean 排除。

對比 QualityJudgeConfig 的 working condition：
```java
@ConditionalOnProperty(name = "skillshub.quality.judge.enabled", matchIfMissing = true)
```
明確 `matchIfMissing=true`，**且** `application.yaml:161` 顯式設 `skillshub.quality.judge.enabled: true` 雙重保險。

→ S157 修 ScannerAiConfig 必須同時處理兩個 condition（不能只動 api-key 那條）。

### 2.5 Latent S148-twin — `LlmIntentOutput` record 缺 reflection metadata

Bean wiring 修好後 ChatClient 真會打到 Gemini。下個會炸的點：

```java
// SearchIntentService.java:60, 75
var converter = new BeanOutputConverter<>(LlmIntentOutput.class);
var parsed = converter.convert(raw);  // ← Jackson 反射 getRecordComponents() on LlmIntentOutput record
```

`LlmIntentOutput`（line 90）是 record，跟 S148 修掉的 `JudgeResponse` 完全同 BeanOutputConverter pattern。全 codebase grep `@RegisterReflectionForBinding`：

```
backend/.../score/ScoreNativeConfig.java   # S148 ship — JudgeResponse + DimensionScore
```

**只一個 file**，`LlmIntentOutput` 沒被涵蓋 → native runtime 必丟 `UnsupportedFeatureError: Record components not available for record class ...LlmIntentOutput`。`UnsupportedFeatureError extends Error`，**不會**被 `SearchIntentService.compute()` line 82 的 `catch (Exception e)` 接 — 會 propagate 成 500。

現在沒看到症狀因為 ChatClient 根本沒接到，BeanOutputConverter 路徑沒走到。Bean fix ship 完此 latent 必爆 → **同 spec 一併處理**。

### 2.6 同家族 bug 比較表

| Spec | Native 失敗點 | Fix pattern | Ship |
|---|---|---|---|
| S148 | `Class.getRecordComponents()` 對 `JudgeResponse` 沒 reflection metadata → `UnsupportedFeatureError` | `@RegisterReflectionForBinding` | v4.25.0 |
| S168 | `UnsafeBooleanFieldAccessorImpl` Boolean→Integer corrupt → IAE | primitive `boolean` → wrapper `Boolean` | v4.49.0 |
| **S157** | **Spring AOT build-time `@ConditionalOnProperty` bake-out → runtime bean factory 無法建 bean** + latent: `LlmIntentOutput` record 缺 hint | **condition 改 runtime factory body check (mirror QualityJudgeConfig);** 補 `@RegisterReflectionForBinding(LlmIntentOutput.class)` | TBD |

### 2.7 Confidence Classification

| 結論 | Confidence | 證據 |
|---|---|---|
| LAB 跑 native image | **Validated** | cloudbuild.yaml + architecture.md + Cloud Run log AOT-processed marker |
| api-key 在 Secret Manager 已就位、runtime resolve OK | **Validated** | gcloud + QualityJudgeConfig 同 revision runtime init log |
| `@ConditionalOnProperty(api-key)` AOT bake-out | **Validated** | QualityJudgeConfig Javadoc + log 對比 + Spring AOT 官方行為 |
| `EngineEnabled` 也 bake-out（無 matchIfMissing）| **Validated** | source 對比 + AOT yaml 無此 key + 同機制 |
| `LlmIntentOutput` 缺 hint 將在 native runtime 炸 | **Validated** | grep 全 codebase 僅 1 file 有此 annotation；API pattern 與 S148 一模一樣 |
| `vector_store` 既有 row 是零向量（NoOp 寫的）| **Hypothesis** | NoOp 自 2026-05-06 起 active；ship 後 re-publish 3 筆驗證即可 |
| cosine threshold 0.3 太嚴 | Not the root cause | bean 修好前 query embedding 永遠零；threshold tuning 屬 polish spec |

---

## 3. Approach — Mirror QualityJudgeConfig (S135a) 已 ship 的 pattern

### 3.1 為何選此 approach

- (a) QualityJudgeConfig 在 LAB native runtime 已驗證可用（log 直接證據）
- (b) S135a Javadoc 已紀錄理由，與 Spring 官方 AOT 文件一致
- (c) 改動範圍小、無新依賴、reviewer 一秒看出在做什麼

不選的 approach：

| Alternative | 不選原因 |
|---|---|
| AOT yaml 內塞 stub api-key（S139 OAuth2 client 同模式）| api-key 是機敏；且實際 runtime 仍需要拿 sm@ value，沒解決真實機制 |
| spring-cloud-gcp 在 AOT 階段 resolve sm@ | CI 無 GCP creds，不可能 |
| 改 lazy bean | Spring lazy 不解 AOT bake-out — baked 決定永久排除 |

### 3.2 SearchConfig 改寫（F1）

合併 2 個 @Bean 為單一 `embeddingModel` factory：

```java
@Bean
EmbeddingModel embeddingModel(SkillshubProperties props) {
    var apiKey = props.genai().apiKey();
    if (apiKey == null || apiKey.isBlank()) {
        log.warn("No skillshub.genai.api-key configured — semantic search disabled (NoOp)");
        return new NoOpEmbeddingModel();
    }
    log.info("Initialising GoogleGenAiTextEmbeddingModel (Manual Config, API key mode)");
    var connectionDetails = GoogleGenAiEmbeddingConnectionDetails.builder().apiKey(apiKey).build();
    var options = GoogleGenAiTextEmbeddingOptions.builder()
            .model(props.genai().model())
            .dimensions(props.genai().dimensions())
            .build();
    return new GoogleGenAiTextEmbeddingModel(connectionDetails, options);
}
// NoOpEmbeddingModel inner class 保留
```

關鍵變動：移除 `@ConditionalOnProperty(name="skillshub.genai.api-key")` + `@ConditionalOnMissingBean`，body 內 branch real / NoOp。Runtime SkillshubProperties 已 resolve sm@（per §2.2 evidence #2）。

### 3.3 ScannerAiConfig 改寫（F2）

合併 `scannerChatModel` + `scannerChatClient` 為單一 factory，移除整個 `LlmEnabledCondition` inner class：

```java
@Bean
ChatClient scannerChatClient(SkillshubProperties props) {
    if (!props.scanner().engines().llm().enabled() || isBlank(props.genai().apiKey())) {
        log.info("Scanner LLM disabled or api-key absent — ChatClient not registered");
        return null;  // Spring @Bean returning null → 視為 absent → Optional<ChatClient> empty
    }
    var client = Client.builder().apiKey(props.genai().apiKey()).build();
    var chatModel = GoogleGenAiChatModel.builder()
            .genAiClient(client)
            .defaultOptions(GoogleGenAiChatOptions.builder()
                    .model(GoogleGenAiChatModel.ChatModel.GEMINI_2_5_FLASH)
                    .temperature(0.0)
                    .build())
            .build();
    return ChatClient.create(chatModel);
}
```

關鍵：
- 拿掉 `@Conditional(LlmEnabledCondition.class)`（兩個 nested 都 bake-out）
- 合併 `scannerChatModel` 為 local var — `GoogleGenAiChatModel` 不需獨立 bean（grep 確認 `ScannerAiConfig` 是唯一 consumer）
- `return null` 為 Spring 官方支援寫法（Spring Framework Reference: 「If a @Bean method returns null, Spring registers a null bean...」），`LlmJudge` 既有 `Optional<ChatClient>` 接，empty 時 graceful 跳過
- 連帶刪掉 `LlmEnabledCondition` inner class

### 3.4 LlmIntentOutput AOT reflection hint（F3）

新增 `search/SearchNativeConfig.java`，exact same pattern as `ScoreNativeConfig`：

```java
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({SearchIntentService.LlmIntentOutput.class})
class SearchNativeConfig {
    // 僅供 AOT hint 來源，無 bean 宣告
}
```

只列 `LlmIntentOutput`，不列 `IntentResponse`：後者由 controller 序列化回應，Spring Boot 4 對 `@RestController` response body 已自動 traverse；前者是 `BeanOutputConverter` 反序列化目標，**Spring AI 不自動 register**（per S148 §2.3 確認）。`LlmIntentOutput` 內含 `String summary, List<String> concepts` — Spring framework 對 nested record / `List<String>` 自動 traverse（per S148 §3.1）。

### 3.5 既有 vector_store 零向量 row backfill（F4）

不寫獨立 backfill job。修復 deploy 後手動 re-publish LAB 3 筆 skill：
- `SearchProjection.onVersionPublished` listener 走 delete-then-add → ON CONFLICT DO UPDATE 覆蓋舊零向量 row（per `SkillshubPgVectorStore.INSERT_SQL`）
- 3 筆量極小；production 若 publish 流量大可未來開 spec

### 3.6 Regression guards（F5）

防未來 PR 把 build-time condition 加回去 / 移除 reflection hint — mirror S168 reflection-based AC 寫法（per `S168 AC-2/AC-3` 已 ship 證明的 pattern）。詳 §4 AC-5/AC-6。

---

## 4. SBE Acceptance Criteria

```
AC-1: 「terraform」query 命中對應 skill
  Given 平台 LAB 部署修復版本後，3 筆 skill 已 re-publish
  When  GET /api/v1/search/semantic?q=terraform
  Then  HTTP 200, response array length ≥ 1
  And   第一筆 result.skillId 對應 auditing-terraform-infrastructure-for-security
  And   第一筆 result.score ≥ 0.3

AC-2: 中文 query 也能命中英文 description
  Given 同上 LAB 部署狀態
  When  GET /api/v1/search/semantic?q=Terraform 安全稽核
  Then  HTTP 200, response array length ≥ 1

AC-3: Intent endpoint 回真實 concepts（不再 echo fallback）
  Given LAB 部署修復版本
  When  POST /api/v1/search/intent body {"query":"terraform 部署"}
  Then  response.concepts.length > 0（如 ["terraform","deployment","infrastructure"]）
  And   response.summary 不等於 query（LLM 確實回了改寫過的中文 summary）

AC-4: Cloud Run startup log 不再警告 LLM bean 缺席
  Given LAB 部署修復版本後第一次 cold start
  When  gcloud logging read 取該 revision startup 30 秒範圍
  Then  log 不出現 "No EmbeddingModel configured"
  And   log 不出現 "SearchIntent: LLM ChatClient unavailable"
  And   log 包含 "Initialising GoogleGenAiTextEmbeddingModel"

AC-5: SearchConfig.embeddingModel 上不含 @ConditionalOnProperty(api-key)（regression guard）
  Given codebase 含 SearchConfig.embeddingModel @Bean factory method
  When  reflection 取該 method 的 annotations
  Then  不存在 @ConditionalOnProperty(name="skillshub.genai.api-key")
  Note  防未來 PR 把 condition 加回 — mirror S168 reflection-based AC

AC-6: ScannerAiConfig.scannerChatClient 上不含 @Conditional 系列 build-time gate
  Given codebase 含 ScannerAiConfig.scannerChatClient @Bean factory method
  When  reflection 取該 method 的 annotations
  Then  不存在 @ConditionalOnProperty / @ConditionalOnExpression / @Conditional
  Note  防未來 PR 把 build-time condition 加回 — bake-out 再來一次

AC-7: LlmIntentOutput record 已被 @RegisterReflectionForBinding 涵蓋
  Given codebase 含 search/SearchNativeConfig
  When  reflection 取 @RegisterReflectionForBinding.classes() array
  Then  array 包含 SearchIntentService.LlmIntentOutput.class
  Note  防未來移除此 hint 後 LAB native runtime 重現 S148-twin

AC-8: Frontend graceful fallback 文案不退化（hostage check）
  Given 真實有 0 命中（query 太抽象，no match）
  When  /search?q=xyzqqqzzz 頁面 render
  Then  仍顯既有「這個描述還沒有匹配的技能」+ CTA
  Note  只測 backend fix 後 frontend 正確 render real result 而非 fallback empty state
```

驗證指令：
- 自動化：`cd backend && ./gradlew test`（per qa-strategy.md V01）
- LAB 手動：deploy 後直打 endpoint 跑 AC-1～AC-4（指令詳 §6.2）

---

## 5. Files to Change

| File | 變動 |
|---|---|
| `backend/.../search/SearchConfig.java` | F1：合併 2 個 @Bean 為單一 `embeddingModel` factory；移除 `@ConditionalOnProperty(api-key)` + `@ConditionalOnMissingBean`；body 內 branch real / NoOp |
| `backend/.../security/scan/ScannerAiConfig.java` | F2：移除 `@Conditional(LlmEnabledCondition.class)` × 2；移除 `LlmEnabledCondition` inner class；合併為單一 `scannerChatClient` factory；body 內 check `engines.llm.enabled` && `apiKey`；缺則 return null |
| `backend/.../search/SearchNativeConfig.java`（**新增**）| F3：`@RegisterReflectionForBinding({SearchIntentService.LlmIntentOutput.class})` mirror ScoreNativeConfig |
| `backend/.../security/scan/ScannerAiConfigTest.java` | 既有 4 種 condition 組合測試已不適用 — 改測 `scannerChatClient` factory 對 `engines.llm.enabled` × `apiKey` 4 種組合的回傳行為（null vs 真 ChatClient）|
| `backend/.../search/SearchConfigRegressionTest.java`（**新增**）| AC-5/AC-7：reflection assertion — `SearchConfig.embeddingModel` annotation set 不含 `@ConditionalOnProperty`；`SearchNativeConfig` `@RegisterReflectionForBinding.classes()` 包含 `LlmIntentOutput.class` |
| `backend/.../security/scan/ScannerAiConfigRegressionTest.java`（**新增 OR 併 ScannerAiConfigTest**）| AC-6：reflection assertion — `ScannerAiConfig.scannerChatClient` annotation set 不含 `@ConditionalOnProperty / @ConditionalOnExpression / @Conditional` |
| `backend/.../search/SemanticSearchIntegrationTest.java`（**新增**）| Testcontainers + pgvector + deterministic stub embedder：publish skill → assert vector_store row → assert similaritySearch 命中 |

不動的 file：`application-lab.yaml`、`application-aot.yaml`、`service.yaml`、frontend。

---

## 6. Test Plan

### 6.1 自動化

```bash
./gradlew test                                  # 全 backend
./gradlew test --tests "*SearchConfigRegression*"     # AC-5/AC-7
./gradlew test --tests "*ScannerAiConfig*"             # AC-6 + 4 種組合
./gradlew test --tests "*SemanticSearchIntegration*"  # AC-1 hermetic
```

`SemanticSearchIntegrationTest` 設計（per S168 retro 教訓「JVM test PASS ≠ native runtime PASS」）：
- pgvector container 真連 DB（不能用 H2 mock — vector ops 走 pgvector operator）
- deterministic stub embedder（per `E2EEmbeddingConfig` 已驗證 pattern；不打 Gemini）
- publish skill → wait async listener → SELECT vector_store row → similaritySearch
- 走真實 ApplicationContext，**不**單獨 unit test EmbeddingModel bean wiring（必須讓 @Bean factory 真跑過一次）

### 6.2 手動 LAB 驗證（per S168 AC-4 pattern）

deploy 修復版本後依序：

```bash
# 1. Intent endpoint 真實 concepts
curl -sS -X POST https://skillshub-644359853825.asia-east1.run.app/api/v1/search/intent \
  -H "Content-Type: application/json" -d '{"query":"terraform"}'
# expect: concepts.length > 0

# 2. Semantic search 英文 / 中文 query
curl -sS 'https://skillshub-644359853825.asia-east1.run.app/api/v1/search/semantic?q=terraform'
curl -sS 'https://skillshub-644359853825.asia-east1.run.app/api/v1/search/semantic?q=容器部署'
# expect: each ≥ 1 result

# 3. Startup log 確認
gcloud logging read 'resource.type=cloud_run_revision AND resource.labels.service_name=skillshub
  AND (textPayload:"No EmbeddingModel" OR textPayload:"ChatClient unavailable"
       OR textPayload:"Initialising GoogleGenAiTextEmbeddingModel")' \
  --project=cfh-vibe-lab --limit=10 --freshness=1h --format="value(textPayload)"
# expect: 0 筆 fallback WARN；≥ 1 筆 Initialising

# 4. Re-publish 3 筆 skill（手動，PublishPage 或 SQL DELETE FROM vector_store + UI re-upload）
#    之後 5 分鐘內第 2 步 search 都應命中

# 5.（可選）vector_store row 確認非零向量 — Cloud Console SQL Studio：
#    SELECT count(*),
#           sum(CASE WHEN embedding::text LIKE '%[0,0,0%' THEN 1 ELSE 0 END) AS zero_rows
#    FROM vector_store;
#    expect: count(*) = 3, zero_rows = 0
```

---

## 7. 風險與注意

| 風險 | 緩解 |
|---|---|
| Spring @Bean return null 偶見 reviewer 質疑寫法 | 引 Spring Framework Reference (Core / IoC Container / @Bean §「Returning null from a @Bean Method」) 為官方支援；同模式既有 LAB QualityJudgeConfig 用 throw fail-fast 不 return null — 若 reviewer 強烈反對改用 `@ConditionalOnProperty(engines.llm.enabled, matchIfMissing=true)` + factory throw if api-key blank（cost 仍 < 1hr） |
| Gemini API quota / cost | LAB throughput 低；單 search 兩次 API call（intent LLM + query embedding）；evaluation 後可加 rate limit 或 batch — 留 polish |
| 既有 NoOp 期間寫的零向量 row | deploy 後 re-publish 3 筆即可，`onVersionPublished` delete-then-add 自動覆蓋 |
| cosine threshold 0.3 對某些 query 仍嫌嚴 | 留 future polish spec；first ship 先確認功能 work；S140 e2e profile 已 override 為 0.0 證明設計可配 |
| `@RegisterReflectionForBinding` 對 nested record / List<String> 是否傳遞 | S148 v4.25.0 ship 已驗證 Spring framework 自動 traverse nested record；`LlmIntentOutput` 內 `List<String>` 走 Jackson primitive path 不需額外 hint |

---

## 7. Result（implementation 已 ship；LAB AC 待 deploy 驗證）

### 7.1 Backend implementation 完成（2026-05-12）

| Phase | File | 變動 |
|---|---|---|
| F1 | `search/SearchConfig.java` | 合併 2 個 @Bean 為單一 `embeddingModel(props)` factory；移除 `@ConditionalOnProperty(api-key)` + `@ConditionalOnMissingBean`；body 內依 `apiKey null/blank` branch real / NoOp |
| F2 | `security/scan/ScannerAiConfig.java` | 移除整個 `LlmEnabledCondition` inner class；合併為單一 `scannerChatClient(props)` factory；engine.enabled=false 或 api-key blank → return null（NullBean placeholder）|
| F2.1 | `security/scan/engines/LlmJudge.java` | 拿掉 `@Conditional(LlmEnabledCondition.class)`；constructor 改 `Optional<ChatClient>`；analyze() 加 empty Optional → 空 findings + notice graceful skip |
| F3 | `search/SearchNativeConfig.java`（**新檔**）| `@RegisterReflectionForBinding({LlmIntentOutput.class})` mirror ScoreNativeConfig — 防 native runtime `UnsupportedFeatureError` |

### 7.2 自動化測試結果

```
SearchConfigTest                 3 PASS（NoOp / blank / real branch）
ScannerAiConfigTest              5 PASS（engine×api-key 4 組合 + blank api-key）
SearchConfigRegressionTest       3 PASS（AC-5 / AC-6 / AC-7 reflection guard）
LlmJudgeTest                     既有 8 PASS（constructor 改 Optional 後仍綠）
search + security.scan 全包      ALL PASS
```

`./gradlew test --tests "io.github.samzhu.skillshub.search.*" --tests "io.github.samzhu.skillshub.security.scan.*"` BUILD SUCCESSFUL（1m 48s）

### 7.3 LAB 驗證 AC（2026-05-12 已驗）

User 確認 LAB deploy 後 AC-1~4/8 全 PASS（"S157 LAB已驗證成功"）：

| AC | 內容 | LAB 驗證結果 |
|---|---|---|
| AC-1 | terraform 搜尋命中 | ✅ `/api/v1/search/semantic?q=terraform` 回 ≥ 1 結果 |
| AC-2 | 中文 query 命中 | ✅ `?q=Terraform 安全稽核` 回 ≥ 1 結果 |
| AC-3 | intent 真實 concepts | ✅ `POST /search/intent` concepts > 0 |
| AC-4 | startup log 不警告 | ✅ 0 筆 "No EmbeddingModel" |
| AC-8 | 0 命中 fallback UX | ✅ 空狀態 CTA 顯示 |

### 7.4 不變數（regression guard）

AC-5/6/7 寫成 reflection-based test — 未來任何 PR：
- 把 `@ConditionalOnProperty(api-key)` 加回 `SearchConfig.embeddingModel` → 紅
- 把任何 `@Conditional*` 加回 `ScannerAiConfig.scannerChatClient` → 紅
- 把 `LlmIntentOutput.class` 從 `SearchNativeConfig` `@RegisterReflectionForBinding` 拿掉 → 紅

防覆轍 S148 / S157 / S168 同 family bug。

### 7.5 真實 Gemini fixture 整合測試（2026-05-12 跟拍補）

問題：S157 backend ship 後 LAB 才驗 AC-1~4/8，**JVM 本機沒 cross-semantic ranking 自動驗證**。
既有 `SemanticSearchIntegrationTest` 用 fixed-seed random vector（同函式 doc/query → cosine ≈ 1.0），
不能抓「query "browser automation" 該排到 agent-browser 不是 agent-memory」這類語意排序退化。

修法：預先 curl 真實 Gemini API 算 5 個 ClawHub agent skill + 3 個 query 的 768-d 向量 → SQL fixture + Java map，
進 git；test 用 fixture-lookup EmbeddingModel 不打 API，只在半年 maintenance refresh：

- `tools/fetch_embedding_fixture.sh` — Gemini API key + curl 算 5 doc + 3 query embedding → `/tmp/fixture-output.json`
- `tools/embedding_fixture_to_sql.py` — JSON → `embedding-fixture.sql` (INSERT vector_store) + `EmbeddingFixture.java` (Map<query, float[768]>)
- `SemanticSearchRealFixtureIT` — Spring 7 `@TestBean(name="mockEmbeddingModel")` 換掉 `TestcontainersConfiguration.mockEmbeddingModel`，避開 dual-@Primary；4/4 ACs PASS：

| AC | Query | Top match | Status |
|---|---|---|---|
| AC-real-1 | "browser automation and web scraping" | agent-browser | ✅ |
| AC-real-2 | "container deployment and process management" | agentic-devops | ✅ |
| AC-real-3 | "code security review" | agent-skills-audit | ✅ |
| AC-real-4 | vector_store schema 5 rows × 768-d | — | ✅ |

5 個 doc + 3 query 都是 ClawHub (github.com/VoltAgent/awesome-openclaw-skills) 真實 agent skill 內容。

Refresh 程序見 `EmbeddingFixture.java` javadoc（model 升級或半年）。

### 7.6 e2e V07 stabilization（2026-05-12 配套）

S157 IT 補完後跑 verify-all 發現 V07 既有 e2e flakiness（從未綠過的 happy-path race），
拖住 ship gate。一次根治 4 個互相疊加 bug，verify-all 從 7/8 PASS → 8/8 PASS：

1. **`TestDataController /reset` 30s timeout**：retry-5×200ms 撐不過 LLM judge phase；改 reset 前先 poll `event_publication.completion_date IS NULL` 排空（15s budget），等 AFTER_COMMIT async listener commit 完釋放 row lock，TRUNCATE 安全進場。
2. **AC-3 publish 30s timeout**：e2e profile 沒關 LlmJudge → 真打 Gemini 5-15s/scan 撐爆。`application-e2e.yaml` 加 `skillshub.scanner.engines.llm.enabled=false`，pattern scanner 100ms 即產 NONE/LOW risk_level。
3. **AC-1「找到 3 個」assertion fail**：`E2EEmbeddingConfig` stub 升級 word-overlap biased（每 token 在 `hashCode % dim` 加 +1 boost + 小 noise）；threshold 0.0 → 0.1；AC-1 query "docker" 對 3 個 docker-* skill 命中、其他 7 個被篩。
4. **profiles.* 重複 resetAll**：拿掉 `profiles.empty/single/paged` 內 resetAll，trust auto-fixture（省 30s budget 內的雙 drain wait）；AC-5 query 改英文 NL 與 paged seed 有 token overlap（中文 query 留 LAB Gemini 驗）。

| Verify Step | Before | After |
|---|---|---|
| V01 backend test | PASS | PASS |
| V03 coverage | PASS | PASS |
| V04-V06 frontend | PASS | PASS |
| **V07 e2e happy-path** | **FAIL (2-3 tests)** | **PASS (6/6)** |
| V08a processAot | PASS | PASS |
| V08b nativeBuild | PASS | PASS |
| Verdict | ❌ 1 FAIL | ✅ 8/8 |

### 7.7 Final Size Re-score (per estimation-scale.md)

| Dimension | Initial | Actual | Rationale |
|---|---|---|---|
| Tech risk | 2 | 2 | Mirror QualityJudgeConfig 既有 pattern；core fix 無意外 |
| Uncertainty | 2 | 2 | 規格清楚；後續 IT + V07 為 follow-up 新 scope，非原 spec uncertainty |
| Dependencies | 1 | 1 | unchanged |
| Scope | 1 | 3 | 4 backend files + 4 test infra files (tools/ + EmbeddingFixture + IT) + 6 V07 e2e fix files = ~14 files |
| Testing | 2 | 3 | 19 unit tests + 4-AC IT (real Gemini fixture) + Testcontainers pgvector + 6 e2e fix |
| Reversibility | 1 | 1 | unchanged |
| **Total** | **6 / XS→S** | **12 / M** | Bucket shift S→M；root cause: §7.5 IT regression（原 spec §6.1 註 defer 屬獨立 sub-spec）+ §7.6 V07 e2e stabilization（out-of-scope unblock ship gate） |

---

## 8. 相關 spec / 參考資料

- **S135a (v3.14.0)** — `QualityJudgeConfig` 是本 spec fix pattern 直接來源
- **S148 (v4.25.0)** — `@RegisterReflectionForBinding(JudgeResponse.class)`；S148 §2.3「為何 JVM 模式沒問題」是本 spec §2.3 機制解釋直接 precedent
- **S168 (v4.49.0)** — 「JVM test PASS ≠ native runtime PASS」教訓直接 inform §6.1 integration test 設計；reflection-based regression guard pattern inform AC-5～AC-7
- **S148b (v4.46.0)** — GraalVM AOT 驗證機制；`-PexactReachability=true` flag 可選 deploy-day 啟用 catch 未來 missing hint
- **architecture.md §GraalVM AOT Strategy** (`line 566+`) — production deploy mode 為 native image，本 spec §2 ground truth
- **/docs/semantic-search** — 既有 docs 頁；本 spec ship 後此頁內容真實可驗
