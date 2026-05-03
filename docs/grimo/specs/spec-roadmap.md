# Skills Hub — Spec Roadmap

> 最後更新：2026-04-30（**S024 ✅ Shipped v2.0.0 (M19)** — Skill State-Based Aggregate Migration 完成；ADR-002 Phase 2 落地完成。Active 推進至 **S025a + S025b**（Test Pyramid Realignment，由 S023 T07 + S024 T05B test cascade 觸發；於 `/planning-spec S025` Phase 3 grill #1 用戶選 C 場景後拆分為 a/b 兩 spec — XL 強制拆，per estimation-scale.md L18+）。）

---

## 🎯 進行中工作 — 依序排序（下一步）

**Phase 4 — Test Pyramid Realignment（C 場景：徹底重整）**：詳 §Active Work `S025a / S025b` 拆分緣由。原單一 S025 在 `/planning-spec` Phase 3 grill #1 評估後落為 XL（17-18 pts），須拆為 S025a（mock lift + Scenario migration）+ S025b（slice 重組 + workaround 移除）兩 spec sequential ship。

### 建議執行順序

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

| 順序 | Spec | 標題 | 點數 | 相依 | 狀態 |
|------|------|------|------|------|------|
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
> **過往（歷史）Phase 2.5 + Phase 2 執行記錄移至 §已發布里程碑 表格**

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

| 順序 | Spec | 標題 | 點數 | 相依 | 狀態 |
|------|------|------|------|------|------|
| 1 | S019 | JaCoCo coverage gate + 80% line threshold | XS(5) | — | ✅ |
| 2 | S020 | Verification command registry + `scripts/verify-all.sh` | S(10) | S019 ✅ | ✅ |
| 3 | S021 | Phase 2 doc-sync — PRD.md + architecture.md | S(8) | — (可與 1/2 平行) | ✅ |
| 4 | S022 | Frontend Verification Baseline | S(8) | S020 ✅ | ✅ |
| 5 | S016 | Row-Level ACL 基礎建設（JSONB acl_entries + GIN）| M(13) | S014 ✅ | ✅ — `v1.2.0` |
| 6 | S017 | ACL-Aware 語意搜尋（PgVectorStore + ACL SQL composition）| S-M(11) | S016 ✅ | ✅ — `v1.3.0` |
| 7 | S018 | Skill Aggregate 充血演化 + SKILL.md 對齊 + Suspend/Reactivate Events | M(13) | S014/S016/S017 ✅ | ✅ — `v1.4.0` |

### 建議執行順序（歷史記錄）

```
S019 ─▶ S020 ─▶ S021 ─▶ S022   Phase 2.5（Project Infra · M17 · 31 pts · ✅ v1.1.1）
                          │
                          ▼
                     S016 ✅ ─▶ S017 ✅   Phase 2（Domain · M14 ✅ v1.2.0 / M15 ✅ v1.3.0）
                              │
                              └─▶ S018 ✅ (M16 ✅ v1.2.0 → graceful degrade 占位已移除)
```

| 順序 | Spec | 標題 | 點數 | 相依 | 狀態 |
|------|------|------|------|------|------|
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

## 📋 狀態總覽

| 階段 | 里程碑 | Spec(s) | 點數 | 累計 | 狀態 |
|------|--------|---------|------|------|------|
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
| Phase 4 | M81: HomePage rework + reusable components 抽取 | S085 | S(8) | 569 | ✅ `v2.63.0` (2026-05-02 — IconTile 6-category tint + 重寫 SkillCard 對齊 prototype `.sh-card` + Hero row 加「發布技能」CTA) |
| Phase 4 | M82: PublishPage rework | S086 | XS(5) | 574 | ✅ `v2.64.0` (2026-05-02 — Hero row + hairline card + uppercase muted labels + semantic-tinted success/error callouts with icons) |
| Phase 4 | M83: SkillDetailPage rework | S087 | S(7) | 581 | ✅ `v2.65.0` (2026-05-02 — Hero row 加 IconTile xl + 22px name + version mono pill + status semantic pill + danger-soft SUSPENDED callout) |
| Phase 4 | M84: AnalyticsPage rework | S088 | XS(5) | 586 | ✅ `v2.66.0` (2026-05-02 — Hero row + MetricCard label-caps + accent purple #7F77DD progress + mono tabular-nums；S084 META 5 sub-specs 全 ✅) |
| Phase 4 | M85: BorderBeam hand-roll BeamFrame（drop border-beam dep） | S089 | XS(3) | 561 | ✅ `v2.62.0` (2026-05-02 — `BeamFrame.tsx` 1:1 port prototype `.sh-search-wrap` conic-gradient；drop border-beam npm dep；JS bundle 396KB→347KB) |
| Phase 4 | M80: Semantic search `?limit=` configurable | S090 | XS(2) | 555 | ✅ `v2.60.0` (2026-05-02 — `?limit=` 1-50 cap；close R25.7 missing-feature；FE 「show more」UX enabled) |
| Phase 4 | M81: LlmJudge prompt calibration | S091 | XS(3) | 558 | ✅ `v2.61.0` (2026-05-02 — `SYSTEM_PROMPT` 重寫區分 demonstrated vs theoretical；fix Anthropic canonical skills 全 HIGH bug AN；handover/planning/deep-research HIGH→LOW，真風險維持 HIGH) |
| Phase 4 | M86: FE i18n VALIDATION_ERROR detail concat | S092 | XS(2) | 588 | ✅ `v2.67.0` (2026-05-02 — `ERROR_MESSAGE_BUILDER` function map；VALIDATION_ERROR / CONSTRAINT_VIOLATION concat backend field-aware message；FE tests 11→18；close R18.3 tech-debt「訊息過於 generic」) |
| Phase 4 | M87: Dev DB persistence (compose named volume) | S093 | XS(2) | 590 | ✅ `v2.68.0` (2026-05-02 — `compose.yaml` 加 named volume `backend_pgvector-data` + `application-local.yaml` `lifecycle-management: start-only`；dev PG 跨 session 持久；首次 transition 一次 fresh，自此 onwards 累積) |
| Phase 4 | M88: UI Round 2 META — 4 prototype mockups → 4 sub-specs | S094 | ~28-29 trim from 38-41 | — | ✅ all 4 sub-specs shipped (S094c+d+a+b)；total ~29 pts vs estimate 38-41 trim deferred to polish；3 mockups (admin/onboarding/landing) ⏸ post-MVP |
| Phase 4 | M88a: Empty State Collection (4 tones) | S094c | XS(5) | 595 | ✅ `v2.69.0` (2026-05-02 — `EmptyState.tsx` 4-tone (seed/invite/redirect/clear)；HomePage keyword/semantic 0-results 全改用之；FE tests 18→23；S094 META 1/4) |
| Phase 4 | M88b: Docs Walkthrough `/docs/your-first-skill` | S094d | XS(5) | 600 | ✅ `v2.70.0` (2026-05-02 — `YourFirstSkillPage.tsx` single-page JSX no parser dep + DocsLayout/Sidebar; AppShell 加「文件」nav；FE tests 23→28；S094 META 2/4) |
| Phase 4 | M88c: My Skills (Author Dashboard) `/my-skills` | S094a | S(9) trim from M | 609 | ✅ `v2.71.0` (2026-05-02 — `?author=` filter bypass PUBLISHED for author view; MySkillsPage hero + 4 metrics + tabs + table rows; EmptyState invite reuse; Sparkline 暫缺 polish；S094 META 3/4) |
| Phase 4 | M88d: Semantic Search Results `/search` | S094b | S(9-10) trim from M | 619 | ✅ `v2.72.0` (2026-05-02 — `/search?q=` 專屬 route + LLM intent summary card with graceful fallback (Optional<ChatClient>) + IntentSummaryCard purple bg + concept chips display；S094 META 4/4 全 ✅) |
| Phase 4 | M89: Risk tier 4-level (split LOW → NONE + LOW) | S095 | S(9) | — | ⛔ superseded — absorbed into S096c (UI v2 META) per Q3 grill 2026-05-02；NONE tier 與 RiskBadge dark-theme redesign 同 sub-spec ship 一次到位 |
| Phase 4 | M90: UI v2 META — full dark-theme redesign + 6 NEW pages + route schema | S096 | XL split → 92 pts across 8 sub-specs | — | 📐 in-design — META spec written 2026-05-02；defaults a/a/a/a 已 lock；NEW prototypes pascalcase 16 mockups source；既有 light-theme S085-S088+S094 work 大半 redesign |
| Phase 4 | M90a: ADR-003 + PRD update | S096a | XS(4) | 623 | ✅ `v2.73.0` (2026-05-02 — ADR-003 route schema author/name dual-route；PRD P7/P8/P9 三個 feature sections + 9 SBE scenarios + D25-D27；glossary +4 entries；S096 META 1/8) |
| Phase 4 | M90b: DESIGN.md v2 + global theme migration | S096b | M(12) trim ~8 | 631 | ✅ `v2.74.0` (2026-05-02 — index.css dark tokens 全 swap + BeamFrame 5-color rewrite per Handoff §8；DOM-shape tests 28/28 PASS；inline-hex polish 留 S096d；S096 META 2/8) |
| Phase 4 | M90c: Routing schema + Risk tier 4-level (absorbs S095) | S096c | M(12) trim ~9 | 640 | ✅ `v2.75.0` (2026-05-02 — `/skills/:author/:name` canonical + `:id` alias; RiskLevel + NONE; classifyRiskLevel 三條件分流; RiskBadge 4-tier dark; Flyway migration deferred for runtime-only path; S096 META 3/8) |
| Phase 4 | M90d1: Inline-hex bulk migration to dark tokens | S096d1 | S(8) split from L | 648 | ✅ `v2.76.0` (2026-05-02 — 20-color sed bulk across 8 files; v1 light hex → v2 dark rgba/light-text variants; tests 28/28 PASS; S096 META 4a/8) |
| Phase 4 | M90d2: SkillCard prototype polish + featured variant | S096d2 | S(7) trim from M | 655 | ✅ `v2.77.0` (2026-05-02 — SkillCard radius xl + mono author + 6 hex patch + featured variant via BeamFrame; SearchResults top-match beam ring; tests 28/28; S096 META 4b/8) |
| Phase 4 | M90d3: Per-skill stats endpoint + Sparkline + MySkills integration | S096d3 | S(8) trim from M | 663 | ✅ `v2.78.0` (2026-05-02 — backend `/api/v1/skills/{id}/stats?period=30d` + Sparkline.tsx SVG polyline (no chart dep) + MySkills SkillRow 30d sparkline column；P6 SBE 補完；S096 META 4c/8) |
| Phase 4 | M90d4a: /publish/review post-upload result page | S096d4a | XS(5) trim from M | 692 | ✅ `v2.83.0` (2026-05-02 — Step 3 URL split; PublishPage navigate to /publish/review?id=X; PublishReviewPage with risk-conditional callout; RiskLevel type +NONE parity; tests 28/28; S096 META 8a/8) |
| Phase 4 | M90d5a: Auto-poll /publish/review during scan | S096d5a | XS(3) trim from M | 695 | ✅ `v2.84.0` (2026-05-02 — useQuery refetchInterval 2s while risk_level==null; auto-stop on scan complete; Loader spinner; tests 28/28; S096 META 8b/8) |
| Phase 4 | M91: Swap BeamFrame to official border-beam package | S097 | XS(4) | 699 | ✅ `v2.85.0` (2026-05-02 — hand-roll → border-beam@1.0.1 wrapper with size=md/colorful/1.96s/strength=0.7; jsdom matchMedia polyfill; 8 call sites unchanged; +48KB bundle for visual parity) |
| Phase 4 | M92: META v2 prototype completeness audit | S098 | META | — | 📋 planning artefact — 16 prototype × shipped status matrix; 8 sub-specs (S098a-h) + 3 existing (S096f2/g2/h2) ≈ 85 pts to full v2 parity |
| Phase 4 | M92a: Publish Step 2 `/publish/validate` page (core) | S098a | XS(5) trim from M(10) | 738 | ✅ `v2.95.0` (2026-05-02 — 4-step stepper UI + auto-poll + auto-navigate；PublishPage onSuccess 改跳 /publish/validate 取代直接 /publish/review；4 tone StatusCallout；reuse PublishReviewPage refetchInterval pattern；defer SSE 真事件串流 + upload-strip file detail；tests 33/33 PASS) |
| Phase 4 | M92a2: SSE 事件串流 + per-step 動畫 | S098a2 | M(8) split from S098a | — | 📋 planned — backend 三 events (BundleParsed/FrontmatterValidated/RiskScanCompleted) + frontend SSE client |
| Phase 4 | M92a3: PublishValidate upload-strip (frontend-only) | S098a3 | XS(2) trim from XS(3) | 765 | ✅ `v3.0.0` (2026-05-02 — frontend-only upload-strip：FileArchive icon + skill.name 派生 filename + version + category + 「✓ 已上傳」綠色 success badge；defer S098a3-2 backend bundle-info endpoint；v3.0.0 milestone) |
| Phase 4 | M92a3-2: Backend bundle-info endpoint | S098a3-2 | XS(2) split from S098a3 | — | 📋 planned — backend GET `/skills/{id}/bundle-info` 返回 { filename, fileSize, fileCount, uploadedAt }；frontend strip 顯實值取代派生值 |
| Phase 4 | M92b: Publish Failures `/publish/failed` page (core) | S098b | XS(4) trim from S(8) | 721 | ✅ `v2.91.0` (2026-05-02 — `/publish/failed?state=A|B&msg=` page；State A 紅色 callout + msg pre-block；State B 橘色 callout + skill ID echo；PublishPage onError navigate State A；defer S098b2 PublishReviewPage HIGH-risk redirect + S098b3 full validation breakdown UI；tests 33/33 PASS；S098 META 5/8) |
| Phase 4 | M92b2: PublishReviewPage HIGH-risk redirect | S098b2 | XS(2) split from S098b | 723 | ✅ `v2.92.0` (2026-05-02 — useEffect on `skill?.riskLevel === 'HIGH'` → navigate /publish/failed?state=B&id=X (replace mode); remove inline HIGH callout dead code; State B navigation flow 完整；tests 33/33 PASS；S098 META 6/8) |
| Phase 4 | M92b3: Validation breakdown UI shell | S098b3 | S(4) trim from S(5) | 769 | ✅ `v3.1.0` (2026-05-02 — PublishFailedPage State A 升級為多段 v-section (SKILL.md / Bundle / Risk scan 三段並列)；ErrRow 結構 type ready for 未來結構化 findings payload；目前派生 single error row from flat msg；既有 4 ACs tests 不破；defer S098b3-2 backend 結構化 findings spec) |
| Phase 4 | M92b3-2: Backend 結構化 findings payload | S098b3-2 | M(6) split from S098b3 | — | 📋 planned — backend GET /publish error response 改 structured `findings: [{severity, rule, line, title, hint}]`；frontend ValidationSection rows 直接 consume |
| Phase 4 | M92c: Version Diff page (frontend-only) | S098c | S(6) trim from M(12) | 744 | ✅ `v2.96.0` (2026-05-02 — `/skills/:id/diff?from=&to=` side-by-side metadata diff (version/size/publishedAt + delta)；version selector chips；VersionList +「比較版本變化」連結；reuse useSkill/useVersions hooks (零 new endpoint)；defer backend diff endpoint S098c2 + file content diff S098c3；tests 33/33 PASS) |
| Phase 4 | M92c2: Backend /diff endpoint | S098c2 | M(8) split from S098c | — | 📋 planned — backend GET `/api/v1/skills/{id}/diff?from=&to=` with description/risk/sha per-version snapshot；需 SkillVersion 加 riskLevel + sha 欄位 + projection |
| Phase 4 | M92c3: File content line-level diff | S098c3 | L(12) split from S098c | — | 📋 planned — zip extract per version + line-level diff library (jsdiff) + side-by-side highlighting |
| Phase 4 | M92d: Homepage 3-column grid + sort chips | S098d | XS(3) trim from S(8) | 717 | ✅ `v2.90.0` (2026-05-02 — SkillCardGrid +xl:grid-cols-3；HomePage 加 4-mode sort chips (推薦/最新/風險低/下載最多) client-side sort；risk-filter sidebar defer 至 S098d2 (need new endpoint or aggregation)；tests 33/33 PASS；S098 META 4/8) |
| Phase 4 | M92d2: Homepage risk filter sidebar | S098d2 | S(5) | 763 | ✅ `v2.99.0` (2026-05-02 — RiskFilterSidebar 4-tier toggle + count breakdown (client-side aggregate skillsPage.content)；HomePage 加 riskFilter Set 配 sortMode → filteredAndSorted 兩階管線；對齊 RiskBadge palette dot；Homepage v2 polish trio 完成；tests 33/33 PASS） |
| Phase 4 | M92e: Skill Detail v2 polish (core) | S098e | XS(5) trim from S(8-9) | 728 | ✅ `v2.93.0` (2026-05-02 — Reviews + Flags tabs (stub via EmptyState)；hero +30d sparkline (reuse S096d3)；Files tab preserved (6 tabs deviation from prototype to keep S082 browser)；零 new component；tests 33/33 PASS；S098 META 7/8) |
| Phase 4 | M92e2: Reviews aggregate + ratings + SkillDetail Reviews tab | S098e2 | S(11) re-est from M(8) | v3.5.0 | ✅ shipped 2026-05-03 cron Tick 7-11 (5 ticks 含 spec planning)：T01 review/ 模組 + Review aggregate + ReviewService + endpoints + V8 (Tick 8) → T02 projection listener + skill 加 averageRating/reviewCount + V9 (Tick 9) → T03 frontend infra (Tick 10) → T04 ReviewsPanel + SkillDetail integration (Tick 11)。Backend 13 tests + frontend 19 tests 全 PASS；ModularityTests 仍乾淨（review allowedDependencies = shared::events/api/security + skill::domain + skill::query）。3 個 design deviation 紀錄於 §7（delete flow ApplicationEventPublisher / listener placement / ReviewsPanel extract）。為 S101b Impact Score 鋪好 averageRating sub-metric 路徑。Spec-Only-Handoff pattern 第 3 次端到端 demo。 |
| Phase 4 | M92e3: Flag 回報流程 — POST form + reviewer queue + status mutations | S098e3 | S(8) re-est from M(7) | v3.5.1 | ✅ shipped 2026-05-03 cron Tick 12-16 (5 ticks 含 spec planning)：T01 backend write flow + FlagStatus enum + cross-skill list (Tick 13) → T02 frontend infra (Tick 14) → T03 FlagsList CTA + FlagSubmitModal (Tick 15) → T04 FlagsQueuePage + AppShell nav + /flags route (Tick 16)。**零 schema migration** — status 既是 String 欄位純應用層 enum 擴充。Backend 16 tests + frontend 20 tests 全 PASS；ModularityTests 仍乾淨。為 ✅ S112 read 端補完 write loop；任何登入用戶可看 reviewer queue (MVP)。Spec-Only-Handoff pattern 第 4 次端到端 demo。 |
| Phase 4 | M92f: Docs IA — Overview + Risk Tiers | S098f | XS(5) trim from M(10) | 733 | ✅ `v2.94.0` (2026-05-02 — `/docs/overview` 入門概覽 + `/docs/risk-tiers` 4-tier 完整說明 (NONE/LOW/MEDIUM/HIGH)；DocsSidebar 該 2 item 變 active link；其餘 3 stub pages defer S098f2/f3；reuse DocsLayout + BeamFrame，零 new component；tests 33/33 PASS；🎉 S098 META 8/8 ✅ 完成) |
| Phase 4 | M92f2: Reference group 3 doc pages | S098f2 | S(6) | 750 | ✅ `v2.97.0` (2026-05-02 — SkillMdSpecPage + FrontmatterPage（fields table 必填 2 / 選填 6）+ BundleStructurePage（ASCII tree + 3 folder semantics）；DocsSidebar「參考」群全 4 item 變 active link；reuse DocsLayout pattern；零 new component；tests 33/33 PASS） |
| Phase 4 | M92f3: Publishing + API/Webhook docs groups | S098f3 | M(8) | 758 | ✅ `v2.98.0` (2026-05-02 — 5 stub pages：UploadValidatePage / VersioningPage / SemanticSearchPage / RestApiPage（14 endpoints quick ref table）/ EventPayloadPage（6 domain events schema）；DocsSidebar 全 11 item active link；Docs IA 達 prototype #16 完整對等；零 new component；tests 33/33 PASS） |
| Phase 4 | M92g: i18n 繁中化 audit (pass 1) | S098g | S(7) trim from M(10) | 709 | ✅ `v2.87.0` (2026-05-02 — Landing/Notifications/IntentSummaryCard/DocsSidebar/YourFirstSkillPage 5 surface 全 user-facing 英文 → 繁中；同步 IntentSummaryCard + DocsSidebar dark token migration；tests 33/33 PASS；HomePage/SkillCard pass 2 defer 至 polish backlog；S098 META 2/8) |
| Phase 4 | M92g+: i18n 繁中化 audit (pass 2) | S098g | XS(2) sweep | 711 | ✅ `v2.88.0` (2026-05-02 — CollectionsPage h1 + EmptyState helper label + YourFirstSkillPage RiskRow 3 strong tags；HomePage/SkillCard 已是繁中無工作；S098h2 EmptyState dark migration spawned for follow-up；tests 33/33 PASS) |
| Phase 4 | M92h2: EmptyState dark migration + 4-step i18n | S098h2 | XS(3) sister-fix | 714 | ✅ `v2.89.0` (2026-05-02 — Sister to S098h；EmptyState 4-tone 全身 hex 替換 (#181818→#EEECEA, bg-white→#0F0F12, #F9F8F4→#171719, #E6E1D9→rgba(255,255,255,0.06)) + InviteTone 4 step labels 繁中 + RedirectTone「Query ·」→「查詢 ·」；tests 33/33 PASS；S098 META 3/8) |
| Phase 4 | M92h: YourFirstSkillPage 配色對比修復 | S098h | XS(3) trim from S(6) | 702 | ✅ `v2.86.0` (2026-05-02 — full dark-token migration per prototype Docs.html: `#181818` text → `#EEECEA`, `bg-white` → `#0F0F12`, `#F9F8F4` cream → `#0F0F12` dark; CTA 反白為 `#EEECEA` on `#08080A`; CompareCard rgba bg/.20 border per `.desc-card` prototype; tests 5/5 PASS; S098 META 1/8) |
| Phase 4 | M90d6: /publish/validate Step 2 page + new domain events + SSE | S096d6 | M(8-10) split from S096d5 | — | 📋 planned — dedicated /publish/validate?id=X Step 2 poll page; 3 new domain events (Bundle/Frontmatter/RiskScan); SSE event stream backend; per-event UI animation |
| Phase 4 | M90e1: Landing page `/` public entry + stats endpoint | S096e1 | S(8) trim from M | 671 | ✅ `v2.79.0` (2026-05-02 — public LandingPage at /; HomePage moved to /browse; new GET /api/v1/stats aggregate; AppShell 瀏覽 path update; Onboarding deferred S096e2; S096 META 5a/8) |
| Phase 4 | M90e2: Onboarding wizard | S096e2 | S(7) split from S096e | — | ⏸ blocked — prototype HTML 缺（16 mockups 無 Onboarding）+ Step 4 starter pack 依賴未 ship 的 S096f Collections；ship S096f 後 + designer 補 prototype 後 unblock |
| Phase 4 | M90f1: Collections read-only stub | S096f1 | XS(5) trim from M | 681 | ✅ `v2.81.0` (2026-05-02 — backend GET /api/v1/collections stub; frontend /collections + EmptyState; AppShell 集合 nav; community/ package pre-aggregation; S096 META 6b/8) |
| Phase 4 | M90f2: Collections full feature | S096f2 | M(13) re-est from M(10-12) | — | 📐 in-design — 2026-05-03 spec doc 寫成；ADR-002 canonical aggregate (Spring Data JDBC 充血 + @MappedCollection skills + Modulith Outbox)；4 endpoints (POST create / GET list?category= / GET single + skills detail / POST install)；2 schema migrations (collections + collection_skills join)；2 domain events (CollectionCreated / CollectionInstalled)；12 ACs (9 backend + 3 frontend)。Install 走 **Approach C** (frontend orchestration: 後端記 event + bump install_count + 回 N 個 download URLs；frontend loop trigger N 個 browser download；reuse 既有 GET /skills/{id}/download 自然累計 download_count)。community module 借此 spec 補 `@ApplicationModule` 正式 register (allowedDependencies = shared::events/api/security + skill::domain)。Risk filter (PRD §P7 SBE Scenario 3) defer 至 S096f3 polish。Skill picker UI 漂亮版 + Collection detail page + edit/delete defer。為 S101b Impact Score 提供 install event hook。 |
| Phase 4 | M90f3: Collections risk filter polish | S096f3 | XS(3-4) | — | 📋 planned — defer from S096f2：在 GET /collections 加 `?risk-max=LOW\|MEDIUM\|HIGH` query；JOIN collection_skills × skills.risk_level 過濾「含高 risk 成員的 collection」（PRD §P7 SBE Scenario 3）。前端 sidebar filter chip。 |
| Phase 4 | M90g1: Request Board read-only stub | S096g1 | XS(5) trim from M | 676 | ✅ `v2.80.0` (2026-05-02 — backend GET /api/v1/requests stub returns []; frontend /requests RequestBoardPage with EmptyState; AppShell 需求 nav; S096g2 deferred for full feature; S096 META 6a/8) |
| Phase 4 | M90g2: Request Board full feature — aggregate + voting + claim + fulfillment | S096g2 | S(11) re-est from M(10-12) | — | 🚧 in-progress (4 tasks queued — cron tick handoff Tick 17) — T01 backend aggregate + service + claim/fulfill/delete + V10 schema → T02 vote toggle service + endpoint + race tests → T03 frontend infra (api/skills.ts request mutations + useRequests/useRequest) → T04 RequestBoardPage CTA + CreateRequestModal + VoteButton + RequestActionBar + tests；execution order T01→T02→T03→T04。原始 design：ADR-002 canonical aggregate (Spring Data JDBC 充血 + Modulith Outbox)；7 endpoints；2 schema migrations (requests + request_votes UNIQUE join)；5 domain events；17 ACs (14 backend + 3 frontend)；7 個 UX 決策。Vote count 走 raw SQL atomic UPDATE pattern (對齊 S076 download_count)。為 S096h2 Notifications 提供 5 個 event hook。 |
| Phase 4 | M90h1: Notifications stub + bell badge | S096h1 | XS(6) trim from M | 687 | ✅ `v2.82.0` (2026-05-02 — backend GET /notifications + /unread-count stubs; frontend /notifications page + AppShell bell badge polls 30s; new notification/ module pre-aggregation; tests 28/28; S096 META 7a/8) |
| Phase 4 | M90h2: Notifications full projection + Version Diff | S096h2 | M(10-12) split from S096h | — | 📋 planned — real domain_events projection + per-user subscription model + 4 mutation endpoints + WebSocket eval + Version Diff page + @ApplicationModule(notification) |
| Phase 5 | M93: META Trust Maturity & Implementation Audit | S099 | META | — | 📐 in-design — 整合 user 連續 3 directives：(1) page audit + OpenAPI 3.1 + skill 文本輸入；(2) cross-marketplace 風險驗證 + LLM description 簡化；(3) OWASP LLM Top 10 對齊。5 P0 sub-specs (a/b/c/d/e META) ≈ 80-100 pts。 |
| Phase 5 | M93a: OpenAPI 3.1 verification | S099a | XS(2) | v3.4.12 | ✅ shipped 2026-05-03 — SpringBootTest 鎖 `GET /v3/api-docs` 返 openapi=3.1.0；OverviewPage 加「API 標準對齊」H2 + Swagger UI link；走 Plan B（應用 yaml 已有 `version: openapi_3_1` 設定）。 |
| Phase 5 | M93b: PublishPage text input mode (core) | S099b | XS(4) trim from S(8) | 774 | ✅ `v3.3.0` (2026-05-02 — Tabs (檔案/文本) + textarea synthesize `new File([text], 'SKILL.md', 'text/markdown')` reuse 既有 uploadSkill mutation；零 backend 改動 (S053 raw .md ext 支援)；defer yaml live validation + markdown preview 至 S099b2/b3) |
| Phase 5 | M93b2: yaml-frontmatter live validation | S099b2 | XS(3) split from S099b | 783 | ✅ `v3.3.3` (2026-05-02 — validateFrontmatter pure parser (---開頭/結束 + name/description 必填)；inline ValidationCheck UI 3 項即時 feedback；submit gate 阻 errors > 0；8 ACs (1 positive + 5 negatives + 2 edge per methodology)；tests 129→137 PASS) |
| Phase 5 | M93b3: markdown preview pane | S099b3 | S(5) split from S099b | 791 | ✅ `v3.4.0` (2026-05-02 — hand-rolled MiniMarkdown 零 dep 取代 react-markdown 50KB；split-view toggle on text mode；frontmatter strip from preview；10 ACs tests (5 positive + 3 neg + 2 edge per methodology)；發現修復 infinite loop bug (##NoSpace fallthrough) + JSX attr `\n` literal bug) |
| Phase 5 | M93c: Cross-marketplace risk validation | S099c | M(10) | — | 📋 planned — scrape awesome-claude-code-skills repo / agentskills.io 上市 skills；bulk upload；產 cross-validation-report.md 比對 risk_level agreement rate |
| Phase 5 | M93d: LLM description quality audit | S099d | M(12) | — | 📋 planned — LLM rubric 跑既有所有 skills description；指標 concrete-verb / domain-noun / marketing-blurb / 字數分佈；報告底分 Top N |
| Phase 5 | M93e: META OWASP LLM Top 10 alignment | S099e | L(15+) split | — | 📋 planned — 對齊 OWASP LLM01-10 v1.1；split into e1-e5：prompt-injection detector / DoS scanner / SBOM / hardcoded-creds / docs page |
| Phase 5 | M93e1: Prompt-injection pattern detector (LLM01) | S099e1 | M(8) | — | 📋 planned — 擴 risk scanner 偵測 jailbreak / instruction-override pattern in SKILL.md instructions |
| Phase 5 | M93e2: Resource DoS scanner (LLM04) | S099e2 | S(5) | — | 📋 planned — 偵測 infinite loops / large memory in scripts |
| Phase 5 | M93e3: SBOM + dependency scanning (LLM05) | S099e3 | M(8) | — | 📋 planned — 產 SBOM；npm audit / pip-audit equivalent for declared deps |
| Phase 5 | M93e4: Hardcoded creds detector enhancement (LLM06) | S099e4 | S(4) | — | 📋 planned — 擴 detector：API key patterns / hardcoded passwords / OAuth tokens |
| Phase 5 | M93e5: Docs page「Risk Scanner 涵蓋與限制」(LLM01-10) | S099e5 | S(3) | 777 | ✅ `v3.3.1` (2026-05-02 — `/docs/risk-scanner-scope` 12 sections per LLM01-10 + 4-tier summary card + 免責聲明 callout；DocsSidebar +12th item；2/3/1/4 (covered/partial/gap/oos) breakdown；tests 123/123 PASS) |
| Phase 5 | M94: META Page Data Authenticity Audit | S100 | META | 790 | ✅ META complete (2026-05-02 — 27 pages audited, 0 fake confirmed; 5/5 sub-specs S100a/b/c/d/e shipped 12 pts over v3.2.6→v3.4.1; existing 9-spec backlog (S096f2/g2/h2 + S098c2/c3/b3-2/e2/e3) queued in roadmap) |
| Phase 5 | M94a: AnalyticsPage Top 10 link to skill detail | S100a | XS(2) | 770 | ✅ `v3.2.6` (2026-05-02 — backend OverviewStats.TopSkill +author 欄位；frontend AnalyticsPage Top 10 div→Link to /skills/:author/:name canonical route per ADR-003；hover bg highlight；tests 123/123 PASS) |
| Phase 5 | M94b: HomePage server-side sort | S100b | XS(3) trim from S(5) | 786 | ✅ `v3.3.5` (2026-05-02 — backend SkillQueryService SORTABLE_PROPERTIES +riskLevel；frontend fetchSkills map sort=newest/most-downloaded/risk-low → Spring Pageable `sort=field,direction`；HomePage 移除 client-side filteredAndSorted/RISK_ORDER；跨頁全域 sort；defer hot-30d-download-rank 為 future polish (需 join download_events)；tests 137/137 PASS) |
| Phase 5 | M94c: PublishPage author prefill from /me | S100c | XS(2) reframed | 788 | ✅ `v3.3.7` (2026-05-02 — Reframed: MySkillsPage 既已 use useMe (S094a)；real gap 是 PublishPage 手填 author。useMe + useEffect prefill；authorTouched state 防 overwrite；placeholder 動態 me?.sub；helper hint 說明可改) |
| Phase 5 | M94d: ErrorState component + 2 demo migrations | S100d | XS(3) | 780 | ✅ `v3.3.2` (2026-05-02 — ErrorState shared component (inline/centered variants + danger-soft palette + custom icon/className override) + 6 ACs tests + AnalyticsPage/PublishPage migrations；剩 3 callsites defer polish backlog) |
| Phase 5 | M94e: AnalyticsPage Top 10 link defensive guard | S100e | XS(2) | 790 | ✅ `v3.4.1` (2026-05-02 — frontend 三重 guard typeof + length + 字面 "undefined" 字串；author 缺失時 row 退回非 link `<div>`；4 ACs PASS (1.07s)；分工：user 提需求 + agent 寫 spec + cron tick implement，validates Spec-Only-Handoff principle) |
| Phase 5 | M95: META Skill Quality / Impact / Security Score System | S101 | META **awaiting human confirm** | — | 📐 in-design — 對標 Tessl Skill Optimizer 三軸 trust signal；Skills Hub 用 PostgreSQL + Gemini + 既有 risk scanner 實作；6 sub-specs ≈ 38 pts；7 open questions 待 user 確認 |
| Phase 5 | M95a: Quality Score backend + LLM judge | S101a | M(10) | — | 📋 awaiting S101 confirm — 4 dimensions × 25%（completeness/actionability/conciseness/robustness）；Gemini judge；skill_scores projection table |
| Phase 5 | M95b: Impact Score proxy metrics | S101b | M(8) | — | 📋 awaiting S101 confirm — 4 sub-metrics（adoption/rating/flag inverse/trend slope）；proxy 替代 Tessl in-sandbox eval |
| Phase 5 | M95c: Security Status simplified + Snyk-equivalent | S101c | S(6) | — | 📋 awaiting S101 confirm — 既有 4-level risk → Pass/Warn/Fail tri-state；併 OWASP LLM05 SBOM (S099e3) |
| Phase 5 | M95d: Frontend SkillCard + Scores tab | S101d | S(8) | — | 📋 awaiting S101 confirm — SkillCard inline 3 figures + SkillDetailPage 第 7 tab radial chart |
| Phase 5 | M95e: Quality Score weekly re-evaluation cron | S101e | XS(3) | — | 📋 awaiting S101 confirm |
| Phase 5 | M95f: Score audit log + admin recalculate UI | S101f | XS(3) | — | 📋 awaiting S101 confirm |
| Phase 5 | M96: Back-nav + footer link target fix-ups | S102 | XS(3) | 793 | ✅ `v3.4.2` (2026-05-03 — 5 處 link target 替換：SkillDetailPage 「返回列表」x2 → `/browse` + error label 統一；SearchResultsPage clear-query nav + EmptyState CTA → `/browse`；LandingPage footer 「狀態」placeholder 移除；FE tests 28→30 PASS；sibling to S100e — S100 META post-ship cross-cutting follow-up pattern) |
| Phase 5 | M97: Stub-page user-facing spec ID leak fix | S103 | XS(2) | 795 | ✅ `v3.4.3` (2026-05-03 — 6 處 user-visible string 移除 internal spec ID `S096f2`/`S096g2`，改用「即將開放」/「後續版本推出」泛詞；CollectionsPage + RequestBoardPage 各 3 處；FE tests 30→32 PASS；Chrome MCP smoke verified；S100 META cross-cutting follow-up 第 3 個 = sibling chain S100e → S102 → S103 全 v3.4.x) |
| Phase 5 | M98: Risk filter empty-state + pagination UX consistency | S104 | XS(3) | 798 | ✅ `v3.4.4` (2026-05-03 — HomePage 3 處 conditional：filter active 時 EmptyState 用 redirect-tone + selected tier label headline + 「清除篩選」escape button，count 顯 `0 個技能（共 103）`，pagination hide 0-hits；EmptyState.tsx RedirectTone 補既有 primaryAction prop render；FE tests 32→36 PASS；Chrome MCP smoke 確認 4 signal 一致；S100 META cross-cutting follow-up 第 4 個) |
| Phase 5 | M99: EmptyState invite-tone steps decoupling | S105 | XS(3) | 801 | ✅ `v3.4.5` (2026-05-03 — EmptyState `steps?: string[]` optional prop + InviteTone conditional render；MySkillsPage opt-in 保留 publish onboarding；其他 4 callsites (Collections/Requests/Reviews/Search) 自動 hide off-context strip；FE tests 6/6 PASS；Chrome MCP smoke `/collections` no-strip + `/my-skills` strip 驗證；S100 META cross-cutting follow-up 第 5 個) |
| Phase 5 | M100: Sort `推薦` behavior alignment with design intent | S106 | XS(2) | 803 | ✅ `v3.4.6` (2026-05-03 — skills.ts sortMap 加 `recommended: 'downloadCount,desc'` + 移除 fall-through exclusion；HomePage:15 stale JSDoc 更新為事實 (backend default 實為 createdAt DESC + S106 alignment 說明)；FE tests 36→37 PASS；Chrome MCP smoke 4 chip distinct first card 驗證 (推薦 r19-lifecycle ≠ 最新 r35-docker)；S100 META cross-cutting follow-up 第 6 個) |
| Phase 5 | M101: Semantic search response projection completeness | S107 | S(5) trim from S(5-7) | 808 | ✅ `v3.4.7` (2026-05-03 — `SemanticSearchService` 注入 `SkillRepository` + batch `findAllById` + `toResult(doc, skill)` 從 canonical Skill aggregate 取 metadata；root cause 改採 read-path lookup 一勞永逸 bypass vector_store metadata drift（write-side projection bug + 存量 backfill 都列 polish backlog）；backend tests `*SemanticSearch*` 全 PASS in 2m 1s；live smoke deferred 至 backend restart；首個 cross-cutting follow-up 涉 backend 改動) |
| Phase 5 | M102: Vite dev proxy for SpringDoc + footer API link UX | S108 | XS(2) | 810 | ✅ `v3.4.8` (2026-05-03 — vite.config.ts proxy 加 `/v3/api-docs` + `/swagger-ui` 兩條 → backend :8080；LandingPage footer 「API」href 從 raw JSON 改 swagger-ui visual UI；FE tests 37→40 PASS；curl smoke `:5173/v3/api-docs` 從 `text/html` SPA fallback 變 `application/json` ✓；S100 META cross-cutting follow-up 第 8 個 = sibling chain S100e → S102~S108 全 v3.4.x complete) |
| Phase 5 | M103: Vite dev proxy for actuator endpoints | S109 | XS(1) | 811 | ✅ `v3.4.9` (2026-05-03 — Mode B Round 14 extends S108 audit cut: actuator 同 SPA fallback gap；vite.config.ts proxy 加 `/actuator` prefix 一條 → 自動 cover health/info/prometheus/metrics；首個 cron tick 不經 Spec-Only-Handoff full-ship 案例 (XS=1 pure dev config + sibling pattern 已驗)；curl smoke `:5173/actuator/health` 從 SPA fallback 變 actuator JSON ✓) |
| Phase 5 | M104: MySkillsPage zh-TW label compliance | S110 | XS(2) | 813 | ✅ `v3.4.10` (2026-05-03 — Mode B Round 15 audit: MetricCard labels (`Total skills` etc.) + status subtitle (`X published · X draft · X suspended`) 仍英文違反 CLAUDE.md zh-TW rule；5 處 string 替換對齊既有 TabPill terminology；FE tests 40→43 PASS；Chrome MCP smoke 4 zh-TW labels ✓ + 5 English leftover 全 removed ✓；第 2 個 single-tick full-ship 案例) |
| Phase 5 | M105: RiskTiersPage zh-TW tier title compliance | S111 | XS(2) | 815 | ✅ `v3.4.11` (2026-05-03 — Mode B Round 16 跑 S110 §8 提議的 i18n grep audit cut，命中 `/docs/risk-tiers` 4 個 Tier titles 仍英文 (`Pure documentation`/`Auto-published`/...)；4 處替換 (「純文件」/「自動上架」/「自動上架（顯警示標）」/「暫不上架，待審核員核准」)；Chrome MCP smoke verified；第 3 個 single-tick full-ship；i18n grep audit cut effectiveness 首次 systematic 驗證) |
| Phase 5 | M106: Flag wiring full-stack — SkillDetail Flags tab + MySkills openCount metric | S112 | S(7) | v3.4.13 | ✅ shipped 2026-05-03 cron Tick 3-6 (4 ticks)：T01 backend `/me/flags-summary` + T02 fe-infra（api/flags.ts + lib/flag-labels.ts）+ T03 SkillDetail Flags tab（FlagsList extract 至獨立 component）+ T04 MySkills 4-card → 3-card（移除假平均評分 + 接 useFlagsSummary）。全跨 Modulith 邊界 + 跨 frontend page 串通。10/10 frontend tests PASS + backend 6 tests (controller slice + service Testcontainers + ModularityTests) 全 PASS；3 個 design deviation 紀錄於 §7。Spec-Only-Handoff pattern 首次端到端 4-tick demo（首例 S099a 是單 task）。 |
| Phase 4 | (deferred) Admin Review Queue | S094e | — | — | ⏸ post-MVP — PRD B6 Backlog；`admin_review_queue_and_detail.html` |
| Phase 4 | (deferred) Onboarding Wizard | S094f | — | — | ⏸ post-MVP — `onboarding_wizard_step_2_of_4.html` |
| Phase 4 | (deferred) Landing Page | S094g | — | — | ⏸ post-MVP — `skills_hub_landing_page.html` |

**MVP（v1.0.0）**：14 specs / 147 story points 已完成 🎉
**Phase 1（PostgreSQL 遷移 v1.1.0）**：1 spec / 20 story points 已完成（S015 absorbed）
**Phase 2.5（Project Infra）**：4 specs / 31 story points 已完成 `v1.1.1`（2026-04-28）— S019 JaCoCo gate / S020 verification registry + verify-all.sh / S021 PostgreSQL doc-sync / S022 Frontend verification baseline
**Phase 2（Row-Level ACL + Aggregate 充血）**：3/3 specs 全部完成 — S016 ✅ `v1.2.0` + S017 ✅ `v1.3.0` + S018 ✅ `v1.4.0`（2026-04-29 同日連續 ship / 共 37 story points）

---

## 🔗 相依關係圖

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

## 📦 進行中里程碑明細

### Milestone 17: Project Infra — Coverage Gate / Verify Registry / Phase 2 Doc-Sync ✅ `v1.1.1` (2026-04-28)

4/4 specs 完成。詳情 → `specs/archive/2026-04-27-S019-*` / `2026-04-27-S020-*` / `2026-04-27-S021-*` / `2026-04-28-S022-*`

### Milestone 14: Row-Level ACL 基礎建設 ✅ `v1.2.0` (2026-04-29)

1/1 specs 完成。詳情 → `specs/archive/2026-04-28-S016-row-level-acl-foundation.md`；ADR-001 §3.2/§8 修訂 `jsonb_path_ops` → default `jsonb_ops`（per S016 ship）。

### Milestone 15: ACL-Aware 語意搜尋 ✅ `v1.3.0` (2026-04-29)

1/1 specs 完成。詳情 → `specs/archive/2026-04-29-S017-acl-aware-semantic-search.md`；T1+T2+T3 全 PASS（199/199 tests / 89% coverage）；驗證 patterns（`??|` SQL + oversample 5x + Builder dual-setter + Testcontainer truncate isolation）已寫入 spec §7.5 給 S018+ 引用。

| # | Spec | 點數 | 相依 | 狀態 |
|---|------|------|------|------|
| S017 | ACL-Aware 語意搜尋（PgVectorStore + ACL SQL composition） | S-M(11) | S016 ✅ | 🔲 Planning |

### Milestone 16: Skill Aggregate 充血演化 + SKILL.md 對齊 ✅ `v1.4.0` (2026-04-29)

1/1 specs 完成。詳情 → `specs/archive/2026-04-27-S018-skill-aggregate-rich-domain.md`；T1+T2+T3+T4+T5 全 PASS（234/234 tests / 89.9% coverage）；驗證 patterns（enum-method override state machine + aggregate guard 不 mutate state + uploadSkill reload from events + SKILL.md `allowed-tools` 解析 + 嚴格化 regex）已寫入 spec §7.5 給未來 spec 引用。

---

## ✅ 已發布里程碑（歸檔索引）

| 里程碑 | 版本 | 日期 | Specs | 歸檔 |
|--------|------|------|-------|------|
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

## 🚫 已合併 / 已取消

### ~~Milestone 13: Spring AI PgVectorStore 接管向量寫入~~ → ABSORBED into S014（2026-04-27）

原 S015 scope（PgVectorStore 接管 + FirestoreVectorStore 刪除 + google-cloud-firestore dep 移除）併入 S014 T7。決策依據：T2 mega ship 後 Mongo deps 已乾淨，若分批保留 Firestore 至 S015，`SearchConfig` 雙條件分支 + `google-cloud-firestore` dep tree 將持續耦合一整輪 spec → 一次拆乾淨少一輪 PR review。詳 ADR-001 §4.5 + S014 spec §1 / §2.1 決策 #2 / #10。

| # | Spec | 狀態 |
|---|------|------|
| ~~S015~~ | ~~Spring AI PgVectorStore 接管向量寫入~~ | 🚫 ABSORBED → S014 T7 |

---

## 📚 待辦清單（後續研究）

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
