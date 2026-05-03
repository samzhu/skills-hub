# S109 — Vite dev proxy for Spring Boot Actuator endpoints

> **Status**: ✅ shipped `v3.4.9` (2026-05-03 — full pipeline cron tick 16，no Spec-Only-Handoff handover)
> **Type**: Frontend dev config (no production deploy impact)
> **Estimate**: XS (1 pt)
> **Triggered by**: 2026-05-03 cron Tick 16 Mode B Round 14 — extends S108 audit cut to actuator paths per S108 §8 lesson

## §1 Goal

S108 (v3.4.8) 補了 SpringDoc 兩條路徑進 vite proxy（`/v3/api-docs` + `/swagger-ui`），但 S108 §8 lesson 已點出 vite proxy 應 mirror **所有 backend-served paths**，包括 SpringDoc 與 actuator。Round 14 audit (curl 對比 dev :5173 vs backend :8080) 確認 actuator 路徑同樣 fallback SPA HTML：

| Path | :8080 backend | :5173 dev (before fix) |
|------|---------------|------------------------|
| `/actuator` | 200 `application/vnd.spring-boot.actuator.v3+json` | 200 `text/html` (SPA fallback) |
| `/actuator/health` | 200 actuator JSON | 200 `text/html` (SPA fallback) |

不修 backend；不引入新 Actuator 配置。**Frontend-only fix**: vite.config.ts proxy 加 `/actuator` 一條規則（萬用 prefix，涵蓋 `/actuator/health` / `/actuator/info` / `/actuator/prometheus` / `/actuator/metrics` 全部子路徑）。

**Sibling 關係**：S100e → S102 → S103 → S104 → S105 → S106 → S107 → S108 → **S109** — 第 9 個 cross-cutting follow-up；同 S108 audit cut（dev environment proxy completeness）的延伸應用，驗證 S108 §8 polish-backlog rule「proxy 應 mirror all backend-served paths」。

## §2 Findings — verified gap

| # | Path | dev :5173 | backend :8080 | 嚴重度 |
|---|------|-----------|---------------|--------|
| 1 | `/actuator` | 200 text/html (SPA fallback) | 200 actuator JSON | Low — dev-only；prod single-port 不受影響 |
| 2 | `/actuator/health` | 200 text/html | 200 actuator JSON | Low — 同 #1，devs 跑 health probe 會誤入 SPA |

**Excluded（本 spec 不修）**:
- `/error` — Spring Boot 內部 error mapping，user 不該主動訪問；proxy 反而可能干擾 SPA 自己的 error 處理
- `/login` — backend 返 404（permit-all per CLAUDE.md），不是 backend-served real path
- `/favicon.ico` — backend + dev 都返 404；favicon 缺失是另一條 polish concern，不在 proxy scope

## §3 Approach

**Trim path**：本 spec 已是最小單元（XS=1 pt）；無 trim 空間。

**Implementation**: vite.config.ts proxy table 加 `/actuator` (prefix match):
```ts
'/actuator': { target: 'http://localhost:8080', changeOrigin: true },
```

`/actuator` prefix 自動 cover all sub-paths (Vite proxy default behavior is prefix match)，不需顯式列出 `/actuator/health` / `/actuator/info` 等。

**不引入測試**：proxy behavior 屬 dev server 配置，unit test 難覆蓋；smoke 用 curl 驗證即可（同 S108 pattern）。

## §4 Acceptance Criteria

| AC | Given | When | Then |
|----|-------|------|------|
| AC-1 | dev server 啟動於 :5173 | `curl http://localhost:5173/actuator/health` | `200 application/vnd.spring-boot.actuator.v3+json`（actuator JSON，不是 SPA fallback HTML）|
| AC-2 | dev server 啟動於 :5173 | `curl http://localhost:5173/actuator` | `200 application/vnd.spring-boot.actuator.v3+json`（actuator root JSON）|

## §5 File plan

| File | Edit | LOC delta |
|------|------|-----------|
| `frontend/vite.config.ts` | proxy table 加 `/actuator` 一條規則 | ~5 |

## §6 Test plan

```bash
# Dev server auto-restart pick up vite.config.ts change
sleep 2
curl -s -o /dev/null -w "%{http_code} %{content_type}\n" http://localhost:5173/actuator/health
# expect: 200 application/vnd.spring-boot.actuator.v3+json

curl -s -o /dev/null -w "%{http_code} %{content_type}\n" http://localhost:5173/actuator
# expect: same actuator JSON
```

**Negative case**: `:5173/actuator/nonexistent` → backend 404 from actuator (not vite SPA fallback；新 proxy rule 涵蓋)。
**Edge case**: prod single-port deploy 不受影響（既有行為，本 fix 只動 dev 配置）。

## §7 Result

**Shipped 2026-05-03 cron Tick 16 @ ~08:53**.

### Implement checklist

- [x] vite.config.ts proxy table 加 `/actuator` 一條規則
- [x] vite dev server auto-restart pick up config change
- [x] Manual smoke `curl :5173/actuator/health` → `200 application/vnd.spring-boot.actuator.v3+json` ✓（before fix: `200 text/html`）
- [x] Manual smoke `curl :5173/actuator` → 同 actuator JSON ✓
- [x] CHANGELOG `v3.4.9` patch entry
- [x] roadmap row → ✅
- [x] spec doc archive 直接（一 tick 走完不經 Spec-Only-Handoff）

### Verify metrics

| Item | Value |
|------|-------|
| Files changed | 2（vite.config.ts + spec/CHANGELOG/roadmap docs）|
| LOC delta | +6 / -0 (vite proxy entry)|
| FE tests | 既有 40（不變；proxy 行為不可 unit test）|
| Backend touch | 0（純 frontend dev config）|
| Wall clock | ~5 min（PLAN 1 + IMPLEMENT 1 + smoke 1 + DOCUMENT 2；無 spec handoff round trip） |

### Live render validation (curl)

| Path | Before (Tick 16 audit) | After (Tick 16 ship) |
|------|----------------------|---------------------|
| `:5173/actuator/health` | `200 text/html` (SPA fallback) | `200 application/vnd.spring-boot.actuator.v3+json` (actuator JSON ✓) |
| `:5173/actuator` | `200 text/html` (SPA fallback) | `200 application/vnd.spring-boot.actuator.v3+json` ✓ |

### Trim deferred

- **`/error` proxy** — Spring Boot 內部 error mapping，proxy 反而可能干擾 SPA 自己 error handling；polish backlog 留 user 決定是否 proxy
- **Test for proxy behavior** — vite dev server 行為難 unit test；S108 + S109 都 trim 此項，polish backlog（可考慮加 manual smoke step 進 README dev workflow section）
- **`/favicon.ico` 缺失** — 不在 proxy scope；polish backlog（front asset 補檔）

### Sibling chain validation

S100e (defensive guard v3.4.1) → S102 (routing residual v3.4.2) → S103 (UX copy hygiene v3.4.3) → S104 (interactive state consistency v3.4.4) → S105 (component-context alignment v3.4.5) → S106 (control-behavior alignment v3.4.6) → S107 (API projection field completeness v3.4.7) → S108 (dev environment proxy completeness v3.4.8) → **S109 (dev proxy actuator extension v3.4.9)** — 第 9 個 cross-cutting follow-up，cut 累積 9 層；S108 audit cut 直接延伸驗證 S108 §8 polish backlog rule。

### Process learning (one-tick full-ship)

S109 是首個 cron-bound agent **不經 Spec-Only-Handoff** 直接一 tick 全 ship 的 spec — 因為：
1. XS=1 pt 純 dev config 改動
2. 同 S108 sibling pattern，approach 已確認
3. 無 cross-file 變動 / 無 frontend code touch / 無 test infrastructure 改動
4. Smoke 驗證 < 1 min via curl

對非常小的 follow-up fix，**single-tick full-ship 比 spec-only-handoff 高效**；spec doc 仍寫但 §1-§7 一次完成。Pattern 適用條件：(a) prior tick 已驗 approach (b) XS scope (c) 無 ambiguous design choice。

## §8 Lesson — proxy completeness systematically

S108 §8 已建議 vite proxy 應 mirror all backend-served paths；S109 補上 actuator 延伸應用。**累積 lesson**：dev environment proxy table 該明確列出三類 backend 路徑：

1. **API endpoints** (e.g. `/api/v1/*`)
2. **API documentation** (e.g. `/v3/api-docs`, `/swagger-ui/*`) — S108
3. **Operational endpoints** (e.g. `/actuator/*`) — S109

任何 future Spring Boot config change 加新 backend-served path（如 `/sse-stream`、`/admin/*`），dev proxy 應同步更新。development-standards.md §dev environment 應記錄此三類 checklist。
