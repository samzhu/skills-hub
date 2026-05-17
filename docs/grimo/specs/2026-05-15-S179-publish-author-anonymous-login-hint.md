# S179 — Publish Author Anonymous Login Hint

> SpecID: S179
> Status: ✅ QA PASS — next `$shipping-release S179`
> Date: 2026-05-15
> Size: XS(7)
> Related: S004 publish UI, S139 login UI + lazy auth gate, S154/S154b author display identity, S176 explicit publish skill name

---

## 1. Goal

`frontend/src/pages/PublishPage.tsx:222` 的作者欄位在未登入時目前顯示空白；S179 要讓未登入使用者在 `/publish` 直接看到「請先登入後發布」，並且點「發佈技能」仍走既有登入流程。

這個 bug 只在前端表單顯示層。後端已在 S154 改成從 auth context 取 author，`frontend/src/pages/PublishPage.tsx:52` 的 `uploadSkill(...)` 也不再送 `author` 欄位；所以本 spec 不新增 API、不改 DB、不放寬 security。它只補 `useAuth()` 與 `useMe()` 狀態不同步時的 UI 文案。

相依狀態：

| Spec | 狀態 | 是否阻擋 S179 |
| --- | --- | --- |
| S139 login UI + lazy auth gate | ✅ shipped | 已提供 `useAuth().login()` 與 anonymous/loading/authenticated 三態 |
| S154/S154b author display identity | ✅ shipped | 已移除可編輯 author input；S179 補 anonymous/loading 顯示 |
| S176 explicit publish skill name | ✅ shipped | 已確認 submit `FormData` 不含 `author`；S179 不改 submit shape |
| S178 browse search cleanup | 📐 in-design | 不相關，無 production import |

Spec overlap scan：active specs S162b、S175、S178 沒有改 `/publish` 作者欄位；S154b 是 archived shipped spec，作為歷史依據，不是 overlap。

## 2. Research And Design

### 2.1 Current Facts

| 來源 | 查到什麼 | 對設計的影響 |
| --- | --- | --- |
| `frontend/src/pages/PublishPage.tsx:41-43` | `useMe()` 回來的 `me` 用來顯示作者身份。 | `/api/v1/me` 尚未回、401、或 query error 時，`me` 會是 `undefined`。 |
| `frontend/src/pages/PublishPage.tsx:73-80` | submit 時如果 `auth.status !== 'authenticated'`，會呼叫 `auth.login()`，完成後回 `/publish`。 | 不需要新增登入按鈕流程；作者欄位只要把同一個事實提前講清楚。 |
| `frontend/src/pages/PublishPage.tsx:222-235` | 作者 display 目前用 `getDisplayName({ author: me?.userId ?? '' })`。 | `me` 缺資料時 `author` 是空字串，畫面就空掉。這正是截圖中的 bug。 |
| `frontend/src/hooks/useAuth.ts:30-74` | `useAuth()` 明確有 `loading`、`anonymous`、`authenticated` 三種狀態。 | 作者欄位應該跟著 auth state 顯示三種文案，而不是只看 `me`。 |
| `frontend/src/api/auth.ts:35-42` | `fetchMe()` 對 `/api/v1/me` 的 401 回 `null`，不 throw。 | 未登入狀態可以穩定判斷為 `auth.status === 'anonymous'`。 |
| `frontend/src/components/AuthArea.tsx:22-40` | Header 已用 skeleton / 登入按鈕處理 loading / anonymous。 | `/publish` 作者欄位可沿用同一套語意：loading 不顯空白，anonymous 顯登入提示。 |
| `frontend/src/pages/PublishPage.test.tsx:107-231` | 現有測試已 mock `useMe` 與 `useAuth`，並驗 author display 和 submit shape。 | 新測試集中加在同一檔，不需要 Playwright。 |

### 2.2 UI State Design

作者欄位改成由 `auth.status` 決定顯示：

| auth 狀態 | 作者欄位顯示 | handle chip | 使用者按「發佈技能」 |
| --- | --- | --- | --- |
| `loading` | `正在確認登入狀態...` | 不顯示 | 若此時按到，沿用既有 `auth.login()` |
| `anonymous` | `請先登入後發布` | 不顯示 | 呼叫 `auth.login()`，不送 `/api/v1/skills/upload` |
| `authenticated` + `me` 有資料 | `getDisplayName({ userId/name/email/handle })` | 有 `me.handle` 時顯示 `@handle` | 送出既有 upload request |
| `authenticated` + `me` 尚未回 | `正在確認作者...` | 不顯示 | submit 可沿用既有行為；若 upload request 發生 401/403，既有 failed page 處理 |

Low-fidelity sketch（不是新設計系統，不改頁面 layout，只改作者欄位內文）：

```text
作者
┌──────────────────────────────────────────────┐
│ 請先登入後發布                                │  anonymous
└──────────────────────────────────────────────┘

作者
┌──────────────────────────────────────────────┐
│ Alice Chen                         @alice     │  authenticated
└──────────────────────────────────────────────┘
```

### 2.3 Approach Comparison

| Approach | 改哪裡 | 使用者實際看到 | 成本 |
| --- | --- | --- | --- |
| A. 只在空字串 fallback 成「請先登入後發布」 | `PublishPage.tsx` display expression | 未登入不再空白；但 loading/authenticated-without-me 也會混在同一文案 | 最小，但狀態不清楚 |
| B. 用 `auth.status` 做三態顯示（recommended） | `PublishPage.tsx` 抽 `authorDisplay` 小段 computed value；測三個狀態 | 未登入顯「請先登入後發布」；確認中顯 loading；登入後顯姓名與 handle | 小改動，符合既有 `useAuth` 狀態模型 |
| C. 整個 `/publish` 頁匿名時改成登入 gate 空狀態 | `PublishPage.tsx` 在 form 前 return login-only UI | 未登入看不到已填表單；登入後回來重填 | 會改變既有 lazy gate 行為，且表單內容不能先填 |

Chosen approach: B。

### 2.4 Task Boundary

| Task | File | 正向情境 | 反向情境 | POC |
| --- | --- | --- | --- | --- |
| T01 | `frontend/src/pages/PublishPage.tsx` | anonymous 作者欄位顯「請先登入後發布」；authenticated 顯 `Alice Chen @alice` | anonymous 不顯空白、不顯 `@undefined` | not required |
| T02 | `frontend/src/pages/PublishPage.test.tsx` | 測 anonymous/loading/authenticated display；測 anonymous submit 只 call `login()` | anonymous submit 不呼叫 `/api/v1/skills/upload` | not required |

## 3. Acceptance Criteria

Verification command:

Run: `cd frontend && npm test`

Pass: all tests carrying `S179` AC ids are green.

| AC | Priority | Verification | Title |
| --- | --- | --- | --- |
| AC-S179-1 | must | Test | 未登入作者欄位顯示登入提示 |
| AC-S179-2 | must | Test | 未登入送出只啟動登入流程 |
| AC-S179-3 | must | Test | 登入中不顯空白作者 |
| AC-S179-4 | must | Test | 已登入作者顯示維持 S154b 行為 |

### AC-S179-1 — 未登入作者欄位顯示登入提示

Given（前提）`useAuth()` 回 `status: 'anonymous'`，且 `useMe()` 沒有 current user data

When（動作）使用者打開 `/publish`

Then（結果）作者欄位 `data-testid="publish-author-display"` 內顯示 `請先登入後發布`

And（而且）作者欄位不顯示空字串佔位、不顯示 `@undefined`、不顯示 OAuth raw `sub`

### AC-S179-2 — 未登入送出只啟動登入流程

Given（前提）`useAuth()` 回 `status: 'anonymous'`，使用者已填技能名稱、分類，並在 text mode 貼上合法 `SKILL.md`

When（動作）使用者點「發佈技能」

Then（結果）`useAuth().login()` 被呼叫一次

And（而且）前端不送 `POST /api/v1/skills/upload`

### AC-S179-3 — 登入中不顯空白作者

Given（前提）`useAuth()` 回 `status: 'loading'`，且 `useMe()` 還沒有 current user data

When（動作）使用者打開 `/publish`

Then（結果）作者欄位顯示 `正在確認登入狀態...`

And（而且）作者欄位不顯示空字串佔位、不顯示 handle chip

### AC-S179-4 — 已登入作者顯示維持 S154b 行為

Given（前提）`useAuth()` 回 `status: 'authenticated'`，`useMe()` 回 `name: 'Alice Chen'`、`handle: 'alice'`、`userId: 'u_a3f9c1'`

When（動作）使用者打開 `/publish`

Then（結果）作者欄位顯示 `Alice Chen`

And（而且）同一列顯示 `@alice`

And（而且）作者欄位仍不是 textbox，`FormData` 仍不含 `author`

### NFR Coverage

| Category | Coverage | Reason |
| --- | --- | --- |
| Performance | N/A | 只改一個已渲染欄位的文案；不新增 network request。 |
| Security | AC-S179-2 | 未登入送出不得繞過登入，也不得送 upload request。 |
| Reliability | AC-S179-1, AC-S179-3 | `/api/v1/me` 還沒回或 401 時不再出現空白作者。 |
| Usability | AC-S179-1, AC-S179-3, AC-S179-4 | 使用者能直接看懂作者欄位目前代表未登入、確認中、或已登入身份。 |
| Maintainability | AC-S179-4 | 維持 S154b 的 read-only author display 與 no-author-FormData contract。 |

## 4. Interface Design

Production code only needs local UI state, no API contract change.

Expected helper shape inside `PublishPage`:

```tsx
const authorDisplay = (() => {
  if (auth.status === 'loading') return { label: '正在確認登入狀態...', handle: null }
  if (auth.status === 'anonymous') return { label: '請先登入後發布', handle: null }
  if (!me) return { label: '正在確認作者...', handle: null }
  return {
    label: getDisplayName({
      author: me.userId,
      authorDisplayName: me.name,
      authorEmail: me.email,
      authorHandle: me.handle,
    }),
    handle: me.handle,
  }
})()
```

Render rule:

- `<span>{authorDisplay.label}</span>` always has visible text.
- Handle chip renders only when `authorDisplay.handle` is truthy.
- No `<Input>` is reintroduced for author.
- `uploadSkill(...)` signature and `FormData` shape stay unchanged.

## 5. File Plan

| File | Action | Notes |
| --- | --- | --- |
| `frontend/src/pages/PublishPage.tsx` | modify | Add auth-state-aware author display text. |
| `frontend/src/pages/PublishPage.test.tsx` | modify | Add S179 tests for anonymous/loading/authenticated author display and anonymous submit behavior. |
| `docs/grimo/specs/spec-roadmap.md` | modify | Add S179 row as `📐 in-design`. |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
## 6. Task Plan

POC：not required — S179 只改既有 React page 的 local auth-state display；不新增 API、DB schema、套件或 framework SPI。

| 順序 | Task file | AC | 狀態 | 驗證 |
|---:|---|---|---|---|
| 1 | `docs/grimo/tasks/2026-05-17-S179-T01-publish-author-auth-state-display.md` | AC-S179-1, AC-S179-2, AC-S179-3, AC-S179-4 | PASS（2026-05-17） | `cd frontend && npm test -- PublishPage` |

## 7. Implementation Results

2026-05-17 S179-T01 result：

- [PublishPage.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/PublishPage.tsx) 新增 `authorDisplay`，依 `auth.status` 顯示作者欄位：
  - `loading` → `正在確認登入狀態...`
  - `anonymous` → `請先登入後發布`
  - `authenticated + me` → 維持 S154b 的 `getDisplayName(...)` 與 `@handle`
- [PublishPage.test.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/PublishPage.test.tsx) 補 AC-S179-1~4。
- Red：`cd frontend && npm test -- PublishPage` → FAIL；`AC-S179-1` 和 `AC-S179-3` 都收到空白作者欄位。
- Green：`cd frontend && npm test -- PublishPage` → PASS（1 file / 17 tests）。
- Verify：`cd frontend && npm run verify` → PASS。

| AC | 結果 | 證據 |
|---|---|---|
| AC-S179-1 | PASS | anonymous 時 `publish-author-display` 顯示 `請先登入後發布`。 |
| AC-S179-2 | PASS | anonymous submit 呼叫 `auth.login()`，不呼叫 `/api/v1/skills/upload`。 |
| AC-S179-3 | PASS | loading 時 `publish-author-display` 顯示 `正在確認登入狀態...`，不顯示 handle chip。 |
| AC-S179-4 | PASS | authenticated 時維持 `Alice Chen` 與 `@alice`，作者欄位仍不是 textbox。 |

2026-05-17 QA Review：

- `frontend/src/pages/PublishPage.tsx` 的 `handleSubmit` 仍在 `auth.status !== 'authenticated'` 時直接呼叫 `auth.login()` 並 `return`，所以 anonymous / loading 不會送 `/api/v1/skills/upload`。
- `frontend/src/api/skills.ts` 的 `uploadSkill(...)` 仍只 append `skillName`、`file`、非空 `version`、`category`、`visibility`；沒有 append `author`。
- `PublishPage.test.tsx` 已覆蓋 AC-S179-1~4，且既有 S154b/S176/S188 tests 仍保護作者欄位不是 textbox、FormData 不含 `author`。
- `cd frontend && npm test -- PublishPage` → PASS（1 file / 17 tests）。
- `cd frontend && npm run verify` → PASS。
- `cd frontend && npm test` → PASS（80 files / 464 tests）。

QA verdict：PASS。本 spec 是前端 local UI state change，沒有新增 API、DB schema、後端 wiring、browser fixture 或 production deploy requirement。下一輪 `$shipping-release S179` 必須重新執行 `./scripts/verify-all.sh` 作為 release preflight，然後歸檔 spec、刪 task file、補 changelog / roadmap / tag。
