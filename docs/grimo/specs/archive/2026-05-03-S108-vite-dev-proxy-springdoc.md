# S108 — Vite dev proxy for SpringDoc paths + footer API link UX

> **Status**: ✅ shipped `v3.4.8` (2026-05-03 — implement cron tick 15)
> **Type**: Frontend dev config + UX polish (no production deploy impact)
> **Estimate**: XS (2 pts)
> **Triggered by**: 2026-05-03 cron Tick 14 Mode B E2E live browser walk-through (Chrome MCP + curl) — Round 13 (negative case batch + footer link audit)

## §1 Goal

LandingPage footer 「API」連結（per S102 ship 保留 + 「文件」二選一）`<a href="/v3/api-docs">` 在 dev 環境（vite :5173）會落入 SPA NotFoundPage —— vite dev proxy 只有 `/api/v1/*` 規則，`/v3/api-docs` 不在 proxy table，被 vite catch-all 回 SPA `index.html`。

**Live 觀察 (Chrome MCP cron Tick 14 Round 13)**：
- `curl http://localhost:5173/v3/api-docs` → `200 text/html`（vite SPA fallback；應該是 JSON）
- `curl http://localhost:8080/v3/api-docs` → `200 application/json`（backend 正確）
- `curl http://localhost:5173/swagger-ui/index.html` → `200 text/html`（同樣 SPA fallback；backend 直接訪問正確顯 Swagger UI）
- vite.config.ts:13 `proxy: { '/api/v1': ... }` — 唯一 proxy 規則

**Production 不受影響**：Spring Boot 把 frontend `dist/` copy 進 `static/` 後 single-port 8080 同時 serve SPA + `/api/v1/*` + `/v3/api-docs` + `/swagger-ui/*`，無 proxy 需求。

**Dev-only fix scope**：vite.config.ts proxy 補 SpringDoc 兩條路徑；可選 LandingPage footer link 從 raw JSON `/v3/api-docs` 改 Swagger UI `/swagger-ui/index.html`（end-user 友善）。

**Sibling 關係**：S100e → S102 → S103 → S104 → S105 → S106 → S107 → **S108** — 第 8 個 cross-cutting follow-up；cut 軸從「page-level data → cross-cutting links → user-visible strings → interactive state → component-context → control-behavior → API projection → **dev-environment proxy completeness**」累積 8 層。發現方式 = curl 對比 dev :5173 vs backend :8080 同 path 不同 response（前 7 cut 都看不見 dev-config-only 問題）。

## §2 Findings — verified gaps

| # | Path | dev :5173 response | backend :8080 response | 嚴重度 |
|---|------|---------------------|--------------------------|--------|
| 1 | `/v3/api-docs` (OpenAPI JSON) | 200 text/html (SPA fallback NotFoundPage) | 200 application/json | Low — dev-only；prod single-port 不受影響 |
| 2 | `/swagger-ui/index.html` (Swagger UI) | 200 text/html (SPA fallback) | 200 text/html (Swagger UI HTML) | Low — 同 #1 |
| 3 | LandingPage `<a href="/v3/api-docs">` 連到 raw JSON | n/a | end-user 看 raw JSON UX 較差 | Low-Medium — UX polish；改連 Swagger UI 更友善 |

**Excluded（本 spec 不修）**:
- Backend `springdoc.api-docs.path` 改路徑 — 影響 OpenAPI 3.1 標準路徑 (`/v3/api-docs`)，超 dev-config scope
- Anonymous auth on /v3/api-docs — 已 permit-all per CLAUDE.md「Feature First, Security Later」

## §3 Approach

**Trim path**：本 spec 已 XS，無 trim 空間；若 implement tick 觸 wall：
- 必做 #1 + #2（vite proxy 兩條路徑；user-visible bug 主因）
- 可 defer #3（footer link 改 swagger-ui；UX polish 非 functional bug）

**Decision per gap**:

- **Gap #1 + #2 (vite proxy)**: 加 SpringDoc 兩條路徑進 `vite.config.ts` proxy table:
  ```ts
  server: {
    proxy: {
      '/api/v1': { target: 'http://localhost:8080', changeOrigin: true },
      '/v3/api-docs': { target: 'http://localhost:8080', changeOrigin: true },
      '/swagger-ui': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  ```

- **Gap #3 (footer link UX)**: LandingPage:158 改 `<a href="/swagger-ui/index.html">`（保留 label「API」+ 點開後是 visual API explorer 而非 raw JSON）。S102 ship 確認 footer 兩 link 為「文件」+「API」，所以 label 不變只改 target。

**不引入新 dev script / Docker compose 改動 / backend 路徑變更**：純 frontend dev config + 單行 link target 改動。

## §4 Acceptance Criteria

| AC | Given | When | Then |
|----|-------|------|------|
| AC-1 | dev server 啟動於 :5173 | `curl http://localhost:5173/v3/api-docs` | `200 application/json`（OpenAPI JSON，不是 SPA fallback HTML）|
| AC-2 | dev server 啟動於 :5173 | `curl http://localhost:5173/swagger-ui/index.html` | `200 text/html` 含 Swagger UI 字面（標題 / Swagger UI bundle script）|
| AC-3 | 進入 LandingPage `/` | 點 footer 「API」link | navigate 到 `/swagger-ui/index.html`（看到 Swagger UI 互動式介面）|
| AC-4 | production single-port deploy（Spring Boot serving SPA + API） | navigate `/swagger-ui/index.html` | 不受影響（既有行為，本 fix 只動 dev 配置與 link target）|

## §5 File plan

| File | Edit | LOC delta |
|------|------|-----------|
| `frontend/vite.config.ts` | proxy table 加 `/v3/api-docs` + `/swagger-ui` 兩條規則 | ~6 |
| `frontend/src/pages/LandingPage.tsx` line 158 | href 從 `/v3/api-docs` 改 `/swagger-ui/index.html` | ~1 |
| `frontend/src/pages/LandingPage.test.tsx` 若不存在 → 新建（AC-3 footer link target） | new file | ~25 |

**測試新增 / 更新**：
- LandingPage.test.tsx — AC-3 footer link href = `/swagger-ui/index.html`
- vite proxy 補測 difficult to unit test（dev server 行為），改 README 加 manual smoke step 即可（polish backlog）

## §6 Test plan

```bash
# Manual smoke (dev server already running):
curl -s -o /dev/null -w "%{http_code} %{content_type}\n" http://localhost:5173/v3/api-docs
# expect: 200 application/json

curl -s -o /dev/null -w "%{http_code} %{content_type}\n" http://localhost:5173/swagger-ui/index.html | head
# expect: 200 text/html (Swagger UI HTML; first lines should contain Swagger UI bundle script tag)

# Component test:
cd frontend && npm test -- --run LandingPage
# expect AC-3 PASS
```

**Negative case**: `curl http://localhost:5173/swagger-ui-nonexistent` → 應 404 from backend (not vite SPA fallback；新 proxy rule 涵蓋)。
**Edge case**: vite proxy hot-reload — 改 vite.config.ts 後需重啟 `npm run dev`（既有 vite 行為，user 已習慣）。

## §7 Result

**Shipped 2026-05-03 cron Tick 15 @ ~08:22**.

### Implement checklist

- [x] vite.config.ts proxy table 加 `/v3/api-docs` + `/swagger-ui` 兩條規則
- [x] LandingPage.tsx line 158 href 從 `/v3/api-docs` 改 `/swagger-ui/index.html`
- [x] LandingPage.test.tsx 新建（3 ACs：S108 footer API link target + S102 baseline 「文件」+ S102「狀態」removed assertions）
- [x] vite dev server auto-restart（config change 觸發 vite 內建 restart，不需手動重啟）
- [x] Manual smoke: `curl http://localhost:5173/v3/api-docs` → `200 application/json` ✓（before fix: `200 text/html` SPA fallback）
- [x] Manual smoke: `curl http://localhost:5173/swagger-ui/index.html` → `200 text/html` ✓（內容由 SPA fallback 變 Swagger UI 真內容，proxy 透明轉發）
- [ ] Chrome MCP visual smoke 推遲：dev server 已 verified via curl，Chrome MCP click test 為 polish 不阻擋 ship
- [x] CHANGELOG `v3.4.8` patch entry
- [x] roadmap row → ✅
- [x] spec doc 移 archive/

### Verify metrics

| Item | Value |
|------|-------|
| Files changed | 4（vite.config.ts + LandingPage.tsx + LandingPage.test.tsx + spec/CHANGELOG/roadmap docs）|
| LOC delta | +35 / -1（含 test 整檔新建 +25）|
| FE tests | 既有 37 → 40（+3 LandingPage.test.tsx 新檔）|
| Backend touch | 0（純 frontend dev config + UX）|
| Wall clock | ~7 min（IMPLEMENT 3 + tests 2 + smoke 1 + DOCUMENT 1）|

### Live render validation (curl)

| Path | Before (Tick 14 audit) | After (Tick 15 ship) |
|------|----------------------|---------------------|
| `curl :5173/v3/api-docs` | `200 text/html` (SPA fallback NotFoundPage HTML) | `200 application/json` (OpenAPI JSON ✓) |
| `curl :5173/swagger-ui/index.html` | `200 text/html` (SPA HTML) | `200 text/html` (Swagger UI HTML ✓ — content reachable via dev) |
| LandingPage footer API link href | `/v3/api-docs` (raw JSON UX 較差) | `/swagger-ui/index.html` (visual API explorer) ✓ |

### Trim deferred

- **Chrome MCP visual smoke**：curl 已驗 proxy 正確，dev server visual smoke 為 polish；polish backlog
- **development-standards.md §dev environment** "Vite proxy 應 mirror 所有 backend-served paths" rule — sibling lesson 與 S103/S104/S105/S106/S107 集中 doc-side rules 處理；polish backlog

### Sibling chain validation

S100e (defensive guard v3.4.1) → S102 (routing residual v3.4.2) → S103 (UX copy hygiene v3.4.3) → S104 (interactive state consistency v3.4.4) → S105 (component-context alignment v3.4.5) → S106 (control-behavior alignment v3.4.6) → S107 (API projection field completeness v3.4.7) → **S108 (dev environment proxy completeness v3.4.8)** — 第 8 個 cross-cutting follow-up，cut 累積 8 層。發現方式 = curl 對比 dev :5173 vs backend :8080 同 path 不同 response（前 7 cut 都看不見 dev-config-only 問題；prod single-port deploy 自然遮蔽此 bug）。本 ship 後 v3.4.x patch series 8 個全 land；S100 META post-ship cross-cutting audit 第 8 輪 follow-up complete。

### Process learning (Round 13 byproduct extended)

curl 對比兩 endpoints (dev :5173 vs backend :8080) 同 path response 是 dev environment audit 的有效 cut — 任何 single-port deploy 假設下 dev proxy 不全的 path 在 dev 都會 fallback 到 SPA NotFoundPage。**Future cron tick Mode B Round 14+** 可批次驗證 SpringDoc 之外其他 backend-served paths（actuator endpoints、static admin paths 等）是否同樣 proxy gap。

## §8 Lesson — dev environment proxy completeness audit cut

S100~S107 cut 軸（page data / links / strings / state / component / control / API projection）都覆蓋不到「dev environment 與 production 行為差異」— vite dev proxy 規則 incomplete 導致 dev 環境 SPA fallback 偷渡到 backend-only path（OpenAPI JSON / Swagger UI），但 production single-port deploy 不受影響，bug 在 prod 隱形。

**建議寫進 development-standards.md §dev environment**：
- Vite proxy table 應 mirror **所有 backend-served paths**（包含 SpringDoc / actuator / static admin endpoints）— 不只 API endpoints
- Dev environment audit 應 curl 對比 dev :5173 vs backend :8080 同 path response，差異即配置漏洞

**Round 13 副產物**：
- 5 個 negative deep-link patterns（`/skills/null` / `/skills/r35` / `/skills/r35/non-existent-name` / `/docs/non-existent-doc` / `/some-random-unknown-route`）全 graceful handle（404 page 顯示，無 crash）— positive negative-case coverage
- NotFoundPage 「回到首頁」link `to="/"` 為 false positive（label「回到首頁」對應 `/` LandingPage 語意一致，per S096e1 routing change 後 `/` = LandingPage = 「首頁」）

Sibling chain extended：S100e → S102 → S103 → S104 → S105 → S106 → S107 → **S108** 第 8 個 cross-cutting follow-up；cut 軸為 dev environment proxy completeness。
