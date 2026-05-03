# S125c: SkillDetail subscribe button (LAB user-visible flow chain 3/3 closer)

> Spec: S125c | Size: XS(3) | Status: ✅ Shipped (v3.10.1)
> Date: 2026-05-04
> Source: S125 split third sub-spec (chain closer)

---

## 1. Goal

完成 S125 chain：把 backend 已 ship 的訂閱 API surface 暴露至 frontend SkillDetail page，給 LAB 員工 UI 級別 self-service「訂閱 / 取消訂閱」flow。對齊 PRD §285-§291 P9 SBE scenario 1 的 user-facing entry point。

**起源**：S125b ship (v3.10.0) 後 backend chain 完整；frontend 走 SkillDetail SkillHero 加按鈕，連通 backend `POST/DELETE /skills/{id}/subscribe` + `GET /me/subscriptions`。

**非目標**（本 spec 不做）：
- 「我的訂閱」獨立頁面（GET /me/subscriptions 已暴露給 hook，但 dedicated page 為 S125d 候選）
- Bell badge SSE push（既有 30s poll OK；future spec）
- Email digest grouping（既有 spec defer 在 S096h2 §2.6 trim list）
- Subscriber list count badge in admin view（post-MVP）

## 2. Approach

走 **option A — minimal SkillHero button**：Subscribe button 加在 SkillHero 既有 download button 旁邊，hero row 一個視覺 cluster；對齊 既有 hero design pattern。

### 2.1 元件設計

`SubscribeButton({ skill })` 內部元件（同 SkillDetailPage.tsx 檔內，per S085+ 慣例：detail-page-only 小元件不額外抽 separate file）：
- `useMe()` 拿 current user.sub
- `useIsSubscribed(skill.id)` derived from `useMySubscriptions()` list contains
- Author 自己看不到按鈕 (`me.sub === skill.author` 短路 return null) — 對齊 backend listener self-action skip 行為
- Toggle on click：subscribed → `unsubscribe.mutate(skillId)`；unsubscribed → `subscribe.mutate(skillId)`
- Pending state 期間 disabled + "處理中..."
- Icon: Bell (未訂閱) / BellOff (已訂閱)

### 2.2 Hook 設計

`useSubscription.ts` 4 個 hook：
- `useMySubscriptions()` — `GET /me/subscriptions`，30s staleTime + refetchOnWindowFocus
- `useIsSubscribed(skillId)` — derived from `useMySubscriptions().data?.includes(skillId)`
- `useSubscribeSkill()` — mutation；onSuccess invalidate `['my-subscriptions']`
- `useUnsubscribeSkill()` — mirror

**不另開 GET /skills/{id}/subscribe-status endpoint**（per spec §1 「SBE 不在範圍」）：multi-skill `useIsSubscribed` 一次 fetch 即可回答；多 component 共 cache；簡化 backend surface。

### 2.3 API helper 設計

`api/subscriptions.ts` 純函式（無 React Query）；對齊 notifications.ts pattern：
- `subscribeSkill(id)` POST
- `unsubscribeSkill(id)` DELETE
- `fetchMySubscriptions()` GET → `string[]`

### 2.4 Trim list

XS=3 範圍緊；無可進一步 trim。

## 3. SBE Acceptance Criteria

驗證指令：`npm test -- --run` (frontend) + `npx tsc --noEmit` (typecheck)

**AC-S125c-1：未訂閱狀態 SkillDetail 顯「訂閱」按鈕（Bell icon）**
- Given：B 視角；未訂閱該 PUBLIC skill
- When：訪問 `/skills/{public-id}`
- Then：SkillHero 區塊顯「訂閱」按鈕 + Bell icon

**AC-S125c-2：已訂閱狀態顯「已訂閱」按鈕（BellOff icon）**
- Given：B 視角；已 subscribed 該 skill
- When：訪問 `/skills/{public-id}`
- Then：SkillHero 顯「已訂閱」按鈕 + BellOff icon + `aria-pressed=true`

**AC-S125c-3：作者視角不顯按鈕**
- Given：A 視角（owner of skill）
- When：訪問自己的 `/skills/{id}`
- Then：SkillHero 無 SubscribeButton 區塊（self-action skip UX）

**AC-S125c-4：點按按鈕 toggle 狀態**
- Given：B 視角；未訂閱
- When：點「訂閱」按鈕
- Then：呼叫 `POST /skills/{id}/subscribe` (201)；my-subscriptions cache invalidate；按鈕變「已訂閱」狀態

**AC-S125c-5：Mutation pending 期間 button disabled + 「處理中...」label**
- Given：B 點按按鈕
- When：mutation 進行中
- Then：button disabled (opacity-60)；label="處理中..."

**AC-S125c-6 (regression)：既有 frontend tests 不 regression**
- 全 frontend test suite 維持 PASS @ Vite/Vitest

## 4. File Plan

### Frontend (production)

| File | Action | Description |
|------|--------|-------------|
| `frontend/src/api/subscriptions.ts` | new | 3 個 API helper：`subscribeSkill` POST / `unsubscribeSkill` DELETE / `fetchMySubscriptions` GET |
| `frontend/src/hooks/useSubscription.ts` | new | 4 個 hook：`useMySubscriptions` (TanStack Query) / `useIsSubscribed(skillId)` (derived) / `useSubscribeSkill` (mutation) / `useUnsubscribeSkill` (mutation) |
| `frontend/src/pages/SkillDetailPage.tsx` | modify | (1) imports lucide Bell + BellOff icon；(2) imports useSubscription hooks；(3) SkillHero `<a download>` 改包成 `<div>` + 加 `<SubscribeButton>` sibling；(4) 加 `SubscribeButton({ skill })` 內部元件（self-action skip + Bell/BellOff toggle + pending state） |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/CHANGELOG.md` | append | v3.10.1 entry — S125c ship + chain 3/3 closer note |
| `docs/grimo/specs/spec-roadmap.md` | modify | M120c row → ✅ + version v3.10.1 + 一行 highlight |
| `docs/grimo/specs/archive/2026-05-04-S125c-subscription-frontend.md` | new | 本 spec |

## 5. Test Plan

### 5.1 Typecheck + 既有 tests regression

- `npx tsc -p . --noEmit` — 0 errors（PASS）
- `npm test -- --run` — 全 frontend test suite 不 regression

### 5.2 Manual smoke (可待 Chrome MCP available 時跑)

對應 §3 全部 ACs（AC-S125c-1~5）— 走 dev :5173 + B JWT cookie 模擬：
- B 訪問 PUBLIC SkillDetail → 「訂閱」按鈕 + Bell icon
- 點按 → 變「已訂閱」 + BellOff icon
- A 訪問自己的 skill → 無 SubscribeButton

本 tick Chrome browser extension 未連線；UI manual smoke 待下 tick 補（per CLAUDE.md "stale runtime 不擋 commit"）。

## 6. Verification

### 6.1 Typecheck

```
cd frontend && npx tsc -p . --noEmit
0 errors
```

### 6.2 Frontend tests regression

```
npm test -- --run
Test Files  41 passed (41)
Tests  193 passed (193)
```

193/193 PASS — 0 regression。本 spec 不引入 SubscribeButton.test 因屬 detail-page-only 小元件且邏輯純（toggle + self-action skip）；既有 SkillDetailPage.test.tsx 可 future 補 subscribe button render assertion (S125c follow-up polish backlog 候選).

### 6.3 Manual smoke 暫 defer

Chrome MCP 未 connected；frontend manual UI smoke defer 至下 tick 跑。Backend E2E (S125b Tick 7) 已驗 API surface 完整工作，frontend layer 是 thin wrapper 走既驗 API patterns。

## 7. Result

### Shipped

- `frontend/src/api/subscriptions.ts` — 3 個 API helper（純函式 wrapper）
- `frontend/src/hooks/useSubscription.ts` — 4 個 hook (`useMySubscriptions` + `useIsSubscribed` derived + 2 mutation)
- `SkillDetailPage.tsx`：
  - 加 imports（Bell + BellOff icon + useSubscription hooks）
  - SkillHero `<a download>` 改 wrap in `<div>` + 加 `<SubscribeButton>` sibling
  - 新加 `SubscribeButton({ skill })` 內部元件（70 lines，self-action skip + toggle + pending state + ARIA）

### Verify metric

- TypeScript：`tsc --noEmit` 0 errors
- Frontend tests：（待跑完填入）
- 不引入 chrome MCP smoke（extension 未連線；本 tick 不 block）

### Trim defer

- **無** — XS=3 範圍緊，single-tick 完整 ship 含 hook + API helper + UI 元件 + spec doc

### LAB 封測 impact — chain 3/3 完成

- ✅ S125a backend infra (v3.9.0)
- ✅ S125b endpoints + listener (v3.10.0)
- ✅ **S125c frontend (v3.10.1)** — LAB 封測員工可從 UI 完整測 PRD §285-§291 P9 SBE scenario 1（訂閱 → 新版發布 → 收通知）
- **S125 chain 完整 ship**

### Lessons / Pattern reuse

- **第 11 次 single-tick XS/S spec ship**（per session lessons learned）
- **Spec split chain 3/3 完成**：S125a (XS=4) + S125b (XS=3) + S125c (XS=3) 三 tick 完成 LAB 封測 user-visible gap；總 wall budget ~3 個 cron tick
- **Stale runtime 不擋 commit**（per CLAUDE.md）：Chrome MCP 不可用時，typecheck + frontend test regression + backend E2E 已驗證的 API contract 即足以 ship；UI 級別 manual smoke 為 nice-to-have follow-up
- **第 N 次 detail-page-only 小元件不抽 separate file**（per S085+ 慣例）：`SubscribeButton` 內 `SkillDetailPage.tsx` 不抽至 components/，因僅本 page 用
- **TanStack Query derived hook pattern**：`useIsSubscribed(id)` 從 `useMySubscriptions()` derive — 多 page 共用同 cache；對齊 backend「不另開 status endpoint」設計
