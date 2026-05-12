# S150 — Collection Detail Page (`/collections/:id`)

**Status:** 📋 Planned  
**Estimate:** S (7 pts)  
**Depends on:** S096f2 ✅

---

## §1 背景與問題

`CollectionsPage`（S096f2 v3.8.0 ship）實作了集合列表，但每個 `CollectionCard` 只顯示：
- 名稱、分類、技能數量、下載次數
- 「安裝 N 個技能」按鈕

**缺少：點擊集合後的 Detail Page**。使用者無法在安裝前確認集合內有哪些技能，
也無法逐一進入技能 detail 頁瞭解。這是一個重要的 UX 斷點。

發現來源：平台功能審計（2026-05-08 Loop）。

---

## §2 目標

提供 `/collections/:id` Detail Page，讓使用者能：
1. 看到集合內所有技能（SkillCard list）
2. 點擊個別技能前往 skill detail
3. 在 detail 頁仍可一鍵安裝整個集合

---

## §3 User Story

> 作為一位使用者，我看到「Security Audit Pack」這個集合，我想知道它裡面有哪 2 個技能、
> 各自的描述與風險等級，再決定是否一次安裝它們。

---

## §4 設計

### 4.1 路由

```
/collections/:id   → CollectionDetailPage
```

`CollectionCard` 標題加 `<Link to={/collections/${c.id}}>` 使整個 card 可點擊。

### 4.2 API

後端已有 `GET /api/v1/collections/{id}` 回傳集合 + skillIds 列表。  
使用 `GET /api/v1/skills/{skillId}` 拉各技能 detail（N 個 parallel 請求，MVP 最多 20 個技能）。  
或直接擴展 `GET /api/v1/collections/{id}` 回傳 embedded skills（follow-up 優化）。

### 4.3 頁面結構

```
CollectionDetailPage
├── Header
│   ├── 返回集合列表
│   ├── 集合名稱 + 分類 badge
│   ├── 描述（full text）
│   └── 安裝全部 N 個技能 button（InstallButton）
├── Skill List
│   ├── N 個 SkillCard（medium size）
│   └── 每張 card 可點擊進 /skills/:id
└── Empty State（skillCount=0 時）
```

### 4.4 Acceptance Criteria

- AC-1: `GET /collections/:id` 回傳 200，頁面顯示集合名稱與 N 個技能
- AC-2: 每個 SkillCard 可點擊導航至 `/skills/:skillId`
- AC-3: `CollectionCard` 在 `CollectionsPage` 標題改為可點擊 `<Link>`
- AC-4: 安裝按鈕功能與 CollectionsPage 上的 `InstallButton` 行為一致
- AC-5: 集合不存在（404）顯示 ErrorBoundary 或空狀態
- AC-6: skillCount=0 顯示「此集合目前無技能」空狀態

---

## §5 Task Plan

| # | Task | 說明 |
|---|------|------|
| T01 | `useCollection(id)` hook | `GET /api/v1/collections/:id`；已有 useCollections，參考添加 |
| T02 | `CollectionDetailPage` component | 頁面主體 |
| T03 | Route 加入 `/collections/:id` | main.tsx / App.tsx |
| T04 | `CollectionCard` 加 Link | 整個 card 或 title 加 `<Link>` |
| T05 | Test | `CollectionDetailPage.test.tsx` AC-1~6 |
