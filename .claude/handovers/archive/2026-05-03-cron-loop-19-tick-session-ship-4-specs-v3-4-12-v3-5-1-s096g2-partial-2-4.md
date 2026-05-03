---
topic: "Cron loop 19-tick session — ship 4 specs (v3.4.12→v3.5.1) + S096g2 partial 2/4"
session_type: "development"
status: "in_progress"
date: "2026-05-03"
---

# Handover: Cron loop 19-tick session — Spec-Only-Handoff pattern x5

## Layer 1 — Portable Summary

> 4.5h cron loop session（30m cadence，CronCreate `3b55d7d3` user 收尾 CronDelete）
> ship 4 個 specs（v3.4.12 / v3.4.13 / v3.5.0 / v3.5.1）+ S096g2 部分推進 2/4。
> 中間 user 持續 mid-session seed 新 spec docs（S099a/S112/S098e2/S098e3/S096f2/
> S096g2/S114a/S096h2）— Spec-Only-Handoff pattern 第 5 次端到端 demo。

### Completed

- **✅ S099a OpenAPI 3.1 verification** ship Tick 1 (single task, v3.4.12 patch)：
  SpringBootTest 鎖 `GET /v3/api-docs` 返 openapi=3.1.0；OverviewPage 加「API 標準對齊」H2 + Swagger UI link
- **✅ S112 Flag wiring full-stack** ship Tick 2-6 (4 tasks, v3.4.13 patch)：
  - T01 backend `/me/flags-summary` endpoint + `FlagService.countOpenFlagsForAuthor` + 4 tests
  - T02 frontend infra `api/flags.ts` + `lib/flag-labels.ts`
  - T03 SkillDetail Flags tab — extract `FlagsList` 至獨立 component (避 Radix Tabs JSDOM fireEvent 不可靠)
  - T04 MySkills MetricCards rework — 移除「平均評分」假 metric + 接 `useFlagsSummary`，4-card → 3-card grid
- **✅ S098e2 Reviews aggregate + ratings full-stack** ship Tick 7-11 (4 tasks, v3.5.0 minor — 首個 minor since v3.x)：
  - T01 review/ 模組 + Review aggregate + ReviewService + endpoints + V8 migration（reviews 表）
  - T02 SkillRatingProjectionListener + SkillRatingService + skill 加 averageRating/reviewCount + V9 migration
  - T03 frontend infra: `api/reviews.ts` + `useReviews` + `RatingStars` (readonly+interactive ARIA radiogroup)
  - T04 ReviewsPanel extract + RatingHero + ReviewRow + ReviewForm + SkillDetailPage Reviews tab
- **✅ S098e3 Flag write flow + reviewer queue** ship Tick 12-16 (4 tasks, v3.5.1 patch — 零 schema migration)：
  - T01 backend write flow: FlagStatus enum + canTransitionTo + FlagService updateStatus/listAllFlags +
    createFlag 加 reporter param + FlagController PATCH/?status= + FlagAdminQueryController + 2 exceptions
  - T02 frontend infra: 3 個新 helper functions (createFlag/fetchFlagsByStatus/updateFlagStatus) + useFlagsQueue + DISMISSED label
  - T03 FlagsList 加「回報問題」CTA + FlagSubmitModal (6-type radio + description optional)
  - T04 FlagsQueuePage + AppShell nav 加「待審回報」+ /flags route
- **🚧 S096g2 Request Board full feature** 進行中 2/4 (Tick 17-19)：
  - Tick 17: spec planning — 4 task files
  - Tick 18: T01 backend — community module 正式註冊 ApplicationModule + Request aggregate (ADR-002 充血) +
    5 events + RequestService + Command/Query controllers + V10 schema (requests + request_votes 兩表) +
    2 exceptions + 13 ACs Testcontainers tests PASS
  - Tick 19: T02 vote toggle — RequestVoteService atomic SQL (INSERT ON CONFLICT + UPDATE GREATEST guard) +
    RequestCommandController POST /vote endpoint + 5 tests PASS

**Test summary**: 全 session 累計 **backend ~30 個新 tests + frontend ~40 個新 tests** 全 PASS；
ModularityTests 整 session 從未壞（4 次 cross-module SPI 加入：security + review + community 各自正式註冊
allowedDependencies）。

### Decisions

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| 跨模組 cross-module SPI 走 NamedInterface (`skill::query`) | 對齊 ScanOrchestrator security→skill::query 既有 pattern；ModularityTests 守 boundary 不變形 | 反向 listener 訂閱 events（會引入週期） |
| Aggregate state-based delete event 走 ApplicationEventPublisher 而非 save() outbox | state-based aggregate 無 @Version → load 後 save() 誤觸 INSERT 衝主鍵 | 加 @Version 給每個 aggregate（過度設計，僅 vote/delete path 需要） |
| Modulith bootstrap mode `@SpringBootTest` 而非 `@ApplicationModuleTest(DIRECT_DEPENDENCIES)` | DIRECT_DEPENDENCIES 拉到 transitive bean (e.g. SkillAclController → StorageService) missing | 維持 DIRECT_DEPENDENCIES + 補 missing bean stub（不值得，full bootstrap reliable） |
| Component extract 至獨立檔（FlagsList / ReviewsPanel / FlagSubmitModal）而非 inline | Radix Tabs JSDOM `fireEvent.click` 不可靠（無 user-event dep）；isolation test 比 page-level Tabs interaction 穩定 | 加 `@testing-library/user-event` dep（增 dep + page-level test 仍 fragile） |
| Spring Data JDBC 用 `@Query` annotation 而非 derived query method names | AOT codegen 對多屬性 compound sort（`findAllByOrderByVoteCountDescCreatedAtDesc`）產生壞 code（缺逗號） | derived query method names（已驗證 broken in Spring Boot 4.0.6） |
| 每 spec 拆 4 sequential tasks 走 Spec-Only-Handoff | 一次 tick 可 ship 1 task（XS-S 級）；S/M 整 spec 一次 ship 不可能 | 整 spec 單 commit（wall budget 不夠） |
| Caching `/stats` endpoint 設計討論結案不做 | User Tick 2 提出問題後 Tick 17 表態「Caching 先不做」 | 立即實作 `@Cacheable` / `@Scheduled`（user 主動踢回） |

### Next Steps

1. **S096g2-T03 frontend infra** — 下一個 unit of work：
   - `frontend/src/api/skills.ts` 加 7 個 helper：createRequest / fetchRequest / toggleVote / claimRequest / releaseClaim / fulfillRequest / deleteRequest
   - extend `SkillRequest` type 加 `claimerId / fulfilledSkillId / voteCount / requesterId` 欄位
   - 新建 `frontend/src/hooks/useRequests.ts` (list with sort + status filter) + `useRequest.ts` (single detail)
   - XS scope，single-tick ship；無 backend 依賴（types 純 frontend）
2. **S096g2-T04 frontend page + components**：
   - `RequestBoardPage.tsx` (modify 既有 stub) — CTA enabled + 串 useRequests + sort chips + status filter
   - 新建 `CreateRequestModal` (mirror FlagSubmitModal pattern)
   - 新建 `VoteButton` (樂觀更新 toggle)
   - 新建 `RequestActionBar` (state-aware claim/release/fulfill/delete buttons)
   - `RequestBoardPage.test.tsx` (AC-15/16/17)
   - 完成後 S096g2 整 spec ship → CHANGELOG + roadmap → ✅ + spec archive + 刪 4 tasks
3. **後續 active spec backlog**（user 累積 seed）：
   - **S096f2** Collections full feature M(13) — 需先拆 task
   - **S114a** RBAC ACL with materialized projection — 需先拆 task (commit `d02e896`)
   - **S096h2** Notifications full projection — 需先拆 task (commit `8ceec39`)
   - 4 個 META spec docs 仍在 specs/（S096/S098/S099/S101）— design state，待 sub-specs 補完才會自然推進
4. **Push 累積 commits**：`git log origin/main..HEAD --oneline` 看 ~15 commits；review 後 `git push`
5. **重啟 backend 套用 V10 migration**：`cd backend && ./gradlew bootRun -x processAot`（local dev 需 fresh DB；migration 已寫但未 apply 到 dev 環境）

### Lessons Learned

- **Spring Data JDBC AOT codegen 對 derived query 多屬性 compound sort 有 bug**：
  `findAllByOrderByVoteCountDescCreatedAtDesc` 產生 `Sort.by(Sort.Order.desc("voteCount")Sort.Order.desc("createdAt"), )` 缺逗號；
  workaround：用 `@Query("SELECT ... ORDER BY ... DESC, ... DESC")` annotation 寫 explicit SQL。Spring Boot 4.0.6 已驗。
- **Radix Tabs `fireEvent.click` 在 JSDOM 不觸發 panel 渲染**（無 `@testing-library/user-event` dep 時）：
  S112-T03 + S098e2-T04 + S098e3-T03 三次驗證 — 直接 unit test extracted standalone component 比 page-level Tabs interaction 穩定。
  **Pattern**：tab content 應 extract 為 single-responsibility component，獨立 unit test。
- **State-based aggregate (Spring Data JDBC) 無 @Version 不能 save() loaded entity**：
  `repo.save(loadedEntity)` 會誤觸 INSERT 衝主鍵（因 isNew() 預設 true）。Workaround：
  (a) delete flow → `repo.deleteById()` + `ApplicationEventPublisher.publishEvent()` 直接發 event（不走 outbox）；
  (b) update flow → 加 @Version field + 對應 schema column；(c) projection update → @Modifying @Query raw SQL
  + aggregate 對應 field 標 @ReadOnlyProperty 防 save 覆蓋。
- **Modulith ApplicationModuleTest(DIRECT_DEPENDENCIES) 限制**：拉直接依賴模組 transitively 帶到 sibling controller bean，
  其依賴若不在 DIRECT_DEPENDENCIES 內會 missing（e.g., SkillAclController → StorageService missing）。Workaround：
  改 `@SpringBootTest` full bootstrap；犧牲 isolation 換 reliability。
- **Cross-module SPI 透過 `@NamedInterface` 暴露**（per skill::query existing pattern）：
  - review module 加 `skill::query` 訂閱 SkillRatingService
  - community module 加 `skill::domain` 訂閱 SkillRepository.findById（fulfill 驗 PUBLISHED）
  Pattern: 把 cross-module callable service 放 sub-package + 加 `@org.springframework.modulith.NamedInterface("name")` 在 package-info.java；
  consumer module allowedDependencies 加 `target :: name`。ModularityTests 守 boundary 不變形。
- **Vote count atomic SQL 走 INSERT ON CONFLICT + UPDATE +1/-1 with GREATEST(0)**：
  PostgreSQL ON CONFLICT DO NOTHING 是 idempotent；DB CHECK >= 0 + GREATEST application guard 雙保險防 race 出負數。
  對齊 Skill download_count S076 既有 pattern。
- **Spec-Only-Handoff pattern 5 次連續 demo 成功**：S099a (single) + S112 + S098e2 + S098e3 + S096g2 = user 寫 spec → cron 拆 4 tasks → cron 連續 ship。
  平均每 spec 5 ticks（1 planning + 4 implement），每 tick ~22min wall。Pattern 證明可 scale 到任意 size。
- **零 schema migration enum 擴充 pattern**（S098e3 demo）：既有 String column + 應用層加 enum + state machine + UI；
  上線 zero-downtime + 既有 row 100% 相容。
- **INVARIANT — 每 spec ship 必含 ModularityTests run**：
  整 session 4 次跨模組 SPI（review/community 加入 + boundary 變動），ModularityTests 從未壞 — 證明邊界守則 effective。

### Session Summary

User 起始 `/loop 30m` 帶長 cron 指令啟動 session-only cron `3b55d7d3`。19 個 ticks（12:00 ~ 16:40）期間：
(1) 走 decision tree step 1，依序 ship 4 specs (S099a/S112/S098e2/S098e3) + S096g2 推進 2/4；
(2) Spec-Only-Handoff pattern 連續 5 次 demo（user mid-session 持續 commit 新 spec docs：S114a / S096h2 等，cron 自然偵測 active 接手）；
(3) 兩次 user mid-tick interrupt（首頁 metrics 顯「—」+ caching 設計討論）皆乾淨 queue，session-end 時 caching 議題 user 表態「先不做」結案；
(4) Branch 累積 ~15 commits 領先 origin/main 待 push；S096g2 T03/T04 為下個 unit of work，估 ~2 ticks 完成。
User 於 Tick 19 後 CronDelete 收尾 session-only cron。

---

## Layer 2 — Environment Details

| Property | Value |
|----------|-------|
| Branch | `main` |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Test Status | All targeted tests PASS — RequestServiceTest 13/13 + RequestVoteServiceTest 5/5 + ModularityTests 2/2 (last run Tick 19 @ 16:39); frontend 累計 cross-spec 20+ tests 全 PASS @ 1.85s |
| Cron jobs | (none — `3b55d7d3` user CronDelete @ session end) |
| Branch ahead of origin | ~15 commits 待 push |

### Uncommitted Changes

```
(working tree clean)
```

### Recent Commits

```
8ceec39 docs(spec): seed S096h2 — Notifications full projection
8eee45e feat(api): S096g2-T02 vote toggle service + endpoint + race tests
9cd99e6 feat(api): S096g2-T01 Request aggregate + endpoints + V10 schema
d02e896 docs(spec): seed S114a — RBAC ACL with materialized projection
a7e1c5f chore(spec): plan S096g2 tasks — break into 4 BDD task files
e5bcace feat: S098e3 ship Flag write flow + reviewer queue (v3.5.1)
03db7fa feat(frontend): S098e3-T03 FlagsList 加「回報問題」CTA + FlagSubmitModal
a717002 feat(frontend): S098e3-T02 flag write/queue api + useFlagsQueue hook
ac5a0e3 feat(api): S098e3-T01 flag write flow + status mutations + reviewer queue endpoint
637e09d docs(spec): seed S096g2 — Request Board full feature
```

### Active Spec Docs（in `docs/grimo/specs/`）

```
2026-05-02-S096-ui-v2-dark-theme-meta.md       (META，design state，sub-specs ship 完才推進)
2026-05-02-S098-prototype-completeness-audit.md (META)
2026-05-02-S099-trust-maturity-meta.md          (META，awaiting confirm)
2026-05-02-S101-quality-impact-security-scores.md (META，awaiting human confirm)
2026-05-03-S096f2-collections-full.md           (M=13，需 task planning)
2026-05-03-S096g2-request-board-full.md         (S=11，🚧 2/4，下個 tick T03)
2026-05-03-S096h2-notifications-projection.md   (剛 seed by user，需 task planning)
2026-05-03-S114a-rbac-acl-projection.md         (剛 seed by user，需 task planning)
```

### Active Tasks（in `docs/grimo/tasks/`）

```
2026-05-03-S096g2-T01-backend-aggregate-and-endpoints.md  ✅ shipped Tick 18
2026-05-03-S096g2-T02-vote-toggle-service.md              ✅ shipped Tick 19
2026-05-03-S096g2-T03-frontend-infra.md                   pending — next unit of work
2026-05-03-S096g2-T04-frontend-page-components.md         pending
```

### Key Files

**S096g2-T03 next session 開工會 touch 的**：
- `frontend/src/api/skills.ts` (modify) — 既有有 `SkillRequest` type；加 7 helpers + extend type fields per task spec §Implementation outline
- `frontend/src/hooks/useRequests.ts` (new) — 對齊 useFlagsQueue 既有 pattern
- `frontend/src/hooks/useRequest.ts` (new) — 對齊 useSkill 既有 pattern

**S096g2-T04 next session 開工會 touch 的**：
- `frontend/src/pages/RequestBoardPage.tsx` (既有 stub) — CTA enabled + 串 hooks
- `frontend/src/components/CreateRequestModal.tsx` (new) — mirror FlagSubmitModal pattern
- `frontend/src/components/VoteButton.tsx` (new)
- `frontend/src/components/RequestActionBar.tsx` (new) — state-aware buttons

**Reference patterns（S096g2-T01/T02 已 ship，下個 task 可參照）**：
- `backend/src/main/java/io/github/samzhu/skillshub/community/Request.java` — Request aggregate
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestVoteService.java` — atomic SQL toggle
- `backend/src/main/java/io/github/samzhu/skillshub/community/package-info.java` — Modulith ApplicationModule 註冊
- `frontend/src/components/FlagSubmitModal.tsx` — modal pattern reference (S098e3-T03)
- `frontend/src/components/FlagsList.tsx` — list + CTA pattern reference (S112/S098e3)

**Active spec backlog**：
- `docs/grimo/specs/2026-05-03-S096f2-collections-full.md` (M=13)
- `docs/grimo/specs/2026-05-03-S096h2-notifications-projection.md` (剛 seed)
- `docs/grimo/specs/2026-05-03-S114a-rbac-acl-projection.md` (剛 seed)
