# Skills Hub — Spec Roadmap

> 最後更新：2026-04-30（**S024 ✅ Shipped v2.0.0 (M19)** — Skill State-Based Aggregate Migration 完成；ADR-002 Phase 2 落地完成。Active 推進至 **S025a + S025b**（Test Pyramid Realignment，由 S023 T07 + S024 T05B test cascade 觸發；於 `/planning-spec S025` Phase 3 grill #1 用戶選 C 場景後拆分為 a/b 兩 spec — XL 強制拆，per estimation-scale.md L18+）。）

---

## 🎯 Active Work — Sequenced (Next Up)

**Phase 4 — Test Pyramid Realignment（C 場景：徹底重整）**：詳 §Active Work `S025a / S025b` 拆分緣由。原單一 S025 在 `/planning-spec` Phase 3 grill #1 評估後落為 XL（17-18 pts），須拆為 S025a（mock lift + Scenario migration）+ S025b（slice 重組 + workaround 移除）兩 spec sequential ship。

### Recommended Execution Order

```
S025a (Mock Lift + Scenario Migration) ─▶ S025b (Slice 重組 + Workaround 移除)
     M(13)、ship as v2.1.0                   M(12-13)、ship as v2.2.0
     ├─ Lift EmbeddingModel mock (8處統一)    ├─ 解 AOT ApplicationModulesRuntime blocker
     │   → TestcontainersConfig @Bean @Primary   (StringListJsonbConverterTest known)
     ├─ CurrentUserProvider 策略落地           ├─ 13 個 Repository → @DataJdbcTest
     │   (per-test stub vs @WithMockUser)        + @AutoConfigureTestDatabase(replace=NONE)
     ├─ 14 個 Listener test                  ├─ 13 個 Controller → @WebMvcTest
     │   → @ApplicationModuleTest + Scenario    + @WithMockUser
     │   (mode=DIRECT_DEPENDENCIES)          ├─ @SpringBootTest 收斂至 ≤3 e2e
     ├─ 38 個 Awaitility 30s → Scenario 5s    ├─ 移除 maxHeapSize=3g + cache.maxSize=8
     ├─ 5 個 @Disabled 全部恢復                  + 升回 cache.maxSize 預設 32
     │   (3× RiskAssessment + 1× SearchProj  └─ verify-all.sh × 5 連續 PASS
     │    + 1× S016EndToEndSmoke)
     └─ AuditEventListenerTest 為 POC pilot
```

| 順序 | Spec | Title | Points | Deps | Status |
|------|------|-------|--------|------|--------|
| 1 | S023 | Spring Modulith Outbox Foundation | M(12) | S018 ✅ + ADR-002 | ✅ — `v1.5.0` (M18) |
| 2 | S024 | Skill State-Based Aggregate Migration | M(13) | S023 ✅ + S016 ✅ + S018 ✅ + ADR-002 | ✅ — `v2.0.0` (M19) — 2026-04-30 |
| 3 | S025a | Mock Lift + Scenario Migration | M(13) | S023 ✅ | ✅ — `v2.1.0` (M20) — 2026-04-30 |
| 4 | S025b | Slice 重組 + Workaround 移除（@DataJdbcTest / @WebMvcTest + AOT blocker + heap/cache workaround 清除） | M(12-13) | S025a ✅ | ✅ — `v2.2.0` (M21) — 2026-04-30；archive 待 `/shipping-release`（cache.maxSize=8 移除 ✓；maxHeapSize 從 3g 降至 2g — full 移除留 S025c 進一步 consolidate 18 個 CONFIG bucket @SpringBootTest） |

> **S025a / S025b 拆分緣由**（2026-04-30 於 `/planning-spec S025` Phase 3 grill #1）：原 S025 用戶選 C 場景（cache key ≤5 / Awaitility 100% 移除 / `@SpringBootTest` ≤3 / 5 個 disabled 全恢復 / workaround 全清）→ 估算 17-18 = XL，per estimation-scale.md 強制拆。拆分依風險與 reversibility：
> - **S025a**（M(13)，two-way door）：mock lift + Scenario migration 為純 test infrastructure 改動，可單 PR ship；blast radius 小（test 改動只影響 test pipeline）
> - **S025b**（M(12-13)，two-way door但需先解 AOT blocker）：slice 重組需先解決 `StringListJsonbConverterTest` / `MapJsonbConverterTest` Javadoc 記載的 AOT `ApplicationModulesRuntime` bean missing 問題；若 S025a ship 後 cache key 已降至預期 → S025b 範圍可重新評估（可能 absorb workaround 移除為 S025a follow-up，留 slice 重組為獨立 spec）
>
> **S023 / S024 拆分緣由**：原 Backlog S023 範圍僅「outbox migration」，研究後（`docs/deepwiki/spring-data-jdbc-modulith/` 6 份 source-level 檔案）發現整體架構轉向更有價值，但合併為單一 spec 估算 **L(16)** 接近 XL 強制拆分線；ADR-002 §5 拆為 S023（基礎建設）+ S024（領域層改寫）— 各 M(12-13)，可獨立 ship 與 verify、blast radius 小。
>
> **S025 觸發**：S023 T07 揭露測試金字塔倒置（53/77 tests 用 `@SpringBootTest`，cache key 爆炸 → LRU evict + container churn）+ Awaitility 30s timeout band-aid + 2 個 e2e MockMvc test `@Disabled`；S025 系統重整 4 範圍 — cache key 收斂 / Scenario migration / slice 重組 / workaround 移除（詳 §Backlog `Project Infrastructure` 段）。
>
> **過往 (歷史) Phase 2.5 + Phase 2 執行記錄移至 §Shipped Milestones 表格**

---

## 🎯 Phase 2 / 2.5 完成記錄（歷史）

```
S019 ─▶ S020 ─▶ S021 ─▶ S022   Phase 2.5（Project Infra · M17 · 31 pts · ✅ v1.1.1）
                          │
                          ▼
                     S016 ✅ ─▶ S017 ✅   Phase 2（Domain · M14 ✅ v1.2.0 / M15 ✅ v1.3.0）
                              │
                              └─▶ S018 ✅ (M16 ✅ v1.4.0 — Skill aggregate 充血演化純 ES path)
```

| 順序 | Spec | Title | Points | Deps | Status |
|------|------|-------|--------|------|--------|
| 1 | S019 | JaCoCo coverage gate + 80% line threshold | XS(5) | — | ✅ |
| 2 | S020 | Verification command registry + `scripts/verify-all.sh` | S(10) | S019 ✅ | ✅ |
| 3 | S021 | Phase 2 doc-sync — PRD.md + architecture.md | S(8) | — (可與 1/2 平行) | ✅ |
| 4 | S022 | Frontend Verification Baseline | S(8) | S020 ✅ | ✅ |
| 5 | S016 | Row-Level ACL 基礎建設（JSONB acl_entries + GIN）| M(13) | S014 ✅ | ✅ — `v1.2.0` |
| 6 | S017 | ACL-Aware 語意搜尋（PgVectorStore + ACL SQL composition）| S-M(11) | S016 ✅ | ✅ — `v1.3.0` |
| 7 | S018 | Skill Aggregate 充血演化 + SKILL.md 對齊 + Suspend/Reactivate Events | M(13) | S014/S016/S017 ✅ | ✅ — `v1.4.0` |

### Recommended Execution Order（歷史記錄）

```
S019 ─▶ S020 ─▶ S021 ─▶ S022   Phase 2.5（Project Infra · M17 · 31 pts · ✅ v1.1.1）
                          │
                          ▼
                     S016 ✅ ─▶ S017 ✅   Phase 2（Domain · M14 ✅ v1.2.0 / M15 ✅ v1.3.0）
                              │
                              └─▶ S018 ✅ (M16 ✅ v1.2.0 → graceful degrade 占位已移除)
```

| 順序 | Spec | Title | Points | Deps | Status |
|------|------|-------|--------|------|--------|
| 1 | S019 | JaCoCo coverage gate + 80% line threshold | XS(5) | — | ✅ |
| 2 | S020 | Verification command registry + `scripts/verify-all.sh` | S(10) | S019 ✅ | ✅ |
| 3 | S021 | Phase 2 doc-sync — PRD.md + architecture.md | S(8) | — (可與 1/2 平行) | ✅ |
| 4 | **S022** | **Frontend Verification Baseline**（vitest coverage tooling + 樣板 component test + ESLint root-cause + V06 enrollment）| **S(8)** | **S020**（registry + verify-all.sh 為 V06 enrollment 前提）| **✅ — `specs/archive/2026-04-28-S022-frontend-verification-baseline.md`** |
| 5 | S016 | Row-Level ACL 基礎建設（JSONB acl_entries + GIN）| M(13) | S014 ✅ | ✅ — `specs/archive/2026-04-28-S016-row-level-acl-foundation.md`（v1.2.0 / 2026-04-29）|
| 6 | S017 | ACL-Aware 語意搜尋（PgVectorStore + ACL SQL composition）| S-M(11) | S016 ✅ | ✅ — `specs/archive/2026-04-29-S017-acl-aware-semantic-search.md`（v1.3.0 / 2026-04-29）|
| 7 | S018 | Skill Aggregate 充血演化 + SKILL.md 對齊 + Suspend/Reactivate Events | M(13) | S014 ✅ + S016 ✅ + S017 ✅ | ✅ — `specs/archive/2026-04-27-S018-skill-aggregate-rich-domain.md`（v1.4.0 / 2026-04-29）|

> **S022 緣由（2026-04-28）**：S020 T2 主驗收命令首次跑出 V04（vitest「No test files found」exit 1）+ V05（`badge.tsx:48` / `tabs.tsx:89` 兩處 `react-refresh/only-export-components` 錯誤）pre-existing 故障；user 選 Option A 在 S020 內最小修正（`smoke.test.ts` + 2 處 `eslint-disable-next-line`）讓 V04/V05 過 happy-path gate。但這是 tactical band-aid，未建立 frontend testing baseline；故獨立 S022 補：(a) `@vitest/coverage-v8` 安裝 + threshold POC（鏡像 S019 模式）；(b) `setupTests.ts` + `@testing-library/react/jest-dom` 引用樣板（既有 deps 未用）；(c) 1-2 個真實 component / hook test（如 `SkillCard`、`useSemanticSearch`）；(d) ESLint root-cause 決策（拆檔 vs cva exception config）— 收尾 S020 Option A 的 `eslint-disable` 占位；(e) qa-strategy.md L23-25「80% line coverage on new code」實作落地 + V06 加入 registry。

> **S018 注記（2026-04-28 revised）**：spec 已 revise — 解 paused、加 SKILL.md `allowed-tools` first-class + `SkillValidator` 嚴格化（per agentskills.io spec）+ Spring Data `AbstractAggregateRoot` 評估收尾（決定不採用，研究結論寫入 §2.4 Challenge #10）。Suspend/Reactivate 授權層採 graceful degrade（S016 ship 前用 `hasRole('admin')` 占位）；其餘 ACs 不依賴 S016 可獨立 ship。size S(11) → M(13)。Modulith outbox migration 拆出 → 📚 Backlog **S023**。

> **S021 平行可能**：純 docs，無 code 衝突；可與 S019/S020 同期推進，或保留至最後讓 S019/S020 結果一併寫入。

---

## 📋 Status Summary

| Phase | Milestone | Spec(s) | Points | 累計 | Status |
|-------|-----------|---------|--------|------|--------|
| MVP | M0: Project Init | S000 | S(10) | 10 | ✅ |
| MVP | M1: 技能瀏覽與搜尋 | S001, S002 | M(12) + S(11) = 23 | 33 | ✅ |
| MVP | M2: 技能發佈流程 | S003, S004 | M(12) + S(10) = 22 | 55 | ✅ |
| MVP | M3: 自動風險評估 | S005 | M(12) | 67 | ✅ |
| MVP | M4: 一鍵安裝（Web 下載） | S006 | S(9) | 76 | ✅ |
| MVP | M5: 語意搜尋 | S007 | M(14) | 90 | ✅ |
| MVP | M6: 使用數據分析 | S008 | S(11) | 101 | ✅ |
| MVP | M7: 設定檔最佳化 | S009 | XS(7) | 108 | ✅ |
| MVP | M8: 安全掃描升級 | S010 | M(12) | 120 | ✅ |
| MVP | M9: 開發環境 OAuth Mock | S011 | XS(8) | 128 | ✅ |
| MVP | M10: OAuth 開關 + LAB 模式 | S012 | XS(8) | 136 | ✅ |
| MVP | M11: GCP Cloud Run 部署 | S013 | S(11) | 147 | ✅ `v1.0.0` |
| Phase 1 | M12: PostgreSQL 資料層遷移（含 S015 absorbed） | S014 | L(20) | 167 | ✅ `v1.1.0` |
| ~~M13~~ | ~~自訂 PgVectorStore~~ | ~~S015~~ | — | — | 🚫 ABSORBED → S014 |
| Phase 2.5 | M17: Project Infra | S019 ✅, S020 ✅, S021 ✅, S022 ✅ | XS(5)+S(10)+S(8)+S(8) = 31 | 198 | ✅ `v1.1.1` (2026-04-28) |
| Phase 2 | M14: Row-Level ACL 基礎建設 | S016 | M(13) | 211 | ✅ `v1.2.0` (2026-04-29) |
| Phase 2 | M15: ACL-Aware 語意搜尋 | S017 | S-M(11) | 222 | ✅ `v1.3.0` (2026-04-29) |
| Phase 2 | M16: Skill Aggregate 充血演化 + SKILL.md 對齊 | S018 | M(13) | 235 | ✅ `v1.4.0` (2026-04-29) |
| Phase 3 | M18: Spring Modulith Outbox Foundation | S023 | M(12) | 247 | ✅ `v1.5.0` (2026-04-29) |
| Phase 3 | M19: Skill State-Based Aggregate Migration | S024 | M(13) | 260 | ✅ `v2.0.0` (2026-04-30) |
| Phase 4 | M20: Mock Lift + Scenario Migration | S025a | M(13) | 273 | ✅ `v2.1.0` (2026-04-30) |
| Phase 4 | M21: Slice 重組 + Workaround 移除 | S025b | M(12-13) | 285 | ✅ `v2.2.0` (2026-04-30 — partial: cache key ~18 達標 baseline 大幅下降；heap 3g → 2g；full default heap 還原留 S025c) |
| Phase 4 | M22: Public-Read Default ACL | S026 | XS(5) | 290 | ✅ `v2.3.0` (2026-05-01 — `*:read` public pseudo-principal 加入預設 ACL；read 普及；mutation 仍 owner-only) |
| Phase 4 | M23: Dev Mode Admin Bypass | S027 | XS(5) | 295 | ✅ `v2.4.0` (2026-05-01 — local profile 預設 LAB mode；DelegatingPermissionEvaluator 對 ROLE_admin 短路；dev 全 mutation 不需 JWT；prod 行為不變) |
| Phase 4 | M24: Frontend SUSPENDED Status Rendering | S028 | XS(5) | 300 | ✅ `v2.5.0` (2026-05-01 — type union 補 SUSPENDED；SkillDetailPage 中譯 + variant；SkillCard 對非 PUBLISHED 顯示 badge) |
| Phase 4 | M25: Block Suspended Skill Download | S029 | XS(5) | 305 | ✅ `v2.6.0` (2026-05-01 — SkillSuspendedException + SkillQueryService.downloadAndRecord guard；403 SKILL_SUSPENDED；admin 也不能下載 SUSPENDED skill) |
| Phase 4 | M26: Conflict-Class Error Mapping | S030 | XS(5) | 310 | ✅ `v2.7.0` (2026-05-01 — IllegalStateException → 409 STATE_CONFLICT；OptimisticLockingFailureException → 409 CONCURRENT_MODIFICATION + retry hint) |
| Phase 4 | M27: Public PUBLISHED-Only Visibility | S031 | XS(5) | 315 | ✅ `v2.8.0` (2026-05-01 — list / categories / analytics 5 處 SQL 加 WHERE status='PUBLISHED'；落地 S028 §7.5 tech debt；findById 不過濾保留 admin/owner 看詳情能力) |
| Phase 4 | M28: Version Name Consistency | S032 | XS(5) | 320 | ✅ `v2.9.0` (2026-05-01 — addVersion 驗 zip SKILL.md name 與 aggregate name 一致；mismatch → 400 VALIDATION_ERROR；阻止內容變身攻擊) |
| Phase 4 | M29: Vector Store Status Sync | S033 | XS(5) | 325 | ✅ `v2.10.0` (2026-05-01 — SearchProjection 加 onSkillSuspended 刪 vector row + onSkillReactivated 重 embed；落地 S031 §7.5；附帶解決 S025b §7 author fallback 的 reactivate path) |
| Phase 4 | M30: SearchProjection Owner from Event/Aggregate | S034 | XS(5) | 330 | ✅ `v2.11.0` (2026-05-01 — onSkillCreated 用 event.author / onVersionPublished 用 aggregate.author；移除 CurrentUserProvider 依賴；S025b §7 author fallback architecture tech debt 完整解決) |
| Phase 4 | M31: Frontend Suspended Detail Page UX | S035 | XS(5) | 335 | ✅ `v2.12.0` (2026-05-01 — SUSPENDED skill detail 隱藏下載按鈕 + 顯示 destructive banner + 隱藏 AddVersionForm；對齊 S028/S029/S030 backend 行為) |
| Phase 4 | M32: Frontend MEDIUM Risk Message | S036 | XS(5) | 340 | ✅ `v2.13.0` (2026-05-01 — Risk tab 補 MEDIUM 段落說明；改用 Record<RiskLevel,string> exhaustive map；mirror S028 STATUS_LABEL pattern) |
| Phase 4 | M33: Upload Size 413 + Frontend Size Pre-check | S037 | XS(5) | 345 | ✅ `v2.14.0` (2026-05-01 — MaxUploadSizeExceededException → 413 PAYLOAD_TOO_LARGE；MultipartException → 400 MULTIPART_ERROR；FileDropZone 加 client-side 10MB pre-check) |
| Phase 4 | M34: ACL List Recognizes `*:read` | S038 | XS(5) | 350 | ✅ `v2.15.0` (2026-05-01 — listEntries 識別 `*:read` 為 synthetic public entry；消除 WARN log spam；frontend 可呈現公開讀取狀態) |
| Phase 4 | M35: Frontend Typed ApiError + 404 vs Server Error | S039 | XS(5) | 355 | ✅ `v2.16.0` (2026-05-01 — apiFetch 拋 ApiError 含 status+code；SkillDetailPage 區分 404 not-found vs server/network error；改善誤導 UX) |
| Phase 4 | M36: Frontend Mutation Error i18n + Multipart 用 ApiError | S040 | XS(5) | 360 | ✅ `v2.17.0` (2026-05-01 — uploadSkill/addVersion 也拋 ApiError；新建 lib/api-error-messages.ts 集中 8 code 翻譯；PublishPage/AddVersionForm 顯示繁中) |
| Phase 4 | M37: Skill Aggregate Input Validation | S041 | XS(5) | 365 | ✅ `v2.18.0` (2026-05-01 — Skill.create 加 NAME_REGEX 與 author blank 驗證；補 JSON POST 缺驗證破口；防畸形 ACL "user::read") |
| Phase 4 | M38: Aggregate description / category Validation | S042 | XS(5) | 370 | ✅ `v2.19.0` (2026-05-01 — Skill.create 補 description trim + blank + ≤1024，category trim + blank reject；S041+S042 完成 4 欄位 invariant 守門) |
| Phase 4 | M39: Keyword Search Also Matches Category | S043 | XS(5) | 375 | ✅ `v2.20.0` (2026-05-01 — SkillQueryService keyword LIKE clause 加 `LOWER(category)` 第三個 OR；對齊 GitHub/npm 通用 catalog 搜尋；HomePage 搜尋「DevOps」從 0 → 25 skills) |
| Phase 4 | M40: Keyword Trim Whitespace | S044 | XS(5) | 380 | ✅ `v2.21.0` (2026-05-01 — keyword 做 `.trim()` 預處理；複製貼上含 leading/trailing space 不再回 0 結果；trim 與 sanitizeLikePattern SQL escape 職責正交) |
| Phase 4 | M41: Strip Error Stack Trace + 405 Handler | S045 | XS(5) | 385 | ✅ `v2.22.0` (2026-05-01 — `spring.web.error.include-stacktrace: never` 全局；405 加 explicit handler；405/415/404 response 從 12-14KB 收斂至 138-180B 不含 stack trace；解 tick 19 §7.5 資訊洩漏) |
| Phase 4 | M42: Semantic Search Fallback to Keyword | S046 | XS(5) | 390 | ✅ `v2.23.0` (2026-05-01 — HomePage isSemanticMode 加 `length > 0` 條件；semantic 回空時自動 fallback keyword mode；解 Chrome E2E 死巷 dev/prod 一致 graceful) |
| Phase 4 | M43: Installation Guide Only for PUBLISHED | S047 | XS(5) | 395 | ✅ `v2.24.0` (2026-05-01 — SkillDetailPage 安裝指引 conditional render；DRAFT/SUSPENDED 隱藏；對齊 download button 隱藏邏輯) |
| Phase 4 | M44: FileDropZone Reject Non-`.zip` | S048 | XS(5) | 400 | ✅ `v2.25.0` (2026-05-01 — FileDropZone handleFile 加擴展名 guard；drag-drop `.txt` 不再 bypass；對齊 S037 size guard 模式) |
| Phase 4 | M45: ZipException → 400 VALIDATION_ERROR | S049 | XS(5) | 405 | ✅ `v2.26.0` (2026-05-01 — GlobalExceptionHandler 加 ZipException handler；corrupt zip 不再噴 raw Java 訊息；frontend 走既有 i18n map) |
| Phase 4 | M46: SearchBar Placeholder Include Category | S050 | XS(5) | 410 | ✅ `v2.27.0` (2026-05-01 — placeholder 對齊 S043；S043/S044/S046 累積 UI copy 待辦清掉) |
| Phase 4 | M47: DuplicateKeyException → 409 DUPLICATE_RESOURCE | S051 | XS(5) | 415 | ✅ `v2.28.0` (2026-05-01 — 重複 name 不再 500 + SQL leak；改 409 + 固定 friendly message + i18n 翻譯) |
| Phase 4 | M48: HttpMessageNotReadableException → 400 INVALID_REQUEST_BODY | S052 | XS(5) | 420 | ✅ `v2.29.0` (2026-05-01 — missing body / malformed JSON 不再洩 controller method 簽名；統一 INVALID_REQUEST_BODY + 繁中 i18n) |
| Phase 4 | M49: Flexible Upload Formats + Canonical Zip Structure | S053 | S(7) | 427 | ✅ `v2.30.0` (2026-05-01 — 三種上傳場景皆 normalize 至 SKILL.md root；plain .md 直接接受；subfolder zip 自動脫 wrapping；下載結構一致) |
| Phase 4 | M50: Aggregate Null-Param 400 + Placeholder Polish | S054 | XS(5) | 432 | ✅ `v2.31.0` (2026-05-01 — Skill.create/SkillVersion.publish NPE 改 IAE 走 400 VALIDATION_ERROR；FileDropZone placeholder 對齊 S053 雙格式) |
| Phase 4 | M51: ACL Tuple Input Validation | S055 | XS(5) | 437 | ✅ `v2.32.0` (2026-05-01 — Skill aggregate 加 ACL_TYPES/ACL_PERMISSIONS 常數 + validateAclTuple；grantAcl/revokeAcl 共用；缺/錯欄位 → 400 VALIDATION_ERROR) |
| Phase 4 | M52: Version Semver Validation | S056 | XS(5) | 442 | ✅ `v2.33.0` (2026-05-01 — Skill aggregate 加 VERSION_REGEX；recordVersionPublished 預驗 semver；foo/空/超長 全 400；解 Q/R/T 三 bug) |
| Phase 4 | M53: DataIntegrityViolationException Catch-All | S057 | XS(5) | 447 | ✅ `v2.34.0` (2026-05-01 — DataIntegrityViolation 父類 catch-all → 400 CONSTRAINT_VIOLATION；S051 dup key 仍 409 不破；累計 5 層 default-error 防漏網) |
| Phase 4 | M54: Flag Input Validation | S058 | XS(5) | 452 | ✅ `v2.35.0` (2026-05-01 — POST flags 缺 type 不再 500 NPE；type 預驗 + payload HashMap 允許 null description；解 Map.of NPE 陷阱) |
| Phase 4 | M55: Semantic Search PUBLISHED-Only Visibility | S059 | XS(5) | 457 | ✅ `v2.36.0` (2026-05-01 — semantic SQL 加 JOIN skills + status='PUBLISHED' filter；對齊 S031；DRAFT/SUSPENDED 不再公開呈現於 semantic 結果) |
| Phase 4 | M56: SkillCard Status Badge Defensive | S060 | XS(5) | 462 | ✅ `v2.37.0` (2026-05-01 — SkillCard truthy guard skill.status；undefined → 不主張；解 semantic 結果誤顯「草稿」badge) |
| Phase 4 | M57: Download Filename Includes Skill Name | S061 | XS(5) | 467 | ✅ `v2.38.0` (2026-05-01 — Content-Disposition filename 動態組 {skillName}-{version}.zip；多下載不再檔名衝撞) |
| Phase 4 | M58: SkillVersion JSON Hide Internals | S062 | XS(5) | 472 | ✅ `v2.39.0` (2026-05-01 — getStoragePath + isNew 加 @JsonIgnore；frontend type 移除 storagePath；資訊洩漏 + 髒 API surface 解) |
| Phase 4 | M59: Skill Aggregate isNew JsonIgnore | S063 | XS(5) | 477 | ✅ `v2.40.0` (2026-05-01 — Skill.isNew() 加 @JsonIgnore；延伸 S062 至 Skill aggregate；JSON 不再含 new artifact) |
| Phase 4 | M60: QueryCache Logger Skip 4xx ApiError | S064 | XS(5) | 482 | ✅ `v2.42.0` (2026-05-01 — main.tsx QueryCache 跳過 4xx ApiError；console pollution 降；UI 已負責處理) |
| Phase 4 | M61: ApiError HMR-Safe + Query networkMode | S065 | XS(5) | 487 | ✅ `v2.42.0` (2026-05-01 — ApiError.is() 替代 instanceof；3 處 caller 統一；QueryClient networkMode='always' 預設；hotfix v2.43.0 retry on 4xx → false 解 Bug AC) |
| Phase 4 | M62: METHOD_NOT_ALLOWED i18n Coverage | S066 | XS(5) | 492 | ✅ `v2.44.0` (2026-05-01 — frontend i18n 補 METHOD_NOT_ALLOWED；12/12 backend codes 全覆蓋) |
| Phase 4 | M63: Version Input HTML5 Pattern Pre-Validation | S067 | XS(5) | 497 | ✅ `v2.45.0` (2026-05-01 — version input 加 HTML5 pattern；client-side 預驗對齊 backend S056；inline 註解記 Chrome pattern 兩陷阱) |
| Phase 4 | M64: PublishPage Form maxLength Constraint | S068 | XS(5) | 502 | ✅ `v2.46.0` (2026-05-01 — category/author input 加 maxLength 對齊 DB varchar 上限；client-side 防超限) |
| Phase 4 | M65: AuditEventListener Null-Defense for ACL | S069 | XS(5) | 507 | ✅ `v2.47.0` (2026-05-01 — on(SkillAcl[Granted|Revoked]Event) null-coalesce；drain 2 個 pre-S055 stuck outbox events) |
| Phase 4 | M66: Flyway V7 Cleanup Pre-S033 Vector Orphans | S070 | XS(5) | 512 | ✅ `v2.48.0` (2026-05-01 — DELETE SUSPENDED vector orphans；S059 filter 已防 user 看到，本 migration 清 storage 累積) |
| Phase 4 | M67: App Routing /skills Alias + NotFound Fallback | S071 | XS(3) | 515 | ✅ `v2.49.0` (2026-05-01 — `/skills` alias 接 HomePage + `*` wildcard 接 NotFoundPage；fix unmatched URL 整頁空白 bug AF) |
| Phase 4 | M68: Flag Type Allowlist + Description Length Cap | S072 | XS(3) | 518 | ✅ `v2.50.0` (2026-05-01 — FlagService 加 `ALLOWED_TYPES` 白名單 6 種 + `description ≤ 500`；fix bogus type 與 5000-char description 接受 bug AG) |
| Phase 4 | M69: `allowed-tools` YAML list interop | S073 | XS(3) | 521 | ✅ `v2.51.0` (2026-05-01 — `SkillValidator` 用 type pattern matching 分流 list/scalar；fix canonical Anthropic SKILL.md 形狀無法上傳 bug AH) |
| Phase 4 | M70: Skill Files Browser API | S074 | S(5) | 526 | ✅ `v2.52.0` (2026-05-01 — `GET /skills/{id}/files` list + `/files/{*path}` read；zip-slip 防禦 + 1MB preview cap；feature for 「skill 明細頁面瀏覽各檔案內容」；FE rendering 留 S077) |
| Phase 4 | M71: `FlagReadModel.isNew()` `@JsonIgnore` | S075 | XS(3) | 529 | ✅ `v2.53.0` (2026-05-01 — `FlagReadModel.isNew()` 加 `@JsonIgnore`；fix `GET /flags` 回傳 `"new": true` 干擾 client bug AI；S063 Skill 修法的 Flag 平行覆蓋) |
| Phase 4 | M72: Download Counter Atomic Increment | S076 | S(5) | 534 | ✅ `v2.54.0` (2026-05-01 — `incrementDownloadCount` 原子 SQL UPDATE + ApplicationEventPublisher；fix 並行下載 50% / 90% 失敗 bug AJ；N=10 從 1/10 → 10/10 success rate) |
| Phase 4 | M73: `Skill.downloadCount` `@ReadOnlyProperty` | S077 | XS(3) | 537 | ✅ `v2.55.0` (2026-05-01 — `Skill.downloadCount` 加 `@ReadOnlyProperty`；fix S076 引入的 lost-update bug AK — concurrent suspend save 覆蓋 atomic increment；race counter 從 3/10 → 10/10) |
| Phase 4 | M74: `Skill.riskLevel` `@ReadOnlyProperty` (preemptive) | S078 | XS(2) | 539 | ✅ `v2.56.0` (2026-05-01 — `Skill.riskLevel` 加 `@ReadOnlyProperty`；preemptive defense per S077 同 pattern；audit 後 Skill aggregate 所有欄位 lost-update 清零；bug AL theoretical) |
| Phase 4 | M75: `SkillSuspendedException` message operation-agnostic | S079 | XS(1) | 540 | ✅ `v2.56.1` (2026-05-01 — message 從「cannot be downloaded」改「is not accessible」；S074 `/files` endpoint 引入後的 polish；FE i18n 不受影響) |
| Phase 4 | M76: Missing param error shape 統一 | S080 | XS(2) | 542 | ✅ `v2.57.0` (2026-05-01 — `MissingServletRequestParameterException` / `MissingServletRequestPartException` 加 handler；fix 缺 multipart param 時 Spring 預設 error shape 繞過標準 ErrorResponse 的 bug AM) |
| Phase 4 | M77: Design Token Migration | S081 | S(5) | 547 | ✅ `v2.58.0` (2026-05-01 — `frontend/src/index.css` 套 DESIGN.md tokens；55 colors + 6 radius + 3 font stack；UI foundation；後續 per-page rework S082-S085 排隊) |
| Phase 4 | M78: SkillDetailPage Files Tab UI | S082 | S(5) | 552 | ✅ `v2.59.0` (2026-05-01 — 4th tab「檔案」接 S074 backend API；FilesPanel 左 list + 右 viewer；text/image/binary/oversize 邊界全 handle；user-driven feature 完成 backend → frontend 全鏈路) |
| Phase 4 | M79: `BorderBeam` light theme tuning | S083 | XS(1) | 553 | ✅ `v2.59.1` (2026-05-01 — `theme="light" duration={4.5} strength={0.7}`；fix 在 #FFFFFF 背景上 dark-tuned beam 偏霧；對齊 DESIGN.md §Elevation 4-5s rotation + user playground 偏好) |
| Phase 4 | M80: UI Rework META（splits S085-S089） | S084 | M(12) | — | ✅ META shipped (2026-05-01 — META spec doc only；產出 5 sub-spec roadmap + 共用 components 候選 + BorderBeam 研究結論；sub-specs S085-S089 各自 ship) |
| Phase 4 | M81: HomePage rework + reusable components 抽取 | S085 | S(8) | — | 📋 backlog — 對 `skills_hub_homepage_mockup.html`；同 batch 帶 S089 BeamFrame；定義 IconTile / Pill / Callout |
| Phase 4 | M82: PublishPage rework | S086 | XS(5) | — | 📋 backlog — 對 `skill_publish_upload_flow.html` + `skill_publish_failure_and_high_risk_states.html` |
| Phase 4 | M83: SkillDetailPage rework | S087 | S(7) | — | 📋 backlog — 對 `skill_detail_page_docker_compose_helper.html`；含 Files tab (S082) 重新整合 |
| Phase 4 | M84: AnalyticsPage rework | S088 | XS(5) | — | 📋 backlog — 對 `platform_analytics_dashboard_admin_view.html`；metric cards + sparkline + activity feed |
| Phase 4 | M85: BorderBeam hand-roll BeamFrame（drop border-beam dep） | S089 | XS(3) | 561 | ✅ `v2.62.0` (2026-05-02 — `BeamFrame.tsx` 1:1 port prototype `.sh-search-wrap` conic-gradient；drop border-beam npm dep；JS bundle 396KB→347KB) |
| Phase 4 | M80: Semantic search `?limit=` configurable | S090 | XS(2) | 555 | ✅ `v2.60.0` (2026-05-02 — `?limit=` 1-50 cap；close R25.7 missing-feature；FE 「show more」UX enabled) |
| Phase 4 | M81: LlmJudge prompt calibration | S091 | XS(3) | 558 | ✅ `v2.61.0` (2026-05-02 — `SYSTEM_PROMPT` 重寫區分 demonstrated vs theoretical；fix Anthropic canonical skills 全 HIGH bug AN；handover/planning/deep-research HIGH→LOW，真風險維持 HIGH) |

**MVP（v1.0.0）**：14 specs / 147 story points 已完成 🎉
**Phase 1（PostgreSQL 遷移 v1.1.0）**：1 spec / 20 story points 已完成（S015 absorbed）
**Phase 2.5（Project Infra）**：4 specs / 31 story points 已完成 `v1.1.1`（2026-04-28）— S019 JaCoCo gate / S020 verification registry + verify-all.sh / S021 PostgreSQL doc-sync / S022 Frontend verification baseline
**Phase 2（Row-Level ACL + Aggregate 充血）**：3/3 specs 全部完成 — S016 ✅ `v1.2.0` + S017 ✅ `v1.3.0` + S018 ✅ `v1.4.0`（2026-04-29 同日連續 ship / 共 37 story points）

---

## 🔗 Dependency Graph

```
── MVP（已完成）─────────────────────────────────────
S000 ──▶ S001 ──▶ S002             ✅
              │
              ├──▶ S003 ──▶ S004   ✅
              │       │
              │       ├──▶ S005    ✅
              │       │
              │       └──▶ S006 ──▶ S008  ✅
              │
              └──▶ S007                ✅

S009 (獨立) ✅
S005 ──▶ S010 (多引擎安全掃描) ✅
S007 ──┘

S009 ──▶ S011 (dev OAuth mock) ✅
              │
              └──▶ S012 (OAuth toggle + LAB) ✅

S013 (GCP Cloud Run 部署，獨立) ✅

── Phase 1（已完成；ADR-001：Firestore → PostgreSQL）─
ADR-001 ──▶ S014 (PostgreSQL 資料層遷移 + 自訂 SkillshubPgVectorStore + Firestore 全清；S015 absorbed) ✅

── Phase 2.5（Project Infra · ✅ 已完成 v1.1.1）──────
S019 ✅ (JaCoCo gate) ──▶ S020 ✅ (verify registry + verify-all.sh) ──▶ S022 ✅ (frontend baseline + V06)
S021 ✅ (PRD/architecture.md doc-sync · 獨立、可平行)

── Phase 2（Domain · Row-Level ACL + Aggregate · M14/M15/M16 全 ✅；2026-04-29 同日連續 ship）─
S014 ✅ ──▶ S016 ✅ (Row-Level ACL 基礎建設；v1.2.0 2026-04-29)
                 │
                 ├──▶ S017 ✅ (ACL-Aware 語意搜尋；v1.3.0 2026-04-29)
                 └──▶ S018 ✅ (Skill Aggregate 充血演化 + SKILL.md alignment；v1.4.0 2026-04-29)
```

---

## 📦 In-Flight Milestone Details

### Milestone 17: Project Infra — Coverage Gate / Verify Registry / Phase 2 Doc-Sync ✅ `v1.1.1` (2026-04-28)

4/4 specs complete. Details → `specs/archive/2026-04-27-S019-*` / `2026-04-27-S020-*` / `2026-04-27-S021-*` / `2026-04-28-S022-*`

### Milestone 14: Row-Level ACL 基礎建設 ✅ `v1.2.0` (2026-04-29)

1/1 specs complete. Details → `specs/archive/2026-04-28-S016-row-level-acl-foundation.md`；ADR-001 §3.2/§8 修訂 `jsonb_path_ops` → default `jsonb_ops`（per S016 ship）。

### Milestone 15: ACL-Aware 語意搜尋 ✅ `v1.3.0` (2026-04-29)

1/1 specs complete. Details → `specs/archive/2026-04-29-S017-acl-aware-semantic-search.md`；T1+T2+T3 全 PASS（199/199 tests / 89% coverage）；validated patterns（`??|` SQL + oversample 5x + Builder dual-setter + Testcontainer truncate isolation）已寫入 spec §7.5 給 S018+ 引用。

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S017 | ACL-Aware 語意搜尋（PgVectorStore + ACL SQL composition） | S-M(11) | S016 ✅ | 🔲 Planning |

### Milestone 16: Skill Aggregate 充血演化 + SKILL.md 對齊 ✅ `v1.4.0` (2026-04-29)

1/1 specs complete. Details → `specs/archive/2026-04-27-S018-skill-aggregate-rich-domain.md`；T1+T2+T3+T4+T5 全 PASS（234/234 tests / 89.9% coverage）；validated patterns（enum-method override state machine + aggregate guard 不 mutate state + uploadSkill reload from events + SKILL.md `allowed-tools` 解析 + 嚴格化 regex）已寫入 spec §7.5 給未來 spec 引用。

---

## ✅ Shipped Milestones (Archive Pointers)

| Milestone | Version | Date | Specs | Archive |
|-----------|---------|------|-------|---------|
| M0: Project Init | `v0.1.0` | 2026-04-25 | S000 | `specs/archive/2026-04-24-S000-project-init.md` |
| M1: 技能瀏覽與搜尋 | `v0.2.0` | 2026-04-25 | S001, S002 | `specs/archive/2026-04-25-S00[12]-*` |
| M2: 技能發佈流程 | `v0.3.0` | 2026-04-25 | S003, S004 | `specs/archive/2026-04-25-S00[34]-*` |
| M3: 自動風險評估 | `v0.4.0` | 2026-04-25 | S005 | `specs/archive/2026-04-25-S005-*` |
| M4: 一鍵安裝（Web 下載） | `v0.5.0` | 2026-04-25 | S006 | `specs/archive/2026-04-25-S006-*` |
| M5: 語意搜尋 | `v0.7.0` | 2026-04-25 | S007 | `specs/archive/2026-04-25-S007-*` |
| M6: 使用數據分析 | `v0.6.0` | 2026-04-25 | S008 | `specs/archive/2026-04-25-S008-*` |
| M7: 設定檔最佳化 | `v0.8.0` | 2026-04-25 | S009 | `specs/archive/2026-04-25-S009-config-optimization.md` |
| M8: 安全掃描升級 | `v0.9.0` | 2026-04-26 | S010 | `specs/archive/2026-04-25-S010-multi-engine-scanner.md` |
| M9: 開發環境 OAuth Mock | `v0.10.0` | 2026-04-27 | S011 | `specs/archive/2026-04-25-S011-dev-oauth-mock.md` |
| M10: OAuth 開關 + LAB 模式 | `v0.11.0` | 2026-04-27 | S012 | `specs/archive/2026-04-27-S012-oauth-toggle-lab-mode.md` |
| M11: GCP Cloud Run 部署 | `v1.0.0` | 2026-04-27 | S013 | `specs/archive/2026-04-27-S013-gcp-deploy-scripts.md` |
| M12: PostgreSQL 資料層遷移 + PgVectorStore 接管 + Firestore 全清 | `v1.1.0` | 2026-04-27 | S014（含 S015 absorbed） | `specs/archive/2026-04-27-S014-postgresql-migration.md` + `adr/ADR-001-postgresql-migration.md` |
| M17: Project Infra（coverage gate + verify registry + frontend baseline） | `v1.1.1` | 2026-04-28 | S019, S020, S021, S022 | `specs/archive/2026-04-27-S019-*` / `2026-04-27-S020-*` / `2026-04-27-S021-*` / `2026-04-28-S022-*` |
| M14: Row-Level ACL 基礎建設（JSONB acl_entries + GIN + ACL CRUD endpoints） | `v1.2.0` | 2026-04-29 | S016 | `specs/archive/2026-04-28-S016-row-level-acl-foundation.md` |
| M15: ACL-Aware 語意搜尋（PgVectorStore + ?| filter + oversample HNSW recall fix） | `v1.3.0` | 2026-04-29 | S017 | `specs/archive/2026-04-29-S017-acl-aware-semantic-search.md` |
| M16: Skill Aggregate 充血演化 + SKILL.md 對齊 + Suspend/Reactivate Events | `v1.4.0` | 2026-04-29 | S018 | `specs/archive/2026-04-27-S018-skill-aggregate-rich-domain.md` |

---

## 🚫 Absorbed / Cancelled

### ~~Milestone 13: Spring AI PgVectorStore 接管向量寫入~~ → ABSORBED into S014（2026-04-27）

原 S015 scope（PgVectorStore 接管 + FirestoreVectorStore 刪除 + google-cloud-firestore dep 移除）併入 S014 T7。決策依據：T2 mega ship 後 Mongo deps 已乾淨，若分批保留 Firestore 至 S015，`SearchConfig` 雙條件分支 + `google-cloud-firestore` dep tree 將持續耦合一整輪 spec → 一次拆乾淨少一輪 PR review。詳 ADR-001 §4.5 + S014 spec §1 / §2.1 決策 #2 / #10。

| # | Spec | Status |
|---|------|--------|
| ~~S015~~ | ~~Spring AI PgVectorStore 接管向量寫入~~ | 🚫 ABSORBED → S014 T7 |

---

## 📚 Backlog (Future Research)

### 安全掃描 — 企業級升級方向

> 待研究。需先完成 S010 基礎 Pipeline 後，依企業客戶需求排優先級。

| 優先級 | Spec 方向 | 說明 | 研究基礎 |
|--------|----------|------|---------|
| SEC-B1 | 信任評分系統 | 多維度 Trust Score（作者信譽、掃描歷史、社群信號），SkillCard 顯示 trust badge。參考 MCPSkills.io 14 signals | deepwiki R7 |
| SEC-B2 | ASBOM 生成 | CycloneDX 1.6 Agent Skill Bill of Materials，滿足 EU AI Act / SOC2 合規 | `cyclonedx-core-java:12.1.0` Apache-2.0 |
| SEC-B3 | 加密簽章驗證 | Ed25519 skill 簽章 + 驗證，確保完整性。參考 MS Agent Governance Toolkit | deepwiki R7 |
| SEC-B4 | 供應鏈 CVE 掃描 | OWASP Dependency-Check 掃描 zip 內 requirements.txt / package.json 已知漏洞 | `dependency-check-core:12.2.1` Apache-2.0 |
| SEC-B5 | Behavioral AST 分析 | tree-sitter taint flow 分析（curl → eval = RCE），獨立引擎升級 | `tree-sitter-ng:0.26.6` MIT |
| SEC-B6 | 定期重新掃描 | 規則更新後對已發佈 skills 批次重掃，偵測新型攻擊 | Cisco skill-scanner rescan 機制 |
| SEC-B7 | ClamAV 惡意軟體掃描 | clamd sidecar container 整合，掃描 zip 中的二進位附件 | `clamav-client:2.1.2` MIT |
| SEC-B8 | LLM Guardrails 強化 | LangChain4j Guardrails 整合（jailbreak 偵測、PII masking） | `langchain4j-guardrails:1.13.1` Apache-2.0 |
| SEC-B9 | SARIF GitHub 整合 | 掃描結果上傳 GitHub Advanced Security Code Scanning alerts | SARIF 2.1.0 upload API |

### ~~Event Sourcing 進階功能~~ — **OBSOLETE pending S024 ship（per ADR-002）**

> **狀態變更（2026-04-29）**：ADR-002 Accepted — Skills Hub Core Domain 從純 Event Sourcing 轉向 Spring Data JDBC 充血聚合 + Modulith Outbox。`domain_events` 表退化為 audit log（由新增的 `AuditEventListener` 寫入），不再是 source of truth。
>
> 以下 ES 進階功能因此 **obsoleted**：
>
> | 原項目 | 為何 obsolete |
> |---|---|
> | ~~ES-B1 Event Replay（從 domain_events 重建 read model）~~ | Read model 與 aggregate 合一，不再需要 replay；如需重建 state，從 `skills` 表直接讀；audit trail 仍由 `domain_events` 提供（read-only） |
> | ~~ES-B2 Aggregate Snapshot~~ | Aggregate 不再 replay events 重建，無 snapshot 必要 — `repo.findById()` 即 O(1) row read |
> | ~~ES-B3 Event Upcasting~~ | 事件 schema 演化由 `event_publication` outbox 與 audit 各自處理；不需框架式 upcasting |
> | ~~ES-B4 Saga / Process Manager~~ | 若未來確有跨 aggregate 流程協調需求（如企業級 B7/B8 組織管理），重新評估獨立技術選型（可能用 Spring Modulith + state machine 而非 Saga 框架） |
>
> 以上若 S024 ship 後仍有需求，可重新建立新的 backlog 項目（時點屆時架構已不同，原描述已不適用）。

### Project Infrastructure（拆自其他 spec 的 follow-up）

> **2026-04-29 update**：原 backlog S023「Spring Modulith outbox migration」已升級為 Active spec — 詳 §Active Work。經 deepwiki 研究後拆為 **S023（基礎建設）+ S024（Skill 充血聚合）**；ADR-002 為架構決策依據。原 backlog 描述（M-L 12-15 pts、僅 listener migration）已過期；實際 S023 範圍為純基礎建設（M 12 pts），S024 為架構轉向（M 13 pts，target `v2.0.0`）。

| ID | Spec 方向 | 估算 | 觸發條件 / 依據 |
|---|---|---|---|
| **S025** | **Test Pyramid Realignment**（測試金字塔重整 + Scenario migration）| **L(15-18)** | **觸發**：S023 T07 揭露 ~50+ distinct context cache key → LRU evict + container churn + heap pressure（workaround：`maxHeapSize=3g + cache.maxSize=8`）；T07 採 Awaitility 30s 暫穩，但「30s timeout」是 timing race band-aid 非設計級正確。<br><br>**範圍 1 — Cache key 收斂**：53 個 `@SpringBootTest` 收到 5-7 個 cache key；`@MockitoBean EmbeddingModel/CurrentUserProvider`（8 file）收進 `TestcontainersConfiguration` 共用 `@Bean @Primary`；JSON converter 改 `@DataJdbcTest` slice；LabMode profile 收斂評估。<br>**範圍 2 — Scenario API migration**：所有 `@ApplicationModuleListener` async test 改 `@ApplicationModuleTest + Scenario` + 顯式 FK seed（已於 S023 T07 pilot 確認 cross-module FK 需手動 seed —— pilot revert 因 scope creep；詳 S023 spec §7.7）；Awaitility timeout 從 30s 收回 5s；2 個 disabled e2e MockMvc test 重撰。<br>**範圍 3 — Slice 重組**：repository test → `@DataJdbcTest`、controller test → `@WebMvcTest`、保留 ≤5 個 e2e `@SpringBootTest`；目標純單元測試比例 ≥50%。<br>**範圍 4 — workaround 移除**：`build.gradle.kts` 移除 `cache.maxSize=8` + heap 收回預設；`TestcontainersConfiguration` 移除 known-limitation comment。<br><br>**完成條件**：cache key ≤10、container 啟動 ≤3 次/run、Awaitility 5s 全綠、verify-all.sh 連續 3 次 PASS。<br><br>**研究基礎**：[Spring Boot 4 Testing Reference](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html) + [Spring Modulith Testing](https://docs.spring.io/spring-modulith/reference/testing.html) + Drotbohm "Introducing Spring Modulith" + S023 T07 pilot findings（FK seed 為 module-isolated test 必要）。 |
