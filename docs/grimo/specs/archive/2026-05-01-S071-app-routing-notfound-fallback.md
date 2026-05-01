# S071: App Routing — `/skills` Alias + NotFound Fallback

> Spec: S071 | Size: XS(3) | Status: ✅ Done — target ship `v2.49.0`
> Trigger: 2026-05-01 long E2E test session — Round 5 navigation edge case 直接在瀏覽器網址列輸入 `http://localhost:5173/skills`（user 直覺把列表頁當作此 URL）→ React Router 找不到 match，因 `<Routes>` 也無 wildcard fallback，整個 `<div id="root">` 渲染為空 — user 看到完全空白頁、navbar 也沒。同樣問題影響任何打字錯誤的 URL（`/Skill/123`、`/skil`、書籤舊網址等）。

---

## 1. Goal

修正 React Router 的兩個 routing gap：
1. `/skills` 沒對應 route → 加 alias 指 HomePage
2. unmatched URL 沒 fallback → 加 NotFoundPage（`*` route）

---

## 2. Approach

`frontend/src/App.tsx` `<Routes>` 內加兩條：

```tsx
<Route path="/skills" element={<HomePage />} />            {/* alias */}
<Route path="*" element={<NotFoundPage />} />              {/* fallback */}
```

新增 `frontend/src/pages/NotFoundPage.tsx` — 包 `<AppShell>` 保持 navbar 一致，內容簡單 404 + 「回到首頁」連結。

---

## 3. Acceptance Criteria

- AC-1: 直接 navigate `/totally-bogus-XYZ` → render NotFoundPage（含 navbar + 「404」標題 + 「回到首頁」連結 href=`/`）
- AC-2: 直接 navigate `/skills` → render HomePage 完整 listing（不再空白）
- AC-3: 既有 routes（`/`、`/skills/:id`、`/publish`、`/analytics`）行為不變

---

## 4. Risks / Rejected Alternatives

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| `/skills` alias 用 HomePage（複用 element） | HomePage 本身已是 listing — alias 路由 zero cost | 改 brand link 從 `/` 改到 `/skills` — 影響太多既有書籤 + analytics |
| NotFoundPage 包 `<AppShell>` | navbar 一致 → user 永遠能回主功能 | 純 404 plain text — 失去 navigation 入口、user 卡死 |
| `<Route path="*">` 而非 React Router v7 errorElement | `<Routes>` API 簡單，不切換到 `createBrowserRouter` 物件式 API | `createBrowserRouter` 重構 — out of scope |

---

## 7. Implementation Results — ✅ Done

### Verification
- `npx vitest run` 11 tests / 0 fail（10 → 11，App.test.tsx 新增 NotFoundPage 渲染合約測試）
- 真實瀏覽器手動驗證：
  - `/totally-bogus-XYZ` → h1=「404」, navbar 出現, 「回到首頁」連結 href=`/`
  - `/skills` → h1=「探索 Agent 技能」, 列表顯示 42 cards
  - `/`、`/skills/:id`、`/publish`、`/analytics` 行為不變
- HMR hot reload: ✓

### Files Changed (3)
- `frontend/src/App.tsx`：加 `/skills` alias + `*` wildcard route + import NotFoundPage
- `frontend/src/pages/NotFoundPage.tsx`（新）：AppShell + 404 + 回首頁連結
- `frontend/src/App.test.tsx`（新）：NotFoundPage 渲染合約測試

### Bug Origin
- 從專案開始（M2 React 19 SPA 初版） routing 表只有 4 條，沒人測 unmatched URL
- React Router v7 預設 unmatched 安靜空白（不像 v3 era 會自動 NotFound）
- E2E coverage 只測 happy path navigation（list → detail → back），沒 random URL fuzzing

### Pattern
未來新增 page 時：
- alias 路徑（`/skills`、`/skill`、`/list`）酌情加 — 但別過度（好的 IA 應該 1 path 1 page）
- `*` wildcard 永遠是最後一條 fallback，**禁止在 listing 大量同類 URL 寫各別 wildcard**
- NotFoundPage 必包 `<AppShell>` — navbar = user escape hatch
