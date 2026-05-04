---
topic: "Cron loop 5-tick session — ship S096g2 v3.6.0 + S096h2 進度 2/4"
session_type: "development"
status: "in_progress"
date: "2026-05-03"
---

# Handover: Cron loop 5-tick session — Spec-Only-Handoff pattern x6 + S096h2 listener wiring

## Layer 1 — Portable Summary

> 接續上一個 19-tick session（已 archived 至 `2026-05-03-cron-loop-19-tick-...md`）；
> 本 session 走 dynamic `/loop` 自我節奏 5 ticks。User Tick 5 後 `CronDelete` 收尾。
> Ship 1 整 spec (S096g2 v3.6.0) + S096h2 進度 2/4。

### Completed

- **✅ S096g2-T03 frontend infra ship** (Tick 1, commit `aafd36a`)：
  - `frontend/src/api/skills.ts` 重寫 SkillRequest 對齊 backend RequestResponse；
    fetchRequests 加 sort+status query；新增 fetchRequest / createRequest /
    toggleVote / claimRequest / releaseClaim / fulfillRequest / deleteRequest
    7 helper + VoteResult / ClaimResult / FulfillResult / CreateRequestBody /
    RequestsQuery 5 type
  - `frontend/src/hooks/useRequests.ts` (new — 30s staleTime + refetchOnWindowFocus)
  - `frontend/src/hooks/useRequest.ts` (new — 對齊 useSkill pattern)
  - `frontend/src/pages/RequestBoardPage.tsx` minimal `req.votes → req.voteCount`
- **✅ S096g2 整 spec ship v3.6.0** (Tick 2, commit `868acbb`)：
  - 4 個 frontend 元件新建 (CreateRequestModal / VoteButton 樂觀更新 /
    RequestActionBar state-aware / RequestBoardPage 全面重建)
  - 3 個 ACs Frontend test PASS @ 1.54s（AC-15/16/17 + S103 spec ID leak invariant carry-forward）
  - CHANGELOG v3.6.0 entry + roadmap → ✅ + spec archived + 4 task files deleted
  - Spec-Only-Handoff pattern 第 6 次端到端 demo
- **✅ S096g2 §6/§7 backfill** (Tick 3, commit `111bb2d`)：tick 21 ship commit
  漏 stage 同 spec doc 修正（git rename detector 100% 偵測導致 §6/§7 留 working
  tree）— 純資料完整性補充
- **✅ S096h2 task planning** (Tick 3, commit `246d326`)：拆 4 BDD task files
  per spec §1-§5 design；spec frontmatter 改 🚧 in-progress；roadmap row 🚧 task-planned
- **✅ S096h2-T01 backend infrastructure** (Tick 4, commit `420af6f`)：
  - V11 schema 2 表 (notifications + notification_preferences) + UNIQUE constraint
    + 2 partial/full index + version columns
  - 2 個 mutable aggregate 走 `@Version`（Notification + NotificationPreference）
  - 2 個 repos (`@Query` annotation 預防性 workaround Spring Boot 4.0.6 AOT bug)
  - package-info `@ApplicationModule` minimum deps（T02 擴）
  - NotificationModuleSmokeTest 8/8 PASS @ 15.5s + ModularityTests 2/2 PASS
- **✅ S096h2-T02 NotificationProjectionListener** (Tick 5, commit `b872d5f`)：
  - 4 個 `@ApplicationModuleListener` (SkillFlagged / ReviewCreated /
    RequestClaimed / RequestFulfilled) + DuplicateKey catch idempotency +
    preferences gate + self-action skip
  - 2 個新 NamedInterface package-info（community::events / review::domain）— 跨
    模組 sub-package events 暴露第 1 次跨模組 SPI（前次 audit 走 skill::domain）
  - V11 ref_event_id `VARCHAR(36) → VARCHAR(255)` in-place fix（composite ID 撐爆）
  - NotificationProjectionListenerTest 6/6 PASS @ 15.5s（AC-1/2/3/4/5/10 全綠）

**Test summary**：本 session 累計 **backend 14 個新 tests + frontend 3 個新 tests**
全 PASS；ModularityTests 從 1 次 violation（S096h2-T02 加 cross-module dep 時）
快速 fix（加 NamedInterface）後立即綠。每個 commit 後 ModularityTests 都跑過。

### Decisions

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| Notification + NotificationPreference 走 `@Version` 而非 Persistable + 自訂 isNew | factory 設 createdAt=Instant.now() 會讓 Persistable.isNew(createdAt==null) always false → INSERT 失敗；@Version Long nullable 是 Spring Data JDBC 慣例（INSERT version=null → isNew=true，UPDATE 已 loaded → isNew=false） | spec §4.3 範本 Persistable 寫法 |
| ref_event_id 用 composite (skillId+":"+type) 而非加 flagId 至 SkillFlaggedEvent record | 不改 security 模組 public signature 避免 cascade；副作用：同 skill 同 type 多筆 flag dedupe 為 1 通知 = spam 防護（owner 知「skill X 有 spam 類回報」一次即可，逐筆 review 進 FlagsList） | 改 SkillFlaggedEvent record 加 flagId 欄位 |
| `community.events` + `review.domain` 加 `@NamedInterface` 暴露 events | Modulith 強制要求 sub-package 須 NamedInterface 才能跨模組 import；對齊 skill::domain 既有 pattern | 把 events 移至各模組 top-level package（過度侵入既有模組結構） |
| V11 ref_event_id `VARCHAR(36) → VARCHAR(255)` in-place fix | branch 仍 ahead of origin ~24 commits，無 production DB 已 apply 此 migration → in-place 比 add V12 ALTER 乾淨；single-developer pre-prod codebase | 加 V12 migration with ALTER COLUMN |
| Notification mutable update 走 service @Modifying SQL（T03 規劃）+ aggregate save 只走 listener INSERT | Mutable aggregate 加 @Version 雖可 INSERT/UPDATE 都通，但 markAllRead batch 路徑用 `@Modifying @Query UPDATE WHERE recipient AND read_at IS NULL` 比 N 個 load+save round-trip 高效 | 純走 aggregate save mutate path（performance penalty + N 次 round-trip） |
| T01 minimum module deps，T02 加 listener 時擴 | Modulith over-declare 無害但保守起見 only-what-imported；T01 aggregates 不 import 跨模組 type，T02 listener 才需要 | 一次性宣告全部 deps（無實 import 時 dep 列項 misleading） |
| Frontend RequestBoardPage 走 minimal callsite update（T03 內 1-line `req.votes → req.voteCount`） | Public signature change 必須同步 callers；T04 會做完整 page 重建，T03 只需保 build green | 留半成品（next tick 撈起來會炸）/ T03 一併做 page 重建（drive-by refactor 違規） |
| Caching `/stats` endpoint 設計討論結案不做（傳承自 19-tick session） | User 該 session 表態「先不做」結案 | 立即實作 @Cacheable / @Scheduled |

### Next Steps

1. **S096h2-T03 backend mutation + query services + controller 取代 stub**
  （下個 unit of work，剩 ~22 commits 中 T03/T04 完成 S096h2 整 spec）：
   - new `NotificationService` (markRead / markAllRead / delete / updatePreferences;
     CurrentUserProvider 抽 sub + ownership 守則拋 NotNotificationRecipientException)
   - new `NotificationQueryService` (list with cursor + category filter; unreadCount)
   - modify `NotificationController` (取代 stub method bodies) — 6+1 endpoints per
     spec §4.1 (GET list / GET unread-count / POST {id}/read / POST read-all /
     DELETE {id} / GET preferences / POST preferences)
   - new 2 exceptions (NotificationNotFoundException / NotNotificationRecipientException)
   - GlobalExceptionHandler 加 404/403 mapping
   - NotificationServiceTest + NotificationControllerTest（@WebMvcTest slice 或
     @SpringBootTest if DIRECT_DEPENDENCIES 拉 missing — 對齊既有 deviation）
   - AC-6/7/8/9 + cursor pagination AC
2. **S096h2-T04 frontend page + preferences modal**：
   - new `frontend/src/api/notifications.ts` (split from skills.ts) — 7 helpers
   - modify `frontend/src/api/skills.ts` 移除舊 Notification + 2 fetchers
   - new `useNotifications` + `useNotificationPreferences` hooks
   - new `PreferencesModal` (4 toggle 表單)
   - modify `NotificationsPage.tsx` (filter chips + list + mark-read + delete + 設定 CTA)
   - new `NotificationsPage.test.tsx` (AC-12/14)
   - 完成後 S096h2 整 spec ship v3.7.0 → CHANGELOG + roadmap + archive + 刪 4 tasks
3. **後續 active spec backlog**（user mid-session seed）：
   - **S096f2** Collections full feature M(13) — 需 task planning
   - **S114a** RBAC ACL with materialized projection M(12) — 需 task planning
     （per CLAUDE.md "Feature First Security Later" 可能 defer）
   - 4 個 META spec docs 仍在 specs/（S096/S098/S099/S101）— design state
4. **Push 累積 commits**：`git log origin/main..HEAD --oneline` 看 ~24 commits；
   review 後 `git push`
5. **重啟 backend 套用 V10/V11 migration**：`cd backend && ./gradlew bootRun -x processAot`
   （local dev 需 fresh DB；migrations 已寫但未 apply 到 dev runtime）
6. **frontend `npmBuild` task 有 pre-existing TS errors**（`global` 等 28 處）—
   執行 backend `./gradlew test` 須 `-x npmBuild` 跳過；非本 session 引入，
   不擋 ship 但下一 session 可考慮 fix（屬 polish）

### Lessons Learned

- **Modulith sub-package events 須走 `@NamedInterface` 暴露**：跨模組 import event
  records（如 `community.events.RequestClaimedEvent`）若 sub-package 無 NamedInterface
  declaration，Modulith 拒（即使 consumer 宣告 `community` whole-module dep）。Fix
  是新建 `package-info.java with @NamedInterface("events")`；對齊 `skill::domain`
  既驗 pattern。本 session 加 `community::events` + `review::domain` 兩個。
- **`@Version` 是 Spring Data JDBC mutable aggregate INSERT/UPDATE 區分標準路徑**：
  Long nullable + DB DEFAULT 0；無需 Persistable + 自訂 isNew flag。Notification +
  NotificationPreference 為第 2/3 次採用（首次 Request S096g2-T01）。
- **Spring Boot 4.0.6 AOT codegen 對 derived query 多屬性 compound sort 有 bug**：
  `findAllByOrderByVoteCountDescCreatedAtDesc` 產生壞代碼缺逗號。Workaround：
  用 `@Query` annotation explicit SQL。第 3 次 ship 套用此 pattern（S096g2-T01
  RequestRepository 首次發現 / 本 session NotificationRepository 預防性套用）。
- **ref_event_id schema 應 VARCHAR(255) 不該 VARCHAR(36)**：UUID 36 chars，加
  composite suffix（`:type` 或 `:userId:claim`）會破 36。即使原 spec 設計只指 UUID
  也應預留 composite 空間 — DB 變更比 application code 變更貴。Lesson：schema
  設計時 string columns ≥ 255 是穩妥默認（除非有強壓縮 motivation）。
- **SkillFlaggedEvent 缺 flagId 欄位是 security 模組 design 既有限制**：FlagService
  設計時未把 flagId 帶入 event payload（aggregateId 為 skillId）。改 record public
  signature 會 cascade caller — 改用 deterministic composite ref_event_id
  workaround（spam dedupe 副作用恰好 = good UX）。Lesson：domain event 設計
  時應把所有 listener 可能 idempotency 鍵候選欄位都帶上。
- **Modulith Scenario API `scenario.publish(event).andWaitForStateChange(...).andVerify(...)`**：
  AFTER_COMMIT async listener 標準 test pattern；對齊 SkillRatingProjectionListenerTest
  既驗。`@SpringBootTest` 比 `@ApplicationModuleTest(DIRECT_DEPENDENCIES)` reliable
  （後者拉 transitive bean missing — 已是第 3 次驗）。
- **Backend `./gradlew test` 觸發 npmBuild → frontend tsc 全跑**：pre-existing
  TS errors（28 處 `global`/missing field 等）擋 backend test。Workaround：
  `-x npmBuild` 跳過。Lesson：dev workflow 加 `-x npmBuild` alias 或在 build.gradle.kts
  把 backend test 與 frontend build 解耦（polish 級別）。
- **Spec-Only-Handoff pattern 第 6 次連續 demo 成功**：S096g2 整 spec ship 為
  本 pattern 第 6 次。session 端到端時間平均 5 ticks（1 planning + 4 implement）；
  本 session 處在 S096h2 中段（task planning 完 + 2/4 implement），同 cadence。

### Session Summary

接續上 19-tick session 的 takeover handover；本 session dynamic `/loop` 自我
節奏 5 ticks（25min cadence）。Tick 1-2 收尾 S096g2 整 spec（T03 frontend infra
+ T04 page rebuild + 4 元件 + 3 ACs test）併入 v3.6.0 ship；Tick 3 同時拆完
S096h2 4 task files + backfill S096g2 spec doc 漏帶 §6/§7。Tick 4-5 接 S096h2
backend infrastructure（aggregates + V11 + module wiring + listener cross-module
SPI 加 2 個 NamedInterface）。Listener 在 Tick 5 撞 V11 schema VARCHAR(36) bug
（composite ref_event_id 達 46 chars），fix 後 6 ACs 全綠。User 在 Tick 5 結束後
`CronDelete` 收尾 session（dynamic mode 無 cron 可刪 — 其實是 ScheduleWakeup queue）。
S096h2 進度 2/4，下次 takeover 從 T03 service + controller 接手。

---

## Layer 2 — Environment Details

| Property | Value |
|----------|-------|
| Branch | `main` |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Test Status | All targeted tests PASS — NotificationProjectionListenerTest 6/6 + NotificationModuleSmokeTest 8/8 + ModularityTests 2/2 (Tick 5 final run @ 19:22); RequestBoardPage.test.tsx 3/3 + tsc PASS (Tick 2 final run @ 17:32) |
| Cron / wakeup | (none — session-end，scheduled wakeup 19:51 已過 fire window；user CronDelete 收尾) |
| Branch ahead of origin | ~24 commits 待 push |

### Uncommitted Changes

```
(working tree clean)
```

### Recent Commits

```
b872d5f feat(api): S096h2-T02 NotificationProjectionListener + cross-module SPI
420af6f feat(api): S096h2-T01 notification aggregates + V11 schema + module wiring
246d326 chore(spec): plan S096h2 tasks — break into 4 BDD task files
111bb2d chore(spec): backfill S096g2 §6/§7 task plan + result
868acbb feat: S096g2 ship Request Board full feature (v3.6.0)
77391cb docs(roadmap): S096g2 進度 2/4 → 3/4（T03 frontend infra shipped）
aafd36a feat(frontend): S096g2-T03 request mutations api + useRequests/useRequest hooks
1f28afd chore(loop): 簡化 loop.md MCP 指引 + 歸檔 tick 19 handover
039af01 docs(roadmap): defer S098a2 + S096d6 (SSE polish) per user 2026-05-03
8ceec39 docs(spec): seed S096h2 — Notifications full projection
```

### Active Spec Docs（in `docs/grimo/specs/`）

```
2026-05-02-S096-ui-v2-dark-theme-meta.md         (META，design state)
2026-05-02-S098-prototype-completeness-audit.md   (META)
2026-05-02-S099-trust-maturity-meta.md            (META，awaiting confirm)
2026-05-02-S101-quality-impact-security-scores.md (META，awaiting human confirm)
2026-05-03-S096f2-collections-full.md             (M=13，需 task planning)
2026-05-03-S096h2-notifications-projection.md     (🚧 2/4，下個 tick T03)
2026-05-03-S114a-rbac-acl-projection.md           (M=12，需 task planning，
                                                    可能 defer per Feature First)
```

### Active Tasks（in `docs/grimo/tasks/`）

```
2026-05-03-S096h2-T01-backend-aggregates-and-schema.md     ✅ shipped Tick 4
2026-05-03-S096h2-T02-projection-listener.md               ✅ shipped Tick 5
2026-05-03-S096h2-T03-mutation-query-controller.md         pending — next unit of work
2026-05-03-S096h2-T04-frontend-page-and-preferences.md     pending
```

### Key Files

**S096h2-T03 next session 開工會 touch 的**（per task spec §Implementation outline）：
- `backend/.../notification/NotificationService.java` (new — markRead / markAllRead / delete / updatePreferences；ownership 守則)
- `backend/.../notification/NotificationQueryService.java` (new — list with cursor + category filter; unreadCount)
- `backend/.../notification/NotificationController.java` (modify — 取代 stub method bodies；6+1 endpoints)
- `backend/.../shared/api/NotificationNotFoundException.java` (new)
- `backend/.../shared/api/NotNotificationRecipientException.java` (new)
- `backend/.../shared/api/GlobalExceptionHandler.java` (modify — 加 404/403 mapping)
- `backend/src/test/.../notification/NotificationServiceTest.java` (new — AC-6/7/8/9)
- `backend/src/test/.../notification/NotificationControllerTest.java` (new — 6 endpoints smoke)

**S096h2-T04 next session 開工會 touch 的**：
- `frontend/src/api/notifications.ts` (new — split from skills.ts；7 helpers + 2 type)
- `frontend/src/api/skills.ts` (modify — 移除 Notification interface + 2 fetchers)
- `frontend/src/hooks/useNotifications.ts` (new)
- `frontend/src/hooks/useNotificationPreferences.ts` (new)
- `frontend/src/components/PreferencesModal.tsx` (new — 4 toggle)
- `frontend/src/pages/NotificationsPage.tsx` (modify — list + filter chips + mutations)
- `frontend/src/pages/NotificationsPage.test.tsx` (new — AC-12/14)

**Reference patterns（本 session 已 ship，T03/T04 可參照）**：
- `backend/src/main/java/io/github/samzhu/skillshub/notification/Notification.java` — `@Version` mutable aggregate
- `backend/src/main/java/io/github/samzhu/skillshub/notification/NotificationProjectionListener.java` — 4 個 cross-module listener
- `backend/src/main/java/io/github/samzhu/skillshub/notification/NotificationRepository.java` — `@Query` annotation cursor pagination
- `backend/src/main/java/io/github/samzhu/skillshub/notification/package-info.java` — @ApplicationModule with NamedInterface deps
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestService.java` — service orchestration pattern
- `backend/src/main/java/io/github/samzhu/skillshub/security/FlagService.java` — mutation + ownership 守則 pattern
- `frontend/src/components/CreateRequestModal.tsx` — modal pattern reference
- `frontend/src/components/RequestActionBar.tsx` — state-aware buttons reference

**Active spec backlog** — task planning 候選：
- `docs/grimo/specs/2026-05-03-S096f2-collections-full.md` (M=13)
- `docs/grimo/specs/2026-05-03-S114a-rbac-acl-projection.md` (M=12，可能 defer)
