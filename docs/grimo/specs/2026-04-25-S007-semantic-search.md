# S007: 語意搜尋（Spring AI + Gemini + Firestore Vector）

> Spec: S007 | Size: M(14) | Status: ⏳ Design
> Date: 2026-04-25

---

## 1. Goal

讓使用者用自然語言描述需求，平台以語意相似度推薦最適合的技能。取代現有關鍵字搜尋，提供更直覺的探索體驗。

依賴 S001（✅ shipped）— 使用 `SkillCreatedEvent`、`SkillVersionPublishedEvent` 觸發 embedding 計算。

### 核心流程

```
使用者輸入「部署容器應用」
    │
    ▼
GET /api/v1/search/semantic?q=部署容器應用
    │
    ▼
SearchController → SemanticSearchService
    │
    ▼
EmbeddingModel.embed(query)              ← gemini-embedding-2, 768 dims
    │                                       TaskType: RETRIEVAL_QUERY
    ▼
VectorStore.similaritySearch(request)    ← FirestoreVectorStore (prod)
    │                                       SimpleVectorStore (dev/test)
    ▼
回傳 [{ skill metadata + relevance score }, ...]
```

### Embedding 觸發（event-driven）

```
SkillCreatedEvent ──▶ SearchProjection ──▶ embed(name + " " + description)
                                            TaskType: RETRIEVAL_DOCUMENT
                                        ──▶ VectorStore.add(document)

SkillVersionPublishedEvent ──▶ 同上（frontmatter 可能含更新的 description）
```

## 2. Approach

### 選擇

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| A: Spring AI VectorStore SPI + 自訂 FirestoreVectorStore | ✅ yes | 標準介面，可替換實作；Spring AI 提供 SimpleVectorStore 用於開發/測試 |
| B: 直接呼叫 Firestore SDK（不走 VectorStore） | no | 無法替換實作，測試需 Firestore emulator；失去 Spring AI 的 observability 整合 |
| C: 使用 MongoDB Atlas VectorStore | no | Firestore MongoDB wire protocol 不支援向量搜尋 |

### 技術決策

| 決策 | 選擇 | 理由 |
|------|------|------|
| Embedding 模型 | `gemini-embedding-2` | 最新 multimodal 模型，支援 128-3072 維度 |
| 維度 | 768 | Firestore 上限 2048，768 在品質與效能間平衡 |
| Spring AI 版本 | `2.0.0-M4` | 專案已配置，含 google-genai 新 starter |
| Embedding starter | `spring-ai-starter-model-google-genai-embedding` | 取代舊 vertex-ai-embedding，支援 API key + Vertex AI 兩種認證 |
| 距離度量 | COSINE | 文字 embedding 業界標準 |
| VectorStore 切換 | `@ConditionalOnProperty` | 生產用 Firestore，開發/CI 用 SimpleVectorStore |
| 搜尋端點 | `GET /api/v1/search/semantic?q=...` | 讀取操作用 GET，topK/threshold 由後端控制 |
| 前端 | 隱藏關鍵字搜尋，僅語意搜尋 | 關鍵字搜尋待確認 Firestore 支援後再規劃 |

### Challenges Considered

1. **Spring AI 2.0.0-M4 與 Spring Boot 4.0.6 相容性** — M4 的 parent POM 聲明 Spring Boot 4.1.0-M2，但 BOM 聲明不是硬性限制。**POC: required** — 驗證 auto-configuration 能正常啟動。
2. **`gemini-embedding-2` 不在 Spring AI enum** — 需透過 `spring.ai.google.genai.embedding.text.options.model=gemini-embedding-2` 手動設定 model name。
3. **Firestore 向量索引需預先建立** — 不是 runtime 操作，需 `gcloud` CLI 在部署時建立。文件化部署步驟。
4. **Firestore 向量搜尋不支援 MongoDB wire protocol** — 必須使用 Firestore 原生 SDK (`com.google.cloud:google-cloud-firestore`)，與 MongoDB driver CRUD 共存。
5. **現有 skills backfill** — 已上架的 skills 沒有 embedding。SearchProjection 啟動時需偵測並補建。

### POC: required

| Hypothesis | Why research can't answer | POC scope |
|---|---|---|
| Spring AI 2.0.0-M4 google-genai embedding starter 在 Boot 4.0.6 正常啟動 | M4 BOM 聲明 Boot 4.1.0-M2，可能有 class 相容性問題 | Boot app 加入 starter dependency，啟動確認 `EmbeddingModel` bean 建立 |
| `EmbeddingModel.embed("test")` 回傳 768 維 float[] | gemini-embedding-2 不在 enum，需手動設 model name | 呼叫 embed，驗證回傳維度 |
| FirestoreVectorStore 可與 MongoDB driver 共存 | 兩者都連 Firestore Enterprise，不同 protocol | Boot app 同時初始化兩個 client |

### 2.3 Research Citations

| Source | Summary |
|--------|---------|
| Spring AI source: `GoogleGenAiTextEmbeddingModelName.java` | Enum 含 `GEMINI_EMBEDDING_001`、`TEXT_EMBEDDING_004`；`gemini-embedding-2` 需手動設 model name |
| Spring AI source: `AbstractObservationVectorStore.java` | 自訂 VectorStore 的基底類別，提供 observability + builder pattern |
| Spring AI source: `SimpleVectorStore.java` | In-memory VectorStore，cosine similarity，可用於開發測試 |
| Spring AI source: `GoogleGenAiEmbeddingConnectionProperties.java` | 支援 API key 模式 (`api-key`) + Vertex AI 模式 (`project-id` + `location`)，auto-detect |
| Firestore Java SDK source: `Query.java` | `findNearest()` 4 個 overloads，支援 COSINE/EUCLIDEAN/DOT_PRODUCT，max 2048 dims |
| Google Cloud docs: Firestore vector search | 索引需 `gcloud firestore indexes composite create --field-config field-path=embedding,vector-config='{"dimension":"768","flat":"{}"}'` |
| Firestore SDK version | `3.27.0` 起支援完整 vector search API，專案 `3.31.6` 完全支援 |

## 3. SBE Acceptance Criteria

Verification command:

    Backend: cd backend && ./gradlew test
    Frontend: cd frontend && npx tsc --noEmit && npm run build

**AC-1: 語意搜尋回傳相關結果**

```
Given 平台有 skill "docker-compose-helper"（description: "管理 Docker Compose 多容器部署"）
  And skill "k8s-deployment"（description: "自動化 Kubernetes 部署流程"）
  And skill "unit-test-generator"（description: "產生 JUnit 單元測試"）
  And embeddings 已建立
When  GET /api/v1/search/semantic?q=我想把應用部署到容器環境
Then  回傳結果包含 docker-compose-helper 和 k8s-deployment
  And 結果按語意相關度排序（score 遞減）
  And 每筆包含 id, name, description, author, category, riskLevel, score
```

**AC-2: 無相關結果**

```
Given 平台有 skill "docker-compose-helper"
When  GET /api/v1/search/semantic?q=量子力學計算
Then  回傳空陣列（score 皆低於 threshold）
  And HTTP 200（不是 404）
```

**AC-3: Event-driven embedding 建立**

```
Given SearchProjection 已啟動
When  發佈 SkillCreatedEvent（name: "docker-helper", description: "管理 Docker 容器"）
Then  VectorStore 中新增一筆 document
  And embedding 為 768 維 float[]
  And document metadata 含 skillId, name, description, author, category
```

**AC-4: 版本更新時 embedding 同步更新**

```
Given VectorStore 中已有 skill "abc" 的 embedding
When  發佈 SkillVersionPublishedEvent（skillId: "abc", 新 frontmatter 含更新的 description）
Then  VectorStore 中 skill "abc" 的 embedding 更新為新 description 的向量
```

**AC-5: VectorStore 可替換**

```
Given application.yaml 設定 skillshub.search.vector-store=simple
When  應用啟動
Then  注入的 VectorStore bean 為 SimpleVectorStore
  And 語意搜尋功能正常運作（in-memory cosine similarity）
```

**AC-6: 前端語意搜尋**

```
Given 使用者在首頁
When  在搜尋框輸入「部署容器應用」並送出
Then  頁面顯示語意搜尋結果（SkillCard 列表）
  And 關鍵字搜尋與分類篩選已隱藏
```

## 4. Interface / API Design

### 4.1 REST API

```
GET /api/v1/search/semantic?q={query}
  → SearchController.semanticSearch(query)
  → SemanticSearchService.search(query)
  → VectorStore.similaritySearch(SearchRequest)
  → 回傳 List<SemanticSearchResult>

Response 200:
[
  {
    "id": "abc-123",
    "name": "docker-compose-helper",
    "description": "管理 Docker Compose 多容器部署",
    "author": "sam",
    "category": "DevOps",
    "latestVersion": "1.0.0",
    "riskLevel": "LOW",
    "downloadCount": 42,
    "score": 0.89
  },
  ...
]
```

### 4.2 Backend Interfaces

```java
// --- SearchController ---
@RestController
@RequestMapping("/api/v1/search")
class SearchController {
    private final SemanticSearchService searchService;

    @GetMapping("/semantic")
    List<SemanticSearchResult> semanticSearch(@RequestParam String q) {
        return searchService.search(q);
    }
}

// --- SemanticSearchResult ---
record SemanticSearchResult(
    String id, String name, String description,
    String author, String category,
    String latestVersion, String riskLevel,
    long downloadCount, double score
) {}

// --- SemanticSearchService ---
@Service
class SemanticSearchService {
    private final VectorStore vectorStore;

    List<SemanticSearchResult> search(String query) {
        var request = SearchRequest.builder()
            .query(query)
            .topK(10)
            .similarityThreshold(0.3)
            .build();
        return vectorStore.similaritySearch(request)
            .stream()
            .map(doc -> new SemanticSearchResult(
                doc.getMetadata().get("skillId").toString(),
                doc.getMetadata().get("name").toString(),
                // ... map remaining fields from metadata
                doc.getScore()
            ))
            .toList();
    }
}

// --- SearchProjection ---
@Component
class SearchProjection {
    private final VectorStore vectorStore;

    @EventListener
    void on(SkillCreatedEvent event) {
        var doc = Document.builder()
            .id(event.aggregateId().toString())
            .text(event.name() + " " + event.description())
            .metadata(Map.of(
                "skillId", event.aggregateId().toString(),
                "name", event.name(),
                "description", event.description(),
                "author", event.author(),
                "category", event.category()
            ))
            .build();
        vectorStore.add(List.of(doc));
    }

    @EventListener
    void on(SkillVersionPublishedEvent event) {
        // delete old embedding, re-add with updated metadata
        vectorStore.delete(List.of(event.aggregateId().toString()));
        // re-build document from event payload / read model
    }
}

// --- FirestoreVectorStore ---
class FirestoreVectorStore extends AbstractObservationVectorStore {
    private final Firestore firestore;
    private final String collectionName;

    @Override
    public void doAdd(List<Document> documents) {
        List<float[]> embeddings = this.embeddingModel.embed(
            documents, EmbeddingOptions.EMPTY, this.batchingStrategy);
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            float[] embedding = embeddings.get(i);
            Map<String, Object> data = new HashMap<>(doc.getMetadata());
            data.put("text", doc.getText());
            data.put("embedding", FieldValue.vector(toDoubleArray(embedding)));
            firestore.collection(collectionName)
                .document(doc.getId())
                .set(data).get();
        }
    }

    @Override
    public void doDelete(List<String> idList) {
        for (String id : idList) {
            firestore.collection(collectionName).document(id).delete().get();
        }
    }

    @Override
    public List<Document> doSimilaritySearch(SearchRequest request) {
        float[] queryEmbedding = this.embeddingModel.embed(request.getQuery());
        double[] queryVector = toDoubleArray(queryEmbedding);

        VectorQuerySnapshot snapshot = firestore.collection(collectionName)
            .findNearest(
                "embedding", queryVector, request.getTopK(),
                VectorQuery.DistanceMeasure.COSINE,
                VectorQueryOptions.newBuilder()
                    .setDistanceResultField("distance")
                    .build()
            ).get().get();

        return snapshot.getDocuments().stream()
            .map(doc -> Document.builder()
                .id(doc.getId())
                .text(doc.getString("text"))
                .metadata(extractMetadata(doc))
                .score(1.0 - doc.getDouble("distance")) // cosine distance → similarity
                .build())
            .filter(doc -> doc.getScore() >= request.getSimilarityThreshold())
            .toList();
    }
}

// --- SearchConfig ---
@Configuration
class SearchConfig {
    @Bean
    @ConditionalOnProperty(name = "skillshub.search.vector-store",
                           havingValue = "firestore")
    VectorStore firestoreVectorStore(Firestore firestore,
                                     EmbeddingModel embeddingModel) {
        return FirestoreVectorStore.builder(firestore, embeddingModel)
            .collectionName("skill_embeddings")
            .build();
    }

    @Bean
    @ConditionalOnProperty(name = "skillshub.search.vector-store",
                           havingValue = "simple", matchIfMissing = true)
    VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
```

### 4.3 Configuration

```yaml
# application.yaml
spring:
  ai:
    google:
      genai:
        embedding:
          project-id: ${GCP_PROJECT_ID:}
          location: ${GCP_LOCATION:us-central1}
          text:
            options:
              model: gemini-embedding-2
              dimensions: 768

skillshub:
  search:
    vector-store: ${SKILLSHUB_VECTOR_STORE:simple}
    collection: skill_embeddings
```

### 4.4 Firestore Vector Index (deployment)

```bash
gcloud firestore indexes composite create \
  --collection-group=skill_embeddings \
  --query-scope=COLLECTION \
  --field-config field-path=embedding,vector-config='{"dimension":"768","flat":"{}"}' \
  --database=skillshub
```

### 4.5 Frontend

```typescript
// api/search.ts — new
export function fetchSemanticSearch(query: string): Promise<SemanticSearchResult[]> {
  return apiFetch(`/search/semantic?q=${encodeURIComponent(query)}`)
}

// types/skill.ts — add
export interface SemanticSearchResult {
  id: string
  name: string
  description: string
  author: string
  category: string
  latestVersion: string | null
  riskLevel: RiskLevel | null
  downloadCount: number
  score: number
}

// hooks/useSemanticSearch.ts — new
export function useSemanticSearch(query: string) {
  return useQuery({
    queryKey: ['semantic-search', query],
    queryFn: () => fetchSemanticSearch(query),
    enabled: query.length > 0,
  })
}

// pages/HomePage.tsx — modify
// - SearchBar onChange → semantic search API
// - 隱藏 CategorySidebar
// - 隱藏分頁（語意搜尋回傳固定 topK 結果）
// - 結果顯示 score badge
```

## 5. File Plan

| # | File | Action | Description |
|---|------|--------|-------------|
| **Backend — search module** ||||
| 1 | `.../search/SearchController.java` | new | `GET /api/v1/search/semantic` endpoint |
| 2 | `.../search/SemanticSearchService.java` | new | 包裝 VectorStore.similaritySearch，映射結果 |
| 3 | `.../search/SemanticSearchResult.java` | new | 回應 record（skill metadata + score） |
| 4 | `.../search/SearchProjection.java` | new | `@EventListener` 消費 SkillCreated/VersionPublished → VectorStore.add |
| 5 | `.../search/FirestoreVectorStore.java` | new | 自訂 VectorStore，繼承 AbstractObservationVectorStore，用 Firestore native SDK |
| 6 | `.../search/SearchConfig.java` | new | `@ConditionalOnProperty` 切換 Firestore / SimpleVectorStore |
| 7 | `.../search/package-info.java` | modify | 更新 `allowedDependencies` |
| **Backend — config** ||||
| 8 | `backend/build.gradle.kts` | modify | 加入 `spring-ai-starter-model-google-genai-embedding`、`spring-ai-vector-store` |
| 9 | `backend/src/main/resources/application.yaml` | modify | 加入 Spring AI google-genai + vector store 配置 |
| **Frontend** ||||
| 10 | `frontend/src/api/search.ts` | new | `fetchSemanticSearch()` API client |
| 11 | `frontend/src/hooks/useSemanticSearch.ts` | new | TanStack Query hook |
| 12 | `frontend/src/types/skill.ts` | modify | 新增 `SemanticSearchResult` type |
| 13 | `frontend/src/pages/HomePage.tsx` | modify | 改用語意搜尋，隱藏關鍵字/分類篩選 |
| **Tests** ||||
| 14 | `.../search/SemanticSearchTest.java` | new | AC-1, AC-2 整合測試（用 SimpleVectorStore + mock EmbeddingModel） |
| 15 | `.../search/SearchProjectionTest.java` | new | AC-3, AC-4 event-driven embedding 測試 |
| 16 | `.../search/SearchConfigTest.java` | new | AC-5 VectorStore 切換測試 |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
