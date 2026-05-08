# S143: `/docs` Canonical Entry → `/docs/overview`

> Spec: S143 | Size: XS(2) | Status: 📐 in-design
> Date: 2026-05-08
> Origin: site audit 2026-05-08 — `/docs` 直接訪問回 404；nav「文件」指向 `/docs/your-first-skill`（walkthrough），與「直接輸入 URL 期待總覽」的直覺不符。`AppShell.tsx:24` 註解早已預告「未來 /docs 可改 index 頁」。

---

## 1. Goal

把 `/docs` 設為文件區的 **canonical entry**：

- 直接訪問 `/docs` → redirect 至 `/docs/overview`（消除 404 死路）
- AppShell nav「文件」也改指 `/docs`，讓 URL 與 nav 一致

**非目標：**
- 不改 docs 任何內容
- 不改其他子頁路由（`/docs/overview`、`/docs/your-first-skill`、`/docs/risk-tiers` 等照舊）

---

## 2. Approach

### 2.1 Router redirect

`frontend/src/App.tsx` 加一條 redirect route：

```tsx
import { Navigate } from 'react-router'

<Route path="/docs" element={<Navigate to="/docs/overview" replace />} />
```

`replace` 避免 `/docs` 留在 history stack（使用者按上一頁不會卡在中間態）。

### 2.2 Nav 對齊

`frontend/src/components/AppShell.tsx:25` 改：

```tsx
{ path: '/docs/your-first-skill', label: '文件' }
↓
{ path: '/docs', label: '文件' }
```

點 nav 走 `/docs` → 自動 redirect 到 `/docs/overview`，與直接輸入 URL 行為一致。

### 2.3 為什麼選擇 overview 為 default landing

- `/docs/overview` 是文件 IA 的 index（S098f 設計），介紹整個 docs 結構
- 第一次進文件的使用者看 overview 比看 walkthrough 更合理（先看地圖再選路）
- AppShell.tsx:24 既有註解早已暗示此演進路線，現在兌現

---

## 3. Acceptance Criteria

```
AC-1: 直接訪問 /docs
  Given 使用者在瀏覽器輸入 https://skillshub.../docs
  When 頁面載入
  Then 自動以 client-side redirect (history.replace) 跳轉至 /docs/overview
  And 不出現 404 頁
  And 瀏覽器歷史紀錄不殘留 /docs（按上一頁直接回到前一頁，不卡 /docs）

AC-2: AppShell nav「文件」走相同路徑
  Given 使用者點 AppShell nav「文件」
  When 連結觸發
  Then 導航至 /docs，立即被 redirect 規則接走至 /docs/overview
  And 最終顯示 OverviewPage

AC-3: docs 子頁面仍正常
  Given 使用者直接訪問 /docs/your-first-skill 或 /docs/overview
  When 頁面載入
  Then 正常顯示對應文件頁，不觸發 redirect
```

驗證指令：`cd frontend && npm test`（vitest，per qa-strategy.md）

---

## 4. Files to Change

| 檔案 | 變動 |
|------|------|
| `frontend/src/App.tsx` | 加 `import { Navigate } from 'react-router'` + 加一條 `/docs` → `/docs/overview` redirect route |
| `frontend/src/components/AppShell.tsx` | nav 條目 `{ path: '/docs/your-first-skill', ... }` 改 `{ path: '/docs', ... }`；移除舊 S094d 註解（過期暗示已兌現） |

---

## 5. Test Plan

### 5.1 自動化（vitest + MemoryRouter）

新增最小 router 測試 `frontend/src/App.test.tsx`（若已存在則 append）：

- AC-1: `MemoryRouter initialEntries={['/docs']}` → assert OverviewPage 內容渲染
- AC-3: `MemoryRouter initialEntries={['/docs/your-first-skill']}` → assert YourFirstSkillPage 內容渲染（不被 redirect）

### 5.2 手動煙霧測試（dev server）

- [ ] `/docs` → 跳轉 `/docs/overview`
- [ ] AppShell 點「文件」→ 同樣到 `/docs/overview`
- [ ] `/docs/overview` 直訪 → 正常顯示，無多餘跳轉
- [ ] `/docs/your-first-skill` 直訪 → 正常顯示
- [ ] `/docs` 跳轉後按瀏覽器「上一頁」→ 回到前一頁（不卡 /docs）
