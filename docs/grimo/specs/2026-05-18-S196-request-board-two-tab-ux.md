# S196: Request Board 兩頁籤 UX

> 規格：S196 | 大小：XS(8) | 狀態：⏳ Design
> 日期：2026-05-18
> 對應：PRD §P8 / S156c / `docs/grimo/ui/prototype/Skills Hub Request Board.html`

---

## 1. 目標

`/requests` 要讓使用者很快完成兩件事：瀏覽大家缺哪些 skill，或把自己的工作問題開成一筆需求。

這次只改前端 UI 與測試，不新增後端狀態模型。現有 API 已能做 `GET /requests`、`POST /requests`、`POST /requests/{id}/vote`、`GET /requests/{id}` 與留言；本 spec 把現有能力整理成兩個主頁籤：

```
/requests
  ├─ 瀏覽需求：搜尋 / 排序 / 我也要 / 需求排行 / 勇者排行提示
  └─ 我要開需求：inline form 發起需求，不再跳 modal
```

### Scope

| In | Out |
|---|---|
| `/requests` 兩個主頁籤：`瀏覽需求` / `我要開需求` | 不新增 `closed` / `status` 欄位 |
| 將 `CreateRequestModal` 改為頁籤內 inline form | 不復活 claim / fulfill endpoints |
| 列表保留 vote、detail link、comments 入口 | 不設計新的 notification flow |
| 需求排行以既有 `voteCount` 排序顯示 | 不做已結案排除，因 current API 無 closed data |
| 更新 prototype / DESIGN page note / frontend tests | 不改 DB migration |

### Dependency

| Dependency | 類型 | 判斷 |
|---|---|---|
| S156c | Code-level shipped | 已提供 simplified request API 與 frontend route。本 spec 只重排 UI，不 import 未 shipped 型別。 |
| S195 | 無重疊 | Active spec 是 Skill edit upload validation UX，與 `/requests` 無共同 production files。 |

## 2. 研究與設計

### 2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
|------|----------|--------------|
| `docs/grimo/PRD.md` §P8 | Request Board 的產品目的：使用者公開發起需求，社群投票決定優先級。舊 PRD 提過 claim/fulfill，但 S156c 已取消該流程。 | UI 文案要聚焦「發起需求 + 我也要投票」，不要再把頁面做成接案系統。 |
| `docs/grimo/specs/archive/2026-05-12-S156c-request-voting-board.md` | S156c 明確把 Request 從 post → claim → fulfill 改成 post → vote / comment。`status`、`claimerId`、`fulfilledSkillId` 已移除。 | 本 spec 不新增「尚無勇者 / 接手中 / 已結案」主頁籤，也不傳 `?status=` query。 |
| `frontend/src/pages/RequestBoardPage.tsx` | 現在頁面只顯示 header、`發起新需求` CTA、列表 row，create flow 是 modal。 | 改成 two-tab page；`CreateRequestModal` 可被 inline panel 取代。 |
| `frontend/src/api/skills.ts` | `fetchRequests({sort})` 只支援 `votes | created`；`SkillRequest` 沒有 status field。 | 排序 controls 只做 `票數最高` / `最新` / local `我投過` hint，不設 status filter。 |
| `frontend/src/components/VoteButton.tsx` | Vote button already has optimistic toggle、`aria-pressed`、server response sync。 | 需求卡沿用 `VoteButton`，不重寫投票狀態。 |
| `docs/grimo/ui/DESIGN.md` | `/requests` prototype source 是 `docs/grimo/ui/prototype/Skills Hub Request Board.html`。 | prototype 已更新成兩頁籤示意；implementation 要同步該方向。 |
| [WAI-ARIA APG Tabs Pattern](https://www.w3.org/WAI/ARIA/apg/patterns/tabs/) | Tabs 是一組只顯示一個 panel 的內容；active tab 要 `aria-selected=true`，其他 tab 要 `false`。 | `瀏覽需求` / `我要開需求` 要用 `role="tablist"`、`role="tab"`、`role="tabpanel"` 與 keyboard 可操作狀態。 |

### 2.2 架構設計

這次是 frontend-only。資料流不變：

```
使用者點「瀏覽需求」
  → RequestBoardPage activeTab = "browse"
  → useRequests({ sort: "votes" | "created" })
  → GET /api/v1/requests?sort=votes
  → 顯示需求卡 + VoteButton + detail link

使用者點「我要開需求」
  → RequestBoardPage activeTab = "create"
  → RequestCreatePanel 顯示 inline form
  → POST /api/v1/requests { title, description }
  → invalidate ["requests"]，切回瀏覽需求並看到新資料
```

### 2.3 做法比較

| 做法 | 採用 | 理由 |
|----------|--------|-----------|
| A: 兩個主頁籤：`瀏覽需求` / `我要開需求` | yes | 使用者確認此方向。頁面工作最清楚：看需求或開需求。 |
| B: 保留目前列表 + `發起新需求` modal | no | 開需求被藏在 CTA 後面；使用者說此頁主要就兩件事，modal 讓第二件事不夠像主功能。 |
| C: 用 `尚無勇者 / 接手中 / 已結案` 狀態頁籤 | no | current API 沒 status；S156c 已移除 claim/fulfill state machine，做狀態頁籤會變成假功能。 |

### 2.4 Low-Fidelity UI Sketches

不是最終像素稿；此 sketch 只鎖定 layout 與互動。

Desktop:

```text
┌──────────────────────────────────────────────────────────────┐
│  只要有人需要，就會有勇者出現。                              │
│  [瀏覽需求] [開新需求]                                       │
└──────────────────────────────────────────────────────────────┘

┌────────────────────── tabs ──────────────────────┐
│ [瀏覽需求 42]                         [我要開需求]│
└───────────────────────────────────────────────────┘

瀏覽需求 tab
┌──────────────────────────────┬───────────────────┐
│ 搜尋框 [票數最高] [最新] [我投過] │ 需求排行榜        │
│ ┌我也要 38│需求 title / desc│按鈕┐ │ 1. 月報抓數據 38 │
│ ┌我也要 24│需求 title / desc│按鈕┐ │ 2. Gradle... 24  │
│ ┌我也要 19│需求 title / desc│按鈕┐ │ 勇者排行榜        │
└──────────────────────────────┴───────────────────┘

我要開需求 tab
┌──────────────────────────────┬───────────────────┐
│ 我要開需求 form               │ 送出後會發生什麼   │
│ - 需求標題                    │ 1. 出現在瀏覽需求 │
│ - 工作問題                    │ 2. 票數推高排序   │
│ - 希望 skill 做到什麼          │ 3. 作者自願接手   │
│ - 分類                        │                   │
│ [送出需求]                    │                   │
└──────────────────────────────┴───────────────────┘
```

Mobile:

```text
[瀏覽需求 42]
[我要開需求]

瀏覽需求：搜尋 → chips → request cards → rankings
我要開需求：form → rule cards
```

### 2.5 Task 邊界提示

| Task 候選 | Class / file | 來源 | 正向情境 | 反向情境 | POC |
|-----------|--------------|------|----------|----------|-----|
| T01 | `frontend/src/pages/RequestBoardPage.tsx` | Prototype + S156c | 頁面顯示兩個主 tab，預設在 `瀏覽需求` | 不再顯示「尚無勇者 / 接手中 / 已結案」主頁籤 | not required |
| T02 | `frontend/src/components/RequestCreatePanel.tsx` 或併入 page | `CreateRequestModal.tsx` | 在 `我要開需求` tab 填 title/description 後 POST `/requests` | 未登入時走 `AuthGatedButton` login，不直接 POST | not required |
| T03 | `RequestBoardPage` list/ranking layout | `VoteButton.tsx`, `useRequests.ts` | `票數最高` 呼叫 `sort=votes`，`最新` 呼叫 `sort=created` | 不傳 `?status=`，避免 S159a 400 | not required |
| T04 | Tests + design sync | `RequestBoardPage.test.tsx`, prototype, DESIGN | RTL 驗 tabs、form POST、sort query、no spec ID leak | 測試不依賴 CSS class 或 pixel snapshot | not required |

## 3. 驗收條件（SBE）

驗證命令：

執行：`cd frontend && npm test && npm run verify`
通過條件：所有帶 `S196` / `AC-S196-*` 的測試都是綠燈，且 TypeScript / ESLint 通過。

| AC | 優先級 | 驗證方式 | 標題 |
|----|----------|--------|-------|
| AC-S196-1 | 必做 | Test | `/requests` 顯示兩個主頁籤 |
| AC-S196-2 | 必做 | Test | 瀏覽需求頁籤顯示列表、投票與排行 |
| AC-S196-3 | 必做 | Test | 我要開需求頁籤 inline submit |
| AC-S196-4 | 必做 | Test | 排序只使用既有 `votes` / `created` API |
| AC-S196-5 | 必做 | Test | 空狀態提供開需求入口 |
| AC-S196-6 | 必做 | Test / Inspection | Tabs a11y contract |
| AC-S196-7 | 必做 | Inspection | Prototype 與 DESIGN 同步 |

**AC-S196-1: `/requests` 顯示兩個主頁籤**
- Given（前提）使用者打開 `/requests`
- When（動作）頁面載入完成
- Then（結果）畫面有 `瀏覽需求` 與 `我要開需求` 兩個 tab
- And（而且）畫面沒有 `尚無勇者`、`接手中`、`已結案` 這三個主頁籤

**AC-S196-2: 瀏覽需求頁籤顯示列表、投票與排行**
- Given（前提）`GET /api/v1/requests?sort=votes` 回傳三筆需求，票數分別為 38、24、19
- When（動作）使用者停在 `瀏覽需求` tab
- Then（結果）畫面顯示三張需求卡，每張卡有 title、description、vote count、`我也要` 投票按鈕與 detail link
- And（而且）右側 `需求排行榜` 依 38、24、19 的票數順序顯示同三筆需求

**AC-S196-3: 我要開需求頁籤 inline submit**
- Given（前提）使用者已登入，停在 `我要開需求` tab
- When（動作）輸入 title `docker compose linter` 與 description `檢查 compose.yaml 結構`，按 `送出需求`
- Then（結果）frontend 發出 `POST /api/v1/requests`，body 含 `{"title":"docker compose linter","description":"檢查 compose.yaml 結構"}`
- And（而且）成功後 invalidate `["requests"]`，畫面回到 `瀏覽需求` tab 或顯示成功後的瀏覽入口

**AC-S196-4: 排序只使用既有 `votes` / `created` API**
- Given（前提）使用者在 `瀏覽需求` tab
- When（動作）點 `票數最高`
- Then（結果）frontend 呼叫 `/api/v1/requests?sort=votes`
- When（動作）點 `最新`
- Then（結果）frontend 呼叫 `/api/v1/requests?sort=created`
- And（而且）任何 request URL 都不包含 `status=` query param

**AC-S196-5: 空狀態提供開需求入口**
- Given（前提）`GET /api/v1/requests` 回傳空陣列
- When（動作）使用者停在 `瀏覽需求` tab
- Then（結果）畫面顯示「目前還沒人發起需求」類似文案
- And（而且）主要 action 是切到 `我要開需求` tab，不是跳離 `/requests`

**AC-S196-6: Tabs a11y contract**
- Given（前提）使用者用鍵盤或 screen reader 操作 `/requests`
- When（動作）焦點停在 tablist
- Then（結果）tablist 有 accessible name，兩個 tab 各自有 `aria-selected`
- And（而且）active tab 對應的 panel 可見，inactive panel 不在主要閱讀順序中

**AC-S196-7: Prototype 與 DESIGN 同步**
- Given（前提）spec 實作完成
- When（動作）檢查 `docs/grimo/ui/prototype/Skills Hub Request Board.html` 與 `docs/grimo/ui/DESIGN.md`
- Then（結果）prototype 仍呈現 `瀏覽需求` / `我要開需求` 兩頁籤方向
- And（而且）DESIGN page inventory 的 `/requests` note 不再描述 status-tab 或 claim/fulfill UI

### 非功能需求檢查

| 分類 | 對應驗收 | 說明 |
|---|---|---|
| Performance | AC-S196-2 / AC-S196-4 | 不新增 API；列表仍只打一個 `GET /requests?sort=`。排行榜由同一份 list data 取前 N，不額外打 request。 |
| Security | AC-S196-3 | 開需求仍走 existing `POST /requests` 與 backend sanitizer；未登入時沿用 `AuthGatedButton` lazy gate。 |
| Reliability | AC-S196-3 / AC-S196-4 | POST 成功後 invalidate `["requests"]`；排序只用已支援參數，避免 unknown param 400。 |
| Usability | AC-S196-1 / AC-S196-5 / AC-S196-6 | 主功能只剩兩個頁籤；空狀態能直接進開需求；tabs 可被輔助科技辨識。 |
| Maintainability | AC-S196-7 | prototype、DESIGN、tests 同步，避免 UI source of truth 分裂。 |

## 4. 介面與 API 設計

### 4.1 Frontend state

```ts
type RequestBoardTab = 'browse' | 'create'
type RequestSortMode = 'votes' | 'created'
```

`RequestBoardPage` local state:

```ts
const [activeTab, setActiveTab] = useState<RequestBoardTab>('browse')
const [sort, setSort] = useState<RequestSortMode>('votes')
const { data: requests, isLoading } = useRequests({ sort })
```

### 4.2 Component shape

```tsx
function RequestBoardPage() {
  return (
    <AppShell>
      <RequestHero />
      <RequestTabs activeTab={activeTab} onChange={setActiveTab} />
      {activeTab === 'browse' ? (
        <RequestBrowsePanel sort={sort} onSortChange={setSort} />
      ) : (
        <RequestCreatePanel onCreated={() => setActiveTab('browse')} />
      )}
    </AppShell>
  )
}
```

### 4.3 Existing APIs reused

| User action | Existing API | Body / Query |
|---|---|---|
| 瀏覽票數最高 | `GET /api/v1/requests?sort=votes` | none |
| 瀏覽最新 | `GET /api/v1/requests?sort=created` | none |
| 按「我也要」 | `POST /api/v1/requests/{id}/vote` | none |
| 開需求 | `POST /api/v1/requests` | `{ title, description }` |
| 進 detail | `<Link to="/requests/:id">` | route only |

### 4.4 Copy contract

| 位置 | 文字方向 |
|---|---|
| Hero H1 | `只要有人需要，就會有勇者出現。` |
| Hero body | 先瀏覽大家缺哪些 skill，再把自己的工作問題開成新需求。 |
| Tabs | `瀏覽需求` / `我要開需求` |
| Vote button | 沿用現有 accessible label；視覺可顯示 `我也要` |
| Create submit | `送出需求` |

## 5. 檔案規劃

| 檔案 | 動作 | 說明 |
|------|--------|-------------|
| `frontend/src/pages/RequestBoardPage.tsx` | modify | 改成 two-tab shell；移除 modal state；新增 sort state；列表與開需求 panel 切換。 |
| `frontend/src/components/CreateRequestModal.tsx` | delete / replace | 若沒有其他 caller，改成 `RequestCreatePanel.tsx` inline form 後刪除；若保留則改名並移除 dialog wrapper。 |
| `frontend/src/components/RequestCreatePanel.tsx` | new | Inline form，沿用 `createRequest` mutation、`localizeApiError`、`AuthGatedButton`。 |
| `frontend/src/pages/RequestBoardPage.test.tsx` | modify | 更新 AC-S196 tests：tabs、sort query、inline submit、空狀態、no status tab。 |
| `docs/grimo/ui/prototype/Skills Hub Request Board.html` | modify | 已更新成兩頁籤示意；implementation 後確認同步。 |
| `docs/grimo/ui/DESIGN.md` | modify | `/requests` page inventory note 加 S196 two-tab direction。 |
| `docs/grimo/specs/spec-roadmap.md` | modify | 新增 S196 active row。 |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
