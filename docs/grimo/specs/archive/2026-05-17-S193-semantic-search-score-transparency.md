# S193: Semantic Search Score Transparency

> 規格：S193 | 大小：XS(5) → S(11) | 狀態：✅ shipped v4.71.0
> 日期：2026-05-17
> 來源：Production browse search report — `/browse` 輸入「甜點」後回傳公開「產生字幕檔」
> 對應：S157 semantic search enablement / S177 search visibility / S186 skills.embedding 同表化 / S189 browse search entry point

---

## 1. 目標

`curl https://skillshub-644359853825.asia-east1.run.app/api/v1/search/semantic?q=%E7%94%9C%E9%BB%9E` 現在匿名回傳 `301f8ea4-d45c-4814-9c42-ac2c2a055f0a / 產生字幕檔 / score=0.5336340824852612`。`GET /api/v1/skills/301f8ea4-d45c-4814-9c42-ac2c2a055f0a` 回 `visibility: "PUBLIC"`。

這張 spec 要修的不是 S177/S186 的 visibility，也不是 cosine similarity 公式。公開 skill 對匿名使用者可讀、可被 semantic search 搜到是正確行為；Spring AI / pgvector 的原本語意也是「算出 similarity score，分數高的排前面」。POC 已確認本 repo 的 SQL、threshold、score mapping 與 Spring AI `PgVectorStore` M6 implementation 一致。因此 `甜點` 命中「產生字幕檔」可以接受，只要 `影片轉字幕`、`transcribe video` 這類真正相關 query 的分數更高、排序更前。

目標：

- 保留原本 similarity search 行為：只要分數過系統門檻即可顯示，結果按 score 高到低排序。
- 保留正向語意探索：`字幕`、`影片轉字幕`、`transcribe video` 仍能找到「產生字幕檔」。
- Production log 要能看出每次 semantic query 的 `resultsCount` 與 top hit score，下一次不用只靠 request URL 推理。
- `/browse` 需要讓使用者看得出語意排序分數，不把低分唯一結果誤解成「系統非常確定」。

不做：

- 不把 `/browse` 改回 keyword search。
- 不恢復 `/search` route 或 intent summary。
- 不做人工審核或個人化推薦。
- 不把 `甜點 -> 產生字幕檔` 視為必須回空的 bug。

## 2. 研究與設計

### 2.1 Production evidence

| 來源 | 看到什麼 | 判讀 |
|---|---|---|
| Chrome `@chrome`，正式站 `/browse` | 2026-05-17 15:16 左右重新輸入「甜點」時，畫面顯示 `找到 0 個相關技能`，未出現「產生字幕檔」。 | 當時 skill 尚未以匿名可讀路徑重現；後續使用者改公開後，改用匿名 API 直接重現。 |
| `gcloud logging read` request log | 2026-05-17 15:12:56、15:13:29 有 `/api/v1/search/semantic?q=甜點`；15:13:31 同一段 session 接著開 `/skills/301f8ea4-d45c-4814-9c42-ac2c2a055f0a`。 | 使用者回報與 Cloud Run request 序列吻合：搜尋「甜點」後進入字幕 skill detail。 |
| `curl https://.../api/v1/search/semantic?q=甜點` | 2026-05-17 15:xx 匿名呼叫回 `產生字幕檔`，`score=0.5336340824852612`。 | 問題已可用 public fixture 重現；0.5336 是調整 threshold / secondary guard 時的負例分數。 |
| `curl https://.../api/v1/skills/301f8ea4-d45c-4814-9c42-ac2c2a055f0a` | response 有 `visibility: "PUBLIC"`、`viewerPermissions.canView: true`。 | 不是 private visibility 問題；S193 鎖定 score transparency 與可觀測性。 |
| `SemanticSearchService` | SQL 用 `embedding <=> queryEmbedding < 1.0 - similarityThreshold`，預設 `skillshub.search.semantic-similarity-threshold=0.3`。 | `0.3` 是寬鬆門檻；短 query 可能因技能描述共通語意被納入結果。 |
| `curl` score sweep | `影片轉字幕=0.7636`、`transcribe video=0.7519`、`字幕=0.6857`、`甜點=0.5336`、`docker=0.5158`、`完全無關的香蕉蛋糕=0.4878`。 | 向量有方向性，正向 query 分數更高；排序邏輯可以沿用。 |
| `GET /api/v1/skills?page=0&size=50` | 匿名 catalog 目前只有 1 筆 public skill：`產生字幕檔`。 | 候選集只有 1 筆時，低分結果也會是第一名；這是資料量少造成的 UX 觀感，不是公式錯。 |
| Cloud Run stdout | 2026-05-17 15:11/15:23 logs 有 `Initialising GoogleGenAiTextEmbeddingModel (Manual Config, API key mode)`，未見 `No skillshub.genai.api-key configured`。 | production 沒走 NoOp zero vector；向量模型有初始化。 |
| SearchProjection logs | `SearchProjection onSkillCreated/onVersionPublished done skillId=301f8...`。 | skill 發布後有重建 embedding；目前不像 embedding stale 或未寫入。 |

這次 logs 不足以直接看到 response body、skill id、distance、score。S193 第一個修正點是讓後端把 top hit id/name/score 以 structured log 或等價可查格式寫出，才符合 Log-Driven Debugging；第二個修正點是確認 UI 有清楚顯示 score / match strength。

### 2.2 Existing code

| 檔案 | 現況 | S193 影響 |
|---|---|---|
| [backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java:32) | 從 `skills.embedding` 用 pgvector cosine distance 找 hits；`SkillSemanticHit.toResult()` 把 distance 轉 score。 | 加 relevance logging、測試 threshold 行為、調整 production threshold 或加 secondary guard。 |
| [frontend/src/pages/HomePage.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/HomePage.tsx:54) | 有搜尋字串就進 semantic mode；空結果顯示 semantic empty state；semantic result 會把 `score` 傳給 `SkillCard`。 | 不改 routing；只確認 semantic card 相符度呈現與分數排序。 |
| [docs/grimo/specs/2026-05-16-S189-browse-search-entry-point-verify-ship.md](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/specs/2026-05-16-S189-browse-search-entry-point-verify-ship.md:14) | S189 scope 是 `/browse` search entry point request contract。 | S193 與 S189 不重疊；S189 管打哪條 API，S193 管 semantic API 回哪些結果。 |

### 2.3 External references

| Source | 查到什麼 | 對 S193 的影響 |
|---|---|---|
| [pgvector README](https://github.com/pgvector/pgvector) | pgvector 支援 cosine distance；`<=>` 是 cosine distance；cosine similarity 可用 `1 - (embedding <=> query)` 算出。 | S193 要把 log 和測試都用「similarity score = 1 - distance」表示，避免用 distance 門檻時反向理解。 |
| [Spring AI SearchRequest Javadoc](https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/vectorstore/SearchRequest.html) | `similarityThreshold` 是 0.0 到 1.0；0.0 接受所有結果，1.0 只接受完全相同。 | `skillshub.search.semantic-similarity-threshold=0.3` 是寬鬆門檻；分數過門檻就顯示，分數高的排前面。 |
| [Spring AI Vector DB docs](https://docs.spring.io/spring-ai/reference/api/vectordbs.html) | threshold closer to 1.0 代表 similarity 越高；範例使用 `.similarityThreshold(0.7)`、`.similarityThreshold(0.75)`。 | UI/log 應顯示 similarity score，不直接顯示 distance；後續若調 threshold，必須用 fixture score 證明。 |
| [Spring AI PgVector docs](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html) | PgVectorStore default distance type 是 `COSINE_DISTANCE`。 | 現有 SQL `<=>` 與 Spring AI default PgVectorStore distance type 對齊。 |
| [Google Developers Blog — Gemini Embedding GA](https://developers.googleblog.com/en/gemini-embedding-available-gemini-api/) | `gemini-embedding-001` 支援 100+ 語言，可用於 retrieval / classification 等任務；也建議 3072/1536/768 output dimensions。 | 中文 query 本身不是不支援；false-positive 應該用 threshold / fixture calibration 修，不是退回 keyword-only。 |

### 2.4 POC — Spring AI PgVectorStore 算法比對

POC 指令：

```bash
cd /Users/samzhu/workspace/github-samzhu/skills-hub
rg -n "spring-ai.*pgvector|spring-ai.*vector|spring-ai" backend/build.gradle.kts backend/gradle.lockfile backend/settings.gradle.kts
javap -classpath ~/.gradle/caches/modules-2/files-2.1/org.springframework.ai/spring-ai-pgvector-store/2.0.0-M6/*/spring-ai-pgvector-store-2.0.0-M6.jar -c -p org.springframework.ai.vectorstore.pgvector.PgVectorStore
javap -classpath ~/.gradle/caches/modules-2/files-2.1/org.springframework.ai/spring-ai-pgvector-store/2.0.0-M6/*/spring-ai-pgvector-store-2.0.0-M6.jar -c -p 'org.springframework.ai.vectorstore.pgvector.PgVectorStore$PgDistanceType'
javap -classpath ~/.gradle/caches/modules-2/files-2.1/org.springframework.ai/spring-ai-pgvector-store/2.0.0-M6/*/spring-ai-pgvector-store-2.0.0-M6.jar -c -p 'org.springframework.ai.vectorstore.pgvector.PgVectorStore$DocumentRowMapper'
```

POC 結果：

| 檔案 / 類別 | 實際看到 | 對 S193 的判斷 |
|---|---|---|
| `backend/build.gradle.kts` | repo 使用 `springAiVersion = "2.0.0-M6"`，dependency 有 `spring-ai-pgvector-store`。 | POC 直接拆本專案實際使用的 Spring AI M6 jar。 |
| `PgVectorStore#doSimilaritySearch(SearchRequest)` | 先算 `1.0 - searchRequest.getSimilarityThreshold()`，再把 query embedding、threshold distance、topK 傳進 SQL。 | 現有 `SemanticSearchService` line 95 綁 `1.0d - similarityThreshold` 正確。 |
| `PgDistanceType.COSINE_DISTANCE` | SQL template 是 `SELECT *, embedding <=> ? AS distance FROM %s WHERE embedding <=> ? < ? %s ORDER BY distance LIMIT ?`。 | 現有 `SemanticSearchService` line 38/43/44 使用 `<=>`、`< ?`、`ORDER BY distance` 正確，且與 Spring AI implementation 一致。 |
| `PgVectorStore$DocumentRowMapper` | 從 result set 讀 `distance`，metadata 記 distance，response score 設為 `1.0 - distance`。 | 現有 `SkillSemanticHit.toResult()` line 35 回 `1.0d - distance` 正確。 |

結論：`甜點 -> 產生字幕檔 score=0.5336` 不是 Spring AI 公式接錯，也不是排序方向反了。正確算法就是：

```text
distance = embedding <=> queryEmbedding
score = 1.0 - distance
thresholdDistance = 1.0 - similarityThreshold
WHERE distance < thresholdDistance
ORDER BY distance ASC
LIMIT topK
```

換成 score 來看就是：

```text
WHERE score > similarityThreshold
ORDER BY score DESC
LIMIT topK
```

因此 S193 不改算法；只補 top-hit score log 與 UI 相符度顯示，讓低分唯一結果不被誤讀成高確定命中。

### 2.5 做法比較

| 做法 | 採用 | 實際行為 | 成本 / 風險 |
|---|---|---|---|
| A. 直接把 threshold 從 `0.3` 調高到固定值 | no | `甜點` 比較可能回空；相關 skill 也可能被濾掉。 | user 已確認「有中就顯示沒關係，分數高的會排在前面」，不應為單一低分命中硬調門檻。 |
| B. 加 keyword overlap guard | no | `甜點` 不會命中字幕；但 `容器部署` 找 `docker-compose-helper` 也可能被誤殺。 | 破壞 P5「不要求知道確切 skill 名稱」的語意搜尋定位。 |
| C. 保留原 threshold/公式，補 score logging + UI score visibility | yes | `甜點` 可以回字幕 skill，但使用者看得到分數；相關 query 分數更高、排序更前。 | 最小改動，符合 Spring AI 原始模型；後續資料量變多時排序自然改善。 |

採用 C。Implementation 可以在同一 spec 內完成：

1. Backend log：semantic query 完成時記 `query`、`resultsCount`、`topHitIds`、`topHitNames`、`topHitScores`。
2. Regression tests：證明正向 query score 高於弱相關 query，且結果按 score desc 排序。
3. UI 檢查：`SkillCard` semantic mode 要顯示相符度 / score，避免唯一低分結果看起來像高確定推薦。

## 3. 驗收條件（SBE）

驗證命令：

執行：`cd backend && ./gradlew test --tests '*SemanticSearch*'`
通過條件：S193 backend score ordering / logging tests 綠燈。

執行：`cd e2e && npx playwright test --grep @S193`
通過條件：瀏覽器在 `/browse` 搜尋時能看到相符度，且正向 query 的分數高於弱相關 query。

執行：`./scripts/verify-all.sh`
通過條件：V01/V03/V04/V05/V06/V07/V08a/V08b 全 PASS。

| AC | 優先級 | 驗證方式 | 標題 |
|---|---|---|---|
| AC-S193-1 | 必做 | Backend test | score 用 `1 - cosine distance` 計算 |
| AC-S193-2 | 必做 | Backend/log test | semantic search log 帶 top hit score |
| AC-S193-3 | 必做 | Backend fixture | 正向 query 分數高於弱相關 query |
| AC-S193-4 | 必做 | Backend fixture | response 按 score desc 排序 |
| AC-S193-5 | 必做 | Frontend/E2E | `/browse` semantic cards 顯示相符度 |
| AC-S193-6 | 必做 | Repo gate | `./scripts/verify-all.sh` exit=0 |

**AC-S193-1: score 用 `1 - cosine distance` 計算**
- Given（前提）DB 有 skill embedding 與 query embedding
- When（動作）呼叫 `GET /api/v1/search/semantic?q=...`
- Then（結果）response score 與 `1 - (embedding <=> queryEmbedding)` 一致

**AC-S193-2: semantic search log 帶 top hit score**
- Given（前提）semantic search 找到至少 1 個 hit
- When（動作）後端完成 query
- Then（結果）log 內可查到 query、resultsCount、topHitIds、topHitNames、topHitScores
- And（而且）沒有記錄使用者 token、cookie、email 或 request body

**AC-S193-3: 正向 query 分數高於弱相關 query**
- Given（前提）平台有 public skill `產生字幕檔`，description 是影片 / 音訊轉字幕用途
- When（動作）匿名使用者分別呼叫 `GET /api/v1/search/semantic?q=影片轉字幕` 與 `GET /api/v1/search/semantic?q=甜點`
- Then（結果）`影片轉字幕` 對該 skill 的 score 高於 `甜點`
- And（而且）兩個 query 都可以回傳該 skill

**AC-S193-4: response 按 score desc 排序**
- Given（前提）平台有多個 public skills，且 semantic query 對它們產生不同 score
- When（動作）匿名使用者呼叫 `GET /api/v1/search/semantic?q=影片轉字幕`
- Then（結果）response 第一筆 score 大於或等於第二筆 score
- And（而且）所有 response score 由高到低排序

**AC-S193-5: `/browse` semantic cards 顯示相符度**
- Given（前提）使用者開啟 `/browse`
- When（動作）在搜尋框輸入 `甜點`
- Then（結果）若畫面顯示 `產生字幕檔` card，card 上也顯示相符度 / score
- And（而且）該分數來自 semantic API response `score`

**AC-S193-6: repo gate 全綠**
- Given（前提）S193 implementation 完成
- When（動作）執行 `./scripts/verify-all.sh`
- Then（結果）所有既有 gate PASS，exit code 是 0

### 非功能需求檢查

| 分類 | 對應驗收 | 說明 |
|---|---|---|
| Performance | AC-S193-4 | response 仍由 SQL `ORDER BY distance LIMIT` 控制，不把所有 rows 拉回 Java 重排。 |
| Security | AC-S193-2 | log 只記 query / public skill summary / score，不記 token、cookie、email。 |
| Reliability | AC-S193-1, AC-S193-3 | 公式與分數關係固定，避免後續把 distance 當 similarity 反用。 |
| Usability | AC-S193-5 | 使用者看到卡片時也看到相符度，知道這是低分命中或高分命中。 |
| Maintainability | AC-S193-2 | 下次 production 搜尋問題能從 log 看到 hit score，不必只靠 request sequence 推理。 |

## 4. 介面與 API 設計

### 4.1 Runtime behavior

```text
GET /api/v1/search/semantic?q=甜點
  -> SemanticSearchService embed query
  -> SQL: WHERE embedding <=> queryEmbedding < (1.0 - threshold)
  -> map distance to score = 1.0 - distance
  -> log query + resultsCount + top hits
  -> return hits over threshold ordered by score desc
```

### 4.2 Config

Keep the existing property name:

```yaml
skillshub:
  search:
    semantic-similarity-threshold: <calibrated value>
```

S193 does not change this value by default. If a future task tunes it, implementation must record the chosen value and fixture evidence in this spec §7.

### 4.3 Logging shape

Preferred log fields:

```text
query="甜點"
principalCount=...
patternsCount=...
resultsCount=0
topHitIds=[]
topHitNames=[]
topHitScores=[]
message="ACL-aware semantic search 完成"
```

When there are hits:

```text
topHitIds=["301f8ea4-d45c-4814-9c42-ac2c2a055f0a"]
topHitNames=["產生字幕檔"]
topHitScores=[0.62]
```

Log top 3 only. The goal is debugging relevance, not dumping the whole result list.

## 5. 檔案規劃

| 檔案 | 動作 | 說明 |
|---|---|---|
| `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java` | modify | 加 top hit score logging；保留 SQL threshold path。 |
| `backend/src/test/java/io/github/samzhu/skillshub/search/*SemanticSearch*Test.java` | add/modify | 補 threshold 與 positive/negative fixture。 |
| `frontend/src/components/SkillCard.tsx` | inspect/modify | 確認 semantic mode score badge / 相符度呈現清楚。 |
| `e2e/tests/S193-semantic-search-score.spec.ts` | add | `/browse` semantic card 顯示相符度；正向 query 分數高於弱相關 query。 |
| `docs/grimo/specs/spec-roadmap.md` | modify | Active table 加 S193。 |
| `docs/grimo/specs/2026-05-17-S193-semantic-search-score-transparency.md` | update | implementation 後補 §6 task plan 與 §7 score evidence。 |

---

## 6. Task Plan

POC：not required for task creation — §7 已用本 repo 實際 `spring-ai-pgvector-store-2.0.0-M6.jar` 驗證 Spring AI PgVectorStore 的 threshold / distance / score 行為；S193 不引入新套件、不改 SQL 公式，只補可觀測性與 UI 證據。

| 順序 | Task file | AC | 狀態 | 驗證 |
|---:|---|---|---|---|
| 1 | `docs/grimo/tasks/2026-05-17-S193-T01-backend-score-logging.md` | AC-S193-1, AC-S193-2, AC-S193-3, AC-S193-4 | PASS（2026-05-17） | `cd backend && ./gradlew test --tests "*SemanticSearch*"` |
| 2 | `docs/grimo/tasks/2026-05-17-S193-T02-browse-score-visibility.md` | AC-S193-5, AC-S193-6 | PASS（2026-05-17） | `cd frontend && npm test -- SkillCard`；`cd e2e && npx playwright test --grep @S193`；`./scripts/verify-all.sh` |

執行順序：

- T01 先讓 backend response score / ordering / log evidence 可查。這一步完成後，production log 能直接看到 `resultsCount` 與 top hit scores，不必靠 request sequence 推理。
- T02 再確認 `/browse` 的卡片顯示 semantic API 回來的 score，並新增瀏覽器證據。現有 `SkillCard` 已有「XX% 相符」badge，task 要確認 assembled `/browse` path 真的把 semantic `score` 傳到 card。
- 不改 semantic search 算法、不調高 threshold、不加 keyword overlap guard；測試重點是 score 越高越接近、response 由高到低排序、低分命中在 UI 上有明確相符度。

## 7. 驗證結果

2026-05-17 POC 驗證完成：

- `javap` 拆 `spring-ai-pgvector-store-2.0.0-M6.jar`，`PgVectorStore#doSimilaritySearch` 會把 threshold 轉成 `1.0 - similarityThreshold`。
- `PgDistanceType.COSINE_DISTANCE` SQL template 使用 `embedding <=> ? AS distance`、`WHERE embedding <=> ? < ?`、`ORDER BY distance LIMIT ?`。
- `PgVectorStore$DocumentRowMapper` 把 response score 設為 `1.0 - distance`。
- 本 repo [SemanticSearchService.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java:38) 與 [SkillSemanticHit.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/search/SkillSemanticHit.java:35) 對齊上述行為。

Production score evidence：

| Query | `產生字幕檔` score |
|---|---:|
| `影片轉字幕` | `0.7635804392858635` |
| `transcribe video` | `0.7519397730971137` |
| `字幕` | `0.6856961761095407` |
| `甜點` | `0.5336340824852612` |
| `docker` | `0.5157523117656111` |
| `完全無關的香蕉蛋糕` | `0.48784846502004464` |

結論：score 越高越接近；排序從分數高到低正確。S193 已可進入開發。

2026-05-17 S193-T01 implementation result：

- [SemanticSearchService.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java) 保留既有 SQL / threshold / `score = 1 - distance` path，只在完成 log 加 `topHitIds`、`topHitNames`、`topHitScores`，限制前 3 筆。
- [SemanticSearchScoreMappingTest.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/test/java/io/github/samzhu/skillshub/search/SemanticSearchScoreMappingTest.java) 驗 AC-S193-1。
- [SemanticSearchFromSkillsTest.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/test/java/io/github/samzhu/skillshub/search/SemanticSearchFromSkillsTest.java) 驗 AC-S193-2 / AC-S193-3 / AC-S193-4。
- Red：`cd backend && ./gradlew test --tests "*SemanticSearch*"` → fail at AC-S193-2，log 尚未含 top-hit 欄位。
- Green：`cd backend && ./gradlew test --tests "*SemanticSearch*"` → PASS（13 tests）。

2026-05-17 S193-T02 implementation result：

- [SkillCard.test.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/components/SkillCard.test.tsx) 明確覆蓋 `AC-S193-5`：`score=0.873` 顯示 `87% 相符`。
- [HomePage.test.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/HomePage.test.tsx) 覆蓋 assembled `/browse` path：semantic API 回 `score=0.91`，畫面顯示 `91% 相符`，且沒有 `/api/v1/skills?keyword=dd`。
- [S193-semantic-search-score.spec.ts](/Users/samzhu/workspace/github-samzhu/skills-hub/e2e/tests/S193-semantic-search-score.spec.ts) 以 browser path 驗 `/browse` 輸入 `images and containers in CI` 後，畫面顯示 API score 對應的 `% 相符`，request log 只含 semantic API。
- Red：`cd e2e && npx playwright test --grep @S193` → fail，`Error: No tests found`。
- Green：`cd frontend && npm test -- SkillCard` → PASS（2 files / 11 tests）。
- Green：`cd frontend && npm test -- HomePage SkillCard` → PASS（3 files / 25 tests）。
- Green：`cd e2e && npx playwright test --grep @S193` → PASS（1 test）。

2026-05-17 local release gate：

- `./scripts/verify-all.sh` → PASS；V01=PASS、V02=INFO（LINE coverage 86.9%）、V03=PASS、V04=PASS、V05=PASS、V06=PASS、V07=PASS、V08a=PASS、V08b=PASS；`Verdict: ✅ all CRITICAL passed; exit=0`。
- S193 AC-S193-1~6 全部 local PASS；production deploy / production log evidence 不屬於本 dev-loop tick。

2026-05-17 shipping preflight：

- `./scripts/verify-all.sh` → PASS；V01=PASS、V02=INFO（LINE coverage 86.9%，covered=4735 / total=5451）、V03=PASS、V04=PASS、V05=PASS、V06=PASS、V07=PASS、V08a=PASS、V08b=PASS；`Verdict: ✅ all CRITICAL passed; exit=0`。
- Production deploy / production log evidence 不屬於本 dev-loop tick；本 release 只 ship local evidence 與文件歸檔。

### Final Size Re-score (per estimation-scale.md)

| Dimension | Initial | Actual | Rationale |
|---|---:|---:|---|
| Tech risk | 1 | 2 | 需要拆本 repo 實際使用的 `spring-ai-pgvector-store-2.0.0-M6.jar` 確認 threshold / distance / score 行為，避免把 cosine distance 當 similarity 反用。 |
| Uncertainty | 1 | 1 | user 已確認低分命中可顯示，需求收斂為 log + UI score transparency，不改 threshold / keyword guard。 |
| Dependencies | 1 | 3 | 行為依賴 S157 semantic search、S177 visibility、S186 same-row embedding，且與 S189 `/browse` entry point ordering 相關。 |
| Scope | 1 | 1 | production code 只改 semantic search logging；UI 已有 score badge，主要補 assembled path tests。 |
| Testing | 1 | 3 | 除 backend/frontend tests 外，新增 Playwright `/browse` browser path，並通過 full `verify-all.sh` 含 happy-path E2E、AOT、bootBuildImage。 |
| Reversibility | 1 | 1 | 無 schema / public API shape 破壞；可用一個 commit 回復 log 與 UI test coverage。 |
| **Total** | **5 / XS** | **11 / S** | Bucket shift XS→S；主要原因是 Spring AI score 行為確認、跨 spec semantic dependency、browser/full release gate testing。 |
