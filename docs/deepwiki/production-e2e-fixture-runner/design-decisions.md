# 設計決策與借鑑分析

## 關鍵設計決策

| # | 決策 | 理由 | 被否決的替代方案 |
|---|------|------|-----------------|
| 1 | E2E 測 production image，而不是 test-flavored app。 | `cloudbuild.yaml:95-97` 已是正式 image path；Spring Boot `bootBuildImage` 從 archive 建 image。測同一顆 image 才能避免「測試版通過、正式版不同」。 | 任何會產生 test-flavored app 的做法。 |
| 2 | 移除 production app 內的 `/internal/test/*` endpoint。 | `TestDataController.java:77-194` 能 reset/seed 資料；即使 profile 不啟用，class 在 production artifact 裡也不是最佳邊界。 | 用 `@Profile` 或 `@ConditionalOnProperty` 擋。 |
| 3 | Fixture runner CLI first，HTTP service later。 | CLI 無 port、攻擊面小；Playwright setup project 可用 subprocess。 | 直接做 private fixture REST service。 |
| 4 | Reset 優先用 disposable DB/schema。 | `backend/compose.yaml:15-16` 現在 dev DB 是 named volume；E2E 應用 throwaway DB 避免互踩與殘留。 | app endpoint truncate allowlist。 |
| 5 | Aggregate seed 優先走 production API。 | `SkillCommandController.java:95-127` + `SkillCommandService.java:105-165` 已定義正式 publish path。 | fixture runner 直接 INSERT skills/skill_versions。 |
| 6 | Projection seed 可 direct SQL，但只在 runner。 | `AnalyticsService.java:40-62` / `70-86` 直接讀 projection/counter；現有 `TestDataController.java:159-194` 也需要 insert event + update counter。 | 為 production app 新增 seed endpoint。 |
| 7 | Playwright 用 project dependencies 做 setup/teardown。 | Playwright 官方說 project dependencies 是推薦 setup/teardown 方式，report/trace/fixtures 整合較好。 | `globalSetup` 或每個 test auto reset。 |
| 8 | Deploy job 只能部署 production image tag。 | OWASP secure-by-default / deny-by-default；fixture image 即使存在，也不能出現在 deploy allowlist。 | 靠人工不要部署 `*-e2e*`。 |

---

## 已知挑戰與處理

### 1. Upload API 需要 auth context

`SkillCommandController.java:105-127` 從 `currentUserProvider.current()` 取 user。Fixture runner 若走 `POST /api/v1/skills/upload`，需要在 E2E env 提供 auth。

處理：

- Fast local E2E 可使用 dev/LAB-style auth，但仍不放 `/internal/test/*`。
- Release E2E 若要測 OAuth，setup project 先跑 mock IdP login，保存 storage state。
- Runner 若只要 seed backend state，可用 fixture-only DB user 建 users row，再用 API 或 controlled runner command 建 skill。

### 2. Semantic search deterministic data

`E2EEmbeddingConfig.java` 現在用 deterministic embedding 替代 Gemini。方案 D 不把它放 production app。

處理：

- Critical-path E2E 不 assert exact semantic ranking，只 assert UI contract / non-empty / fallback behavior。
- 需要固定 semantic hit 時，runner 可 direct SQL seed `skills.embedding` 欄位，因 S186 後 embedding 與 skills 同表。
- 真 Gemini ranking 留給 LAB/nightly，不放在 fast release gate。

### 3. Quality score deterministic data

`E2EQualityJudgeConfig.java:12-20` 現在提供 deterministic judge。方案 D 不把它放 production app。

處理：

- UI critical path 可 assert「評分計算中」或 runner direct SQL seed `skill_scores`。
- 真 LLM judge 放慢速 gate。

### 4. Parallel workers

`e2e/playwright.config.ts:14-19` 現在 `workers: 1`，因 reset/seed 共用狀態。

處理：

- 第一版仍 workers=1，但 setup project 只 seed 一次。
- 若要 parallel，fixture runner 為每個 project/shard 建獨立 DB/schema + manifest。

---

## 對 Skills Hub 的採用建議

### 直接採用

1. **Production artifact cleanliness gate**  
   對應 S202。新增 `assertProductionArtifactClean`，掃 jar/image 是否包含 `TestDataController` / `E2E*Config` / `application-e2e.yaml`。

2. **Playwright project dependencies**  
   對應 `e2e/playwright.config.ts:44-49` 的 projects。新增 setup/teardown projects，`chromium` depends on setup。

3. **Fixture manifest**  
   取代 `_fixtures.ts` 的 seed return values。`e2e/results/fixtures.json` 作為 Playwright tests 與 runner 的資料契約。

4. **Route absence test**  
   setup project 第一件事打 `POST /internal/test/reset`，必須 404。這比只掃 jar 更貼近 runtime。

### 概念可借鑑

1. **Disposable DB per E2E run**  
   初期可能先用 schema；長期最好一 run 一 DB/container。與 Docker/Testcontainers throwaway real services 方向一致。

2. **Fixture runner direct SQL for projections only**  
   Aggregate state 走 API，projection/counter 才 direct SQL。這保留 `SkillCommandService` 的 invariant。

3. **Release target 分層**  
   Fast E2E 可用 Vite dev server；release E2E 應測 `frontend/dist` copied into backend static，因 `cloudbuild.yaml:56-66` 正式包就是這樣。

### 不採用

| 設計 | 不採用原因 |
|------|-----------|
| test-flavored app | 雖可讓正式 jar 乾淨，但 Playwright 測的是另一個 app，不符合本輪「測 production image」目標。 |
| production app private test endpoint | 即使只 bind internal network，仍把 destructive operation 放進 app artifact。 |
| 每個 test auto reset | `e2e/tests/_fixtures.ts:138-145` 現況造成 state coupling；方案 D 改 setup seed + manifest。 |

---

## 建議實作路線

| Phase | 改哪些 file | 跑出什麼結果 |
|---|---|---|
| 1 | 新增 `docs/grimo/specs/S202` 正式設計；標註方案 D only | 團隊共識：只做 production app + external fixture runner/service。 |
| 2 | 從 production app 移除 `TestDataController` / `E2E*Config` / `application-e2e.yaml` | `jar tf build/libs/*.jar | rg 'TestDataController|E2E|application-e2e'` 無輸出。 |
| 3 | 新增 `e2e/fixtures` TypeScript runner skeleton | `cd e2e && npx playwright test --project "setup fixtures"` 會產 `results/fixtures.json`。 |
| 4 | Runner 支援 reset + seed profiles + manifest | `e2e/results/fixtures.json` 出現 seeded ids。 |
| 5 | Playwright config 改 project dependencies | setup project 先 seed，browser tests 讀 manifest。 |
| 6 | CI 加 production route absence + artifact scan | `/internal/test/reset` 回 404 才跑瀏覽器測試。 |
| 7 | Release E2E target 改測 production static assets | browser 測到與 Cloud Build 同形態的 app。 |

---

## 總結

方案 D 的核心不是「把 `TestDataController` 搬到別處」，而是把測試資料管理從 application runtime 拆出去。正式 image 只做正式產品能力；fixture runner 只在 E2E 環境存在；Playwright 透過 setup project 準備資料，browser tests 只驗證使用者會走到的 frontend/backend 路徑。
