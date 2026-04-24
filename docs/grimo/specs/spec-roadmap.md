# Skills Hub — Spec Roadmap

## Milestone 0: Project Init (Foundation)
Goal: 建立完整的專案骨架，含 ES+CQRS 基礎設施，確保前後端 build + test 都能跑
Done when: S000 done, `./gradlew build` 成功，frontend dev server 可啟動

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S000 | Project Init — 前後端骨架、ES 基礎設施、Gradle 整合 | S(11) | none | 🔲 |

### S000: Project Init

**Description:** 在現有模板基礎上完成專案初始化：資料夾重命名（`skillshub/` → `backend/`）、建立 React 前端專案、整合 Gradle build、加入 Firestore MongoDB driver、建立 Spring Modulith module skeleton（含 ES+CQRS 基礎設施）、移除 htmx。

**Scope:**
- 重命名 `skillshub/` → `backend/`，更新 `settings.gradle.kts`
- 建立 `frontend/` React 專案（Vite 8 + React 19 + TypeScript 6 + Tailwind 4 + shadcn/ui + Beam）
- Gradle task 整合前端 build（`npm install` → `npm run build` → copy to static/）
- 加入 `spring-boot-starter-data-mongodb` 依賴
- 加入 `google-cloud-firestore` 依賴（for vector search）
- 移除 `htmx-spring-boot` 依賴
- 建立 `shared/events/` — `DomainEvent` 基底類別、`DomainEventRepository`（MongoDB 實作）
- 建立 Spring Modulith module packages（skill/command, skill/query, skill/domain, security, search, analytics, storage）
- 設定 `.gitignore`（node_modules, build artifacts）
- 驗證 `./gradlew build` 成功
- 驗證 frontend dev server `npm run dev` 可啟動

**SBE Acceptance Criteria:**
```
Scenario: Gradle build 成功
  Given 專案已完成初始化
  When 執行 cd backend && ./gradlew build
  Then build 成功，無錯誤
  And 產出的 jar 包含 static/ 目錄下的前端 build output

Scenario: Frontend dev server 啟動
  Given frontend/ 目錄已建立
  When 執行 cd frontend && npm run dev
  Then Vite dev server 在 localhost:5173 啟動
  And 頁面顯示基本的 React app

Scenario: Spring Modulith module 結構驗證
  Given 所有 module packages 已建立
  When 執行 Spring Modulith verify
  Then 無模組邊界違規

Scenario: Event Store 基礎設施可用
  Given DomainEvent 和 DomainEventRepository 已建立
  When 寫入一筆測試 event 到 domain_events collection
  Then 可成功讀回該 event
```

**Estimation:**
| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 1 | Known tech, template exists |
| Uncertainty | 2 | ES infra + Gradle frontend integration |
| Dependencies | 2 | npm + Gradle + MongoDB driver |
| Scope | 3 | ~15 files (configs, skeletons, ES infra, frontend scaffold) |
| Testing | 1 | Build + basic integration verification |
| Reversibility | 1 | Easy to redo |
| **Total** | **10** | **S** |

---

## Milestone 1: 技能瀏覽與搜尋 (Critical Path P1)
Goal: 使用者能在 Web 介面上瀏覽、關鍵字搜尋、篩選已上架的技能
Done when: S001-S002 all done

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S001 | Skill 領域模型 + Command/Query API (ES+CQRS) | M(13) | S000 | 🔲 |
| S002 | 技能瀏覽與搜尋 UI + 關鍵字搜尋 | M(12) | S001 | 🔲 |

### S001: Skill 領域模型 + Command/Query API (ES+CQRS)

**Description:** 建立 skill module 的 ES+CQRS 架構：Domain Events（SkillCreated, SkillVersionPublished）、Command Side（CreateSkillCommand, SkillCommandService）、Query Side（SkillReadModel, SkillProjection, SkillQueryService）。含 SKILL.md frontmatter 解析與驗證。

**SBE Acceptance Criteria:**
```
Scenario: 建立新 Skill（Command Side）
  Given 一個合法的 CreateSkillCommand（name, description, author, category）
  When POST /api/v1/skills
  Then domain_events collection 新增一筆 SkillCreated event
  And skills read model 同步建立對應 document
  And 回傳 201 Created，含 skill id

Scenario: 取得 Skill 詳情（Query Side）
  Given 已有一個 id 為 "abc123" 的 skill（由 projection 建構的 read model）
  When GET /api/v1/skills/abc123
  Then 回傳 200 + 完整 skill metadata（從 read model 讀取）

Scenario: SKILL.md frontmatter 驗證 — 成功
  Given 上傳的 SKILL.md 含合法 frontmatter（name, description）
  When 驗證引擎解析
  Then 回傳 valid + 解析後的 metadata

Scenario: SKILL.md frontmatter 驗證 — 失敗
  Given 上傳的 SKILL.md 缺少 name 欄位
  When 驗證引擎解析
  Then 回傳 invalid + 錯誤訊息 "Missing required field: name"

Scenario: Event Store 完整性
  Given 對同一 skill 執行多次 command（create, publish version）
  When 查詢 domain_events by aggregateId
  Then 回傳按 sequence 排序的完整事件流
```

**Estimation:**
| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 2 | ES+CQRS pattern implementation |
| Uncertainty | 2 | Event→projection mapping design |
| Dependencies | 2 | S000 ES infra, Firestore MongoDB driver |
| Scope | 3 | ~15 files (commands, events, projections, controllers, validator) |
| Testing | 2 | Integration test with Testcontainers |
| Reversibility | 1 | Easy |
| **Total** | **12** | **M** |

### S002: 技能瀏覽與搜尋 UI + 關鍵字搜尋

**Description:** 前端技能列表頁面（含搜尋框、分類篩選）、技能詳情頁面。後端關鍵字搜尋 API（name/description 匹配，從 read model 查詢）。

**SBE Acceptance Criteria:**
```
Scenario: 用關鍵字搜尋技能
  Given 平台上有 50 個已上架的 skills
  When 使用者在搜尋框輸入 "docker"
  Then 回傳所有 name 或 description 含 "docker" 的 skills
  And 結果按相關度排序
  And 每筆顯示 name、description、author、version、風險等級、下載次數

Scenario: 按分類篩選技能
  Given 平台上有多個分類（例如：DevOps、Testing、Documentation）
  When 使用者選擇 "DevOps" 分類
  Then 只顯示該分類下的 skills

Scenario: 技能詳情頁
  Given 使用者點擊某個 skill
  Then 顯示完整的 SKILL.md 內容（rendered markdown）
  And 顯示版本歷史、風險評估結果、下載統計
```

**Estimation:**
| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 1 | Standard React + REST |
| Uncertainty | 2 | UI design iteration |
| Dependencies | 2 | S001 Query API |
| Scope | 3 | ~12 files (pages, components, hooks, API client) |
| Testing | 2 | Component tests + API integration |
| Reversibility | 1 | Easy |
| **Total** | **11** | **S** |

---

## Milestone 2: 技能發佈流程 (Critical Path P2)
Goal: 技能作者能上傳 skill 資料夾（zip），經驗證後透過 ES 流程上架並管理版本
Done when: S003-S004 all done

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S003 | Skill 上傳 + 版本管理（GCS + ES 事件流） | M(14) | S001 | 🔲 |
| S004 | 技能發佈 UI（上傳、版本歷史） | S(10) | S002, S003 | 🔲 |

### S003: Skill 上傳 + 版本管理（GCS + ES 事件流）

**Description:** 實作 skill zip 上傳。Command Side 流程：接收 zip → 解壓驗證 SKILL.md → 存儲 zip 到 GCS（storage module）→ 發佈 SkillVersionPublished event → Projection 更新 read model。含 storage module 的 GCS 整合。

**SBE Acceptance Criteria:**
```
Scenario: 上傳合法的純 markdown skill
  Given 作者準備了一個 skill zip，僅含 SKILL.md（無 scripts/）
  When POST /api/v1/skills (multipart upload)
  Then domain_events 新增 SkillCreated + SkillVersionPublished events
  And zip 存儲到 GCS
  And read model 建立版本號 v1.0.0
  And 回傳 201 Created

Scenario: 上傳不合規的 skill
  Given zip 中缺少 SKILL.md
  When POST /api/v1/skills
  Then 回傳 400 Bad Request + 具體錯誤訊息
  And 不存儲到 GCS
  And 不產生任何 domain event

Scenario: 更新已有 skill 的版本
  Given 已有 v1.0.0
  When PUT /api/v1/skills/{id}/versions (multipart upload, version=1.1.0)
  Then domain_events 新增 SkillVersionPublished event
  And 舊版本 zip 仍存在於 GCS
  And read model 的 latestVersion 更新為 v1.1.0
```

**Estimation:**
| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 2 | GCS integration + ES event flow for upload |
| Uncertainty | 2 | Multipart upload + event atomicity |
| Dependencies | 2 | GCS, S001 ES infra |
| Scope | 3 | ~12 files (storage service, package service, commands, events) |
| Testing | 2 | Integration test with GCS emulator |
| Reversibility | 1 | Easy |
| **Total** | **12** | **M** |

### S004: 技能發佈 UI

**Description:** 前端發佈頁面：拖拽上傳 zip、顯示驗證結果、版本歷史管理。

**SBE Acceptance Criteria:**
```
Scenario: 上傳 skill zip
  Given 作者在發佈頁面
  When 拖拽或選取一個 skill zip 檔
  Then 顯示上傳進度
  And 上傳完成後顯示驗證結果（成功/失敗 + 詳細訊息）

Scenario: 查看版本歷史
  Given 一個有多個版本的 skill
  When 作者進入該 skill 的詳情頁
  Then 顯示所有版本列表（版本號、發佈時間、風險等級）
```

**Estimation:**
| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 1 | Standard file upload UI |
| Uncertainty | 2 | UX design for upload flow |
| Dependencies | 2 | S002 (UI base), S003 (upload API) |
| Scope | 2 | ~6 files |
| Testing | 2 | Component tests |
| Reversibility | 1 | Easy |
| **Total** | **10** | **S** |

---

## Milestone 3: 自動風險評估 (Critical Path P3)
Goal: 發佈時自動掃描技能內容，評估安全風險等級
Done when: S005 done

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S005 | 風險評估引擎（Event-driven）+ UI 顯示 | M(13) | S003 | 🔲 |

### S005: 風險評估引擎（Event-driven）+ UI 顯示

**Description:** security module 監聽 `SkillVersionPublished` event，自動觸發風險評估。掃描 skill zip 中的 scripts/，發佈 `SkillRiskAssessed` event。Projection 更新 read model 的 riskLevel。含社群回報（flag）— 發佈 `SkillFlagged` event。

**SBE Acceptance Criteria:**
```
Scenario: 純 markdown skill 的風險評估
  Given SkillVersionPublished event 發佈（skill 無 scripts/）
  When RiskAssessmentListener 處理
  Then 發佈 SkillRiskAssessed event（level=LOW）
  And read model 更新 riskLevel 為 LOW

Scenario: 含危險指令的 scripts
  Given SkillVersionPublished event 發佈（scripts/ 含 rm -rf）
  When RiskAssessmentListener 處理
  Then 發佈 SkillRiskAssessed event（level=HIGH, findings=[...]）
  And read model 更新 riskLevel 為 HIGH
  And findings 包含檔案、行號、指令

Scenario: 含外部依賴的 scripts
  Given scripts/ 中引用外部 URL
  When 風險評估引擎掃描
  Then findings 列出所有外部依賴 URL

Scenario: 社群回報
  Given 使用者發現某 skill 有安全疑慮
  When POST /api/v1/skills/{id}/flags
  Then domain_events 新增 SkillFlagged event
  And read model 的 flags collection 新增記錄
```

**Estimation:**
| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 2 | Pattern matching engine + event chain |
| Uncertainty | 2 | Detection patterns |
| Dependencies | 2 | S003 (event flow, GCS content access) |
| Scope | 3 | ~10 files (scanner, patterns, listener, flag, events) |
| Testing | 2 | Unit tests with known dangerous/safe samples |
| Reversibility | 1 | Easy |
| **Total** | **12** | **M** |

---

## Milestone 4: 一鍵安裝 — Web 下載 (Critical Path P4)
Goal: 使用者能從 Web 下載技能的 zip 檔
Done when: S006 done

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S006 | Skill 下載 API + UI（含 SkillDownloaded event） | S(10) | S003 | 🔲 |

### S006: Skill 下載 API + UI

**Description:** 從 GCS 取得 skill zip 並透過 API 回傳。下載時發佈 `SkillDownloaded` event → AnalyticsProjection 記錄。前端加入下載按鈕 + 安裝指引。

**SBE Acceptance Criteria:**
```
Scenario: 下載最新版本
  Given 使用者在某 skill 的詳情頁
  When 點擊「下載」按鈕
  Then 下載該 skill 最新版本的 zip 檔
  And domain_events 新增 SkillDownloaded event
  And read model downloadCount +1

Scenario: 下載指定版本
  Given 使用者選擇 v1.0.0
  When 點擊該版本的下載按鈕
  Then 下載 v1.0.0 的 zip 檔

Scenario: 下載頁面提供安裝指引
  Given 使用者下載了 skill zip
  Then 頁面顯示安裝說明

Scenario: 下載事件記錄
  Given 使用者下載了某 skill
  Then download_events read model 新增一筆記錄（由 AnalyticsProjection 處理）
```

**Estimation:**
| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 1 | Standard file serving |
| Uncertainty | 1 | Clear scope |
| Dependencies | 2 | S003 (GCS), event infra |
| Scope | 2 | ~6 files |
| Testing | 2 | Integration test |
| Reversibility | 1 | Easy |
| **Total** | **9** | **S** |

---

## Milestone 5: 語意搜尋 (Critical Path P5, 非最優先)
Goal: 使用者用自然語言描述需求，AI 推薦最適合的技能
Done when: S007 done

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S007 | 語意搜尋（Spring AI + Gemini + Firestore Vector） | L(15) | S001 | 🔲 |

### S007: 語意搜尋

**Description:** SearchProjection 監聽 SkillCreated / SkillVersionPublished events，透過 Spring AI 整合 Gemini embedding API 產生 embedding，存入 Firestore（原生 SDK）。搜尋時用 `findNearest()` 做向量搜尋。前端搜尋框支援「關鍵字」/「語意」切換。

**SBE Acceptance Criteria:**
```
Scenario: 自然語言搜尋
  Given 平台上有 "docker-compose-helper" 和 "k8s-deployment" 等 skills
  When 使用者輸入「我想把應用部署到容器環境」
  Then 回傳語意相關的 skills
  And 結果按語意相關度排序

Scenario: 任務導向推薦
  Given 使用者輸入「幫我寫單元測試」
  Then 推薦測試相關的 skills
  And 附上匹配理由

Scenario: 無相關結果
  Given 沒有匹配的 skill
  Then 顯示「未找到匹配的技能」
```

**Estimation:**
| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 3 | Firestore native SDK + MongoDB driver 混合 |
| Uncertainty | 2 | Embedding quality tuning |
| Dependencies | 2 | Spring AI, Gemini API, Firestore native SDK |
| Scope | 3 | ~8 files |
| Testing | 2 | Integration test with mock embeddings |
| Reversibility | 2 | Vector index migration |
| **Total** | **14** | **M** |

---

## Milestone 6: 使用數據分析 (Critical Path P6)
Goal: 追蹤技能下載/使用狀況，提供數據儀表板
Done when: S008 done

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S008 | 數據分析儀表板（Event-driven projection） | M(12) | S006 | 🔲 |

### S008: 數據分析儀表板

**Description:** AnalyticsProjection 消費 SkillDownloaded events，建構統計 read model。提供平台總覽（總 skill 數、總下載、熱門排行）和單一 skill 統計（下載趨勢圖）。前端儀表板頁面。

**SBE Acceptance Criteria:**
```
Scenario: 技能下載統計
  Given 某 skill 被下載了 150 次（150 筆 SkillDownloaded events）
  When 查看該 skill 的詳情頁
  Then 顯示總下載次數 150
  And 顯示近 7 天 / 30 天的下載趨勢圖

Scenario: 平台總覽儀表板
  Given 管理者進入數據分析頁面
  When 查看總覽
  Then 顯示：總 skill 數、總下載次數、本週新增 skills、熱門排行 Top 10

Scenario: 技能作者查看自己的數據
  Given 某作者發佈了 3 個 skills
  When 進入「我的技能」頁面
  Then 顯示每個 skill 的下載次數、趨勢
```

**Estimation:**
| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 1 | Standard aggregation |
| Uncertainty | 2 | Dashboard UI design |
| Dependencies | 2 | S006 (download events) |
| Scope | 3 | ~8 files |
| Testing | 2 | Integration + component tests |
| Reversibility | 1 | Easy |
| **Total** | **11** | **S** |

---

## Summary

| Milestone | Specs | Total Points | 累計 |
|-----------|-------|-------------|------|
| M0: Project Init | S000 | S(10) | 10 |
| M1: 技能瀏覽與搜尋 | S001, S002 | M(12) + S(11) = 23 | 33 |
| M2: 技能發佈流程 | S003, S004 | M(12) + S(10) = 22 | 55 |
| M3: 自動風險評估 | S005 | M(12) | 67 |
| M4: 一鍵安裝（Web 下載） | S006 | S(9) | 76 |
| M5: 語意搜尋 | S007 | M(14) | 90 |
| M6: 使用數據分析 | S008 | S(11) | 101 |

**Total: 9 specs, 101 story points**

### Dependency Graph

```
S000 ──▶ S001 ──▶ S002
              │
              ├──▶ S003 ──▶ S004
              │       │
              │       ├──▶ S005
              │       │
              │       └──▶ S006 ──▶ S008
              │
              └──▶ S007 (non-priority, after M4)
```

### Backlog (ES 進階功能)

| 優先級 | 功能 | 說明 |
|--------|------|------|
| ES-B1 | Event Replay | 從 domain_events 重建 read model |
| ES-B2 | Aggregate Snapshot | 定期快照 aggregate 狀態，加速載入 |
| ES-B3 | Event Upcasting | 事件 schema 版本遷移 |
| ES-B4 | Saga / Process Manager | 跨 aggregate 的長流程協調 |
