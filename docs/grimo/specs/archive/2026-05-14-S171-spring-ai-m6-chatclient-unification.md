# S171: Spring AI M6 model abstraction cleanup

> 規格：S171 | 大小：S(11) | 狀態：✅ dev complete
> 日期：2026-05-14
> 對應：PRD P3 自動風險評估、P5 語意搜尋、S135 Quality Score、architecture.md Framework Dependency Table

---

## 1. 目標

`QualityJudge`、`LlmJudge`、`SearchIntentService` 這些 chat 使用者要只吃 Spring AI 的 `ChatClient`；建立 / 替換 chat provider 時以 `ChatModel` 介面作為邊界；語意搜尋和 indexing 的向量生成要只吃 Spring AI 的 `EmbeddingModel`。底層 Gemini 怎麼建、未來要換哪個模型或 provider，集中在同一個 AI wiring config 裡處理。

`SkillshubPgVectorStore` 不在本 spec 的修改範圍。它是為了 `owner`、`skill_id`、`acl_entries` 欄位與 ACL-aware similarity SQL 做的客製 vector store；S171 只要求它繼續透過 `EmbeddingModel` 取得向量，不改它的 schema、SQL、builder 或 ACL 行為。

現在 code 看到的實體狀態：

```
QualityJudgeConfig.java:37   -> 直接 new com.google.genai.Client
ScannerAiConfig.java:66      -> 直接 new com.google.genai.Client
QualityJudge.java:24         -> constructor 吃 GoogleGenAiChatModel
LlmJudge.java:97             -> constructor 吃 Optional<ChatClient>
SearchIntentService.java:40  -> constructor 吃 Optional<ChatClient>
SearchConfig.java:65         -> 直接 new GoogleGenAiTextEmbeddingModel
SearchProjection.java        -> 注入 EmbeddingModel（這是正確抽象）
```

這個 spec 要把 provider-specific code 留在 infrastructure config 裡，讓業務 class 只看到 Spring AI 抽象介面：

| 能力 | Runtime 抽象 | Provider-specific 實作只允許在哪裡出現 |
|------|--------------|----------------------------------------|
| Chat / LLM judge / intent summary | `ChatClient`（use-case client）與 `ChatModel`（provider model） | `AiModelConfig` |
| Embedding generation / semantic search / vector indexing | `EmbeddingModel` | `AiModelConfig` |
| Vector persistence / ACL-aware similarity search | `SkillshubPgVectorStore` 客製 store，內部仍吃 `EmbeddingModel` | 不替換成官方 `PgVectorStore`；Google provider class 不出現在本 class |

Spring AI 2.0.0-M6 升級同 spec 完成，因為 M6 已在 2026-05-08 發布，且 release note 明確提到 Google GenAI options setter 移除、改用 builder pattern。builder pattern 是建立 concrete provider implementation 的正確寫法；但 Spring 容器和 runtime class 對外操作的型別要是 `ChatModel` / `ChatClient` / `EmbeddingModel` 這些 Spring AI 介面。

本 spec 不改 prompt、rubric、掃描規則、語意搜尋排序、`SkillshubPgVectorStore` ACL SQL，也不啟用 Spring AI auto-config。AOT/native image 的 `skillshub.genai.api-key` runtime resolve 問題仍用 manual config 解。

## 2. 研究與設計

### 2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
|------|----------|--------------|
| `backend/build.gradle.kts:33` | `springAiVersion` 目前是 `2.0.0-M5`，並引入 `spring-ai-google-genai`、`spring-ai-google-genai-embedding`、`spring-ai-client-chat`。 | S171 要把 BOM 升到 `2.0.0-M6`，不要 pin 個別 artifact version。 |
| `docs/grimo/architecture.md:540` | Framework table 仍寫 Spring AI `2.0.0-M5`，Google GenAI 是 direct API、不是 Vertex AI。 | S171 要同步 architecture doc 到 M6；仍保留 Google GenAI direct API wording。 |
| `backend/src/main/java/.../score/judge/QualityJudgeConfig.java:37` | Quality path 在 config 內直接建立 `com.google.genai.Client`，再建 `GoogleGenAiChatModel`。 | Google SDK client 建立應集中，不讓每個 feature config 自己處理。 |
| `backend/src/main/java/.../security/scan/ScannerAiConfig.java:66` | Scanner path 也直接建立 `com.google.genai.Client`，再包成 `ChatClient`。 | 兩條 Gemini chat wiring 重複，應改成共用 `ChatModel` factory + 具名 `ChatClient`。 |
| `backend/src/main/java/.../score/judge/QualityJudge.java:24` | Quality domain class constructor 吃 `GoogleGenAiChatModel`，再自己 `ChatClient.builder(gemini).build()`。 | 業務 class 應改吃 `ChatClient`，讓 provider 可抽換。 |
| `backend/src/main/java/.../security/scan/engines/LlmJudge.java:97` | Scanner LLM 已吃 `Optional<ChatClient>`，缺 AI credential 時回 notice，不擋掃描流程。 | 可保留 optional consumer 行為，只把 provider-specific factory 移走。 |
| `backend/src/main/java/.../search/SearchIntentService.java:40` | Search intent 也吃 `Optional<ChatClient>`；目前會拿到 scanner 的 generic `ChatClient` bean。 | S171 要避免「任一 ChatClient 都被 Optional 注入」造成用途混線，改用具名 client 或 `ObjectProvider`。 |
| `backend/src/main/java/.../search/SearchConfig.java:58` | Embedding path 已對 runtime 暴露 `EmbeddingModel`，但 provider-specific `GoogleGenAiTextEmbeddingModel` 建立放在 search module config。 | 抽象介面方向正確；provider factory 應搬到共用 AI config，search module 只消費 `EmbeddingModel`。 |
| `backend/src/main/java/.../search/SkillshubPgVectorStore.java:33` | 自訂 PgVectorStore 寫 `vector_store` 7 欄：`id, content, metadata, embedding, owner, skill_id, acl_entries`。 | 這是 ACL schema 需求，不是模型 provider 抽換問題；S171 不替換、不改 SQL。 |
| `backend/src/main/java/.../search/SkillshubPgVectorStore.java:113` | ACL 查詢用 `acl_entries ??| ?::text[]`，還有 published filter 與 oversample。 | 官方 VectorStore 抽象不能直接取代這段專案特有查詢；保留客製 store。 |
| [Spring AI 2.0.0-M6 release note](https://spring.io/blog/2026/05/08/spring-ai-1-0-7-1-1-6-2-0-0-M6-available-now/) | M6 已發布到 Maven Central；含安全修復；Google GenAI options mutable setter 移除，需用 builder/constructor。 | 升 M6 有安全與 API hygiene 理由；現有 builder 寫法與 M6 相容，但需驗 compile。 |
| [Spring AI ChatClient docs](https://docs.spring.io/spring-ai/reference/2.0/api/chatclient.html) | `ChatClient` 是 fluent API；可用 `ChatClient.builder(ChatModel)` 或 `ChatClient.create(ChatModel)`；多 model/multiple clients 時可手動建立多個 `ChatClient`。 | 以 `ChatModel` 做 provider port，以具名 `ChatClient` 做 use-case client。 |
| [Spring AI Google GenAI docs](https://docs.spring.io/spring-ai/reference/2.0/api/chat/google-genai-chat.html) | `GoogleGenAiChatModel` 是可注入的 chat model；manual config 仍要用 `com.google.genai.Client` 連 Google GenAI service。 | `com.google.genai.Client` 不是錯，但應只出現在 infrastructure factory，不出現在 judge/search/scanner runtime class。 |
| [Spring AI Chat Model API](https://docs.spring.io/spring-ai/reference/api/chatmodel.html) | 官方文件說 Chat Model API 是跨 AI model 的 portable interface，並定義 `ChatModel extends Model<Prompt, ChatResponse>`。 | chat provider builder 可以留在 config，但 bean return type 與業務 class dependency 要用 `ChatModel` / `ChatClient`。 |
| [Spring AI Embeddings Model API](https://docs.spring.io/spring-ai/reference/api/embeddings.html) | 官方文件定義 `EmbeddingModel extends Model<EmbeddingRequest, EmbeddingResponse>`，並提供 `embed(Document)` / `embed(String)` / batch embed。 | embedding provider builder 可以留在 config，但 search runtime dependency 要用 `EmbeddingModel`。 |
| [Spring AI Vector Databases API](https://docs.spring.io/spring-ai/reference/api/vectordbs.html) | 官方文件把 `VectorStore` / `VectorStoreRetriever` 當 vector database 抽象，並寫明 vector database 不產生 embedding，建立 embedding 要用 `EmbeddingModel`。 | S171 只調整模型抽象；專案因 ACL 需求保留 `SkillshubPgVectorStore`，不把它改成官方 `PgVectorStore`。 |
| [Spring AI M6 GitHub release](https://github.com/spring-projects/spring-ai/releases/tag/v2.0.0-M6) | M6 新增 `ChatModel.buildRequestPrompt` default method，並修 Google GenAI / embedding null-safety。 | 不設計自製 wrapper interface；直接重用 Spring AI `ChatModel` / `ChatClient`。 |
| `docs/grimo/development-standards.md:244` | S009 規範寫「Spring AI Manual Configuration，不混 auto-config 與 manual config」。 | S171 不啟用 provider auto-config；只用 Spring AI core API 手動建 bean。 |
| `docs/grimo/qa-strategy.md:39` | 標準驗證入口是 `./scripts/verify-all.sh`，V08a/V08b 會抓 AOT/native 類問題。 | AC 驗證用 project standard pipeline；AI wiring 額外加 targeted tests。 |

### 2.2 User journey simulation

| Journey | 現在可能發生什麼 | S171 後要看到什麼 |
|---------|------------------|-------------------|
| LAB 有 `skillshub.genai.api-key`，使用者上傳含 scripts skill | `ScanOrchestrator` 觸發 `LlmJudge`；Scanner 自己建 `Client`；Quality 另一份 config 也建 `Client`。 | `AiModelConfig` 建一個 Gemini `ChatModel`，再建 `scannerChatClient` / `qualityJudgeChatClient`；上傳流程照常寫入 risk assessment / skill score。 |
| 本機 dev 沒 api key，開頁面搜尋「容器部署」 | `SearchIntentService` 可能拿不到 `ChatClient` 後 fallback；semantic embedding 走 NoOp。 | `searchIntentChatClient` 缺席時仍 fallback；`EmbeddingModel` 走 NoOp bean；頁面可用，沒有 provider-specific exception。 |
| 未來把品質評分改成 OpenAI 或另一個 Gemini model | `QualityJudge` constructor 型別綁死 `GoogleGenAiChatModel`。 | 只改 config 的 `qualityJudgeChatClient` bean；`QualityJudge` 不改。 |
| 未來把 embedding 從 Google GenAI 換成 OpenAI / Vertex AI / local embedding | `SearchConfig` 目前直接建 `GoogleGenAiTextEmbeddingModel`。 | 只改 config 的 `EmbeddingModel` bean；`SearchProjection`、`SemanticSearchService`、`SkillshubPgVectorStore` 不改。 |

### 2.3 架構設計

採用「Spring AI model abstractions as ports」：

| Port | 用在哪裡 | 誰可以知道 provider class |
|------|----------|---------------------------|
| `ChatModel` | 建立 use-case `ChatClient`，是 chat provider 的抽象邊界 | 只有 `AiModelConfig` |
| `ChatClient` | `QualityJudge`、`LlmJudge`、`SearchIntentService` 發 prompt / structured output | runtime use-case class |
| `EmbeddingModel` | `SearchProjection` 建向量、`SemanticSearchService` 查向量、`SkillshubPgVectorStore` embed document/query | runtime search class |
| `SkillshubPgVectorStore` | 寫入 7 欄 `vector_store`、執行 ACL-aware similarity search | 本 class 可以知道 JDBC / pgvector / ACL SQL；不可以知道 Google provider class |

```
SkillshubProperties
  -> AiModelConfig
       -> chatModel(): ChatModel
          (method body uses Client.builder() + GoogleGenAiChatModel.builder())
       -> embeddingModel(): EmbeddingModel
          (method body uses GoogleGenAiTextEmbeddingModel provider class)
       -> qualityJudgeChatClient(ChatModel): ChatClient
       -> scannerChatClient(ChatModel): ChatClient | null
       -> searchIntentChatClient(ChatModel): ChatClient | null

QualityJudgeConfig
  -> qualityJudge(qualityJudgeChatClient): QualityJudge

QualityJudge(ChatClient qualityJudgeChatClient)
LlmJudge(Optional<ChatClient> scannerChatClient)
SearchIntentService(Optional<ChatClient> searchIntentChatClient)
SearchProjection(EmbeddingModel)
SemanticSearchService(EmbeddingModel via SkillshubPgVectorStore builder)
```

規則：

1. Provider-specific imports 只允許出現在 `AiModelConfig`：`com.google.genai.Client`、`GoogleGenAiChatModel`、`GoogleGenAiChatOptions`、`GoogleGenAiTextEmbeddingModel`、`GoogleGenAiEmbeddingConnectionDetails`、`GoogleGenAiTextEmbeddingOptions`。
2. production runtime class 不 import `GoogleGenAi*`，除 config 與 tests 外。
3. `ChatModel` 是 provider abstraction；`ChatClient` 是 use-case abstraction。
4. `EmbeddingModel` 是 embedding provider abstraction；search module 不知道 Google GenAI。
5. `qualityJudgeChatClient` 是 required use case：`skillshub.quality.judge.enabled=true` 且 api-key 缺時 fail-fast，維持 S135a outbox retry 設計。
6. `scannerChatClient` 與 `searchIntentChatClient` 是 optional use case：engine disabled 或 api-key 缺時 bean 不建立，consumer fallback。
7. `embeddingModel` 是 required infrastructure bean：有 api-key 走 real Google GenAI；無 api-key 走 NoOp 768 維零向量，維持 S157 現有 fallback。
8. 不使用 Spring AI model / ChatClient builder auto-config；base/test profile 明確 `spring.ai.model.chat: none`、`spring.ai.model.embedding.text: none` 與 `spring.ai.chat.client.enabled=false`，避免 auto-created model/client builder 混進 context。
9. `SkillshubPgVectorStore` 保留現況：不改官方 `PgVectorStore`、不改 `vector_store` schema、ACL SQL、per-request builder；只維持它 constructor / builder 吃 `EmbeddingModel` 介面。

### 2.4 做法比較

| 做法 | 採用 | 理由 |
|------|------|------|
| A. 保留現況，只把 M5 升 M6 | no | `QualityJudge` 仍綁 `GoogleGenAiChatModel`，`SearchConfig` 仍直接建 `GoogleGenAiTextEmbeddingModel`。升級只修版本，不修抽象邊界。 |
| B. 啟用 Spring AI Google GenAI auto-config，再注入 `ChatClient.Builder` / `EmbeddingModel` | no | 官方支援，但專案 S009/S157 已證明 AOT build-time property 與 Secret Manager runtime resolve 會讓 `api-key` gate 失真；官方文件也說多個手動 `ChatClient` 場景要設 `spring.ai.chat.client.enabled=false` 關掉 builder auto-config。 |
| C. 手動建立 Spring AI 抽象介面 beans：`ChatModel`、具名 `ChatClient`、`EmbeddingModel` | yes | 對齊 Spring AI portable API；builder pattern 只用在 config 內建立 provider implementation；runtime class 不知道 Google SDK；未來換 chat 或 embedding provider 都只改 config。 |

### 2.5 信心分類

| 決策 | 信心 | 依據 | POC |
|------|------|------|-----|
| Spring AI 升到 `2.0.0-M6` | Hypothesis | 官方 release 已發布，但本機 Gradle cache 只有 M5，尚未 compile。 | required：`./gradlew compileJava test --tests "*AiModelConfig*"` |
| `QualityJudge` 改吃 `ChatClient` | Validated | 現有 method 本來就是用 `client.prompt().call().entity(...)`；只改 constructor boundary。 | not required |
| `LlmJudge` / `SearchIntentService` 改用具名 optional `ChatClient` | Hypothesis | Spring Optional injection 對單一 bean 已驗證；具名 optional 需用 `ObjectProvider<ChatClient>` 或 wrapper factory 實測。 | required：context test 驗 no-key / key / disabled 三種組合 |
| `GoogleGenAiChatModel` 繼續 manual config | Validated | S135a/S157 已用 M5 ship；官方 M6 docs 仍保留 manual config 與 `com.google.genai.Client`。 | not required |
| `EmbeddingModel` 搬到共用 AI config，search module 只消費 interface | Validated | 現有 `SearchConfig.embeddingModel()` 對外型別已是 `EmbeddingModel`；`SearchProjection` / tests 已用 interface。 | not required |
| `SkillshubPgVectorStore` 不動 | Validated | code 與 architecture 已寫明 7 欄 table / ACL SQL 是專案需求；官方文件也把 embedding generation 交給 `EmbeddingModel`，vector store 負責儲存與查詢。 | not required |

### 2.6 Task 邊界提示

| Task 候選 | Class / file | 來源 | 正向情境 | 反向情境 | POC |
|-----------|--------------|------|----------|----------|-----|
| T01 | `backend/build.gradle.kts` | Spring AI M6 release | `springAiVersion=2.0.0-M6` 後 `compileJava` 通過 | 不 pin 單一 Spring AI artifact version | required |
| T02 | `shared/ai/AiModelConfig.java` | ChatClient docs + Google GenAI docs + existing SearchConfig | api-key 存在時提供 `ChatModel`、`EmbeddingModel`、3 個 named clients | api-key 缺時 optional chat clients 缺席、quality enabled fail-fast、embedding 回 NoOp | required |
| T03 | `score/judge/QualityJudge.java` / config tests | 現有 QualityJudge | constructor 改 `ChatClient` 後 `judgeImplementation` 仍呼叫 `.entity(JudgeResponse.class)` | production class 不 import `GoogleGenAiChatModel` | not required |
| T04 | `security/scan/engines/LlmJudge.java` / `search/SearchIntentService.java` | S157 optional fallback | scanner/search 各自只拿自己的 named client | scanner disabled 不影響 search intent fallback | required |
| T04b | `search/SearchConfig.java` / `shared/ai/AiModelConfig.java` | Existing `EmbeddingModel` interface | search module 不再 import Google embedding classes | no-key 仍回 768 維 NoOp vector；`SkillshubPgVectorStore` 不改 | not required |
| T05 | docs sync | architecture + dev standards | docs 顯示 Spring AI M6、manual config、ChatModel/ChatClient boundary | 不再說 `ScannerAiConfig` 是唯一 ChatClient factory | not required |

## 3. 驗收條件（SBE）

驗證命令：

執行：`./scripts/verify-all.sh`
通過條件：所有帶 `AC-S171-*` tag 或 display name 的測試都是綠燈，且 full verify exit code = 0。

| AC | 優先級 | 驗證方式 | 標題 |
|----|----------|--------|-------|
| AC-S171-1 | 必做 | Test | Spring AI BOM 升到 M6 且不 pin managed artifact |
| AC-S171-2 | 必做 | Test | Chat runtime class 只依賴 ChatClient，不依賴 Google provider classes |
| AC-S171-3 | 必做 | Test | Provider-specific Google classes 只出現在 AI config |
| AC-S171-4 | 必做 | Test | Quality judge 有 api-key 時可建立 named ChatClient，無 api-key 時 fail-fast |
| AC-S171-5 | 必做 | Test | Scanner / search intent 無 api-key 時 fallback，不擋應用啟動 |
| AC-S171-6 | 必做 | Test | Spring AI model / ChatClient auto-config 都關閉 |
| AC-S171-7 | 必做 | Inspection | architecture / development standards 同步 M6 與 wiring 原則 |
| AC-S171-8 | 必做 | Test | Embedding runtime class 只依賴 EmbeddingModel，不依賴 Google provider classes |
| AC-S171-9 | 必做 | Inspection | 客製 SkillshubPgVectorStore ACL 行為不被 S171 修改 |
| AC-S171-10 | 必做 | Test | `shared.ai` 是明確 Modulith named interface，跨模組引用走 `shared :: ai` |

**AC-S171-1: Spring AI BOM 升到 M6 且不 pin managed artifact**
- Given（前提）`backend/build.gradle.kts` 使用 Spring AI BOM 管理 `org.springframework.ai:*`
- When（動作）執行 `./gradlew dependencyManagement` 或 `./gradlew compileJava`
- Then（結果）`springAiVersion` 是 `2.0.0-M6`
- And（而且）`spring-ai-google-genai`、`spring-ai-google-genai-embedding`、`spring-ai-client-chat` 沒有個別 explicit version

**AC-S171-2: Chat runtime class 只依賴 ChatClient**
- Given（前提）production code 內有 `QualityJudge`、`LlmJudge`、`SearchIntentService`
- When（動作）執行 import scan
- Then（結果）這三個 class 只 import `org.springframework.ai.chat.client.ChatClient`
- And（而且）不 import `GoogleGenAiChatModel`、`GoogleGenAiChatOptions`、`com.google.genai.Client`

**AC-S171-3: Provider-specific Google classes 只出現在 AI config**
- Given（前提）production code 完成 S171 refactor
- When（動作）執行 `rg -n "com\\.google\\.genai\\.Client|GoogleGenAi(Chat|TextEmbedding|Embedding)|Client\\.builder\\(\\)" backend/src/main/java`
- Then（結果）provider-specific factory 命中只在 `AiModelConfig.java`
- And（而且）test fixtures 可以使用 Spring AI `ChatModel` stub，不需要 new Google SDK client

**AC-S171-4: Quality judge 有 api-key 時可建立 named ChatClient，無 api-key 時 fail-fast**
- Given（前提）`skillshub.quality.judge.enabled=true`
- When（動作）ApplicationContext 帶 `skillshub.genai.api-key=test-key` 啟動
- Then（結果）context 有 `qualityJudgeChatClient` bean，`QualityJudge` constructor 成功注入
- And（而且）ApplicationContext 不帶 api-key 啟動時失敗，錯誤訊息包含 `skillshub.genai.api-key`

**AC-S171-5: Scanner / search intent 無 api-key 時 fallback，不擋應用啟動**
- Given（前提）`skillshub.scanner.engines.llm.enabled=true` 且沒有 `skillshub.genai.api-key`
- When（動作）ApplicationContext 啟動並取得 `LlmJudge`、`SearchIntentService`
- Then（結果）context 啟動成功
- And（而且）`LlmJudge.analyze(...)` 回傳一筆 notice `LLM judge disabled`
- And（而且）`SearchIntentService.summarize("terraform")` 回傳 `summary="terraform"`、`concepts=[]`

**AC-S171-6: Spring AI model / ChatClient auto-config 都關閉**
- Given（前提）base 或 test profile 設 `spring.ai.model.chat=none`、`spring.ai.model.embedding.text=none` 與 `spring.ai.chat.client.enabled=false`
- When（動作）ApplicationContext 啟動
- Then（結果）不存在未命名的 auto-config `ChatClient.Builder` / `GoogleGenAiChatModel` / Google GenAI embedding bean 干擾具名 beans
- And（而且）所有 AI model beans 都由 `AiModelConfig` 建立或刻意缺席

**AC-S171-7: docs sync**
- Given（前提）S171 code 完成
- When（動作）查看 `architecture.md` 與 `development-standards.md`
- Then（結果）Framework Dependency Table 寫 Spring AI `2.0.0-M6`
- And（而且）Configuration Best Practices 寫明：manual config 仍保留，但 provider builder 只在 config layer；runtime code 使用 `ChatClient` / `ChatModel` / `EmbeddingModel` abstraction；Google provider classes 只在 config layer

**AC-S171-8: Embedding runtime class 只依賴 EmbeddingModel**
- Given（前提）production code 內有 `SearchProjection`、`SemanticSearchService`、`SkillshubPgVectorStore` 與原 `SearchConfig`
- When（動作）執行 import scan
- Then（結果）search runtime class 只 import `org.springframework.ai.embedding.EmbeddingModel`
- And（而且）不 import `GoogleGenAiTextEmbeddingModel`、`GoogleGenAiEmbeddingConnectionDetails`、`GoogleGenAiTextEmbeddingOptions`

**AC-S171-9: 客製 SkillshubPgVectorStore ACL 行為不被 S171 修改**
- Given（前提）`SkillshubPgVectorStore` 寫入 `vector_store` 7 欄，且 similarity search 包含 `acl_entries ??| ?::text[]`
- When（動作）完成 S171 refactor 並查看 diff
- Then（結果）`SkillshubPgVectorStore.java` 的 SQL、schema 欄位、per-request builder、ACL filter 沒有為了 S171 被修改
- And（而且）它仍透過 `EmbeddingModel` 取得 document/query embedding

**AC-S171-10: shared.ai Modulith named interface**
- Given（前提）`AiModelConfig` 放在 `shared.ai`
- When（動作）執行 `ApplicationModules.of(SkillshubApplication.class).verify()` 或 `./scripts/verify-all.sh`
- Then（結果）`shared.ai` 被公開為 `shared :: ai`
- And（而且）`search` / `security` 對 AI wiring 的相容 factory 引用不會讓 `processTestAot` 失敗

### 非功能需求檢查

| 分類 | 對應驗收 | 說明 |
|---|---|---|
| Performance | AC-S171-5, AC-S171-8, AC-S171-9 | 無 api-key / disabled path 不做 chat network call，應用可啟動；有 api-key path 不新增額外 LLM call；embedding 維持既有一段 `EmbeddingModel` path；ACL vector SQL 不改。 |
| Security | AC-S171-1, AC-S171-3 | M6 含安全修復；API key 仍只由 `SkillshubProperties` 注入到 config，不進 log、不傳到業務 class。 |
| Reliability | AC-S171-4, AC-S171-5, AC-S171-6 | required quality path fail-fast；optional scanner/search path fallback；embedding no-key NoOp；auto-config 不混入。 |
| Usability | N/A | 無使用者 UI 或 API response 變更。 |
| Maintainability | AC-S171-2, AC-S171-7, AC-S171-8, AC-S171-9 | provider-specific imports 集中，未來替換 chat 或 embedding provider 只改 config 與 docs；ACL vector store 維持專案客製責任。 |

## 4. 介面與 API 設計

### 4.1 新 config shape

```java
@Configuration
class AiModelConfig {

    @Bean
    @Nullable
    ChatModel chatModel(SkillshubProperties props) {
        if (isBlank(props.genai().apiKey())) {
            return null;
        }
        var client = Client.builder()
                .apiKey(props.genai().apiKey())
                .build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .defaultOptions(GoogleGenAiChatOptions.builder()
                        .model(GoogleGenAiChatModel.ChatModel.GEMINI_2_5_FLASH)
                        .temperature(0.0)
                        .build())
                .build();
    }

    @Bean("qualityJudgeChatClient")
    @ConditionalOnProperty(name = "skillshub.quality.judge.enabled", matchIfMissing = true)
    ChatClient qualityJudgeChatClient(SkillshubProperties props, ObjectProvider<ChatModel> chatModel) {
        var model = chatModel.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("skillshub.genai.api-key is required when quality judge is enabled");
        }
        return ChatClient.builder(model).build();
    }

    @Bean("scannerChatClient")
    @Nullable
    ChatClient scannerChatClient(SkillshubProperties props, ObjectProvider<ChatModel> chatModel) {
        if (!props.scanner().engines().llm().enabled()) return null;
        var model = chatModel.getIfAvailable();
        return model == null ? null : ChatClient.builder(model).build();
    }

    @Bean("searchIntentChatClient")
    @Nullable
    ChatClient searchIntentChatClient(ObjectProvider<ChatModel> chatModel) {
        var model = chatModel.getIfAvailable();
        return model == null ? null : ChatClient.builder(model).build();
    }

    @Bean
    EmbeddingModel embeddingModel(SkillshubProperties props) {
        if (isBlank(props.genai().apiKey())) {
            return new NoOpEmbeddingModel(props.genai().dimensions());
        }
        var connection = GoogleGenAiEmbeddingConnectionDetails.builder()
                .apiKey(props.genai().apiKey())
                .build();
        var options = GoogleGenAiTextEmbeddingOptions.builder()
                .model(props.genai().model())
                .dimensions(props.genai().dimensions())
                .build();
        return new GoogleGenAiTextEmbeddingModel(connection, options);
    }
}
```

注意：`@Bean return null` 是既有 S157 pattern。若 implementation POC 發現 named `Optional<ChatClient>` injection 對 NullBean 行為不穩，task 可改成 provider method 回 `Optional<ChatClient>` wrapper 或改用 `ObjectProvider<ChatClient>` constructor；驗收條件不變。

### 4.2 Consumer signatures

```java
public class QualityJudge {
    public QualityJudge(@Qualifier("qualityJudgeChatClient") ChatClient client) { ... }
}

class QualityJudgeConfig {
    @Bean
    @ConditionalOnProperty(name = "skillshub.quality.judge.enabled", matchIfMissing = true)
    QualityJudge qualityJudge(@Qualifier("qualityJudgeChatClient") ChatClient client) { ... }
}

public class LlmJudge implements SecurityAnalyzer {
    public LlmJudge(@Qualifier("scannerChatClient") Optional<ChatClient> chatClient) { ... }
}

public class SearchIntentService {
    public SearchIntentService(@Qualifier("searchIntentChatClient") Optional<ChatClient> chatClient) { ... }
}
```

若 `@Qualifier Optional<T>` 不符合 Spring injection 行為，implementation 用：

```java
public LlmJudge(@Qualifier("scannerChatClient") ObjectProvider<ChatClient> provider) {
    this.chatClient = Optional.ofNullable(provider.getIfAvailable());
}
```

### 4.3 Config keys

| Key | 行為 |
|-----|------|
| `skillshub.genai.api-key` | 唯一 Gemini API key 來源；有值才建 provider `ChatModel`。 |
| `skillshub.quality.judge.enabled` | `true` 時 quality client required；缺 api-key fail-fast。 |
| `skillshub.scanner.engines.llm.enabled` | `false` 時 scanner client 缺席，`LlmJudge` notice fallback。 |
| `spring.ai.model.chat` | base/test profile 保持 `none`，避免 provider chat model auto-config。 |
| `spring.ai.model.embedding.text` | base/test profile 保持 `none`，避免 provider embedding model auto-config。 |
| `spring.ai.chat.client.enabled` | base/test profile 設 `false`，避免 auto-config `ChatClient.Builder` 混入多 client 手動配置。 |

## 5. 檔案規劃

| 檔案 | 動作 | 說明 |
|------|--------|-------------|
| `backend/build.gradle.kts` | modify | `springAiVersion` M5 → M6；不 pin individual Spring AI artifacts。 |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/ai/AiModelConfig.java` | new | 集中 Google GenAI `ChatModel`、`EmbeddingModel` 與 named `ChatClient` beans。 |
| `backend/src/main/java/io/github/samzhu/skillshub/score/judge/QualityJudgeConfig.java` | modify | 移除 duplicated Google client/model factory；本檔縮小成只 wire `QualityJudge(@Qualifier("qualityJudgeChatClient") ChatClient)`，不 import Google provider types。 |
| `backend/src/main/java/io/github/samzhu/skillshub/score/judge/QualityJudge.java` | modify | constructor 改吃 `ChatClient`；移除 `GoogleGenAiChatModel` import。 |
| `backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScannerAiConfig.java` | delete/merge | 移除 scanner-only `Client.builder()`；scanner client 由 `AiModelConfig` 管。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SearchConfig.java` | modify/delete | 只移除 provider-specific embedding factory；NoOp embedding 可搬到 `AiModelConfig`，search module 只留 threshold / search service wiring。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SkillshubPgVectorStore.java` | no change | 客製 7 欄 vector_store / ACL-aware SQL / per-request builder 不動；只確認它仍吃 `EmbeddingModel`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/security/scan/engines/LlmJudge.java` | modify | 注入具名 scanner client，保留 optional fallback。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SearchIntentService.java` | modify | 注入具名 search intent client，避免拿到 scanner / quality client。 |
| `backend/src/test/java/.../shared/ai/AiModelConfigTest.java` | new | context runner 測 api-key / no-key / disabled / auto-config-off；涵蓋 chat + embedding。 |
| `backend/src/test/java/.../score/judge/QualityJudgeTest.java` | modify/new | 用 stub `ChatModel` 或 `ChatClient` 驗 constructor boundary。 |
| `backend/src/test/java/.../search/SearchConfigRegressionTest.java` | modify | AC-S171 import scan / auto-config regression。 |
| `backend/src/main/resources/application.yaml` | modify | 補 `spring.ai.model.chat: none`、`spring.ai.model.embedding.text: none`、`spring.ai.chat.client.enabled=false`，把 manual config 原則放進 packaged base config。 |
| `backend/src/test/resources/application.yaml` | modify if needed | 保持 / 補足 `spring.ai.model.chat: none`、`spring.ai.model.embedding.text: none`、`spring.ai.chat.client.enabled=false` 說明。 |
| `docs/grimo/architecture.md` | modify | Framework table M6；AI wiring principle。 |
| `docs/grimo/development-standards.md` | modify | S009 config rule 更新：manual config + ChatModel/ChatClient boundary。 |
| `docs/grimo/specs/spec-roadmap.md` | modify | 新增 S171 row。 |

## 6. Task Plan

### POC Findings

POC: required — §2.5 有兩個 implementation 前必須確認的假設：Spring AI `2.0.0-M6` 是否能在本專案解析與編譯、以及 `@Qualifier Optional<T>` 對具名 optional bean 的注入行為。

| POC | Command / fixture | Verdict | 結果 |
|-----|-------------------|---------|------|
| M6 compile | 暫時把 `backend/build.gradle.kts` 的 `springAiVersion` 改成 `2.0.0-M6`，執行 `cd backend && ./gradlew compileJava`，跑完還原回 M5 | PASS | `BUILD SUCCESSFUL in 7s`。M6 artifacts 可解析，現有 production code 至少能通過 `compileJava`。 |
| Qualified optional injection | 暫時新增 `OptionalQualifierPocTest`，用 `ApplicationContextRunner` 驗 `@Qualifier("scannerValue") Optional<String>` 在 named bean 缺席 / 存在兩種狀態；執行 `cd backend && ./gradlew test --tests "*OptionalQualifierPocTest" -x processTestAot`，跑完刪除 POC test | PASS | `BUILD SUCCESSFUL in 9s`。named bean 缺席時 consumer 拿到 `Optional.empty()`；named bean 存在時拿到指定值。 |

POC 注意：第一次未加 `-x processTestAot` 時，Gradle 先跑 `processTestAot` 並掃到整批 backend tests，啟動多個 Testcontainers；該次被手動終止，非設計失敗。正式 task 的驗證命令若只驗單一 context test，可明確加 `-x processTestAot`；full verify 仍由 `./scripts/verify-all.sh` 跑完整 V08a/V08b。

### Task Index

| Task | Status | AC | 做什麼 | 前置 |
|------|--------|----|--------|------|
| T01 | PASS | AC-S171-1, AC-S171-4, AC-S171-6 | 升 Spring AI M6，新增 `AiModelConfig`，集中 `ChatModel` / named `ChatClient` / `EmbeddingModel` manual beans，並用 context tests 驗 api-key / no-key / disabled / auto-config-off | POC PASS |
| T02 | PASS | AC-S171-2, AC-S171-4, AC-S171-5 | 改 chat consumers：`QualityJudge` 吃 `ChatClient`，`LlmJudge` / `SearchIntentService` 吃具名 optional clients，移除 scanner-only Google client factory | T01 PASS |
| T03 | PASS | AC-S171-3, AC-S171-8, AC-S171-9 | 把 embedding provider factory 從 search module 搬到 `AiModelConfig`，保留 `SkillshubPgVectorStore` 不動，補 provider import scan tests | T02 PASS |
| T04 | PASS | AC-S171-7, AC-S171-1..9 | 同步 architecture / development standards，收斂 regression tests 與 verify command，確保 roadmap/spec 狀態一致 | T03 PASS |
| T05 | PASS | AC-S171-3, AC-S171-10 | 修正 `shared.ai` Modulith 邊界：公開 `shared :: ai` named interface，讓 `SearchConfig` / `ScannerAiConfig` 的相容 factory 不再讓 `processTestAot` 失敗 | T04 PASS |

### E2E 判斷

E2E not required at task planning time — S171 改的是 backend wiring / Spring context / dependency version / docs，沒有 browser UI flow，也沒有新增 API response 或 DB schema。整合風險在 Spring context、AOT、native image；由 task context tests、`compileJava`、`./gradlew test`、`processAot` 與 final `./scripts/verify-all.sh` 覆蓋。

---

<!-- Section 7 added by /planning-tasks after implementation -->

## 7. Implementation Results

### 7.1 What Changed

- `backend/build.gradle.kts` 的 Spring AI BOM 升到 `2.0.0-M6`，未 pin 單一 Spring AI artifact 版本。
- 新增 `shared.ai.AiModelConfig`，集中建立 Spring AI `ChatModel`、具名 `ChatClient`（quality / scanner / search intent）與 `EmbeddingModel`；`com.google.genai.Client` 與 `GoogleGenAi*` provider classes 只留在此 config。
- `QualityJudge` 改吃 `ChatClient`；`LlmJudge` 與 `SearchIntentService` 改吃具名 optional `ChatClient`，無 api-key 時照既有 fallback。
- `SearchConfig` 不再建立 Google embedding provider；search runtime class 只依賴 `EmbeddingModel`。
- `SkillshubPgVectorStore` 未修改，`vector_store` 7 欄、`acl_entries ??| ?::text[]` ACL SQL、per-request builder 都保持原樣。
- `shared.ai` 新增 Modulith `@NamedInterface("ai")`，`search` / `security` 透過 `shared :: ai` 引用相容 factory，修掉 `processTestAot` 的 module boundary failure。
- `architecture.md`、`development-standards.md` 同步 Spring AI M6、manual config、`ChatModel` / `ChatClient` / `EmbeddingModel` 邊界與 `shared :: ai` 規則。

### 7.2 Verification

| Command | Result |
|---------|--------|
| `cd backend && ./gradlew test --tests "*AiModelConfigTest" -x processTestAot -x compileAotTestJava -x aotTestClasses` | PASS |
| `cd backend && ./gradlew test --tests "*QualityJudgeWiringTest" --tests "*LlmJudgeTest" --tests "*SearchIntentServiceTest" -x processTestAot -x compileAotTestJava -x aotTestClasses` | PASS |
| `cd backend && ./gradlew test --tests "*SearchConfigRegressionTest" --tests "*SearchConfigTest" --tests "*AiModelConfigTest" -x processTestAot -x compileAotTestJava -x aotTestClasses` | PASS |
| `cd backend && ./gradlew test --tests "*ModularityTests" -x processTestAot -x compileAotTestJava -x aotTestClasses` | PASS |
| `cd backend && ./gradlew processTestAot` | PASS |
| `./scripts/verify-all.sh` | PASS — V01=PASS, V02=INFO 84.2% line coverage, V03=PASS, V04=PASS, V05=PASS, V06=PASS, V07=PASS, V08a=PASS, V08b=PASS |

### 7.3 Notes

- V01/V03 初次 full verify 失敗點是 `processTestAot` 的 Modulith violation，不是 Spring AI M6 API、API key、或 `ChatClient` wiring 問題；T05 以 `shared :: ai` named interface 修正後 `processTestAot` 與 full verify 皆通過。
- Planning-tasks 要求的獨立 subagent QA 與本 session 的 developer rule（只有 user 明確要求 subagents 才能 spawn）衝突，因此本次未啟動 subagent；以 project standard `./scripts/verify-all.sh` 完整通過作為收尾證據。

### Final Size Re-score (per estimation-scale.md)

| Dimension | Initial | Actual | Rationale |
|---|---:|---:|---|
| Tech risk | 2 | 2 | Spring AI M6 API compile/test worked after POC；hidden issue was project Modulith boundary, not provider API breakage. |
| Uncertainty | 2 | 2 | User intent was clear: runtime classes use Spring AI interfaces, Google provider code stays in config. Implementation added one boundary clarification. |
| Dependencies | 2 | 2 | Still depends on shipped S135a/S157 behavior plus Spring AI / Google GenAI artifacts; no new external service dependency. |
| Scope | 2 | 3 | Actual work touched score, search, security, shared AI config, Modulith package boundaries, tests, docs, and roadmap. |
| Testing | 2 | 3 | Required targeted Spring context tests, Modulith verification, `processTestAot`, full `verify-all.sh`, Playwright gate, and boot image gate. |
| Reversibility | 1 | 2 | `shared :: ai` is now a named interface consumed by search/security; rollback requires coordinated module dependency updates. |
| **Total** | **11 / S** | **14 / M** | Bucket shift S→M; root cause: Spring AI refactor crossed module boundaries and AOT verification. |
