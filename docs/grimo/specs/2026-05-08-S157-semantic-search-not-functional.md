# S157: Semantic Search Not Functional in LAB — Hero Feature 實質失靈

> Spec: S157 | Size: M(8) | Status: 📐 in-design
> Date: 2026-05-08
> Origin: deployment audit 2026-05-08（LAB）— 平台主打「語意搜尋」hero feature，但實測 `/search?q=terraform` 對名稱明確含 `terraform` 的 skill 回 0 results。深掘：`/api/v1/search/intent` 回 fallback `{summary: query, concepts: []}`，per `frontend/src/api/search.ts` 註解，這代表 **LLM 未接 / 不可用**；連帶 query embedding 無法生成 → vector search 永遠空集合。

---

## 1. Goal

讓「語意搜尋」在 LAB 環境**真的能用**：搜「terraform」找到 `auditing-terraform-...`、搜「容器部署」找到 docker / k8s 類 skill。

**為什麼重要：**
- LandingPage hero 文案「在每個 AI 程式助理之間探索、發佈、治理 SKILL.md bundle」+ analytics page 顯「語意搜尋 — 自然語言描述需求，由 Gemini embedding + pgvector 比對技能 description」— **賣點明確指向 semantic search**
- /docs/semantic-search 整篇都在解釋此功能
- `/search?q=...` UI 完整（fallback CTA、撰寫技巧 link、graceful copy）但**沒實質結果**
- User 第一次登入跑去搜 → 0 結果 → 對平台價值打問號

**非目標：**
- 不重做語意搜尋 UI（已完整）
- 不調整 score threshold tuning（先讓功能 work，tuning 留 polish spec）
- 不解 GraalVM JudgeResponse reflection issue（屬 S148）

---

## 2. Root Cause Analysis

### 2.1 觀察到的症狀

```
GET /api/v1/search/semantic?q=terraform        → 200 []
GET /api/v1/search/semantic?q=test             → 200 []
POST /api/v1/search/intent  body={query:'terraform'}
                                                → 200 {"summary":"terraform","concepts":[]}
```

`concepts: []` 是 frontend / backend 共識 fallback signal — per `frontend/src/api/search.ts:21`：
> When LLM is unavailable, backend graceful-fallbacks to `{summary: query, concepts: []}`.

→ **LLM 沒接**。

### 2.2 假設樹（待驗證）

```
A. LAB profile 沒設 Gemini API key
   → ChatClient bean 失敗 fallback → empty concepts
B. Gemini 接好但 vector_store 沒 backfill
   → 每次 publish 該觸發 embedding 寫入 pgvector，
     但若沒實際跑 → vector search 永遠 0 row 可比
C. Cosine similarity threshold 太高（如 > 0.8）
   → 即使有 embedding，3 筆 skill 都被 reject
D. SkillshubPgVectorStore 自訂實作有 bug（per architecture
   doc，本平台 extends Spring AI AbstractObservationVectorStore
   寫了一個 pgvector wrapper）
```

### 2.3 驗證方式（spec implementer 第 1 步）

不確定哪個是 root cause，spec 不寫死「就是 A」。要求 implementer 先做 5 分鐘診斷：

1. `gcloud logging read` 找 ChatClient / Gemini / embedding 相關 ERROR/WARN
2. `psql` 查 `vector_store` 表有幾 row（有 → embedding 有寫；無 → publish flow 沒生 embedding）
3. 查 `application-lab.yaml` / Cloud Run 環境變數有沒有 `GOOGLE_API_KEY` / `spring.ai.google.genai.api-key`
4. 跑一次 publish（任意 skill）看 backend log 是否出現 embedding insert SQL

這 4 步應能在 5–10 分鐘鎖定 A/B/C/D 哪一個（或多個）。

### 2.4 修法（依診斷結果分支）

| 根因 | 修法 |
|------|------|
| A. Gemini API key 缺 | LAB profile 加 `spring.ai.google.genai.api-key` 配置；GCP Secret Manager 取 key（per architecture doc 用 `${sm@...}`）|
| B. Embedding 沒 backfill | 跑 backfill job（`SkillVersionPublishedEvent` async listener 觸發 — 確認 listener 註冊；對既有 3 筆 skill 手動觸發 re-embed）|
| C. Score threshold 太高 | 調整 `SkillshubPgVectorStore` 預設 cosine threshold（如 0.5 / 0.6） |
| D. PgVectorStore impl bug | 看 `SkillshubPgVectorStore` 的 `doSimilaritySearch()` 邏輯，補測試 |

預期：**A + B 同時成立** 機率最高（LAB 沒設 key + 沒 key 就連 publish-time embedding 也失敗）。

---

## 3. Acceptance Criteria

```
AC-1: 語意搜尋對「明顯命中」的字回 ≥ 1 結果
  Given 平台有 skill「auditing-terraform-infrastructure-for-security」
  When 使用者搜 q=terraform
  Then /api/v1/search/semantic?q=terraform 回 ≥ 1 結果
  And 結果第一筆 score > 0.5 且 skillId 對應 auditing-terraform-...

AC-2: 中文 query 也能命中英文 skill description
  Given 同上 skill（description 英文「Auditing Terraform...」）
  When 使用者搜 q=「Terraform 安全稽核」
  Then 回 ≥ 1 結果且 skillId 對應該 skill

AC-3: Intent endpoint 回真實 concepts
  Given /api/v1/search/intent body={query:'terraform 部署'}
  When LLM 接通
  Then concepts.length > 0（如 ['terraform','deployment','infrastructure']）
  And summary 是中文 1-sentence 改寫過的描述
  And 不再回 fallback shape {summary: query, concepts: []}

AC-4: vector_store 表有對應 row
  Given 平台 3 筆 skill 都已 publish
  When SQL: SELECT count(*) FROM vector_store
  Then ≥ 3（每個 skill version 至少 1 row embedding）

AC-5: 新 publish skill 自動觸發 embedding
  Given 任何人 publish 一筆新 skill
  When SkillVersionPublishedEvent 觸發 async listener
  Then ≤ 30 秒內 vector_store 出現對應 row
  And 後續搜尋能命中此 skill

AC-6: Frontend graceful fallback 文案保留
  Given 真實有 0 命中（query 太抽象，no match）
  When 頁面 render
  Then 仍顯示既有「這個描述還沒有匹配的技能」+ 3 個 CTA
  Note: 不改 frontend；只測 backend 真的有 result 時 frontend
        會 render 結果而非 fallback empty state
```

驗證指令：
- `cd backend && ./gradlew test`（per qa-strategy.md；新增 `SemanticSearchIntegrationTest` 走 Testcontainers + pgvector）
- 手動 LAB：deploy 後跑 AC-1/2 真實 query 確認

---

## 4. Files to Change

依診斷結果分支：

### 必動

| 檔案 | 變動 |
|------|------|
| `backend/src/main/resources/application-lab.yaml` | 加 `spring.ai.google.genai.api-key=${sm@gemini-api-key}` 等配置 |
| Cloud Run lab service config | GCP Secret Manager 加 `gemini-api-key` secret + service account 授權 |

### 可能動（依診斷）

| 檔案 | 變動 |
|------|------|
| `backend/src/main/java/.../skill/event/SkillVersionPublishedListener.java` | 確認 embedding 寫入 listener 已註冊；缺則加 |
| `backend/src/main/java/.../search/SkillshubPgVectorStore.java` | 調 cosine threshold；或修 doSimilaritySearch bug |
| `backend/src/main/java/.../search/EmbeddingBackfillJob.java`（新增） | 對既有 skill backfill embedding（含 idempotent guard）|

### Test

| 檔案 | 變動 |
|------|------|
| `backend/src/test/java/.../search/SemanticSearchIntegrationTest.java` | 新增 — Testcontainers postgres+pgvector，publish skill → assert vector_store row → assert search 命中 |

---

## 5. Test Plan

### 5.1 自動化（gradlew test + Testcontainers）

```java
@SpringBootTest
@Testcontainers
class SemanticSearchIntegrationTest {

    // pgvector container
    // mock Gemini embedding API → return deterministic vector

    @Test
    void publishSkill_thenSemanticSearch_returnsHit() {
        // 1. publish skill「terraform-tool」
        // 2. wait async listener → vector_store row created
        // 3. semantic search q="terraform" → assert hit
    }

    @Test
    void searchUnrelatedQuery_returnsEmpty() {
        // q="quantum mechanics" → empty (verifies threshold not too low)
    }
}
```

### 5.2 手動 LAB

deploy 後：
- [ ] `curl /api/v1/search/intent -d '{"query":"terraform"}'` → concepts.length > 0
- [ ] `/search?q=terraform` UI → 顯 result card（不再 0 results empty state）
- [ ] `/search?q=容器部署` → 中文也能 work
- [ ] publish 一筆新 skill → 30 秒內 search 該 skill 名稱可命中
- [ ] `psql -c "select count(*) from vector_store"` → ≥ 3

---

## 6. 風險與注意

| 風險 | 緩解 |
|------|------|
| Gemini API quota 用爆 | LAB 環境 throughput 低；正式上線前評估 quota；或加 rate limit on /search/* endpoints |
| Embedding cost（每 publish 都打 Gemini）| 監控 cost；考慮 batch embedding 或 cheaper model（gemini-embedding-001 比 chat 便宜 100x） |
| 搜尋結果 score threshold tuning | 留 polish spec；先 work 為主，threshold 預設 0.5（pgvector cosine distance < 0.5 = 接受） |
| 已 publish 的舊 skill 沒 embedding | backfill job 跑一次；之後新 publish 自動接 listener |

---

## 7. 相關 spec

- **S148**（GraalVM JudgeResponse reflection）— 不同 root cause（reflection metadata vs LLM config），但同屬「LAB AI features 沒 work」群；可同 sprint 處理
- **/docs/semantic-search**（既有 docs 頁）— 解釋此 feature；本 spec ship 後此頁內容真實可驗
