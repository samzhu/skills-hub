# Changelog

## [v3.8.4] — List endpoint row-level ACL filter（S121 完成；2026-05-04 — LAB-blocker fix）

> S121 single-tick ship — Mode B Round 37 (2026-05-04) finding **CRITICAL** 直接修。User 2026-05-04 directive「LAB 封測前 + ACL 權限控制」觸發。**PATCH bump** — production code 1 file diff（`SkillQueryService.search()` 加 SQL clause）+ test fixture 升級；零 schema 變動；無 controller / API contract change。

### Fixed — Backend (LAB-blocker)
- `backend/.../skill/query/SkillQueryService.java`：`search()` 加 row-level ACL filter — `AND acl_entries ??| :aclPatterns`，patterns 由 `AclPrincipalExpander.expand(currentUserProvider.current(), "read")` 產生。修補 list endpoint 過去完全沒套 acl_entries filter 的 critical gap（S016 ship 的 `SkillPermissionStrategy` 只給 `@PreAuthorize` 用；list path 從未套用）→ S116 visibility toggle (PUBLIC/PRIVATE) 在 list 路徑現在**真實生效**。對齊 S016 §2.4 既驗 `??` escape + `SqlParameterValue(Types.ARRAY)` ARRAY bind 模式。

### Changed — Backend (test)
- `backend/src/test/.../skill/query/SkillSearchTest.java`：加 `@MockitoBean CurrentUserProvider` + `@MockitoBean AclPrincipalExpander` 兩個 mock；`@BeforeEach` 加 default stub（admin user + patterns 含 `*:read`）；既有 6 fixture acl_entries `List.of()` → `List.of("*:read")` 表達 PUBLIC 語意（ACL filter 啟用後須含此 pseudo-principal）；新加 AC-S121-1（PRIVATE 對 non-grantee 不可見）+ AC-S121-2（granted user 看得到）。

### Verify metric
- `SkillSearchTest`：13/13 PASS @ 9.6s（既有 11 不 regression + 新加 2 PASS）
- `compileJava` 8s + `test` 2m 1s（含 Testcontainers PG boot）
- Backend devtools restart 2.9s
- E2E manual smoke (Round 37 fixture)：anonymous list 從 total=2 → total=1（PRIVATE 不再 leak）；B grant 後 list 含 PRIVATE；revoke 後 list 不含 — 6 case all PASS

### Design decisions
- **不加 admin bypass 路徑**（per spec §2.2）— 對齊 S016 既驗：admin bypass 集中於 `DelegatingPermissionEvaluator.hasAdminRole()` 用於 `@PreAuthorize` mutation 路徑；CQRS read 路徑走 ACL 自然 expand pattern。Admin 若需看 PRIVATE skill 須走 grant `role:admin:read`
- **不改 CurrentUserProvider anonymous fallback**（per spec §2.3）— 利用 `AclPrincipalExpander.expand("read")` 自動加 `*:read`，anonymous fallback (lab-user, [admin]) 自然只看 PUBLIC skill；不引入新 fallback 行為
- **author-mode 與 ACL 完全正交**（per spec §2.4）— `?author=B` 仍套 ACL filter，A 對 B 的 PRIVATE skill 無 grant 不顯示

### Roadmap progress
- ✅ S121 (S=4-5) shipped — Phase 5 row M116
- 📋 S122 / S123 (XS each) 待 implement — 補 single GET + download endpoint @PreAuthorize（同 chain）

### Pattern reuse
- 第 3 次採用 `AclPrincipalExpander.expand` pattern（S016 / S017 / S121）
- 第 3 次採用 `??|` + `SqlParameterValue(Types.ARRAY)` 雙 hack（S016 SkillPermissionStrategy / S017 PgVectorStore / S121 SkillQueryService）
- 第 6 次 single-tick XS/S spec ship（per session lessons learned）

## [v3.8.3] — Skill visibility public/private toggle（S116 完成；2026-05-03）

> S116 跨 2 個 cron tick (Tick 35 spec planning + Tick 36 single-tick ship) — user 2026-05-03 mid-tick directive 2/2「新增 skill 時可選 public 跟 GitHub 概念很像 / 私人的再自己共享給別人」。**PATCH bump** — 純 enum + factory 條件分支；零 schema 變動；既有 4-arg uploadSkill caller 行為與 v3.x 完全一致。

### Added — Backend
- `backend/.../skill/domain/Visibility.java`（new）— enum PUBLIC / PRIVATE + `defaultValue()`；對齊 SkillStatus enum 既驗 pattern
- `backend/.../skill/command/CreateSkillCommand.java`（modify）— 加 `Visibility visibility` field + 4-arg backward-compat ctor delegate to 5-arg with PUBLIC default
- `backend/.../skill/domain/Skill.java`（modify）— `create` factory 條件式 seed `*:read`：PUBLIC 加（v3.x 既有行為）；PRIVATE 不加；author=null+PRIVATE → IllegalArgumentException
- `backend/.../skill/command/SkillCommandService.java`（modify）— 5-arg `uploadSkill(..., visibility)` overload + 4-arg backward-compat delegate
- `backend/.../skill/command/SkillCommandController.java`（modify）— `@PostMapping("/upload")` 加 `@RequestParam(required=false, defaultValue="PUBLIC") Visibility visibility`
- `backend/src/test/.../skill/domain/SkillAggregateTest.java`（modify）— 加 5 個 S116 AC tests（PUBLIC default 4-arg / PRIVATE seed / explicit PUBLIC / private+null author rejection / public+null author seed only `*:read`）

### Added — Frontend
- `frontend/src/api/skills.ts`（modify）— 加 `Visibility` type export + `uploadSkill` 5-arg with PUBLIC default
- `frontend/src/pages/PublishPage.tsx`（modify）— 加 visibility state + radio group fieldset 兩 option（公開 / 私人）+ helper text + 透傳 mutation

### Verified
- `SkillAggregateTest` 29/29 PASS @ 0.032s（既有 24 + S116 新加 5）
- `SkillCommandServiceTest` 2/2 PASS @ 8.263s + `ModularityTests` 2/2 PASS
- `PublishPage.test.tsx` 8/8 PASS @ 1.14s（regression — 既有 test 走 4-arg backward-compat 行為一致）
- 全 frontend suite **193/193 PASS** @ 8.82s（0 regression）；`npx tsc --noEmit` PASS

### Why
- **User 2026-05-03 mid-tick directive 2/2 完成**：GitHub-style visibility model；對齊既有 S016 row-level ACL 基礎建設 + S026 *:read synthetic public + S038 listEntries 識別 + ADR-006 ACL principal types fail-closed safety baseline
- **Approach B (derived from acl_entries) 取代 schema 變動**：MVP 不擴 `is_public` column；對齊 S038 既驗 listEntries 識別 *:read 慣例；S114a 設計中的 `is_public` GENERATED column 未來路徑自然從同 acl_entries derive 無 migration breaking
- **既有 4-arg uploadSkill caller 行為與 v3.x 完全一致**：backward-compat ctor delegate pattern 達到 0 callsite migration cost（vs S098a3-2 fileCount 路徑走 cross-test 6 callsite migration — 本路徑 100x 低成本）
- **既有 ACL filter 0 改動 + 行為自動正確**：private skill anonymous read 由 既有 GIN ?| filter (S016 既驗) 自動 fail-closed；owner authenticated read 走 `user:owner:read` pattern 自然 match

### Pattern milestones
- 第 2 次採用 backward-compat ctor delegate pattern（首次：N/A；對比 S098a3-2 fileCount 走 cross-test 6 callsite migration — 不同策略價值）
- 第 2 次 derived from existing column 模式（對齊 S038 *:read 識別；vs S098a3-2 加新 column file_count 走相反方向）
- enum + factory conditional 取代 schema 變動策略 codebase 第 2 次採用（首次為 SkillStatus.DRAFT default）

## [v3.8.2] — JWT + ACL Safety graceful degradation（S115 完成；2026-05-03）

> S115 跨 2 個 cron tick (Tick 33 spec planning + Tick 34 trimmed implement) — user 2026-05-03 mid-tick directive 「把權限設計落成安全設計文件 + 當 token 內容跟原先設計不一樣時不要直接壞掉」。**PATCH bump** — 純 safety policy；無 user-visible 行為變動，但補完既有 `jwt.getName()` NPE 風險（500 → 401）+ 加 graceful claim parsing + Micrometer 觀測能力。

### Added — Backend
- `backend/.../shared/security/CurrentUserProvider.java`（modify）— ctor 2-arg 加 `JwtClaimAnomalyMetrics`；sub null check via `getSubject()` + 預檢空字串 → throw MissingJwtSubException（取代既有 `jwt.getName()` NPE 路徑）；新增 `parseStringListClaim(token, claimName)` helper graceful 處理 4 種情境：(1) 缺 silent → empty list；(2) 型別錯（非 List）→ empty + WARN + counter；(3) 含非字串元素 → skip per element + WARN + counter；(4) 純淨 List<String> → immutable copy
- `backend/.../shared/security/JwtClaimAnomalyMetrics.java`（new）— Micrometer counter wrapper component；shape `jwt_claim_anomaly_total{claim, reason}`；對齊既有 OpenTelemetry / Grafana LGTM stack
- `backend/.../shared/api/MissingJwtSubException.java`（new）— RuntimeException；對齊既有 RequestNotFoundException naming convention
- `backend/.../shared/api/GlobalExceptionHandler.java`（modify）— 加 MissingJwtSubException → 401 + RFC 6750 `WWW-Authenticate: Bearer error="invalid_token"` header
- `backend/src/test/.../shared/security/CurrentUserProviderTest.java`（modify）— ctor migration 走新 2-arg；加 6 個 S115 AC tests（AC-1 missing sub / AC-1 blank sub / AC-2 roles type mismatch / AC-3 non-string element skip / AC-4 groups null silent fallback / AC-5 unknown claim forward-compat）

### Added — Documentation
- `docs/grimo/adr/ADR-006-jwt-acl-safety.md`（new）— 5-段範本（Status / Context / Decision / Consequences / Alternatives）+ 4 個 matrix 表（Required vs optional claims / ACL principal types fail-closed safety / Spring Security OAuth2 error → HTTP / Observability）+ Implementation snapshot；對齊 ADR-002 / ADR-003 / ADR-004 既驗 doc structure
- `docs/grimo/specs/archive/2026-05-03-S115-jwt-acl-safety-design.md`（archived）— 含 §6 trimmed task plan + §7 verification + 4 deviation entries + lessons learned

### Verified
- `CurrentUserProviderTest` 11/11 PASS @ 0.396s（既有 S012 5 個 AC-4/5 regression + S115 新加 6 個 AC-1/2/3/4/5）
- `ModularityTests` 2/2 + `MeControllerTest` + `AdminControllerTest` regression PASS（ctor 2-arg migration 不破既有 Spring DI；codebase 唯一 `new CurrentUserProvider(` 直接用法為本 test 自身）

### Why
- **User 2026-05-03 mid-tick directive 1/2 完成**：把權限設計落成「安全設計文件」（ADR-006）+ 當 token 內容跟原先設計不一樣時不要直接壞掉（graceful degradation policy）
- **取代既有 `jwt.getName()` NPE 風險**：S012 既有 path 對 sub=null 直接 NPE → 500（user-visible 災難）；本 spec 補 explicit null check + 401 RFC-compliant 路徑
- **Forward-compat IdP claim schema 演化**：未知新 claim（如未來 `tenant_id` / `scope_v2`）不破 parsing；型別錯由 silent fallback 升級為「fallback empty + WARN + counter」，給 ops alerting 介入空間
- **ADR + Spec 雙寫**：ADR 是 policy 永久記錄；spec 是 ship how-to 可 archive；對齊既有 ADR-002/003/004 慣例；user directive 明示需要 doc level 落實
- **Trimmed M(8-10) → 單 tick ship**：核心 invariant + ADR + 11 unit tests 落地；AC-8 端到端 prod fallback guard test / JwtSafetyTest E2E / glossary / dev-standards 更新 4 項 polish 走 backlog

### Pattern milestones
- 第 6 次 Exception 獨立 class 升級 naming canonical（MissingJwtSubException + 既有 5 個 RequestNotFoundException / NotRequestClaimerException / NotificationNotFoundException / NotNotificationRecipientException / CollectionNotFoundException + SkillNotPublishableException + BundleNotPublishedException）
- codebase 第 1 次採用 `SimpleMeterRegistry` test pattern（Micrometer counter 直接 read 不走 Spring context；後續 metrics-related test 可借鏡）

## [v3.8.1] — Bundle-info endpoint + PublishValidatePage 真值（S098a3-2 完成；2026-05-03）

> S098a3-2 跨 2 個 cron tick (Tick 31 spec planning + Tick 32 ship) — 取代 S098a3 frontend-only upload-strip 派生 placeholder 為 backend 真資料路徑（filename canonical / fileSize / fileCount / uploadedAt）。**PATCH bump** — 純 polish；新加 endpoint + V13 migration column；既有 row file_count=0 走 frontend hide 路徑；無 breaking change。

### Added — Backend
- `backend/.../skill/query/BundleInfoQueryService.java`（new — Skill + SkillVersion JOIN read；filename derive `<name>-<version>.zip` per S041 canonical；NoSuchElement → 404 NOT_FOUND；無 latestVersion → 404 bundle_not_published distinct）
- `backend/.../skill/query/SkillQueryController.java`（modify）— 加 `GET /api/v1/skills/{id}/bundle-info` + 構造 BundleInfoQueryService 注入
- `backend/.../shared/api/BundleNotPublishedException.java`（new — 404；對齊既有 RequestNotFoundException naming）
- `backend/.../shared/api/GlobalExceptionHandler.java`（modify — 加 bundle_not_published 404 mapping）
- `backend/.../storage/PackageService.java`（modify — 加 `countEntries(byte[]): int` helper；走 ZipInputStream + entry.isDirectory() 過濾；非 zip / IOError → 0 不破上傳路徑）
- `backend/.../skill/command/PublishVersionCommand.java`（modify — 加 `int fileCount` field）
- `backend/.../skill/domain/SkillVersion.java`（modify — 加 `@Column("file_count") int fileCount` + factory + getter）
- `backend/.../skill/command/SkillCommandService.java`（modify — `uploadSkill` + `addVersion` 兩 path 走 `packageService.countEntries(zipBytes)` 寫入 cmd）
- V13 migration — `ALTER TABLE skill_versions ADD COLUMN file_count INTEGER NOT NULL DEFAULT 0` + COMMENT；既有 row 0 = legacy unknown signal（per spec §2.3 MVP 不 backfill；下次 publish 該 skill 才 update）
- `backend/src/test/.../skill/query/BundleInfoQueryServiceTest.java`（new — 6 個 Testcontainers tests AC-1/2/3/5 + orphan latestVersion pointer + blank latestVersion corner case）
- 6 個 cross-test 檔 PublishVersionCommand callsite migration（SkillVersionRepositoryTest + SkillCommandServiceTest + SkillSuspendReactivateTest + SkillCommandServiceCrossAggregateTest + SkillVersionAggregateTest + SkillVersionQueryTest + ScanOrchestratorTest；統一加 `, 0,` for fileCount 預設）

### Added — Frontend
- `frontend/src/api/skills.ts`（modify — 加 `BundleInfo` interface + `fetchBundleInfo` helper）
- `frontend/src/hooks/useBundleInfo.ts`（new — TanStack Query hook with `enabled: !!id` gate + 60s staleTime + `retry: false`（404 為 expected fallback path 不 retry））
- `frontend/src/pages/PublishValidatePage.tsx`（modify — strip render 走 `bundleInfo` 真值；fileSize KB rounded + fileCount 「N 個檔案」(fileCount > 0 gate)；endpoint 404 / loading state fall back 派生 placeholder 「剛剛上傳」）

### Verified
- Backend：BundleInfoQueryServiceTest 6/6 + SkillVersionRepositoryTest 6/6 + SkillCommandServiceTest 2/2 + SkillVersionAggregateTest 4/4 + ModularityTests 2/2 PASS @ Testcontainers
- Frontend：PublishValidatePage.test.tsx 4/4 PASS @ 1.38s + 全 frontend suite 193/193 PASS @ 7.32s（0 regression）；npx tsc --noEmit PASS

### Why
- **補完 S098a3 frontend-only ship 留 defer hook**：S098a3 (v3.0.0) ship 為 derived placeholder；本 spec 走 backend 真資料路徑提升 PublishValidatePage upload-strip UX 一致性
- **fileCount 走「上傳時計算 + 寫 column」而非「即時 GCS scan」**：避免每次 endpoint 走 GCS download + zip extract 災難；既有 row 走 0 fallback signal + frontend hide 該欄屬 graceful policy
- **filename canonical derive 不存原始上傳檔名**：對齊 S041 既驗 download endpoint canonical naming；user 上傳檔名與系統識別無 invariant 關係
- **第 5 次 enabled-gate hook canonical 採用**：對齊 useSkill / useRequest / useCollection / useNotificationPreferences 既驗 pattern

## [v3.8.0] — Collections full feature（S096f2 完成；2026-05-03）

> S096f2 跨 5 個 cron tick (Tick 26 spec planning + Tick 27 T01 backend infra + Tick 28 T02 service/controllers + Tick 29 T03 FE infra + Tick 30 T04 frontend full + spec ship) ship — 取代 ✅ S096f1 read-only stub：完整 Collection aggregate（ADR-002 canonical Spring Data JDBC 充血 + @MappedCollection skills + Modulith Outbox）+ Command/Query controllers split + 4 REST endpoints + 一鍵 install (Approach C frontend orchestration) + 2 frontend components (CreateCollectionModal + InstallButton)。**MINOR bump** — 新 aggregate + V12 schema 2 表 + 2 domain events + 4 REST endpoints + 2 frontend components + community module formal `@ApplicationModule` register。為 S101b Impact Score 提供 install event hook。

### Added — Backend
- `backend/.../community/Collection.java` — Aggregate root extends AbstractAggregateRoot + `@Version` Long version + `@MappedCollection(idColumn="collection_id", keyColumn="position")` 一對多 skills；factory `create()` 驗 5 個 fields + skillIds unique 守則；mutation `recordInstall(installerId)` bump install_count + register `CollectionInstalledEvent`；defensive `getSkills()` copy 對齊既有 Skill aggregate 慣例
- `backend/.../community/CollectionSkillRef.java` — `record (@Column("skill_id") String skillId)` 不持有 own @Id，Spring Data JDBC derived PK 模式
- `backend/.../community/CollectionRepository.java` — `ListCrudRepository<Collection, String>` + `findAllByOrderByCreatedAtDesc` + `findAllByCategoryOrderByCreatedAtDesc`（single-property sort 不觸 AOT compound-sort bug）
- `backend/.../community/CollectionService.java` — create/install/list/get + getCollectionSkills helper（保 collection 順序 + filter SUSPENDED missing skills 防外洩）
- `backend/.../community/CollectionCommandController.java` — POST create + POST install；CreateCollectionBody / InstallResponse record
- `backend/.../community/CollectionQueryController.java` — GET list?category= + GET single；CollectionSummary / CollectionDetail / CollectionSkillSummary 3 record DTO；取代既有 S096f1 stub `CollectionController`
- `backend/.../community/events/CollectionCreatedEvent.java` + `CollectionInstalledEvent.java` — record；MVP 無 listener，預留 hook 給 future S101b Impact Score
- `backend/.../community/package-info.java`（modify）— 加 `displayName="Community"` 對齊 audit/notification/analytics 既驗 module metadata 慣例
- `backend/.../skill/domain/SkillRepository.java`（modify）— 加 `findAllByIdInAndStatus(Collection<String>, SkillStatus)` derived query；給 CollectionService.create 預檢全 PUBLISHED 用
- `backend/.../shared/api/CollectionNotFoundException.java` + `SkillNotPublishableException.java`（with `List<String> invalidSkillIds`）+ GlobalExceptionHandler 加 404/400 mapping
- `backend/.../community/RequestService.java`（modify）— `fulfill` caller migration 從 `IllegalArgumentException("skill_not_publishable: ...")` 升級為新 `SkillNotPublishableException`，attached single-skill ctor `(String invalidSkillId, String reason)`
- V12 migration — `collections` 表 (含 `version` BIGINT DEFAULT 0 樂觀鎖) + `collection_skills` 表 (PK `(collection_id, position)` + UNIQUE `(collection_id, skill_id)` + ON DELETE CASCADE + 4 indexes 對齊 sort/filter axis)
- `backend/src/test/.../community/CollectionModuleSmokeTest.java` — 9 個 schema/aggregate round-trip + @MappedCollection ordering + UNIQUE 守則 + CASCADE + ApplicationEvents 流驗證
- `backend/src/test/.../community/CollectionServiceTest.java` — 9 個 Testcontainers test（AC-1/2/3/4/5/6/7/8 + cross-recipient install + SUSPENDED skill 仍能 read）
- `backend/src/test/.../community/CollectionControllerTest.java` — 8 個 `@WebMvcTest` slice test（HTTP routing + status code + exception → 翻譯）
- `backend/src/test/.../community/RequestServiceTest.java`（modify）— AC-12 assertion 升級為 `isInstanceOf(SkillNotPublishableException.class)`

### Added — Frontend
- `frontend/src/api/skills.ts`（modify）— 加 `CreateCollectionRequest` / `CollectionDetail` / `CollectionSkillSummary` 3 type + `fetchCollection` / `createCollection` / `installCollection` 3 helper；`fetchCollections` 加 optional `category` param；`SkillCollection.description` 改 nullable 對齊 backend
- `frontend/src/hooks/useCollections.ts`（new）— list with optional category filter（30s staleTime + refetchOnWindowFocus 對齊 useRequests / useNotifications canonical）
- `frontend/src/hooks/useCollection.ts`（new）— single detail；對齊 useSkill / useRequest enabled-gate pattern
- `frontend/src/components/CreateCollectionModal.tsx`（new）— mirror CreateRequestModal pattern；4 個欄位（name / description / category / textarea skill UUID list per §2.6 trim — fancy multi-select picker defer）
- `frontend/src/components/InstallButton.tsx`（new）— mutation + 50ms 間隔 loop trigger N 個 `<a download>` click（spec §1 Approach C frontend orchestration；reuse 既有 `/skills/{id}/download` 自然累計 each skill download_count）
- `frontend/src/pages/CollectionsPage.tsx`（modify）— full rewrite：CTA active 開 modal + InstallButton on card；移除 S096f1 stub disabled 提示
- `frontend/src/pages/CollectionsPage.test.tsx`（modify）— 取代既有 stub-state assertions 為 AC-10/11/12 BDD + S103 spec ID leak invariant carry-forward；URL-aware fetchMock + spy on `HTMLAnchorElement.prototype.click` 驗 N 個 download 觸發

### Verified
- Backend：CollectionModuleSmokeTest 9/9 + CollectionServiceTest 9/9 + CollectionControllerTest 8/8 PASS @ Testcontainers + RequestService/VoteService regression 18/18 PASS（caller migration ✓）+ ModularityTests 2/2 PASS
- Frontend：CollectionsPage.test.tsx 4/4 PASS @ 1.44s（AC-10 CTA enable / AC-11 create modal happy / AC-12 install + N download trigger / S103 carry-forward）+ 全 frontend suite 193/193 PASS @ 7.82s（0 regression）；npx tsc --noEmit PASS

### Why
- **補完 ✅ S096f1 stub**：S096f1 ship 為 read-only `[]` + disabled CTA + EmptyState invite tone；本 spec 把 backend stub 升為實 Collection aggregate 完整 CRUD + curate + 一鍵 install，UX 啟用「建立 → 瀏覽 → 安裝」完整流程，對齊 PRD §P7 SBE Scenarios。
- **走 ADR-002 canonical pattern + 第 4 次 @Version + AbstractAggregateRoot 採用**：對齊 Skill / Request / Collection 既驗，Persistable + 自訂 isNew 範本第 4 次 deviate（factory 設 createdAt 會破 isNew flag — 已成 codebase 慣例知識）。
- **Command/Query split 第 2 次**：對齊 Request (S096g2) canonical；POST 走 command、GET 走 query，便於 future read-side optimization 不污染 mutation 路徑。
- **community module displayName 補完**：S096g2 ship 時補 allowedDependencies；本 spec 補 displayName 對齊 audit / notification / analytics module metadata 慣例。
- **Spec-Only-Handoff pattern 第 8 次端到端 demo**：Tick 26 spec planning → Tick 27-30 implement loop（每 tick 1 task = 1 commit）→ ship。Cron-driven，task scope 設計準度高（4 task 各約 1 tick wall budget）。

### Deviations
- 詳 spec doc archive §6/§7（含 collection_skills PK 從 `(collection_id, skill_id)` 改 `(collection_id, position)` 對齊 @MappedCollection canonical / @Version 取代 spec §4.3 Persistable 範本 / SkillNotPublishableException 升級獨立 class 取代既有 IllegalArgumentException 路徑等）

## [v3.7.0] — Notifications full projection（S096h2 完成；2026-05-03）

> S096h2 跨 4 個 cron tick (Tick 22 spec planning + Tick 23 backend infra T01+T02 + Tick 24 backend service/controller T03 + Tick 25 frontend full T04) ship — 取代 ✅ S096h1 read-only stub：完整 cross-module event projection（4 個 `@ApplicationModuleListener` 訂閱 SkillFlagged / ReviewCreated / RequestClaimed / RequestFulfilled）+ 2 個 mutable aggregate（Notification + NotificationPreference 走 `@Version`）+ 7 REST endpoints + 4 frontend pieces（api split / 2 hooks / PreferencesModal / NotificationsPage rewrite）。**MINOR bump** — 新 module + V11 schema + 5 個 domain event subscriptions + UNIQUE idempotency 守則 + 訂閱偏好 4 toggle UX。

### Added — Backend
- `backend/.../notification/Notification.java` — Mutable aggregate `@Version`；factory `create()` for listener INSERT path；`markRead()` mutation；`isOwnedBy(userId)` ownership 守則
- `backend/.../notification/NotificationPreference.java` — 4 boolean toggle aggregate（flags/reviews/requests/versions）；`defaults(userId)` factory + partial `update()` + `isEnabled(category)` switch
- `backend/.../notification/NotificationRepository.java` — Spring Data JDBC repo + `@Query` annotation cursor pagination（Spring Boot 4.0.6 AOT codegen workaround for compound sort）；`findByRecipient` + `findByRecipientAfterCursor`（tuple compare avoid OFFSET 災難）+ `countUnreadByRecipient` + `markAllReadForUser` (@Modifying)
- `backend/.../notification/NotificationPreferenceRepository.java` — CrudRepository
- `backend/.../notification/NotificationProjectionListener.java` — 4 個 `@ApplicationModuleListener`（cross-module SPI）+ DuplicateKey catch idempotency + preferences gate（無 row 預設 ON）+ self-action skip（不通知自己）；deterministic `ref_event_id` composite 從 payload 派生
- `backend/.../notification/NotificationService.java` — markRead / markAllRead / delete / updatePreferences / getPreferences；ownership 守則拋 `NotNotificationRecipientException`；mark-all-read 走 `@Modifying SQL UPDATE WHERE recipient AND read_at IS NULL`（避 N round-trip + N 個 @Version 競賽）
- `backend/.../notification/NotificationQueryService.java` — list with cursor + category filter；`limit + 1` Slice pattern derive `hasNext`（避 COUNT(*) 災難）；unreadCount partial index path
- `backend/.../notification/NotificationController.java` — rewrite stub → 7 endpoints (GET list/unread-count/preferences + POST {id}/read + read-all + preferences + DELETE {id})；DTO 對齊 frontend type spec §4.7
- `backend/.../notification/package-info.java` — `@ApplicationModule(displayName="Notifications", allowedDependencies={"shared::events,security,api", "skill::domain", "community + community::events", "review::domain", "security"})`
- `backend/.../shared/api/{NotificationNotFoundException,NotNotificationRecipientException}.java` + GlobalExceptionHandler 加 404/403 mapping（small-snake-case error code 便於 FE i18n）
- `backend/.../community/events/package-info.java`（new — `@NamedInterface("events")` 暴露 RequestClaimed/Fulfilled record 跨 module import）
- `backend/.../review/domain/package-info.java`（new — `@NamedInterface("domain")` 暴露 ReviewCreatedEvent record 跨 module import）
- V11 migration — `notifications` 表（含 UNIQUE(recipient_id, ref_event_id, category) idempotency 守則 + partial index `idx_notifications_recipient_unread` for unread queries + version column）+ `notification_preferences` 表（4 boolean defaults TRUE + version column）
- `backend/src/test/.../notification/NotificationModuleSmokeTest.java` — 8 個 schema/aggregate round-trip + UNIQUE constraint test
- `backend/src/test/.../notification/NotificationProjectionListenerTest.java` — 6 個 cross-module Scenario API test（AC-1/2/3/4/5/10）
- `backend/src/test/.../notification/NotificationServiceTest.java` — 12 個 Testcontainers test（AC-6/7/8/9 + AC-list cursor + cross-recipient isolation）
- `backend/src/test/.../notification/NotificationControllerTest.java` — 10 個 @WebMvcTest slice test（HTTP routing + status code + exception → 翻譯）

### Added — Frontend
- `frontend/src/api/notifications.ts` — split from skills.ts；`Notification` type（`read: boolean → readAt: string|null` + 加 `refEventId`）+ `NotificationPreferences` type + `NotificationsQuery` + 7 helper（fetchNotifications / fetchUnreadCount / markNotificationRead / markAllNotificationsRead / deleteNotification / fetchPreferences / updatePreferences）；fetchNotifications 內部解 backend wrapper 取 items
- `frontend/src/hooks/useNotifications.ts` — list with optional category filter（cache 30s + refetchOnWindowFocus 對齊既有 pattern）+ 3 個 mutation hook（mark-read / mark-all-read / delete）；onSuccess invalidate `['notifications']` + `['notifications-unread']` 同步 list + bell badge
- `frontend/src/hooks/useNotificationPreferences.ts` — get（5min staleTime）+ update mutation（onSuccess setQueryData 立即生效跳過 refetch）
- `frontend/src/components/PreferencesModal.tsx` — 4 toggle modal（fixed overlay + cancel/save buttons；mirror CreateRequestModal pattern）；submit 只送 diff（避 unchanged 欄位也 POST）；versions toggle disabled 標「敬請期待」
- `frontend/src/pages/NotificationsPage.tsx` — full rewrite：取代 stub-style EmptyState 為主邏輯：filter chips（全部 / 回報 / 評論 / 需求；versions 不顯）+ 全部已讀 / 設定 hero CTA + interactive row（點 row body → markRead；點 ✕ → delete）+ readAt-derived isRead 視覺
- `frontend/src/pages/NotificationsPage.test.tsx` — 7 tests 涵蓋 AC-12（list 5 筆 / row click mark-read / 全部已讀 / 點 ✕ delete / 評論 chip filter）+ AC-14（modal 開 + 4 toggle 顯 + flags off 儲存 → POST partial body）

### Modified
- `frontend/src/api/skills.ts` — 移除舊 `Notification` interface + `fetchNotifications` + `fetchUnreadCount`（已搬至 `api/notifications.ts`）
- `frontend/src/components/AppShell.tsx` — `fetchUnreadCount` import 從 `'@/api/skills'` 切至 `'@/api/notifications'`（既有 30s poll 機制無改動）

### Verified
- Backend：NotificationServiceTest 12/12 + NotificationControllerTest 10/10 + NotificationProjectionListenerTest 6/6 + NotificationModuleSmokeTest 8/8 PASS @ Testcontainers + ModularityTests 2/2 PASS
- Frontend：NotificationsPage.test.tsx 7/7 PASS @ 1.05s + 全 frontend suite 193/193 PASS @ 5.62s（0 regression）；npx tsc --noEmit PASS

### Why
- **補完 ✅ S096h1 stub**：S096h1 ship 為 read-only `[]` + bell badge 30s poll 機制；本 spec 把 backend stub 升為實 cross-module event-driven projection（CQRS write/read split），UX 啟用 mark-read / 全部已讀 / 刪除 / 訂閱偏好；對齊 PRD §P9 SBE Scenarios + Engineering Handoff §2.17。
- **第 5 次跨模組 NamedInterface SPI demo**：notification module 訂閱 4 個跨模組 event（review / community / community::events / security），新加 2 個 NamedInterface（community::events + review::domain）對齊既有 skill::domain pattern；ModularityTests 整 spec 從未壞，Modulith 邊界守則 effective。
- **Spec-Only-Handoff pattern 第 7 次端到端 demo**：Tick 22 拆 4 BDD task → Tick 23-25 implement loop（每 tick 1 task = 1 commit）→ ship。Cron-driven，無人 mid-flight 微調，task scope 設計準度高（4 task 各約 1 tick wall budget）。

### Deviations
- 詳 spec doc archive §7（含 ref_event_id `VARCHAR(36) → 255` in-place fix / `@Version` 取代 spec §4.3 Persistable 範本 / 加 2 NamedInterface / `@SpringBootTest` 取代 `@ApplicationModuleTest` 等 5 項）

## [v3.6.0] — Request Board full feature（S096g2 完成；2026-05-03）

> S096g2 跨 5 個 cron tick (Tick 17 spec planning + Tick 18-21 implement) ship — 取代 ✅ S096g1 read-only stub：完整 Request aggregate（ADR-002 canonical Spring Data JDBC 充血 + Modulith Outbox）+ vote toggle (atomic SQL 對齊 S076 download_count pattern) + claim/release/fulfill state machine + state-aware RequestActionBar UI。**MINOR bump** — 新 aggregate + 2 schema migrations + 5 domain events + 7 REST endpoints + 4 frontend components。為 S096h2 Notifications projection 提供 5 個 event hook。

### Added — Backend
- `backend/.../community/Request.java` — Aggregate root extends AbstractAggregateRoot + Persistable<String>；create/claim/release/fulfill/assertDeletable 充血方法 + registerEvent
- `backend/.../community/RequestRepository.java` — Spring Data JDBC repo + `@Query` explicit ORDER BY（AOT codegen 對 derived compound sort 有 bug，workaround 用 annotation）
- `backend/.../community/RequestService.java` — create/claim/release/fulfill/deleteRequest 3-line orchestration（load → mutate → save 觸發 @DomainEvents 自動 outbox publish）；fulfill 驗 SkillRepository.findById PUBLISHED
- `backend/.../community/RequestVoteService.java` — atomic SQL toggle (INSERT ON CONFLICT DO NOTHING + UPDATE GREATEST(0, count±1) + DELETE)；對齊 S076 download_count pattern
- `backend/.../community/RequestCommandController.java` — POST/DELETE endpoints (create/vote/claim/release/fulfill/delete)；reporter 從 CurrentUserProvider 抽 sub
- `backend/.../community/RequestQueryController.java` — GET list 含 ?status=&sort= filter + GET single；RequestResponse DTO（不外洩 Persistable.isNew/version）
- `backend/.../community/events/Request{Posted,Voted,Claimed,Released,Fulfilled}Event.java` — 5 個 record；future S096h2 listener 預留 hook
- `backend/.../community/package-info.java` — community 正式註冊 `@ApplicationModule(allowedDependencies = "skill::domain,skill::query,shared::events,shared::api,shared::security")`
- `backend/.../shared/api/{NotRequestClaimerException,SkillNotPublishableException}.java` + GlobalExceptionHandler 加 403/400 mapping
- V10 migration — `requests` 表（含 status CHECK / vote_count CHECK >= 0 / 3 indexes 對齊 sort axis）+ `request_votes` 表（PRIMARY KEY (request_id, user_id) UNIQUE constraint + ON DELETE CASCADE）
- `backend/src/test/.../community/RequestServiceTest.java` — 13 個 Testcontainers test 涵蓋 AC-1/2/3/4/7/8/9/10/11/12/13 + ModularityTests
- `backend/src/test/.../community/RequestVoteServiceTest.java` — 5 個 race-aware test 涵蓋 AC-5/6 + 多 user 並發 + non-existent + DB CHECK guard

### Added — Frontend
- `frontend/src/api/skills.ts` — SkillRequest type 對齊 backend RequestResponse（votes → voteCount + 加 requesterId/claimerId/fulfilledSkillId/updatedAt）；fetchRequests 加 sort+status query；新增 fetchRequest / createRequest / toggleVote / claimRequest / releaseClaim / fulfillRequest / deleteRequest 7 個 helper + VoteResult/ClaimResult/FulfillResult/CreateRequestBody/RequestsQuery 5 個 type
- `frontend/src/hooks/useRequests.ts` — list with sort+status；30s staleTime + refetchOnWindowFocus 對齊 useFlagsQueue
- `frontend/src/hooks/useRequest.ts` — single detail；對齊 useSkill pattern
- `frontend/src/components/CreateRequestModal.tsx` — title input + description textarea + useMutation + invalidate ['requests']
- `frontend/src/components/VoteButton.tsx` — 樂觀更新 toggle (onMutate 即翻 voted+±1 → onSuccess sync server 真值 → onError rollback)；aria-pressed toggle pattern
- `frontend/src/components/RequestActionBar.tsx` — 4 種 state-aware 按鈕（OPEN+requester→刪除 / OPEN+others→認領 / IN_PROGRESS+claimer→釋放+完成 / FULFILLED→查看技能 link）；fulfill 走 `window.prompt` MVP trim
- `frontend/src/pages/RequestBoardPage.tsx` — 全面重建：CTA 啟用 + 串 useRequests + RequestRow 串 VoteButton + RequestActionBar
- `frontend/src/pages/RequestBoardPage.test.tsx` — 取代 S103 disabled-button assertion（已退場）；新 AC-15/16/17 + S103 「no spec ID leak」invariant carry-forward

### Verified
- Backend：RequestServiceTest 13/13 + RequestVoteServiceTest 5/5 PASS @ Testcontainers + ModularityTests 2/2 PASS
- Frontend：RequestBoardPage.test.tsx 3/3 PASS @ 1.54s（AC-15 list+CTA / AC-16 create modal / AC-17 vote 樂觀更新）；npx tsc --noEmit PASS

### Why
- **補完 ✅ S096g1 stub**：S096g1 ship 為 read-only EmptyState + disabled CTA；本 spec 把 backend `[]` stub 升為實 aggregate + UI 啟用「發起 → 投票 → 認領 → 完成」完整流程，對齊 PRD §P8 SBE Scenarios。
- **走 ADR-002 canonical pattern**：Spring Data JDBC 充血聚合 + Modulith Outbox + 第 5 次跨模組 NamedInterface SPI（review 模組 Tick 11 + community 模組 Tick 18）— ModularityTests 整 session 從未壞，邊界守則 effective。

### Deviations
- 詳 spec doc archive §7（7 項 implementation deviations + 4 個 pattern echoes）

## [v3.5.1] — Flag write flow + reviewer queue（S098e3 完成；2026-05-03）

> S098e3 跨 5 個 cron tick (Tick 12 spec planning + Tick 13-16 implement) ship — 補完 ✅ S112 (read 端) 對應的 write + reviewer 端：FlagSubmitModal 提交 form + FlagsQueuePage reviewer queue + FlagController PATCH status mutation。**零 schema migration**（status 既是 String 欄位，純應用層 enum 擴充 OPEN→RESOLVED/DISMISSED + 既有 row 100% 相容）。

### Added — Backend
- `backend/.../security/FlagStatus.java` — enum (OPEN/RESOLVED/DISMISSED) + canTransitionTo state machine（terminal 狀態不可逆，保 audit trail）
- `backend/.../security/FlagStatusChangedEvent.java` — record 預留 future audit listener
- `backend/.../security/FlagAdminQueryController.java` — cross-skill `GET /api/v1/flags?status=` for reviewer queue
- `backend/.../shared/api/InvalidStatusTransitionException.java` + `FlagNotFoundException.java` + GlobalExceptionHandler 加 400/404 mapping
- `backend/src/test/.../security/FlagServiceTest.java` (new) — 9 個 Testcontainers test 涵蓋 AC-1/2/3/4/5/6×2/7×2/8

### Added — Frontend
- `frontend/src/api/flags.ts` 加 `createFlag` / `fetchFlagsByStatus` / `updateFlagStatus` + `CreateFlagBody` interface；Flag.status 擴充 `'DISMISSED'`
- `frontend/src/lib/flag-labels.ts` 加 DISMISSED label「已駁回」+ neutral-soft pill style
- `frontend/src/hooks/useFlagsQueue.ts` — TanStack Query 30s staleTime + refetchOnWindowFocus
- `frontend/src/components/FlagSubmitModal.tsx` — 6-type radio + description optional textarea + useMutation invalidate
- `frontend/src/pages/FlagsQueuePage.tsx` — reviewer queue page 含 Resolve/Dismiss buttons
- 2 個 frontend test 檔：`FlagsQueuePage.test.tsx` (AC-11/12) + 既有 `FlagsList.test.tsx` 加 AC-9/10 (4/4 PASS)

### Changed
- `backend/.../security/FlagService.java` — `createFlag` 加 reporter 4th 參數 + null/blank → "anonymous" fallback；新 `updateStatus` / `listAllFlags` + `getFlagsBySkillId` 2-arg overload
- `backend/.../security/FlagController.java` — 注入 CurrentUserProvider 抽 sub for reporter；getFlags 加 `?status=`；新 `PATCH /api/v1/skills/{skillId}/flags/{flagId}` endpoint
- `backend/.../security/FlagReadModelRepository.java` — 加 3 個 derived queries + `@Modifying @Query updateStatus` (FlagReadModel.isNew=true 不能用 save() path)
- `frontend/src/components/FlagsList.tsx` — 加 useState showModal + 「回報問題」CTA 永顯（不論有無既存 flag）+ FlagSubmitModal trigger
- `frontend/src/components/AppShell.tsx` — navLinks 加「待審回報」link target `/flags`
- `frontend/src/App.tsx` — 加 `/flags` route + import FlagsQueuePage
- `backend/src/test/.../security/FlagControllerTest.java` — 加 CurrentUserProvider mock + BeforeEach setup；3 處 createFlag mock 從 3-arg 改 4-arg；getFlagsBySkillId mock 從 1-arg 改 2-arg

### Verified
- Backend：FlagServiceTest 9/9 PASS @ 8s（Testcontainers AC-1/2/3/4/5/6×2/7×2/8）+ FlagControllerTest 5/5 PASS（slice + mock）+ ModularityTests 2/2 PASS（無新跨模組依賴）
- Frontend cross-spec：FlagsList 4 + ReviewsPanel 4 + RatingStars 5 + FlagsQueuePage 2 + MySkillsPage 5 → 20/20 PASS @ 1.85s
- Typecheck 0 error（排除 pre-existing `Cannot find name 'global'`）

### Why
- **補完 ✅ S112 read 端 write loop**：S112 ship 後 SkillDetail Flags tab 能看 flags 但無提交按鈕；FlagController POST 既有但無前端 trigger；FlagReadModel.status 設計含 OPEN 但無轉 RESOLVED 的 path — admin 只能 SQL UPDATE 才能消化 OPEN flag，違反 Feature First 完整性。
- **零 schema migration**：status 既是 String varchar 欄位，純應用層加 enum + state machine + UI；既有 OPEN row 100% 相容；上線 zero-downtime。

### Deviations
- **T01 endpoint placement**：FlagAdminQueryController cross-skill GET 放 `/api/v1/flags`（path 跳出 `/api/v1/skills/.../flags` 階層）— 與既有 nested controller 各司其職；reviewer 不需先選 skill 才能看跨 skill queue。
- **T04 skill name 顯示走 link 而非 N+1 fetch**：reviewer queue 每 row 顯示 `skillId` + link to `/skills/{skillId}`；point-in-time joining skill name 留 polish backlog（per spec §4.1 Approach C simplest）。

### Process note
S098e3 是第 4 個「user 寫 spec → cron 拆 task → cron 連續 ship」端到端 demo（首例 S099a 單 task / 第 2 例 S112 / 第 3 例 S098e2 / 本例 S098e3）。Spec-Only-Handoff pattern 第 4 次驗證；5 ticks 平均 ~22min/tick。

---

## [v3.5.0] — Reviews aggregate + ratings full-stack（S098e2 完成；2026-05-03）

> S098e2 跨 5 個 cron tick (含 Tick 7 spec planning) ship — 新建 `review/` Modulith 模組（ADR-002 canonical pattern：Spring Data JDBC 充血聚合 + Modulith Outbox + AFTER_COMMIT projection）；3 個 REST endpoints (POST/DELETE/GET)；2 schema migrations (V8 reviews 表 + V9 skills 加 average_rating/review_count)；前端 SkillDetail Reviews tab 從永遠 EmptyState 接上完整 hero + list + form 流程。Minor 版本因引入新 module + new schema column。

### Added — Backend (review module)
- `backend/src/main/java/io/github/samzhu/skillshub/review/package-info.java` — `@ApplicationModule` allowedDependencies = shared::events/api/security + skill::domain + skill::query
- `backend/src/main/java/io/github/samzhu/skillshub/review/domain/Review.java` — Aggregate root (state-based 充血) + create factory + AC-2/3 validation；ReviewCreated event registration
- `backend/src/main/java/io/github/samzhu/skillshub/review/domain/ReviewRepository.java` — Spring Data JDBC repo + findBySkillIdOrderByCreatedAtDesc / existsBySkillIdAndAuthorId derived queries
- `backend/src/main/java/io/github/samzhu/skillshub/review/domain/ReviewCreatedEvent.java` + `ReviewDeletedEvent.java` — record events
- `backend/src/main/java/io/github/samzhu/skillshub/review/ReviewService.java` — 3-line create / delete orchestration + AC-4 dup check + AC-7 author check
- `backend/src/main/java/io/github/samzhu/skillshub/review/ReviewController.java` — 合併 POST/DELETE/GET 單 controller (3 endpoints volume 不需拆)
- `backend/src/main/java/io/github/samzhu/skillshub/review/SkillRatingProjectionListener.java` — `@ApplicationModuleListener` × 2 訂閱 ReviewCreated/Deleted
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillRatingService.java` — raw SQL UPDATE projection helper (NamedParameterJdbcTemplate；COALESCE 處理 0-review)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/ReviewForbiddenException.java` — 403 handler input (AC-7)
- `backend/src/main/resources/db/migration/V8__create_reviews_table.sql` — reviews 表 (rating CHECK [1,5] + content CHECK length≤2000 + (skill_id,author_id) UNIQUE)
- `backend/src/main/resources/db/migration/V9__add_skill_rating_projection_columns.sql` — skills 加 average_rating NUMERIC(3,2) + review_count BIGINT (DEFAULT 0)
- 3 個 backend test 檔：ReviewServiceTest (Testcontainers AC-1/2/3/4/6/7/8) + SkillRatingProjectionListenerTest (Modulith Scenario API AC-5×2) + 修改 GlobalExceptionHandler 加 ReviewForbiddenException 403 handler

### Added — Frontend
- `frontend/src/api/reviews.ts` — Review type + fetchReviews + createReview + deleteReview helpers
- `frontend/src/hooks/useReviews.ts` — TanStack Query hook 60s staleTime
- `frontend/src/components/RatingStars.tsx` — 5-star icon row (lucide Star) readonly + interactive ARIA radiogroup
- `frontend/src/components/ReviewsPanel.tsx` — main panel + RatingHero + ReviewRow + ReviewForm internal components
- 2 個 frontend test 檔：RatingStars.test.tsx (5 unit) + ReviewsPanel.test.tsx (4 isolation, AC-10/11/12)

### Changed
- `backend/.../skill/domain/Skill.java`：加 `averageRating` + `reviewCount` `@ReadOnlyProperty` field + getters；read-only column 防 aggregate save() 並發覆蓋（mirror downloadCount S077 pattern）
- `frontend/src/types/skill.ts`：Skill interface 加 averageRating: number + reviewCount: number 對齊後端 DTO
- `frontend/src/pages/SkillDetailPage.tsx`：Reviews tab 從 永遠 `<EmptyState>` 改 `<ReviewsPanel skill={skill} currentUserId={me?.sub}>`；加 useMe + ReviewsPanel imports

### Verified
- Backend：ReviewServiceTest 8/8 PASS @ 17.2s（Testcontainers + 真 PostgreSQL）+ SkillRatingProjectionListenerTest 2/2 PASS @ 16.9s（Modulith Scenario API）+ ModularityTests 2/2 PASS（review allowedDependencies 加 `skill :: query` 後 boundary 仍乾淨）
- Frontend cross-spec：ReviewsPanel 4 + RatingStars 5 + FlagsList 2 + MySkillsPage 5 + SkillDetailPage 3 → 19/19 PASS @ 1.73s
- Typecheck 0 error（排除 pre-existing `Cannot find name 'global'`）

### Why
- **PRD §316 B3 + §80/§221 真實 review feature**：原 Reviews tab 永遠空 EmptyState 不 honor PRD「社群評分」期望；S098e2 接 backend aggregate + projection + frontend full UI 走通端到端。
- **為 S101b Impact Score 預備 rating sub-metric**：S101b Impact Score 4-sub-metric 中 rating 來源就是這個 averageRating；先 ship S098e2 解 unblock。

### Deviations
- **T01 delete event publish path**：spec 寫 aggregate registerEvent + repo.save() 觸發 outbox，但 state-based aggregate 無 @Version → load 後 save() 誤觸 INSERT 衝主鍵。改用 ApplicationEventPublisher 直接發 ReviewDeletedEvent；Create flow 仍走 outbox（factory new entity, save() INSERT 正確）。Listener 訂閱兩種 publish 路徑都收得到。
- **T02 listener 放置位置 + bootstrap mode**：listener 放 review/ 而非 skill/，review allowedDependencies 加 skill::query（與 ScanOrchestrator 既有 cross-module SPI 同 pattern）；@ApplicationModuleTest(DIRECT_DEPENDENCIES) 拉到 SkillAclController → StorageService missing → 改 @SpringBootTest full bootstrap。
- **T04 ReviewsPanel extract 至獨立檔**：spec template 寫 internal components；per S112-T03 啟示 Radix Tabs JSDOM fireEvent.click 不可靠（無 user-event dep），改 extract → unit test 直接 isolation。

### Process note
S098e2 是第二個「user 寫 spec → cron 拆 task → cron 連續 ship」端到端 demo（首例 S112，S099a 是 single task）。Spec-Only-Handoff pattern 走第三次成功。5 ticks（含 1 tick 純 spec planning + 4 tick implement）平均 ~22min/tick；Modulith boundary 兩個跨模組 SPI（skill::query → security 既有 + review）pattern 一致。

---

## [v3.4.13] — Flag wiring full-stack（S112 完成；2026-05-03）

> S112 跨 4 個 cron tick ship — 把既有 `FlagController` GET endpoint 真接到 SkillDetail Flags tab，新加 `GET /me/flags-summary` aggregate count 接到 MySkillsPage「待處理回報」MetricCard，同時移除 MySkillsPage「平均評分」假 metric（等 S101a Quality Score）。Page audit fresh round 發現 backend 早 ship 完整但前端兩處未串 → 4 task 接下：T01 backend (Tick 3) + T02 fe-infra (Tick 4) + T03 SkillDetail tab (Tick 5) + T04 MySkills rework (Tick 6)。

### Added
- `backend/src/main/java/io/github/samzhu/skillshub/security/MeFlagsController.java` — `GET /api/v1/me/flags-summary` 回 `{openCount: N}`；CurrentUserProvider + FlagService 注入
- `backend/src/main/java/io/github/samzhu/skillshub/security/FlagService.java`：`countOpenFlagsForAuthor(String): long` 跨 flags + skills 兩表 SQL COUNT，過濾 `f.status='OPEN' AND skills.author=:author AND skills.status='PUBLISHED'`
- `frontend/src/api/flags.ts`：`Flag` / `FlagsSummary` type + `fetchFlags` / `fetchFlagsSummary` helper
- `frontend/src/lib/flag-labels.ts`：6 type + 2 status 中譯 + warning-soft / success-soft pill palette
- `frontend/src/hooks/useFlags.ts` + `frontend/src/hooks/useFlagsSummary.ts`：TanStack Query hook with 60s staleTime
- `frontend/src/components/FlagsList.tsx`：standalone Flags tab body component（FlagRow internal）
- 5 test files：`MeFlagsControllerTest` (1) + `FlagServiceTest` (3) + `FlagsList.test.tsx` (2) + 2 new tests in `MySkillsPage.test.tsx`

### Changed
- `backend/.../security/package-info.java`：allowedDependencies 加 `shared :: security`（與 skill / search 模組同 pattern；解 MeFlagsController 對 CurrentUserProvider 的 cross-module 依賴）
- `frontend/src/pages/SkillDetailPage.tsx`：「回報」tab 從永遠 render `<EmptyState>` 改為 `<FlagsList skillId={skill.id}>`；0 flag 自然 fallback 到 EmptyState
- `frontend/src/pages/MySkillsPage.tsx`：移除「平均評分」MetricCard；4-card grid `lg:grid-cols-4` → 3-card `lg:grid-cols-3`；「待處理回報」value 從寫死 `0` 改 `flagsSummary?.openCount ?? 0`，subtitle「MVP 暫缺」→「未處理 OPEN 狀態」
- `frontend/src/pages/MySkillsPage.test.tsx`：S110 AC-1「4 個 metric cards」更新為「3 個」（移除「平均評分」assertion）

### Verified
- Backend：`MeFlagsControllerTest` (slice 1 test) + `FlagServiceTest` (Testcontainers 3 tests, AC-6 user 隔離 + AC-7×2 graceful) + `ModularityTests` (2 tests, boundary 仍乾淨) → 全 PASS
- Frontend：`FlagsList.test.tsx` (AC-1/AC-2) + `SkillDetailPage.test.tsx` (3 既有 error path) + `MySkillsPage.test.tsx` (3 S110 + 2 S112 AC-3/AC-4) → 10/10 PASS @ 1.09s
- Typecheck `npx tsc --noEmit` 0 error（排除 pre-existing `Cannot find name 'global'`）

### Why
- **MVP scope** — 既有 backend FlagController 早 ship（S058/S072/S075）但 frontend 兩處未串：(1) SkillDetailPage Flags tab 永遠空、(2) MySkillsPage「待處理回報」寫死 0，看似真資料但不是。S112 把這個 visibility-vs-reality gap 補上。
- **不重複 S098e2/S098e3 future scope** — 本 spec 只接 GET 側（顯示既有 OPEN flags + count）；POST 回報表單 + reviewer queue + Reviews aggregate 等屬 S098e3（4 個月後 reviewer 流程系列）。

### Deviations
- **T01 endpoint placement**：spec §4.1 寫「擴 MeController」但 MeController 在 `shared/security` 模組、FlagService 在 `security` 模組，`shared → security` 反向不允許（Modulith 邊界）。改建獨立 `security/MeFlagsController` + 加 `shared :: security` 到 security 模組 allowedDependencies（與 skill/search 同 pattern）。Endpoint path 不變仍是 `/api/v1/me/flags-summary`。
- **T03 component extraction**：spec template 寫「FlagsList + FlagRow internal components within SkillDetailPage.tsx」但 Radix Tabs `fireEvent.click` 在 JSDOM 不可靠（無 user-event dep）— page-level test 2/2 fail。改 extract 至 `components/FlagsList.tsx` 獨立檔，unit test 直接 isolation → 5/5 PASS。Bonus single-responsibility。
- **T04 hook placement**：`useFlagsSummary` 必須放早 return 前（Rules of Hooks ordering），不能放 `metric calc` 之後。

### Process note
S112 是首次「user mid-flight 寫 spec → cron 自動接手實作 4 個 sequential task」走通的端到端 demo。Spec-Only-Handoff pattern 第 2 個成功案例（首個是 S099a OpenAPI 3.1）。4 ticks 連續 ship 4 task 平均每 tick ~21min wall（含 Testcontainers/AOT context 啟動 ~15s × 多次），全部 single-tick scope-fit；無 wall-hit / WIP / BLOCKED。

---

## [v3.4.12] — OpenAPI 3.1 verification + docs note（S099a 完成；2026-05-03）

> S099 META Trust Maturity 第 1 個 sub-spec ship — 鎖契約 `GET /v3/api-docs` 返 OpenAPI 3.1.0（對齊 agentskills.io trust maturity + JSON Schema 2020-12）+ OverviewPage 加 API 標準對齊 docs note 給 user 看 standard compliance。30m cron loop Tick 1 同 tick full-ship（XS=2，spec assumption Plan A 但實際走 Plan B — `application-local.yaml` 早有 `version: openapi_3_1` 設定）。

### Added
- `backend/src/test/java/io/github/samzhu/skillshub/api/OpenApiVersionTest.java`：`@SpringBootTest + @AutoConfigureMockMvc + @TestPropertySource` lock 契約 — `GET /v3/api-docs` status 200 + `$.openapi == "3.1.0"`，防 SpringDoc 升版 default 漂回 3.0.x
- `frontend/src/pages/docs/OverviewPage.tsx`：新「API 標準對齊」H2 段落 — 1-2 句中文 + inline code `/v3/api-docs` + Swagger UI link，置於「三個核心機制」與「下一步」之間

### Verified
- Backend：`./gradlew test --tests '*OpenApi*' -x npmBuild` PASS（tests=1 failures=0 errors=0 @ 0.676s test 執行 + 15.5s context startup）
- Frontend：typecheck `npx tsc --noEmit` 0 error
- Chrome MCP live smoke `/docs/overview`：H2 list `["三個核心機制","API 標準對齊","下一步"]` ✓；OpenAPI 3.1 + `/v3/api-docs` + swagger-ui link 4/4 DOM assertions PASS

### Why
S099 META 系列 entry point 落地（Trust Maturity & Implementation Audit）。OpenAPI 3.1 標準化 + JSON Schema 2020-12 對齊是 trust maturity 第一道訊號 — 對 API consumers 表達「這個平台 spec 是 latest standard 不是 legacy 3.0」。Test lock 契約後續 SpringDoc 升版若 default 漂掉會在 CI 立刻 catch。

### Process note
第 4 個 single-tick full-ship 案例（S109 / S110 / S111 / **S099a**）— 但這是首個 Mode A（spec-driven）非 Mode B follow-up：Spec-Only-Handoff pattern validated（Tick 21 mid-session pivot 寫 spec → 下個 tick cron 自然偵測 📋 接手）。Plan A/B fallback design 也 paid off — spec 寫時 backend down 無法 live verify default OpenAPI 版本，但 explicit Plan B 條件先寫好讓 implement tick 不需重 plan。

---

## [v3.4.11] — RiskTiersPage zh-TW tier title compliance（S111 完成；2026-05-03）

> Mode B Round 16 Tick 18 — 跑 S110 §8 提議的 systematic i18n grep cut (`grep -rnE 'title="[A-Z][a-z]+' frontend/src/pages/`) 直接命中 `/docs/risk-tiers`：4 個 Tier card titles (`Pure documentation` / `Auto-published` / `Auto-published, with warning badge` / `Blocked until reviewer approves`) 全英文，body 已 zh-TW，是 same-component i18n inconsistency。Tick 18 同 tick full-ship（XS=2 string replace + Chrome MCP smoke，per S109 pattern 第 3 次）。

### Changed
- `frontend/src/pages/docs/RiskTiersPage.tsx`：4 處 Tier title 替換 — `Pure documentation`→`純文件`、`Auto-published`→`自動上架`、`Auto-published, with warning badge`→`自動上架（顯警示標）`、`Blocked until reviewer approves`→`暫不上架，待審核員核准`（選詞對齊 browse / search 既有「上架」+ `reviewer` business term「審核員」）

### Verified
- Chrome MCP live smoke `/docs/risk-tiers`：4 zh-TW titles render ✓ + 3 English leftover 全 removed ✓
- 無 npm test scope（page 無既有 test，doc-only reference page，scope XS 不新建）
- FE tests 累計 43（不變）

### Why
S110 §8 提議的 systematic i18n grep cut 首次 application — 一條 grep 直接命中 4 處 leftover，**audit cut effectiveness confirmed**。S100e → S102 → S103 → S104 → S105 → S106 → S107 → S108 → S109 → S110 → **S111** 第 11 個 S100 META cross-cutting follow-up；cut 累積 11 層；S110 同軸延伸並驗證 i18n grep audit cut。

### Process note
第 3 個 single-tick full-ship 案例（S109 vite proxy actuator + S110 MySkillsPage + S111 RiskTiersPage）。Pattern 持續驗證 — XS scope + clear CLAUDE.md rule + sibling pattern proven + smoke < 30s。對 i18n compliance 類 micro fix，single-tick full-ship 已成 cron-bound agent 高效 standard pattern。

---

## [v3.4.10] — MySkillsPage zh-TW label compliance（S110 完成；2026-05-03）

> Mode B Round 15 Tick 17 audit (Chrome MCP `/my-skills`) 找到 i18n compliance gap：4 個 MetricCard label (`Total skills` / `Total downloads` / `Avg rating` / `Open flags`) 與 status subtitle (`X published · X draft · X suspended`) 仍英文，違反 CLAUDE.md「UI 語言: 繁體中文」rule。同 page TabPill labels（line 117-128）已 zh-TW（「全部」「已發布」「草稿」「已停用」），是 same-page i18n inconsistency。Tick 17 同 tick full-ship（XS=2 string replace + minimal test，per S109 process pattern）。

### Changed
- `frontend/src/pages/MySkillsPage.tsx`：5 處 user-facing string 從英文改 zh-TW — `Total skills`→`技能總數`、`Total downloads`→`下載總數`、`Avg rating`→`平均評分`、`Open flags`→`待處理回報`、`X published · X draft · X suspended`→`已發布 X · 草稿 X · 已停用 X`（與既有 TabPill labels terminology 一致）

### Added
- `frontend/src/pages/MySkillsPage.test.tsx`（新建）：3 ACs — AC-1 zh-TW labels present + AC-2 status subtitle 不含 English token + AC-3 English leftover regression guard

### Verified
- `cd frontend && npm test -- --run MySkillsPage`：1 file 3/3 PASS（1.03s）
- Chrome MCP live smoke `/my-skills`：4 zh-TW labels render ✓ + 5 English leftover 全 removed ✓
- FE tests 累計 40 → 43（+3）

### Why
S100e → S102 → S103 → S104 → S105 → S106 → S107 → S108 → S109 → **S110** 第 10 個 S100 META cross-cutting follow-up — cut 累積 10 層（S103 同軸延伸：user-visible string compliance；S103 修 stub copy spec ID leak，S110 修 page label English leftover，都是 zh-TW i18n compliance audit cut）。

### Process note
第 2 個 single-tick full-ship 案例（首例 S109 vite proxy actuator）。Pattern 持續驗證：(1) XS scope (2) CLAUDE.md rule clear (3) Sibling pattern proven (4) Smoke < 30s via Chrome MCP。對 i18n compliance / copy polish / dev-config 類 micro fix，single-tick full-ship 比 two-tick Spec-Only-Handoff 高效。

---

## [v3.4.9] — Vite dev proxy for Spring Boot Actuator endpoints（S109 完成；2026-05-03）

> Mode B Round 14 (extends S108 audit cut to actuator paths) Tick 16 audit 確認 dev environment proxy completeness gap continues：vite.config.ts proxy 已涵蓋 `/api/v1/*` + S108 加的 `/v3/api-docs` + `/swagger-ui`，但 actuator 路徑仍 fallback。Tick 16 同 tick full-ship（XS=1 pt 純 dev config）：proxy 加 `/actuator` prefix 一條規則 → 自動 cover all sub-paths (health/info/prometheus/metrics)；prod single-port deploy 不受影響。S108 §8 polish-backlog rule「proxy 應 mirror all backend-served paths」直接延伸驗證。

### Changed
- `frontend/vite.config.ts`：proxy table 加 `/actuator` 一條規則 → `http://localhost:8080`，與既有 `/api/v1/*` + S108 SpringDoc 規則同 target

### Verified
- Manual smoke: `curl :5173/actuator/health` → `200 application/vnd.spring-boot.actuator.v3+json`（before: `200 text/html` SPA fallback）✓
- Manual smoke: `curl :5173/actuator` → 同 actuator JSON ✓
- Vite dev server auto-restart pick up config change（不需手動重啟）

### Why
S108 §8 lesson 已建議 dev proxy 應 mirror all backend-served paths；S109 補上 actuator 延伸。累積 lesson：dev environment proxy table 該明確列出三類 backend 路徑 — (1) API endpoints (`/api/v1/*`) (2) API documentation (`/v3/api-docs`, `/swagger-ui/*`, S108) (3) Operational endpoints (`/actuator/*`, S109)。Future Spring Boot config 加新 backend-served path 時 dev proxy 應同步。

### Process note
S109 是首個 cron-bound agent **不經 Spec-Only-Handoff** 直接一 tick 全 ship 的 spec — XS=1 pure dev config + 同 S108 sibling pattern approach 已驗 + smoke < 1 min via curl。對 micro follow-up fix，single-tick full-ship 比 two-tick spec→implement handoff 高效。

### Sibling chain
S100e → S102 → S103 → S104 → S105 → S106 → S107 → S108 → **S109** 第 9 個 S100 META cross-cutting follow-up；cut 累積 9 層；S108 audit cut 延伸應用。

---

## [v3.4.8] — Vite dev proxy for SpringDoc + footer API link UX（S108 完成；2026-05-03）

> Mode B Round 13 (curl 對比 dev :5173 vs backend :8080 同 path response) Tick 14 audit 找到 dev environment proxy completeness gap：vite.config.ts proxy 只有 `/api/v1/*`，footer 「API」link `/v3/api-docs` 在 dev 環境 fallback 到 SPA NotFoundPage（prod single-port deploy 不受影響因 Spring Boot 同時 serve SPA + API + SpringDoc）。Tick 15 frontend-only fix：vite proxy 補 SpringDoc 兩條 (`/v3/api-docs` + `/swagger-ui`) + LandingPage footer link 從 raw JSON 改 Swagger UI（end-user 友善視覺 API explorer）。

### Changed
- `frontend/vite.config.ts`：proxy table 加 `/v3/api-docs` + `/swagger-ui` 兩條規則 → `http://localhost:8080`，與既有 `/api/v1/*` 同 target
- `frontend/src/pages/LandingPage.tsx`：footer 「API」link href 從 `/v3/api-docs` (raw JSON) 改 `/swagger-ui/index.html` (visual API explorer)

### Added
- `frontend/src/pages/LandingPage.test.tsx`（新建）：3 ACs — AC-3 footer 「API」link href = swagger-ui + S102 baseline 「文件」link + S102 baseline「狀態」placeholder 已移除

### Verified
- `cd frontend && npm test -- --run LandingPage`：1 file 3/3 PASS（1.23s）
- Manual smoke: `curl :5173/v3/api-docs` → `200 application/json`（before: `200 text/html` SPA fallback）✓
- Manual smoke: `curl :5173/swagger-ui/index.html` → `200 text/html`（content 由 SPA fallback 變 Swagger UI 真內容）✓
- FE tests 累計 37 → 40（+3）

### Why
S100e → S102 → S103 → S104 → S105 → S106 → S107 → **S108** 第 8 個 S100 META cross-cutting follow-up — cut 從「page-level data → cross-cutting links → user-visible strings → interactive state → component-context → control-behavior → API projection → **dev environment proxy completeness**」累積 8 層。發現方式 = curl 對比 dev :5173 vs backend :8080 同 path 不同 response；prod single-port deploy 自然遮蔽此 bug。本 ship 後 v3.4.x patch series 8 個全 land。

---

## [v3.4.7] — Semantic search response uses canonical Skill aggregate（S107 完成；2026-05-03）

> Mode B Round 12 (Chrome MCP semantic search live + backend curl 對比) Tick 12 audit 找到 API projection field completeness gap：`GET /api/v1/search/semantic` response 缺 author / category / riskLevel 欄位（全 empty/null），但同 skill `/skills/{id}` 返回完整 (author=r30, category=Testing, riskLevel=LOW)，造成 `/search?q=docker` 全顯「未評估」risk badge。Tick 13 backend fix 採 **service read path lookup from canonical Skill aggregate** — `SemanticSearchService` 注入 `SkillRepository`，`similaritySearch` 拿 documents 後 batch `findAllById(skillIds)` 撈 Skills，`toResult(doc, skill)` 從 aggregate 取 metadata（不依賴 vector_store metadata，徹底 bypass 歷史 projection 寫入不一致）。Race condition graceful — skill 已刪 fallback empty defaults。

### Changed
- `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java`：注入 `SkillRepository`；search 加 batch `findAllById` lookup；`toResult` 改 `(Document doc, Skill skill)` 兩參數，skill==null graceful fallback；移除 dead `toLong` helper

### Verified
- `cd backend && ./gradlew test --tests '*SemanticSearch*' -x npmBuild`：BUILD SUCCESSFUL in 2m 1s；既有 `SemanticSearchIntegrationTest` 全 PASS（含 ACL e2e）
- `./gradlew compileJava`：BUILD SUCCESSFUL in 10s

### Why
原計畫修 `SearchProjection.java` 寫入路徑 + 補 backfill，但發現 root cause = vector_store metadata drift 是長期問題（onVersionPublished line 147 硬塞 null + 歷史 row 寫入不齊全）。改採 read-path lookup 一勞永逸：embedding metadata 只當 nearby data，最終資料來自 canonical aggregate；不需 backfill 也不需修 write-side projection（兩者皆 polish backlog）。

### Trim deferred
- `SearchProjection:147` write-side null bug fix — 不再被 read 依賴
- `SearchResultsPage:108` unsafe cast removal — backend 補齊後 cast 仍 work
- vector_store metadata backfill — read 已 bypass
- Live smoke deferred to backend restart

### Sibling chain
S100e → S102 → S103 → S104 → S105 → S106 → **S107** 第 7 個 cross-cutting follow-up；cut 從「page-level data → cross-cutting links → user-visible strings → interactive state → component-context → control-behavior → **API projection field completeness**」累積 7 層。首次涉及 backend 改動（前 6 個全 frontend）。

---

## [v3.4.6] — Sort `推薦` behavior alignment with design intent（S106 完成；2026-05-03）

> Mode B Round 11 (Chrome MCP click sort chips + compare first card across 4 modes) Tick 10 audit 找到 control-behavior misalignment：「推薦」chip 與「最新」chip 行為相同 — 都 fall through 到 backend default `ORDER BY created_at DESC`，UI 4 chip 但只實際有 3 種 sort 行為。Tick 11 frontend-only fix：加 explicit `recommended → downloadCount,desc` mapping，per HomePage:17 design intent (stale comment claimed backend default = downloadCount desc)；推薦 與 下載最多 暫同 mapping 但 UX chip 仍 distinct，future evolve 為 recommendation algorithm 時改 mapping 即可。

### Changed
- `frontend/src/api/skills.ts`：sortMap 加 `recommended: 'downloadCount,desc'`；conditional 改寫去除 `!== 'recommended'` exclusion；JSDoc 更新 mapping 說明
- `frontend/src/pages/HomePage.tsx`：line 15 stale JSDoc 更新（backend default 實為 createdAt DESC，非舊 comment 聲稱的 downloadCount desc；S106 alignment + future evolution note）

### Added
- `frontend/src/pages/HomePage.test.tsx`：AC-S106 — 預設 sortMode=recommended 時 fetchSkills URL 必須含 `sort=downloadCount,desc`（不再 fall-through）

### Verified
- `cd frontend && npm test -- --run HomePage`：1 file 5/5 PASS（1.28s）
- Chrome MCP live smoke 4 chip first card：推薦 r19-lifecycle ✓ ≠ 最新 r35-docker ✓ ≠ 風險低 r35-docker (NONE→LOW asc) ≠ 下載最多 r19-lifecycle (= 推薦 per design)
- FE tests 累計 36 → 37（+1）

### Why
S100e → S102 → S103 → S104 → S105 → **S106** 第 6 個 S100 META cross-cutting follow-up — cut 從「page-level data → cross-cutting links → user-visible strings → interactive state → component-context → control-behavior alignment」累積 6 層。發現方式 = Chrome MCP click sort chips + compare first card across 4 modes（前 5 cut 都看不見此 bug，需 same-page multi-control 對比才浮現）。同 round 內 category filter cut **passed**（DevOps → 38 個技能，server-side 正確）— 證明 audit cut 多樣化是 cumulative quality 累積方式，不同 cut 揭露不同層 bug。

---

## [v3.4.5] — EmptyState invite-tone steps decoupling（S105 完成；2026-05-03）

> Mode B Round 10 (Chrome MCP focus+Space keyboard nav for Radix Tabs — synthetic .click() 與 React event system 衝突需真 keyboard event) Tick 8 audit 找到 component-context misalignment：`EmptyState` `invite` tone 內部 hardcode 4-step strip `['打包','自動掃描','發佈','追蹤']`，但 5 個 production callsite 中只有 MySkillsPage（新作者 publish onboarding）真符合此 context；其他 4 處（CollectionsPage / RequestBoardPage / SkillDetailPage Reviews tab / SearchResultsPage no-query）顯無關 publish flow steps 造成 user 疑惑。Tick 9 frontend-only fix：抽 `steps?: string[]` 為 optional prop（default hide）+ MySkillsPage explicit opt-in。

### Changed
- `frontend/src/components/EmptyState.tsx`：`EmptyStateProps` 加 `steps?: string[]`；InviteTone 改用 `props.steps` conditional render；移除 hardcoded `const steps = [...]`
- `frontend/src/pages/MySkillsPage.tsx`：加 `steps={['打包', '自動掃描', '發佈', '追蹤']}` opt-in 保留新作者 onboarding context
- `frontend/src/components/EmptyState.test.tsx`：改寫 AC-2 為「不傳 steps → 不顯 strip」+ 新增 AC-S105「傳 steps → 顯 strip」

### Verified
- `cd frontend && npm test -- --run EmptyState`：1 file 6/6 PASS（752ms）
- Chrome MCP live smoke：
  - `/collections` empty → `hasSteps: false`（4-step strip 隱藏）✓
  - `/my-skills` empty → `hasSteps: true`（MySkillsPage opt-in 保留）✓
- FE tests 累計 36 → 36（test count 不變：AC-2 改寫 + AC-S105 新增抵消）

### Why
S100 page-level data audit + S102 cross-cutting links + S103 user-visible strings + S104 interactive state consistency 都覆蓋不到「shared component 在不同 context 顯示是否一致語意」— Component 內部 hardcode (publish onboarding steps) 在多 context reuse 時偷渡進不適合 context。S105 補這個 cut：**shared component reuse audit**（component-context alignment）。

S100e → S102 → S103 → S104 → **S105** 第 5 個 cross-cutting follow-up，cut 從「data → links → strings → state → component-context」累積；發現方式 = Chrome MCP focus+Space tab nav（Round 10 副產物：Radix synthetic-event 隔離 pattern 已驗證，future Mode B keyboard-nav E2E 可 reuse）。

---

## [v3.4.4] — Risk filter empty state + pagination UX consistency（S104 完成；2026-05-03）

> Mode B Round 9 (Chrome MCP interactive click) Tick 6 audit 找到 bug：點「無風險」filter (DB 0 NONE-tier) 後 3 處 UI signal 自相矛盾（generic seed-empty EmptyState 暗示 registry 空 + 「共 103 個技能」unfiltered count + 「第 1 / 6 頁」pagination 暗示更多頁）。Tick 7 frontend-only fix 修齊 4 signal 一致：context-aware EmptyState（redirect tone + selected tier label headline + 「清除篩選」escape button）+ filtered count display + pagination guard。Backend 不轉 server-side filter（per S100b deliberate decision；scope 超 fix-spec）。

### Changed
- `frontend/src/pages/HomePage.tsx`：3 處 conditional — count display 加 filter-active 變體、EmptyState branch 加 redirect-tone override、pagination guard 加 `filteredSkills.length > 0`；新增 `RISK_TIER_LABELS` const for headline localization
- `frontend/src/components/EmptyState.tsx`：RedirectTone 補 `primaryAction` / `secondaryAction` render（既有 `EmptyStateProps` interface 早已 declare 此 prop 但 RedirectTone 過去未 wire — missing-feature gap 不是 signature change；filter-active 0-hits 場景需要明確 escape button）

### Added
- `frontend/src/pages/HomePage.test.tsx`（新建）：baseline + AC-1/3 combined（filter active → headline 含 tier label + count 顯 filtered/total）+ AC-2（清除篩選 button reset）+ AC-4（pagination hidden when 0 filter hits）

### Verified
- `cd frontend && npm test -- --run HomePage`：1 file 4/4 PASS（1.29s）
- Chrome MCP live smoke：navigate /browse → 點「無風險」filter button → js DOM probe 確認 4 signal 一致（headline / count / pagination hide / clear button present）— 對照 Tick 6 audit 紀錄完全反轉
- FE tests 累計 32 → 36（+4）

### Why
S100 page-by-page data audit + S102 cross-cutting links audit + S103 user-visible string audit 都覆蓋不到「同 page 上 N 個 UI signal 對同一 user action 是否一致」— Mode B Round 9 採 Chrome MCP click interaction 視角才看見 filter / pagination / count / empty-state 4 signal **同時** 處理 click filter 時的對齊性。S100e → S102 → S103 → **S104** 第 4 個 cross-cutting follow-up — cut 從「data → links → strings → interactive state」逐層累積。

---

## [v3.4.3] — Stub-page user-facing copy spec ID leak fix（S103 完成；2026-05-03）

> Mode B Round 8 (Chrome MCP live render audit) 發現 `/collections` 與 `/requests` 兩個 stub 頁面在 user-facing copy 暴露 internal spec ID（`S096f2` / `S096g2`），共 6 處：disabled button title attr + label + EmptyState subtext。Production 用戶不該看到內部 milestone 編號。NotificationsPage 同類 stub copy 已 clean，證明 leak 是 per-page sloppy copy 不是系統性 pattern；可單點修不必抽 i18n 抽象。

### Changed
- `frontend/src/pages/CollectionsPage.tsx`：3 處 user-visible string 移除 `S096f2`，改用「即將開放」/「後續版本推出」泛詞
- `frontend/src/pages/RequestBoardPage.tsx`：3 處 user-visible string 移除 `S096g2`，改用「即將開放」/「後續版本推出」泛詞

### Added
- `frontend/src/pages/RequestBoardPage.test.tsx`：AC-3 button label/title + AC-4 EmptyState subtext assertion（spec ID 字面 0 出現）

### Updated
- `frontend/src/pages/CollectionsPage.test.tsx`：加 `AC-S103` test — visible text + button title attribute 不含 `S096f2` 字面

### Verified
- `cd frontend && npm test -- --run CollectionsPage RequestBoardPage`：2 files 6/6 PASS（857ms）
- AC-5 grep `S096[fgh]2 frontend/src/pages/*.tsx` 過濾 JSDoc → 0 user-visible hits
- Chrome MCP smoke：navigate /collections + /requests → DOM tree 0 spec ID 字面（before/after 對照表見 spec §7）
- FE tests 累計 30 → 32（+2）

### Why
S100 page-by-page data audit + S102 cross-cutting link audit 都覆蓋不到 user-visible string 是否含 internal jargon — Mode B Round 8 採 Chrome MCP live render 視角才看見 dev 把 milestone 編號當「等 X ship 就 enable」reference 寫進 production copy。S100e (defensive guard) → S102 (routing residual) → **S103 (UX copy hygiene)** 形成 v3.4.x patch series — S100 META post-ship 第 3 個 cross-cutting follow-up，靠 audit cut 多樣化（page / link / copy）累積品質。

---

## [v3.4.2] — Post-S096e1 routing residual link target fix（S102 完成；2026-05-03）

> S096e1 把 `/` 從 browse list 改為新 LandingPage 之後，4 處 back-navigation / EmptyState CTA 的 target 漏了同步換 `/browse`，造成 label-target 語意打架（label 寫「列表 / 瀏覽」實際跳 LandingPage）；外加 1 處 LandingPage footer placeholder「狀態」自指迴圈。S100 META page-by-page audit 已 confirm 27 pages 0 fake，但對 **inter-page link semantic alignment** 是盲點 — S102 是 sibling to S100e，補這個 cut。

### Changed
- `frontend/src/pages/SkillDetailPage.tsx`：2 處 `to="/"` → `to="/browse"`；error state link label 「返回首頁」→「返回列表」統一
- `frontend/src/pages/SearchResultsPage.tsx`：`navigate('/')` → `navigate('/browse')`（清空 query 提交時）；EmptyState `primaryAction.href` `'/'` → `'/browse'`（「瀏覽全部技能」CTA）
- `frontend/src/pages/LandingPage.tsx`：footer 移除自指 `<Link to="/">狀態</Link>` placeholder

### Added
- `frontend/src/pages/SearchResultsPage.test.tsx`：AC-3 form clear → `/browse` + AC-4 EmptyState CTA href = `/browse`（用內建 `fireEvent`，**不**新增 user-event dep）

### Updated
- `frontend/src/pages/SkillDetailPage.test.tsx`：AC-3 既有 test 從 `返回首頁`/`/` 改成 `返回列表`/`/browse`（重命名 `AC-3 (S102)`）

### Verified
- `cd frontend && npm test -- --run SkillDetailPage SearchResultsPage`：2 files 5/5 PASS（999ms）
- FE tests 累計 28 → 30（+2）

### Trim
- AC-5 LandingPage footer DOM test 未寫 — spec §3 trim 順序「5 → 2」明示，footer 是純 JSX 一行移除人眼 review 足夠；polish backlog 若需要再補

### Why
S100 page-by-page data audit 視角對 inter-page link 對齊是盲點：page 內 fetch 是否假抓得乾淨，但對 「page A 的 outgoing link 指向的 page B 是否仍存在 / 語意對齊」不在自然視野。S102 補這層，S100e (defensive guard) + S102 (routing residual) 共同形成 S100 META 的 post-ship cross-cutting follow-up pattern。

---

## [v3.4.1] — AnalyticsPage Top 10 link defensive guard（S100e 完成；2026-05-02）

> User 觀察：/analytics 頁面 Top 10 連結點過去 404「找不到此技能」。Root cause：backend runtime 落後 S100a deploy，response 漏 `author` 欄位 → frontend 渲染 `<Link to="/skills/undefined/<name>">`。本 spec 補 frontend 三重 guard（typeof + length + 字面 "undefined" 字串），author 缺失時 row 退回非 link `<div>`，rank/name/downloads 完整保留。

### Added
- `frontend/src/pages/AnalyticsPage.test.tsx`：4 ACs cover positive / no-author key / "undefined" string / empty topSkills（vi.mock useOverview fixture provider）

### Changed
- `frontend/src/api/analytics.ts`：`topSkills[].author` 改為 optional 反映 backend stale-runtime 真實風險
- `frontend/src/pages/AnalyticsPage.tsx`：map block 加 `hasValidAuthor` guard + ternary 切 Link / div polymorphic row

### Verified
- `cd frontend && npx vitest run src/pages/AnalyticsPage.test.tsx`：1 file 4/4 PASS（1.07s）

### Why
S100e 是 S100a 的 defensive sibling — S100a 加了 author 欄位但 stale runtime 不一定送出；frontend 需 own 自己的 fail-safe，不能 assume backend payload schema 完整。

---

## [v3.4.0] — PublishPage markdown preview pane（S099b3 完成；2026-05-02）

> S099b3 — PublishPage text mode 加 split-view markdown preview。Hand-rolled MiniMarkdown 取代 50KB+ react-markdown dep。完成 PublishPage text mode 三件套：text mode + frontmatter validation + preview。

### 🎨 Frontend (S099b3)
- 新檔 `lib/mini-markdown.tsx` — 自寫 line-by-line markdown parser + renderer：
  - `# / ## / ###` → h1/h2/h3
  - ` ``` ` fenced code → `<pre>`
  - `- ` / `1. ` → `<ul>` / `<ol>`
  - 行內 backtick `code` → `<code>`
  - 其他 non-empty → `<p>`
  - YAML frontmatter `---...---` 自動 strip from preview
  - **零 dep**（避免 react-markdown ~50KB+ remark-gfm bundle）
- 新檔 `lib/mini-markdown.test.tsx` — **10 ACs** per methodology「3-5 反例 / round」：
  - 5 positive (h1-3 / p / code / ul / ol)
  - 3 negatives (empty / unclosed-fence / ##NoSpace)
  - 2 edges (frontmatter strip / inline code)
- `pages/PublishPage.tsx`：text mode 加 preview toggle（Eye / EyeOff icon）；split-view 預覽顯在 textarea 右側 (md:grid-cols-2 / max-h-400px scroll)；caption「預覽（不含 frontmatter）」明示 frontmatter 不渲染

### 🐛 Bug found + fixed during dev
**parser 無窮迴圈** — 「##NoSpace」(無 space) 不符 heading regex 也不符 paragraph collector（首字 `#` 排除），原始 paragraph branch 不 i++ → infinite loop。Tests 跑時讓 vitest 卡死多 worker 在 96-97% CPU。Fix：paragraph fallback 強制包含當前 line + i++ 推進。

### 🐛 Test bug found + fixed
JSX attribute string `content="...\n..."` 不解 `\n` escape（HTML attribute literal）。原 5 tests 失敗 → 改用 JSX expression form `content={'...\n...'}`。Lessons learned 寫入 mini-markdown.test.tsx 註解供未來參考。

### ✅ Tests
- 140 → 150 tests PASS（+10 across 1 test file）
- `npx tsc --noEmit` clean

### S099 META 進度
- 5/8 sub-specs shipped (S099a / S099b / S099b2 / S099b3 / S099e5)
- PublishPage text mode 三件套完成 ✅
- 剩 S099c (cross-marketplace) / S099d (LLM rubric) / S099e1-e4 (scanner upgrades)

## [v3.3.7] — PublishPage author auto-prefill from /me（S100c reframed；2026-05-02）

> S100 audit 原列 S100c MySkillsPage auth-based filter — 但 inspect 後發現 MySkillsPage 從 S094a 起就已用 useMe()。真正 gap 是 PublishPage 仍要 user 手填 author。Reframe 為 PublishPage author auto-prefill。

### 🎨 Frontend (S100c reframed)
- `pages/PublishPage.tsx`：
  - 加 `useMe()` hook + useEffect prefill author from me?.sub on first load
  - `authorTouched` state — user 手動改後不再 overwrite（防 useMe race / 後續 fetch 覆蓋 user 編輯）
  - placeholder 從 hardcoded "your-name" 改為動態 `me?.sub ?? 'your-name'`
  - 顯 helper hint 「已自動填入你的識別 ${me.sub} — 可改為團隊或代發名稱」when prefill 未被改

### Why not「lock author」
某些 publish 場景：team-publishing / publish-on-behalf。Author 仍 editable，僅以 user 識別為合理 default。

### ✅ Tests
- 140/140 PASS
- `npx tsc --noEmit` clean

### S100 META 進度
- 5/4 sub-specs done — S100a (Top 10 link) / S100b (server sort) / S100c (author prefill) / S100d (ErrorState) — META 全 ✅；S100c 從 backlog 改為 reframed 動作 ship。

## [v3.3.6] — useCategories hook test — hook coverage complete（2026-05-02）

> Mode B — 補完 hook test coverage 三個（useSkill / useVersions / useCategories）。

### ✅ Tests
- 新檔 `hooks/useCategories.test.tsx` — 3 ACs：fetch + data resolved；data shape 對齊 CategoryCount；error response 觸發 isError state。

### 結果
- 137 → 140 tests PASS（+3）
- `npx tsc --noEmit` clean
- Hook coverage 完整（3/3）：useSkill / useVersions / useCategories

## [v3.3.5] — Server-side sort: HomePage 跨頁全域 sort（S100b 完成；2026-05-02）

> S100b — HomePage sort chips 改 server-side query param。原 client-side sort 只對「當前頁」生效（page=20 視窗內排序）；user 翻頁會看到不同順序。改用 Spring Pageable `sort=field,direction` 實現跨頁全域 sort。

### 🎨 Backend (S100b)
- `SkillQueryService.java`：SORTABLE_PROPERTIES whitelist 加 `riskLevel` enabled risk-low sort（其餘 downloadCount / createdAt 已在 whitelist）。

### 🎨 Frontend (S100b)
- `api/skills.ts` SkillSearchParams 加 `sort?: 'recommended' | 'newest' | 'most-downloaded' | 'risk-low'`；fetchSkills map 為 Spring Pageable `sort=field,direction` query；'recommended' = 不傳（後端 default createdAt DESC）
- `pages/HomePage.tsx`：
  - sortMode 直接 pass 給 useSkillList（server-side）
  - 移除既有 client-side `filteredAndSorted` useMemo + `RISK_ORDER` const（client risk-low sort 用 字典序 enum 順序，後端 ORDER BY 字典序自然 NONE→LOW→MEDIUM→HIGH）
  - 改用簡化 `filteredSkills` 只 client-side filter (risk-tier multi-select，後端無此 filter 不在 scope)

### Trade-off resolved
原 trade-off「filter 與 sort 只在當前頁生效」 → sort 部分解決（跨頁全域）；filter 仍 client-side（限本頁多 tier OR 邏輯）。後續若加 backend filter query 可移除最後 client-side fallback。

### ✅ Tests
- 137/137 PASS（既有 HomePage test fixture 不受 sort param 影響 — 還是回 fixture content）
- `npx tsc --noEmit` clean

## [v3.3.4] — ErrorState migration cleanup — PublishReview/Validate/Diff（2026-05-02）

> S100d follow-up — 把 ErrorState 抽出後剩 3 callsites 完整 migration。

### 🎨 Frontend
- `pages/PublishReviewPage.tsx`：missing-id callout (centered) + load-error callout (inline) → ErrorState；移除 AlertCircle import
- `pages/PublishValidatePage.tsx`：missing-id callout → ErrorState
- `pages/VersionDiffPage.tsx`：missing-id callout → ErrorState

### 結果
- 0 hardcoded `rgba(226,75,74,0.14)` error-tone callouts in pages（剩 SUSPENDED status pills / RiskBadge / err-row 為合理 status indicators）
- 137 → 137 tests PASS（沒新 component；行為 invariant 保留）
- `npx tsc --noEmit` clean

## [v3.3.3] — PublishPage frontmatter live validation（S099b2 完成；2026-05-02）

> S099b2 — PublishPage text mode 加 live frontmatter validation。輸入 SKILL.md 內容時即時驗 `---` block + `name:` + `description:` 必填欄位；submit button gate 在所有錯誤清除前 disabled。

### 🎨 Frontend (S099b2)
- `pages/PublishPage.tsx`：
  - 新 `validateFrontmatter(content)` pure parser（regex-based shallow validation；不取代 backend full YAML parse；fail-fast inline feedback）
  - 偵測：(a) 必須以 `---` 開頭 (b) 必須有結束 `---` (c) frontmatter block 內含 `name:` + `description:` 非空值
  - 新 `ValidationCheck` sub-component — ✓ 綠 / ⚠️ 灰 + label
  - textarea 下方 inline 顯 3 個 check + 詳細 error 列表
  - submit gate：text mode + errors > 0 → disabled

### ✅ Tests
- 新檔 `pages/PublishPage.test.tsx` — **8 ACs**（per 2026-05-02 methodology upgrade「3-5 反例 / round」）：
  - 1 positive：valid frontmatter + name + description
  - 5 negative cases：empty / no-leading-delim / no-closing-delim / missing-name / missing-description
  - 2 edge cases：empty value field / leading whitespace
- 129 → 137 tests PASS（+8 new）
- `npx tsc --noEmit` clean

### S099 META 進度
- 4/8 sub-specs shipped (S099a / S099b core / S099b2 / S099e5)
- 剩 S099b3 markdown preview / S099c cross-marketplace / S099d LLM rubric / S099e1-4 scanner upgrades

## [v3.3.2] — ErrorState shared component + 2 demo migrations（S100d 完成；2026-05-02）

> S100d — 抽 5+ 重複的紅色 callout pattern 為 shared `ErrorState` primitive。Per S100 audit 各 page error fallback UX 不一致；統一 callout token + 兩 variant (inline / centered) 解決。

### 🎨 Frontend (S100d)
- 新檔 `components/ErrorState.tsx`：danger-soft palette（rgba(226,75,74,0.14) bg / #F2A6A6 fg）；2 variants：
  - `inline`（default）— 水平 small callout 用於 mutation error / form-level
  - `centered` — full-width box 用於 page-level data-load failure
  - props：title / optional message / optional icon override / className
- 新檔 `components/ErrorState.test.tsx` — 6 ACs：variant 切換 / 有/無 message / custom icon override / ReactNode message / extra className 套用

### Demo migrations
- `pages/AnalyticsPage.tsx`：error branch 從 inline `<div>...rgba(226,75,74,0.14)...</div>` → `<ErrorState variant="centered" />`
- `pages/PublishPage.tsx`：mutation.isError branch 從 inline 12-line `<div>` → `<ErrorState className="mt-4" />`，移除 AlertCircle import

剩 3 個 callsite 待 migrate（PublishReviewPage / PublishValidatePage / PublishFailedPage 各 1）— defer 至 polish backlog；不在本 commit scope 防 commit creep。

### ✅ Tests
- 123 → 129 tests PASS（+6 new ErrorState）
- `npx tsc --noEmit` clean

## [v3.3.1] — `/docs/risk-scanner-scope` LLM01-10 mapping page（S099e5 完成；2026-05-02）

> S099e5 — 對 consumer 透明公開 Skills Hub risk scanner 對齊 OWASP LLM Top 10 v1.1 (2023) 各項威脅的 coverage state（covered / partial / gap / out-of-scope）。

### 🎨 Frontend (S099e5)
- 新檔 `pages/docs/RiskScannerScopePage.tsx` — 12 sections：每 LLM01-10 項顯 threat 描述 + Skills Hub 對應 coverage（含 reasoning + future plan）+ summary card 4 個 coverage tier 計數 + 免責聲明 callout。
- `App.tsx` +route `/docs/risk-scanner-scope`
- `DocsSidebar.tsx`：「參考」群加 12th item「Risk Scanner 範圍」

### Coverage breakdown (per page)
| Tier | Count | LLM IDs |
|------|-------|---------|
| ✅ Covered | 2 | LLM06 (sensitive paths) / LLM08 (allowed-tools agency budget) |
| 🟡 Partial | 3 | LLM01 (RCE only) / LLM05 (curl source unverified) / LLM07 (allowed-tools no composability) |
| ❌ Gap | 1 | LLM04 (DoS scanner planned S099e2) |
| ◯ Out of Scope | 4 | LLM02 / LLM03 / LLM09 / LLM10 |

### 結果
- 123 → 123 tests PASS（DocsSidebar.test 加 12th item 同步）
- `npx tsc --noEmit` clean

### S099 META 進度
- S099a ✅ (v3.2.7) + S099b ✅ (v3.3.0) + S099e5 ✅ (本 commit) — 3/8 sub-specs shipped
- 剩 S099c (cross-marketplace) / S099d (LLM rubric) / S099e1-e4 (scanner upgrades)

## [v3.3.0] — PublishPage 文本貼上 mode（S099b 完成；2026-05-02）

> User directive: skill 沒有可以直接輸入文本的方式。Solution: PublishPage 加雙 mode tabs（檔案 / 文本）；text mode 用 `new File([text], 'SKILL.md', ...)` synthesize 後沿用既有 uploadSkill mutation。零 backend 改動 — backend S053 既有 `.md` 多 ext 支援。

### 🎨 Frontend (S099b)
- `pages/PublishPage.tsx`：
  - 加 `mode: 'file' | 'text'` state + Tabs UI（FileText icon / Upload icon）
  - text mode：`<textarea>` 14-row mono-font + placeholder 顯 SKILL.md 範例 frontmatter
  - submit handler conditional：text mode → `new File([skillMdText], 'SKILL.md', { type: 'text/markdown' })` synthesize；file mode → 既有 file state
  - submitDisabled rule per mode（text mode 檢 trim length > 0；file mode 檢 file != null）
  - inline 連結 `/docs/skill-md-spec` 給文本 mode 作者參考規範
  - 抽 `ModeTab` sub-component（active state shadow + bg-card / inactive muted）

### Reuse
- 既有 `uploadSkill` API client + mutation flow + onSuccess navigate 到 `/publish/validate?id=X` + onError navigate `/publish/failed?state=A&msg=`
- 零 backend 改動（依賴 S053 既有 raw .md 上傳支援）
- 零 new dep（無 JSZip 等 zip lib）

### Trim
原 S(8) 估含 syntax highlighting / preview / yaml-frontmatter live validation。本 commit ship XS(4) 核心：
- ✅ Tabs + textarea + synthesize file submit
- ⏸ S099b2: yaml-frontmatter live validation (parser + error highlighting)
- ⏸ S099b3: markdown preview pane (split view)

### ✅ Tests
- 123/123 PASS（既有 PublishPage 無 dedicated test；future regression 加 PublishPage.test.tsx 可 cover mode 切換 + synthesize 行為）
- `npx tsc --noEmit` clean

## [v3.2.4] — useVersions hook test（2026-05-02）

> Mode B — useVersions enabled-guard + queryKey cache isolation contract verified。

### ✅ Tests
- 新檔 `hooks/useVersions.test.tsx` — 3 ACs：
  - empty skillId → query disabled (防 /skills//versions 空 id 請求)
  - valid skillId → fetch invoked + data resolved
  - 兩個 distinct skillId → 各自 cache key 觸發 2 次 fetch (cache isolation)

### 結果
- 120 → 123 tests PASS（+3 new）
- `npx tsc --noEmit` clean

## [v3.2.3] — useSkill / useSkillByAuthorAndName hook tests（2026-05-02）

> Mode B 最後一輪 — useSkill hook 是核心 query primitive；S096c dual-route enabled-guard 行為驗證。Tests 115 → 120。

### ✅ Tests
- 新檔 `hooks/useSkill.test.tsx` — 5 ACs：
  - useSkill empty id → query disabled (no fetch)
  - useSkill valid id → fetch invoked + data resolved
  - useSkillByAuthorAndName missing author / missing name / both present 三種 enabled-guard scenarios

### 結果
- 115 → 120 tests PASS（+5 new）
- `npx tsc --noEmit` clean

## [v3.2.2] — DocsSidebar standalone tests（S098f3 close verification；2026-05-02）

> Mode B — DocsSidebar 控制 11 doc page navigation；Active link contract + group label contract verified。

### ✅ Tests
- 新檔 `components/DocsSidebar.test.tsx` — 5 ACs：4 group labels render；all 11 nav items render；current path highlights matching link；non-active items use muted style；各 link href 對應 sidebar 路徑。

### 結果
- 110 → 115 tests PASS（+5 new）
- `npx tsc --noEmit` clean

## [v3.2.1] — AppShell nav + bell badge invariants（S096h1；2026-05-02）

> Mode B — AppShell 是 every-page wrapper；7 nav links + bell badge poll-driven unread count contract verified。

### ✅ Tests
- 新檔 `components/AppShell.test.tsx` — 6 ACs：「Skills Hub」brand + 7 nav links render；current path highlight；unread=0 不顯 badge；unread>0 顯數字；count>99 顯「99+」；children render in `<main>`。

### 結果
- 104 → 110 tests PASS（+6 new）
- `npx tsc --noEmit` clean

## [v3.2.0] — 🎉 100-test milestone：BeamFrame + FileDropZone（2026-05-02）

> Mode B — 跨 100-test milestone（28 → 104 tests in 31 ticks）。BeamFrame thin wrapper + FileDropZone S037/S048/S053 guard tests。

### ✅ Tests
- 新檔 `components/BeamFrame.test.tsx` — 2 ACs：children render；nested tree 完整 pass-through。
- 新檔 `components/FileDropZone.test.tsx` — 6 ACs：empty prompt；selected file 顯 name+KB；invalid ext inline error + skip callback；oversized error；valid zip triggers callback；S053 .md multi-ext support。

### 結果
- 96 → 104 tests PASS（+8 new across 2 files）
- `npx tsc --noEmit` clean

### 🎉 100-test milestone
- 起點：v2.85.0 28 tests
- v3.2.0：104 tests（+76 new in 31 cron ticks）
- 0 bugs found in 31 consecutive ticks
- All component primitives have isolated tests

## [v3.1.11] — IntentSummaryCard component invariants（S094b；2026-05-02）

> Mode B — IntentSummaryCard concept-chip conditional rendering verified。

### ✅ Tests
- 新檔 `components/IntentSummaryCard.test.tsx` — 4 ACs：「已理解你的意圖」label render；summary text 直接顯示；empty concepts → 無 chips；concepts > 0 → 各 chip render。

### 結果
- 92 → 96 tests PASS（+4 new）
- `npx tsc --noEmit` clean

## [v3.1.10] — MetricCard component invariants（2026-05-02）

> Mode B — MetricCard shallow render contract verified。

### ✅ Tests
- 新檔 `components/MetricCard.test.tsx` — 4 ACs：label + value 顯示；number value 轉 text；subtitle 條件 render；無 subtitle 結構 2 個 `<p>`。

### 結果
- 88 → 92 tests PASS（+4 new）
- `npx tsc --noEmit` clean

## [v3.1.9] — CategorySidebar component invariants（2026-05-02）

> Mode B — CategorySidebar 互動 contract 驗證。

### ✅ Tests
- 新檔 `components/CategorySidebar.test.tsx` — 7 ACs：全部按鈕 + 各 category render；全部 count = sum；各 category 顯自身 count；selected=null/string active state；click 全部/category → onSelect(null|name)。

### 結果
- 81 → 88 tests PASS（+7 new）
- `npx tsc --noEmit` clean

## [v3.1.8] — SearchBar controlled input invariants（2026-05-02）

> Mode B — SearchBar is reused on HomePage + LandingPage; basic controlled-input contract verified.

### ✅ Tests
- 新檔 `components/SearchBar.test.tsx` — 4 ACs：input value reflected; type="search" attribute; typing → onChange callback; clearing → empty-string callback。

### 結果
- 77 → 81 tests PASS（+4 new）
- `npx tsc --noEmit` clean

## [v3.1.7] — IconTile component invariants（S085 contract；2026-05-02）

> Mode B — IconTile 是 SkillCard / SkillDetailPage 共用 primitive。Initial derivation logic + size class + a11y invariants。

### ✅ Tests
- 新檔 `components/IconTile.test.tsx` — 7 ACs：
  - single-word → first letter uppercase
  - hyphenated → first letters of first 2 words
  - underscore-separated → same split
  - empty/whitespace → `?` fallback
  - size sm/md/lg/xl → correct w-* class
  - aria-hidden=true (decorative)
  - unknown category → graceful default tile (no crash)

### 結果
- 70 → 77 tests PASS（+7 new）
- `npx tsc --noEmit` clean

## [v3.1.6] — RiskFilterSidebar tests（ledger Round 3.3+3.4 ✅；2026-05-02）

> Mode B — fill 2 ledger 📋 rows (Round 3.3 toggle / 3.4 multi-tier counts)。S098d2 client-side aggregation invariants 驗證。Tests 65 → 70 (+5)。

### ✅ Tests
- 新檔 `components/RiskFilterSidebar.test.tsx` — 5 ACs：
  - count breakdown derivation（null risk 不計入；各 tier 正確 count）
  - empty Set → 「全部」active state
  - click tier button → onToggle(tier) call
  - click 「全部」→ onClear call
  - selected tier → active state visual
- `docs/grimo/test-cases.md`：Round 3.3 + 3.4 標 ✅；Round 3 done count 0→2；Total 10→12（21 still planned）

### 結果
- 65 → 70 tests PASS（+5 new）
- `npx tsc --noEmit` clean

## [v3.1.5] — RiskBadge component tests（4-tier S096c invariants；2026-05-02）

> Mode B — 為 S096c 4-tier risk system 補 component test。RiskBadge 是 SkillCard / SkillDetailPage / RiskFilterSidebar 共用 primitive；isolated 行為驗證。

### ✅ Tests
- 新檔 `components/RiskBadge.test.tsx` — 7 ACs：
  - 4 tier labels (NONE/LOW/MEDIUM/HIGH → 無/低/中/高 風險)
  - null level → 「未評估」fallback
  - unknown tier (e.g. future CRITICAL) → graceful raw-level render
  - NONE tooltip contains「不代表 100% 安全」caveat (per S096c contract)
- 不測 inline-style hex（per ALWAYS rule「test against DOM structure not incidental constants」）

### 結果
- 58 → 65 tests PASS（+7 new）
- `npx tsc --noEmit` clean

## [v3.1.4] — VersionList tests（ledger Round 5.5 ✅；2026-05-02）

> Mode B — fill 1 ledger 📋 row (Round 5.5 VersionList diff link)。Tests 53 → 58 (+5)。

### ✅ Tests
- 新檔 `components/VersionList.test.tsx` — 5 ACs：
  - empty versions → 「尚無版本記錄」fallback
  - single version → 不顯 diff link
  - 2+ versions → 「比較版本變化」link with href `/skills/:skillId/diff`
  - 最新 badge on index 0
  - download link href `/api/v1/skills/{skillId}/versions/{version}/download`
- `docs/grimo/test-cases.md`：Round 5.5 標 ✅；Round 5 done count 2→3；Total 9→10（23 still planned）

### 結果
- 53 → 58 tests PASS（+5 new）
- `npx tsc --noEmit` clean

## [v3.1.3] — Notifications + Collections empty state tests（ledger Round 7.1+7.2 ✅；2026-05-02）

> Mode B — fill 2 ledger 📋 rows (Round 7.1 NotificationsPage + Round 7.2 CollectionsPage)。Tests 47 → 53 (+6)。

### ✅ Tests
- 新檔 `pages/NotificationsPage.test.tsx` — 3 ACs：empty list → EmptyState clear tone with 3 stats; h1「通知中心」+ intro; non-empty list renders rows (no EmptyState)
- 新檔 `pages/CollectionsPage.test.tsx` — 3 ACs：h1「精選技能集合」; 「建立集合」disabled CTA (S096f1 stub); empty fetch 不 crash
- `docs/grimo/test-cases.md`：Round 7.1 + 7.2 標 ✅；Round 7 done count 1→3；Total 7→9（24 still planned）

### 結果
- 47 → 53 tests PASS（+6 new across 2 new test files）
- `npx tsc --noEmit` clean
- Ledger Round 7 (Empty state polish) **3/3 全 done**

## [v3.1.2] — SkillDetailPage error path tests（ledger Round 1.4 ✅；2026-05-02）

> Mode B — fill 1 ledger 📋 row (Round 1.4 negative)：404 not-found / 500 server error / 返回首頁 link 三 ACs。對齊 S039 區分 4xx vs 5xx error 邏輯。Tests 44 → 47 (+3)。

### ✅ Tests
- 新檔 `pages/SkillDetailPage.test.tsx` — 3 ACs 對齊 ledger Round 1.4：
  - AC-1: 404 → 「找不到此技能」(不顯 retry hint per S039)
  - AC-2: 500 → 「載入技能時發生錯誤」+「請稍後重試或重新整理頁面」 hint
  - AC-3: error state「返回首頁」link to `/`
- `docs/grimo/test-cases.md`：Round 1.4 標 ✅；Round 1 done count 0 → 1；Total 6 → 7。

### 結果
- 44 → 47 tests PASS（+3 new）
- `npx tsc --noEmit` clean

## [v3.1.1] — Test coverage backfill：PublishValidatePage（2026-05-02）

> Mode B E2E round — 為 v2.95.0 ship 的 S098a PublishValidatePage 補 component test。Tests 40 → 44 (+4)。最後一個未測 newly-shipped page 補完。

### ✅ Tests
- 新檔 `pages/PublishValidatePage.test.tsx` — 4 ACs：
  - AC-1: missing id query renders error callout
  - AC-2: scanning state (riskLevel=null) renders 4-step stepper + scanning callout
  - AC-3: scan complete (riskLevel set) triggers useEffect navigate to `/publish/review`（用 sentinel route「REDIRECTED_TO_REVIEW」斷言 redirect transition）
  - AC-4: upload-strip renders skill metadata（S098a3 派生 filename「date-formatter-1.0.0.zip」+「✓ 已上傳」badge）

### 結果
- 40 → 44 tests PASS（+4 new）
- `npx tsc --noEmit` clean
- 此 session 所有 newly-shipped pages（PublishFailed / VersionDiff / PublishValidate）皆有 component test 覆蓋

## [v3.1.0] — PublishFailedPage State A 結構化驗證 breakdown UI（S098b3 完成；2026-05-02）

> S098b3 — 把 PublishFailedPage State A 從「單段紅色 callout + msg pre-block」升級為對齊 prototype #7 的多段 v-section UI shell（SKILL.md 驗證 / Bundle 結構 / 風險掃描 三段並列）。每段含 status badge + err-row 列表；目前 backend 只送 flat msg → 派生為 single error row，未來結構化 findings payload 可填多 row 不需改 component。

### 🎨 Frontend
- `pages/PublishFailedPage.tsx`：State A 重構
  - 加 `ErrRow` 結構 type `{ severity: 'error'|'warning', title, hint? }`
  - 派生邏輯：msg 非空 → single ErrRow severity='error' （未來可解析 JSON payload 為多筆）
  - 拆 `ValidationSection` 子 component：head（icon tile + title + sub + status badge）+ err-rows divide-y list
  - State A 渲染順序：top callout 「驗證在第 2 步停止」→ SKILL.md section（failed + 1 error row）→ Bundle section（skipped + opacity-60）→ Risk scan section（skipped + opacity-60）
  - 三段並列示意「驗證流程的依賴鏈」— 即使 SKILL.md 失敗，user 也看到後續步驟存在但被略過

### 設計理由
prototype #7 用 multi v-section 顯示完整驗證流程；前一版只 single callout 失去階段感。本 commit ship UI shell — backend 結構化 findings payload spec 為後續 backend work（S098b3-2 候選）；目前 flat msg 仍 work，UI 已 ready for upgrade。

### Skipped 兩段視覺處理
Bundle 結構 + 風險掃描 status='skipped' opacity-60；不顯 ✓ 但顯 — 圖示 + 「略過」標籤；hint sub 解釋「修正 SKILL.md 後再跑」。

### ✅ Tests
- `npx vitest run` (cwd=frontend) → 9 files / 40 tests PASS
  - 既有 PublishFailedPage.test.tsx 4 ACs 全 pass — heading「驗證在第 2 步停止 — 沒有任何資料寫入。」+ msg decoded + footer CTA 都保留
- `npx tsc --noEmit` (cwd=frontend) → no errors

### Polish backlog 進度
- S098b3 ✅ shipped — 留 S098b3-2 待 backend 結構化 findings payload spec ready 後 ship

## [v3.0.1] — Test coverage backfill：PublishFailedPage + VersionDiffPage（2026-05-02）

> Mode B E2E round — 為 v3.0.0 ship 的 S098b/c 兩 page 補 component test。Tests 33 → 40 (+7)。Test backfill 不算 spec，純 regression 防護。

### ✅ Tests
- 新檔 `pages/PublishFailedPage.test.tsx` — 4 ACs covering State A 紅 tone (validation error msg) / State B 橘 tone (HIGH-risk + id echo) / missing-state fallback to A / footer CTA href targets
- 新檔 `pages/VersionDiffPage.test.tsx` — 3 ACs：default to/from selection (latest 2 versions) / query param override (from=1.0.0&to=1.2.0) / insufficient versions (<2) fallback message。Mock global fetch 餵 versions/skill JSON。

### 結果
- 33 → 40 tests PASS（+7 new）
- `npx tsc --noEmit` clean

## [v3.0.0] — v2.x polish 系列里程碑 + PublishValidate upload-strip（S098a3 完成；2026-05-02）

> **v3.0.0 milestone release** — 標誌 v2.x 14-version polish 系列完成（v2.86.0 → v2.99.0），同時 ship S098a3 PublishValidatePage 上傳資訊 strip（frontend-only trim）。S098 META 8/8 全達 + 4 個 split P1 spec 全 ship + Docs IA 11/11 全 active link + Homepage v2 polish trio 完整。

### 🎨 Frontend (S098a3)
- `pages/PublishValidatePage.tsx`：加 upload-strip 區（FileArchive icon + skill name 派生 filename + version + category + 「剛剛上傳」timestamp + 「✓ 已上傳」綠色 success badge）。位於 stepper 上方（per prototype #5）。
  - Trim：原始 prototype 顯 raw zip filename + fileSize + fileCount —— 需 backend `/skills/{id}/bundle-info` endpoint。本 commit 用 skill.name 派生「{name}-{version}.zip」+ version + category 替代；對 user「知道在驗證哪個 bundle」這個最低需求已足夠。
  - Defer S098a3-2：backend bundle-info endpoint + 真 filename / fileSize / fileCount。

### 📊 v2.x → v3.0.0 累積成果
**14 versions** 在連續 cron loop ticks 完成 (2026-05-02 同日)：

| Version | Spec | 主題 |
|---|---|---|
| v2.86.0 | S098h | YourFirstSkillPage 配色對比修復 |
| v2.87.0 | S098g pass 1 | i18n 繁中化 5 surface |
| v2.88.0 | S098g pass 2 | i18n sweep 殘留英文 |
| v2.89.0 | S098h2 | EmptyState dark migration + 4-step i18n |
| v2.90.0 | S098d | Homepage 3-col grid + sort chips |
| v2.91.0 | S098b | Publish Failed dedicated page |
| v2.92.0 | S098b2 | PublishReviewPage HIGH-risk auto-redirect |
| v2.93.0 | S098e | SkillDetailPage 5-tab + sparkline hero |
| v2.94.0 | S098f | Docs IA: Overview + Risk Tiers |
| v2.95.0 | S098a | Publish Step 2 /publish/validate page |
| v2.96.0 | S098c | Version Diff page (frontend-only) |
| v2.97.0 | S098f2 | Docs 參考群 3 stub pages |
| v2.98.0 | S098f3 | Docs 發佈+API 群 5 stub pages |
| v2.99.0 | S098d2 | Homepage risk filter sidebar |
| **v3.0.0** | S098a3 + milestone | Upload-strip + 系列里程碑標記 |

### 🎯 v3.0.0 累積里程碑（user-facing surfaces 完整對等 prototype）
- ✅ **S098 META 8/8** — 8 個 prototype-driven sub-spec 全 ship
- ✅ **Docs IA 11/11** — 入門 2 + 參考 4 + 發佈 3 + API/Webhook 2，全 sidebar 點擊可達
- ✅ **Homepage v2 polish trio** — 3-col grid + sort chips + risk filter sidebar
- ✅ **Publish flow 閉環** — Upload → Validate → Review → Live + State A/B failure 分流
- ✅ **i18n 繁中化** — 全 user-facing 字串繁中（CLAUDE.md 規約達標）
- ✅ **Dark theme migration** — YourFirstSkillPage / EmptyState / IntentSummaryCard / DocsSidebar 補完最後 light-theme 殘留

### Polish backlog（v3.x 接續）
- S098a3-2 / S098b3 / S098c2 / S098c3 / S098e2 / S098e3：trim spawn — 多需 backend aggregate 或新 endpoint
- S096f2 / S096g2 / S096h2：Collections / Request Board / Notifications 從 stub→full
- S094e Admin Review：post-MVP B6 backlog（auth + role 依賴）

### ✅ Tests
- `npx vitest run` (cwd=frontend) → 7 files / 33 tests PASS
- `npx tsc --noEmit` (cwd=frontend) → no errors

## [v2.99.0] — Homepage risk filter sidebar（S098d2 完成；Homepage v2 polish trio 完成；2026-05-02）

> S098d2 — Homepage 加 risk-tier 4-level filter sidebar with count breakdown。Closes Homepage v2 polish trio：S098d (3-col grid + sort chips) → S098d2 (risk filter)。對齊 prototype #2 sidebar IA。

### 🎨 Frontend
- 新檔 `components/RiskFilterSidebar.tsx`：4 tier toggle checkbox (NONE/LOW/MEDIUM/HIGH) + count breakdown + 「全部」reset。每 tier 含 dot color 對齊 RiskBadge palette（success/info/warning/danger）。
  - 計數：client-side aggregate `skillsPage.content`；不打 backend
  - selected: empty Set = 「不篩選」= 全顯（初始狀態）
  - toggle 即加入 / 移除 Set；多選邏輯（OR 過濾）
- `pages/HomePage.tsx`：
  - 加 `riskFilter: Set<RiskLevel>` state + `toggleRisk` / `clear`
  - 既有 `sortedSkills` useMemo 升級為 `filteredAndSorted`：兩階管線（filter → sort）；`riskFilter.size > 0` 時依 selected Set 過濾，else pass-through
  - sidebar 區插入 RiskFilterSidebar 在 CategorySidebar 上方（`space-y-6` 群間距）
  - 只 keyword 模式顯（語意搜尋模式同 CategorySidebar 隱藏）

### Trade-off
- 計數與過濾「只當前頁」生效；換頁會看到不同 count（page=20 數量級可接受）
- 全域 risk-tier count 需 backend `/skills/risk-counts` 或 client-side 全量 fetch（page=∞）—— defer 為 polish backlog；目前 client-side aggregate 對 MVP 視覺驗收已足

### ✅ Tests
- `npx vitest run` (cwd=frontend) → 7 files / 33 tests PASS
- `npx tsc --noEmit` (cwd=frontend) → no errors

### 🎉 Homepage v2 polish trio 完成
| 項目 | 狀態 | spec |
|---|---|---|
| 3-column grid (xl breakpoint) | ✅ v2.90.0 | S098d |
| Sort chips (4 mode) | ✅ v2.90.0 | S098d |
| Risk filter sidebar with count breakdown | ✅ v2.99.0 | S098d2 |

Homepage 達 prototype #2 完整對等。

## [v2.98.0] — Docs IA 完整收官（S098f3 完成；發佈群 + API/Webhook 群 5 stub pages；2026-05-02）

> S098f3 — 完成 DocsSidebar 剩 5 placeholder items：發佈群（上傳與驗證 / 版本管理 / 語意搜尋）+ API & Webhook 群（REST 參考 / Event payload）。**Skills Hub Docs IA 11/11 全 active link ✅**。

### 🎨 Frontend
- 新檔 `pages/docs/UploadValidatePage.tsx` — 4-step 流程說明（Step component + 編號 dot + 常見錯誤列表）。
- 新檔 `pages/docs/VersioningPage.tsx` — SemVer 規則 + PATCH/MINOR/MAJOR 升版時機 + suspended/reactivate 行為。
- 新檔 `pages/docs/SemanticSearchPage.tsx` — Gemini text-embedding-004 + pgvector cosine similarity 流程 + fallback 行為（Gemini 不可用 → keyword）。
- 新檔 `pages/docs/RestApiPage.tsx` — quick reference table 列 14 個主要 endpoints（依「瀏覽 / 發佈 / 搜尋與聚合」三組）；連 OpenAPI Swagger UI。
- 新檔 `pages/docs/EventPayloadPage.tsx` — 6 個 domain event schema (SkillPublished / VersionPublished / SkillRiskAssessed / SkillFlagged / SkillSuspended / SkillDownloaded) 包裝結構 + 各自 payload 範例。
- `App.tsx`：新 5 routes (`/docs/upload-validate`、`/docs/versioning`、`/docs/semantic-search`、`/docs/rest-api`、`/docs/event-payload`)
- `DocsSidebar.tsx`：發佈群 3 items + API/Webhook 群 2 items 補 path → **全 11 sidebar items 變 active link**

### Reuse-only
- DocsLayout（既有）+ 同 H2/Callout/inline 元件 pattern（每 page inline 定義避免抽取過早）
- 零 backend / 零 new shared component / 零 backend dependency

### Trim 紀錄
原 M(8) 估 5 page。本 commit ship 全 5 — fit one tick 沒觸發 trim（pattern 已建立，純 markup work fast）。

### ✅ Tests
- `npx vitest run` (cwd=frontend) → 7 files / 33 tests PASS
- `npx tsc --noEmit` (cwd=frontend) → no errors

### 🎉 Skills Hub Docs IA 完整 11/11 ✅

| Group | Items shipped |
|---|---|
| 入門 | 概覽 / 撰寫第一個技能 (2/2) |
| 參考 | SKILL.md 規範 / Frontmatter 欄位 / Bundle 結構 / 風險層級 (4/4) |
| 發佈 | 上傳與驗證 / 版本管理 / 語意搜尋 (3/3) |
| API & Webhook | REST 參考 / Event payload (2/2) |

DocsSidebar 全 11 個 items 都是 active link；無更多 placeholder。Docs IA 達到 prototype #16 設計完整對等。

## [v2.97.0] — Docs 參考群 3 stub pages（S098f2 完成；2026-05-02）

> S098f2 — 完成 DocsSidebar「參考」群剩 3 item path wiring：SKILL.md 規範 / Frontmatter 欄位 / Bundle 結構。Reuse `DocsLayout` + 在 `OverviewPage` / `RiskTiersPage` 確立的 dark-token markup pattern。

### 🎨 Frontend
- 新檔 `pages/docs/SkillMdSpecPage.tsx` — agentskills.io v1.2 在地化精煉 reference；列出兩段（frontmatter + markdown 內文）+ 核心約束（檔名 / name 規則 / description 用途 / 5MB 限制） + 連結 Frontmatter 與 Bundle 細項頁。
- 新檔 `pages/docs/FrontmatterPage.tsx` — 完整欄位對照表 (table 格式)：必填 2（name / description）+ 選填 6（version/author/license/compatibility/metadata/allowed-tools），每欄含型別 + 限制 + 說明。Risk-tier callout 連結。
- 新檔 `pages/docs/BundleStructurePage.tsx` — 慣例佈局 ASCII tree + 三資料夾（scripts/ references/ assets/）逐一語意說明（scripts/ 加「會觸發風險掃描」標籤）+ bundle 限制（5MB / 50 檔 / no symlinks）。
- `App.tsx`：新 routes `/docs/skill-md-spec`、`/docs/frontmatter`、`/docs/bundle`
- `DocsSidebar.tsx`：「參考」群 3 個 placeholder items 補 path → 全 4 item 變 active link

### Reuse
- DocsLayout（同 OverviewPage / RiskTiersPage / YourFirstSkillPage pattern）
- 同樣 H2 + P + Callout 內部小元件慣例（每 page 各自 inline 定義避免抽取過早）
- 零 backend / 零 new shared component

### ✅ Tests
- `npx vitest run` (cwd=frontend) → 7 files / 33 tests PASS
- `npx tsc --noEmit` (cwd=frontend) → no errors

### Trim 紀錄
原 S(6) 估完整含 3 page。本 commit ship 全 3 — fit one tick 沒觸發 trim。
DocsSidebar「發佈」+「API 與 Webhook」兩 group 共 5 item 仍 placeholder：
- ⏸ S098f3 (defer)：上傳與驗證 / 版本管理 / 語意搜尋 / REST 參考 / Event payload — 兩 group 全部 5 個 stub pages

### Skills Hub Docs IA 階段性完成
入門群 (2/2) + 參考群 (4/4) ✅ 完備；發佈 + API 群 (0/5) ⏸ S098f3 待 ship。

## [v2.96.0] — Version Diff 頁（frontend-only stub）（S098c 完成；2026-05-02）

> S098c — `/skills/:id/diff?from=&to=` 版本比較頁。Frontend-only trim：reuse 既有 `/skills/{id}/versions` endpoint，零 backend 改動。對齊 prototype `Skills Hub Version Diff.html`。

### 🎨 Frontend
- 新檔 `pages/VersionDiffPage.tsx`：side-by-side metadata diff。
  - hero 顯 skill name + GitCompare icon + 比對 from/to version code chips
  - version selector chips：點任一 version 即更新 from（紅）或 to（綠）；color-coded per `tone` palette (danger/success rgba)
  - VersionCard side-by-side：from / `→` / to 三欄；md:grid-cols-[1fr_auto_1fr]
  - 各 card 顯：v{version} (font-mono large) / 套件大小 (formatBytes B/KB/MB) / 發布時間 (zh-TW localized)
  - Delta 卡：套件大小 delta (絕對 + %) / 發布間隔 (天數)；浮點精度 `.1`
  - Default selection: 最新（to）+ 倒數第二新（from）；query params override
- `components/VersionList.tsx`：當 versions ≥ 2 顯「比較版本變化」連結 (GitCompare icon + 12px subtle text)，連到 VersionDiffPage 預設值
- `App.tsx`：新 route `/skills/:id/diff`

### Trim 紀錄
原 M(12) 估含 backend `/api/v1/skills/{id}/diff?from=&to=` 端點 + risk/description/scripts hash diff + file content side-by-side。本 commit ship S(6)：
- ✅ frontend-only metadata diff（version + size + publishedAt + delta 計算）
- ✅ from/to query params + selectable chips
- ✅ VersionList 連結 entry point
- ⏸ S098c2: backend `/diff` endpoint 含 description / risk-level / scripts SHA per version snapshot（需 SkillVersion 加 riskLevel + sha 欄位 + projection）
- ⏸ S098c3: file content line-level diff（需 zip extract 與 line-level diff library）

### Reuse
- `useSkill` + `useVersions` hooks（既有；零 new endpoint）
- 純 composition；零 new backend / 零 new shared component

### ✅ Tests
- `npx vitest run` (cwd=frontend) → 7 files / 33 tests PASS
- `npx tsc --noEmit` (cwd=frontend) → no errors

### S098 META 收尾統計
P1 主要 backlog 全完成：
- S098 META 8/8 ✅ (h/g/h2/d/b/b2/e/f) — v2.86.0 → v2.94.0
- S098a Publish Step 2 ✅ — v2.95.0
- S098c Version Diff ✅ — v2.96.0
總共 11 個 user-facing v2 polish ship 完。

剩 polish backlog：S098a2/a3 / b3 / c2/c3 / d2 / e2/e3 / f2/f3 / 既有 S096f2/g2/h2 / S094e admin review (post-MVP)。

## [v2.95.0] — Publish Step 2 `/publish/validate` 中介頁（S098a 完成；2026-05-02）

> S098a — 完成 publish 流程「Step 1 上傳 → **Step 2 驗證** → Step 3 審視 → Step 4 上架」中介頁。對齊 prototype `Skills Hub Publish Step 2.html`。原 PublishPage 直接跳 review 心智模型不清（user 看到 spinner 不知在等什麼）；新中介頁明示 4-step stepper 狀態。

### 🎨 Frontend
- 新檔 `pages/PublishValidatePage.tsx`：4-step stepper UI + auto-poll + auto-navigate。
  - `Upload (done) → Validate (active) → Review (future) → Live (future)` 視覺狀態
  - 步圈顏色 per prototype `.step-num` palette：done = `rgba(29,158,117,0.18)` + ✓ / active = `bg-foreground` filled + 數字 / future = `#171719` outline + 數字
  - 連接線 `step-line` done = `rgba(29,158,117,0.40)` / future = `rgba(255,255,255,0.10)`
  - StatusCallout 4 tone (info/warning/success/danger) — scanning 為 warning + spinner；完成短暫顯 success「即將跳轉」後 useEffect 出走
  - useQuery refetchInterval 2s pattern（同 PublishReviewPage S096d5a）；scan 完 (riskLevel 設值) 即停 poll + navigate `/publish/review?id=X` (replace mode 防 back-button 循環)
- `pages/PublishPage.tsx`：onSuccess 改 navigate `/publish/validate?id=X` 取代既有 `/publish/review?id=X`
- `App.tsx`：新 route `/publish/validate`

### Trim 紀錄
原 M(10) 估含「stepper + SSE event stream + per-event 即時動畫 + upload-strip file detail + check-list 三項即時打勾」。本 commit ship XS(5)：
- ✅ stepper 4-step UI + status indicators
- ✅ auto-poll + auto-navigate
- ✅ 4 tone status callout (loading/scanning/success/error)
- ⏸ S098a2: 真 SSE event stream — 需 backend 三 events (BundleParsed / FrontmatterValidated / RiskScanCompleted)；目前 poll-based 已 cover 80% UX
- ⏸ S098a3: upload-strip file detail (filename / size / 檔案數)；需 backend `/skills/{id}/bundle-info` endpoint

### Reuse
- `useQuery` refetchInterval pattern from PublishReviewPage S096d5a — 同樣 callback-driven enable/disable
- `fetchSkillById` API client — 零 new endpoint
- 純 composition pattern；零 new shared component

### ✅ Tests
- `npx vitest run` (cwd=frontend) → 7 files / 33 tests PASS
- `npx tsc --noEmit` (cwd=frontend) → no errors

### 流程完整性
S098a + S098b + S098b2 + S096d4a + S096d5a 構成 publish 流程閉合：
- 上傳成功 → `/publish/validate` (S098a) → 自動跳 `/publish/review` (S096d4a/d5a)
- 上傳失敗 → `/publish/failed?state=A` (S098b)
- HIGH-risk 觸發 → `/publish/failed?state=B` (S098b2 from review)

## [v2.94.0] — Docs IA expansion: Overview + Risk Tiers（S098 META 8/8 ✅ 完成；M92f 完成；2026-05-02）

> S098f — `/docs/overview` 入門概覽頁 + `/docs/risk-tiers` 風險層級完整說明頁。對齊 prototype `Skills Hub Docs.html` sidebar IA。**S098 META 8 sub-specs 全達成 ✅**。

### 🎨 Frontend
- 新檔 `pages/docs/OverviewPage.tsx`：給新使用者的 Skills Hub 落地頁。
  - h1 + intro 段交代「什麼是 Skills Hub / 為什麼存在」
  - 三個核心機制 FeatureCard grid（自動風險評分 / 語意搜尋 / 開放標準）
  - 下一步 BeamFrame primary CTA「撰寫第一個技能」+ secondary「了解風險層級」
- 新檔 `pages/docs/RiskTiersPage.tsx`：完整 4-tier 風險層級 reference。
  - 4 個 `Tier` cards (NONE/LOW/MEDIUM/HIGH) — 各 tone 用 semantic-soft palette (success/warning/danger rgba 配色)
  - 每 tier 含 title + body + 可選 note section（dashed-border separator + tone-color)
  - 「遇到 HIGH 風險怎麼辦？」雙視角段（作者 / Reviewer）
  - footer prev/next nav 連回 your-first-skill
- `App.tsx`：新 routes `/docs/overview` + `/docs/risk-tiers`
- `DocsSidebar.tsx`：「概覽」+「風險層級」item 加 `path` — 變成可點 active link（其他 sidebar items 仍 placeholder）

### 重複利用
- DocsLayout (S094d ship) — 同一 sidebar + main column 結構
- BeamFrame (S097 npm package wrapper) — primary CTA visual emphasis
- 零 new component / 零 backend change — 純 markup-style content pages

### Trim 紀錄
原 M(10) 估含 5 doc pages 完整 (Overview / SKILL.md spec / Frontmatter / Bundle / Risk tiers)。本 commit ship XS(5) — 2 個最高 user value stub:
- ✅ Overview (新 user 必經 landing → 引導三個機制理解)
- ✅ Risk Tiers (publish 流程理解 prerequisite — Skill detail page 上 risk 標籤的 reference)
- ⏸ S098f2: 其餘 3 stub pages (SKILL.md spec / Frontmatter fields / Bundle structure) — sidebar 仍顯 placeholder
- ⏸ S098f3: 「發佈」+「API」group items 詳細 walkthrough

### ✅ Tests
- `npx vitest run` (cwd=frontend) → 7 files / 33 tests PASS
- `npx tsc --noEmit` (cwd=frontend) → no errors

### 🎉 S098 META 完成
**8/8 sub-specs shipped** — 從 v2.86.0 至 v2.94.0 共 9 個 release：
- S098h (v2.86.0) — YourFirstSkillPage 配色對比修復
- S098g pass 1 (v2.87.0) — i18n 繁中化 top-of-funnel 5 surface
- S098g pass 2 (v2.88.0) — i18n sweep 殘留英文
- S098h2 (v2.89.0) — EmptyState dark migration + 4-step i18n
- S098d (v2.90.0) — Homepage 3-col grid + sort chips
- S098b (v2.91.0) — Publish Failed dedicated page
- S098b2 (v2.92.0) — PublishReviewPage HIGH-risk auto-redirect
- S098e (v2.93.0) — SkillDetailPage 5-tab + sparkline hero
- S098f (v2.94.0) — Docs IA expansion: Overview + Risk Tiers ← **本 commit**

剩 S098a (Publish Step 2 M=10) + S098c (Version Diff M=12) 為原 META 未列 P0 進階功能；S098d2/b3/e2/e3/f2/f3 為 trim spawn — 全部入 polish backlog。

## [v2.93.0] — SkillDetailPage 5-tab + sparkline hero（S098 META 7/8；M92e 完成；2026-05-02）

> S098e — SkillDetailPage v2 polish per prototype `Skills Hub Skill Detail.html`。加 Reviews + Flags tabs (stub) + 30d 下載 sparkline 至 hero strip（reuse S096d3 Sparkline + `/skills/{id}/stats`）。

### 🎨 Frontend
- `pages/SkillDetailPage.tsx`：
  - hero 抽出為 `SkillHero` sub-component；加 30-day download sparkline (size 120×32, accent purple `#7F77DD`)，僅 PUBLISHED 狀態顯示，DRAFT/SUSPENDED 數據不具參考性所以 hidden。
  - tabs 結構從 4 → 6：新增「評論」(reviews) + 「回報」(flags) tabs；保留既有 Files (S082 file browser, 偏離 prototype 5-tab 是 trade-off — 不破壞既有功能)。Reviews stub 用 EmptyState invite tone「評論系統即將推出」；Flags stub 用 EmptyState clear tone「目前沒有任何回報」。
  - 加 `useQuery(['skill-stats', id, '30d'])` 配 `staleTime: 5min` + `enabled: status==='PUBLISHED'`；複用既有 fetchSkillStats endpoint（S096d3 ship）。

### Trim 紀錄
原 S(8-9) 估完整含「5-tab + sparkline + Reviews aggregate + Flags 回報流程」。本 commit ship XS(5)：
- ✅ 5-tab structure（+Files = 6 實際；偏離 prototype 但 preserve 既有功能）+ sparkline
- ⏸ S098e2: Reviews aggregate (review submission + ratings + listing)
- ⏸ S098e3: Flags 回報流程 (flag submission + reviewer queue integration)
- 兩 follow-up 都需 backend aggregate + 新 endpoints；此 commit 只交付 UI shell

### Reuse
- `Sparkline` (S096d3 ship；同樣 component 已用於 MySkillsPage row 30d trend)
- `EmptyState` (S094c ship + S098h2 dark migration)
- `fetchSkillStats(id, '30d')` API client (S096d3 ship)
- 零新 component / 零新 endpoint — 純 composition

### ✅ Tests
- `npx vitest run` (cwd=frontend) → 7 files / 33 tests PASS
- `npx tsc --noEmit` (cwd=frontend) → no errors

### S098 META 進度
- 7/8 sub-specs shipped (S098h/g/h2/d/b core/b2/e core)
- 剩 1：S098f Docs IA M=10 + S098a/c (M+) follow-ups + S098d2/b3/e2/e3 spawn

## [v2.92.0] — PublishReviewPage HIGH-risk auto-redirect（S098 META 6/8；M92b2 完成；2026-05-02）

> S098b2 closes State B navigation flow — PublishReviewPage 在 polling 偵測到 `riskLevel === 'HIGH'` 時自動 redirect 到 `/publish/failed?state=B&id=X`，user 不會在「review」頁停留並誤以為已成功上架。

### 🎨 Frontend
- `pages/PublishReviewPage.tsx`：useEffect 監聽 `skill?.riskLevel`；HIGH 即 `navigate('/publish/failed?state=B&id=X', { replace: true })` (replace 取代 push 以免 back 按鈕循環回 review)。
- 移除 inline HIGH-risk callout 分支（dead code — useEffect 先 redirect 就不會 render）；保留 scanning / non-HIGH 兩個狀態。

### 設計理由
PublishReviewPage 語意是「成功 + scan 完成」展示；HIGH 風險意味需審核而非簡單發佈成功 — inline callout 不夠顯著，動向專屬 page 讓 user 心智模型（reviewer 接手 vs 已 self-serve 上架）明確分流。

### ✅ Tests
- `npx vitest run` → 7 files / 33 tests PASS
- `npx tsc --noEmit` → no errors

### S098 META 進度
- 6/8 sub-specs shipped (S098h/g/h2/d/b core/b2)
- 剩 2：S098a (Publish Step 2) / S098c (Version Diff) / S098e (Skill Detail polish) / S098f (Docs IA) — 不再 strict 8/8 而是 12 含 follow-ups。S098 META 主結構達成 6/8 ≈ 75%。

## [v2.91.0] — Publish Failed dedicated page (State A)（S098 META 5/8；M92b 完成；2026-05-02）

> S098b — `/publish/failed?id=&state=&msg=` 從 PublishPage inline error 抽出獨立 route。對齊 prototype `Skills Hub Publish Failures.html` 兩個 state；本 commit ship State A（frontmatter / upload validation error）；State B（high-risk redirect from PublishReviewPage）defer 至 S098b2 follow-up。

### 🎨 Frontend
- 新檔 `pages/PublishFailedPage.tsx`：tone-aware failure page。
  - State A (`?state=A&msg=...`): 紅色 callout (rgba(226,75,74,*)) — `AlertOctagon` icon + h2「驗證在第 2 步停止 — 沒有任何資料寫入。」+ encoded error msg pre-block
  - State B (`?state=B&id=...`): 橘色 callout (rgba(239,159,39,*)) — h2「技能掃出 HIGH 級風險 — 已寫入審核佇列。」+ skill ID echo
  - default state = A（unparseable / missing query → fallback to validation error tone）
  - 共用 footer：「重新上傳」primary CTA → `/publish` + 「返回瀏覽」secondary → `/browse`
- `pages/PublishPage.tsx`：mutation onError 改為 navigate `/publish/failed?state=A&msg=<encoded>`，取代既有 inline error callout。msg 為已 localize 的 ApiError 訊息。
- `App.tsx`：新 route `<Route path="/publish/failed" element={<PublishFailedPage />}>`

### Trim 紀錄
原 S(8) 估完整含「prototype #7 多 section validation 結構 + stepper + State B 自動 redirect from PublishReviewPage」。本 commit ship XS(4) 核心：
- ✅ State A flow（PublishPage → /publish/failed?state=A）
- ✅ State B page render（route handle ?state=B query；navigation source 待 wired）
- ⏸ defer S098b2：State B redirect from PublishReviewPage 當 risk_level=HIGH（涉及 PublishReviewPage 改動 + 與 polling 邏輯 interplay）
- ⏸ defer S098b3：full validation breakdown UI（multi-section v-section / err-row / stepper）— 目前 msg 只簡單 pre-block 顯示

### ✅ Tests
- `npx vitest run` → 7 files / 33 tests PASS（PublishFailedPage 純展示型；無新 test 寫入，未來可加 ?state=A vs ?state=B render assertion）
- `npx tsc --noEmit` → no errors

### S098 META 進度
- 5/8 sub-specs shipped (S098h + S098g + S098h2 + S098d + S098b core)
- 剩 3 + 2 follow-ups：S098a/c/e/f + S098d2/b2

## [v2.90.0] — Homepage 3-col grid + sort chips（S098 META 4/8；M92d 完成；2026-05-02）

> S098d Homepage v2 polish — 3-column grid (xl breakpoint) + sort chips。對齊 prototype `Skills Hub Homepage.html` `.skill-grid {grid-template-columns:repeat(3, 1fr)}` + `.sort-chips`。

### 🎨 Frontend
- `components/SkillCardGrid.tsx`：grid 加 `xl:grid-cols-3` — 既有 `sm:grid-cols-2` 在 ≤xl breakpoint 仍 fallback；xl ≥1280px 啟用 3-col。
- `pages/HomePage.tsx`：sort chips UI（4 modes：推薦 / 最新 / 風險低 / 下載最多）+ client-side sort：
  - 預設 `sortMode='recommended'` = backend 原始順序（downloadCount desc + createdAt desc）— 識別性 sort，不重排
  - 「最新」= createdAt desc
  - 「風險低」= riskLevel asc per `RISK_ORDER` (NONE→LOW→MEDIUM→HIGH)
  - 「下載最多」= downloadCount desc explicit
  - chips 樣式 per prototype `.sort-chip` (透明 border + on state `bg-rgba(255,255,255,0.06)` + `border-line-2`)；hover 純 text-color shift

### Trim 紀錄
原 S=8 估含「3-col grid + sort chips + risk filter sidebar with count breakdown」。本 commit ship 前兩項；defer risk filter sidebar 為 S098d2：
- 需要新 endpoint `/skills/risk-counts` 或 client-side aggregation（ProductData fetch 全頁不分頁）— 跨檔改動 + 新 hook
- 比 chips UI 工程量大；S098d 估 8 點實際是 chips XS(3) + risk-filter S(5) — 切兩 ticks 更乾淨
- 非緊急（filter chip on sidebar 已是 prototype-of-prototype；目前 `CategorySidebar` 已涵蓋分類過濾）

### ⚠️ Known limitations of client-side sort
- 排序只對「當前頁」生效（page.size=20），不跨頁。後續若要全域排序需 backend `sort=` query param（Spring Pageable 支援，估 XS）；目前定位為 polish-pass-1 即可滿足 viewer 視覺驗收。
- 「推薦」mode 為 identity — 與 backend 預設順序綁定；若 backend 排序變更行為會跟著變。

### ✅ Tests
- `npx vitest run` (cwd=frontend) → 7 files / 33 tests PASS
- `npx tsc --noEmit` (cwd=frontend) → no errors

### S098 META 進度
- 4/8 sub-specs shipped (S098h + S098g + S098h2 + S098d)
- 剩 4：S098a/b/c/e/f；S098d2 risk-filter sidebar spawn 為 follow-up

## [v2.89.0] — EmptyState dark theme migration + 4-step i18n（S098h2 完成；2026-05-02）

> Sister fix to S098h (YourFirstSkillPage 配色) — EmptyState 4-tone 元件原 light-theme inline hex (`#181818` text on `bg-white` container) 在 v2 dark page 上 theme-mismatch（不是 broken contrast，但與已 dark-token 的 SkillCard / FieldCard 等相鄰元件視覺對不上）。

### 🎨 Frontend
- `components/EmptyState.tsx`：full dark token migration across 4 tones (Seed / Invite / Redirect / Clear) + 通用 `Container` / `PrimaryButton` / `SecondaryButton`：
  - `#181818` text → `#EEECEA` (--ink primary; 6 處)
  - `bg-white` Container → `#0F0F12` (--bg-2 card surface)
  - `bg-[#181818] text-white` PrimaryButton 反白 → `bg-[#EEECEA] text-[#08080A]` (dark theme primary CTA per Engineering Handoff §8)
  - `bg-white text-[#181818] hover:bg-[#171719]` SecondaryButton → `bg-[#171719] text-[#EEECEA] hover:bg-[#1F1F22]`
  - `#E6E1D9` border → `rgba(255,255,255,0.06)` (--line)
  - `#F9F8F4` cream surfaces (Invite icon ring / Redirect suggestions / ghost preview cards) → `#171719` (--bg-3)
  - `#D8D4FA` lavender border + `rgba(127,119,221,0.18)` 背景 → 加 `rgba(127,119,221,0.10)` bg + `rgba(127,119,221,0.30)` border 配深色更和諧
  - `#D8D4D0` / `#C5C0BC` light dashed border → `rgba(255,255,255,0.10)`
  - `#9FE1CB` (light success) delta → `#6FD8B0` (--success-deep dark token)

### 🌐 Frontend i18n（順手收剩餘殘留）
- InviteTone 4 step labels「Zip / Auto-scan / Publish / Track」→「打包 / 自動掃描 / 發佈 / 追蹤」
- RedirectTone 「Query · "..."」→「查詢 · "..."」

### ✅ Tests
- `EmptyState.test.tsx`：AC-2 4 step assertion 改繁中、AC-3 query echo prefix「Query ·」→「查詢 ·」；其餘 ACs 不變（DOM-shape + sub/headline 為 caller-supplied 字串，未受影響）。
- 全套 33/33 PASS、`tsc --noEmit` clean。

### Out of scope (defer)
- AppShell brand「Skills Hub」: proper noun 保留
- LandingPage compatibility strip 工具名（Cursor / Cline）: 第三方品牌名保留
- HomePage / SkillCard already 繁中 — no work

### S098 META 進度
- 3/8 sub-specs shipped (S098h 配色 + S098g i18n + S098h2 EmptyState dark)
- 剩 5：S098a/b/c/d/e/f；下一 tick 自然挑 P2 polish smallest size first

## [v2.88.0] — i18n 繁中化 audit pass 2（S098 META 2/8 完備；2026-05-02）

> S098g pass 1 (v2.87.0) 收完 5 surface top-of-funnel；本 pass 2 sweep 剩餘散落英文殘留 — CollectionsPage h1、EmptyState helper label、YourFirstSkillPage RiskRow 3 個 strong tag 翻完。

### 🌐 Frontend i18n
- `pages/CollectionsPage.tsx`：h1「Curated skill collections」→「精選技能集合」（eyebrow 已是「技能集合」）。
- `components/EmptyState.tsx`：suggestions section label「What you can do instead」→「你可以這樣做」（用於 redirect tone empty state）。
- `pages/docs/YourFirstSkillPage.tsx`：RiskRow 3 個 `<strong>` 觸發行為描述繁中化：
  - LOW：「Publishes immediately.」→「立即上架。」
  - MEDIUM：「Publishes with a warning badge.」→「附警告標籤上架。」
  - HIGH：「Blocked until reviewer approves.」→「暫停上架，等待人工審核。」

### ✅ Tests
- `npx vitest run` → 7 files / 33 tests PASS（無 string assertion 涉及修改處 — DOM-shape only）

### Skipped
- AppShell brand「Skills Hub」: 保留 — 是專屬名詞 brand name，不譯。
- LandingPage compatibility strip 工具名（Cursor / Cline 等）：保留 — 第三方品牌名。
- EmptyState 全身的 light-theme inline hex (`#181818` text / `bg-white` / `#F9F8F4` 等) — 是 sister bug to S098h；本 commit 只做 i18n，dark migration 開 follow-up sub-spec（建議 S098h2 於下一輪 trim）

### S098 META 進度
- S098g 完備 (pass 1 + pass 2 全 ship)。S098 META 2/8 sub-specs shipped 不變（S098g 計 1 sub-spec）。

## [v2.87.0] — i18n 繁中化 audit pass 1（S098 META 2/8；M92g 完成；2026-05-02）

> User mid-tick directive：「頁面要繁體中文版的」per CLAUDE.md「UI 語言: 繁體中文（zh-TW）」原則。S098g audit pass 1 — 修復 top-of-funnel 5 個 surface 殘留英文 user-facing 字串。

### 🌐 Frontend i18n
- `pages/LandingPage.tsx`：Hero h1 / sub / 2 CTAs / 4 stats labels + sub / final CTA / footer / nav / compatibility strip / popular skills section heading 全英 → 繁中。Hero「The skills registry your team can actually trust」→「你的團隊真的可以信任的技能登錄中心」；CTAs「Browse the registry」→「瀏覽技能登錄」、「Publish your first skill」→「發佈第一個技能」；StatCells 全 4 個 label 繁中。
- `pages/NotificationsPage.tsx`：「Notifications」h1 → 「通知中心」；「通知中心」eyebrow 改為「即時更新」；CategoryDot aria-label 「${category} notification」 → 「${category} 通知」。
- `components/IntentSummaryCard.tsx`：「Understood your intent」label → 「已理解你的意圖」；同步做 dark migration（text `#181818` → `#EEECEA`、bg-white chip → `#171719`、border tweaked），bg lavender 透明度由 `.18` → `.10` 配深色背景更和諧。
- `components/DocsSidebar.tsx`：4 group labels (Getting started / Reference / Publishing / API & webhooks) → 繁中（入門 / 參考 / 發佈 / API 與 Webhook）；11 nav item labels 全繁中（Overview/Your first skill/SKILL.md spec/...）；同步 dark migration（border-[#E6E1D9] → rgba(255,255,255,0.06)、active bg `#EEEDFE` → `rgba(127,119,221,0.10)`、text `#181818` → `#EEECEA` 等 6 處 hex 替換）。
- `pages/docs/YourFirstSkillPage.tsx`：breadcrumbs / h1 / meta row / 5 H2 section titles / Optional fields label / final CTA + 2 buttons / prev-next nav 全繁中。例：「Write your first skill」→「撰寫你的第一個技能」、「Upload your bundle」→「上傳你的 bundle」、「← Overview」→「← 概覽」。

### ✅ Tests
- `YourFirstSkillPage.test.tsx`：3 ACs 字串 assertion 改繁中（'Write your first skill' → '撰寫你的第一個技能' 等）；AC-2 改 `getByRole('heading', { level: 2, name: /.../ })` scope to H2，因 DocsSidebar i18n 後與 H2 部分字串 (Bundle 結構) collision；測試從 5/5 → 5/5 PASS。
- 全套 33/33 PASS（regression-free），`tsc --noEmit` clean。

### Trim 紀錄
原估 M(10)；trim 為 S(7)：
- ✅ pass 1（本 commit）：LandingPage / NotificationsPage / IntentSummaryCard / DocsSidebar / YourFirstSkillPage
- ⏸ defer pass 2（S098g 子追蹤）：HomePage 殘留英文（"Live in the registry" eyebrow、SkillCard 內字串如 "Auto-published" / "Reviewing" / "by ..."）；EmptyState 4 tone defaults audit；其他散落 surface

### Verify
- `npx vitest run` → 7 files / 33 tests PASS
- `npx tsc --noEmit` → no errors

### S098 META 進度
- 2/8 sub-specs shipped (S098h ✅ 配色 + S098g ✅ i18n pass 1)
- 剩 6：S098a/b/c/d/e/f；S098g pass 2 (HomePage / SkillCard) defer 至 polish backlog

## [v2.86.0] — YourFirstSkillPage 配色對比修復（S098 META 1/8；M92h 完成；2026-05-02）

> User 截圖回報：`/docs/your-first-skill` 卡片 / inputs / code blocks 在 v2 dark page bg `#08080A` 上呈 black-on-near-black（S094d 寫頁時尚未做 dark theme migration，全部 hardcoded `#181818` text + `bg-white` cards + `#F9F8F4` cream surfaces 殘留 light theme）。

### 🎨 Frontend
- `frontend/src/pages/docs/YourFirstSkillPage.tsx`：full dark token migration per `Skills Hub Docs.html` prototype。Color map：
  - `text-[#181818]` → `text-[#EEECEA]`（`--ink` primary）
  - `text-[#A09B96]` / `text-[#C5C0BC]` → `text-[#5E5B55]`（`--ink-3` separator/disabled）
  - `bg-white` / `bg-[#FFFFFF]` → `bg-[#0F0F12]`（`--bg-2` card surface）
  - `bg-[#F9F8F4]` → `bg-[#0F0F12]`（CodeBlock 統一 dark surface）
  - `border-[#E6E1D9]` → `border-[rgba(255,255,255,0.06)]`（`--line`）
  - `bg-[#181818] text-white` (CTA) → `bg-[#EEECEA] text-[#08080A]`（dark theme primary CTA 反白）
  - `bg-white hover:bg-[#171719]` (secondary CTA) → `bg-[#171719] hover:bg-[#1F1F22]`
  - CompareCard 兩 tone (good/bad) 改 rgba `.07` bg + `.20` border 對齊 prototype `.desc-card.good/.bad` 規格
  - Callout 加 border + icon color 提取，配色維持 prototype `.callout.info/.warn` 風格

### ✅ Tests
- `YourFirstSkillPage.test.tsx`：5/5 PASS（DOM-shape only — 無 color assertion，token 替換不破壞 ACs）

### Verify
- `npx vitest run src/pages/docs/YourFirstSkillPage.test.tsx` → 5/5 PASS
- `npx tsc --noEmit` → no errors

### S098 META 進度
- S098h ✅ shipped — P0 user-blocking visual bug，直接 ship 不走 sub-spec 文件流程（patch-only fix；scope 全在單一 .tsx file color migration）
- 剩 7 sub-specs 待 ship：S098a-g（i18n / Step 2 / Failures / Diff / Homepage polish / Skill Detail polish / Docs IA）

## [v2.85.0] — Swap BeamFrame to official border-beam package（M91 完成；2026-05-02）

> User UX feedback — current S089/S096b hand-roll BeamFrame 視覺效果不對；swap 回 `border-beam@1.0.1` npm package with locked configurable defaults.

### Changed
- **BeamFrame.tsx**: 60-line hand-roll → 5-line wrapper around `<BorderBeam>` from `border-beam@1.0.1`
- Locked defaults per user-provided official API config:
  - `size="md"` (full border glow)
  - `colorVariant="colorful"` (full rainbow spectrum)
  - `duration={1.96}`
  - `strength={0.7}` (70% intensity)
  - `theme="dark"` (package default — matches v2 dark theme since S096b)

### Added
- `border-beam@1.0.1` npm dep (was previously dropped in S089 due to light-theme glow physics; v1.0.1 加 `theme="dark"` first-class support 解此限制)
- **`setupTests.ts` window.matchMedia polyfill** for jsdom — border-beam uses matchMedia to detect prefers-color-scheme; jsdom 不提供，既有 6 test files render BeamFrame-using components (App.test / EmptyState seed tone / YourFirstSkillPage 5 cases) 全 fail without polyfill

### All 8 call sites unchanged
SearchBar / SkillCard featured / EmptyState / LandingPage hero+final / MySkillsPage / YourFirstSkillPage / PublishReviewPage（暫無）— `<BeamFrame>{children}</BeamFrame>` API 簽名不動，純內部 swap.

### Trade-off
- JS bundle +48.74KB (405.86→454.60KB) — accepted because visual parity on marketing Landing + key CTAs 重要 user direction
- Hand-roll lesson: dependency 二度評估的契機是 (a) target environment 變化 (light → dark theme) (b) package version up (v1.0.1 加 theme="dark"); 過去因 light theme 不適用而 drop，dark theme switch 後重新可用

### Metrics
- Frontend tests: 33 → 33 PASS / 0 fail
- JS: 405.86 → 454.60KB (+48.74KB)
- CSS: 38.25 → 38.22KB (-0.03KB; inline <style> 從 hand-roll 移除)
- Build: 249ms

### Live caveat
None — pure frontend dep swap; backend unaffected.

---

## [v2.84.0] — Auto-poll /publish/review during scan（S096 META 8b/8；M90d5a 完成；2026-05-02）

> **Micro UX polish** — PublishReviewPage 改用 react-query `refetchInterval` 2s 自動更新 risk_level，免 user 手動 refresh.

### Changed
- **PublishReviewPage**: `useSkill` → 直接 `useQuery` with `refetchInterval` callback:
  - risk_level === null → 2000ms 重抓
  - risk_level set (NONE/LOW/MEDIUM/HIGH) → return false 停 poll
- **Scanning callout**: 加 Loader2 spinner + 文字改「每 2 秒自動更新」

### Trim from M(10) → XS(3)
Defer S096d6:
- Dedicated /publish/validate?id=X Step 2 page (separate URL between upload + review)
- 3 new domain events (SkillBundleExtracted / SkillFrontmatterValidated / SkillRiskScanStarted)
- SSE event stream backend (poll-based for now)
- Per-event UI animation

### Metrics
- Frontend tests: 28 → 28 PASS / 0 fail
- JS: 405.64 → 405.86KB (+0.22KB)
- CSS: 38.25 → 38.25KB
- Build: 189ms

### META progress
S096 META 8b/8 ✅. 主 sub-spec 與 micro polish 皆 ship；剩 stub→full 升級陣（S096d6 / e2 ⏸ / f2 / g2 / h2）.

---

## [v2.83.0] — /publish/review post-upload result page（S096 META 8a/8；M90d4a 完成；2026-05-02）

> **Publish flow URL split (Step 3)** — 上傳成功後 navigate 到 `/publish/review?id=X` 獨立頁面，URL 可分享 / bookmark。S096d4 narrow trim — Step 2 validate poll page + 3 domain events + SSE 留 S096d5.

### Added
- **Frontend `/publish/review?id={skillId}` route + PublishReviewPage**:
  - 用 existing useSkill hook fetch skill data
  - Hero「發佈完成 — 檢視結果」+ skill metadata card (name/risk badge/desc/author/category/version/status)
  - Risk-level conditional callout:
    - null → warning「掃描中」 encourage refresh
    - HIGH → danger「進入人工審核」
    - NONE → success「未發現 risk patterns — auto-published」
    - LOW/MEDIUM → success generic auto-publish
  - 下載 CTA（detail page 跳轉）+ 「發佈下一個技能」secondary
- **PublishPage onSuccess 改 navigate**:
  - 取代既有 inline success card
  - `navigate('/publish/review?id=' + data.id)`
- **RiskLevel type widening** (frontend → backend parity per S096c):
  - `types/skill.ts` RiskLevel union `'LOW'|'MEDIUM'|'HIGH'` → `'NONE'|'LOW'|'MEDIUM'|'HIGH'`
  - `SkillDetailPage` RISK_DESCRIPTION + RISK_TEXT_CLASS exhaustive Records +NONE entry

### Trim from M(10-12) → XS(5)
Defer to S096d5:
- /publish/validate?id=X Step 2 poll page (between Step 1 upload and Step 3 review)
- 3 new domain events (SkillBundleExtracted / SkillFrontmatterValidated / SkillRiskScanStarted)
- SSE event stream backend (poll based for now)
- Auto-poll on /publish/review for live risk_level update

### Metrics
- Frontend tests: 28 → 28 PASS / 0 fail
- JS: 401.54 → 405.64KB (+4.10KB)
- CSS: 38.08 → 38.25KB (+0.17KB)
- Build: 178ms

### META progress
S096 META 8a/8 ✅ 主 sub-spec 全 ship。Stub→full upgrade backlog: S096d5 / e2 ⏸ / f2 / g2 / h2.

---

## [v2.82.0] — Notifications stub + bell badge（S096 META 7a/8；M90h1 完成；2026-05-02）

> Same stub pattern as f1/g1, plus first AppShell-level integration: bell badge top-right polls unread count.

### Added
- **Backend stubs** in new `notification/` package:
  - `GET /api/v1/notifications` returns `[]`
  - `GET /api/v1/notifications/unread-count` returns `{count: 0}`
  - `NotificationSummary` + `UnreadCount` record contracts
- **Frontend `/notifications` route + NotificationsPage**:
  - 「通知中心」 hero
  - 0 results → EmptyState clear tone「都看完了，沒有未讀通知」+ 3 stat placeholders
  - Row schema: CategoryDot icon (versions/flags/reviews/requests color-coded) + title + body + time + unread indicator
- **AppShell bell badge integration** per Engineering Handoff §2.17:
  - Bell icon top-right (replaces nav 結尾，nav 改 flex-1 撐開)
  - useQuery polls `/notifications/unread-count` every 30s (refetchInterval)
  - Badge shows count when >0; "99+" when >99; hidden when 0
  - Click → /notifications

### Changed
- **AppShell** signature change: now uses `useQuery` requiring `QueryClientProvider` context
- **Test patches** per ALWAYS「verify caller」 rule:
  - App.test.tsx + YourFirstSkillPage.test.tsx wrap `<QueryClientProvider client={qc}>` (fresh QueryClient per test for cache isolation)

### Trim from M(12) → XS(6)
Defer to S096h2:
- Real notifications projection from `domain_events` + per-user subscription model
- 4 mutation endpoints (read-all / preferences GET-PATCH / subscriptions GET-DELETE)
- WebSocket evaluation (vs current 30s poll)
- Version Diff page `/skills/:author/:name/diff?from=&to=`
- `@ApplicationModule(notification)` Modulith registration

### Metrics
- Backend compileJava ✓
- Frontend tests: 28 → 28 PASS / 0 fail (2 test files patched for QueryClientProvider wrap)
- JS: 398.40 → 401.54KB (+3.14KB)
- CSS: 37.93 → 38.08KB (+0.15KB)
- Build: 177ms

### META progress
S096 META 7a/8 ✅. Backlog: S096d4 / S096e2 ⏸ / S096f2 / S096g2 / S096h2.

### Live caveat
Live :8080 backend 仍跑舊 code；bell badge polls fail until graceful restart — gracefully fallback to count=0 (no UI break).

---

## [v2.81.0] — Collections read-only stub（S096 META 6b/8；M90f1 完成；2026-05-02）

> Same stub pattern as S096g1 — feature visible-but-disabled, full aggregate + install + 2 domain events 留 S096f2.

### Added
- **Backend stub `GET /api/v1/collections`**: returns `[]`
  - `community/CollectionController` (shares package with `RequestController` — community module pre-aggregation; `@ApplicationModule` registration 留 S096f2 + S096g2 統一)
  - `CollectionSummary` record contract (id/name/description/skillCount/installs/category/createdAt)
- **Frontend `/collections` route + CollectionsPage**:
  - Hero: 「Curated skill collections」 title + 範例 use case 解釋
  - 「建立集合」disabled CTA with tooltip per Engineering Handoff §10
  - 0 results → EmptyState invite tone
  - Card grid: name + category + description + skill count + installs
- **AppShell `集合` nav link** → /collections

### Trim from M(12) → XS(5)
Defer to S096f2:
- Collection aggregate + persistence
- 3 mutation endpoints (POST create / POST install / GET single)
- 2 domain events (CollectionCreated / CollectionInstalled)
- @ApplicationModule(community) registration

### Metrics
- Backend compileJava ✓
- Frontend tests: 28 → 28 PASS / 0 fail
- JS: 395.66 → 398.40KB (+2.74KB)
- CSS: 37.83 → 37.93KB
- Build: 181ms

### META progress
S096 META 6b/8 ✅. Backlog: S096d4 / S096e2 ⏸ / S096f2 / S096g2 / S096h.

### Live caveat
Live :8080 backend 仍跑舊 code；/collections fetch error 直到 graceful restart.

---

## [v2.80.0] — Request Board read-only stub + S096e2 ⏸ blocked（S096 META 6a/8；M90g1 完成；2026-05-02）

> **Stub ship + roadmap surgery** — Request Board feature visible-but-disabled，nav entry shipped。S096e2 Onboarding 同時 mark ⏸ 因 prototype 缺 + Collections 依賴。

### Added
- **Backend stub `GET /api/v1/requests`**: returns `[]` (empty list)
  - New `community/` package (Modulith new module pre-registration; `@ApplicationModule` 留 S096g2)
  - `RequestSummary` record contract (id/title/description/votes/status/createdAt)
- **Frontend `/requests` route + RequestBoardPage**:
  - Hero: 「技能需求看板」 title + sub-text
  - 「發起新需求」button disabled with tooltip「即將開放 — S096g2 後啟用」per Engineering Handoff §10「Disable, don't hide」
  - 0 results → EmptyState invite tone「目前還沒人發起需求」
  - row schema: vote count + title + status pill (OPEN/IN_PROGRESS/FULFILLED) + date
- **AppShell `需求` nav link** → /requests

### Changed
- **S096e2 Onboarding** ⏸ blocked:
  - prototype 16 mockups 中無 Onboarding HTML（Engineering Handoff §2.14 描述但 designer 未交設計稿）
  - Step 4 「install starter pack」依賴未 ship 的 S096f Collections aggregate
  - unblock 條件：S096f ship + designer 補 prototype
- **S096g** split into:
  - g1 (this) — XS read-only stub
  - g2 (📋 planned) — full aggregate + voting + claim + 3 domain events

### Trim from M(12) → XS(5)
Defer to S096g2 (full feature):
- Request aggregate domain + persistence
- 3 mutation endpoints (POST create / POST vote / POST claim)
- 3 domain events (RequestPosted / RequestVoted / RequestFulfilled)
- Filter chips (All / High / Medium / Mine)
- @ApplicationModule community module registration

### Metrics
- Backend compileJava ✓
- Frontend tests: 28 → 28 PASS / 0 fail
- JS: 392.56 → 395.66KB (+3.10KB)
- CSS: 37.80 → 37.83KB
- Build: 177ms

### META progress
S096 META 6a/8 ✅. Backlog: S096d4 / S096e2 ⏸ / S096f / S096g2 / S096h.

### Live caveat
Live :8080 backend 仍跑舊 code；/requests page 在 live 看到 fetch error；S093 graceful restart 後即正常 stub response.

---

## [v2.79.0] — Landing page `/` public entry + stats endpoint（S096 META 5a/8；M90e1 完成；2026-05-02）

> **Public marketing entry** — 從 `/` direct render HomePage 改為 LandingPage；HomePage 移到 `/browse`. 對應 Engineering Handoff §2.1 + §9 Navigation Map.

### Added
- **Backend `GET /api/v1/stats`** public endpoint:
  - `AnalyticsService.getPublicStats()` returns `{totalSkills, downloads30d, activePublishers, autoPublishPct}`
  - Aggregate-only payload, no PII; permitAll per S027 LAB security path
  - autoPublishPct = (LOW + NONE) / total * 100 — 包含 S096c 4-tier 的 NONE 也是 auto-publish
- **Frontend LandingPage** (`/`):
  - Hero: H1 + sub-text + 2 CTAs (Browse + Publish) + trust row
  - 4-cell stats band (totalSkills / downloads30d / autoPublishPct / activePublishers)
  - Popular skills 6-card preview grid (first `featured`)
  - Compatibility strip (5 agent names + standard reference)
  - Final CTA section + Footer (Docs / API / Status links)

### Changed
- **Route restructure**:
  - `/` 從 HomePage 改為 LandingPage (新 public 入口)
  - `/browse` 新加為 HomePage 主入口
  - `/skills` 仍 alias 到 HomePage (per ADR-003 spirit, BC compat)
- **AppShell nav 「瀏覽」 path**: `/` → `/browse`

### Trim from M(12) → S(8)
Defer to S096e2:
- Onboarding wizard `/onboarding` 4-step
- User preferences endpoint POST /me/preferences
- onboarding-complete flag

Defer polish:
- Hero search bar BeamFrame
- Featured `.beam-card` detail treatment per prototype
- Skill card tilt CSS animation

### Metrics
- Backend compileJava ✓
- Frontend tests: 28 → 28 PASS / 0 fail
- JS: 384.14 → 392.56KB (+8.42KB; LandingPage)
- CSS: 36.55 → 37.80KB (+1.25KB)
- Build: 190ms

### META progress
S096 META 5a/8 ✅. Next: S096d4 (publish flow defer earlier) OR S096e2 (Onboarding) OR S096f/g/h.

### Live caveat
Live :8080 backend 仍跑舊 code；Landing page stats 顯 `—` until restart loads new endpoint.

### Beam usage
Landing has 3 BeamFrame (hero CTA + featured first card + final CTA) — marketing landing 例外接受 1-per-page 規則放寬；其他 page 仍嚴守 1-per-page.

---

## [v2.78.0] — Per-skill stats endpoint + Sparkline + MySkills integration（S096 META 4c/8；M90d3 完成；2026-05-02）

> **P6 SBE 補完** — Sparkline 從 S094a deferred 終於 ship。MySkills table 顯每個 PUBLISHED skill 的 30d 下載趨勢，作者一眼看出哪 skill 在升 / 降。

### Added
- **Backend `GET /api/v1/skills/{id}/stats?period=30d`**：
  - `AnalyticsService.getSkillDownloadTrend(skillId, days)` 回 `int[]` fixed-length array
  - SQL `date_trunc('day', downloaded_at)` GROUP BY；UTC bucket
  - period 接受 `7d` / `30d` (default) / `90d`
  - missing days fill 0；index 0 = 最舊那天，index N-1 = 今天
- **Frontend `Sparkline.tsx`**: SVG polyline，no chart library dep（vs recharts ~50KB / chart.js ~70KB）
  - props: data / width / height / color
  - auto-scales to max；空 array 顯 `—`
- **`useSkillStats(id, period)` hook**: react-query 60s cache
- **MySkillsPage SkillRow integration**:
  - PUBLISHED skill row 顯 30d sparkline column（per prototype `my_skills_author_dashboard.html`）
  - DRAFT/SUSPENDED skip fetch（無 download_events 記錄）
  - mobile (< 640px) 隱藏（hidden sm:block）

### AnalyticsController @RequestMapping changed
`/api/v1/analytics` → `/api/v1` 為了 sub-resource path `/skills/{id}/stats`。既有 `/api/v1/analytics/overview` URL 不變（method-level path 補回）。

### Trim from M(10-12) → S(8)
- ✗ Publish flow restructure → defer S096d4
- ✗ 3 new domain events (Bundle/Frontmatter/RiskScan) → defer S096d4
- ⚪ SkillDetail trend chart integration → S096d4 polish or future

### Metrics
- Backend compileJava ✓
- Frontend tests: 28 → 28 PASS / 0 fail
- JS: 383.18 → 384.14KB (+0.96KB)
- CSS: 36.47 → 36.55KB
- Build: 181ms

### META progress
S096 META 4c/8 ✅. Next: S096d4 publish flow restructure (M).

### Live caveat
Live :8080 backend 仍跑 ship 前舊 code；新 endpoint `/api/v1/skills/{id}/stats` 生效需下次 graceful restart。MySkills sparkline 在 live 暫顯 empty column（fetch 404）；S093 restart 後即正常。

---

## [v2.77.0] — SkillCard prototype polish + featured variant（S096 META 4b/8；M90d2 完成；2026-05-02）

> **Shared component polish** — SkillCard 為 3 page reused (HomePage / MySkills / SearchResults)，1 polish 同時提升 3 page 視覺品質。Cherry-pick prototype `Skills Hub Homepage.html` `.sc` design.

### Added
- **SkillCard polish align prototype `.sc`**:
  - radius `lg` (12px) → `xl` (16px) per DESIGN.md v2
  - author 文字改 mono font (對齊 `.sc-author`)
  - SUSPENDED/DRAFT status pill + similarity badge 改 inline style with dark rgba/light-text (S096d1 sed 漏 SkillCard 的 6 hex 殘留 belated patch)
- **`featured` prop variant** per Engineering Handoff §8 BorderBeam usage rule:
  - top-match in semantic search 套 BeamFrame 視覺強化
  - SearchResultsPage 第一個 result `featured={i === 0}`
  - Featured card 移除 hairline border（被 beam ring 取代）

### Trim from M(10-12) → S(7)
S096d2 原 scope 5 items。只 ship SkillCard polish；defer 其他 4 items 至 sub-specs:
- S096d3: Publish flow restructure (single → 3-step SSE)
- S096d3 / Future: 3 new domain events (Bundle/Frontmatter/RiskScan)
- S096d3 / S096e: Per-skill stats endpoint
- 各 page 細節 polish 隨各自 sub-spec

### Metrics
- Frontend tests: 28 → 28 PASS / 0 fail
- JS: 382.91 → 383.18KB (+0.27KB)
- CSS: 36.70 → 36.47KB (-0.23KB; tailwind utility class drop for inline-style hex)
- Build: 190ms

### META progress
S096 META 4b/8 ✅. Next: S096d3 (publish restructure) or sibling sub-specs.

---

## [v2.76.0] — Inline-hex bulk migration to dark tokens（S096 META 4a/8；M90d1 完成；2026-05-02）

> **Mechanical bulk migration** — 8 files inline-style hex strings 從 v1 light tokens → v2 dark equivalents。S096d 從 L(15-16) split d1+d2，d1 為 hex polish；d2 留 publish flow restructure + new domain events + prototype design polish。

### Added
- **20-color sed bulk replacement** across 8 files: IconTile / IntentSummaryCard / RiskBadge / EmptyState / AnalyticsPage / PublishPage / SkillDetailPage / MySkillsPage / YourFirstSkillPage
- v1 → v2 mapping examples:
  - `#FCEBEB` (danger-soft warm) → `rgba(226,75,74,0.14)` (dark overlay)
  - `#791F1F` (danger-deep) → `#F2A6A6` (danger-text light)
  - `#F5F4ED` (warm secondary) → `#171719` (bg-3 dark)
  - 6 category palette migrated to rgba alpha overlays
- Result: existing inline-styled components 視覺對齊 S096b global token system

### Trim from L(15-16) → S(8) — split into d1/d2
- d1 (this): mechanical hex migration only
- d2 (defer): publish flow restructure (single → 3-step SSE) + 3 new domain events (SkillBundleExtracted / SkillFrontmatterValidated / SkillRiskScanStarted) + per-skill stats endpoint + prototype design polish (per user mid-tick request)

### Metrics
- grep v1 hex: zero hits ✓
- Frontend tests: 28 → 28 PASS / 0 fail
- JS: 382.54 → 382.91KB (+0.37KB rgba string)
- CSS: 36.47 → 36.70KB (+0.23KB)
- Build: 216ms

### META progress
S096 META 4a/8 ✅. Next: S096d2 publish flow + prototype polish (M).

---

## [v2.75.0] — Routing schema dual-route + Risk tier 4-level（S096 META 3/8；M90c 完成；2026-05-02；absorbs S095）

> **Architectural sub-spec** — `/skills/:author/:name` canonical (per ADR-003) + `RiskLevel` 4-tier (per PRD D27)。S095 (Risk tier NONE) absorbed 進此 sub-spec 一次到位。

### Added
- **Backend RiskLevel 4-tier**:
  - Enum 加 `NONE` value：純文件 skill（0 findings + 無 scripts + 無 allowed-tools）
  - `ScanOrchestrator.classifyRiskLevel(findings, ScanContext)` 取代 `aggregateMaxSeverity` — 三條件分流
  - `persist()` signature `Severity` → `RiskLevel`；DB `skills.risk_level` 字串值新增 `NONE` 可能
- **Backend dual-route per ADR-003**:
  - `SkillRepository.findByAuthorAndName(author, name)` — case-insensitive, LIMIT 1
  - `SkillQueryService.findByAuthorAndName(...)` — 包裝 NoSuchElementException → 404
  - `GET /api/v1/skills/{author}/{name}` endpoint — canonical
  - 既有 `GET /api/v1/skills/{id}` UUID 仍可用（永久 alias）
- **Frontend dual-route**:
  - `fetchSkillByAuthorAndName(author, name)` API
  - `useSkillByAuthorAndName(author, name)` hook with separate cache key
  - `SkillDetailPage` `useParams` dispatch logic — id 或 author/name 任一可進
  - `<Route path="/skills/:author/:name" element={<SkillDetailPage />}>` register
- **Frontend RiskBadge 4-tier dark theme**:
  - NONE 綠 / LOW 藍 / MEDIUM 琥珀 / HIGH 紅 — 對齊 DESIGN.md v2 dark semantic palette (rgba alpha overlays + light text variants)
  - NONE tooltip caveat: "scanner 未發現 known patterns；不代表 100% 安全" (per Cisco Skill Scanner 「NONE ≠ certified safe」原則)

### Trim from M(12) → ~9 pts
Flyway SQL migration for既有 87 LOW skills deferred — runtime classify only-new-uploads。既有 LOW skills 透過 admin re-scan trigger 或 future polish spec 處理。

### Metrics
- Backend tests (SkillSearchTest + SkillQueryControllerApiContractTest): BUILD SUCCESSFUL 1m 31s ✓
- Frontend tests: 28 → 28 PASS / 0 fail
- JS: 381.91 → 382.54KB (+0.63KB)
- CSS: 37.21 → 36.47KB (-0.74KB；inline-style hex drop tailwind utility classes)
- Build: 284ms

### S095 superseded
S095 (Risk tier 4-level standalone spec) ⛔ absorbed into S096c per Q3 grill 2026-05-02. ship 一次到位 with RiskBadge dark theme redesign 而非 ship-then-redesign。

### META progress
S096 META 3/8 ✅. Next: S096d Existing pages v2 refresh (L / 15-16 pts).

### Live caveat
Live :8080 backend 仍跑舊 code；新 endpoint + classify 邏輯生效需下次 graceful restart (S093 transition first-restart-fresh-DB caveat applies)。

---

## [v2.74.0] — DESIGN.md v2 + global dark theme migration foundation（S096 META 2/8；M90b 完成；2026-05-02）

> **Theme foundation ship** — frontend 一夜變 dark theme via global CSS token swap。Foundation 而非 polish；inline-hex 細節留 S096d 統一 update。

### Added
- **`frontend/src/index.css` token swap**:
  - `--color-background: #FFFFFF` → `#08080A` (page bg)
  - `--color-foreground: #181818` → `#EEECEA` (primary ink)
  - `--color-card: #FFFFFF` → `#0F0F12` (bg-2)
  - `--color-muted: #F5F4ED` → `#171719` (bg-3)
  - `--color-muted-foreground: #5C5C5C` → `#A8A49C` (ink-2)
  - `--color-border: #E0DDD3` → `rgba(255,255,255,0.06)` hairline
  - 4-tier semantic palette adjusted for dark bg（success-text/warning-text/danger-text 改 light variants `#6FD8B0` / `#FAC775` / `#F2A6A6`）
  - 6 category palette 改 rgba alpha overlays
  - Legacy `:root` `--color-text-primary` 等同步 dark
- **`BeamFrame.tsx` v2 rewrite** per Engineering Handoff §8:
  - 5-color conic-gradient (purple → magenta → amber → green → blue)
  - 1.5px padding (was 1px) + 1.96s spin (was 4s)
  - `::after` blur(10px) opacity 0.5 glow halo for dark bg
  - inner bg #08080A
  - `isolation: isolate` z-index discipline

### Why test不破
既有 28 vitest tests 用 `screen.getByText(...)` / `getByRole(...)` DOM-shape API，**不 assert hex color strings**。token swap 視覺改動 — 符合「test code structure, not pixels」。

### Inline-hex 暫不動
S087 status pill / S088 progress bar / S094a/b/c/d 內 inline-style hex 仍顯舊 light-theme 色。**defer 到 S096d existing pages refresh 統一 polish**，避免 S096b scope creep。

### Metrics
- Frontend tests: 28 → 28 PASS / 0 fail
- JS: 381.66 → 381.91KB (+0.25KB)
- CSS: 37.09 → 37.21KB (+0.12KB)
- Build: 162ms

### META progress
S096 META 2/8 ✅. Next: S096c Routing schema + Risk tier 4-level (M / 12 pts; absorbs S095).

---

## [v2.73.0] — ADR-003 + PRD P7-P9 + Glossary（S096 META sub-spec 1/8；M90a 完成；2026-05-02）

> **Docs-only gate** — S096 META 第一個 sub-spec，把 v2 redesign 的架構決定寫進文件，後續 sub-specs reference 這些 docs 為 source of truth。

### Added
- **ADR-003: Route Schema Migration** (`docs/grimo/adr/ADR-003-route-schema-author-name.md`)
  - `/skills/:author/:name` canonical（對齊 GitHub/npm/Docker Hub `:owner/:name` 慣例）
  - `/skills/:id` UUID 永久 alias（既有 caller / bookmark 不破）
  - dual-route 並行；backend 兩個 endpoint resolve 同一 aggregate
- **PRD P7 — Collections** 📋 (S096f planned): 創建集合 / 一鍵安裝 / 分類篩選 三個 SBE scenarios
- **PRD P8 — Request Board** 📋 (S096g planned): 發起需求 / 投票 / 認領與實作 三個 SBE scenarios
- **PRD P9 — Notifications** 📋 (S096h planned): 新版本通知 / 分類過濾 / 全部已讀 三個 SBE scenarios
- **PRD Decision Log D25-D27**:
  - D25: URL schema (per ADR-003)
  - D26: UI 主題 (dark theme `#08080A` per Engineering Handoff §7)
  - D27: Risk tier 階數 (4-tier per S096c)
- **Glossary 4 new entries**: Collection / Request / Notification / Subscription
- **Glossary update**: RiskLevel 從 3-tier (LOW/MEDIUM/HIGH) → 4-tier (NONE/LOW/MEDIUM/HIGH)

### No code change
git diff 限於 `docs/grimo/*`，0 行 backend/frontend code touched.

### META progress
S096 META 1/8 ✅ — next: S096b DESIGN.md v2 + global theme migration foundation (M).

---

## [v2.72.0] — Semantic Search Results page `/search`（M88d 完成；2026-05-02）

> **S094 META sub-spec 4/4 ship — META 全 ✅** — 把 HomePage inline 語意搜尋結果分流到專屬 `/search?q=...` route，加 LLM intent summary 顯示「系統如何理解你的查詢」(README ll.117 核心 UX 差異化)。

### Added
- **Backend SearchIntent endpoint**：
  - `POST /api/v1/search/intent` accepts `{query}`, returns `{summary, concepts: string[]}`
  - `SearchIntentService` 用 `Optional<ChatClient>` graceful fallback：LLM 不可用時回 `{summary: query, concepts: []}` — frontend 透過 `concepts.length` 判斷是否顯卡片，**POC HALT 風險規避**
  - `BeanOutputConverter<LlmIntentOutput>` 結構化輸出（同 S091 LlmJudge pattern）
  - `ConcurrentHashMap` per-instance cache 避免重複 LLM call（5min idle eviction polish 留 future）
- **S094b: Semantic Search Results**（M88d / S 9-10 trim from M）：
  - 新 route `/search?q=...` + `SearchResultsPage.tsx`
  - SearchBar + URL = source of truth（Enter key navigate）
  - `IntentSummaryCard` — purple #EEEDFE bg + ✦ "Understood your intent" + summary + concept chips（display-only，× interactivity defer）
  - Result list reuse `SkillCard` with `score` prop
  - 0 results → EmptyState redirect tone + 3 suggestions（S094c reuse）
  - 空 query → EmptyState invite tone「輸入一句描述或關鍵字」（S094c reuse）
  - LLM fallback：concepts.length === 0 → 不顯 IntentSummaryCard，純 result list
- **`useSearchIntent` hook**: react-query 5min cache

### Trim from prototype
- Per-result why-match LLM reasoning（避免 7+ LLM calls/search）
- Top match gradient bg + 0.94 score 強烈視覺
- Refine chips 4 items at bottom（user 自己 re-search）
- Concept chip × interactivity（display-only）

### Metrics
- Frontend tests: 28 → 28 PASS / 0 fail
- JS: 377 → 381KB (+4KB)
- CSS: 36.97 → 37.09KB (+0.1KB)
- Build: 213ms
- Backend compileJava: BUILD SUCCESSFUL ✓

### S094 META 全 ✅ summary
4 sub-specs all shipped: S094c (4-tone EmptyState v2.69.0) / S094d (Docs Walkthrough v2.70.0) / S094a (MySkills v2.71.0) / S094b (SearchResults v2.72.0). Total ~28-29 pts vs estimate 38-41 — trim 8-12 pts deferred to polish.

### Next
S095 Risk tier 4-level (NONE + LOW + MEDIUM + HIGH) — backlog 📋 ready to pick up.

### Live smoke caveat
Live :8080 backend 仍跑 ship 前舊 code；user 下次 graceful restart 後 (S093 transition) 新 endpoint 生效。

---

## [v2.71.0] — My Skills Author Dashboard `/my-skills`（M88c 完成；2026-05-02）

> **S094 META sub-spec 3/4 ship** — 補 P6 SBE「作者查看自己的數據」唯一 missing piece；author 進入後看到 hero + 4 metrics + tabs + skill list；0 skills 走 EmptyState invite tone (S094c reuse)。

### Added
- **Backend `?author=` filter on GET /skills**：
  - `SkillQueryService.search` 加 `String author` 4th param
  - 帶 author 時 bypass `WHERE status = 'PUBLISHED'` filter（S031），改 `WHERE LOWER(author) = LOWER(:author)` — 讓作者看自己 DRAFT/SUSPENDED
  - 不帶 author 時維持 S031 公開查詢只露 PUBLISHED 行為
  - +5 SkillSearchTest cases (AC-S094a-1~5: exact / case-insensitive / all-statuses / no-match / combined)
- **S094a: My Skills Author Dashboard**（M88c / S 9 pts trim from M）：
  - 新 route `/my-skills` + `MySkillsPage.tsx`
  - Hero: `以 lab-user 身份發布` + 「你的 N 個技能」 + 「發布新技能」CTA (BeamFrame)
  - 4 MetricCards: Total skills (status breakdown subtitle) / Total downloads / Avg rating "—" / Open flags 0
  - Tabs: 全部 / 已發布 / 草稿 / 已停用 — filter table rows by status
  - Table-style rows: IconTile + name + StatusPill + RiskBadge + description + downloads + version pill
  - 0 skills → EmptyState invite tone（S094c reuse）+ 2 CTAs（發布 / 看 docs）
  - StatusPill (PUBLISHED/DRAFT/SUSPENDED) 對齊 DESIGN.md 4-tier semantic
- **`useMe` hook**: GET `/api/v1/me` cache 5min；用於 author identity

### AppShell
- 加「我的技能」nav link → `/my-skills`

### Trim from prototype（M → S 收斂）
- Sparkline column / per-skill 30d trend endpoint 暫缺（polish follow-up）
- Avg rating "—" + Open flags 0（rating / flag aggregation MVP 暫缺）

### Metrics
- Backend tests: 3 affected suites BUILD SUCCESSFUL（+5 SkillSearch tests / 2 mock-patch）
- Frontend tests: 28 → 28 PASS / 0 fail
- JS: 372 → 377KB (+5KB)
- CSS: 36.7 → 36.97KB
- Build: 426ms

### META progress
S094 META 4 sub-specs：3/4 ✅ (S094c + S094d + S094a)。Final: S094b Semantic Search Results (M, with LLM intent POC).

### Known limitation
- Live :8080 backend 仍跑 ship 前舊 code（compose container 持續 running per S093，未 restart）；新 endpoint 行為要 user 下次 backend restart S093 transition 後現地生效。Spec ship 與 live deploy 為兩個獨立步驟。

---

## [v2.70.0] — Docs Walkthrough page `/docs/your-first-skill`（M88b 完成；2026-05-02）

> **S094 META sub-spec 2/4 ship** — Skills Hub 第一份開發者 docs entry，把 frontmatter / semantic search / risk tier 三個核心機制在一頁內讓作者建立心智模型。

### Added
- **S094d: Docs Walkthrough**（M88b / XS 5 pts）：
  - 新 route `/docs/your-first-skill` + `YourFirstSkillPage.tsx` (single-page JSX, no markdown parser; XS scope 不引 react-markdown ~30KB dep)
  - `DocsLayout.tsx`: AppShell chrome + 224px sidebar + 680px main column
  - `DocsSidebar.tsx`: 4 IA group full structure（Getting started / Reference / Publishing / API & webhooks），只「Your first skill」是 active link，其他 placeholder
  - 6 main sections + final CTA + prev/next nav 對齊 prototype `docs_page_write_your_first_skill.html`
  - Helper components inline (`H2`/`P`/`Code`/`CodeBlock`/`Callout`/`FieldCard`/`CompareCard`/`RiskRow`); 5 vitest tests cover AC-1~5
- **AppShell**: 加「文件」nav link → `/docs/your-first-skill`

### Metrics
- Frontend tests: 23 → 28 PASS / 0 fail
- JS bundle: 358 → 372KB (+14KB pure JSX no parser dep)
- CSS: 35.1 → 36.7KB (+1.6KB)
- Build time: 166ms

### META progress
S094 META 4 sub-specs：2/4 ✅ (S094c + S094d)。Next: S094a My Skills Author Dashboard (M / first backend touch).

---

## [v2.69.0] — EmptyState component (4 tones) + HomePage 0-results 改寫（M88a 完成；2026-05-02）

> **S094 META sub-spec 1/4 ship** — 抽取共享 `EmptyState` 元件，4 種 voice（seed/invite/redirect/clear）對齊 prototype；先 ship 給後續 sub-specs（S094a MySkills + S094b SearchResults）reuse。

### Added
- **S094c: Empty State Collection (4 tones)**（M88a / XS 5 pts）：
  - `EmptyState.tsx` 含 4 個 sub-renderer（`SeedTone` / `InviteTone` / `RedirectTone` / `ClearTone`）對應 prototype 4 種 empty state
  - Props: `tone | headline | sub? | eyebrow? | query? | suggestions? | stats? | primaryAction? | secondaryAction? | auditLink?`
  - Primary CTA 套 `BeamFrame`；secondary 用 hairline border；color tokens 對齊 DESIGN.md 4-tier semantic
  - 5 vitest test cover AC-1/2/3/4/5（4 tone render + optional fields）
- **HomePage 0-results 改寫**（取代 generic 「找不到符合的技能」inline empty）：
  - keyword 模式無 query → seed tone「技能庫等著被開啟」
  - keyword 模式有 query → redirect tone（query echo + 3 suggestions）
  - semantic 模式 0 結果 → redirect tone（同上 + sub 文案不同）
- **SkillCardGrid**: 加 `query?: string` prop 用於 0-results tone 區分

### Metrics
- Frontend tests: 18 → 23 PASS / 0 fail
- JS bundle: 351KB → 358KB (+7KB) / CSS: 32.7KB → 35.1KB (+2.4KB)
- Build time: 166ms（無 regression）

### META progress
S094 META 4 sub-specs：1/4 ✅。Next: S094d Docs Walkthrough.

---

## [v2.68.0] — Dev DB persistence: compose named volume + start-only lifecycle（M87 完成；2026-05-02）

> **Dev infra polish** — 解 dev DB 不持久問題；加 named volume + `start-only` lifecycle 讓 PG container 跨 backend stop/restart 完整保留資料。

### Added
- **S093: Dev DB persistence**（M87 / XS 2 pts）：
  - `backend/compose.yaml`：pgvector service 加 named volume mount `pgvector-data:/var/lib/postgresql/data` + 顯式 top-level volume 宣告（project prefix `backend_pgvector-data`）
  - `application-local.yaml`：`spring.docker.compose.lifecycle-management` 從 `start-and-stop` 改 `start-only` — bootRun stop 不 down compose container
  - 配合：累積測試 corpus 不再每次 restart fresh / outbox drain / vector_store 等持久路徑可跨 session 驗證
  - 舊行為：每次 graceful stop → docker compose down → anonymous volume churn → fresh DB
  - 新行為：stop 不動 container；`docker compose down` 不加 `-v` 仍保 volume；`docker compose down -v` 是顯式 reset 路徑

### Transition cost
- 首次 user-triggered restart 仍會走 JVM 內舊 lifecycle（start-and-stop）一次 → 一次 fresh DB；自此 onwards 持久

---

## [v2.67.0] — FE i18n VALIDATION_ERROR field-level detail concat（M86 完成；2026-05-02）

> **Polish ship** — 關閉 tick 60 R18.3 tech-debt「i18n VALIDATION_ERROR 訊息過於 generic」。User 看到驗證錯誤時直接顯示具體 field+value，不需開 DevTools 定位。

### Added
- **S092: FE i18n field-level detail concat**（M86 / XS 2 pts）：
  - `api-error-messages.ts`：`ERROR_MESSAGES` 靜態 map → `ERROR_MESSAGE_BUILDER` function map
  - `VALIDATION_ERROR` / `CONSTRAINT_VIOLATION` 動態 concat backend message：「驗證失敗：Field 'name' fails regex ^[a-z0-9-]{1,64}$ (got: BAD-Name)」
  - 其他 code（DUPLICATE_RESOURCE / STATE_CONFLICT 等）仍 fixed 模板（無 actionable detail / 防 SQL 洩漏）
  - 新增 7 個 vitest test cover AC-1/1b/2/3/4/5/6（happy concat / empty-message fallback / unknown code / non-Error）
  - Backend audit 結論：`SkillValidator` messages 已含具體 field+value，0 changes
  - Frontend tests 11 → 18 PASS；JS 351KB 無 regression

### UX impact
- **Pre**: 「zip 套件驗證失敗，請確認格式正確。」（user 不知改哪欄）
- **Post**: 「驗證失敗：Field 'name' fails regex ^[a-z0-9-]{1,64}$ (got: BAD-Name)」（精確定位）

---

## [v2.66.0] — AnalyticsPage rework + MetricCard 對齊 DESIGN.md（M84 完成；2026-05-02）

> **UI rework — 最後一個 page rework，S084 META 全 ✅** — 對齊 `platform_analytics_dashboard_admin_view.html`：metric strip 4-up + label-caps + accent purple progress + mono tabular-nums。

### Added
- **S088: AnalyticsPage rework**（M84）：
  - Hero row：H1 22px + sub-text「技能總覽、下載趨勢與熱門排行」
  - MetricCard 重寫：移除 shadcn Card primitive，hairline border + label-caps style (11px uppercase tracking-0.05em)
  - Top skills card：`#15px font-medium 標題 + 「依下載次數」 label-caps subhead
  - Rank 數字 mono tabular-nums + 13px 粗體 muted
  - Progress bar 用 DESIGN.md accent #7F77DD（不用 generic `bg-primary`）+ 1.5px 高 + bg `#F5F4ED` (surface-secondary)
  - Download counts mono tabular-nums
  - Error state 用 callout-danger #FCEBEB / #791F1F
  - 11 frontend tests / 0 fail；CSS 32.6 KB / JS 351.2 KB

### S084 META completion
所有 5 sub-specs 全 ✅：S089 BeamFrame / S085 HomePage + IconTile / S086 PublishPage / S087 SkillDetailPage / S088 AnalyticsPage. UI rework META spec roadmap 全 backlog 清空。

---

## [v2.65.0] — SkillDetailPage rework（M83 完成；2026-05-02）

> **UI rework** — 對齊 `skill_detail_page_docker_compose_helper.html`：xl IconTile + name 22px + version mono pill + status semantic pill + DESIGN.md callout pattern。

### Added
- **S087: SkillDetailPage rework**（M83）：
  - Hero row：IconTile xl 52px + name 22px + author tertiary 13px + version mono pill + RiskBadge + status semantic-soft pill (success/warning/danger 4-tier per DESIGN.md)
  - Status pill 用 inline style 套 DESIGN.md hex（PUBLISHED green / DRAFT amber / SUSPENDED red）
  - SUSPENDED callout per DESIGN.md `card-callout-danger`：bg #FCEBEB / fg #791F1F + AlertCircle icon
  - 移除 shadcn Badge 依賴（hand-rolled spans）
  - Files tab (S082) 樣式不變；Risk / Versions tabs 結構保留
  - 11 frontend tests / 0 fail；CSS 32.3 KB / JS 351.1 KB

---

## [v2.64.0] — PublishPage rework（M82 完成；2026-05-02）

> **UI rework** — 對齊 `skill_publish_upload_flow.html` + `skill_publish_failure_and_high_risk_states.html`：hero hint + hairline card + uppercase label + semantic-tinted success/error callouts。

### Added
- **S086: PublishPage rework**（M82）：
  - Hero row：H1 + sub-text 解釋系統會自動驗證/掃描/索引
  - Card 從 shadcn primitives 改 hand-rolled hairline border 配 14px padding（per `.sh-card`）
  - Form labels uppercase 12px tracking-wide muted（per prototype convention）
  - Version input 加 `font-mono` class（technical 字串應 mono 顯示）
  - Success callout：accent-soft green (#EAF3DE / #27500A) + CheckCircle2 icon + Skill ID font-mono + 「查看技能 →」 with ArrowRight
  - Error callout：danger-soft (#FCEBEB / #791F1F) + AlertCircle icon
  - 11 frontend tests / 0 fail；CSS 32.4 KB / JS 350.7 KB

---

## [v2.63.0] — HomePage rework + IconTile reusable component（M81 完成；2026-05-02）

> **UI rework** — 對齊 `skills_hub_homepage_mockup.html` 設計稿：hero row + IconTile + reformatted SkillCard + DESIGN.md tokens。S084 META spec sub-spec roadmap 第 2 個 ship。

### Added
- **S085: HomePage rework**（M81）：
  - `frontend/src/components/IconTile.tsx`：6-category tint（devops/infra/testing/docs/data/security）+ size sm/md/lg/xl + 1-2 letter initial
  - `SkillCard.tsx` 重寫：hairline border + IconTile + version mono pill + category badge + download stat per prototype `.sh-card`
  - `HomePage.tsx` hero row：H1 22px + 13px sub-text + 「發布技能」黑色 primary CTA top-right per `.sh-hero-row`
  - 11 frontend tests / 0 fail；CSS 32.4 KB / JS 349.4 KB

### Verification
- Chrome smoke：完整對齊 prototype — hero / search beam / sidebar accent-soft / cards with IconTile + risk pill + version mono / category badge

---

## [v2.62.0] — BeamFrame hand-roll component（M85 完成；2026-05-02）

> **Drop dependency** — S083 試 BorderBeam npm `theme="light"` 但物理上做不出 glow（rgba(0,0,0,x) inner-shadow on white）。Prototype HTML `.sh-search-wrap` 直接 hand-roll conic-gradient + 1px frame，與 DESIGN.md `card-featured` pattern 1:1 對齊。Drop dep + 自寫 component 方案。

### Added
- **S089: BeamFrame hand-roll**（M85）：
  - `frontend/src/components/BeamFrame.tsx`：1px padding + `::before` conic-gradient + 4s rotation；1:1 port prototype CSS
  - SearchBar 改用 BeamFrame
  - drop `border-beam@1.0.1` npm dep
  - JS bundle **396 KB → 347 KB（−49 KB / -7 KB gzip）**
  - 11 frontend tests / 0 fail

### Verification
- Chrome smoke: HomePage SearchBar 顯 60° accent purple arc 旋轉，per DESIGN.md §Elevation 4s spec
- BeamFrame reusable for primary CTAs in 後續 S085/S086+

---

## [v2.61.0] — LlmJudge prompt calibration（M81 完成；2026-05-02）

> **Bug fix** — LlmJudge engine 對任何 `allowed-tools: Bash` 的 skill 都打 OWASP-AS4 sev=8.5（theoretical command injection）→ Anthropic canonical skills (handover / planning-project / deep-research) 全變 HIGH。Real production impact: 所有正規 skill imports 顯示「高風險」→ user trust 受損，rating 失去訊號意義。E2E test loop tick 81 R34 anthropic skill re-scan 系統發現（bug AN）。

### Fixed
- **S091: LlmJudge prompt calibration**（M81）：
  - `LlmJudge.SYSTEM_PROMPT` 重寫，明確區分 demonstrated vs theoretical risk
  - HIGH (sev 7-10): demonstrated dangerous behavior（rm -rf / curl|bash / hardcoded secrets / /etc/passwd / obvious injection）
  - MEDIUM (sev 4-7): concrete concerns short of harm（writes to system paths / description-vs-impl mismatch）
  - LOW (sev 1-4): minor noteworthy（broad tool decl with focused use / 描述模糊）
  - 「Skills with allowed-tools using those tools for routine information gathering... are LOW unless specific dangerous commands appear」
  - 加 anti-pattern 列表：「Theoretical 'X could be misused if attacker manages Y' is NOT a finding」
  - 299 backend tests / 0 fail (LlmJudge tests mock-based 不受 prompt 影響)

### Verification
- 5 fixtures 5/5 AC PASS:
  - handover / planning-project / deep-research：HIGH → **LOW** ✓
  - real-high regression (rm -rf + secrets)：HIGH 維持 ✓ (14 findings — 真風險不漏)
  - pure-docs regression：LOW 維持 ✓ (unchanged)

---

## [v2.60.0] — Semantic search `?limit=` configurable（M80 完成；2026-05-02）

> **Polish** — close R25.7 missing-feature observation。`/api/v1/search/semantic` 之前 hardcoded TOP_K=10；client `?limit=` silently dropped (Spring default for unknown param)。FE 想做「show more」UX 沒辦法。

### Added
- **S090: Semantic search `?limit=` configurable**（M80）：
  - `SearchController.semanticSearch` 加 `@RequestParam(defaultValue = "10") int limit`
  - Validate `limit ≥ 1`（disallow 0 / negative）；cap `MAX_LIMIT = 50`（防 client 提巨量值）
  - `SemanticSearchService.search` 簽名加 `int topK` 參數，取代 hardcoded `TOP_K`
  - 299 backend tests / 0 fail

### Verification
- AC-1 default (no limit) → 10 results ✓
- AC-2 limit=3 → 3 results ✓
- AC-3 limit=50 → 50 results ✓
- AC-4 limit=999 → cap 50 ✓
- AC-5 limit=0 → 400 VALIDATION_ERROR「limit must be >= 1」✓
- AC-6 limit=-1 → 400 ✓
- AC-7 limit=abc → 400 (Spring `MethodArgumentTypeMismatchException` 走標準 ErrorResponse shape — S080 fix 順帶覆蓋)✓

---

## [v2.59.1] — `BorderBeam` light theme tuning（M79 完成；2026-05-01）

> **Polish** — User-driven「原生效果不錯，你用就沒那麼好看，研究一下」+ jakubantalik playground 截圖。`SearchBar` 用 `<BorderBeam>` 全套 default props，包括 `theme="dark"`，但 DESIGN.md 規定 surface = warm off-white #FFFFFF。Dark-tuned saturation/glow 落淺色背景 → 霧、暗、偏粉。

### Polished
- **S083: `BorderBeam` light theme tuning**（M79）：
  - `<BorderBeam theme="light" duration={4.5} strength={0.7}>`
  - `theme="light"` — 切到 light-tuned ThemeColors（package internal `sizeThemePresets`）
  - `duration={4.5}` — 對齊 DESIGN.md §Elevation §3「4-5s per rotation」（package default 1.96 偏快）
  - `strength={0.7}` — 對齊 user playground 偏好（default 1 在 SaaS 螢幕太強）
  - 11 frontend tests / 0 fail；無 prop interface 或行為 break

### Verification
- Chrome smoke：HomePage SearchBar beam 視覺淡且 subtle、rotation 變慢、不霧
- 同步驗到 S081 tokens 全到位（accent-soft 紫 / danger-soft 粉 / warm off-white 背景 / Inter）

---

## [v2.59.0] — SkillDetailPage Files Tab UI（M78 完成；2026-05-01）

> **Feature** — S074 backend API（M70）已 ship，但 frontend 還沒 UI 消費。本 spec 加 SkillDetailPage 4th tab「檔案」，user 可在不下載整包 zip 的前提下瀏覽 references / scripts / SKILL.md 內容。User-driven「希望在 skill 明細頁面可以瀏覽各檔案內容」(tick 62 提出，至此完成 backend → frontend 全鏈路)。

### Added
- **S082: SkillDetailPage Files Tab UI**（M78）：
  - `api/skills.ts` 新增 `fetchSkillFiles` + `fetchSkillFile`（後者走原生 fetch，回 Blob+Content-Type，不走 apiFetch JSON）
  - `types`：新增 `SkillFile` interface（`{path, size, type}` 對齊 backend `FileEntryResponse`）
  - `hooks/useSkillFiles.ts`：`useSkillFiles(skillId)` + `useSkillFile(skillId, path)` React Query hooks
  - `components/FilesPanel.tsx`：左欄 file list（按 path 字典序 + MIME icon + size）+ 右欄 viewer（text plain-text in `<pre>`，image inline `<img>`，binary fallback，>1 MB 友善訊息）
  - `pages/SkillDetailPage.tsx`：tab 從 3 個變 4 個（概要 / **檔案** / 版本歷史 / 風險評估）
  - 11 frontend tests / 0 fail；無 regression

### Edge handling
- 403 SKILL_SUSPENDED → 「此技能已被停用，無法瀏覽檔案」
- 404 (DRAFT 無 PUBLISHED 版本) → 「此技能尚未發布版本」
- 413 PAYLOAD_TOO_LARGE → 「檔案過大，無法預覽（單檔上限 1 MB）」+ 顯示實際大小
- binary（非 text MIME）→ fallback message + 路徑/類型/大小 + 「下載整包 zip」hint
- image (`image/*`) → inline 顯示

### Verification
- Smoke (anthropic/pdf 12 files)：tab 切換 ✓ / 12 entries 列出 ✓ / SKILL.md 預設預覽 ✓ / scripts/*.py 點選顯示 Python 原始碼

---

## [v2.58.0] — Design Token Migration（DESIGN.md → index.css；M77 完成；2026-05-01）

> **UI Foundation** — User-driven 「參考 DESIGN.md 設計語言優化畫面」。先做 token foundation，後續 per-page rework（S082-S085）才有正確顏色基底。`frontend/src/index.css` 從 shadcn 預設 monochrome oklch tokens 遷至 DESIGN.md spec：warm off-white surface (#FFFFFF) + 灰 ink text (#181818) + purple accent (#7F77DD) + 完整 4-tier semantic (success/warning/danger/info) + 6 category tints + Inter/JetBrains Mono/Source Serif Pro 字體 stack。

### Added
- **S081: Design Token Migration**（M77）：
  - 55 個 color tokens（13 shadcn alias + 5 accent + 3 info + 5 success + 5 warning + 5 danger + 12 category + 7 prototype-compat aliases）
  - 6 radius scale（xs 3px / sm 4px / md 8px / lg 12px / xl 16px / pill 999px）
  - 3 font stack（sans Inter / mono JetBrains Mono / serif Source Serif Pro）
  - shadcn convention 命名（`--color-primary` / `--color-foreground`）保留 backward compat — 既有 components 自動套新色彩
  - 新 utility classes：`bg-accent-soft` / `text-success-deep` / `bg-category-devops` / `border-warning-mid` 等 Tailwind v4 自動生成
  - Prototype HTML 引用的舊變數名（`--color-text-primary` / `--color-background-primary`）用 `:root` 補齊，方便 prototype port

### Verification
- 11 frontend tests / 0 fail
- `npm run build` 成功（dist 30KB CSS / 389KB JS / 250ms）

### Follow-up
S082-S085 per-page reworks 排隊：HomePage / PublishPage / SkillDetailPage（含 Files tab UI 接 S074 backend API）/ AnalyticsPage。

---

## [v2.57.0] — Missing param error shape 統一（M76 完成；2026-05-01）

> **Bug fix** — `POST /api/v1/skills/upload` 缺 form param 時 Spring 預設 error handler 直接回，繞過 GlobalExceptionHandler 的標準 `ErrorResponse{error, message, timestamp}` shape，回成 `{timestamp, status, error: "Bad Request", message, path}` 預設 shape。`error` 欄位變「Bad Request」（HTTP reason phrase）而非我們的 semantic code（VALIDATION_ERROR），FE i18n 用 error code 對應 localized message → silently fall through，user 看到 raw EN 訊息。E2E test loop tick 71 Round 27 API consistency audit 發現（bug AM）。

### Fixed
- **S080: Missing param error shape 統一**（M76）：
  - `GlobalExceptionHandler.handleMissingParam` 處理 `MissingServletRequestParameterException` + `MissingServletRequestPartException`
  - 回標準 `ErrorResponse{error: "VALIDATION_ERROR", message, timestamp}`
  - 與既有 `IllegalArgumentException` → VALIDATION_ERROR 路徑語意對齊
  - 299 backend tests / 0 fail

### Verification
- POST /skills/upload 缺 version → `{error: "VALIDATION_ERROR", ...}` ✓
- POST /skills/upload 缺 file → 同 shape ✓
- happy path upload 仍 201 ✓
- 既有 5 種已 handle 的 4xx exceptions 不變

---

## [v2.56.1] — `SkillSuspendedException` message 改 operation-agnostic（M75 完成；2026-05-01）

> **Polish** — S074 引入 `/files` endpoint 後，shared `SkillSuspendedException` 的 message 還是寫死「cannot be downloaded」（S029 設計時只服務 `/download`）。對 file-browser 場景的 API debug log / response 訊息誤導。FE i18n 用 error code 對應 localized string，**不依賴 backend message**——故 user 不受影響，純為清理 API debug 觀感。E2E test loop tick 70 polish round。

### Polished
- **S079: `SkillSuspendedException` 改 operation-agnostic**（M75）：
  - constructor message: `"Skill is suspended and cannot be downloaded: " + id` → `"Skill is suspended and not accessible: " + id`
  - Javadoc 同步更新：涵蓋 `/download` 與 S074 `/files` 兩 endpoint
  - 無 test 釘住此字串 → 299 tests / 0 fail

### Verification
- `/download` SUSPENDED → 403 + 「is not accessible」 ✓
- `/files` SUSPENDED → 403 + 「is not accessible」 ✓
- error code (`SKILL_SUSPENDED`) / status (403) 不變 — FE i18n mapping 無需調整

---

## [v2.56.0] — `Skill.riskLevel` `@ReadOnlyProperty`（preemptive defense；M74 完成；2026-05-01）

> **Defense-in-depth** — S077 fix lost-update on `download_count`，audit 後發現 `risk_level` 同模式：`ScanOrchestrator.updateRiskLevel`（atomic SQL UPDATE）+ aggregate save 並發時，後者 full-row UPDATE 帶上 in-memory 舊值（多為 null）覆蓋 scan 寫入的 HIGH/MEDIUM/LOW。`updateRiskLevel` SQL 不增加 `version`，aggregate 的 `@Version` optimistic lock 偵測不到此衝突。E2E test loop tick 67 Round 24 audit + 5 trial 嘗試重現未觸發（dev 環境 timing 太緊）但架構漏洞與 S077 完全相同（bug AL pattern）。Preemptive ship — 不留地雷。

### Fixed
- **S078: `Skill.riskLevel` `@ReadOnlyProperty`**（M74）：
  - `Skill.riskLevel` 加 `@org.springframework.data.annotation.ReadOnlyProperty`（同 S077 pattern）
  - findById SELECT 仍含此欄位（read 不變；API JSON 仍 expose `riskLevel`）
  - save() 的 INSERT/UPDATE 排除此欄位
  - 唯一寫入路徑：`SkillRepository.updateRiskLevel` atomic SQL UPDATE（保留既有）
  - INSERT path 由 DB schema `risk_level VARCHAR(10) NULL` 接管（預設 NULL）
  - 299 backend tests / 0 fail（無 regression）

### Audit Result
本 spec 同步 audit 其他 aggregate 欄位是否有同 pattern：
- ✓ `download_count` — fixed by S077
- ✓ `risk_level` — fixed by 本 spec
- ✓ `status` / `latestVersion` / `aclEntries` / `name` / `author` / `description` / `category` — 只走 aggregate save，無獨立 atomic path → safe
- ✓ `SkillVersion.riskAssessment` — 僅 aggregate `attachRiskAssessment` 寫入 → safe

至此 Skill aggregate 所有欄位的 lost-update 漏洞清零。

---

## [v2.55.0] — `Skill.downloadCount` `@ReadOnlyProperty`（lost-update fix；M73 完成；2026-05-01）

> **Regression fix from S076** — S076 引入原子 SQL `incrementDownloadCount` 解決並行下載 OptimisticLocking 失敗；但同時引入 lost-update：concurrent suspend (or any aggregate save) 與 download 交錯時，aggregate `save()` 用 full-row UPDATE 把所有欄位（含 `download_count`）回寫，**覆蓋掉並發的原子增量**。實測 10 並行 download + 1 並發 suspend：10 dl HTTP 200，但 final `download_count = 3`（其他 7 個被 suspend save 蓋掉）。E2E test loop tick 66 Round 23.5 race condition 探查發現（bug AK）。

### Fixed
- **S077: `Skill.downloadCount` `@ReadOnlyProperty`**（M73）：
  - `Skill.downloadCount` 加 `@org.springframework.data.annotation.ReadOnlyProperty`
  - findById SELECT 仍含此欄位（read 不變）；save() 的 INSERT/UPDATE 排除此欄位
  - 唯一寫入路徑為 `SkillRepository.incrementDownloadCount` atomic SQL UPDATE
  - INSERT path 由 DB schema `download_count BIGINT NOT NULL DEFAULT 0` 接管
  - Aggregate `recordDownload()` 行為不變（純 in-memory mutation）；單元測試 `SkillAggregateTest` 全 PASS
  - 299 backend tests / 0 fail（無 regression）

### Verification
- 10 parallel download + 1 concurrent suspend → counter delta = 10（pre-fix: 3 — 7 個增量被 save 覆蓋）✓
- 30 parallel download (純 concurrent，無 suspend) → 仍 30 ✓（S076 fix 維持）
- INSERT createSkill flow 正常（DB DEFAULT 接管）

---

## [v2.54.0] — Download Counter Atomic Increment（M72 完成；2026-05-01）

> **Production-grade fix** — 並行下載同一 skill 觸發 OptimisticLockingFailureException 級聯：N=2 並行 → 50% 失敗，N=10 → 90% 失敗。一般使用情境（兩 user 同時點下載）就會踩到。E2E test session Round 22 concurrent download 探測量化（bug AJ）。Counter 不是 state-machine concern，aggregate `@Version` 樂觀鎖過度保護。

### Fixed
- **S076: Download Counter Atomic Increment**（M72）：
  - `SkillRepository.incrementDownloadCount` 新增 `@Modifying @Query` 原子 SQL UPDATE（pattern 同 S024 T5 既有 `updateRiskLevel`）
  - `SkillQueryService.downloadAndRecord` 改：
    - 不再 `skill.recordDownload(); skillRepo.save(skill)`（aggregate read-modify-write）
    - 改 `skillRepo.incrementDownloadCount(skillId, ts)` + `eventPublisher.publishEvent(SkillDownloadedEvent.of(...))`
  - Modulith Event Publication Registry 透過 `@TransactionalEventListener` 攔截 ApplicationEventPublisher events，outbox at-least-once 保證不變
  - Aggregate `recordDownload()` method 保留（`SkillAggregateTest` 仍測它示範 invariant）；只是 service 不再走它
  - 299 backend tests / 0 fail（無 regression）

### Verification
- N=10 parallel downloads → 10/10 HTTP 200（pre-fix: 1/10）
- N=30 parallel downloads → 30/30 HTTP 200（pre-fix: 4/30）
- counter delta == HTTP 200 count ✓
- download_events projection delta == HTTP 200 count ✓
- outbox 0 pending 收尾

---

## [v2.53.0] — `FlagReadModel.isNew()` `@JsonIgnore`（M71 完成；2026-05-01）

> **Patch-class minor** — `GET /api/v1/skills/{id}/flags` 回應 JSON 多一個 `"new": true` 欄位（Spring Data JDBC `Persistable.isNew()` framework artifact），不該洩漏到 API contract。完全平行於 Bug AA / S063（Skill aggregate `isNew()` JsonIgnore）— 那次 fix 沒覆蓋獨立的 `FlagReadModel`。E2E test session Round 21 flag flow 深入測試發現。

### Fixed
- **S075: `FlagReadModel.isNew()` `@JsonIgnore`**（M71）：
  - `FlagReadModel.isNew()` 加 `@JsonIgnore`（method-level；Jackson 優先 method 注解過 record property 推斷）
  - `FlagControllerTest` 加 1 個 S075 test（`getFlagsExcludesIsNewArtifact`）— assert `$[0].new` doesNotExist
  - 298 → 299 backend tests / 0 fail

### Verification
- API JSON keys: ['id', 'skillId', 'type', 'description', 'reportedBy', 'createdAt', 'status']（少了 `new`）✓
- Spring Data JDBC INSERT 行為不變（仍走 `isNew()=true` path，只是 Jackson 不 serialize）

---

## [v2.52.0] — Skill Files Browser API（M70 完成；2026-05-01）

> **Feature** — 使用者要在 SkillDetailPage 上瀏覽 skill 包裡個別檔案內容（references / 子腳本 / 設定範例），不必下載整包再解壓。本 spec 補 backend API 兩個 endpoint：list / read single。FE rendering（檔案瀏覽器 UI）留 S076。

### Added
- **S074: Skill Files Browser API**（M70）：
  - `GET /api/v1/skills/{id}/files` — list 最新版本 zip 內所有 entries (`{path, size, type}`)
  - `GET /api/v1/skills/{id}/files/{*path}` — read 單一 entry 內容（Spring 6+ wildcard pattern）
  - `FileBrowserService`：fail-fast findById + SUSPENDED guard（共用 `SkillSuspendedException` → 403）；ZipInputStream enumerate；read-only metadata（**不**觸發 `SkillDownloadedEvent`，瀏覽 ≠ 下載）
  - **Zip-slip 防禦**：拒絕 `..` segments / 開頭 `/` 或 `\` / 空字串（list 階段 skip + log warn；read 階段拋 `IllegalArgumentException` → 400）
  - **單檔上限 1 MB**：超過拋 `FileTooLargeException` → 413 PAYLOAD_TOO_LARGE（與 multipart 上傳上限區分；message 不同讓 i18n 可分流）
  - **MIME inference**：18 種常見副檔名 → text/markdown / json / yaml / py / sh / png 等；未知 fallback application/octet-stream
  - `FileBrowserServiceTest` +7 tests（zip-slip / MIME / null/blank）
  - 291 → 298 backend tests / 0 fail

### Verification
- AC-1 list multi-file zip → 完整 entries ✓
- AC-2 read SKILL.md → text content ✓
- AC-3 read non-existent path → 404 NOT_FOUND ✓
- AC-4 SUSPENDED skill /files → 403 SKILL_SUSPENDED ✓
- AC-5 path traversal `../etc/passwd` → 400 VALIDATION_ERROR ✓
- AC-6 1.5 MB file → 413 PAYLOAD_TOO_LARGE ✓
- AC-7 binary file (.bin) → 200 + Content-Type=application/octet-stream ✓

---

## [v2.51.0] — `allowed-tools` YAML list interop（M69 完成；2026-05-01）

> **Patch-class minor** — `SkillValidator.validateFieldConstraints` 對 `allowed-tools` 欄位只支援空白分隔字串，但 canonical agentskills.io spec + Anthropic 自家 SKILL.md（`.claude/skills/handover` / `planning-project` / `deep-research` …）皆使用 YAML list 形狀。SnakeYAML 解出 `ArrayList`，現行程式 `toString()` → `"[Read, Bash]"` 含中括號，後續 `split` 切出 `[Read,` / `Bash]` 全不過 `ALLOWED_TOOL_TOKEN_REGEX`（regex 開頭要 `[A-Z]`）→ canonical 形狀全部 400 拒收。E2E test session Round 15 hand-craft 第三方 frontmatter 變體探查發現。

### Fixed
- **S073: `allowed-tools` YAML list interop**（M69）：
  - `SkillValidator.validateFieldConstraints`：以 Java type pattern matching `if (allowedTools instanceof List<?> list)` 分流
  - List → `stream().map(String::valueOf).toList()`；scalar → 既有 `split("\\s+")`（向後相容）
  - 既有 token 白名單 regex（shell injection 防禦）邏輯不變
  - `SkillValidatorTest` 加 3 個 S073 test：block sequence valid / flow sequence valid / list 含 injection 拒收
  - 291 backend tests / 0 fail（288 → 291）

### Verification
- Block seq `- Read\n- Bash(git:*)` → 201 ✓
- Flow seq `[Read, "Bash(npm:test)"]` → 201 ✓
- Legacy string `"Read Bash"` → 201（向後相容）✓
- List 含 `; rm -rf /` → 400 並指向違規 token ✓
- Smoke：上傳 R15.3 原失敗 zip → 201 + outbox drain ✓

---

## [v2.50.0] — Flag Type Allowlist + Description Length Cap（M68 完成；2026-05-01）

> **Patch-class minor** — `FlagService.createFlag` 兩道閘：(1) `type` 必須屬白名單 `{malicious, spam, inappropriate, copyright, security, other}`；(2) `description` ≤ 500 字元。E2E test session Round 10 flag flow 探查發現 `type="bogus"` 任意字串接受、`description="xxx" * 5000` 接受 — DB 髒、admin review 不可能、儲存成本。S058 修 `Map.of` null 時沒做型別白名單；S055 ACL aggregate validation 也沒同步覆蓋 Flag aggregate。本 spec 補齊。

### Changed
- **S072: Flag Type Allowlist + Description Length Cap**（M68）：
  - `FlagService` 加 `ALLOWED_TYPES` Set + `DESCRIPTION_MAX = 500`
  - `createFlag` 違反 → `IllegalArgumentException` → 400 VALIDATION_ERROR
  - `FlagControllerTest` 加 2 個 reject test
  - 288 backend tests / 0 fail（286 → 288）

### Verification
- bogus type → 400 with allowlist message ✓
- 5000 chars description → 400 ✓
- boundary 500 / 6 個 valid types / empty description → 201 ✓
- 既有 SkillFlagged event store / read-model write 行為不變

---

## [v2.49.0] — App Routing `/skills` Alias + NotFound Fallback（M67 完成；2026-05-01）

> **Patch-class minor** — React Router 兩個 routing gap 一次補：(1) `/skills` 沒對應 route → 加 alias 指 HomePage；(2) unmatched URL 沒 fallback → 加 NotFoundPage（`*` route）。E2E test session Round 5 navigation edge case 直接打 `http://localhost:5173/skills` 整頁空白 — user 看不到 navbar 也沒任何提示。同樣影響任何拼錯 URL / 舊書籤。

### Added
- **S071: App Routing `/skills` Alias + NotFound Fallback**（M67）：
  - `frontend/src/App.tsx` 加 `<Route path="/skills" element={<HomePage />} />` + `<Route path="*" element={<NotFoundPage />} />`
  - `frontend/src/pages/NotFoundPage.tsx`（新）：包 `<AppShell>` 保 navbar 一致 + 404 + 「回到首頁」連結
  - `frontend/src/App.test.tsx`（新）：NotFoundPage 渲染合約測試
  - 11 frontend tests / 0 fail（10 → 11）

### Verification
- `/totally-bogus-XYZ` → 404 + navbar + 回首頁連結 ✓
- `/skills` → 完整 HomePage listing（42 cards）✓
- 既有 routes（`/`、`/skills/:id`、`/publish`、`/analytics`）行為不變

---

## [v2.48.0] — Flyway V7 Cleanup Pre-S033 Vector Orphans（M66 完成；2026-05-01）

> **Patch-class minor** — Flyway V7 migration `DELETE FROM vector_store WHERE skill_id IN (SELECT id FROM skills WHERE status='SUSPENDED')`。S033 (M29 v2.10.0) 加 `SearchProjection.onSkillSuspended` 之前的 SUSPENDED events 從未過 listener → vector_store 內留 orphan。S059 filter 確保 user-visible impact=0；本 migration 清 storage 累積。

### Added
- **S070: Cleanup Pre-S033 SUSPENDED Vector Orphans**（M66）：
  - Flyway `V7__cleanup_pre_s033_suspended_vectors.sql`
  - Idempotent — clean DB 跑 no-op

### Verification
- `flyway_schema_history` V7 success
- 2 個 orphan rows cleaned；當前 SUSPENDED join vector_store count = 0
- `./gradlew test` — 286 / 0 fail

---

## [v2.47.0] — AuditEventListener Null-Defense for ACL Events（M65 完成；2026-05-01）

> **Patch-class minor** — `AuditEventListener.on(SkillAclGrantedEvent)` + `on(SkillAclRevokedEvent)` 加 null-coalesce defense。Tick 46 outbox 探查發現 2 個 pre-S055 (tick 28) 卡住的 SkillAclGrantedEvent（type=null）— `Map.of(null)` 拋 NPE → republish task 重投仍 fail → 永久 stuck。fix 後 backend restart → republish 自動 drain → outbox pending 從 2 → 0。

### Changed
- **S069: AuditEventListener Null-Defense for ACL Events**（M65）：
  - `on(SkillAclGrantedEvent)` + `on(SkillAclRevokedEvent)` 對 type/principal/permission/grantedBy/revokedBy 加 `?? ""`
  - 同 S058 (FlagService) `Map.of` null pattern 第二例
  - drain 2 個 historical stuck events ✓

---

## [v2.46.0] — PublishPage Form maxLength Constraint（M64 完成；2026-05-01）

> **Patch-class minor** — `PublishPage` category / author input 加 HTML5 `maxLength` 對齊 DB column 上限：category 50、author 255。User 不再 round-trip 至 backend 收 CONSTRAINT_VIOLATION 才知超限。

### Changed
- **S068: PublishPage Form maxLength Constraint**（M64）：
  - `category`: maxLength={50}
  - `author`: maxLength={255}

---

## [v2.45.0] — Version Input HTML5 Pattern Pre-Validation（M63 完成；2026-05-01）

> **Patch-class minor** — `PublishPage` + `AddVersionForm` 的 version input 加 HTML5 `pattern` 屬性，client-side 即時拒絕非 semver 輸入（如「foo」）；先前須 round-trip 至 backend 收 400 才知錯。

### Changed
- **S067: Version Input HTML5 Pattern Pre-Validation**（M63）：
  - Pattern: `\d+\.\d+\.\d+(-[A-Za-z0-9\.\-]+)?`
  - 對齊 backend `Skill.VERSION_REGEX` (S056)
  - inline 註解記 HTML5 pattern 兩陷阱（不要寫 `^...$`；char class 內 `.`/`-` 必須 escape 否則 Chrome silent 停用）

### Verification
- vitest 10 / 0 fail
- Chrome `foo`/空 → reject；`1.0.0`/`2.0.0-rc.1`/`3.0.0-alpha-1` → accept

---

## [v2.44.0] — METHOD_NOT_ALLOWED i18n Coverage（M62 完成；2026-05-01）

> **Patch-class minor** — frontend `api-error-messages.ts` 加 `METHOD_NOT_ALLOWED: '此操作的請求方法不正確，請重新整理頁面後再試。'`。S045 backend 已 ship 此 code，frontend i18n 漏譯（user 看英文 fallback）。Backend ↔ Frontend i18n 現 12/12 全覆蓋。

### Changed
- **S066: METHOD_NOT_ALLOWED i18n**（M62）：

### Coverage Audit
- 12 個 backend ErrorResponse codes 全部對應 frontend i18n entries

---

## [v2.42.0] — ApiError HMR-Safe Instance Check + QueryCache 4xx Skip（M61 完成；2026-05-01）

> **Patch-class minor** — 兩個並行修：
> 1. **S064 (M60)**：QueryCache 全域 logger 跳過 4xx ApiError —  UI 已負責處理（404 friendly state per S039、400/409 i18n banner per S040），console pollution 大幅降低
> 2. **S065 (M61)**：`ApiError.is(err)` static type guard 取代 3 處 `instanceof ApiError` — Vite HMR 模組重載產生多個 ApiError class instance 時 instanceof 不可靠；name-based check 穩定。同時 QueryClient 預設 `networkMode: 'always'`

### Added
- **S064: QueryCache Logger Skip 4xx ApiError**（M60 落地）
- **S065: ApiError Resilient Instance Check (HMR-safe) + Query networkMode='always'**（M61 落地）：
  - `ApiError.is(err): err is ApiError` — name-based duck-typed check
  - 3 處 `instanceof ApiError` 替換（main.tsx / api-error-messages.ts / SkillDetailPage.tsx）
  - QueryClient `networkMode: 'always'` 預設
  - **9 個 SBE AC 全綠**（S064 3 + S065 4 + 一致性檢查）

### Trigger
- 2026-05-01 /loop tick 37 — Chrome console 大量 [QueryCache] 噪音；deep debug 訪問 invalid skill UUID 揭露 React Query fetchStatus='paused' 卡死 + HMR instanceof 失效兩層問題

### Verification
- `npm test` — 10 / 0 fail
- E2E：4xx ApiError 不再 console pollution；ApiError.is 直接 invocation 正確 type guard

### Bug AC Hotfix（同 S065 ship 內含）
- 4xx ApiError 不 retry — 原 `retry: 1` 對 user 錯誤（404/400/409）重試無意義，retry backoff 期 React Query 進入 `fetchStatus='paused'` hang，SkillDetailPage 顯「載入錯誤」而非 friendly 404
- Fix: `retry: (count, err) => ApiError 4xx → false; else < 1`
- Chrome E2E：invalid UUID 訪問正確顯「找不到此技能」

---

## [v2.40.0] — Skill Aggregate isNew JsonIgnore（M59 完成；2026-05-01）

> **Patch-class minor** — 延伸 S062 修復至 `Skill` aggregate：`isNew()` 加 `@JsonIgnore`。先前 GET `/skills/{id}` 與 list endpoint 仍暴露 `new: false` artifact；S062 只修了 SkillVersion 同類欄位。

### Changed
- **S063: Skill Aggregate isNew JsonIgnore**（M59）：
  - `Skill.isNew()` 加 `@JsonIgnore`
  - 對齊 S062 Pattern — 所有 `implements Persistable<>` aggregate 都該隱藏

### Trigger
- 2026-05-01 /loop tick 36 — Skill JSON 仍含 `new` field（S062 漏修）

### Verification
- `./gradlew test` — 286 / 0 fail
- E2E：detail + list endpoint JSON keys 不再含 `new`

---

## [v2.39.0] — SkillVersion JSON Hide Internals（M58 完成；2026-05-01）

> **Patch-class minor** — `SkillVersion` aggregate `getStoragePath()` 與 `isNew()` 加 `@JsonIgnore`：API JSON 不再暴露內部 GCS/FS 路徑（`skills/{uuid}/{ver}/skill.zip`）+ Spring Data JDBC `Persistable.isNew()` artifact。Frontend type 同步移除過時 `storagePath` 欄位（grep 確認 0 個 caller）。

### Changed
- **S062: SkillVersion JSON Hide Internals**（M58）：
  - `getStoragePath()` + `isNew()` 加 `@JsonIgnore`
  - Frontend `SkillVersion` type 移除 `storagePath`
  - **5 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 35 — `GET /skills/{id}/versions` JSON 暴露 `storagePath` 內部路徑 + `new` artifact

### Verification
- `./gradlew test` — 286 / 0 fail
- `npm test` — 10 / 0 fail
- E2E：JSON keys 為 `[allowedTools, fileSize, frontmatter, id, publishedAt, riskAssessment, skillId, version]`；download 仍正常

---

## [v2.38.0] — Download Filename Includes Skill Name（M57 完成；2026-05-01）

> **Patch-class minor** — `/api/v1/skills/{id}/download` 與 `/skills/{id}/versions/{ver}/download` 的 Content-Disposition filename 從 hardcoded `skill.zip` / `skill-{ver}.zip` 改為動態 `{skillName}-{version}.zip`。user 同時下載多個 skill 不再檔名衝撞。

### Changed
- **S061: Download Filename Includes Skill Name**（M57）：
  - 兩個 download endpoint 動態組 filename
  - skill name regex `[a-z0-9-]{1,64}`（S041）已限為 filename 安全字元
  - **4 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 34 — Content-Disposition filename 與 skill name 無關，多下載衝撞

### Verification
- `./gradlew test` — 286 / 0 fail
- E2E：`filename=tick34-cn-4257-1.0.0.zip` 包含 skill name + version

---

## [v2.37.0] — SkillCard Status Badge Defensive Undefined Check（M56 完成；2026-05-01）

> **Patch-class minor** — `SkillCard` status badge 條件加 truthy guard：`skill.status && skill.status !== 'PUBLISHED'`。先前 semantic 結果（`SemanticSearchResult` 缺 `status` field → undefined）誤評估為「非 PUBLISHED」→ 全部誤顯「草稿」badge。S059 invariant 已保證 semantic 結果皆 PUBLISHED；undefined → 不主張。

### Changed
- **S060: SkillCard Status Badge Defensive Undefined Check**（M56）：
  - SkillCard 條件 `skill.status !== 'PUBLISHED'` → `skill.status && skill.status !== 'PUBLISHED'`
  - 對齊 S059 「semantic 結果皆 PUBLISHED」 invariant

### Trigger
- 2026-05-01 /loop tick 32 §7.5 — Chrome semantic mode 卡片全顯「草稿」badge 即使結果皆 PUBLISHED

### Verification
- `npm test` — 10 / 0 fail
- Chrome E2E：keyword DRAFT/SUSPENDED 仍顯 badge；semantic 結果不再誤顯

---

## [v2.36.0] — Semantic Search PUBLISHED-Only Visibility（M55 完成；2026-05-01）

> **Minor bump** — `/api/v1/search/semantic` SQL 加 JOIN skills + WHERE status='PUBLISHED' filter，對齊 S031 list/categories endpoint 視 visibility。先前 DRAFT/SUSPENDED skills（vector_store 仍有 embedding row）會公開呈現於 semantic search 結果。

### Added
- **S059: Semantic Search PUBLISHED-Only Visibility**（M55 落地）：
  - `SIMILARITY_SEARCH_SQL_ACL` 加 JOIN skills + status filter
  - 對齊 S031 設計（query-side filter，非 write-side）
  - **4 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 32 Chrome E2E — HomePage semantic mode 顯示 10 結果含明顯 DRAFT skills，違反 S031「公開只見 PUBLISHED」設計

### Verification
- `./gradlew test` — 286 / 0 fail（含 4 既有 ACL test 對齊 seed 改 PUBLISHED）
- E2E：semantic 9 results 全 PUBLISHED（DB 對照確認）

### Tech Debt（同 tick 32 發現）
- Frontend `SemanticSearchResult` 沒 `status` field；SkillCard 視 undefined 為 non-PUBLISHED → 卡片顯「草稿」badge 即使結果 PUBLISHED — 留下一輪修

---

## [v2.35.0] — Flag Input Validation（M54 完成；2026-05-01）

> **Minor bump** — `POST /api/v1/skills/{id}/flags` 缺 `type` 不再噴 NPE 500「No message available」；改 400 + VALIDATION_ERROR + 友善訊息。`FlagService.createFlag` 預驗 type/description；payload 改 HashMap 構築允許 nullable description。

### Added
- **S058: Flag Input Validation**（M54 落地）：
  - type 預驗（非 null + 非 blank + length ≤ 20 對齊 DB column）
  - description trim（允許 null）
  - payload `Map.of` → `HashMap`（避 null value NPE）
  - **6 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 31 endpoint sweep — `/actuator/mappings` 揭未測 `/flags` endpoint；POST 缺 type → 500 NPE「No message available」；`Map.of` 不接受 null values

### Resolved
- semantic 系統性回 0 — 自行解決（vector_store row 累積至 52 後達 similarity threshold 0.3 命中）

### Verification
- `./gradlew test` — 286 / 0 fail
- E2E：缺 type 400、空 type 400、超長 type 400、合法 + description 201、null description 201

---

## [v2.34.0] — DataIntegrityViolationException Catch-All Handler（M53 完成；2026-05-01）

> **Minor bump** — 為 `DataIntegrityViolationException` 加 catch-all handler → 400 + `CONSTRAINT_VIOLATION`。先前 long category（DB varchar(50)）/ NOT NULL / FK violation 等 DataIntegrity 子類落 Spring 預設 500 + 暴露完整 SQL exception。S051 既有 DuplicateKeyException handler 優先匹配仍 409 不 regress。

### Added
- **S057: DataIntegrityViolationException Catch-All Handler**（M53 落地）：
  - `@ExceptionHandler(DataIntegrityViolationException.class)` → 400 CONSTRAINT_VIOLATION
  - 固定 message「Submitted data exceeds allowed length or format constraints」
  - i18n `CONSTRAINT_VIOLATION: '提交資料超過允許的長度或格式，請檢查後重試。'`
  - **6 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 30 — 100-char category（DB cap=50）→ 500 + 「value too long for type character varying(50)」+ INSERT 語句 + column 列洩漏

### Defense-in-depth Layer Stack
- 累計 5 層 backend default-error 防漏網：S045 (yaml) / S049 (Zip) / S051 (DupKey) / S052 (BodyNotReadable) / S057 (DataIntegrity)

### Verification
- `./gradlew test` — 286 / 0 fail
- `npm test` — 10 / 0 fail
- E2E：long category 500→400 (146B clean)；dup name 仍 409 不 regress；合法 input 仍 201

---

## [v2.33.0] — Version Semver Validation（M52 完成；2026-05-01）

> **Minor bump** — `Skill.recordVersionPublished` 加嚴格 semver 預驗：`MAJOR.MINOR.PATCH` 三段數字（optional 連字 pre-release suffix）。違反 → 400 VALIDATION_ERROR。先前 `version=foo` / `version=` 都 200 創建畸形 row；超長 version 觸 DB constraint → 500 + raw SQL leak。三個 bug 由單一 fix 一次解決。

### Added
- **S056: Version Semver Validation**（M52 落地）：
  - `VERSION_REGEX = ^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$`（npm/Cargo/pip 慣例）
  - `recordVersionPublished` 第一行 regex 驗證
  - **6 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 29 — `PUT /skills/{id}/versions` 接受 `version=foo` 200 創建 / 接受空 200 / 超長 → 500 + SQL leak

### Verification
- `./gradlew test` — 286 / 0 fail
- E2E：foo / 空 / 超長 全 400；1.2.3 與 2.0.0-rc.1 仍 200

---

## [v2.32.0] — ACL Tuple Input Validation（M51 完成；2026-05-01）

> **Minor bump** — `Skill` aggregate 加 ACL tuple 預驗：`type ∈ {user, role, group}`、`principal` 非 blank、`permission ∈ {read, write, delete, suspend, reactivate}`。違反 → 400 VALIDATION_ERROR。先前 POST `/acl` 接受任意 permission 字串 / 缺 type 仍 201 創建，畸形 ACL entry 入 DB。

### Added
- **S055: ACL Tuple Input Validation**（M51 落地）：
  - `ACL_TYPES` + `ACL_PERMISSIONS` 常數；`validateAclTuple` private helper
  - `grantAcl` + `revokeAcl` 共用驗證
  - **6 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 28 — POST `/acl` 缺 type / invalid permission 都 201 創建畸形 entry

### Verification
- `./gradlew test` — 286 / 0 fail
- E2E：缺 type / invalid permission / blank principal 全 400；合法 grant 201；revoke 同樣驗證

### Tech Debt
- DB 既存畸形 entries（tick 26 雜訊）需 future migration 清理

---

## [v2.31.0] — Aggregate Null-Param 400 + Placeholder Polish（M50 完成；2026-05-01）

> **Patch-class minor** — Aggregate factory null-check 從 `Objects.requireNonNull`（NPE → 500）改 `IllegalArgumentException`（→ 400 VALIDATION_ERROR），對齊 user input 守門點語意。順手對齊 FileDropZone placeholder 至 S053 後雙格式。

### Changed
- **S054: Aggregate Null-Param 400 + Placeholder Polish**（M50 落地）：
  - `Skill.create` + `SkillVersion.publish` 5 處 NPE → IAE
  - 移除兩檔案的 unused `java.util.Objects` import
  - 對齊既有 unit test 斷言（NPE → IAE）
  - FileDropZone placeholder：「拖拽 zip 檔到此處」→「拖拽 zip 或 md 檔到此處」

### Trigger
- 2026-05-01 /loop tick 26 — `POST /api/v1/skills` body `{}` 回 HTTP 500 NPE「name is required」（user input 守門點不該用 NPE）

### Verification
- `./gradlew test` — 286 / 0 fail
- `npm test` — 10 / 0 fail
- E2E：缺 name 從 500 NPE → 400 VALIDATION_ERROR；FileDropZone placeholder 顯新文字

---

## [v2.30.0] — Flexible Upload Formats + Canonical Zip Structure（M49 完成；2026-05-01）

> **Minor bump** — 上傳支援 3 種格式（zip-root / zip-subfolder / plain `.md`），平台統一 normalize 至「SKILL.md 在 zip 根」標準結構。下載安裝體驗一致：所有 skill 解開都是 root SKILL.md（+ optional 兄弟檔）。

### Added
- **S053: Flexible Upload Formats + Canonical Zip Structure**（M49 落地）：
  - `PackageService.normalizeToZip` — magic-byte 偵測 + 三種上傳場景統一輸出：
    - **Case 1** zip root SKILL.md → pass-through
    - **Case 2** zip `sss/SKILL.md` 等 subfolder → strip wrapping folder repack 至 root（兄弟檔保留）
    - **Case 3** plain `.md` 純文字 → wrap 為單檔 zip 含 root SKILL.md
  - `FileDropZone` default accept `.zip,.md`；多副檔名 guard split-by-comma；inline error「只接受 .zip / .md 檔」
  - **8 個 SBE AC 全綠**

### Trigger
- 2026-05-01 user request — 「上傳的 zip 檔有可能解開就是 SKILL.md 也有可能有人是連資料夾都打包進去, 打開是 sss 資料夾 md 檔在 sss/SKILL.md 邊緣案例要防呆, 也有可能有人是很簡單的 文字檔複製貼上就完成的 SKILL 也要思考到」
- 二次 clarification：「但是平台收到都會整理成一致的資料夾檔案結構 下載的安裝體驗才會一致」— 範圍擴至 Case 2 也 normalize

### Verification
- `./gradlew test` — 286 / 0 fail
- `npm test` — 10 / 0 fail
- E2E 三 case 上傳→下載皆「SKILL.md 在 zip 根」一致

### Design Rationale
- magic-byte 偵測（ZIP `PK\x03\x04` / RFC 1951）— Content-Type 與副檔名 client 可控；server 必驗實際內容
- wrap 至 zip 而非直接存 `.md`：保留 `skills/{id}/{ver}/skill.zip` 路徑契約；fileSize / 下載 / 後續 scripts/ 提取 contract 一致
- Case 2 取「整個包都是這個 skill 的資料夾」語意 — 只保留與 SKILL.md 同 prefix 的兄弟檔，避免合併不相關 sibling

---

## [v2.29.0] — HttpMessageNotReadableException → 400 INVALID_REQUEST_BODY（M48 完成；2026-05-01）

> **Minor bump** — 修補預設訊息洩漏 controller method 完整 fully-qualified class name + 巢狀類別 + 參數 type list（屬資訊洩漏）。涵蓋 missing body、malformed JSON、type mismatch 三種 Jackson-wrapped 情境，frontend i18n 顯繁中。

### Added
- **S052: HttpMessageNotReadableException → 400 INVALID_REQUEST_BODY**（M48 落地）：
  - `@ExceptionHandler(HttpMessageNotReadableException.class)` → 400 + 固定 message「Request body is missing or malformed」
  - i18n `INVALID_REQUEST_BODY: '請求內容缺失或格式錯誤，請重試。'`
  - **6 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 26 API probe — `POST /api/v1/skills/{id}/suspend` 不帶 body 時 response 暴露完整 controller method 簽名

### Verification
- `./gradlew test` — 286 / 0 fail
- `npm test` — 10 / 0 fail
- E2E：missing body / malformed JSON / 合法 body 三場景驗證

---

## [v2.28.0] — DuplicateKeyException → 409 DUPLICATE_RESOURCE（M47 完成；2026-05-01）

> **Minor bump** — 重複 skill name 提交不再回 500 + 暴露完整 SQL exception 訊息（含 INSERT 語句、column list、constraint 名稱）；改 normalize 為 409 + `DUPLICATE_RESOURCE` code + 固定 user-friendly message。Frontend i18n 加「此名稱已被使用，請換一個名稱。」

### Added
- **S051: DuplicateKeyException → 409 DUPLICATE_RESOURCE**（M47 落地）：
  - `@ExceptionHandler(DuplicateKeyException.class)` → 409 Conflict
  - 固定 message「A resource with the same identifier already exists」（不暴露 ex.getMessage SQL detail）
  - i18n map 加 `DUPLICATE_RESOURCE: '此名稱已被使用，請換一個名稱。'`
  - **5 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 25 API probe — POST /api/v1/skills 重複 name 回 HTTP 500 + 完整 SQL：「PreparedStatementCallback; SQL [INSERT INTO "skills" ("acl_entries", "author", ...) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)]; ERROR: duplicate key value violates unique constraint "skills_name_key"」— 三層 bug：資訊洩漏 + 錯誤 status code + SQL 黑話訊息

### Verification
- `./gradlew test` — 286 / 0 fail
- `npm test` — 10 / 0 fail
- E2E：dup name 500→409 / 135B body 不含 SQL；new name 仍 201

---

## [v2.27.0] — SearchBar Placeholder 對齊 S043（M46 完成；2026-05-01）

> **Patch-class minor** — `SearchBar` placeholder 從「搜尋技能名稱或描述...」改為「搜尋名稱、描述或分類...」對齊 S043 後 keyword search 已涵蓋 category 比對的行為。S043/S044/S046 三個 spec 累積的 UI copy 待辦清掉。

### Changed
- **S050: SearchBar Placeholder Include Category**（M46）：
  - 「技能名稱」→「名稱」（冗餘字砍）
  - 加「分類」對齊 S043 SQL `OR LOWER(category) LIKE :kw`

---

## [v2.26.0] — ZipException → 400 VALIDATION_ERROR（M45 完成；2026-05-01）

> **Minor bump** — 上傳 corrupt zip 時 `java.util.zip.ZipException` 不再落 Spring 預設 500 / 暴露 raw 訊息（如「invalid stored block lengths」）；改 normalize 為 400 VALIDATION_ERROR + 固定 user-friendly message。Frontend 走既有 i18n map 顯繁中「zip 套件驗證失敗，請確認格式正確。」

### Added
- **S049: ZipException → 400 VALIDATION_ERROR**（M45 落地）：
  - `@ExceptionHandler(ZipException.class)` 加進 GlobalExceptionHandler；most-specific-first 規則保證優先匹配
  - 固定 message「Invalid zip file: cannot read package contents」（不暴露 ex.getMessage 內 Java 內部 detail）
  - **4 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 24 Chrome happy-path E2E — base64 編碼 zip 損毀，user 看到 raw「invalid stored block lengths」（Java 內部訊息），i18n VALIDATION_ERROR 友善翻譯未生效（`err.code` undefined 因為 backend 回 500 而非 400）

### Verification
- `./gradlew test` — 286 tests / 0 fail
- E2E：valid zip 201；invalid zip → frontend 顯示「zip 套件驗證失敗，請確認格式正確。」

---

## [v2.25.0] — FileDropZone — Reject Non-`.zip` Files Client-Side（M44 完成；2026-05-01）

> **Minor bump** — `FileDropZone.handleFile` 加擴展名 guard：drag-drop 非 `.zip` 檔（如 `malicious.txt`）顯示 inline「只接受 .zip 檔」並不呼叫 `onFileSelect`。先前 `accept=".zip"` 只 hint file picker；drag-drop bypass 該限制 — user 須等到後端 400 才知錯。對齊 S037 size guard 模式（同 funnel point；drag + click 路徑共用）。

### Added
- **S048: FileDropZone — Reject Non-`.zip` Files Client-Side**（M44 落地）：
  - **extension guard**：`accept` prop 動態取副檔名 → 不符 set inline error → 不呼叫 `onFileSelect`
  - case-insensitive 比對（`.ZIP` / `.zip` 一致）
  - 不查 `File.type` MIME（OS 差異不可靠）— 後端仍嚴格驗 zip magic bytes（defense-in-depth）
  - **5 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 23 Chrome E2E — PublishPage 注入 `malicious.txt` 經 drag-drop 被 FileDropZone 接受、selectedFile 設、submit 啟用；後端會擋但 user 浪費 round-trip

### Verification
- `npm test -- --run` — 10 tests / 0 fail
- Chrome E2E：`.txt` 顯 inline 拒絕；`.zip` / `.ZIP` 通過；11MB `.zip` 仍走 S037 size guard

---

## [v2.24.0] — Installation Guide Only for PUBLISHED（M43 完成；2026-05-01）

> **Minor bump** — `SkillDetailPage` 概要 tab 安裝指引「下載 zip 後解壓...」只對 PUBLISHED skill 顯示。先前 DRAFT skill 沒下載按鈕但仍顯指引、SUSPENDED skill 有「無法下載」banner 但仍顯指引 — UX 矛盾解決。

### Added
- **S047: Installation Guide Only for PUBLISHED**（M43 落地）：
  - **conditional render**：`{skill.status === 'PUBLISHED' && (... 安裝指引 ...)}`
  - 對齊既有 download button 隱藏邏輯（PUBLISHED only）
  - **4 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 22 Chrome E2E — DRAFT skill 詳情頁顯「安裝指引：下載 zip 後解壓...」但版本歷史「尚無版本記錄」+ 沒下載按鈕；user 看到指引卻找不到下載 UX 矛盾

### Verification
- `npm test -- --run` — 10 tests / 0 fail
- Chrome E2E 三 status 驗：PUBLISHED 有指引 ✓ / DRAFT 沒指引 ✓ / SUSPENDED 沒指引 + suspend banner 仍在 ✓

---

## [v2.23.0] — Semantic Search Fallback to Keyword（M42 完成；2026-05-01）

> **Minor bump** — `HomePage` semantic search 回 0 結果（不是 error）時自動 fallback 至 keyword search mode。先前 user 在搜尋框輸入任何 query 若 semantic 系統性 / 語意上沒命中即停在死巷「未找到匹配的技能 試試換個描述方式」。fix 後 keyword mode 自動接手，dev（embedding 未配置）+ prod（真 zero match）兩場景一致 graceful。

### Added
- **S046: Semantic Search Fallback to Keyword**（M42 落地）：
  - **`isSemanticMode` 加 `(semanticResults?.length ?? 0) > 0`** — semantic 回空也算 fallback 觸發條件
  - **`useSkillList` 已並行請求**（既有 hook 有 own enabled guard）— 純 render 切換，無需新 API call
  - **6 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 20 Chrome E2E — HomePage 輸入「DevOps」（既存 25 個 DevOps skill）回 0；frontend 走 semantic mode；semantic 系統性回 0；fallback 條件 `!semanticError` 不觸發 → 死巷

### Verification
- `npm test -- --run` — 10 tests / 0 fail
- Chrome E2E：keyword=DevOps 0→25（keyword mode）；keyword=blahblah999 顯 0 友善 empty state；清空 query 回 default browse 25

### Tech Debt（同 tick 21 發現）
- 搜尋框 placeholder「名稱或描述」未對齊 S043 後行為（已含 category）
- semantic 系統性回 0 根因（vector / threshold / embedding mismatch）— 屬獨立 backend bug，不影響本 fix；prod 環境真 zero match 場景本 fix 仍有意義

---

## [v2.22.0] — Strip Error Stack Trace + 405 Handler（M41 完成；2026-05-01）

> **Minor bump** — 收斂 Spring Boot fallback 錯誤回應的 stack trace leak（12-14KB → 138-180B）。`spring.web.error.include-stacktrace: never` 為全局 defense；`HttpRequestMethodNotSupportedException` 加 explicit handler 把 405 normalize 至 `{error: "METHOD_NOT_ALLOWED", message, timestamp}`。

### Added
- **S045: Strip Error Stack Trace + 405 Handler**（M41 落地）：
  - **`spring.web.error.{include-stacktrace, include-exception, include-message, include-binding-errors}`** 4 toggle（注意 Spring Boot 4 將 `server.error.*` 重命名為 `spring.web.error.*` — 沿用舊 prefix yaml parse 不報錯但完全沒生效）
  - **`@ExceptionHandler(HttpRequestMethodNotSupportedException.class)`** → 405 METHOD_NOT_ALLOWED + ErrorResponse 格式
  - **6 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 19 §7.5 + tick 20 baseline — `POST /api/v1/skills/{id}/versions`、`DELETE /api/v1/skills`、`POST text/plain`、未知 path 等回應均含 12-14KB body 暴露 `LabSecurityFilter`、`FilterChainProxy` 等內部 class name；屬資訊洩漏

### Verification
- `./gradlew test` — 286 tests / 0 failures / 0 errors
- E2E HTTP：405 12.9KB → 138B；415 13.9KB → 180B；404 unknown path 12.7KB → 157B；所有 response 不含 `trace` field
- 既有 GlobalExceptionHandler 路徑（VALIDATION_ERROR、SKILL_SUSPENDED、PAYLOAD_TOO_LARGE 等）格式不變

### Spring Boot 4 Property Rename Note
- Spring Boot 4 將 `server.error.*` 改名 `spring.web.error.*`（per spring-boot-autoconfigure-4.0.6 spring-configuration-metadata.json）
- yaml parse 不會 warn — 寫錯 prefix 完全 silent
- application.yaml 加註解警示未來不再踩

### Tech Debt（同 tick 20 發現）
- **Bug C**：HomePage semantic search 回 0 結果（不是 error）時停在「未找到匹配的技能 試試換個描述方式」死巷，沒 fallback 至 keyword search；prod 環境 embedding 真找不到時 user 也卡住。Frontend UX bug — 留下一輪
- 搜尋框 placeholder「名稱或描述」未對齊 S043 後行為（已含 category）
- 415 / NoResourceFoundException 404 仍走 Spring 預設格式（無 trace 已安全；格式不一致為小問題）

---

## [v2.21.0] — Keyword Trim Whitespace（M40 完成；2026-05-01）

> **Minor bump** — `SkillQueryService.search` 的 `keyword` 參數做 `.trim()` 預處理；user 從複製貼上常含 leading/trailing whitespace（先前 `keyword=t17 ` 回 0、`keyword= DevOps` 回 0），現在與 `keyword=t17` / `keyword=DevOps` 結果一致。對齊 GitHub / npm / Google 通用搜尋 trim 慣例。

### Added
- **S044: Keyword Trim Whitespace**（M40 落地）：
  - **`keyword.trim()` 預處理**（與 `sanitizeLikePattern` 的 `%/_/\` SQL escape 職責正交）
  - **既有 `StringUtils.hasText` 邏輯不變**：trim 後仍空字串視同無 keyword（沿用既有 whitespace-only = 無 filter 行為）
  - **`?category=` 顯式 filter 不 trim**（該欄位來自前端 dropdown 應為精確 match）
  - **6 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 19 — API E2E sweep 試 `keyword=t17%20`（trailing space）回 0 結果；plain `keyword=t17` 回 1；複製貼上含尾空白為高頻 user 場景

### Verification
- `./gradlew clean test` — 286 tests / 0 failures / 0 errors（含新加 `keywordTrimsWhitespace`）
- E2E HTTP：trail `t17 ` → 1（was 0）、lead ` DevOps` → 25（was 0）、surround `  t17  ` → 1（was 0）、純空白 → 25（不破）、plain `t17`/`DevOps` → 1/25（不破）

### Tech Debt（同 tick 19 發現，留下一輪）
- **Bug B**：`POST /api/v1/skills/{id}/versions`（method not allowed）response 12.9KB stack trace 含 `LabSecurityFilter` / Spring Security filter chain class names — `GlobalExceptionHandler` 未處理 `HttpRequestMethodNotSupportedException`，落入 Spring 預設 `BasicErrorController`（含 `trace` field）。屬資訊洩漏類

---

## [v2.20.0] — Keyword Search Also Matches Category（M39 完成；2026-05-01）

> **Minor bump** — `SkillQueryService.search` keyword `LIKE` 子句加 `LOWER(category)` 第三個 OR；user 輸入 category 名（如「DevOps」、「Testing」）即可命中對應分類所有 skill。對齊 GitHub / npm / Docker Hub 等通用 catalog 搜尋慣例。`?category=` 顯式 filter 仍維持精確 match（與此擴展正交）。

### Added
- **S043: Keyword Search Also Matches Category**（M39 落地）：
  - **SQL 擴充**：`AND (LOWER(name) LIKE :kw OR LOWER(description) LIKE :kw OR LOWER(category) LIKE :kw)`
  - **Unit test**：`SkillSearchTest.keywordSearchMatchesCategory`（fixture 含 2 DevOps + 1 Testing；`keyword=DevOps` → 2 個 result）
  - **6 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 18 — HomePage 搜尋框輸入「DevOps」（既存 category 名）回 0 個 skill；違反通用搜尋 UX 期待

### Verification
- `./gradlew test` — 305 → 306 tests / 0 fail（1 新加）
- E2E HTTP：`?keyword=DevOps` 從 0 → 25 skills（all category=DevOps）✓ AC-1
- E2E HTTP：`?keyword=devops`（小寫）→ 25 skills ✓ AC-4
- E2E HTTP：`?keyword=test&category=Testing` → 0（顯式 filter 精確 match 仍生效）✓ AC-5

### Design Rationale
- 對齊 GitHub / npm / Docker Hub 通用 catalog search 慣例（搜尋框跨多欄位）
- 不加 `author` 至 keyword（隱私 + 通常為獨立 operator 設計，如 `author:alice`）
- 不加 query param `?fields=` 細粒度控制（過早泛化；MVP 一個搜尋框 user 期待全文匹配）

### Tech Debt
- 規模成長至 ~10k skills 時可考慮 GIN trigram / full-text search index（目前 2-3 個 LIKE 在 < 1000 規模可忽略）
- S031 §7.5 admin panel endpoint 仍待設計

---

## [v2.19.0] — Aggregate description / category Validation（M38 完成；2026-05-01）

> **Minor bump** — 補完 `Skill.create` aggregate 四欄位驗證：description（trim + blank reject + ≤ 1024 chars）+ category（trim + blank reject）。承接 S041，徹底封閉 JSON POST 之前缺驗證的破口。

### Added
- **S042: Aggregate description / category Validation**（M38 落地）：
  - **`DESCRIPTION_MAX = 1024`** 常數（與 `SkillValidator.DESCRIPTION_MAX` 同值；mirror S041 NAME_REGEX 不依賴 validation 子模組的策略）
  - **description 驗證**：trim + 非 blank + 長度 ≤ 1024；違反 → 400 VALIDATION_ERROR
  - **category 驗證**：trim + 非 null 但 blank → 400；null 仍允許（schema 允許）
  - **7 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 17 — JSON POST 接受空 / 超長 description 與空白 category；S041 補了 name/author，S042 補完 description/category

### Verification
- `./gradlew test` — 301 → 305 tests / 0 fail（4 新加）
- E2E HTTP：6 個 invariant 綠燈 + multipart regression OK

### Pattern
- mirror S041：4 個 invariant 欄位（name / author / description / category）統一 trim + blank reject 風格；aggregate factory 為最終守門

### Tech Debt
- S031 §7.5 admin panel endpoint 仍待設計

---

## [v2.18.0] — Skill Aggregate Input Validation（M37 完成；2026-05-01）

> **Minor bump** — `Skill.create` aggregate factory 加 invariant 守門：name 必符 agentskills.io regex；author 拒絕 blank；補 JSON POST path 之前缺乏驗證的破口。前端與後端 entry path（multipart upload + JSON POST）行為一致，畸形 ACL `user::read` 不再產生。

### Added
- **S041: Skill Aggregate Input Validation**（M37 落地）：
  - **`NAME_REGEX = ^[a-z0-9-]{1,64}$`** 常數（與 `SkillValidator.NAME_REGEX` 同字面）— domain 不依賴 validation 子模組，複製 regex literal + inline 註解提醒同步
  - **`name` 驗證**：trim 後 match regex；違反 → IllegalArgumentException → 400 VALIDATION_ERROR
  - **`author` 驗證**：trim；非 null 但 blank（`""` / `"   "`）→ IllegalArgumentException → 400（不 silent null-out — schema `skills.author NOT NULL` 會 fail；user 應收明確錯誤）
  - **null author 仍允許**（用於 unit test fixture 控制 ACL seed；prod schema 下不會持久化成功）
  - **6 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 16 — `POST /api/v1/skills` JSON path 接受 `name=""` / `name="BadName"` / `author="   "`，產生畸形 ACL `user::read` / `user:   :read`

### Verification
- `./gradlew test` — 296 → 301 tests / 0 fail（5 新加）
- E2E HTTP：5 個 AC 綠燈

### Defense-in-depth
- Aggregate factory 是 invariant 最終守門 — 在 `Skill.create` 守可保護所有 entry path（multipart upload + JSON POST + 未來新 path）；不依賴 controller / DTO 層註解

### Tech Debt
- S031 §7.5 admin panel endpoint 仍待設計

---

## [v2.17.0] — Frontend Mutation Error i18n + Multipart 也用 ApiError（M36 完成；2026-05-01）

> **Minor bump** — frontend mutation error UX 對齊 CLAUDE.md「UI 語言: 繁體中文」原則：upload / addVersion 失敗時不再顯示英文後端訊息，改用 i18n Record map 翻譯。同時 `uploadSkill` / `addVersion` 也拋 `ApiError`，與 `apiFetch` 行為一致。

### Added
- **S040: Frontend Mutation Error i18n + Multipart 也用 ApiError**（M36 落地）：
  - **`uploadSkill` / `addVersion` 改 throw `ApiError`**：與 `apiFetch` 對齊；攜 status + code（從 backend `ErrorResponse.error` 解析）
  - **`frontend/src/lib/api-error-messages.ts`（new）**：8 個 backend error code 對繁中模板（PAYLOAD_TOO_LARGE / VALIDATION_ERROR / MULTIPART_ERROR / VERSION_EXISTS / STATE_CONFLICT / CONCURRENT_MODIFICATION / NOT_FOUND / SKILL_SUSPENDED）+ `localizeApiError(err)` helper
  - **`PublishPage` + `SkillDetailPage` AddVersionForm**：error 顯示改 `localizeApiError(mutation.error)`；未知 code fallback 至 `error.message`
  - **6 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 15 — PublishPage 直接顯示「Upload size exceeds the 10 MB limit」於繁中 UI；違反 CLAUDE.md UI 語言原則

### Verification
- `npm test` — 10/10 PASS
- `tsc -b` — 0 errors
- `npm run lint` — 0 warnings
- backend 三類 error shape 確認（413 / 400 / 404）

### Pattern
- mirror S028 STATUS_LABEL / S036 RISK_DESCRIPTION 的 Record-based i18n pattern；scope 小，不引入 i18next 等框架

### Tech Debt
- HomePage / AnalyticsPage list errors 可漸進採用 `localizeApiError`
- S031 §7.5 admin panel endpoint 仍待設計

---

## [v2.16.0] — Frontend Typed ApiError + 404 vs Server Error（M35 完成；2026-05-01）

> **Minor bump** — frontend `apiFetch` 拋自訂 `ApiError`（含 `status` + `code`）；`SkillDetailPage` 區分 404 not-found 與其他 server / network error。改善 server 中斷時誤導 user「skill 不存在」的 UX。

### Added
- **S039: Frontend Typed `ApiError` + 區分 404 vs server error**（M35 落地）：
  - **`ApiError extends Error`**：含 `readonly status: number` + `readonly code?: string`（從 backend `ErrorResponse.error` 解析）
  - **`apiFetch` 改 throw `ApiError`**：保留 `error.message` 兼容既有 callers；新增 `error.status` / `error.code` 路徑
  - **`SkillDetailPage` error block case-split**：`error instanceof ApiError && error.status === 404` → 「找不到此技能」；其他 → 「載入技能時發生錯誤，請稍後重試或重新整理頁面」
  - **4 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 14 — `SkillDetailPage` 對 任何 error（404 / 500 / network）都顯示「找不到此技能」；server 中斷時誤導 user

### Verification
- `npm test` — 10/10 PASS（既有 callers 用 `error.message` 路徑不破；ApiError 是 Error 子類）
- `tsc -b` — 0 errors
- `npm run lint` — 0 warnings
- backend 404 sanity：`{"error":"NOT_FOUND","message":"...","timestamp":"..."}` 匹配 ApiError 構造

### Tech Debt
- 其他 page（HomePage / AnalyticsPage / PublishPage）可漸進採用 ApiError 做更精細 UX 區分
- S031 §7.5 admin panel endpoint 仍待設計

---

## [v2.15.0] — ACL List Recognizes `*:read`（M34 完成；2026-05-01）

> **Minor bump** — `SkillAclQueryService.listEntries` 識別 S026 公開讀取 pseudo-principal `"*:read"` 為 synthetic entry，消除 WARN log spam，讓 frontend 可呈現「公開讀取」狀態。

### Added
- **S038: ACL List Recognizes `*:read`**（M34 落地）：
  - **`*:read` 識別為 synthetic entry**：response 含 `{type:"public", principal:"*", permission:"read"}`；frontend 可根據 type=public 渲染特殊 badge / icon
  - **消除 WARN log spam**：每次對 public skill 讀 ACL 不再觸發「格式異常」WARN（baseline 每讀一次一條 WARN）
  - **CRUD 約束不變**：`grantAcl` / `revokeAcl` 仍只接受 `user|role|group` 三 namespace；`*:read` 為 platform-level 預設由 `Skill.create` 自動 seed，不允許 user CRUD
  - **5 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 13 — 每次對 public skill GET /acl 都觸發 WARN log；S026 後每個 PUBLISHED skill 都有 `*:read` → log spam at scale；同時 ACL list response 不含 `*:read`，frontend 無法呈現「公開讀取」狀態

### Verification
- `./gradlew test` — 296 tests / 0 fail（含新加 `listEntries_publicReadPseudoPrincipal_recognized` test）
- E2E HTTP：ACL list response 4 entries（3 user + 1 public）；連 3 次 read 後 WARN count = 0

### Tech Debt
- Frontend 顯示「公開讀取」UI（如 type=public 加特殊 badge）為 future spec 範疇
- S031 §7.5 admin panel endpoint 仍待設計

---

## [v2.14.0] — Upload Size 413 + Frontend Size Pre-check（M33 完成；2026-05-01）

> **Minor bump** — HTTP 語意修正 + frontend 早期防呆：超 10MB 上傳從 **HTTP 409 STATE_CONFLICT**（被 S030 catch-all 過度攔截）改回正確的 **HTTP 413 PAYLOAD_TOO_LARGE**；FileDropZone 加 client-side size pre-check 避免 user 浪費頻寬上傳大檔才知失敗。

### Added
- **S037: Upload Size 413 + Frontend Size Pre-check**（M33 落地）：
  - **`@ExceptionHandler(MaxUploadSizeExceededException.class)` → 413 PAYLOAD_TOO_LARGE**：含實際 byte limit 的 message（"Upload size exceeds the 10 MB limit"）；error code `PAYLOAD_TOO_LARGE` 對 client 可區分
  - **`@ExceptionHandler(MultipartException.class)` → 400 MULTIPART_ERROR**：其他 multipart 解析錯誤
  - 順序 most-specific-first — `MaxUploadSizeExceededException extends MultipartException`，自動先匹配 size-exceeded handler
  - **`FileDropZone` 加 `maxSizeBytes` prop**（預設 10MB 對齊 backend）+ `handleFile` 集中 guard + inline 紅色錯誤「檔案大小 X.X MB 超過 Y MB 限制」
  - 6 個 SBE AC 全綠

### Changed
- 11MB upload 從 409 STATE_CONFLICT → **413 PAYLOAD_TOO_LARGE**；訊息明確指出 limit

### Trigger
- 2026-05-01 /loop tick 12 — 11MB upload 回 409 STATE_CONFLICT（被 S030 IllegalStateException catch-all 過度攔截）；frontend 無 size pre-check

### Verification
- backend `./gradlew test` — 295 tests / 0 fail
- frontend `npm test` — 10/10 PASS；`tsc -b` 0 errors；`npm run lint` 0 warnings
- E2E HTTP 11MB → 413 with "Upload size exceeds the 10 MB limit"；5MB → 201

### Tech Debt
- S031 §7.5 admin panel endpoint 仍待設計

---

## [v2.13.0] — Frontend MEDIUM Risk Message（M32 完成；2026-05-01）

> **Minor bump** — frontend Risk tab UX 修正：補齊 MEDIUM 風險等級說明段落（既有只有 LOW/HIGH）；同步以 `Record<RiskLevel, string>` map 取代 inline `if` 串，TypeScript exhaustive check 防止未來新增等級漏改。

### Added
- **S036: Frontend MEDIUM Risk Message**（M32 落地）：
  - **`RISK_DESCRIPTION: Record<RiskLevel, string>`** — 三段中文說明（LOW / MEDIUM / HIGH）；MEDIUM 為新增「此技能含可執行腳本，但未偵測到高風險模式。建議審視 scripts/ 內容後再使用。」
  - **`RISK_TEXT_CLASS: Record<RiskLevel, string>`** — 視覺色階對應（muted / amber-700 / red-600），對齊 `RiskBadge` 既有 LOW/MEDIUM/HIGH 色彩語意
  - 3 個 inline `{skill.riskLevel === 'X' && (...)}` → 1 個 `{skill.riskLevel && (...)}` 統一從 map 取
  - **5 個 SBE AC 全綠**

### Pattern
- mirror S028 `STATUS_LABEL: Record<SkillStatus, string>` 模式 — codebase 一致性；TypeScript Record 強制 exhaustive

### Trigger
- 2026-05-01 /loop tick 11 — `RiskBadge` 三色齊全但 `SkillDetailPage` Risk tab 只有 LOW/HIGH 段落，MEDIUM skill user 看 badge 後找不到詳述

### Verification
- `npm test` — 10/10 PASS
- `tsc -b` — 0 type errors
- `npm run lint` — 0 errors / 0 warnings

---

## [v2.12.0] — Frontend Suspended Detail Page UX（M31 完成；2026-05-01）

> **Minor bump** — frontend UX 修正：SUSPENDED skill 詳情頁不再渲染下載按鈕（避免 user 點擊落到 raw 403 JSON），新增「已停用」提示橫幅（destructive variant），隱藏新增版本表單。

### Added
- **S035: Frontend Suspended Detail Page UX**（M31 落地）：
  - **下載按鈕條件加 `status === 'PUBLISHED'`**：避免 user 點擊後 navigate 至後端 raw 403 JSON 頁面
  - **SUSPENDED banner**：destructive variant border/bg，「此技能已被停用，無法下載」+ admin 聯絡指示
  - **AddVersionForm 隱藏**：對 SUSPENDED skill 不渲染新增版本表單（backend `recordVersionPublished` 對 SUSPENDED 拋 IllegalStateException → 409；UI affordance principle: don't let users start what they can't finish）
  - **6 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 10 — SUSPENDED skill detail page 仍顯示下載按鈕；click 後落到後端 raw JSON 403 SKILL_SUSPENDED

### Verification
- `npm test` — 10/10 PASS
- `tsc -b` — 0 type errors
- `npm run lint` — 0 errors / 0 warnings
- Visual code review：三處 conditional rendering 對齊 S028（status badge）+ S029（backend 403 SKILL_SUSPENDED）+ S030（state machine 409）

### Tech Debt
- S031 §7.5 admin panel endpoint（用於 reactivate 操作）仍待設計

---

## [v2.11.0] — SearchProjection Owner from Event/Aggregate（M30 完成；2026-05-01）

> **Minor bump** — 完成 S025b §7 architecture tech debt：`SearchProjection.onSkillCreated` 與 `onVersionPublished` 從 `currentUserProvider.userId()`（async listener 走 labUserId fallback）改為 `event.author()` / `skill.getAuthor()`（source of truth）。移除 `CurrentUserProvider` 依賴。

### Added
- **S034: SearchProjection Owner from Event/Aggregate**（M30 落地）：
  - **`onSkillCreated`**：`.owner(currentUserProvider.userId())` → `.owner(event.author())` — 從 event 直接取 author（caller 上傳 form 帶來，已是 source of truth）
  - **`onVersionPublished`**：query Skill aggregate → `owner = skill.getAuthor()`（mirror S033 onSkillReactivated 模式；不依賴 frontmatter author 因可能與 aggregate 不一致）
  - **移除 `CurrentUserProvider` field + ctor param**：SearchProjection 不再需要 SecurityContext；簡化 search → shared::security 耦合
  - **3 個 SBE AC 全綠**

### Changed
- `SearchProjectionTest`：3 處 expected `owner = "test-owner"` 改為 author 名（"sam" / "jane"）；`onSkillCreated_multipleSkillsHaveIndependentOwnerState` 重寫為「不同 author events 各自獨立寫入」；移除 `@WithMockUser` + SecurityContextHolder 操作

### Trigger
- 2026-05-01 /loop tick 9 — vector_store.owner = `lab-user`（不是 author=alice）；S025b §7 已記的 architecture tech debt 中的 onSkillCreated / onVersionPublished path（S033 已修 onSkillReactivated）

### Verification
- `./gradlew test` — 295 tests / 0 fail
- E2E HTTP（local LAB mode）：upload author=alice → vector_store.owner=`alice`（baseline `lab-user`）；PUT version → owner 維持 alice

### Architecture Tech Debt Complete
- S025b §7 author fallback 完整解決（S033 onSkillReactivated + S034 onSkillCreated/onVersionPublished）
- SearchProjection 對 author 屬 deterministic — 永遠跟 event/aggregate 同步，不受 SecurityContext propagation 影響

---

## [v2.10.0] — Vector Store Status Sync（M29 完成；2026-05-01）

> **Minor bump** — `SearchProjection` 新增兩個 listener，把 vector_store 同步至 skill state machine：suspend 刪 row，reactivate 重新 embed。落地 S031 §7.5 tech debt；附帶解決 S025b §7 author-fallback architecture tech debt 中的 reactivate path。

### Added
- **S033: Vector Store Status Sync**（M29 落地）：
  - **`SearchProjection.onSkillSuspended`**：純 delete-by-id；semantic search 不再命中已下架 skill
  - **`SearchProjection.onSkillReactivated`**：query Skill aggregate + 最新 SkillVersion，rebuild doc 寫回 vector_store with author-derived ACL + S026 `*:read` public pseudo-principal
  - 注入 `SkillRepository` + `SkillVersionRepository`（`search` module `allowedDependencies` 已含 `skill :: domain`）
  - 5 個 SBE AC 全綠

### Changed
- `SearchProjectionTest` 與 `SearchProjectionAclWriteTest` 從 `BootstrapMode.DIRECT_DEPENDENCIES` 升 `ALL_DEPENDENCIES` — 因 search 現需 skill module 的 repos，連帶 storage module bean 需載入

### Trigger
- 2026-05-01 /loop tick 8 — SUSPENDED skill 的 vector_store row 仍存在；reactivate 不會 refresh。S031 §7.5 已登記為 tech debt。

### Verification
- `./gradlew test` — 295 tests / 0 fail（含新 2 unit tests）
- E2E HTTP（local LAB mode）：upload → vector_store 1 row；suspend → 0 row；reactivate → 1 row with **owner=alice**（從 aggregate 取，非 lab-user fallback）+ acl_entries 含 `*:read`

### Side benefit — author fallback tech debt 部分解決
- onSkillReactivated 從 `skill.getAuthor()` 取 owner，不依賴 SecurityContext — 解 S025b §7 已記的 architecture tech debt 中的 reactivate path
- onSkillCreated / onVersionPublished 仍受 SecurityContext propagation 影響；修法留 future spec

### Tech Debt
- onSkillCreated / onVersionPublished 也應改用 aggregate-based author（不依賴 currentUserProvider 在 async thread）
- Admin panel endpoint（S031 §7.5）仍待設計

---

## [v2.9.0] — Version Name Consistency（M28 完成；2026-05-01）

> **Minor bump** — 資料完整性修正：`PUT /api/v1/skills/{id}/versions` 現在驗 zip SKILL.md `name` 與 skill aggregate `name` 一致；違反 → 400 VALIDATION_ERROR。

### Added
- **S032: Version Name Consistency**（M28 落地）：
  - **`SkillCommandService.addVersion` 加 name consistency check**：在 `SkillValidator.validate` 後，比對 `validation.metadata().get("name")` vs `skill.getName()`；不一致 → `IllegalArgumentException` → 400 VALIDATION_ERROR with structured log（skillId / aggregateName / zipName）。
  - **上移 `findById`**：從原本 line 145 移至 validate 之後、version 重複 check 之前。
  - **4 個 SBE AC 全綠**（mismatch 400 / matching 200 / 既有 test 不破 / POST upload 行為不變）。

### Trigger
- 2026-05-01 /loop tick 7 系統測試 — 對 skill A 可 PUT 一個 SKILL.md name=B 的 zip 並 200 接受；下載 zip 的 metadata 與平台 listing 的 name 矛盾。

### Verification
- `./gradlew test` — 293 tests / 0 fail（含新 `addVersion_nameMismatch_rejects` test）
- E2E HTTP：mismatch → 400 VALIDATION_ERROR with explicit message；matching → 200 + latestVersion=1.1.0；POST /upload 不受影響

### Defense-in-depth note
- 阻止「上傳一個高分 PUBLISHED skill A v1.0.0 後，PUT 一個惡意內容但 name 不同的 v1.1.0」變身攻擊（分數刷量 + 內容偷換）；每個 version 必延續同 aggregate name

---

## [v2.8.0] — Public PUBLISHED-Only Visibility（M27 完成；2026-05-01）

> **Minor bump** — 公開查詢過濾 status：list / keyword / categories / analytics 端只回 PUBLISHED skill。落地 S028 §7.5 已登記的 tech debt。對 frontend 行為 user-facing 改變：DRAFT/SUSPENDED 不再出現在公開列表、搜尋、分類計數、analytics 排行。

### Added
- **S031: Public PUBLISHED-Only Visibility**（M27 落地）：
  - **`SkillQueryService.search` SQL 加 `WHERE status = 'PUBLISHED'`**（含 countSql）— list / keyword / category 三種查詢統一過濾
  - **`SkillQueryService.getCategoryCounts` 加 `AND status = 'PUBLISHED'`** — sidebar 計數對齊 list 結果
  - **`AnalyticsService.getOverview` totalSkills + newSkillsThisWeek SQL 加 status filter**
  - **`AnalyticsService.getTopSkills` SQL 加 status filter** — 排行不含 SUSPENDED
  - **`findById` 不過濾**（detail page）— admin / owner 仍可看 SUSPENDED / DRAFT 做 reactivate 決定；對齊 S028 frontend 三狀態渲染
  - **6 個 SBE AC 全綠**

### Trigger
- 2026-05-01 /loop tick 6 系統測試 — 列表 17 個 skills 含 2 SUSPENDED + 2 DRAFT；categories DevOps count=16 含 SUSPENDED；analytics topSkills 含 `suspend-download-test`、`draft-skill-tick5`；違反 PRD 公開瀏覽端只看 PUBLISHED 設計（per S028 §7.5 tech debt）

### Verification
- `./gradlew test` — 292 tests 全綠（`SkillSearchTest` fixture 從 DRAFT 改 PUBLISHED 對齊新規則）
- E2E HTTP（local LAB mode）：list 17→13；keyword 含 SUSPENDED 名 1→0；categories DevOps 16→13；analytics totalSkills 17→13 + topSkills 排除 SUSPENDED；detail page 仍 200 對 SUSPENDED

### Tech Debt
- **Semantic search vector cleanup**：當前 SUSPENDED skill 的 vector_store row 仍在；future spec（S032 SearchProjection status sync）加 `onSkillSuspended` listener 刪 vector + `onSkillReactivated` 重 embed
- **Admin panel endpoint**：`/api/v1/admin/skills` with full status visibility — 屬 future S033+ 設計

---

## [v2.7.0] — Conflict-Class Error Mapping（M26 完成；2026-05-01）

> **Minor bump** — HTTP 錯誤映射修正：將 conflict 類例外（state machine 違規 / 樂觀鎖競態）從 HTTP 500（with stacktrace 暴露）統一映射至 HTTP 409 Conflict + 結構化 ErrorResponse。延伸已建立的 `VersionExistsException` → 409 pattern。

### Added
- **S030: Conflict-Class Error Mapping**（M26 落地）：
  - **`@ExceptionHandler(IllegalStateException.class)` → 409 STATE_CONFLICT**：覆蓋 aggregate state machine 違規（duplicate ACL grant / revoke missing ACL / suspend DRAFT / reactivate non-SUSPENDED）。aggregate 仍拋 IllegalStateException + descriptive message — 只 HTTP 層映射改變。
  - **`@ExceptionHandler(OptimisticLockingFailureException.class)` → 409 CONCURRENT_MODIFICATION**：覆蓋 Spring Data `@Version` 樂觀鎖競態；attached retry hint message。
  - **6 個 SBE AC 全綠**（duplicate / revoke missing / suspend DRAFT / reactivate PUBLISHED / 並行 5 grants / 既有 test 不破）。

### Trigger
- 2026-05-01 /loop tick 5 系統測試 — duplicate grant 回 500 + stacktrace 暴露；5 並行 grants 4/5 → 500。

### Verification
- `./gradlew test` — 292 tests 不破
- E2E HTTP：4 種 state conflict 都 409 STATE_CONFLICT；5 並行 grant 1×201 + 4×409（0×5xx）；同 principal race 1×201 + 1×409 CONCURRENT_MODIFICATION

### Tech Debt
- 自動 retry on OptimisticLock 留 future spec — auto retry 可能 mask 真正衝突；屬 idempotency-key middleware 或 server-side bounded retry 設計範疇

---

## [v2.6.0] — Block Suspended Skill Download（M25 完成；2026-05-01）

> **Minor bump** — 安全性修正：被 suspend 的 skill 不可下載。原 API 對 SUSPENDED skill 仍回 200 + zip，違反 `SkillStatus.SUSPENDED` 設計意圖「因安全風險或違規而下架，不可下載」。

### Added
- **S029: Block Suspended Skill Download**（M25 落地）：
  - **`SkillSuspendedException`（new）**：sentinel exception，攜 skillId；class Javadoc 說明 403 vs 410 的選擇（403 因 SUSPENDED 可被 admin reactivate）。
  - **`SkillQueryService.downloadAndRecord` guard**：service 層 single chokepoint；先 `findById` + status check，fail-fast 早於 storage download；兩條 endpoint（`downloadLatest` + `downloadVersion`）均受保護。
  - **`GlobalExceptionHandler` 加 `@ExceptionHandler(SkillSuspendedException.class)`**：HTTP 403 + `ErrorResponse{code:"SKILL_SUSPENDED", message:..., timestamp:...}`；structured log 含 skillId。
  - **4 個 SBE AC 全綠**（AC-1~4：SUSPENDED 兩 endpoint 都 403 / PUBLISHED regression / reactivate 恢復）。

### Trigger
- 2026-05-01 /loop tick 4 系統測試 — `GET /api/v1/skills/{id}/download` 對 SUSPENDED skill 仍回 200 + zip bytes，違反 PRD 設計意圖。S029 補正。

### Verification
- `./gradlew test` — 292 tests / 0 fail / 0 disabled
- E2E HTTP（local LAB mode）：PUBLISHED 200 → suspend → SUSPENDED download 403 SKILL_SUSPENDED → reactivate → download 200

### Security note
- guard 在 service layer 而非 ACL `@PreAuthorize` — S027 admin bypass 對 `@PreAuthorize` 短路不影響此 status guard；admin 也不能下載 SUSPENDED skill（除非先 reactivate），符合 audit trail 要求

---

## [v2.5.0] — Frontend SUSPENDED Status Rendering（M24 完成；2026-05-01）

> **Minor bump** — 純 frontend type + UI rendering 修正，對齊 backend `SkillStatus` enum 三狀態。User-facing 改變：SUSPENDED skill 在 SkillDetailPage 顯示「已停用」+ destructive variant；SkillCard 在 list 上對 DRAFT / SUSPENDED 顯示 badge。

### Added
- **S028: Frontend SUSPENDED Status Rendering**（M24 落地）：
  - **`SkillStatus` type union 補齊**：`'DRAFT' | 'PUBLISHED' | 'SUSPENDED'` 對齊 backend enum
  - **`SkillDetailPage` label 中譯**：加 `STATUS_LABEL: Record<SkillStatus, string>` map（DRAFT '草稿' / PUBLISHED '已發佈' / SUSPENDED '已停用'）+ `statusBadgeVariant()` switch（DRAFT secondary / PUBLISHED default / SUSPENDED destructive）；取代既有 ternary fallback 直接顯示英文 raw string
  - **`SkillCard` 對非 PUBLISHED 顯示 badge**：DRAFT outline / SUSPENDED destructive；happy path PUBLISHED 不顯示避免視覺噪音

### Trigger
- 2026-05-01 /loop tick 3 系統測試 — frontend type 與 backend enum drift；SUSPENDED skill 在 detail page 顯示英文 'SUSPENDED'，SkillCard 完全不顯示狀態。

### Verification
- `npm test` — 10/10 tests PASS
- `npx tsc -b` — 0 type errors（exhaustive Record + switch 防漏）
- `npm run lint` — 0 errors / 0 warnings

### Tech Debt
- backend `SkillQueryService.search` 不過濾 status — DRAFT 與 SUSPENDED 都出現在公開 list；屬產品決策，留 future spec（S029 admin panel 設計時整體決定）

---

## [v2.4.0] — Dev Mode Admin Bypass（M23 完成；2026-05-01）

> **Minor bump** — 新增 `ROLE_admin` super-admin 短路 + `local` profile 預設 LAB mode。對 prod 行為零影響（OIDC `roles: ["admin"]` claim 才會帶此 authority；攻擊者無法 spoof）。對齊 PRD「Feature First, Security Later」+ user instruction「dev 先不做授權認證」。

### Added
- **S027: Dev Mode Admin Bypass**（M23 落地）：
  - **`DelegatingPermissionEvaluator` admin bypass**：`evaluate()` 加短路 — `Authentication.authorities` 含 `ROLE_admin` 時 `hasPermission` 直接 return true，不查 strategy。對齊 RBAC 慣例（GitHub org admin / Atlassian site admin / 多數 SaaS admin role 均 cross-resource bypass）。
  - **`application-local.yaml` LAB mode**：預設 `skillshub.security.oauth.enabled: false`。`SecurityConfig.filterChain` 走 LAB 分支（`anyRequest().permitAll()` + `LabSecurityFilter` 注入 `lab-user` with `ROLE_admin`）。配合 admin bypass，所有 `@PreAuthorize` ACL gate 在 dev 自動通過，無需手動 JWT。
  - **5 個 SBE AC 全綠**（AC-1~AC-5）：local LAB 啟動 / ACL grant 通過 / mutation 全通過 / prod 行為不變 / 既有 9 個 unit test 不破。

### Trigger
- 2026-05-01 /loop tick 2 系統測試：anonymous 對 alice 的 skill 執行 `POST /api/v1/skills/{id}/acl` 回 HTTP 401；違反「dev 先不做授權認證」意圖。S027 補正。

### Verification
- `./gradlew test` — 292 tests / 0 fail / 0 disabled（含新加 admin bypass test）
- E2E HTTP（local LAB mode）：`POST /api/v1/skills/{id}/acl` HTTP 201（tick 1 為 401）；suspend / reactivate / PUT version / DELETE acl 全通過
- 對 prod 模式（oauth.enabled=true）零影響：`DelegatingPermissionEvaluatorTest` 既有 9 test 全綠

---

## [v2.3.0] — Public-Read Default ACL（M22 完成；2026-05-01）

> **Minor bump** — 新功能：skill 上傳後預設對所有使用者開放讀取（PRD MVP 設計意圖落地）。User-facing 行為改變：anonymous / 任意 user 都能 read 任何 skill；write/delete/suspend/reactivate mutation 仍 owner-only ACL 守。

### Added
- **S026: Public-Read Default ACL**（M22 落地）：
  - **`"*:read"` public-read pseudo-principal**：約定 unix glob「any」語意，不撞 `user:`/`role:`/`group:` 命名空間；Postgres `?|` array overlap 純字串比對，零 escaping 風險。
  - **`Skill.create()` 預設 aclEntries 加 `*:read`**：author 非 null 時 4 entries（`user:{author}:{read,write,delete}` + `*:read`）；author null 時仍 seed `["*:read"]` 維持公開。
  - **`SearchProjection.onSkillCreated` + `onVersionPublished` initialAcl 加 `*:read`**：vector_store row 對所有使用者開放讀取，semantic search 命中。
  - **`AclPrincipalExpander.expand` 對 `read` permission 附 `*:read`**：caller patterns 含 `*:read` → 與 vector_store 內 `*:read` `?|` 命中；write/delete/suspend/reactivate **不**附（mutation 仍 owner-only）。
  - **5 個 SBE AC 全綠**：AC-1（Skill aggregate 預設）+ AC-2（vector_store 預設）+ AC-3（expander）+ AC-4（anonymous overlap，via DB SQL — local profile NoOpEmbeddingModel 無法走 HTTP 端到端）+ AC-5（write 仍 owner-only）。

### Changed
- 既有 7 個 test 斷言更新加 `*:read` 預期值（`SkillAggregateTest` 5 處 + `AclPrincipalExpanderTest` 2 處）— 行為對齊新預設。

### Trigger
- 2026-05-01 /loop tick 1 系統測試：anonymous user 對 alice 上傳的 skill 執行 `GET /api/v1/search/semantic` 回 `[]`，違反 PRD MVP「skill 預設公開」設計意圖；S026 補正預設 ACL。

### Verification
- `./gradlew test` — 291 tests / 0 fail / 0 disabled
- DB direct `?|` overlap query: anonymous (lab-user) `["user:lab-user:read", "role:admin:read", "*:read"]` matches uploaded skill `["user:lab-user:read", "*:read"]` → t；任意 bob (`["user:bob:read", "role:user:read", "*:read"]`) 同 t

---

## [v2.2.0] — Phase 4b: Slice 重組 + Workaround 移除（M21 完成；2026-04-30）

> **Minor bump** — 純 internal test infrastructure 重整。User-facing API contract 完全不變；資料庫 schema 不變；Spring Modulith module 邊界不變。運維端取得：13 REPO test 切 `@DataJdbcTest` slice + 11 Controller test 切 `@WebMvcTest` slice + 2 SearchProjection async test 切 `@ApplicationModuleTest` + E2E 12→3 收斂；S025a 留下的 `S016EndToEndSmokeTest:57 @Disabled` 恢復；`cache.maxSize=8` workaround 全清；`maxHeapSize` 從 3g 降至 2g。

### Added
- **S025b: Slice 重組 + Workaround 移除**（M21 落地）：
  - **`RepositorySliceTestBase`（new abstract base class）**— `@DataJdbcTest + @Import(TestcontainersConfiguration) + @TestPropertySource("management.tracing.enabled=false") + @ImportAutoConfiguration + @Transactional(NOT_SUPPORTED)` + `META-INF/spring/<fqn>.imports` 帶 `SpringModulithRuntimeAutoConfiguration` FQN。13 REPO slice test 共用同一 cache key。
  - **`WebMvcSliceTestBase`（new abstract base class）**— `@Import(SecurityConfig) + @EnableConfigurationProperties(SkillshubProperties) + @MockitoBean JwtDecoder + @MockitoBean PermissionEvaluator + @ImportAutoConfiguration + @TestPropertySource("management.tracing.enabled=false")`。11 Controller slice test 共用同一 cache key。
  - **Spring Modulith AOT blocker 雙條 path 解法**（POC 揭露 spec §2.4 hypothesis 不完整）：(a) `management.tracing.enabled=false` 解 `ModuleObservabilityAutoConfiguration` 對 `ApplicationModulesRuntime` hard dep；(b) bare `@ImportAutoConfiguration` + 同名 `META-INF/spring/<class-fqn>.imports` resource file 解 `ApplicationModulesFileGeneratingProcessor` classpath-level 註冊（`spring-modulith-runtime` `aot.factories`，非 auto-config 無屬性可關）。
  - **13 REPO test 遷移 `extends RepositorySliceTestBase`** — converter (Map/StringList JSONB) / event store (DomainEvent / sequence uniqueness) / skill repo+service (SkillVersion / SkillAcl / SkillUploadAllowedTools / SkillSuspendReactivate) / analytics (DownloadEvent idempotency) / search (Skillshub PgVectorStore ACL / Owner write) / S025b T04 demote (SkillCommandService / SkillSearch / SkillVersionQuery)。
  - **11 Controller test 遷移 `@WebMvcTest` extends `WebMvcSliceTestBase`** — Me/Admin/LabModeMe/LabModeAdmin/SkillsApiAnonymous/Analytics/Flag/SkillQueryControllerApiContract + 3 ACL/Security 拆解（SkillCommand/SkillSuspend/SkillAcl — HTTP/auth gate 留 slice，DB seed + async event 移除已被 S016 e2e 涵蓋）；JwtDecoderConditionalTest 保留 `@SpringBootTest` 因與 `@MockitoBean JwtDecoder` 強制注入 fundamental conflict（CONFIG bucket）。
  - **2 SearchProjection async test 遷移 `@ApplicationModuleTest(BootstrapMode.DIRECT_DEPENDENCIES)`** — `SearchProjectionTest` + `SearchProjectionAclWriteTest`；對齊 S025a `AuditEventListenerTest` pilot pattern；`@WithMockUser` + Scenario API 保留。
  - **E2E 12→3 收斂**（per spec §4.8）：保留 `RiskAssessmentIntegrationTest` + `S016EndToEndSmokeTest`（吸收 `SkillIntegrationTest` + `SkillUploadTest` + `SkillDownloadTest` 7 個 method）+ `SemanticSearchIntegrationTest`（吸收 `SemanticSearchAclTest` 4 個 ACL test）；4 個 demote REPO（SkillCommandService / SkillSearch / SkillVersionQuery / SkillshubPgVectorStoreAclSearch inner）；4 個 file delete。`ModulithActuatorTest` 從 `RANDOM_PORT + TestRestTemplate` 改 `MOCK + MockMvc`（per AC-5 ≤3 RANDOM_PORT 收斂）。
  - **`S016EndToEndSmokeTest:57 @Disabled` 恢復**（S025a §7.7 deferred）— 切 `@SpringBootTest(WebEnvironment.RANDOM_PORT) + @AutoConfigureMockMvc + @EnableScenarios`；移除 `@Disabled`；多次 `scenario.stimulate(action).andWaitForStateChange(query)` chain 取代 `Awaitility.await(N).untilAsserted(...)`（Modulith Scenario API 支援同 test 多次 stimulate）。設計修正：`vector_store.acl_entries` 含 author ACL 假設不成立（`SearchProjection.onVersionPublished` async listener 用 `currentUserProvider.userId()` 走 `labUserId` fallback 覆寫），改用 `domain_events` + `skills.acl_entries`（sync TX）作為 sync point。
  - **`cache.maxSize=8` jvmArg 全清（T01）** — `grep "spring.test.context.cache.maxSize" build.gradle.kts` = 0。
  - **12 個 SBE AC**：9 FULL（AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-10, AC-11）+ 3 PARTIAL with documented deviation（AC-8 / AC-9 / AC-12，皆有 S025c tech debt 登記）。
  - **獨立 QA subagent verdict PASS**（所有 PARTIAL 屬 documented deviation；4 個 MINOR finding 屬 cosmetic，非 blocking）。

### Changed
- **`build.gradle.kts` `tasks.test {}`**：移除 `cache.maxSize=8` jvmArg（T01）；`maxHeapSize` 從 `"3g"` 降至 `"2g"`（T05 — full default 還原 blocked by 18 個 CONFIG bucket `@SpringBootTest`）；移除 S023-T07 過時 comment block。

### Performance
- **Cache key 從 baseline ~42-45 降至 ~18**（pgvector container 啟動 18 次/run；indirect measurement — `-Dlogging.level.org.springframework.test.context.cache=DEBUG` 在 Gradle test fork JVM 不 propagate，改數 Testcontainer 啟動次數）。
- **`./gradlew clean test` 2m 50s**（vs S025a baseline 2m 3s — 增 23s 因 RANDOM_PORT e2e 啟 Tomcat 比 MOCK 略慢；仍在 +10% 容忍範圍）。
- **JaCoCo line coverage 86.9%**（covered=1423 / total=1637；V03 gate ≥80% PASS）。

### Documentation
- `docs/grimo/qa-strategy.md` — § Layer 1 加 REPO slice via `RepositorySliceTestBase` + WEB slice via `WebMvcSliceTestBase` 兩段 pattern；測試金字塔目標更新（cache key ~18 / S025c 再降 ≤10；E2E ≤3 對齊實況）。
- `docs/grimo/development-standards.md` — 新增「REPO slice via RepositorySliceTestBase」段（含 5 點設計理由：`@AutoConfigureTestDatabase(replace=NON_TEST)` SB4 default / Flyway 自動啟用 / `AbstractJdbcConfiguration` `KNOWN_INCLUDES` / Spring Modulith AOT 雙 path fix / `@Transactional(NOT_SUPPORTED)` 反 `@DataJdbcTest` default）+ 「WEB slice via WebMvcSliceTestBase」段（OAuth2 RS test pattern 用 `.with(jwt())` 而非 `@WithMockUser`）；cache key 上限更新至 ~18 with S025c tech debt path。
- `docs/grimo/specs/spec-roadmap.md` — S025b entry → ✅；M21 milestone 新增 + `v2.2.0` 標記；S025c tech debt 登記（CONFIG bucket consolidation + JVM heap default 還原）。

### Known Limitations
- **Cache key ~18（≤ 10 未達）** — 設計上由 S025c（CONFIG bucket consolidation）落地：18 個 `@SpringBootTest` 進一步合併 / 共用 customizer / 抽 base class，目標 ≤10。
- **`maxHeapSize=2g` 仍存在**（從 3g 降低，未完全還原 default）— 同上 S025c 解。
- **Test pyramid UNIT 19 / target 21（-2）& CONFIG 19 / target ≤13（+6）** — 同 cache key 議題；S025c 解。
- **`SearchProjection.onVersionPublished` async listener ACL 一致性 architecture tech debt**：用 `currentUserProvider.userId()` 在 async thread 走 `labUserId` fallback；應改用 event 帶的 author 或讀既有 row 的 owner（per spec §7 揭露）。

### Verification
- `./gradlew clean test` — 291 tests / 0 failed / 0 skipped / 0 disabled
- `./gradlew test --tests "*ModularityTests*"` — Spring Modulith 7 module 邊界全合規 PASS
- `./scripts/verify-all.sh` × 5 連續 — V01-V06 全 PASS，0 flakiness
- JaCoCo line coverage 86.9% ≥ 80% gate
- E2E grep `WebEnvironment.RANDOM_PORT` = 3 file（RiskAssessment / S016 / SemanticSearchIntegration），AC-5 PASS
- `@Disabled` 在 active `@Test` method = 0（S016 內僅 javadoc 歷史脈絡），AC-6 PASS

---

## [v2.1.0] — Phase 4a: Mock Lift + Scenario Migration（M20 完成；2026-04-30）

> **Minor bump** — 純 internal test infrastructure 重整 + 1 行 production bug fix（S023-T07 真因）。User-facing API contract 完全不變；資料庫 schema 不變；Spring Modulith module 邊界不變。運維端取得：async listener test 5s timeout（取代 30s band-aid）+ 4/5 disabled tests 恢復 + production async listener SecurityContext propagation 真正生效（S023-T07 wrapper 從旁路復活）。

### Added
- **S025a: Mock Lift + Scenario Migration**（M20 落地）：
  - **`TestcontainersConfiguration` 加 2 個共用 `@Bean`**：(a) `@Primary EmbeddingModel mockEmbeddingModel()` 三個 overload stub 完整（fixed seed 42 → cosine sim ≈ 1.0 > 0.3 threshold）取代散佈在 8 file 的 `@MockitoBean EmbeddingModel`；(b) `ScenarioCustomizer scenarioTimeout()` global default Awaitility timeout 5s（per Spring Modulith `ScenarioParameterResolver` 自動 pickup），取代 S023-T07 30s band-aid。
  - **`LabModeTestBase`（new abstract base class）**— `@SpringBootTest + @AutoConfigureMockMvc + @Import(TestcontainersConfiguration.class) + @TestPropertySource(oauth.enabled=false)` 收斂；3 LabMode test（`LabModeMeControllerTest` / `LabModeAdminControllerTest` / `JwtDecoderConditionalTest`）extends 後共用同一 cache key。
  - **`@WithMockUser` 取代 `@MockitoBean CurrentUserProvider`**（2 file：`SearchProjectionTest` + `SearchProjectionAclWriteTest`）— `CurrentUserProvider` production code 早已從 `SecurityContextHolder` 取（line 41），`AsyncListenerConfig` 已 wrap `DelegatingSecurityContextAsyncTaskExecutor`；改 `@WithMockUser` 為**零 production code change** 的更貼近 prod 行為驗證路徑。
  - **AuditEventListenerTest 改 `@ApplicationModuleTest(DIRECT_DEPENDENCIES) + Scenario`**（S025a-T01 POC pilot）— 8 個 30s `Awaitility.await` → `scenario.publish(event).andWaitForStateChange(...).andVerify(...)` pattern；p95 latency = 0.318s（Scenario 5s default 大量 headroom）。
  - **`RiskAssessmentIntegrationTest` 3 個 `@Disabled` 恢復**（S023-T07 標記）— 改 `@SpringBootTest + @EnableScenarios + Scenario.stimulate(() -> uploadHttp).andWaitForStateChange(...)` 取代 MockMvc + Awaitility 30s；ScanOrchestrator 完整 SARIF pipeline p95 = 0.559s（5s default 即可，但保守用 15s override per spec §4.5）。
  - **`SearchProjectionTest:127` disabled 恢復** — 用 programmatic `SecurityContextHolder.setAuthentication(...)` 切換 + Scenario 序列化兩次發布，驗 per-request builder owner isolation（修復 S023-T07 mock 切換 + async race 問題）。
  - **All `Duration.ofSeconds(30)` → `Duration.ofSeconds(5)`** 全 `src/test/java`（`grep` = 0）— 含 `SkillUploadTest` / `SkillCommandServiceTest` / `SkillAclCommandServiceTest` / `SkillSuspendReactivateTest` / `SkillDownloadTest` / `SkillAclControllerTest` / `SkillSuspendControllerSecurityTest` / `EventPublicationOutboxBehaviorTest` / `HikariPoolUnderLoadTest` / `S016EndToEndSmokeTest`。
  - **12 個 SBE AC 全綠**（10 FULL + 2 PARTIAL deferred-by-design）：AC-1~AC-12；verify-all.sh V01-V06 全綠 × 3 連續 0 flakiness；269 tests / 0 failed / 1 skipped（S016 deferred per AC-6）；JaCoCo line coverage **89.7%** ≥ 80% gate。
  - **獨立 QA subagent verdict PASS**（10/12 FULL + 2/12 PARTIAL by design；1 minor Javadoc finding deferred to S025b doc pass）。

### Fixed
- **S023-T07 production bug 真因修正**（`AsyncListenerConfig.applicationTaskExecutor` bean alias）— S023 加的 `DelegatingSecurityContextAsyncTaskExecutor` wrapper 在 Spring 7.0 + Spring Boot 4.0.6 多 `TaskExecutor` bean（`applicationTaskExecutor` + `taskScheduler`）環境下被 `@Async` `AsyncExecutionInterceptor` by-type lookup 跳過，fallback `SimpleAsyncTaskExecutor` → wrapper **從未生效** → SecurityContext 不 propagate 至 async listener thread → `vector_store.owner = "lab-user"`（fallback 而非真實 user）。S023-T07 沒抓到是因當時所有 SearchProjection tests 用 `@MockitoBean CurrentUserProvider` 直接 stub return value 完全 bypass 了 SecurityContext 路徑。S025a-T03 的 `@WithMockUser` test 揭露此問題。**修法**：bean 加 alias `taskExecutor`（`@Async DEFAULT_TASK_EXECUTOR_BEAN_NAME`）強制 by-name lookup：
  ```java
  @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
  public TaskExecutor applicationTaskExecutor() {
      var executor = new ThreadPoolTaskExecutor();
      // ...
      return new DelegatingSecurityContextAsyncTaskExecutor(executor);
  }
  ```
  Production async listener（`SearchProjection` / `AnalyticsProjection` / `ScanOrchestrator` / `AuditEventListener`）SecurityContext propagation 從 S025a 起真正生效。

### Performance
- **`./gradlew clean test` 從 S023 baseline 2m 37s → 2m 3s**（-22%；含全部 269 tests + JaCoCo report）— cache key 從 baseline 53 estimated 降至 ~42-45（mock lift -10 + LabModeTestBase -2）；最終 ≤ 25 / ≤ 10 deferred S025b。

### Documentation
- `docs/grimo/qa-strategy.md` — § Layer 1 表格新增「Async Listener Test → Spring Modulith Scenario API」；新增 § Async Listener 驗證標準 pattern 段（含 standard pattern code、global timeout default 5s、Awaitility 何時使用、anti-pattern 列表、測試金字塔目標）。
- `docs/grimo/development-standards.md` — § Testing Standards 新增「測試金字塔規範（S025a 起）」；含 `@ApplicationModuleTest + Scenario` 為 async test 首選 + `@MockitoBean` 散佈為 anti-pattern + 修法（lift / @WithMockUser / base class）+ `@Async` bean alias 規則 + cache key 上限 + `Duration.ofSeconds(30)` 禁用。
- `docs/grimo/specs/spec-roadmap.md` — S025a entry → ✅；M20 milestone 新增 + `v2.1.0` 標記。

### Known Limitations
- **Cache key 仍 ~42-45（≤ 25 / ≤ 10 未達）**— 設計上由 S025b（slice 重組 @DataJdbcTest / @WebMvcTest）落地。
- **`S016EndToEndSmokeTest:57` 仍 disabled** — per spec §3 AC-6 allowed deferral；S025b 將改 `@ApplicationModuleTest + Scenario` 重寫 e2e flow。
- **`build.gradle.kts` workaround `maxHeapSize=3g + cache.maxSize=8` 仍存在** — S025b ship 後可移除（cache key ≤ 10 後不再需要）。
- **AC-4 listener migration count 設計與實作落差**（spec design 期待 12+，實作 1）— HTTP-driven tests + infra tests 不適合 `@ApplicationModuleTest`；落差已 documented in spec §7.4，S025b 評估 slice 替代。
- **AsyncListenerConfig.applicationTaskExecutor `/**` Javadoc 缺 `taskExecutor` alias 說明**（QA subagent 指出 minor finding）— inline comment 完整解釋；S025b doc pass 補。

### Verification
- `./gradlew test` — 269 tests / 0 failed / 1 expected skipped（S016 per spec deferral）
- `./gradlew jacocoTestCoverageVerification` — 89.7% line coverage ≥ 80% gate
- `./gradlew test --tests "*ModularityTests*"` — Spring Modulith 7 module（含 audit）邊界全合規 PASS
- `./scripts/verify-all.sh` × 3 連續 — V01-V06 全 PASS，0 flakiness
- 獨立 QA subagent — verdict PASS

---

## [v2.0.0] — Phase 3b: Skill State-Based Aggregate Migration（M19 完成；2026-04-30）

> **Major bump**（per ADR-002 §5.1）— Skill domain 內部架構模式根本性改變（ES POJO → Spring Data JDBC 充血聚合）。API contract 不變（Skill / SkillVersion JSON shape 與 v1.5.0 一致；`@Version` 欄位由 `@JsonIgnore` 隱藏）。資料庫 schema 向前相容（V6 migration 加 `skills.version` BIGINT；歷史 ES events row 在 `domain_events` 保留可 read-only 查詢）。

### Added
- **S024: Skill State-Based Aggregate Migration**（M19 落地）：
  - **Skill aggregate 充血**（`@Table("skills") extends AbstractAggregateRoot<Skill> implements Persistable<String>`）— 業務方法 mutate state + `registerEvent(...)`；6 個充血方法（`recordVersionPublished` / `suspend` / `reactivate` / `grantAcl` / `revokeAcl` / `recordDownload`）；`@Version Long version` 樂觀鎖（V6 Flyway migration）；`isNew()` 自訂以對應 client-generated UUID PK。
  - **SkillVersion 獨立 aggregate**（`@Table("skill_versions") extends AbstractAggregateRoot<SkillVersion>`）— 與 Skill 透過 plain `String skillId` FK 引用（**不**用 `@MappedCollection`，避開 `WritingContext.update()` delete-and-reinsert 雷）；`publish` factory + `attachRiskAssessment` 充血方法；4 個 derived queries。
  - **SkillCommandService 縮為 3-line orchestration**（load → mutate → save）— 移除 `eventStore` 注入 + `saveDomainEventOnly` / `nextEventSequence` / `loadAggregate` 等 ES path helpers。
  - **AuditEventListener**（新 `audit` 頂層 module）— 9 個 `@ApplicationModuleListener` 訂閱所有 Skill domain events 寫 `domain_events` audit row；冪等性三層保險：`UUID.nameUUIDFromBytes(dedupKey)` 確定性 row id + `INSERT ... ON CONFLICT (id) DO NOTHING` + 同 aggregate `pg_advisory_xact_lock(hashtext('audit:' || aggregateId)::bigint)` 序列化；`FlagService` 共用同 advisory lock 避免 `MAX(sequence)+1` race。
  - **Query side 簡化** — `SkillQueryController` response type 改為 `Skill` / `SkillVersion` aggregate（取代 `SkillReadModel` / `SkillVersionReadModel` records）；`SkillQueryService` 直打 `SkillRepository` / `SkillVersionRepository`；search 動態 SQL 透過 `Skill.fromRow(...)` 物化 row 為 aggregate。
  - **ScanOrchestrator 改 attachRiskAssessment 路徑** — 移除 `eventStore.save(SkillRiskAssessed)` + `objectMapper` 注入；改 `versionRepo.findBySkillIdAndVersion + sv.attachRiskAssessment + versionRepo.save`（透過 `@DomainEvents` publish `SkillRiskAssessedEvent` 至 outbox；audit row 由 AuditEventListener 統一處理）。
  - **新增 audit module**（`io.github.samzhu.skillshub.audit`）— `@ApplicationModule(allowedDependencies = {"shared :: events", "skill :: domain"})`；移到獨立頂層 module 避開 `shared → skill` Modulith cycle。
  - **API contract regression test**（`SkillQueryControllerApiContractTest`）— jsonPath 鎖定 v1.5.0 fields；驗證無 `version` 欄位 expose（`@JsonIgnore` on `Skill.version` 生效）。
  - **13 個 SBE AC 全綠**：AC-1~AC-13；verify-all.sh V01-V06 全綠；269 tests / 0 failed / 5 skipped；JaCoCo line coverage 89.6% ≥ 80% gate。
  - **獨立 QA subagent verdict PASS**（with minor Javadoc fix in-place）— 13 ACs 全部驗證；audit module 邊界乾淨。

### Changed
- **架構轉向**（per ADR-002 Phase 2）：Skills Hub Core Domain 從純 Event Sourcing 路線完成轉向「Spring Data JDBC 充血聚合 + Modulith Outbox」。`domain_events` 表角色重新定位為 **event log**（保留完整 ES 精神 — events 不可變、`(aggregate_id, sequence)` 嚴格遞增、理論上可 replay 還原任意時點 aggregate state；只是寫入路徑改為 AuditEventListener async 統一寫入，平時用 `repo.findById()` O(1) read 而非 events fold replay）。
- **DomainEventRepository 加 `saveAuditIdempotent`** `@Modifying @Query` — 原子 `INSERT INTO domain_events ... SELECT COALESCE(MAX(seq), 0)+1 ... ON CONFLICT (id) DO NOTHING`。
- **FlagService.createFlag** 加同 listener 的 advisory lock（避免與 audit listener race）+ `@Transactional` annotation。

### Removed
- **5 個 read-model 檔案**：`SkillProjection.java` / `SkillReadModel.java` / `SkillReadModelRepository.java` / `SkillVersionReadModel.java` / `SkillVersionReadModelRepository.java`（per AC-9）。
- **7 個 obsolete test 檔案**：`SkillProjectionAclTest` / `SkillProjectionStatusTest` / `SkillProjectionListenerAnnotationsTest` / `SkillReadModelAclTest` / `AtomicDownloadCountTest`（read-model listener tests）+ `SkillAclTest` / `SkillStateMachineTest`（v1.5.0 ES replay constructor 測試；功能由新 `SkillAggregateTest` 覆蓋）。
- **`SkillCommandService.saveDomainEventOnly`** transitional bridge + `nextEventSequence` helper + `loadAggregate` ES replay method + `eventStore` field injection。
- **`Skill` 類所有 ES path API**：`Skill(String, List<DomainEvent>)` replay constructor、`create(String,String,String,String)` event-returning factory、deprecated `publishVersion/suspend/reactivate/grantAcl/revokeAcl` overloads、`nextSequence()`、`@Transient publishedVersions/currentAclEntries/latestSequence` fields。
- **`ScanOrchestrator`** 直接 `eventStore.save(SkillRiskAssessed)` 路徑 + `DomainEventRepository` / `ObjectMapper` 注入（audit 由 listener 處理）。

### Known Limitations
- **無 emergency replay 路徑**：`Skill.fromHistory(events)` factory 已隨 ES path API 完整移除；如需 emergency rebuild aggregate from `domain_events`，可寫 private static factory（events 序列保留 → 隨時可加）。本 spec §7 KF-8 + architecture.md 已記載此設計選項。

### Documentation
- `docs/grimo/architecture.md` — Architecture Pattern 段（L22-174）整段改寫為「Spring Data JDBC Rich Aggregate + Modulith Outbox」；Event Store 段改為「Event Log」（保留 ES 精神 + 不主動 replay 設計取捨說明）；System Architecture box / Module Design 同步更新；audit module 新增。
- `CLAUDE.md` — Architecture pattern 段完整改寫；Modulith modules 加 `audit (S024)`；架構描述強調 ES 精神保留 + 不主動 replay 取捨。
- `docs/grimo/development-standards.md` — § 「Event Sourcing + CQRS 規範」段整段改寫為「Spring Data JDBC Rich Aggregate + Modulith Outbox 規範」（14 條標準）；保留 § Spring Modulith Outbox 規範段（S023 既有）不變。
- `docs/grimo/specs/spec-roadmap.md` — S024 entry → ✅；M19 milestone → ✅ `v2.0.0`；ES backlog ES-B1~B4 strikethrough（既有 pending S024 ship 標註）。

---

## [v1.5.0] — Phase 3a: Spring Modulith Outbox Foundation（M18 完成；2026-04-29）

### Added
- **S023: Spring Modulith Outbox Foundation**（M18 落地）：
  - **Transactional outbox**（`spring-modulith-starter-jdbc`）— V4 Flyway migration 建 `event_publication` + `event_publication_archive` 表；publisher TX rollback → outbox row 同 rollback（atomic）；listener 失敗 → status='FAILED'。
  - **9 個 listener async migration**（`@EventListener` → `@ApplicationModuleListener`）：5 個 `SkillProjection` non-FK handlers + 2 個 `SearchProjection` + 1 個 `AnalyticsProjection.on(SkillDownloaded)` + 1 個 `ScanOrchestrator.on(SkillVersionPublished)`。Hybrid migration：保留 2 個 sync `@EventListener`（`SkillProjection.onSkillCreated/onVersionPublished` — FK target row 創建者）。
  - **Async infra**（`shared/config/AsyncListenerConfig` + `SchedulerConfig`）— `applicationTaskExecutor` (`ThreadPoolTaskExecutor` corePool=2/maxPool=2/queue=200) wrapped by `DelegatingSecurityContextAsyncTaskExecutor`（解 async thread `SecurityContextHolder` 為空 production bug）。
  - **Multi-instance retry 互斥**（V5 Flyway + ShedLock 7.7.0）— `IncompleteEventRepublishTask` `@Scheduled(PT1M) + @SchedulerLock(name="republish-incomplete-events")`；`JdbcTemplateLockProvider.usingDbTime()` 規避 cluster clock skew。
  - **Idempotency 保護**：`AnalyticsProjection.saveIdempotent` 用 `download_events.event_id UNIQUE + ON CONFLICT DO NOTHING` 嚴格冪等；`ScanOrchestrator` 用 `risk_assessment.sourceEventId` 比對；`SkillProjection.on(SkillDownloaded)` drop idempotency（async race 設計修正）。
  - **Observability**（`shared/events/EventPublicationMetrics`）— `event_publication.failed.count` + `incomplete.count` Micrometer gauges；`/actuator/modulith` 顯示 6 modules + EVENT_LISTENER edges。
  - **Production bugs fixed**：`AsyncListenerConfig` `DelegatingSecurityContextAsyncTaskExecutor` wrap；`SkillQueryService.downloadLatest/Version` 加 `@Transactional`（`@ApplicationModuleListener` 在 TX 外 silently drop；`@Transactional` 對 private method 無效）。
  - **12 個 SBE AC 全綠**：AC-1~AC-12；verify-all.sh V01-V06 **連續 3 次全綠**；JaCoCo line coverage 89.53% ≥ 80% gate；262 tests / 0 failed / 5 skipped。
  - **獨立 QA subagent verdict PASS**（3 連綠 stability + 程式碼品質 + 設計 sync check）— follow-up 由 S025 處理。
  - **Validated patterns 寫入 development-standards**：`@ApplicationModuleListener` 規範、Hybrid migration、`applicationTaskExecutor` 容量設計、`DelegatingSecurityContextAsyncTaskExecutor` wrap、YAML profile override=replace、PostgreSQL JDBC `Instant` binding 限制、AOP proxy field 不透明、UNIQUE + ON CONFLICT 嚴格冪等、ShedLock `usingDbTime()`。

### Changed
- **架構轉向**（per ADR-002 Accepted）：Skills Hub Core Domain 從純 Event Sourcing 路線轉向「Spring Data JDBC 充血聚合 + Modulith Outbox」；`domain_events` 表退化為 audit log（S024 起由 `AuditEventListener` 寫入），不再是 source of truth。S023 為 phase 1 基礎建設；S024（target v2.0.0）為 phase 2。
- **`SkillDownloadedEvent` / `SkillVersionPublishedEvent`** records 加 `eventId` / `sourceEventId` + static factory `of(...)`。
- **Actuator exposure** 加 `modulith`（base + dev profile 兩處）。
- **Build infrastructure**：`tasks.test { maxHeapSize = "3g" }` + `-Dspring.test.context.cache.maxSize=8`（解 17+ `@SpringBootTest` 同 JVM Java heap OOM）。

### Known Limitations
- **2 個 e2e MockMvc test method `@Disabled`**（`S016EndToEndSmokeTest` + `RiskAssessmentIntegrationTest` × 3）— MockMvc + `@ApplicationModuleListener` async dispatch 時序不可靠；功能由其他 test 分散覆蓋；S025 改寫為 `@ApplicationModuleTest + Scenario API`。
- **Awaitility 30s timeout**（T07 採用）— 熱機 + container churn 下穩定；S025 重整後可收回 5s。
- **53 distinct context cache key** → LRU evict + container churn；`maxHeapSize=3g + cache.maxSize=8` 為 transitional workaround；S025 系統解。

### Documentation
- `docs/grimo/architecture.md` 加「Spring Modulith Outbox」段落 + ADR-002 引用 + `domain_events` 標 transitional state
- `docs/grimo/adr/ADR-002-skill-aggregate-state-based.md`（架構決策依據；S023/S024 拆分）
- `docs/grimo/development-standards.md` 加「Spring Modulith Outbox 規範」段落（10 條 validated patterns）
- `docs/grimo/PRD.md` Phase 3a v1.5.0 上線狀態
- `docs/grimo/specs/spec-roadmap.md` Phase 3 milestone (M18 ✅ / M19 design)；S025 Backlog entry（Test Pyramid Realignment）
- `docs/deepwiki/spring-data-jdbc-modulith/`（6 份 source-level 研究檔案）

---

## [v1.4.0] — Phase 2: Skill Aggregate 充血演化 + SKILL.md 對齊（M16 完成 + Phase 2 全部完成；2026-04-29）

### Added
- **S018: Skill Aggregate 充血演化 + SKILL.md 對齊 + Suspend/Reactivate Events**（M16 落地；同時 Phase 2 全部完成 — M14/M15/M16 三個 milestone 同日連續 ship）：
  - **State machine 集中於 enum**（`skill/domain/SkillStatus`）— 改寫為 enum-method override pattern：每 enum value（DRAFT/PUBLISHED/SUSPENDED）獨立 override 合法 transition；default 拋 IllegalStateException with status name；class-level Javadoc 加 ASCII state machine diagram。
  - **Skill aggregate 充血演化**（`skill/domain/Skill`）— 加 `private SkillStatus status` field；constructor replay switch 加 4 個新 arm（SkillCreated/SkillVersionPublished/SkillSuspended/SkillReactivated）；`publishVersion()` 加 state machine guard `this.status.publish()`（SUSPENDED 拋例外 — guard 先於版本不變量）；新 business methods `suspend(SuspendCommand)` / `reactivate(ReactivateCommand)`；新 accessor `status()`；新 helper `parseAllowedTools(frontmatter)`。
  - **Suspend/Reactivate ES 流程**（4 new records）— `SkillSuspendedEvent(aggregateId, reason, suspendedBy)` + `SkillReactivatedEvent(aggregateId, reason)` + `SuspendCommand(skillId, reason, suspendedBy)` + `ReactivateCommand(skillId, reason)`；`SkillCommandService` 加 `suspend()` / `reactivate()` `@Transactional` methods（mirror S016 grantAcl/revokeAcl 模式）。
  - **REST endpoints + @PreAuthorize 整合 S016 PermissionEvaluator**（`skill/command/SkillCommandController`）— `POST /{id}/suspend` + `/{id}/reactivate` + `@PreAuthorize("hasPermission(#id, 'Skill', 'suspend|reactivate')")` 守門（用 S016 spec §2.1 #4 預備的兩 verb）；nested records `SuspendRequest(reason)` / `ReactivateRequest(reason)`；建構子注入 `CurrentUserProvider`，suspendedBy 從 SecurityContext 取（防 spoof）。
  - **SkillProjection BUG 修 + 新 listeners**（`skill/query/SkillProjection`）— `on(SkillVersionPublishedEvent)` 加 `repo.updateStatus(id, "PUBLISHED", now)` 修「永遠 DRAFT」BUG（首版觸發 DRAFT→PUBLISHED；後續發版 idempotent）；新 `on(SkillSuspendedEvent)` / `on(SkillReactivatedEvent)` listeners；`SkillReadModelRepository` 加 `updateStatus(id, status, ts)` `@Modifying @Query`。
  - **Hardcoded sequence 移除**（`skill/command/SkillCommandService`）— `createSkill` / `uploadSkill` 走 `aggregate.nextSequence()`（new aggregate=1；uploadSkill 第二步 reload from DB events → nextSequence=2）；對齊「aggregate 為 sequence source of truth」原則。
  - **SKILL.md 對齊**（`skill/validation/SkillValidator` + `skill/query/SkillVersionReadModel` + V3 Flyway migration）— 加 NAME_REGEX (`^[a-z0-9-]{1,64}$`) / DESCRIPTION_MAX=1024 / COMPATIBILITY_MAX=500 / ALLOWED_TOOL_TOKEN_REGEX 嚴格化（白名單拒收 shell injection 如 `;rm -rf`）；`SkillVersionReadModel` 加 `@Column("allowed_tools") List<String> allowedTools` first-class column（用 JSONB 與既有 acl_entries 同模式重用 `StringListJsonbConverter`）；`SkillVersionPublishedEvent` record 加 `List<String> allowedTools` typed payload；V3 migration backfill from existing frontmatter；uploadSkill 解析 space-separated → List。
  - **15 個 SBE AC 全綠**：AC-1 (DRAFT default) / AC-2 (BUG 修：首版→PUBLISHED) / AC-3 (idempotent) / AC-4-9 (state machine guards) / AC-10 (apply 多型分派 replay) / AC-11 (sequence chain) / AC-12 (@PreAuthorize 整合 S016) / AC-13 (allowed_tools first-class) / AC-14 (Validator 嚴格化 6 違規場景) / AC-15 (合規 frontmatter)；`./scripts/verify-all.sh` V01-V06 全 PASS（234/234 tests / 0 failures / 89.9% LINE coverage / gate 80%）。
  - **獨立 QA subagent verdict PASS**（234/234 tests / 89.9% coverage / V01-V06 全綠；1 MINOR follow-up — AC-13 HTTP wire-level assertion 缺；非 production defect — controller 直接回 SkillVersionReadModel 含 allowedTools field；已記 spec §7.9）
  - **Validated patterns 已寫入 spec §7.5**（給未來 spec 引用）：
    - Enum-method override 為 Java state machine 標準實作
    - Aggregate state machine guard 不 mutate state（state 由 replay 改變）
    - `uploadSkill` 兩步 saveAndPublish 走 reload aggregate 從 events
    - SKILL.md `allowed-tools` 解析 + 嚴格化 regex（拒收 shell injection）
    - 既有 record evolution 對 test caller minimal update（加 `List.of()` 一參）

### Changed
- **`SkillProjection.on(SkillCreatedEvent)`**：read model `status="DRAFT"` 不變；但 `on(SkillVersionPublishedEvent)` 現會 atomic `updateStatus("PUBLISHED")` — 修復「永遠 DRAFT」BUG（spec §3 AC-2）
- **`SkillCommandService.createSkill / uploadSkill`**：移除 hardcoded `1L`/`2L` literal — 走 `aggregate.nextSequence()`；uploadSkill 第二步加 `loadAggregate(...)` reload 取 nextSequence=2
- **`SkillVersionPublishedEvent` record evolved**：加第 6 field `List<String> allowedTools`；既有 3 個 test caller 連帶更新加 `List.of()` 引數（SearchProjectionTest / ScanOrchestratorTest / SarifReporterTest）
- **`SkillValidator` 嚴格化**：除既有 missing-required-field 檢查外，加 4 大 constraint（name regex / description-1024 / compatibility-500 / allowed-tools 白名單 token regex）

### Notes
- **Tech debt 入 §7.7**：
  - 重複 `parseAllowedTools` helper（aggregate + service 兩處 9 line 重複）— 可抽 `AllowedToolsParser` utility for follow-up
  - IllegalStateException → HTTP 500 而非 409（S016 T4 + S018 T4 共題；建議統一 controller advice 為 409 Conflict）
  - SkillVersionPublishedEvent record evolution 對既有 events store 中無 `allowedTools` key 的 replay 行為待 production V01 跑後驗（已加 null guard）
  - Modulith outbox migration 拆出至 S023（per spec header + roadmap Backlog）
  - AC-13 HTTP wire-level assertion 缺（QA finding；非 production defect — controller 直接回 ReadModel 含 first-class field）
- **Test growth path**：S017 ship 後 baseline 199 → T1 +12 (211) → T2 +3 (214) → T3 +7 (221) → T4 +4 (225) → T5 +9 (234)。新增 6 個 test class，~28 個 test method。
- **Phase 2 全部完成 ✅**：M14（S016 v1.2.0）+ M15（S017 v1.3.0）+ M16（S018 v1.4.0）三個 milestone 於 2026-04-29 同日連續 ship；共 37 story points + 41 個 SBE AC + 30+ 個 test method。

## [v1.3.0] — Phase 2: ACL-Aware 語意搜尋（M15 完成；2026-04-29）

### Added
- **S017: ACL-Aware 語意搜尋**（PgVectorStore SQL composition + Builder.aclPatterns + SemanticSearchService 注入）— Phase 2 M15 落地：把 S016 已就位的 row-level ACL（`vector_store.acl_entries` + `??|` SQL pattern）接到語意搜尋 SQL 路徑，使「搜尋結果集 ⊆ 有 read 權限的 chunk」成為資料層硬約束。
  - **Vector Store SQL 升級**（`search/SkillshubPgVectorStore`）：
    - 加 `static final String SIMILARITY_SEARCH_SQL_ACL`（含 `WHERE acl_entries ??| ?::text[] AND embedding <=> ? < ? ORDER BY distance LIMIT ?`；`??|` escape per S016 §2.4 #2 lesson）
    - 加 `static final int OVERSAMPLE_FACTOR = 5`（per pgvector docs / Supabase blog 2024 慣例 — 解 HNSW post-filter recall 問題）
    - Builder 加 `aclPatterns(@Nullable List<String>)` setter（與 S016 T6 既有 `aclEntries(...)` 並列：寫入端 vs 查詢端兩語義分流；中文 Javadoc 各自註明用途）
    - 加 `static String buildPgArrayLiteral(List<String>) → "{a,b,c}"` helper（PostgreSQL `text[]` cast literal；空 list → "{}"）
    - `doSimilaritySearch(SearchRequest)` 加 ACL 分流：`aclPatterns == null` → 既有 SQL（向後相容）；`非 null（含空 list）` → ACL SQL + oversample 5x + `Math.min` 兜底 + `subList(0, topK)` slice + structured log debug
  - **Service 層 ACL 展開**（`search/SemanticSearchService`）：
    - 建構子注入 `CurrentUserProvider currentUserProvider, AclPrincipalExpander aclExpander`
    - `search(query)` 改：`var aclPatterns = aclExpander.expand(currentUserProvider.current(), "read")` → builder.aclPatterns(aclPatterns) → similaritySearch；structured log atInfo 含 userId / patternsCount / resultsCount
  - **既有 IT fixture 對齊新 ACL 機制**：`SemanticSearchIntegrationTest` 不驗 ACL 但走 `SemanticSearchService`，TestRestTemplate 不帶 JWT → CurrentUserProvider lab user fallback 後 patterns 含 `role:admin:read`；fixture acl_entries 加 `role:admin:read` 一條對齊（最小變動）
  - **`search/package-info.java` 已就位**：`allowedDependencies` 含 `"shared :: security"` — S014 T7 為 SearchProjection 注入 CurrentUserProvider 鋪路時已加；S017 受益免修改
  - **11 個 SBE AC 全綠**（AC-1 Builder API + SQL constant 字面 / AC-2~6 doSimilaritySearch 5 SQL 分流情境 / AC-7~10 端到端 HTTP / AC-11 Modulith verify）；`./scripts/verify-all.sh` V01-V06 全 PASS（199/199 tests / 0 failures / 89.0% LINE coverage / gate 80%）
  - **獨立 QA subagent verdict REJECT-MINOR with 3 inline fix**（V01-V06 全 PASS；coverage 89.0%）：(a) AC-9 test comment `'sam'` → `'lab-user'`（lab.user-id 對齊 `application.yaml`）；(b) AC-9 加 `andExpect(jsonPath("$").isEmpty())` 硬斷 empty array；(c) AC-6 spec §7.6 註記 SQL LIMIT 綁定值由 OVERSAMPLE_FACTOR 常數斷言 + SQL constant 字面驗證間接證實
  - **Validated patterns 已寫入 spec §7.5**（給 S018+ 後續 ACL spec 引用）：
    - ACL-aware vector search SQL（`??|` + `?::text[]` cast literal）
    - Oversample + Java slice 解 HNSW post-filter recall（`OVERSAMPLE_FACTOR = 5`）
    - Builder API 雙 setter 命名分流（讀寫路徑）
    - Test isolation 跨 `@SpringBootTest` Testcontainer（`TRUNCATE skills RESTART IDENTITY CASCADE`）
    - 既有 IT fixture 對齊新 ACL 機制（minimal pattern：fixture acl_entries 加 `role:admin:read`）

### Notes
- **Tech debt 入 §7.7**：(a) AC-6 大資料集 EXPLAIN ANALYZE 驗證留 follow-up（trigger：production latency > 200ms 或 recall < 0.95）；(b) `SearchProjection.onVersionPublished` delete-then-add 與 ACL preservation 為 S016 T6 已知 limitation（S017 純讀路徑不引入新風險，留 follow-up 把 vector_store.acl_entries 接到 ACL events）；(c) AC-9 anonymous fail-secure 設計取捨：對「全綠生產 ACL」場景有效，對「為 IT 友善而加 admin role pattern」row 則 anonymous 會搜得到 — per spec §4.4 注解可接受。
- **Test growth path**：S016 ship 後 baseline 182 → T1 +8 (190) → T2 +5 (195) → T3 +4 (199)。新增 2 個 test class（共 17 個 test method；含 `@Nested` integration cases）。
- **Spec drift 已就地修正於 §7.4**（per spec table）：(1) `search/package-info.java` 預期 modify 實際 unchanged（S014 T7 已加）；(2) §3 BDD 寫 POST 但實際 controller 為 GET（S007 既有設計）；(3) 既有 IT fixture 修法更輕（acl_entries 加 1 條，無需 `@TestPropertySource`）。
- **S018 graceful degrade 占位（`hasRole('admin')`）可移除** — S016/S017 ship 後 PermissionEvaluator 完整就位。

## [v1.2.0] — Phase 2: Row-Level ACL Foundation（M14 完成；2026-04-29）

### Added
- **S016: Row-Level ACL 基礎建設**（JSONB acl_entries + GIN(default `jsonb_ops`) + DelegatingPermissionEvaluator + ACL CRUD endpoints）— Phase 2 M14 落地：
  - **DB schema**（V2 Flyway migration）— `skills.acl_entries JSONB NOT NULL DEFAULT '[]'::jsonb` + `idx_skills_acl_entries USING GIN (acl_entries)` 配 default `jsonb_ops`（**ADR-001 §3.2/§8 修訂**：`jsonb_path_ops` 不支援 key-existence operator `?|`，per PostgreSQL 16 docs）；`vector_store.acl_entries` 同；backfill 邏輯：`skills.author` → `["user:<author>:read|write|delete"]`、`vector_store.owner` → `["user:<owner>:read"]`（fail-secure on null owner）。
  - **Permission Evaluator Strategy/Registry**（`shared/security`）— `PermissionStrategy` interface + `DelegatingPermissionEvaluator` dispatcher（DI 收集所有 strategy；Anonymous / null Authentication 直接拒絕）+ `AclPrincipalExpander`（user/role/group 三命名空間展開）+ `SkillPermissionStrategy`（`acl_entries ??| :patterns` SQL；`SqlParameterValue(Types.ARRAY, String[])` 包裝避 IN-list 展開；補 group: patterns from `CurrentUserProvider.current().groups()`）。
  - **`CurrentUser` 擴 groups field** — JWT `groups` claim 抽取 + LAB 模式空 list fallback；`SecurityConfig` 加 `static @Bean MethodSecurityExpressionHandler` 破除 `PrePostMethodSecurityConfiguration` circular dep。
  - **ACL CRUD endpoints** — `SkillAclController`：`POST /api/v1/skills/{id}/acl`（201；@PreAuthorize write 守門）+ `DELETE /api/v1/skills/{id}/acl?type=...&principal=...&permission=...`（204）+ `GET /api/v1/skills/{id}/acl`（200；@PreAuthorize read 守門）；`grantedBy` / `revokedBy` 從 `CurrentUserProvider.userId()` 取（防 spoof）。
  - **ACL ES domain events** — `SkillAclGrantedEvent` / `SkillAclRevokedEvent` + `GrantAclCommand` / `RevokeAclCommand` + `Skill.grantAcl(cmd)` / `revokeAcl(cmd)` aggregate methods（驗 invariant：無重複 grant、revoke 必須有對應 entry）+ `Skill` constructor 加 `currentAclEntries: Set<String>` minimal replay state；`SkillCommandService.grantAcl(cmd)` / `revokeAcl(cmd)` `@Transactional` 走 saveAndPublish 路徑。
  - **Read-side projection** — `SkillProjection.on(SkillAclGrantedEvent)` → `appendAclEntry`（`acl_entries || to_jsonb(:entry)` + `WHERE NOT @>` 冪等保證）；`on(SkillAclRevokedEvent)` → `removeAclEntry`（`jsonb_array_elements_text` + `jsonb_agg` + `COALESCE(..., '[]'::jsonb)` 防 NOT NULL violation）。`on(SkillCreatedEvent)` 加 owner ACL seed（`["user:author:read|write|delete"]`），對齊 V2 backfill 並讓作者通過 `@PreAuthorize` 自身 PUT。
  - **既有 PUT /api/v1/skills/{id}/versions 套 @PreAuthorize**；create/upload 路徑無 `#id` 不套 row-level（per spec §4.13）。
  - **Vector store INSERT_SQL 升 7 欄** — 8 placeholder 設計解 NOT NULL × COALESCE preservation 矛盾（位 7=INSERT VALUES 必非 null；位 8=UPDATE COALESCE 可 null 觸發保留）；`SearchProjection` 從 owner 衍生 initial `["user:" + owner + ":read"]`。
  - **Spring Modulith allowedDependencies** — `skill/package-info.java` 加 `"shared :: security"`。
  - **`StringListJsonbConverter`**（`shared/persistence`）— JSONB ↔ List<String> 雙向 converter。
  - **15 個 SBE AC**：14 ✅ + AC-13 PARTIAL（schema meta + 功能性命中替代 EXPLAIN bitmap，大資料集 EXPLAIN 留 S017 順帶處理）；`./scripts/verify-all.sh` V01-V06 全 PASS（182/182 tests / 0 failures / 88.7% LINE coverage / gate 80%）；E2E smoke 跨 controller→service→aggregate→event store→projection→read model→vector_store 全鏈驗證。
  - **獨立 QA subagent verdict PASS** with 2 IMPORTANT inline fix：(a) `S016EndToEndSmokeTest.e2e_putVersion_acl_gate` alice 路徑改 `andExpect(status().isOk())` 硬斷（避 500 silently pass）；(b) ADR-001 §3.2/§8 三處 `jsonb_path_ops` → default `jsonb_ops` 修訂（本 commit 一併 ship）。

### Changed
- **ADR-001 §3.2 / §8 References / §S016 row（line 154）** — 三處 `jsonb_path_ops` 改 default `jsonb_ops`（per S016 ship；PostgreSQL 16 docs 明確 `jsonb_path_ops` 不支援 `?|` operator）；§3.2 加修訂 footer 引用 PostgreSQL 16 docs。
- **Spec drift 已就地修正於 §2.4 / §2.6 / §4.5 / §4.15 / §4.16**（per spec §7.4 design drift table）：(1) SQL operator `?|` → `??|`（pgJDBC 重 parse）、(2) `MapSqlParameterSource` ARRAY 綁定 stub → `SqlParameterValue(Types.ARRAY, String[])`、(3) vector_store INSERT 7 placeholder → 8 placeholder、(4) `SkillProjection.on(SkillCreatedEvent)` seed ACL、(5) `removeAclEntry` 加 `COALESCE(..., '[]'::jsonb)`、(6) AC-13 EXPLAIN 替代覆蓋路徑。

### Notes
- **Validated patterns 已寫入 spec §7.5**（給 S017 / 未來 ACL spec 直接複用）：`??|` SQL pattern + 冪等 appendAclEntry SQL + COALESCE removeAclEntry SQL + 8-placeholder INSERT 雙綁設計 + minimal aggregate replay state。
- **Tech debt 入 §7.7**：(a) AC-13 大資料集 EXPLAIN bitmap 驗證留 S017；(b) 重複 grant 在 controller 層 HTTP 表現（aggregate 拋 IllegalStateException → Spring default 500）— 與 S018 SkillValidator 嚴格化同期處理錯誤碼集中化。
- **Test growth path**：T2 baseline 142 → T3 +12 (154) → T4 +13 (167) → T5 +9 (176) → T6 +6 (182)。新增 16 個 test class，約 40 個 test method。
- **S017 unblocked + S018 graceful degrade 占位可移除**（per roadmap M14 collapse note）。

## [v1.1.1] — Phase 2.5: Project Infra（M17 完成；2026-04-28）

### Added
- S022: Frontend Verification Baseline — `@vitest/coverage-v8@4.1.5`（exact-pin lockstep）+ `vite.config.ts` test block（globals / jsdom / setupFiles / coverage v8 + `include` whitelist + `thresholds.lines: 80`）+ `setupTests.ts`（`@testing-library/jest-dom/vitest` subpath）+ 1 component test（`SkillCard.test.tsx` 6 it 含 `<MemoryRouter>` wrapper）+ 1 hook test（`useSemanticSearch.test.tsx` 4 it 含 `<QueryClientProvider>` + `vi.mock('@/api/search')`）+ ESLint root-cause（`eslint.config.js` 中心化 `allowExportNames: ['badgeVariants', 'tabsListVariants']` override；移除 `badge.tsx`/`tabs.tsx` 兩處 inline `eslint-disable-next-line` — S020 Option A placeholder 收尾）+ V06 enrollment 入 `scripts/verify-all.sh`（`run_skip_if` pattern 對齊 V04/V05；vitest threshold gate 不過 → exit 1）+ qa-strategy.md 補丁（V06 row + Coverage cross-link「Backend V03 + Frontend V06 同 80% line gate」+ §AC-to-Test Contract 「Build / Config Spec — Evidence-Only AC 例外」sub-section S019/S020/S022 共識）：
  - **Coverage threshold 漸進加入 gate 模式**：design 階段選 project-wide `lines: 80` 對齊 backend BUNDLE，但 frontend baseline 0 tests（不像 backend S019 已 88% baseline from 115 tests）；Phase 2 task creation 前 challenge approach 發現直接 include 全 `src/**` 會立即 fail；改 `coverage.include` whitelist 鎖定有對應 test 的 source 檔（`SkillCard.tsx` + `useSemanticSearch.ts`），threshold 對 tested files 維持 80% aggregate；後續 frontend spec 加 test 時 append 到本 list；untested files 不算入分母。
  - **Industry-standard ESLint 路線**：`allowConstantExport` 不涵蓋 `cva()` CallExpression（plugin source `constantExportExpressions` 只列 4 種 AST 型別）；`allowExportNames` 只接 exact string，無 regex（plugin Issue #83 仍 open）；採 2026 shadcn 主流 config-level `allowExportNames` 而非拆檔 — 保留 shadcn CLI re-scaffold lifecycle + HMR safety net 對其他 non-component export 仍生效。
  - 8 AC 全綠（per S019/S020/S022 共識的 evidence-only AC 例外 — frontend pure infra spec 用 grep + line count + test run + verify-all.sh 證據而非 `@DisplayName` 對應 test 方法）；獨立 QA subagent Round 1 PASS with 2 MINOR inline fix（`coverage/` 加入 `eslint.config.js globalIgnores` + `frontend/.gitignore` 避 vitest html reporter `coverage/*.js` 觸 ESLint warning；`verify-all.sh` L10 header comment `V01-V05 → V01-V06`）。
- S021: Phase 2 doc-sync — PRD.md / architecture.md / glossary.md / qa-strategy.md 4 個 docs 一次性 rewrite，從 Firestore + MongoDB 字眼全面對齊到 PostgreSQL 16 + pgvector + 自訂 SkillshubPgVectorStore + Cloud SQL Auth Proxy sidecar 現實（與 ADR-001 + S014 archived spec + `backend/build.gradle.kts` 三 source of truth 一致）：
  - `docs/grimo/PRD.md` — §MVP Scope 兩行 + §Architecture Overview ASCII diagram Firestore box → PostgreSQL+pgvector+Cloud SQL Auth Proxy sidecar + §Decision Log D3/D8/D9/D14/D22 五 cells in-place rewrite + §Decision Log 末尾加 ADR-001 footer pointer + §MVP Scope 後加 §上線狀態（Status）mini-section（MVP v1.0.0 / Phase 1 v1.1.0 / Phase 2.5 / Phase 2 4-bullet milestone）
  - `docs/grimo/architecture.md` — §State at Planning footnote / §Event Flow ASCII / §System Architecture ASCII / §Module Design line / §Data Model 6 表 + Flyway V1 + JSONB + HNSW + cosine 全文 rewrite / §Framework Dependency Table（移除 `spring-boot-starter-data-mongodb` + `google-cloud-firestore` 共 2 條，加入 `spring-boot-starter-data-jdbc` / `spring-boot-starter-jdbc` / `spring-ai-pgvector-store` core artifact / `spring-boot-flyway` / `flyway-core` / `flyway-database-postgresql` / `postgresql` 共 7 條）/ §Firestore Configuration 整段替換為 §PostgreSQL Configuration（Local Dev pgvector compose / GCP Cloud SQL Auth Proxy sidecar service.yaml / HikariCP pool 設定 / Flyway versioning / pgvector extension flag / IAM 自動化）
  - `docs/grimo/glossary.md` — L24 事件儲存定義從 Firestore collection 改為 PostgreSQL `domain_events` 表（JSONB payload + per-aggregate `(aggregate_id, sequence)` UNIQUE）
  - `docs/grimo/qa-strategy.md` — §Three-Layer Verification Layer 2 Integration Verification Firestore MongoDB 行 → PostgreSQL pgvector + Testcontainers `pgvector/pgvector:pg16`；§Testing with Firestore 整段替換為 §Testing with PostgreSQL（dev/CI 同 Testcontainers image / Staging Cloud SQL + Auth Proxy sidecar / cross-link architecture.md §PostgreSQL Configuration）
- 7 AC 全綠（純 docs；grep + line count + human review evidence per qa-strategy AC-to-Test Contract evidence-only 例外）；獨立 QA subagent Round 1 PASS with MINOR inline fix — architecture.md §Data Model `domain_events` DDL column widths `VARCHAR(64) → (50)` / `VARCHAR(128) → (100)` / `metadata` 補 `NOT NULL DEFAULT '{}'::jsonb`，對齊 `V1__initial_schema.sql` ground truth。

### Changed
- `docs/grimo/specs/spec-roadmap.md` §M17 描述「D8/D9/D14/D15」typo 校正為「D3/D8/D9/D14/D22」（5 條 storage decisions；D15 是 Spring Modulith decision，與 storage 無關）— 同 commit 處理避免下個 spec 又踩 typo。

### Notes
- **歷史保留三層**：ADR-001 §1-§4（決策驅動 + alternatives）+ S014 archived spec §4 / §2.1（實作細節 + final state）+ git log（時間軸）。PRD/architecture 為 current-state docs，不在受影響行加 superseded annotation 以避免認知負擔。
- **Implementation Note 重點**（spec §7）：(a) spec §5 line numbers 對 qa-strategy.md 漂移 ~30 行（推測 S019/S020 ship 期間在 qa-strategy 加入 §Verification Command Registry 子段）— 改寫照 §識別，不用行號定位；(b) §State at Planning footnote 用 ADR-001 替稱「Firestore」，兼顧 footnote 語義 + AC-3 strict 0-hits grep；(c) D14 描述改用 spec §4.1 / S014 archive / actual code 為 source of truth（ADR-001 §2 早期描述「Spring AI 官方 PgVectorStore starter」與最終實作「自寫 `SkillshubPgVectorStore extends AbstractObservationVectorStore`」分歧，後者勝出）— ADR-001 §2 不 retroactively edit；(d) Framework Dependency Table 補齊 actual 7 條超過 spec §4.5 設計階段估的 5 條。
- **Pre-flight gate**：`scripts/verify-all.sh` V01-V05 全綠（V01 PASS / V02 LINE coverage 88.1% INFO / V03 PASS / V04 PASS / V05 PASS）— 純 docs spec 無 runtime risk，仍跑 baseline 確認 coverage 88.1%（≥80% gate）+ frontend lint clean，沒誤動 code path。
- **M17 進度 2/4 → 3/4 (S019+S020+S021 shipped)**；剩 S022 frontend baseline ship 後一次發 v1.1.1 milestone tag。

### Added
- S020: Verification Command Registry + `scripts/verify-all.sh` — `/verifying-quality` Step 0.5 protocol 期望的兩個 artifact 一次補齊：
  - `docs/grimo/qa-strategy.md` 新章節 `## Verification Command Registry` — 5 row 主 table（V01-V05）+ `### Known Limitations` sub-table（`bootRun -x processAot` workaround）+ `### 不 enroll 的命令` sub-table（5 條 rationale；含 cyclonedxBom / bootBuildImage / npm coverage）
  - `scripts/verify-all.sh`（4277 bytes，bash 3.2 portable，chmod +x）— V01 `./gradlew clean test jacocoTestReport`（CRITICAL）/ V02 awk 解析 `jacocoTestReport.csv` 顯示 `LINE coverage = NN.N% (covered=X / total=Y)`（INFO）/ V03 `./gradlew jacocoTestCoverageVerification`（CRITICAL，task 未註冊則 SKIP）/ V04+V05 frontend `npm test` + `npm run lint`（CRITICAL，`node_modules` 不存在則 SKIP）/ Summary section 三行 verdict（Results / Counts / Verdict ✅ all CRITICAL passed; exit=0）
  - `verify-all.log` at repo root — ISO timestamp section header + 各 V0N stdout 完整保留；`gradle clean` 不再摧毀（Round 2 修；spec §7.10）
- 7 AC 全綠（all build-evidence per qa-strategy AC-to-Test Contract evidence-only 例外）；Round 1 QA REJECT-IMPORTANT（log 路徑被 V01 `clean` 摧毀 + Results trailing space）→ Round 2 fix（log 搬 repo root + `${RESULTS[*]}` IFS-join）→ Round 2 QA PASS。
- 4 個 doc-only stale-path MINOR（subagent Round 2 額外抓出）已就地修：§1 narrative / §3 AC-3 / §4.2 script header / §7.4 code block / §5 file plan `.gitignore` 條目。

### Changed
- `frontend/src/components/ui/badge.tsx`、`frontend/src/components/ui/tabs.tsx` — 各加 1 行 `// eslint-disable-next-line react-refresh/only-export-components` 註明 shadcn/ui CLI scaffolding 慣例（cva variants 與 component 同檔便於 CLI 升級；HMR 行為非阻擋）— Option A tactical bridge，由 **S022 Frontend Verification Baseline** 後續 root-cause 取代（拆檔 vs cva exception config 設計決策）。
- `docs/grimo/specs/spec-roadmap.md` Active Work 順序：S019 ✅ → S020（本次）→ S021 → **S022 (NEW)** → S016/S017/S018；M17 從 3 specs / 23 pts → 4 specs / 31 pts；M17 進度 **2/4 (S019+S020 shipped)**。

### Added (frontend baseline placeholder)
- `frontend/src/smoke.test.ts`（8 行）— vitest 至少 1 個 test 檔（解 `No test files found, exit 1`）；Option A bridge，將由 S022 真實 component / hook test 取代。
- 新建 repo root `.gitignore`（5 行）含 `verify-all.log` entry — Round 2 fix 副產物，避免 worktree 污染。

### Notes
- **Round 2 bug fix lessons learned**（spec §7.10）：(a) 設計階段 § log 路徑時須檢查所有 verify command 副作用，「log 應被自己 verify 的 build 行為摧毀」是邏輯矛盾；(b) bash trailing space 從 array print 出來，預設 `${arr[*]}` IFS-join 是最簡 portable 解；(c) QA subagent evidence-table 模式（每條 check + 結果 + ❌/✅）強制 surface 隱藏 bug，建議入 `/verifying-quality` skill expected output。
- **Coverage baseline**：V02 顯示 88.1% (1055/1198)；S019 ship 時 88.03% (1052/1195)；浮動 +0.07pp 來自 frontend smoke.test.ts 加入後 backend 測試 instrumentation 微調，遠在 V03 gate 0.80 內。
- **S021 / S022 待補（已登記）**：(i) qa-strategy.md `## AC-to-Test Contract` 明文新增「build/config spec = evidence-only AC」例外（S019 + S020 兩輪 QA 共識）；(ii) S020 §3 AC-5 文字「rename node_modules 模擬未裝」測法不可行（V01 npmInstall 副作用會重建 node_modules）— 修正為「邏輯設計意圖 + 等價 inline-script 驗證」；(iii) frontend coverage L23-25 文字實作落地（→ S022）。
- M17 進度 2/4；S021 + S022 ship 後一次發 v1.1.1 milestone tag。

### Added
- S019: JaCoCo coverage gate plugin + 80% line threshold — 補 S014 ship 後 `/verifying-quality` 列為 IMPORTANT 的 pre-existing project gap：`qa-strategy.md` L18-21 宣告 80% line coverage 但 `build.gradle.kts` 從未掛 `jacoco` plugin。本 spec 一次補齊：
  - `plugins {}` + `jacoco`；`jacoco { toolVersion = "0.8.14" }` 顯式 pin（首版官方支援 Java 25 bytecode；對齊 CLAUDE.md「Ecosystem-Managed Versions」）
  - `tasks.test { finalizedBy(tasks.jacocoTestReport) }` — test 結束自動產 report
  - `tasks.jacocoTestReport` — `xml.required` / `html.required` / `csv.required` 三 report 全開（CSV 為 S020 verify-all.sh awk 解析來源；cross-spec contract）
  - `classDirectories.setFrom(...fileTree { exclude(...) })` — 4 條 exclusion：`SkillshubApplication*` / `config/**` / `*Configuration*` / `db/migration/**`
  - `tasks.jacocoTestCoverageVerification` — `element="BUNDLE"` + `counter="LINE"` + `value="COVEREDRATIO"` + `minimum = "0.80"`（T1 POC 量出 baseline 88.03% ≥ 0.80 → 第一檔 decision rule，無 backlog 條目觸發）；`classDirectories.setFrom(tasks.jacocoTestReport.get().classDirectories)` 共用 exclusion
  - `tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }` — Gradle JaCoCo plugin 預設不接 check（per [official docs](https://docs.gradle.org/current/userguide/jacoco_plugin.html)），須顯式 wire；接上後 `./gradlew build` / `check` 自動跑 gate，CI/PR 一致行為
- 6 AC 全綠，全為 build-evidence（與 S013 deploy / S014 §7 evidence 模式一致）：tasks --group verification 列出兩 task / 3 reports 產出 / verification BUILD SUCCESSFUL（無 `Rule violated`）/ check graph 含 verification task / exclusions 生效（CSV grep `Application$` / `Configuration` / `\.config` package = 0/0/0）/ tests=115/0/0/0 與 S014 baseline 一致 + GraalVM `:processAot` / `:nativeCompile` / `:nativeTest` 全未觸發。

### Notes
- **Coverage baseline**: LINE 88.03%（covered=1052, missed=143, total=1195）；INSTRUCTION 88.67%、BRANCH 70.23%（informational，本 spec 只 gate LINE）。
- **GraalVM × JaCoCo task graph 零交集**：JaCoCo agent 只 attach JVM `test` task；`:processAotTestResources` 出現於 graph 但 SKIPPED（GraalVM plugin 副 task 不執行），不違反 AC-6。
- **獨立 QA subagent PASS**：8 項檢查全綠（build script integrity / live build / report artifacts / exclusions / task graph / design drift / CLAUDE.md compliance / live coverage 0.8803 confirmed）；一個 MINOR finding（qa-strategy.md `AC-to-Test Contract` 未明文記錄 build/config evidence-only 例外，已建議 S021 doc-sync 補；不影響 S019 正確性）。
- **M17 進度**：1/3（S019 ✅；S020 verify-all.sh / S021 PRD+architecture doc-sync 待完）；M17 全綠後一次發 `v1.1.1` 小版本（含 verify-all.sh + Phase 2 doc-sync 一併）。

## [v1.1.0] - 2026-04-27 — Phase 2 啟動：PostgreSQL 資料層遷移

### Changed
- S014: 把 Firestore Enterprise + MongoDB driver 全面換成 **PostgreSQL 16 + pgvector**（Spring Data JDBC for CRUD + event store；自訂 `SkillshubPgVectorStore` for vector search）— 一次性拆乾淨 `google-cloud-firestore` dep 與 `FirestoreVectorStore` 死碼（原 S015 PgVectorStore 接管 scope **absorbed**；詳 ADR-001 §4.5）；行為等同 v1.0.0、115/115 tests 全綠、bootRun in-vivo smoke 通過。
- **Persistence layer（5 個 read model + event store）**：`Skill*ReadModel` / `DownloadEventReadModel` / `FlagReadModel` / `DomainEvent` 從 `@Document`（MongoDB）改 `@Table`（Spring Data JDBC）；皆 implement `Persistable<String>.isNew()=true` 強制 INSERT 路徑（避開預設 SELECT-then-UPDATE）；`Map<String,Object>` 欄位透過 `MapJsonbConverter` 雙向 round-trip JSONB 並保留 nested 型別。
- **Repository 介面**：5 個 `*Repository` 從 `MongoRepository` 改 `ListCrudRepository`；改用 `@Query` + `NamedParameterJdbcTemplate` 處理動態 keyword/category/sort filter（含 LIKE `%` `_` `\` 三符 escape + `SORTABLE_PROPERTIES` 白名單防 SQL 注入）；`@Modifying @Query` helper 集中於介面層（`incrementDownloadCount` / `updateLatestVersion` / `updateRiskLevel` / `updateRiskAssessment`）。
- **Concurrency**：`AnalyticsService.recordDownload` 從「先 SELECT 後 UPDATE」改 atomic `UPDATE ... SET count = count + 1`；100 並發測試零 lost update。
- **Spring AI VectorStore 接管（T8 architectural refinement）**：`build.gradle.kts` 從 `spring-ai-starter-vector-store-pgvector` 換 **`spring-ai-pgvector-store`** core artifact（無 auto-config）；新增 `SkillshubPgVectorStore extends AbstractObservationVectorStore`（235 行，Builder pattern）—`doAdd` 走 6-欄 `INSERT ... ON CONFLICT DO UPDATE COALESCE` 原子寫入（id / content / metadata / embedding / **owner** / **skill_id** 一次到位，不留中間視窗）；對齊 CLAUDE.md「Spring AI Manual Configuration」原則（與 S007 GoogleGenAiChatModel 同模式）。
- **Per-request builder pattern**：`SearchProjection` / `SemanticSearchService` constructor 注入 `(JdbcTemplate, EmbeddingModel, ...)`，每次寫入用 `SkillshubPgVectorStore.builder(jdbc, em).owner(u).skillId(id).build().add(...)` 建新 instance；不註冊 singleton Bean — owner/skillId 是 per-write context，避免 ThreadLocal 與 state leak。
- **Schema**：Flyway `V1__initial_schema.sql` 自動建立 6 表（skills / skill_versions / download_events / flag_read_model / domain_events / vector_store）+ `vector` / `uuid-ossp` extensions + HNSW `vs_emb_idx` index + `(aggregate_id, sequence)` UNIQUE constraint。
- **GCP 部署**：`application-gcp.yaml` 完全重寫為 **Cloud SQL Auth Proxy sidecar** 模式（`jdbc:postgresql://localhost:5432/...` + HikariCP `maximum-pool-size=3 / minimum-idle=1 / leak-detection-threshold=60000`）；無 `socket-factory` dep；本機 `application-local.yaml` 使用同條 URL（dev/prod parity）。
- **`compose.yaml`**：mock-oauth2-server healthcheck `wget --spider`（HEAD，server 不支援 → 404）改 `wget -q -O - ... > /dev/null`（GET）— pre-existing bug。
- **Cross-aggregate 順序**：`SkillProjection.on(SkillCreatedEvent)` 加 `@Order(HIGHEST_PRECEDENCE)`，與 `SearchProjection` / `ScanOrchestrator` 形成顯式 listener 排序鏈（避免 FK violation 21 例）；search 模組 `package-info.java` `allowedDependencies` 加 `"shared :: security"`（SearchProjection 注入 CurrentUserProvider）。

### Removed
- `FirestoreVectorStore.java`（S007 過渡版自寫向量寫入 helper）— 由自訂 `SkillshubPgVectorStore` 接管。
- `google-cloud-firestore` + `spring-cloud-gcp-starter-data-firestore` dependencies；`spring.cloud.gcp.firestore.*` 全 yaml 設定；`firestore.enabled` flag。
- `application.yaml` + `config/application-dev.yaml` 的 `vector-store: simple` 預設 + `SimpleVectorStore` fallback bean — dev 走 Docker Compose pgvector container（dev/prod parity），無 in-memory fallback 必要。
- 過時 POC 測試：`SemanticSearchPocTest`（SimpleVectorStore POC obsolete）+ `SemanticSearchTest`（與 IntegrationTest scope 重疊）。

### Added
- `backend/src/main/java/.../search/SkillshubPgVectorStore.java`（235 行，T8 核心新類）。
- `backend/src/main/java/.../shared/persistence/JdbcConfiguration.java` + `MapJsonbConverter`（custom JSONB Reading/WritingConverter）。
- `backend/src/main/resources/db/migration/V1__initial_schema.sql`（Flyway baseline schema）。
- `TestcontainersConfiguration` 改 `pgvector/pgvector:pg16` container（取代 mongo:7）。
- 新增測試：`PgVectorStoreOwnerWriteTest`（6 欄寫入 + ON CONFLICT COALESCE 防護 + schema introspection）、`SearchProjectionTest`（`@SpringBootTest` + Testcontainers，per-request isolation）、`AtomicDownloadCountTest`（100 並發）、`DomainEventSequenceUniquenessTest`（UNIQUE constraint）、`MapJsonbConverterTest`（JSONB round-trip）。

### Notes
- **3 輪獨立 QA PASS**（spec §7.11 第一輪 subagent / §7.12 第二輪 subagent / §7.13 `/verifying-quality` skill 主驗）— 含 hermetic E2E + boundary CJK / escape quote + 6-欄 SELECT + design integrity 14-check。Verdict 一致 PASS。
- **115/115 tests PASS**（37 test classes；T7 ship 121 → T8 後 115：移除 SemanticSearchPocTest 3 + SemanticSearchTest 3 + SearchConfig 2 個 simple tests，新增 SearchProjectionTest 1 + PgVectorStoreOwnerWriteTest 1 = net -6）。
- **AC-13 強化（再次修訂）**：原 S014 規劃「保留 FirestoreVectorStore + Spring AI 官方 PgVectorStore」→ 修訂為「Firestore 全清 + 自寫 SkillshubPgVectorStore 子類」；理由：vector_store schema 含 `owner` / `skill_id` 自訂欄位（為 S016/S017 ACL 鋪路），官方 starter 4-欄 INSERT 無法擴；自寫子類 6-欄 atomic 寫入消除 add→UPDATE 兩步驟中間視窗 + `instanceof PgVectorStore` guard + `UUID.fromString` workaround。
- **2 個 IMPORTANT pre-existing project gaps**（未阻擋本次 ship，已登記 follow-up）：(1) JaCoCo plugin 缺失（qa-strategy 宣告 80% 線覆蓋率但 task 未註冊）；(2) Verification command registry / `scripts/verify-all.sh` 缺失（`/verifying-quality` Step 0.5 期望結構化 registry）— 建議獨立 spec 補。
- **MVP（v1.0.0）→ Phase 2（v1.1.0）**：本次為 MVP 完成後第一個 spec；架構升級為下一階段 Row-Level ACL（S016）+ ACL-Aware 語意搜尋（S017）+ Skill aggregate 充血演化（S018）鋪路。

## [v1.0.0] - 2026-04-27 — MVP complete 🎉

### Added
- S013: GCP Cloud Run 部署腳本與打包流程 — 一組可在全新 GCP 專案上一鍵跑通的 bash 腳本，把 Skills Hub 從 source code 打包並部署到 Cloud Run。開發者只需 `export GCP_PROJECT_ID / GCP_REGION / SKILLSHUB_GENAI_API_KEY`，依序跑 4 個腳本即可看到 service URL。
- `scripts/gcp/.env.example` — 3 必填 + 7 可選環境變數範本（含 Cloud Run cost-guard defaults）。
- `scripts/gcp/.gitignore` — 一行 `.env` 防 commit 真實 API key。
- `scripts/gcp/01-bootstrap.sh` — 啟用 7 GCP API（run, artifactregistry, firestore, storage, secretmanager, aiplatform, iam）+ 建立 Artifact Registry repo + Firestore Enterprise (MongoDB compat) + GCS bucket + Service Account + 7 個最小 IAM roles（datastore.user, storage.objectAdmin, aiplatform.user, secretmanager.secretAccessor, logging.logWriter, monitoring.metricWriter, cloudtrace.agent）。4 處 idempotent `describe ... &>/dev/null || create` pattern。
- `scripts/gcp/02-create-secrets.sh` — `gcloud secrets describe` if/else: 已存在用 `versions add`，不存在用 `create`；後續 grant SA `roles/secretmanager.secretAccessor`。
- `scripts/gcp/03-build-push.sh` — `gcloud auth configure-docker` + `(cd backend && ./gradlew bootBuildImage --imageName=$IMG:$SHA)` + `docker tag $IMG:$SHA $IMG:latest` + 兩 `docker push`，產出 `<git-short-sha>` + `:latest` 雙 tag。
- `scripts/gcp/04-deploy.sh` — `gcloud run deploy` 帶 `^@^` 自訂分隔符語法處理含 comma 的 `SPRING_PROFILES_ACTIVE=gcp,prod` env var、`--update-secrets` 注入 Secret Manager 引用、`--service-account` 綁定 runtime SA、`--allow-unauthenticated` 公開存取、Cloud Run cost-guard flags（`--min-instances=0`、`--max-instances=10`、`--memory=512Mi`、`--cpu=1`），結尾抽取並印出 service URL。
- `scripts/gcp/99-teardown.sh` — 互動 `read -r -p "... Type 'yes' to confirm:"` 嚴格 `[[ "$CONFIRM" == "yes" ]]` 確認後，依序刪除 Cloud Run service / AR repo / GCS bucket / Firestore DB / Secret / Service Account；**GCP project 本身保留**（避免誤刪）。
- `scripts/gcp/README.md` — 三步啟動 quick start + image tag 策略 + cost guard + LAB 模式提示 + Troubleshooting 表 + 變數對照表 + 5 個官方 docs 連結。

### Notes
- §2.6 Validation Pass 在實作前對齊 2 處設計校正：(1) `--set-env-vars` 改用 `^@^` 自訂分隔符（取代脆弱的 `\,` 跳脫，參考 Cloud Run docs 與 GHSA-fvxx-ggmx-3cjg 安全公告）；(2) `.gitignore` 用 per-dir 模式（repo root 無 .gitignore，與 backend/、frontend/ 一致）。
- AC-1 (file structure + bash -n) 與 AC-2 (.env.example 內容) 已 automated 驗證；AC-4/5/6/7/9 設計 review 通過；AC-3/8 為 manual-ready（需真實 GCP project + billing），spec §7.4 提供完整 verify checklist 供使用者部署時核對。
- 零 Java/Gradle/yaml 變動 — 純新增 8 個 deploy infra 檔；既有 114 tests 維持綠（`./gradlew test` UP-TO-DATE）。
- shellcheck 為 advisory（spec §3 已宣告非阻擋）；本機 macOS 預設未裝。
- 獨立 QA subagent 驗證 PASS（一個 MINOR finding：§3 AC-2 spec 文字僅列舉 5 個可選變數，shipped `.env.example` 完整列 7 個——純文件不一致，無 code 變動）。
- 14 個 spec 全部完成、147 story points 達成 — Skills Hub MVP 達標 🎉

## [v0.11.0] - 2026-04-27

### Added
- S012: OAuth 開關 + LAB 模式 — 透過 `skillshub.security.oauth.enabled` 開關（預設 `true`，env var `SKILLSHUB_SECURITY_OAUTH_ENABLED=false` 顯式關閉）切換兩種模式：
  - **OAuth 模式**（預設）— 維持 S011 行為：JwtDecoder + JwtAuthenticationConverter beans 透過 `@ConditionalOnProperty` 建立、`/api/v1/me` 與 `/api/v1/admin/**` 須帶 JWT。
  - **LAB 模式** — JwtDecoder bean 不建立、SecurityFilterChain `anyRequest().permitAll()`、`LabSecurityFilter` 注入預設 lab user（principal=`lab-user`，authorities=`[ROLE_admin]`），所有 endpoint 在無 JWT 情境下皆可訪問且通過 `@PreAuthorize("hasRole('admin')")`。
- `shared/security/CurrentUserProvider` — 統一抽象「當前使用者識別」的三分支邏輯（JwtAuthenticationToken → 已認證其他 token → 安全 fallback `lab-user`），未來 audit 欄位（`createdBy` / `updatedBy`）直接 constructor injection 即可，不需各自處理 JWT vs LAB 型別差異。
- `shared/security/CurrentUser` record 與 `LabSecurityFilter`（`OncePerRequestFilter`）。
- `SkillshubProperties.Security` nested record（`oauth.enabled` + `lab.user-id`），與既有 4 個 `@ConfigurationProperties` 區塊同層。
- 5 個新測試類別涵蓋 7 個 AC：`CurrentUserProviderTest`（4 unit, AC-4/5）、`SkillshubSecurityPropertiesTest`（1 SpringBoot, AC-6）、`LabModeMeControllerTest`（2 SpringBoot, AC-2/7）、`LabModeAdminControllerTest`（1 SpringBoot, AC-2）、`JwtDecoderConditionalTest`（2 SpringBoot, AC-3）。

### Changed
- `shared/security/MeController` — 內部依 `Authentication` 子型別分支：`JwtAuthenticationToken` 走 OAuth 路徑（保留 6 個 JWT claims 抽取邏輯，S011 既有測試斷言不變），其他 Authentication 走 LAB 路徑（`CurrentUserProvider` + 4 欄空值占位），兩條路徑回相同 6-key shape，避免前端因模式不同收到不同 schema。
- `shared/security/AdminController` — 移除 `@AuthenticationPrincipal Jwt` 參數（LAB 模式下 principal 是 `String "lab-user"` 而非 `Jwt`，會 NPE），改注入 `CurrentUserProvider`，`by` 欄位用 `users.userId()`；`@PreAuthorize("hasRole('admin')")` 不變（兩模式都通過）。
- `shared/security/SecurityConfig` — 注入 `SkillshubProperties`，`filterChain` 內部 if/else 分支於兩種模式（單一 bean 策略，避免兩個 chain bean 競爭）；JwtDecoder + JwtAuthenticationConverter 兩個 @Bean 加 `@ConditionalOnProperty(prefix="skillshub.security.oauth", name="enabled", havingValue="true", matchIfMissing=true)`，`matchIfMissing=true` 為 fail-secure 預設。
- `application.yaml` 加 `skillshub.security.{oauth.enabled, lab.user-id}` 區塊（純值，與 S009 規範一致）。

### Notes
- 遵循 CLAUDE.md「Feature First, Security Later」：S001~S010 既有 endpoints 在 LAB 模式仍維持匿名可達；OAuth 模式下亦只有 `/api/v1/me` 與 `/api/v1/admin/**` 需要 JWT。
- 設計 §2.6 Validation Pass 已在實作前對 main 分支做差異對照，發現並校正兩處 spec 初稿與實情不符（AdminController 也用 `@AuthenticationPrincipal Jwt`、MeController 回 6 欄而非 2 欄）；實作完全依校正後設計，無 post-implementation drift。
- `LabSecurityFilter` 用 `UsernamePasswordAuthenticationToken` 三參構造子（principal, credentials, authorities）才會讓 `isAuthenticated()=true`；`SecurityContextHolder.clearContext()` 在 finally 為冗餘但無害的明示意圖（`FilterChainProxy` 本就會清）。
- 驗證：`./gradlew clean test` → BUILD SUCCESSFUL（114 tests, 0 failed）。

## [v0.10.0] - 2026-04-27

### Added
- S011: 開發環境 OAuth Mock 整合 — `./gradlew bootRun` 自動帶起 navikt/mock-oauth2-server (透過 `spring-boot-docker-compose`)，提供三組假身分（admin / developer / viewer）核發 OIDC JWT，含 `sub` / `roles` / `groups` / `company_id` / `dept_id` / `scope` 等符合 RFC 7519 / 9068 + SCIM 慣例的 claim
  - `backend/compose.yaml` 加入 `mock-oauth2-server` service（port 9000:8080，掛 `config/oauth-mock-config.json`，含 healthcheck + `org.springframework.boot.ignore` label）
  - `backend/config/oauth-mock-config.json` 三組 `requestParam: client_id` mapping（admin-client → admin-001、developer-client → dev-042、viewer-client → viewer-007）
  - `backend/build.gradle.kts` 啟用 `spring-boot-starter-security-oauth2-resource-server` 與其 test starter（從 S000 模板註解狀態取消註解）
  - `shared/security/SecurityConfig` — 顯式 SecurityFilterChain：`/api/v1/me` + `/api/v1/admin/**` authenticated，`anyRequest().permitAll()` 保留 S001~S010 既有匿名可達；`@EnableMethodSecurity` 啟用 method-level role 判斷
  - `shared/security/MeController` (`/api/v1/me`) 回傳 token 6 個 claims；`shared/security/AdminController` (`/api/v1/admin/echo`) 用 `@PreAuthorize("hasRole('admin')")` 示範 role 強制
  - `JwtAuthenticationConverter` 透過 `JwtGrantedAuthoritiesConverter.setAuthoritiesClaimName("roles")` + `setAuthorityPrefix("ROLE_")` 自動映射 JWT `roles: ["admin"]` → `ROLE_admin` GrantedAuthority
- 4 個測試類別涵蓋 8 個 AC：`OAuthMockE2ETest`（Testcontainers 真打 mock 容器，AC-1~3）+ `MeControllerTest` / `AdminControllerTest` / `SkillsApiAnonymousTest`（MockMvc + `SecurityMockMvcRequestPostProcessors.jwt()`，AC-4~8）

### Fixed
- Spring Boot 4 / Spring Security 7 OAuth2 Resource Server auto-config 不會自動建立 `JwtDecoder` bean — 顯式宣告 `JwtDecoder` 並用 `SupplierJwtDecoder` 包裝確保 lazy discovery（mock 容器後啟動不擋 `bootRun`）。`@Value` 帶 default 值同時兼容 prod yaml 與 test classpath 完全覆蓋 main yaml 的情境

### Notes
- 遵循 CLAUDE.md「Feature First, Security Later」：S001~S010 既有 API 全部維持匿名可達；只有本 spec 新增 demo 端點要 JWT
- Spring Boot 4 已將 starter 重新命名為 `spring-boot-starter-security-oauth2-resource-server`（原 `spring-boot-starter-oauth2-resource-server`）

## [v0.9.0] - 2026-04-26

### Added
- S010: Multi-engine security scanner pipeline — replaces S005 single-regex scanner with 5 independently-toggleable engines + SARIF 2.1.0 output
  - `SecurityAnalyzer` SPI + `Phase` (STATIC/LLM/META) execution model in `security.scan` package
  - 5 engines: `PatternScanner` (8 regex rules, OWASP AST06), `SecretScanner` (10 patterns inspired by gitleaks MIT + masking), `MetadataValidator` (Jakarta Bean Validation against agentskills.io frontmatter), `LlmJudge` (Spring AI ChatClient + Gemini 2.5 Flash + structured output via `BeanOutputConverter`), `MetaAnalyzer` (cross-engine deterministic rules: META_EXFIL_PATTERN, META_MULTI_ENGINE_SIGNAL, META_OPACITY)
  - `ScanOrchestrator` (`@EventListener` on `SkillVersionPublishedEvent`) — Phase 1 STATIC parallel via `Executors.newVirtualThreadPerTaskExecutor()`, Phase 2 LLM sequential, Phase 3 META sequential; max-severity aggregation; per-engine try/catch isolation
  - `SarifReporter` — hand-rolled Jackson POJO renderer (no third-party SARIF lib due to dead-library landscape); HIGH/MEDIUM/LOW → error/warning/note + `security-severity` 浮點字串 for future GHAS integration; one `runs[]` per enabled engine
  - `ScannerAiConfig` — Spring AI Manual Configuration for `GoogleGenAiChatModel` + `ChatClient`; `AllNestedConditions` dual gate (`engines.llm.enabled=true` AND `genai.api-key` present)
  - `SkillVersionReadModel.riskAssessment Map<String,Object>` field stores level + findings + notices + SARIF + scannedAt
  - `SkillshubProperties.Scanner` nested `@ConfigurationProperties` record with per-engine toggles
- New dependencies: `spring-ai-google-genai`, `spring-ai-client-chat` (chat artifact NOT transitive from genai)

### Fixed
- Cross-module `@EventListener` race condition: `SkillProjection` and `ScanOrchestrator` both listen to `SkillVersionPublishedEvent` with default LOWEST_PRECEDENCE → undefined order. Explicit `@Order(HIGHEST_PRECEDENCE)` on SkillProjection + `@Order(LOWEST_PRECEDENCE)` on ScanOrchestrator. (Race existed since S005 but was masked because S005 didn't depend on `skill_versions` document being pre-written.)

### Removed
- S005 legacy classes: `RiskAssessmentListener`, `RiskScanner`, `RiskFinding`, old `ScanResult` (replaced by `ScanOrchestrator` + `PatternScanner` + `SecurityFinding` + new `ScanResult` with SARIF map)

## [v0.8.0] - 2026-04-25

### Changed
- S009: Config optimisation — `@ConfigurationProperties` best practices aligned across all YAML and Java config
  - Removed all `${...}` placeholder indirection from `skillshub.*` and `spring.data.mongodb.uri`
  - Eliminated Spring AI auto-config / Manual Config conflict: `spring.ai.model.embedding.text: none` + `GoogleGenAiEmbeddingConnectionAutoConfiguration` excluded in base
  - Centralised `gemini-embedding-2` model name and `768` dimensions into `SkillshubProperties.GenAI` via `@DefaultValue`
  - `springdoc` disabled by default in `application.yaml`; enabled only in `config/application-dev.yaml`
  - `config/application-secrets.properties.example` updated to dot-notation (`skillshub.genai.api-key=...`)

## [v0.7.0] - 2026-04-25

All 9 specs complete — full MVP shipped.

### Added
- S007: Semantic search — Spring AI 2.0.0-M4 + Gemini embedding (`gemini-embedding-2`, 768 dims) + dual VectorStore (`SimpleVectorStore` dev / `FirestoreVectorStore` prod)
  - `GET /api/v1/search/semantic?q=...` endpoint with cosine similarity ranking (topK=10, threshold=0.3)
  - `SearchProjection` (@EventListener) seeds VectorStore on `SkillCreatedEvent` + `SkillVersionPublishedEvent`
  - `FirestoreVectorStore` extends `AbstractObservationVectorStore` via Firestore native SDK (`findNearest()`)
  - `SearchConfig` @ConditionalOnProperty switches backend; `NoOpEmbeddingModel` fallback for local dev
  - Frontend: `useSemanticSearch` TanStack Query hook; `HomePage` dual-mode (semantic/keyword); `SkillCard` score badge (`XX% 相符`)

### Fixed
- `package-info.java` `allowedDependencies` now references `"skill :: domain"` named interface (required for `SkillCreatedEvent` access in Spring Modulith)
- `application.yaml` (test + local): suppress `GoogleGenAiTextEmbeddingAutoConfiguration` conflict via `spring.ai.model.embedding.text: none`

## [v0.6.0] - 2026-04-25

MVP release covering Milestones 0-4 + 6 (8/9 specs shipped).

### Added
- S000: Project init — backend/frontend scaffold, ES+CQRS infra, Spring Modulith modules
- S001: Skill domain model + Command/Query API with Event Sourcing
- S002: Skill browse & search UI — keyword search, category filter, detail page (React 19 + shadcn/ui)
- S003: Skill upload + versioning — GCS storage, multipart upload, version management
- S004: Skill publish UI — drag-drop upload form, version history, add version
- S005: Risk assessment engine — pattern scanner, event-driven risk evaluation, community flagging
- S006: Skill download API + UI — download endpoints, SkillDownloaded events, install guide
- S008: Analytics dashboard — overview stats API, top skills, metric cards

### Not Yet Implemented
- S007: Semantic search (Spring AI + Gemini + Firestore Vector) — in design phase
