# S007: 語意搜尋（Spring AI + Gemini + Firestore Vector）

> Spec: S007 | Size: M(14) | Status: ⏳ Design
> Date: 2026-04-25

---

## 1. Goal

讓使用者用自然語言描述需求，AI 推薦語意相關的技能。這是 Critical Path P5 — 讓 Skills Hub 從「知道名字才找得到」進化到「描述需求就推薦」。

依賴 S001（✅ shipped）— 使用 `SkillCreatedEvent`、`SkillVersionPublishedEvent`。
標記為「非最優先」— P1-P4 完成後才實作。

## 2. Approach

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| A: Spring AI EmbeddingModel + Firestore findNearest() | ⭐ yes | PRD D9 決策。Spring AI 提供 EmbeddingModel 抽象，Vertex AI Gemini 提供 embedding。Firestore native SDK 的 findNearest() 做向量搜尋 |
| B: 直接用 Vertex AI SDK (不走 Spring AI) | no | 失去 Spring AI 的 EmbeddingModel 抽象，未來換模型需要重寫 |
| C: 用 MongoDB Atlas Vector Search | no | Firestore 不支援 MongoDB wire protocol 的向量搜尋 |

### Key Decisions

1. **Embedding 生成** — Spring AI `EmbeddingModel` + Vertex AI Gemini (`text-embedding-005`)。`@EventListener` on SkillCreated/SkillVersionPublished → 生成 embedding → 存入 Firestore。
2. **向量存儲** — Firestore native SDK。每個 skill 一筆 document，field `embedding` 存 float array (768-dim)。需要建 Firestore vector index。
3. **搜尋流程** — 使用者查詢 → Spring AI 生成 query embedding → Firestore `findNearest()` → 回傳 top-K skill IDs → 從 MongoDB read model 取完整資料。
4. **混合架構** — MongoDB driver 管 CRUD (skills, skill_versions collections)，Firestore native SDK 管 vector search。兩套連線。
5. **Frontend** — SearchBar 的 Keyword/Semantic toggle。Semantic 模式呼叫 `POST /api/v1/search/semantic`。
6. **測試** — Mock `EmbeddingModel` 回傳固定向量。Mock Firestore client。不需要實際 GCP credentials。

### POC: required

| Hypothesis | Why research can't answer | POC scope |
|---|---|---|
| Spring AI 2.0.0-M4 的 `EmbeddingModel` 能透過 Vertex AI 生成 Gemini embeddings | Spring AI M4 是 milestone release，API 可能有 breaking changes | 寫一個 test class，注入 `EmbeddingModel`，呼叫 `embed("test text")`，驗證回傳 float array |
| Firestore native SDK `findNearest()` 在 Java 25 上可用 | google-cloud-firestore 3.31.6 未明確標注 Java 25 相容 | 寫一個 test class，建立 Firestore client，呼叫 vector query |
| MongoDB driver + Firestore native SDK 可在同一 Spring Boot app 共存 | 兩者都連 Firestore Enterprise，但用不同 protocol | Boot app 啟動時兩個 client 都能初始化 |

### Challenges Considered

- **GCP credentials** — 開發/測試環境需要 Application Default Credentials。CI 需要 service account key。
- **Vector index** — Firestore 需要手動建立 vector index（無法自動建立）。部署時需要 setup 步驟。
- **Embedding 成本** — Gemini text-embedding-005: $0.00001/1K tokens (~$1-2/月 at MVP scale)。
- **Cold start** — 現有 skills 需要 backfill embeddings。可用 event replay 或 batch script。

### 2.3 Research Citations

- PRD D9 — "Spring AI + Gemini embedding + Firestore 原生向量搜尋（findNearest()）"
- PRD D14 — "混合：MongoDB 驅動（metadata/CRUD）+ 原生 SDK（向量搜尋）"
- [Spring AI Vertex AI Gemini](https://docs.spring.io/spring-ai/reference/api/embeddings/vertexai-embeddings-text.html) — `VertexAiTextEmbeddingModel` [needs POC validation]
- [Firestore findNearest()](https://cloud.google.com/firestore/docs/vector-search) — Vector search with `FieldValue.vector()` [needs POC validation]
- Architecture doc — `google-cloud-firestore:3.31.6`, `spring-ai-*:2.0.0-M4`, `google-cloud-vertexai:1.24.0`

## 3. SBE Acceptance Criteria

Verification command:

    Run: cd backend && ./gradlew test
    Pass: all tests carrying S007 AC ids are green.

---

**AC-1: 自然語言搜尋**

```
Given skills: docker-compose-helper (description: "Generate docker-compose files"),
      k8s-deployment (description: "Scaffold Kubernetes manifests")
And   embeddings have been generated for both skills
When  POST /api/v1/search/semantic { "query": "我想把應用部署到容器環境" }
Then  回傳 results sorted by similarity score
And   k8s-deployment 在結果中（語意匹配「部署到容器」）
```

**AC-2: Embedding 自動生成**

```
Given POST /api/v1/skills/upload creates a new skill
When  SkillCreated event fires
Then  SearchProjection 生成 embedding 並存入 Firestore vector store
```

**AC-3: 無相關結果**

```
Given 搜尋查詢與所有 skills 語意不相關
When  POST /api/v1/search/semantic { "query": "量子計算優化" }
Then  回傳空結果陣列
And   HTTP 200 (不是 404)
```

**AC-4: Frontend semantic 模式**

```
Given 使用者在搜尋框切換到「語意」模式
When  輸入「幫我寫單元測試」
Then  呼叫 POST /api/v1/search/semantic
And   顯示語意搜尋結果（含 similarity score）
```

## 4. Interface / API Design

### 4.1 Flow

```
User: "我想把應用部署到容器環境"
    │
    ▼
POST /api/v1/search/semantic { "query": "..." }
    │
    ▼
SemanticSearchService
    ├─ EmbeddingModel.embed(query) → float[768]
    ├�� Firestore.findNearest("skills", "embedding", queryVector, limit=10)
    └─ Map Firestore results → SkillReadModel from MongoDB
    │
    ▼
Response: [ { skill, similarityScore }, ... ]
```

### 4.2 API

```
POST /api/v1/search/semantic
  Request:  { "query": "我想把應用部署到容器環境", "limit": 10 }
  Response: 200 [
    { "skill": { ...SkillReadModel }, "score": 0.94 },
    { "skill": { ...SkillReadModel }, "score": 0.82 }
  ]
```

### 4.3 SearchProjection (embedding generation)

```java
@EventListener
void on(SkillCreatedEvent event) {
    var embedding = embeddingModel.embed(event.name() + " " + event.description());
    // Store to Firestore: collection "skills", doc id = event.aggregateId(), field "embedding"
    firestoreClient.collection("skills").document(event.aggregateId())
        .set(Map.of("embedding", FieldValue.vector(embedding), "name", event.name()));
}
```

## 5. File Plan

| # | File | Action | Description |
|---|------|--------|-------------|
| **search module** |||
| 1 | `.../search/SemanticSearchService.java` | new | EmbeddingModel + Firestore findNearest |
| 2 | `.../search/SearchProjection.java` | new | @EventListener → generate + store embeddings |
| 3 | `.../search/SearchController.java` | new | POST /api/v1/search/semantic |
| 4 | `.../search/SemanticSearchResult.java` | new | Record: skill + score |
| 5 | `.../search/package-info.java` | modify | Add dependencies |
| **Config** |||
| 6 | `application.yaml` | modify | Spring AI Vertex AI config |
| 7 | `build.gradle.kts` | modify | Add spring-ai-vertex-ai dependency if needed |
| **Frontend** |||
| 8 | `frontend/src/api/skills.ts` | modify | Add semanticSearch() |
| 9 | `frontend/src/pages/HomePage.tsx` | modify | Semantic mode toggle + results |
| **Tests** |||
| 10 | `.../search/SemanticSearchServiceTest.java` | new | Mock EmbeddingModel + Firestore |
