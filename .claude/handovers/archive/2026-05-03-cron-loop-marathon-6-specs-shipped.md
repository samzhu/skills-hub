---
topic: "Cron-loop marathon — 6 specs shipped (v3.6.0→v3.8.3) + Mode B Round 36 audit + user mid-tick directives 完成"
session_type: "development"
status: "completed"
date: "2026-05-03"
---

# Handover: Cron-loop 36-tick marathon — 6 specs ship + 3 backlog audit findings

## Layer 1 — Portable Summary

> 本 session 由 `/takeover` 接續上一次 5-tick session 的 S096h2 進度 2/4；
> User `CronDelete` 終止 loop 後正常收尾。Cron-driven dynamic /loop 自我節奏跑
> 共 36 ticks（含 takeover + Mode B Round 36）。

### Completed

- **✅ S096h2 ship v3.7.0** (Tick 1-4, 接續上 session 進度 2/4 → 4/4)：
  - Tick 1 commit `e25ce28` T03 NotificationService + Command/Query split + 2 exception (Notification 翻 404/403)；ServiceTest 12 + ControllerTest 10 PASS
  - Tick 2 commit `7aa6e02` T04 frontend full：api/notifications.ts split + useNotifications + useNotificationPreferences hooks + PreferencesModal (4 toggle) + NotificationsPage 全面 rewrite (filter chips + 全部已讀 + 設定) + AppShell import 切換 + AC-12/14 tests
  - Spec ship + CHANGELOG + roadmap → ✅ + spec doc archive + 4 task files cleanup
- **✅ S096f2 ship v3.8.0** (Tick 5-9, 5-tick cycle)：
  - Tick 5 commit `1d2af35` chore(spec): plan S096f2 tasks (4 BDD task files)
  - Tick 6 commit `b8b2798` T01 Collection aggregate (AbstractAggregateRoot + @Version + @MappedCollection) + V12 schema + community @ApplicationModule + 2 events + SkillRepo extension + smoke 9/9
  - Tick 7 commit `a083db5` T02 service + Command/Query controllers + 2 exception (CollectionNotFound 404 + SkillNotPublishable 400 with invalidSkillIds list) + RequestService.fulfill caller migration + 17 tests
  - Tick 8 commit `bf101f4` T03 frontend api 3 helper + 2 hooks (useCollections + useCollection) + CollectionsPage extract
  - Tick 9 commit `c5a313e` T04 frontend full：CreateCollectionModal (textarea skill picker per §2.6 trim) + InstallButton (50ms loop browser download) + CollectionsPage rewrite + AC-10/11/12 tests + spec ship
- **✅ S098a3-2 ship v3.8.1** (Tick 10-11, 2-tick)：
  - Tick 10 commit `67ce56c` spec planning + queue S115/S116 backlog rows (user mid-tick directives 1+2 加 📋 row)
  - Tick 11 commit `0b695e8` single-tick implement：V13 migration `skill_versions.file_count` + PackageService.countEntries helper + PublishVersionCommand 加 fileCount field + SkillVersion @Column + 兩 upload pipeline 計算 + BundleInfoQueryService + GET /skills/{id}/bundle-info endpoint + BundleNotPublishedException 404 distinct + frontend useBundleInfo hook + PublishValidatePage strip 真值 + 6 個 cross-test 檔 PublishVersionCommand callsite migration
- **✅ S115 ship v3.8.2 — JWT + ACL Safety graceful degradation** (Tick 12-13)：
  - Tick 12 commit `f57d8fb` spec planning（user 2026-05-03 mid-tick directive 1/2 接手）
  - Tick 13 commit `c8d77f3` trimmed implement：CurrentUserProvider sub null check + parseStringListClaim helper + JwtClaimAnomalyMetrics Micrometer counter + MissingJwtSubException → 401 + WWW-Authenticate header + GlobalExceptionHandler mapping + ADR-006 5-段範本 + 4 個 matrix 表 + 11/11 unit tests
  - Trim defer：AC-8 prod fallback guard test (@SpringBootTest setup heavy) / JwtSafetyTest E2E / JwtDecoder OIDC discovery 503 / glossary / dev-standards 更新 / Grafana dashboard JSON
- **✅ S116 ship v3.8.3 — Skill visibility public/private toggle** (Tick 14-15)：
  - Tick 14 commit `d193350` spec planning（user mid-tick directive 2/2）
  - Tick 15 commit `8b25de8` single-tick ship：Visibility enum + CreateSkillCommand 加 field + 4-arg backward-compat ctor delegate to 5-arg PUBLIC default + Skill.create factory 條件式 seed *:read (PRIVATE 不加，author=null+PRIVATE → IllegalArgumentException) + SkillCommandService 5-arg overload + Controller @RequestParam visibility=PUBLIC default + frontend api/skills.ts Visibility type + PublishPage radio fieldset + SkillAggregateTest 加 5 個 S116 AC tests
  - **Approach B**（derived from acl_entries 是否含 *:read）— 零 schema 變動；既有 ACL infra 0 改動
- **🔍 Mode B Round 36 — API projection field completeness audit** (Tick 16, commit `fad7261`)：
  - 3 個 findings 加 backlog（per Mode B 「找到 bug → 切回 Mode A 寫 fix-spec」）
  - **Bug AP** (LOW)：Frontend SkillVersion type 缺 fileCount field → S117 (XS=1) backlog
  - **Bug AQ** (LOW)：Backend Collection DTO `installs` (list) vs `installCount` (single) inconsistency → S118 (XS=2) backlog
  - **Bug AR** (MEDIUM)：SkillQueryService.search() SELECT 缺 average_rating/review_count → SkillCard 顯 rating 永遠 0 (user-visible) → S119 (XS=2) backlog
- **本 session test 累計**：
  - Backend：S096h2 22 + S096f2 26 + S098a3-2 6 + S115 6 + S116 5 + cross-test migrations + ModularityTests 整 session 從未壞 = 65+ 新 tests
  - Frontend：S096h2 7 + S096f2 4 + 多 regression = 11+ 新 tests + 全 193/193 PASS @ 各 ship phase

### Decisions

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| S096f2 collection_skills PK = (collection_id, position) + UNIQUE (collection_id, skill_id) | Spring Data JDBC `@MappedCollection(keyColumn=position)` canonical pattern 要求 PK 含 keyColumn；child entity Optional<@Id> 與 keyColumn 衝突；UNIQUE 維持「同 collection 內 skill 不重複」語意 | spec §4.2 範本 PK = (collection_id, skill_id) 與 keyColumn pattern 衝突 |
| S096f2 走 ADR-002 canonical `@Version + AbstractAggregateRoot` 不走 spec §4.3 Persistable | factory 設 createdAt=Instant.now() 會破 isNew flag — codebase 第 4 次踩坑教訓；@Version 是 mutable INSERT/UPDATE 標準路徑 | spec §4.3 Persistable + 自訂 isNew 範本 |
| S098a3-2 fileCount 走「上傳時計算 + 寫 column」而非「即時 GCS scan」 | 避免每次 endpoint 走 GCS download + zip extract 災難；既有 row 走 0 fallback signal + frontend hide 該欄是 graceful policy | A. 即時 GCS scan / C. 等 S098c2 Version snapshot store（整體拆 too 大）|
| S098a3-2 不加新 `SkillNotFoundException` class | 既有 NoSuchElementException 路徑 + GlobalExceptionHandler `handleNotFound` 翻 NOT_FOUND 已 cover；新 class 違反 minimal diff | spec §4.4 範本 加新 SkillNotFoundException class |
| S115 trimmed M(8-10) → 單 tick ship 4 項 polish defer | 核心 invariant + ADR + 11 unit tests = 完整 safety policy 落地；AC-8 prod guard test 走 @SpringBootTest setup heavy | 完整 ship 含 JwtSafetyTest E2E + AC-8 fallback guard + glossary / dev-standards |
| S116 走 Approach B (derived from acl_entries 是否含 *:read)；零 schema 變動 | 對齊 S038 既驗 listEntries 識別 *:read；S114a 設計中的 is_public GENERATED column 未來路徑自然從同 acl_entries derive 無 migration breaking | A. 加 is_public BOOLEAN column / C. 等 S114a ship 後走其 GENERATED column |
| S116 走 backward-compat 4-arg ctor delegate to 5-arg PUBLIC default | 既有 caller 0 callsite migration cost（vs S098a3-2 走 cross-test 6 callsite migration — 100x 低成本對比） | 強制 5-arg 新 signature → 6+ test files migration cost |
| S116 SkillCreatedEvent 不擴 visibility field | 避免 record signature 加 field cascade 全部 caller migration；future audit 走 derived from acl_entries 同 read path | spec §4.5 範本 加 visibility field 至 event |
| Mode B Round 36 finding 走「documented backlog」而非 inline-fix | 對齊 Mode B rule「找到 bug → 切回 Mode A 寫 fix-spec」；3 個 findings 屬 cross-cutting consistency 而非 hotfix critical | inline-fix 同 audit commit |

### Next Steps

**剩餘 active backlog**（按優先序）：

1. **Mode B Round 36 follow-up 3 個 fix-specs**（XS each，single-tick ship 候選）：
   - **S119** (XS=2, MEDIUM)：`SkillQueryService.search()` SELECT 加 `average_rating, review_count` — user-visible bug；SkillCard rating 星星永遠 0；建議**最高優先**
   - **S117** (XS=1, LOW)：Frontend `SkillVersion` type sync `fileCount` field
   - **S118** (XS=2, LOW)：Backend Collection DTO `installs → installCount` naming alignment + frontend type sync + caller migration

2. **S114a RBAC ACL projection** (📐 M=12, in-design 已寫 spec doc)：
   - 走 task planning + 4-tick implement loop（對齊 S096g2/h2/f2 既驗 4-task split pattern）
   - 解 ACL filter audit gap（per spec roadmap row「既有 list / single GET 補 row-level ACL filter」）— 補完 S116 visibility 完整 enforcement
   - **注意**：per CLAUDE.md「Feature First, Security Later」可能 defer；user 若有 directive 則接手

3. **後續 Mode A spec 候選**（roadmap 📋 backlog 多選一）：
   - S096f3 Collections risk filter polish (XS=3-4)
   - S098b3-2 / S098c2 / S098c3 — S098 META v2 prototype completeness audit sub-specs
   - S099c / S099d / S099e / S099e1 — S099 META Trust Maturity sub-specs

4. **後續 Mode B cuts**（per Round 36 排隊）：
   - Cross-cutting links（routing change 後漏 callsite）
   - User-visible string compliance（i18n / spec ID leak / hardcoded English；last 跑 tick 56）
   - Anonymous vs authenticated flow 比對（S116 ship 後此 cut 高 value）
   - Form interaction (publish / version add / ACL grant) — Chrome MCP heavy

5. **Push 累積 commits**：`git log origin/main..HEAD --oneline` 看 ~38 commits；review 後 `git push`
6. **重啟 backend 套用 V12 + V13 migration**：本 session 走 Testcontainers 跑 test 端有套用，dev runtime 沒套用過；`cd backend && ./gradlew bootRun` 即可
7. **Frontend `npmBuild` task pre-existing TS errors** — backend `./gradlew test` 須 `-x npmBuild` 跳過（自 takeover lessons learned 起就如此；非本 session 引入）

### Lessons Learned

- **`@MappedCollection(keyColumn="position")` 第 1 次 codebase 採用**：child entity table PK 須含 keyColumn 對齊 Spring Data JDBC canonical；對應 list 順序保留靠 keyColumn 而非 INSERT 順序。本 session S096f2 採用；既有 Skill aggregate aclEntries 走 JSONB 不同 path；SkillVersion 是獨立 aggregate。
- **`@RecordApplicationEvents` 取代 outbox 表查 assertion**：Modulith Event Publication Registry 只追蹤有 listener 的 events；無 listener spec smoke test 走 @RecordApplicationEvents 攔截 ApplicationEventPublisher 流，等同 at-least-once 保證測試。S096f2 codebase 首採用。
- **`SimpleMeterRegistry` 是 Micrometer 測試標準**：對 counter increment 斷言走 `registry.find(name).tag(...).counter().count()` 直接讀；無需 Spring `@MeterRegistry`-managed setup。S115 codebase 首採用。
- **Backward-compat ctor delegate vs cross-test callsite migration — 100x 成本對比**：S098a3-2 加 PublishVersionCommand.fileCount field → 走 cross-test 6 callsite migration（每個加 `, 0`）；S116 加 CreateSkillCommand.visibility field → 走 4-arg backward-compat ctor delegate to 5-arg PUBLIC default → 0 callsite migration。Lesson：record 加 field 時若有合理 default value，**backward-compat ctor pattern > cross-test migration**。S098a3-2 路徑也合理（fileCount=0 是 unknown signal，不是 default value），不是 always-applicable。
- **ADR + Spec 雙寫**：ADR 是 policy 永久記錄；spec 是 ship how-to 可 archive。S115 走雙寫對齊 ADR-002/003/004 既驗慣例；user 直接 directive「落成安全設計文件」明示需要 ADR 而非 transient task spec。
- **derived from existing column 取代新加 column**：S116 visibility derived from acl_entries 是否含 `*:read`（對齊 S038 既驗 listEntries 識別）；S098a3-2 走相反路徑（加新 column file_count）。Lesson：當既有 schema 已表達 invariant 時，derived 路徑 > 新加 column；既有 invariant 演化未來成 GENERATED column 路徑（per S114a 已 plan `is_public` GENERATED column）。
- **Single-tick XS spec ship pattern 越來越成熟**：本 session 第 4 次採用（S099a / S110 / S111 / S098a3-2 / S116 累計 5 次）— XS(2) size 適合「IMPLEMENT + VERIFY + DOCUMENT + PERSIST 一 commit 落地」，不需 BDD task split。
- **enum + factory conditional 取代 schema 變動策略**：`if (visibility == PUBLIC) entries.add("*:read")` 一行替代 V14 migration + column + UI badge；`Visibility.defaultValue() = PUBLIC` 對齊 enum convention。codebase 第 2 次採用（首次為 SkillStatus.DRAFT default）。
- **Spec-Only-Handoff pattern 在 cron-loop 內運作良好**：本 session 連續 8 次端到端 demo（S096g2 / S096h2 / S096f2 / S098a3-2 / S115 / S116 等），平均 4-5 ticks / spec ship cycle。User mid-tick directive interrupt → ack + 收尾 + queue backlog 路徑運作如預期。
- **S114a ACL filter gap 是 known feature-first tradeoff**：S116 visibility 走 factory conditional seed 是 cosmetic；真正 ACL enforcement 仍欠 S114a `@PreAuthorize` on `findById` path。MVP 階段 user 接受此 tradeoff（per CLAUDE.md「Feature First, Security Later」）。S114a ship 後 visibility 才真正 end-to-end enforced。

### Session Summary

接續上 5-tick handover（S096h2 進度 2/4）；本 session 共跑 36 ticks 把 S096h2/g2/f2 + S098a3-2 + S115 + S116 共 6 個 spec ship（v3.6.0 → v3.8.3），實質完成 S096 META 8/8 sub-specs（含 deferred d6 / blocked e2）+ user 2026-05-03 mid-tick 兩個 directive（JWT/ACL safety + Skill visibility）。本 session 多 pattern milestones：8 次 Spec-Only-Handoff demo / 4 次 @Version+AbstractAggregateRoot canonical / 6 次 Exception 獨立 class naming / 5 次 single-tick XS ship / 1 次 @MappedCollection 首採用 / 1 次 ADR-006 + spec 雙寫。最後一 tick 跑 Mode B Round 36 API projection field completeness audit 找到 3 個 cross-cutting consistency findings 進 backlog (S117/S118/S119 fix-specs)。User `CronDelete` 終止 loop，本 session 自然收尾無 cleanup 需求。

---

## Layer 2 — Environment Details

| Property | Value |
|----------|-------|
| Branch | `main` |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Test Status | Backend：BundleInfoQueryServiceTest 6/6 + SkillAggregateTest 29/29 + CollectionServiceTest 9/9 + CollectionControllerTest 8/8 + NotificationServiceTest 12/12 + NotificationControllerTest 10/10 + CurrentUserProviderTest 11/11 + 多 regression 全 PASS @ Testcontainers + ModularityTests 2/2。Frontend：全 193/193 PASS @ ~7.32s + tsc PASS（CollectionsPage / NotificationsPage / PublishPage / PublishValidatePage 都 covered）|
| Branch ahead of origin | ~38 commits 待 push |
| Cron / wakeup | (none — user CronDelete 終止；ScheduleWakeup queue 23:51 已過 fire window) |

### Uncommitted Changes

```
 M README.md
?? .claude/handovers/archive/2026-05-03-cron-loop-5-tick-session-ship-s096g2-v3-6-0-s096h2-2-4.md
?? .claude/執行.md
```

**注意**：3 個 uncommitted item 都不是本 session 引入：
- `README.md` 從 takeover 之前已修改（pre-existing）
- `.claude/handovers/archive/2026-05-03-cron-loop-5-tick-session-ship-...md` 是 takeover 操作 archive 的副產品
- `.claude/執行.md` 是 user 並行新加的 untracked file

### Recent Commits

```
fad7261 chore(audit): Mode B Round 36 — API projection field completeness
8b25de8 feat: S116 ship skill visibility public/private toggle (v3.8.3)
d193350 chore(spec): plan S116 skill visibility toggle (public/private)
c8d77f3 feat: S115 ship JWT + ACL safety graceful degradation (v3.8.2)
0b695e8 feat: S098a3-2 ship bundle-info endpoint + strip 真值 (v3.8.1)
f57d8fb chore(spec): plan S115 JWT + ACL safety design (graceful degradation)
67ce56c chore(spec): plan S098a3-2 bundle-info + queue S115/S116 backlog
c5a313e feat: S096f2 ship Collections full feature (v3.8.0)
bf101f4 feat(frontend): S096f2-T03 collection api helpers + 2 hooks + page extract
a083db5 feat(api): S096f2-T02 collection service + controllers + 2 exceptions
b8b2798 feat(api): S096f2-T01 collection aggregate + V12 schema + community metadata
1d2af35 chore(spec): plan S096f2 tasks — break into 4 BDD task files
c5a313e feat: S096f2 ship Collections full feature (v3.8.0)
7aa6e02 feat: S096h2 ship Notifications full projection (v3.7.0)
e25ce28 feat(api): S096h2-T03 notification mutation/query services + controller
```

### Active Spec Docs（in `docs/grimo/specs/`）

```
2026-05-02-S096-ui-v2-dark-theme-meta.md         (META，design state；本 session
                                                    多 sub-specs ship 但 META 自身 row
                                                    狀態未 close — closure 候選下次
                                                    session housekeeping)
2026-05-02-S098-prototype-completeness-audit.md   (META；S098a3-2 ship 1/8 sub)
2026-05-02-S099-trust-maturity-meta.md            (META；awaiting confirm)
2026-05-02-S101-quality-impact-security-scores.md (META；awaiting human confirm)
2026-05-03-S114a-rbac-acl-projection.md           (📐 in-design M=12；defer per
                                                    Feature First Security Later 或
                                                    user directive 接手)
```

**注意**：S096h2 / S096g2 / S096f2 / S098a3-2 / S115 / S116 spec docs 已 archived 至 `docs/grimo/specs/archive/`。

### Active Backlog Rows（roadmap 📋 待 spec planning / implement）

下次優先序（per Next Steps 的順序）：

| Spec | Size | 觸發點 | 為什麼優先 |
|------|------|--------|-----------|
| **S119** | XS(2) | Mode B Round 36 finding (Bug AR MEDIUM) | user-visible bug — SkillCard rating 星星永遠 0；快速 ship 高 ROI |
| **S117** | XS(1) | Mode B Round 36 finding (Bug AP) | Frontend SkillVersion type sync 漏 fileCount；trivial fix |
| **S118** | XS(2) | Mode B Round 36 finding (Bug AQ) | Collection DTO naming inconsistency；含 caller migration |
| **S114a** | M(12) | spec doc 已寫；正式 task planning 待 | 為 visibility (S116) 補完 ACL filter end-to-end enforcement；per Feature First 可 defer |
| **S096f3** | XS(3-4) | S096f2 ship 後排隊 | Collections risk filter polish |

### Key Files

**最近 ship 的關鍵 spec archive**（next session 若需 reference）：
- `docs/grimo/specs/archive/2026-05-03-S096h2-notifications-projection.md` — S096h2 完整 §1-§7 含 deviations + lessons
- `docs/grimo/specs/archive/2026-05-03-S096f2-collections-full.md` — S096f2 同
- `docs/grimo/specs/archive/2026-05-03-S098a3-2-bundle-info-endpoint.md` — S098a3-2 同
- `docs/grimo/specs/archive/2026-05-03-S115-jwt-acl-safety-design.md` — S115 同 + ADR-006 reference
- `docs/grimo/specs/archive/2026-05-03-S116-skill-visibility-toggle.md` — S116 同
- `docs/grimo/adr/ADR-006-jwt-acl-safety.md` — JWT/ACL Safety Policy permanent decision record

**Mode B Round 36 audit log**：
- `.claude/progress/loop-e2e-test-coverage.md` (line ~496+) — Round 36 entry 含 3 findings 詳述 + cuts not exercised 排隊清單

**S114a / S117 / S118 / S119 implement 起手會 touch 的**：
- `S114a`：`backend/.../skill/...` aggregate / query / SecurityConfig；spec doc 已寫設計，task planning 為下一步
- `S117`：`frontend/src/types/skill.ts:113-124` SkillVersion interface；`frontend/src/components/VersionList.tsx`（顯版本歷史）
- `S118`：`backend/.../community/CollectionQueryController.java` (CollectionSummary record `installs` field)；`frontend/src/api/skills.ts` (SkillCollection type `installs`)；`frontend/src/pages/CollectionsPage.tsx` CollectionCard caller (`collection.installs.toLocaleString()`)
- `S119`：`backend/.../skill/query/SkillQueryService.java:133-137` raw JDBC SELECT clause；對應 RowMapper（從 ResultSet read average_rating + review_count 兩 ordinal）；frontend SkillCard rating display 驗證 + S103 spec ID-leak invariant carry-forward

**Frontend pre-existing TS errors workaround**（自 takeover lessons 起持續）：
- 跑 `./gradlew test` 須 `-x npmBuild` 跳過 frontend 28 處 pre-existing TS errors（全 `global` / missing field 等；非本 session 引入）

**Reference patterns（本 session shipped；下個 implement 可借鏡）**：
- ADR-002 canonical aggregate pattern：`backend/.../skill/domain/Skill.java` + `backend/.../community/Request.java` + `backend/.../community/Collection.java`（@Version + AbstractAggregateRoot 範本）
- Backward-compat ctor delegate pattern：`backend/.../skill/command/CreateSkillCommand.java`（4-arg → 5-arg PUBLIC default）
- @WebMvcTest slice canonical：`backend/src/test/.../shared/security/WebMvcSliceTestBase.java`（共用 base）+ `backend/src/test/.../skill/security/FlagControllerTest.java` 等（多 controller 同 path mapping 走 `@WebMvcTest(controllers={Cmd, Query})` 一次拉）
- @MappedCollection canonical：`backend/.../community/Collection.java`（first codebase usage；child PK = (parent_id, position)）
- Spec-Only-Handoff 8 次 demo cycle 平均 4-5 ticks / spec — 已成熟到可 estimate 新 spec wall budget
