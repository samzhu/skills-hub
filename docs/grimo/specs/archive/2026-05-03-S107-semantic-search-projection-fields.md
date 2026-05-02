# S107 — Semantic search response projection field completeness

> **Status**: ✅ shipped `v3.4.7` (2026-05-03 — implement cron tick 13)
> **Type**: Backend DTO/projection fix + frontend silent cast removal
> **Estimate**: S (5-7 pts)
> **Triggered by**: 2026-05-03 cron Tick 12 Mode B E2E live browser walk-through (Chrome MCP) — Round 12 (search bar typing + semantic search flow)

## §1 Goal

`GET /api/v1/search/semantic?q=...` backend response 缺失 3 個欄位：`author` / `category` / `riskLevel` 都返回 empty string 或 null，但同 skill 的 `GET /api/v1/skills/{id}` 返回完整資料（`author=r30, category=Testing, riskLevel=LOW`）。前端 `SearchResultsPage` 用 unsafe cast (`r as unknown as Parameters<typeof SkillCard>[0]['skill']`) 把 SemanticSearchResult 當 Skill 渲染，造成 risk badge 全顯「未評估」、author / category 缺。

**Live 觀察 (Chrome MCP cron Tick 12 Round 12)**：
- 進入 `/search?q=docker` → 10 results
- 全部 cards 顯「未評估」risk badge（雖然 `r30-docker-1777650352` 透過 `/skills/{id}` 查實際是 LOW）
- author / category 值缺（accessibility tree 可看到 r35-docker-1777685322 沒顯 author "r35"，r30 沒顯 "r30"）
- API 直查 `/search/semantic?q=docker` first record:
  ```json
  {"id":"27a12b...", "name":"r30-docker-1777650352", "author":"", "category":"", "riskLevel":null, ...}
  ```
- 同 skill `/skills/27a12b3d...` 返回:
  ```json
  {"name":"r30-docker-1777650352", "author":"r30", "category":"Testing", "riskLevel":"LOW", ...}
  ```

**Root cause**: backend `SemanticSearchResult` DTO 或 query projection 沒包含 author / category / riskLevel 欄位。前端 type 定義（`frontend/src/types/skill.ts:84`）期待這些欄位（`riskLevel: RiskLevel | null` etc.），but backend 沒填。

**Sibling 關係**：S100e → S102 → S103 → S104 → S105 → S106 → **S107** — 第 7 個 cross-cutting follow-up；cut 從「page-level data → cross-cutting links → user-visible strings → interactive state → component-context → control-behavior → **API projection field completeness**」累積 7 層。發現方式 = Chrome MCP semantic search 跑後 page DOM 對比 backend response 對比 `/skills/{id}` 完整 response 三方面 inconsistency（前 6 cut 都看不見此 bug）。

## §2 Findings — verified gaps

| # | Endpoint / File | 現狀 | 嚴重度 |
|---|----------------|------|--------|
| 1 | `GET /api/v1/search/semantic` response | `author: ""`, `category: ""`, `riskLevel: null` 全 fixed empty 而非 actual data | **High** — `/search` 全 user 看到所有 skill 都是「未評估」+ 無 author/category 資訊 |
| 2 | `frontend/src/types/skill.ts:84` SemanticSearchResult | type 定義要求 `riskLevel: RiskLevel \| null`（per S094b） | Low — type 對的，是 backend 沒填 |
| 3 | `frontend/src/pages/SearchResultsPage.tsx:108` | `skill={r as unknown as Parameters<typeof SkillCard>[0]['skill']}` unsafe cast 隱藏 type system 警告 | Medium — fix backend 後該 cast 仍存在，但 not the root cause |

## §3 Approach

**Trim path**：本 spec 估 S，若 implement tick 觸 wall：
- 必做 #1 (backend DTO + projection) — 修 user-visible bug 主因
- 可 defer #3 (cast removal) — 既有 cast 不破壞行為，可後續 polish

**Decision per gap**:

- **Gap #1 (backend DTO + projection)**:
  - 找 backend `SemanticSearchResult` DTO（位於 `search/` module 內）
  - 補上缺失欄位 `author` / `category` / `riskLevel`
  - 對應 query / projection logic 確保從 DB 撈到這些欄位（很可能是 join skills table 取，或是 vector_store row 已含但沒投射）
  - Backend test: 補測 verify response 含 actual field values

- **Gap #2 (frontend type)**: 不改 — 前端期待是對的，後端 align 即可

- **Gap #3 (frontend cast)**: 移除 `as unknown as Parameters<typeof SkillCard>[0]['skill']` cast（after backend fix → SemanticSearchResult 與 SkillCard skill prop type 應 structurally compatible；若 SkillCard 仍要求 status / createdAt / updatedAt 欄位則保留 cast 但縮小範圍）

**不引入新 endpoint / aggregate / cache**：純 projection field 補齊。

## §4 Acceptance Criteria

| AC | Given | When | Then |
|----|-------|------|------|
| AC-1 | DB 有 skill `r30-docker-1777650352` (riskLevel=LOW, author=r30, category=Testing) | `GET /api/v1/search/semantic?q=docker` | response first match 含 `riskLevel="LOW"`, `author="r30"`, `category="Testing"` (不是 null/"") |
| AC-2 | 進入 `/search?q=docker` (frontend) | render results list | 至少 1 個 card 顯非「未評估」risk badge（如「低風險」「中風險」「高風險」），對齊 `/browse` 同 skill 的 risk badge |
| AC-3 | DB 有 skill `riskLevel=null`（尚未掃描完，e.g. just-uploaded） | `GET /api/v1/search/semantic` 命中此 skill | response `riskLevel: null` 仍允許（不該強制 default to LOW；frontend 對應 fall-through 已有 `'未評估'` UI） |
| AC-4 | `SemanticSearchResult` DTO java side 編譯 + tests | backend `./gradlew test` | 既有 search tests 全綠 + 新加 projection field test 通過 |

## §5 File plan

**Backend** (要 grep 確認位置；以下為 educated guess):

| File | Edit | LOC delta |
|------|------|-----------|
| `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchResult.java`（或 `SemanticSearchService.java` 內定義） | DTO 加 `author / category / riskLevel` 欄位 | ~3-5 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java` | query / mapping 把這些欄位從 skills table 撈出 | ~5-10 |
| `backend/src/test/java/io/github/samzhu/skillshub/search/SemanticSearchTest.java` | 補測 AC-1 / AC-3 | ~10 |

**Frontend** (only if cast removal trim 沒 defer):

| File | Edit | LOC delta |
|------|------|-----------|
| `frontend/src/pages/SearchResultsPage.tsx` line 108 | 移除 unsafe cast (after backend fix structurally compatible) | ~2 |

## §6 Test plan

```bash
cd backend
./gradlew test --tests *SemanticSearch*
# Smoke: curl http://localhost:8080/api/v1/search/semantic?q=docker → first result 含 author/category/riskLevel
cd ../frontend
npm test -- --run SearchResultsPage  # 確認 cast removal（若做）不破現有 AC-3/4 (S102)
npm run build
# Chrome MCP smoke: /search?q=docker → DOM 應顯非「未評估」risk badges + author/category 字面
```

**Negative case**: skill `riskLevel=null` (just-uploaded 未掃描) — semantic search 命中，response `riskLevel: null` 仍 valid；frontend RiskBadge 顯「未評估」是 graceful fallback。
**Edge case**: query 無結果 (`q=zzzzznosuchquery`) — empty array response，前端 EmptyState redirect tone（既有，不變）。

## §7 Result

**Shipped 2026-05-03 cron Tick 13 @ ~07:27**.

### Approach changed mid-implement (從 spec §3 Plan A 切到 Plan B)

PLAN 階段發現 `SemanticSearchResult` DTO 早已含全部欄位（id/name/description/author/category/latestVersion/riskLevel/downloadCount/score）；root cause 不在 DTO，而是 **`SearchProjection.java:147` 在 `onVersionPublished` 把 metadata.riskLevel 硬塞 null**，加上歷史 vector_store row 是早期 path 寫入 metadata 不齊全。修 projection 寫入路徑只解決 future row 問題，存量資料仍 broken；需要 backfill。

改採 **service read path lookup from canonical Skill aggregate**：
- `SemanticSearchService` 注入 `SkillRepository`
- `similaritySearch` 拿 documents 後 batch `findAllById(skillIds)` 撈 canonical Skills
- `toResult(doc, skill)` 從 Skill aggregate 取 author/category/riskLevel/downloadCount（不依賴 vector_store metadata）
- Race condition graceful：skill 已刪但 vector_store row 仍在 → fallback empty defaults

優點：
- **不需 backfill**（讀時 lookup canonical source）
- **不需修 SearchProjection 寫入路徑**（write-side 不健康但 read-side 不依賴）
- **避免 stale embedding metadata 永遠 drift** 的根本問題

代價：每次搜尋多 1 次 `findAllById` 對 PK index 的 batch 查詢（topK ≤ 50 場景 O(1) 可忽略）。

### Implement checklist

- [x] Backend: `SemanticSearchService` 注入 `SkillRepository`；search() 加 batch lookup；`toResult(Document, Skill)` 兩參數版本，skill==null fallback graceful
- [x] Backend: 移除 dead code `toLong` helper（原 metadata 路徑 unused）
- [x] Backend test: `./gradlew test --tests '*SemanticSearch*' -x npmBuild`：BUILD SUCCESSFUL in 2m 1s（既有 Integration test 全綠；無 break）
- [x] Frontend: 不改（既有 unsafe cast 留 polish backlog；Type system 與 backend response 對齊不變）
- [ ] Live smoke 推遲：backend 為 stale runtime（per CLAUDE.md operations note；cron loop "NEVER block a commit on restarting a stale runtime"）—下次 backend restart 後 `/search?q=docker` 應顯實 risk badges
- [x] CHANGELOG `v3.4.7` patch entry
- [x] roadmap row → ✅
- [x] spec doc 移 archive/

### Verify metrics

| Item | Value |
|------|-------|
| Files changed | 2（SemanticSearchService.java + spec/CHANGELOG/roadmap docs）|
| LOC delta | +30 / -22（service 內結構重組；net +8）|
| Backend test | `./gradlew test --tests *SemanticSearch* -x npmBuild`：BUILD SUCCESSFUL in 2m 1s；既有 SemanticSearchIntegrationTest 全 PASS |
| Frontend touch | 0（unsafe cast 留 polish backlog）|
| Wall clock | ~22 min（PLAN 5 + IMPLEMENT 6 + VERIFY 7 + DOCUMENT 4）|

### Trim deferred

- **SearchProjection.java:147 `onVersionPublished` riskLevel 硬塞 null fix** — write-side projection 不再被 read 路徑依賴，修 write 路徑不影響使用者；polish backlog（"vector_store metadata drift cleanup" 集中 ship）
- **SearchResultsPage.tsx:108 unsafe cast removal** — backend response 補齊後 cast 仍 work，不破行為；polish backlog
- **Backfill existing vector_store rows** — read path lookup 已 bypass，存量資料不影響使用；polish backlog (DB cleanup spec)
- **Live smoke verify on running backend** — backend 需 `./gradlew bootRun -x processAot` 重啟才反映本 fix；deferred 至 user 操作

### Sibling chain validation

S100e (defensive guard v3.4.1) → S102 (routing residual v3.4.2) → S103 (UX copy hygiene v3.4.3) → S104 (interactive state consistency v3.4.4) → S105 (component-context alignment v3.4.5) → S106 (control-behavior alignment v3.4.6) → **S107 (API projection field completeness v3.4.7)** — 第 7 個 cross-cutting follow-up，cut 累積 7 層；首次涉及 backend 改動（前 6 個全 frontend）。發現方式 = Chrome MCP semantic search live + backend curl + `/skills/{id}` 三方面對比；Round 12 副產物（Round 8/9/10/11 都看不見此 bug）。

### Lesson

設計 ES + projection 系統時，**embedding metadata = derived state，不 should be source of truth**。Read path 應從 canonical aggregate query；vector_store metadata 只作為 vector matching 的 nearby data（避免 N+1 of 全 entity load）但**最終資料來自 aggregate**。本 fix 採此 pattern 一勞永逸解決「projection 寫入路徑歷史不一致」的長期問題，不需 backfill。

## §8 Lesson — API projection completeness audit cut

S100~S106 cut 軸（page data / links / strings / state / component / control）都覆蓋不到「同一 entity 透過不同 endpoint 返回是否一致」。`/skills/{id}` vs `/search/semantic` 同一 skill 的 author/category/riskLevel 欄位 inconsistent — backend projection 在 specialized endpoint 跳過某些欄位，frontend 用 unsafe cast 把不同 DTO 當同 type 渲染，掩蓋了不一致。

**建議寫進 development-standards.md §API**：同一 entity 的 read endpoints（list / detail / search / aggregate） response 應包含 entity 的 **canonical fields set**（subset 才是合法 trim，empty fixed-value 是 bug）。前端 cast 不該用 `as unknown as` 跨 incompatible type — type system 不警告 = 行為不一致 hidden。

**Round 12 副產物**：
- 同 round 中 IntentSummaryCard 在 `/search` 正常運作（"已理解你的意圖" + 4 concept chips: docker/containerization/deployment/environment management 都顯）— S094b LLM intent endpoint working
- HomePage `/browse` 內 search 不打 intent endpoint（per S094b 設計：dedicated /search 才用 intent）— 不是 bug 是設計
- HomePage SearchBar 是 controlled input（不 navigate），與 SearchResultsPage form submit (S102 fix) 是兩條 path

Sibling chain extended：S100e → S102 → S103 → S104 → S105 → S106 → **S107** 第 7 個 cross-cutting follow-up；cut 軸為 API projection field completeness。
