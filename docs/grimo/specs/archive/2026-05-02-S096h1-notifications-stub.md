# S096h1 — Notifications Stub + Bell Badge (S096 META 7a/8)

> **Status**: shipped
> **Type**: stub backend endpoint + frontend page + AppShell bell badge integration
> **Estimate**: M(12) → trim 至 XS(6)
> **Source**: PRD §P9 + Engineering Handoff §2.17 + prototype `Skills Hub Notifications.html`

## §1 Goal

Skills Hub 缺 user-facing notification 機制 — 訂閱 skill 的版本更新、flag、審核回應等動態無集中可見處。完整 S096h 含 notifications projection from domain_events + 4 endpoints + WebSocket / poll + Version Diff page，scope M(12)。

本 stub 先 ship 「nav bell badge + 空 list page」讓 feature 對外 visible，正式 projection / mark-read mutation / preferences / Version Diff 留 S096h2.

模式同 f1 / g1：disabled 互動 + EmptyState clear tone「都看完了，沒有未讀通知」.

## §2 Approach

### §2.1 Trim from M(12) → XS(6)

**Defer S096h2**:
- Notifications projection from `domain_events` (per-user subscription join filter)
- Mutation endpoints: POST /notifications/read-all / GET-PATCH /users/notification-preferences / GET-DELETE /users/subscriptions
- WebSocket 即時 push（or aggressive polling）
- Version Diff page `/skills/:author/:name/diff?from=&to=`
- New Modulith `notification` module @ApplicationModule registration

**Ship in h1**:
- Backend stubs:
  - `GET /api/v1/notifications` returns `[]`
  - `GET /api/v1/notifications/unread-count` returns `{count: 0}`
- Frontend `/notifications` route + NotificationsPage with EmptyState clear tone
- AppShell **bell icon top-right** (per Engineering Handoff §2.17)
  - Polls unread-count every 30s（react-query refetchInterval）
  - Badge shows count when >0；hide when 0
  - Click → /notifications

### §2.2 AppShell layout change

- Layout change: nav `<nav>` 改 `flex-1` 撐開，bell icon `<Link>` 推至 right side
- 影響: AppShell-rendered page tests (App.test.tsx + YourFirstSkillPage.test.tsx) 需 wrap QueryClientProvider — 因 useQuery dep
- Per ALWAYS rule「verify a public signature change against every caller」：tests patched 同 commit

### §2.3 Why polling not WebSocket

WebSocket 為 Spring Boot 4 新 surface (per S096 META §2.4 risk register medium); poll 30s 簡單 + 無 framework risk + LAB mode 無 user session concept. WebSocket 留 S096h2 真正 projection ship 時評估.

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | Backend `GET /api/v1/notifications` | 200 + `[]` |
| AC-2 | Backend `GET /api/v1/notifications/unread-count` | 200 + `{count: 0}` |
| AC-3 | Frontend `/notifications` route | renders NotificationsPage |
| AC-4 | NotificationsPage 0 results | EmptyState clear tone「都看完了，沒有未讀通知」+ 3 stats placeholder |
| AC-5 | NotificationsPage with data | rows show category dot icon + title + body + time + unread indicator |
| AC-6 | AppShell bell icon | visible top-right；clicks navigate to /notifications |
| AC-7 | AppShell bell badge | hidden when count=0; show count when >0; show "99+" when >99 |
| AC-8 | AppShell bell badge | refetches every 30s (per Handoff §2.17) |
| AC-9 | Tests 28/28 PASS | regression check (App.test + YourFirstSkillPage.test patched for QueryClientProvider) |
| AC-10 | Build ≤ 410KB JS | budget |
| AC-11 | Backend compileJava | success |

## §4 Implementation file plan

```
backend/src/main/java/io/github/samzhu/skillshub/notification/
└── NotificationController.java   ← NEW (2 endpoint stubs + 2 records)

frontend/src/
├── api/skills.ts                 ← + fetchNotifications + fetchUnreadCount + Notification type
├── pages/NotificationsPage.tsx   ← NEW (~85 LOC; EmptyState clear + NotificationRow + CategoryDot)
├── components/AppShell.tsx       ← + Bell icon + useQuery for unread count + badge UI
├── App.tsx                       ← + /notifications route
└── App.test.tsx + pages/docs/YourFirstSkillPage.test.tsx ← + QueryClientProvider wrap
```

## §5 Test plan

- `./gradlew compileJava` ✓
- `npm test` — 28/28 PASS（QueryClientProvider patched for AppShell-rendering tests）
- `npm run build` — JS ≤ 410KB
- Manual smoke (after backend restart):
  - `curl http://localhost:8080/api/v1/notifications/unread-count` → `{"count":0}`
  - 瀏覽器 right-side bell icon 顯示（badge 隱藏因 0）；點 → /notifications

## §6 Verification

實際結果 §7。

## §7 Result

- **Backend `compileJava`**: BUILD SUCCESSFUL ✓
- **Frontend tests**: 28 → 28 PASS / 0 fail (App.test + YourFirstSkillPage.test 兩個 patch QueryClientProvider wrap)
- **JS bundle**: 398.40 → 401.54KB (+3.14KB；NotificationsPage + bell badge)
- **CSS bundle**: 37.93 → 38.08KB (+0.15KB)
- **Build time**: 177ms（無 regression）
- **Files touched**: 7 (1 backend + 5 frontend + 2 test patches) + 1 spec doc
- **AC coverage**:
  - AC-1~8 impl ✓ (manual smoke pending live restart)
  - AC-9 28/28 ✓ (signature-change-affected tests patched)
  - AC-10 401.54 < 410 ✓
  - AC-11 compileJava ✓

ship as **v2.82.0** (M90h1 / S096 META sub-spec 7a/8)。

## §8 Notes for downstream sub-specs

- **S096h2 (defer)**: real notifications projection from domain_events + per-user subscription model + 4 mutation endpoints + WebSocket evaluation + Version Diff page + `@ApplicationModule(notification)` registration
- **S096 META progress**: 7a/8；only S096d4 + 4 deferred sub-spec doubles (e2 ⏸ / f2 / g2 / h2) left
- **Live :8080 caveat**: backend 仍跑 ship 前舊 code；bell badge polls fail until graceful restart, gracefully shows count=0 fallback
