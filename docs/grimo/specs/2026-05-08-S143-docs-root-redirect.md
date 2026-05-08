# S143: `/docs` Root Redirect → `/docs/overview`

> Spec: S143 | Size: XS(2) | Status: 📋 planned
> Date: 2026-05-08
> Origin: site audit 2026-05-08 — `/docs` 直接訪問回 404；nav「文件」連結雖指向 `/docs/your-first-skill`，但使用者直接輸入 `/docs` 會看到空白 404 頁

---

## 1. Goal

讓 `/docs` URL 自動 redirect 到 `/docs/overview`，消除 404 死路。

**非目標：**
- 不改 docs 任何內容
- 不改 nav「文件」連結目標（已指向 `/docs/your-first-skill`，維持原狀）

---

## 2. Approach

前端 React Router 加一條 redirect route：

```tsx
// AppRouter.tsx（或對應 routes 設定檔）
<Route path="/docs" element={<Navigate to="/docs/overview" replace />} />
```

`replace` 避免 `/docs` 留在 history stack。

---

## 3. Acceptance Criteria

```
Scenario: 直接訪問 /docs
  Given 使用者在瀏覽器輸入 https://skillshub.../docs
  When 頁面載入
  Then 自動跳轉至 /docs/overview
  And HTTP 狀態等同 redirect（history 不留 /docs）
  And 不出現 404 頁

Scenario: /docs 子頁面仍正常
  Given 使用者訪問 /docs/your-first-skill 或 /docs/overview
  When 頁面載入
  Then 正常顯示對應文件頁，不觸發 redirect
```

---

## 4. Files to Change

| 檔案 | 變動 |
|------|------|
| `frontend/src/App.tsx`（或 router 設定） | 加 `/docs` → `/docs/overview` redirect route |

---

## 5. Test Plan

- [ ] 訪問 `/docs` → 確認跳轉至 `/docs/overview`
- [ ] 訪問 `/docs/overview` → 確認正常顯示，無多餘跳轉
- [ ] 訪問 `/docs/your-first-skill` → 確認正常顯示
