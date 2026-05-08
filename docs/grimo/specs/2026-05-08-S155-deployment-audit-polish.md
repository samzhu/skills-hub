# S155: Deployment Audit Polish — 7 個 LAB 小 UX 問題

> Spec: S155 | Size: S(7) | Status: 🚧 in-progress（4/7 shipped 2026-05-08 — items #1 + #3 + #5 + #7）
> Date: 2026-05-08
> Origin: deployment audit 2026-05-08（LAB skillshub-...run.app）— 一輪掃描中收集到 5 個獨立小問題，個別都太小不值得各開一隻 spec，但累積會破壞使用體驗一致性。打包進一隻 S(5) 解決。

---

## 1. Goal

修掉 LAB audit 中發現的 5 個獨立 UX gap：

1. Footer「API」link 在 LAB 環境破掉（SpringDoc 未啟用 → 404 raw JSON）
2. `/auth-debug` 頁無 AppShell wrapper，使用者卡住無法導航
3. `/publish/failed` 直訪顯 stale「驗證失敗 / 0 error · 0 warning」自相矛盾資料
4. 旗標 tab 文案夾雜英文「由 **reviewer** 處理」、「被其他人 **flag** 時通知你」
5. 通知偏好 modal 「新版本」訂閱項顯 `（敬請期待）` 但仍可勾選

每項獨立解，互不依賴，可分 task 並行做。

**非目標：**
- 不重做這些頁面的整體設計
- 不解決 author display 問題（S154 處理）
- 不解決 SPA fallback 路由問題（S152 處理）

---

## 2. Approach

### 2.1 #1 Footer「API」link in LAB

**現況**：
- `frontend/src/pages/LandingPage.tsx`（或 footer 元件）有 `<a href="/swagger-ui/index.html">API</a>`
- LAB profile（`SPRING_PROFILES_ACTIVE=lab,gcp`）未啟用 `springdoc-openapi-starter-webmvc-ui`
- 點 link → backend 回 raw JSON 404 `{"error":"NOT_FOUND","message":"No static resource swagger-ui/index.html..."}`

**選項**：

| 方案 | 動作 | Pros | Cons |
|------|------|------|------|
| A. 改指向 `/docs/rest-api`（內部文件頁）| 純前端 link 改 | 簡單；總是 work | 失去 interactive Swagger UI 體驗 |
| B. LAB 也啟用 SpringDoc | application-lab.yaml 加 `springdoc.api-docs.enabled=true` 等 | 保留 interactive 體驗 | 暴露 schema 給未授權 user（lab 用戶可接受？）|
| C. Conditional link — 啟用時才顯示 | 前端 fetch `/v3/api-docs` 探活；不可用 hide | 自適應 | fetch 開銷 + 複雜 |

**選 A**：現階段 LAB 環境定位是 demo，schema 暴露非必要；docs 內 `/docs/rest-api` 已經有 REST API 介紹頁，直接導過去最一致。**未來** dev profile 再啟用 SpringDoc 時可雙連結（A + B）。

### 2.2 #2 /auth-debug 沒 AppShell

**現況**：`AuthDebugPage` 直接 render：
```tsx
return <div>...</div>
```
無 `<AppShell>` wrapper → user 直訪此 URL 後**無 nav 條**，唯一退路是瀏覽器後退。

**修正**：
```tsx
return (
  <AppShell>
    <h1>...</h1>
    {hasRealOauth ? <AuthDebugTable .../> : <NoOauthHint />}
  </AppShell>
)
```

外加 `NoOauthHint` 元件加「返回首頁」link 與更友善的訊息：

```tsx
function NoOauthHint() {
  return (
    <EmptyState
      tone="redirect"
      headline="此功能僅在開發環境啟用"
      sub="這是給開發者除錯用的頁面。需要後端 real-oauth profile 啟用才有真實認證資料；目前為 LAB 環境。"
      primaryAction={{ label: '返回首頁', href: '/browse' }}
    />
  )
}
```

替代「需要 SPRING_PROFILES_ACTIVE 含 real-oauth」這種給 dev 看的 jargon。

### 2.3 #3 /publish/failed 直訪 stale data

**現況**：`PublishFailedPage` 在 `useLocation().state` 為 `null` / 缺 `findings` 時，仍 render 主畫面，但 fallback 值為「0 error · 0 warning」 + 「失敗」標籤 → 互相矛盾，user 困惑。

**修正**：Page 開頭加 state guard：
```tsx
const { state } = useLocation()
if (!state || !state.findings) {
  return (
    <AppShell>
      <EmptyState
        tone="redirect"
        headline="沒有失敗紀錄可顯示"
        sub="此頁面僅在發佈流程觸發失敗時自動導入。請從上傳開始。"
        primaryAction={{ label: '前往上傳', href: '/publish' }}
      />
    </AppShell>
  )
}
```

對齊 `PublishValidatePage` / `PublishReviewPage` 既有「缺 skill id 參數 — 請從 /publish 重新發佈」的 pattern。

### 2.4 #4 文案夾雜英文（多處）

**現況** — sweep 全 frontend user-facing 文案，至少以下處夾雜英文：

| 位置 | 英文字 | 修為 |
|------|--------|------|
| `FlagsPanel` empty state（skill detail 旗標 tab） | reviewer、flag | 審核者、回報 |
| `NotificationPreferencesModal` | flag、description | 回報、描述 |
| `FlagsQueuePage` 標題下 sub | OPEN、reviewer、Resolve、Dismiss | 待處理、審核者、處理、駁回 |
| `FlagsQueuePage` empty state | reviewer | 審核者 |

**修正方法**：grep 全 frontend `'reviewer'`、`'flag'`、`'OPEN'`、`'Resolve'`、`'Dismiss'`、`'description'`（在 user-facing 文案內），逐一替換。技術代碼裡的識別子名稱（變數、API path、enum value）不改 — 只動 UI 顯示文案。

**例外保留英文**：
- 程式碼示意內的英文（如 `skills-hub install ...` command）保留
- 專有名詞（如 SKILL.md / OAuth / OpenAPI）保留

### 2.5c #7 InstallCard「CLI ▼」Dead UI（新增）

**現況**：SkillDetail 右側 sidebar `InstallCard` 顯示「CLI ▼」字眼 + 下三角箭頭，視覺上**強烈暗示 dropdown selector**（claude / cline / cursor / 等 CLI 選擇）。實測：
- 該元素是純 `<span>` 不是 `<button>`
- 無 onclick / event handler
- 點下去無 dropdown 出現
- 無 ARIA `role="combobox"` / `aria-haspopup`

→ **死 UI**：佯裝可互動，user 點了沒反應，挫敗。

**修法（兩選一）：**

A. **若 CLI selector 是 design intent 但未實作**：移除 ▼ 箭頭與「CLI」字眼，純文字 hint「複製為 CLI 命令」即可
B. **若應該是 dropdown 但 ship 漏**：實作真 dropdown，列出 npm / Claude Code / Cline / Cursor 等 client；切換改 `prefix` 文字（如 `npx skills-hub install` / `claude skill install`）

**選 A**（MVP 階段先省互動，留下次需求一起做）：
- `<span>CLI ▼</span>` → 移除 ▼，刪除 placeholder hint
- 等真有 multi-CLI support 時另開 spec 實作（與 S145 訂閱 UI 同期）

### 2.5b #6 `/browse` Sort Tabs Active Highlight Desync（新增）

**現況**：`/browse` 右上角 sort tabs（推薦 / 最新 / 風險低 / 下載最多）— 點「最新」後資料順序確實重排（deep-research 移到首位），但**「推薦」pill 仍 active highlighted**（border 仍在「推薦」上，「最新」是 transparent border）。

**確認 vs 假象**：實測 4 個 sort tab 點擊後 className：
- 推薦: `border border-[rgba(255,2...` （白色 border 顯 active）
- 最新 / 風險低 / 下載最多: `border border-transparent`

**Root cause 假設**：`HomePage` 用 `sort` state 控 active class，但條件 typo / 比錯（如 `sort === 'recommended'` 預設 true 永遠 active；點擊只更新 query param 沒同步 state）。

**修正**：sweep `HomePage.tsx` sort tab render 邏輯，確保 active class 條件 = 當前 sort state。

### 2.5 #5 通知偏好「新版本（敬請期待）」

**現況**：`NotificationsPage` 偏好 modal 列出 4 項：回報 / 評論 / 需求 / 新版本。新版本顯示 `（敬請期待）` 但 checkbox 仍可互動（disabled 灰，與其他 3 項視覺有別）。

**問題**：「敬請期待」placeholder 文案是 anti-pattern — 既然不能用，user 不該看到。要嘛真的做、要嘛 hide 整項直到做。

**修正**：直接從 modal 的 4 項中 hide「新版本」項。等真實做時再 show。

實作：`NotificationPreferencesModal` 條件 hide：
```tsx
const items = [
  { key: 'flag',     label: '回報', desc: '...' },
  { key: 'review',   label: '評論', desc: '...' },
  { key: 'request',  label: '需求', desc: '...' },
  // 「新版本」訂閱：等 S145（訂閱管理頁）ship 後 再加回
]
```

---

## 3. Acceptance Criteria

```
AC-1: Footer「API」link 點下去進入 /docs/rest-api 不再 404
  Given 使用者在 LandingPage / 任何頁 footer
  When 點「API」link
  Then URL 變成 /docs/rest-api
  And 顯示 RestApiPage 內容（既有 docs 頁）

AC-2: /auth-debug 直訪有 AppShell + 友善訊息
  Given 使用者直接打 /auth-debug URL（LAB profile）
  When 頁面載入
  Then 顯示 AppShell（top nav 完整可導航）
  And 主內容區顯 EmptyState「此功能僅在開發環境啟用」+ 「返回首頁」CTA
  And 不出現「SPRING_PROFILES_ACTIVE」等 dev jargon

AC-3: /publish/failed 直訪不顯示 stale 資料
  Given 使用者直訪 /publish/failed 沒有從 /publish/validate 走 state 過來
  When 頁面載入
  Then 顯示 EmptyState「沒有失敗紀錄可顯示」+ 「前往上傳」CTA
  And 不出現「驗證失敗 / 0 error · 0 warning」混亂訊息

AC-4: User-facing 文案無夾雜英文（4 處）
  Given 任何 user 訪問以下 4 處：旗標 tab empty state、通知偏好 modal、
        /flags queue page header sub、/flags queue empty state
  When 文案 render
  Then 「reviewer」→「審核者」、「flag」→「回報」、
       「OPEN」→「待處理」、「Resolve」→「處理」、「Dismiss」→「駁回」、
       「description」（在中文 sentence 內）→「描述」

AC-5: 通知偏好 modal 不再顯「新版本（敬請期待）」
  Given 使用者打開通知中心 → 設定 modal
  When modal render
  Then 只顯 3 個訂閱項（回報、評論、需求）
  And 不出現 disabled 的「新版本」項

AC-7: InstallCard「CLI ▼」死 UI 移除
  Given 使用者訪問 SkillDetail 右側 sidebar InstallCard
  When 元素 render
  Then 不顯示 ▼ 箭頭暗示可 dropdown
  And 「CLI」placeholder 改為簡潔文字「Skills Hub CLI」（純文字 label）
  And user 滑鼠 hover / click 此區無 misleading dropdown indicator

AC-6: /browse sort tab active highlight 與 sort state 同步
  Given 使用者訪問 /browse 預設 sort=推薦
  When 點擊 「最新」/ 「風險低」/ 「下載最多」 任一 tab
  Then 該 tab 視覺上 active（border 變白圈），其他 tab 失去 highlight
  And 列表順序對應該 sort 規則
```

驗證指令：
- 自動化：`cd frontend && npm test`（per qa-strategy.md）— 新增 `LandingPage.footer.test.tsx`、`AuthDebugPage.test.tsx`、`PublishFailedPage.test.tsx` 補各 AC 對應 case
- 手動：deploy 後 5 條 AC 各打一遍

---

## 4. Files to Change

| 檔案 | 變動 |
|------|------|
| `frontend/src/pages/LandingPage.tsx`（或 footer component） | footer「API」link `href` 改 `/docs/rest-api` |
| `frontend/src/pages/AuthDebugPage.tsx` | 加 `AppShell` wrapper；no-oauth 訊息改 EmptyState 友善文案 |
| `frontend/src/pages/PublishFailedPage.tsx` | 開頭加 state guard，缺 state 顯 EmptyState + 「前往上傳」CTA |
| `frontend/src/components/v2/tabs/FlagsPanel.tsx`（或 empty state copy） | 「由 reviewer 處理」→「由審核者處理」 |
| `frontend/src/components/NotificationPreferencesModal.tsx` | 「被其他人 flag 時」→「被其他人回報時」；移除「新版本」項 |
| `frontend/src/pages/HomePage.tsx` | sort tab active class 條件比對 sort state；確認 4 個 tab 互斥 highlight |
| `frontend/src/components/v2/InstallCard.tsx` | 移除「CLI ▼」死 UI placeholder；改純文字 label |
| **Tests** | 對應 5 個 AC 寫 vitest case |

---

## 5. Test Plan

### 5.1 自動化

- AC-1: footer link href assertion in `LandingPage.test.tsx`
- AC-2: `AuthDebugPage.test.tsx` — render → assert AppShell present + headline 「此功能僅在開發環境啟用」
- AC-3: `PublishFailedPage.test.tsx` — `MemoryRouter` 直訪無 state → assert EmptyState present
- AC-4: grep test —`expect(container.textContent).not.toMatch(/reviewer|flag(?!s)/)` 在 FlagsPanel + NotificationPreferencesModal render 後
- AC-5: `NotificationPreferencesModal.test.tsx` — checkbox count === 3

### 5.2 手動 LAB

deploy 後逐項：
- [ ] LandingPage 滾到底點「API」→ `/docs/rest-api` 顯示正確
- [ ] 直訪 `/auth-debug` → AppShell + 友善訊息 + 返回首頁 CTA
- [ ] 直訪 `/publish/failed` → 「沒有失敗紀錄可顯示」+ CTA
- [ ] 點旗標 tab 任一 skill → 文案無「reviewer」/「flag」
- [ ] 通知中心 → 設定 → modal 只 3 項（回報、評論、需求）

---

## 6. Verification

| 項目 | 結果 |
|------|------|
| #1 Footer API link | ✅ shipped 2026-05-08 — LandingPage.tsx:158 改 `<Link to="/docs/rest-api">`，避開 LAB swagger-ui 404 |
| #7 InstallCard「CLI ▼」死 UI | ✅ shipped 2026-05-08 — 改純文字 "Skills Hub CLI"，移除假 dropdown 視覺暗示 |
| #5 通知偏好「新版本」placeholder | ✅ shipped 2026-05-08 — PreferencesModal 移除整項；NotificationsPage.test 同步驗 hidden |
| #3 /publish/failed stale data | ✅ shipped 2026-05-08 — 加 EmptyState guard；缺 findings/msg/id 顯「沒有失敗紀錄可顯示」+ 「前往上傳」CTA |
| `npx vitest run InstallCard.test.tsx` | ✅ 5/5 PASS |
| `npx vitest run NotificationsPage.test.tsx` | ✅ 7/7 PASS |
| `npx vitest run PublishFailedPage.test.tsx` | ✅ 6/6 PASS（AC-3 改驗 EmptyState；AC-4 補 `&msg=` 走原 render path） |
| #2 /auth-debug AppShell | ⏳ pending |
| #4 文案夾雜英文 | ⏳ pending |
| #6 /browse sort tab active highlight | ⏳ pending |

---

## 7. Result（partial — items #1 + #5 + #7）

**Shipped 2026-05-08** — 兩個 commit：
- `973007b` items #1 + #7（footer link + InstallCard 死 UI）— 3 file changes，5/5 vitest PASS
- `<S155 #5 commit>` item #5（通知偏好 hide「新版本」）— 2 file changes，7/7 vitest PASS

### 7.1 程式變動

- `frontend/src/pages/LandingPage.tsx:158`
  - footer API link 由 `<a href="/swagger-ui/index.html">API</a>` 改為 `<Link to="/docs/rest-api">API</Link>`
  - 註解標 S155 #1 + 解釋 LAB profile 沒 SpringDoc 的成因
- `frontend/src/components/v2/InstallCard.tsx:58`
  - `<span>CLI ▼</span>` 改為 `<span>Skills Hub CLI</span>`
  - 註解標 S155 #7 + 解釋移除假 dropdown 暗示
- `frontend/src/components/v2/InstallCard.test.tsx:45-49`
  - `expect(screen.getByText('CLI ▼'))` 同步改成 `'Skills Hub CLI'` + 補 negative assertion 確保 ▼ 不再出現

### 7.2 後續 follow-up（仍在 spec §2 設計內）

下個 tick 可挑剩餘 5 項中的任一項繼續：#2（auth-debug AppShell）/ #3（publish/failed guard）/ #4（文案 sweep）/ #5（通知偏好 hide）/ #6（sort tab active）。每項 1 個 component edit + 對應 test，maps 1:1 到剩餘 AC-2 / AC-3 / AC-4 / AC-5 / AC-6。

### 7.3 Drive-by 觀察（不修，留紀錄）

LandingPage.tsx:157 footer「文件」link 仍指 `/docs/your-first-skill`；S143 ship 後 AppShell nav 已改 `/docs`。footer 與 nav 不一致。本 spec 範圍未涵蓋此 link，留下次 sweep 時跟進（footer 一致性 / docs IA 對齊）。

---

## 8. 設計筆記

5 條 fix 都是獨立的小型 frontend 改動，互不依賴；可拆成 5 個 task 並行。Backend 無需修改（footer link 只是改 href）。

`/auth-debug` 訊息改寫的 trade-off：原訊息對 dev 有用（直接點出 env var 名稱），改後對普通 user 更友善但 dev 失去 hint。緩解：保留底部「除錯資訊」可摺疊區塊顯 raw env var name，default collapsed。本 spec MVP 直接 hide，跳過收合 UI；若 dev 反映需要再加。
