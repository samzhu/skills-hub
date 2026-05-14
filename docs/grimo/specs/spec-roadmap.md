# Skills Hub — Spec Roadmap

> 最後更新：2026-05-14（S171 v4.58.0 ship — Spring AI M6 model abstraction cleanup；`verify-all.sh` 全綠；ChatClient/ChatModel/EmbeddingModel 邊界完成）

## 使用說明

此檔案是輕量索引，只記錄 SpecID / 標題 / 點數 / 相依 / 狀態。設計決策、研究結論、AC 細節、ship 記錄，請查對應的 spec 檔案（`docs/grimo/specs/` 或 `specs/archive/`）。

**狀態符號：** ✅ shipped · 📐 in-design · 📋 planned · ⏳ in-progress · ⏸ deferred · ⛔ cancelled

---

## 📝 待辦清單

**需要使用者操作：**
- [x] S132 T02: ✅ 完成 2026-05-07 — `gcloud builds submit` LAB pipeline 跑通；Developer Connect push-trigger 待運維時機再啟用
- [x] S134 T04: ✅ 完成 2026-05-05 — Google login PASS, claim shape in §7

**設計已備妥，可直接 /planning-tasks：**
- [ ] S099e1: Prompt-injection scanner（設計完成於 S099 META §6）
- [ ] S099e3: Dependency vulnerability scanner（設計完成於 S099 META §6）
- [x] S099c: spec doc 完成 2026-05-07 — tools/cross-validate.py + report
- [x] S114a: RBAC ACL Owner+Viewer（✅ shipped 2026-05-06 — v4.0.0）

**Deferred polish（低優先）：**
- [ ] S098a2: SSE 事件串流（條件：scan 平均 > 5s 且 user 抱怨等待）
- [x] S129: Server compression（spec 設計中，📐）
- [ ] S131: Error code naming convention

---

## 🚧 Active

| SpecID | 標題 | 點數 | 相依 | 狀態 |
|--------|------|------|------|------|
| S099 | META Trust Maturity & Implementation Audit | META | — | ✅ sub-specs all shipped (S099c v4.6.0 + S099d v4.7.0 + S099e1-e4) |
| S120 | E2E auth integration test | M(8-10) | S114a | ✅ v4.8.0 — anonymous GET public → 200；root cause: @TestConfiguration on AotStubBeans |
| S135 | META Skill Quality Score System (Tessl 8-dim) | META | — | ✅ shipped — S135a (v3.14.0) + S135b (v4.1.0) |
| S141 | `/api/v1/me` Display Claims（補 email/name/picture pass-through，修 user-visible 顯示成 Google sub bug） | XS(7) | S139 ✅（v4.18.0）| ✅ v4.21.0 — 3/3 tests PASS (MeController OAuth + LAB 兩分支) |
| S142a | SkillDetailPage v2 frontend rework | M-L(13-15) | S142b API contract | ✅ v4.22.0 — 318/318 Vitest PASS (6 tasks: T01 foundation + T02 hero + T03 tabs + T04 FileExplorer + T05 Sidebar + T06 page assembly); 0 TS production errors |
| S142b | SkillDetailPage v2 backend supplement (SkillScore composite + SecurityReport 4-quad + Skill aggregate field projection) | S-M(8-10) | S135a ✅ | ✅ v4.1.0 — 41/41 tests PASS (21 SecurityCategoryMapper + 3 SecurityReport + 6 SkillScore + 5 QualityScore + 4 SkillQueryService + 2 APIContract) |
| S172 | Production UI responsive polish（Skill detail sidebar no horizontal overflow；AppShell compact nav；Browse 0-result suggestions；CreateCollectionModal 用「我的技能」下拉新增/移除取代 UUID textarea；MySkills dark segmented tabs） | S(9) | S142a ✅, S143 ✅, S096f2 ✅, S094a ✅ | 📐 in-design — production Chrome audit 2026-05-14 |

---

## 🏁 Milestones

里程碑管理：每個版本要出哪些 specs。欄位：目標版本 · 包含 Specs · 狀態。

| 版本 | 包含 Specs | 狀態 |
|------|-----------|------|
| v3.11.0 | S133 (Markdown export) | ✅ shipped 2026-05-05 |
| v3.12.0 | S134 (Real OAuth trial — Google OAuth) | ✅ shipped 2026-05-05 |
| v3.13.0 | S138 (test debt recovery — 18 failures → 0) | ✅ shipped 2026-05-05 |
| v3.14.0 | S135a (Backend Quality Score) | ✅ shipped 2026-05-06 |
| v4.17.0 | S132 (CI Cloud Build pipeline — LAB manual submit 跑通) | ✅ shipped 2026-05-07 |
| v4.18.0 | S139 (Login UI + Lazy Auth Gate + LAB Google OAuth E2E) | ✅ shipped 2026-05-07 |
| v4.19.0 | S140 (E2E Critical Path Backfill — 6 happy-path specs + TestDataController) | ✅ shipped 2026-05-07 |
| v4.0.0 | S114a (RBAC ACL Owner+Viewer) | ✅ shipped 2026-05-06 |
| v4.1.0 | S135b (Frontend Quality Display) | ✅ shipped 2026-05-06 |
| v4.2.0 | S120 (E2E auth integration test) | ✅ shipped v4.8.0 |
| v4.2.0 | S099e1 (PromptInjectionScanner — OWASP LLM01) | ✅ shipped 2026-05-07 |
| v4.3.0 | S099e3 (DependencyVulnScanner — OWASP LLM05) | ✅ shipped 2026-05-07 |
| v4.4.0 | S099e4 (SecretScanner +6 patterns — OWASP LLM06) | ✅ shipped 2026-05-07 |
| v4.5.0 | S099e2 (ResourceDoSScanner — OWASP LLM04) | ✅ shipped 2026-05-07 |
| v4.6.0 | S099c (cross-marketplace validation) | ✅ shipped — tools/cross-validate.py 41 skills |
| v4.7.0 | S099d (LLM description quality audit) | ✅ shipped — tools/quality-audit.py 5-dim rubric |
| v4.16.0 | S098c3 (file-list diff) | ✅ shipped — GET /file-list-diff + FileListDiffPanel |
| v4.50.0 | S154b (Author display identity — frontend) | ✅ shipped 2026-05-11 |
| v4.51.0 | S144 (Skill delete) | ✅ shipped 2026-05-11 |
| v4.52.0 | S145 (Subscription management tab) | ✅ shipped 2026-05-11 |
| v4.56.0 | S170 (Group tree principal model) | ✅ shipped 2026-05-14 — S170 all AC 1-15 PASS，`verify-all.sh` 全綠 |
| v4.57.0 | S169 (CQRS permission contract) | ✅ shipped 2026-05-14 — S169 all AC 1-15 PASS，`verify-all.sh` 全綠 |
| v4.58.0 | S171 (Spring AI M6 model abstraction cleanup) | ✅ shipped 2026-05-14 — S171 all AC 1-10 PASS，`verify-all.sh` 全綠 |

---

## 📋 Backlog

| SpecID | 標題 | 點數 | 相依 | 狀態 |
|--------|------|------|------|------|
| S143 | `/docs` canonical entry → `/docs/overview`（含 nav 對齊）| XS(2) | — | ✅ v4.23.0 — 4/4 vitest PASS（Navigate replace + nav 對齊） |
| S146 | 掃描器補強 — GitHub Actions Unpinned Dependency 偵測（`@master/@main/@HEAD`） | XS(3) | S147 research | ⛔ cancelled 2026-05-13 — 不做；移除 spec file |
| S147 | Issue-code scanner architecture（Snyk agent-scan taxonomy + per-code detectors + detail security tab） | L(16 tasks) | — | ⏳ Plan — 2026-05-14 重新規劃；每個檢測項目獨立 task + POC first |
| S148 | Bug — GraalVM native image AOT reflection 涵蓋不足（JudgeResponse 部分） | S(5) | — | ✅ v4.25.0 — 4/4 JudgeResponse deserialize PASS（@RegisterReflectionForBinding + catch(Error) 防 outbox 無限重試） |
| S148b | GraalVM AOT 驗證機制 — POC reject H1（SkillshubProperties 無 bug）；`--exact-reachability-metadata` flag 已 ship；architecture.md 文件化 | XS(3) | — | ✅ v4.46.0 — architecture.md 加 GraalVM AOT Strategy 段（5 子段 + reviewer 4 自檢）|
| S148f | cyclonedx-bom 3.2.4 + nativeCompile 衝突修復 — 升 plugin 4.x / 換 SPDX / 隔離 task graph（POC 決定）；ship native production deploy 才會擋到 | XS(3) — POC 結果可漲 S | 上游修復 ＋ SBOM 消費者 ＋ native deploy 任一觸發 | 🗄️ archived 2026-05-13 — 原 ⏸ deferred 2026-05-10（POC H1 reject：cyclonedx 無 4.x；上游 issue #821 open 無修復）；spec file 移至 `specs/archive/`，reactivate 觸發條件不變 |
| S148c | Modulith cycle 修復 — `shared.api.GlobalExceptionHandler` ↔ `skill.validation` 雙向相依（拖垮 processTestAot） | S(5) | — | ✅ v4.38.0 — SkillValidationException + ValidationFinding 移至 shared.api 解環；compile 通過；shared↔skill cycle violation 消失 |
| S148d | Modulith allowed-targets 補 — `score → security` 跨 module 引用 SecurityFinding 未在 allowed targets 列表（S148c 解環後浮現） | XS(2) | — | ✅ v4.39.0 — security.scan 加 @NamedInterface("scan") + score allowedDependencies 加 "security :: scan"；processTestAot Modulith 驗證階段通過 |
| S148e | processTestAot duplicate cacheManager bean — TestDataControllerTest$CacheStubConfig vs WebMvcSliceTestBase$AotStubBeans 重複定義（S148c+d 解後浮現） | XS(2) | — | ✅ v4.40.0 — 移除 TestDataControllerTest 本地 CacheStubConfig；processTestAot BUILD SUCCESSFUL 預設流程恢復 |
| S149 | Cloud Run 結構化日誌改善研究（JSON format + log levels + trace ID） | META(research) | — | ⛔ cancelled 2026-05-13 — MVP feature-first，日誌可觀測性 polish 非當前 critical path；spec file 移至 `specs/archive/` |
| S150 | Collection Detail Page（/collections/:id — 技能清單 + 逐一預覽）| S(7) | S096f2 ✅ | ✅ shipped — CollectionDetailPage.tsx + useCollection hook + 6/6 tests（roadmap status 漏更新，2026-05-12 補正）|
| S151 | Quality Score 訊息一致性修正（hero card "評分計算中" vs. 品質 tab "此版本尚未評分"） | XS(2) | S135b ✅ | ✅ v4.37.0 — QualityTabV2「此版本尚未評分」改「評分計算中，請稍後重新整理」對齊 hero/badge 風格；12/12 PASS |
| S152 | SPA fallback for unknown routes（未知 URL → React NotFoundPage，移除 allowlist drift）| S(6) | — | ✅ v4.26.0 — 8/8 PASS（catchall pattern 取代 14 條 allowlist + /api/ early-return） |
| S153 | Skill detail 404 UX — 統一 400/403/404 顯「找不到此技能」（移除誤導 retry 提示）| XS(3) | — | ✅ v4.24.0 — 9/9 vitest PASS（isUnviewable 擴展涵蓋 400/403/404） |
| S154 | Author display identity (backend) — platform user_id 解耦 OAuth sub；`users` 表 + skills.author/owner_id/acl_entries 全切 user_id；CurrentUserProvider refactor；Command forgery fix；Query LEFT JOIN | M(12) | — | ✅ v4.47.0 — 6 tasks T01-T06 ship；DelegatingPermissionEvaluator 對齊 platform user_id；TestUserSeed fixture pattern；719/0/7（含 T06 re-enable 8 RBAC tests）|
| S154b | Author display identity (frontend) — getDisplayName helper + 10 component sweep（Phase 0 加 PublishPage author 改 read-only） + useMe CurrentUser interface 對齊 S154 11-key response + ShareSkillModal 4-polish | S(9) → S(11) | S154 ✅ | ✅ v4.50.0 — 5 tasks T01-T05 ship；frontend 350/350 + backend 725/725；10/10 AC PASS；defensive empty fallback in AuthArea + SkillGrantService trust-or-resolve（`startsWith("u_")`）+ PublishPage author read-only |
| S155 | Deployment audit polish — 7 個 LAB 小 UX 問題（footer link / auth-debug / publish-failed / 文案 / 偏好 modal / CLI dropdown 死 UI）| S(7) | — | ✅ v4.27.0 + v4.28.0 + v4.29.0 + v4.30.0 + v4.31.0 — 6/6 in-scope items shipped；#6 拆出 S155b（sort tab needs-reverify-in-LAB）|
| S155b | Sort tab active highlight LAB reverify | XS(2) | — | ⛔ cancelled 2026-05-09 — 程式碼掃描確認無 bug：HomePage.tsx:217 `isOn = sortMode === mode` derived state，無 secondary state 可漂移；click handler line 222 `setSortMode + setPage(0)` 同 batch；auditor 觀察解釋為 stale cached bundle pre-commit `6211734` |
| S156 | List clickability + Analytics hero card 修正 | S(5) | — | ✅ v4.32.0 — #1 historical (S100e) + #3 hero card removed；#2 拆 S156b |
| S156b | RequestDetailPage 新 page（描述 + 7 actions + 留言 simple list）+ hook + route — `/requests/:id` | M(8) | S156 ✅ | ⛔ superseded 2026-05-12 — 取代為 S156c（voting-board pivot：砍 claim/fulfill 機制，簡化為「post + vote + comment」） |
| S156c | Request 簡化為投票需求板 + Detail Page + Comments — 拆 S096g2 claim/release/fulfill machinery + 新增 comment 機制 + RequestDetailPage | M(12) | S156 ✅, S096g2 ✅ (拆) | ✅ v4.55.0 — 12/12 ACs PASS（含 §7.10 QA touchup：RetiredEndpointsTest + AC-3 集中 anchor）；verify-all.sh exit=0；backend 3m19s 綠 + frontend 410/410 + Modulith verify pass |
| S157 | Semantic search not functional in LAB — Gemini config + embedding backfill + vector_store wiring | S(6) → M(12) | — | ✅ v4.53.0 — backend impl (544969b) + LAB AC-1~4/8 全 PASS + `SemanticSearchRealFixtureIT` 真實 Gemini fixture 4/4 ACs PASS (cross-semantic ranking regression) + V07 e2e stabilization (4 個既有 flakiness 一次根治；verify-all 8/8 PASS) |
| S158 | API response privacy hardening — list 移除 aclEntries / ownerId | S(5) | — | ✅ v4.33.0 — list endpoint 隱藏 aclEntries+ownerId（@JsonView）；detail + 權限 contract 後續由 S169 接手 |
| S158b | Detail viewer permissions — split spec superseded | M(8) | S158 ✅ | ⛔ superseded 2026-05-13 — 整合進 S169；舊 spec 已移 archive，不作實作依據 |
| S159 | Skill query API hardening — META 拆 S159a/b/c/d | META | — | ✅ done — S159a (v4.43.0) + S159d (v4.44.0) + S159b (v4.54.0) ship；S159c ⛔ cancelled 2026-05-13（premise 錯誤）|
| S159a | Unknown query param 拒收（SkillQuery / categories 端點 fail-fast 400） | XS(3) | — | ✅ v4.43.0 — interceptor + handler；11+1+2 unit tests PASS |
| S159b | Category storage normalize — V20 lowercase + V21 dual-column display | M(15) | v4.54.0 | ✅ shipped 2026-05-12 — Round 2 ship 含 V07 hermetic gate bug fix（dual-column 保留 CamelCase）|
| S159c | `?tag=` filter 實作 — controller param + repo `findByTag()` + frontend filter chip | S(5) | — | ⛔ cancelled 2026-05-13 — premise 錯誤（skill 無 tags 欄位、前端無 tag chip UI、META S159 §2.2 origin observation 也誤判）；spec file 移至 `specs/archive/` |
| S159d | Pageable 非法值拒收 — `page < 0` / `size <= 0` / `size > 100` → 400 | XS(2) | — | ✅ v4.44.0 — `PageableValidationInterceptor` + handler；13+1+11+2 unit tests PASS |
| S160 | Security headers + CSRF — CSP / HSTS / Referrer-Policy / Permissions-Policy + CSRF re-enable | M(8) → 五段 ship | — | ✅ **shipped 2026-05-12 — 全部 9 ACs PASS**（headers + CSRF infra + frontend apiFetch + CSP report endpoint + AC-1 integration test）；原 5-spec 第三個 fully shipped |
| S160b | CSRF infrastructure feature-flag — backend SecurityConfig branch + Bearer JWT exempt（default OFF）| XS(2) | S160 ✅ | ✅ shipped 2026-05-12 — CsrfFlagTest 驗 Bearer exempt；92/92 shared.security PASS |
| S160b' | Frontend apiFetch X-XSRF-TOKEN auto-inject | XS(2) | S160b ✅ | ✅ shipped 2026-05-12 — withCsrfHeader helper 13/13 PASS；mutation methods 自動 round-trip；387/387 全 vitest 無 regression |
| S160b'' | CSP report endpoint POST /api/v1/csp-report | XS(2) | S160b' ✅ | ✅ shipped 2026-05-12 — 接 3 種 Content-Type；CSP_REPORT_ONLY constant 加 report-uri directive；4/4 tests PASS |
| S160b''' | AC-1 CSRF chain rejection integration test（MockMvc + csrf() roundtrip） | XS(1) | S160b'' ✅ | ✅ shipped 2026-05-12 — CsrfChainTest 2/2 PASS via @WebMvcTest(CspReportController) + @TestPropertySource csrf.enabled=true |
| S161 | User input sanitization — Review / Flag / Request 文字欄位 XSS strip + backfill | S(6) → 五段 ship | — | ✅ **shipped 2026-05-12 — 全部 8 ACs PASS**（plain-text 7 欄位 + markdown allowlist + V19 backfill；原 5-spec 第二個 fully shipped） |
| S161b | Apply PlainTextDeserializer 至 flag + collection DTOs | XS(2) | S161 ✅ | ✅ shipped 2026-05-12 0af2883 |
| S161b' | Request DTO — title plain-text 套用 | XS(1) | S161 ✅ | ✅ shipped 2026-05-12 47a4506 |
| S161b'' | Request.description markdown safe subset (OWASP HtmlPolicyBuilder allowlist) — javascript: URL 擋 | S(3) | S161b' ✅ | ✅ shipped 2026-05-12 — MarkdownSafeDeserializer + 11/11 cases PASS |
| S161c | V19 Flyway migration backfill 既存 stored XSS payload | XS(2) | S161 ✅ | ✅ shipped 2026-05-12 46eee1e |
| S162 | API response consistency — 統一 error shape (415/500) | S(5) | — | ✅ v4.34.0+v4.35.0 — AC-3 415 + AC-5 500 fallback ship；AC-6 framework default；AC-1/2/8b 拆 S162b/c |
| S162b | API consistency — 401/403 走平台 ErrorResponse（SecurityConfig.exceptionHandling.authenticationEntryPoint + accessDeniedHandler） | S(5) | — | ⏸ deferred 2026-05-13 — 暫緩待研究；S169 先不消費 final error shape |
| S162c | API consistency — ownership 409→403 split spec superseded | S(6) | — | ⛔ superseded 2026-05-13 — 整合進 S169；舊 spec 已移 archive，不作實作依據 |
| S163 | Skill owner management — PUT update + visibility toggle（registry 不需 suspend；私人 = revoke public:* ACL）| S(5) → 三段 ship | S144 ✅ | ✅ **shipped 2026-05-12 — 全部 8 ACs PASS**（backend 136564d + EditSkillModal fbce208 + visibility toggle 本 tick）|
| S163b | Skill owner frontend — EditSkillModal（AC-7）+ PageHeader 編輯 button | XS(3) | S163 ✅ | ✅ shipped 2026-05-12 fbce208 |
| S163b' | Skill visibility toggle UX — PageHeader [轉為私人]/[公開分享] button | XS(2) | S163b ✅ | ✅ shipped 2026-05-12 — VisibilityToggleButton 自包 grants query；27/27 vitest PASS；374/374 全 suite 無 regression |
| S164 | Collection owner management — PUT update + DELETE（OPTIONS 確認完全無 mutation methods）| S(5) → 兩段 ship | S150 ✅ | ✅ **shipped 2026-05-12 — 全部 8 ACs PASS**（backend 8fbee3d + frontend modal+action bar 本 tick）；原 5-spec 第四個 fully shipped |
| S164b | Collection owner frontend — EditCollectionModal + CollectionDetailPage action bar | XS(3) | S164 ✅ + S150 ✅ | ✅ shipped 2026-05-12 — 14/14 vitest PASS；395/395 全 suite 無 regression |
| S165 | Jackson `@JsonView` prod hotfix — `JsonMapperBuilderCustomizer` 顯式 enable `DEFAULT_VIEW_INCLUSION` | XS(2) | S158 ✅ + S166a ✅ | ✅ v4.41.0 — bean + diagnostic test + dev-standards rule |
| S166 | META Spring AOT bean registration sweep — verify-all.sh 全綠（S148e 後 processTestAot 解封首次跑揭露 ~30 fail） | META | S148e ✅ | ✅ v4.41.0 — sub-spec a 已 ship；b/c 改判定無真實 cluster（V01 全綠後消失）|
| S166a | 拆 cache 基礎設施（`@EnableCaching` + S114b ACL cache + Caffeine dep） — 一次解 cluster A ~10 個 AOT context-load fail | S(5) | — | ✅ v4.41.0 — cache infra 全拆；MVP 不需，未來 reintroduce 走 S2XX-cache |
| S166b | WebMvcSlice cluster B per-test stub bean 補完（~15 個 AOT 缺 bean） | M(8) | S166a ✅ | ⛔ 取消 — S166a + S165 ship 後 V01 全綠，cluster B 不再存在（之前的失敗多由 cache infra 連鎖造成） |
| S166c | `@SpringBootTest` 全 context cluster C 真實 fail 排查（S016 / S120 e2e / RiskAssessment） | S(5) | S166a ✅ | ⛔ 取消 — V01 全綠後 cluster C 失敗也消失（S120 dual-source race 由 SkillsHubAuthE2ETest 改走 `/grants` 修） |
| S166d | AOT-doc cleanup — README/CONTRIBUTING/qa-strategy/architecture/ADR-007/playwright-expert/`e2e/playwright.config.ts` 移 `-x processAot` 殘留 | XS(1) | S166a ✅ | ✅ v4.41.0 — micro-spec ship 同 v4.41.0 |
| S167 | 移除 deprecated `/api/v1/skills/{id}/acl` HTTP layer — controller + test + frontend doc + S016/E2E test 對齊 `/grants` shape | XS(2) | S114a ✅ + v4.41.0 deprecation log | ✅ v4.42.0 — HTTP 層拿掉；dead code 留 S167b |
| S167b | dead-code 清理 — `SkillCommandService.grantAcl/revokeAcl` + `Skill.grantAcl/revokeAcl` + `SkillAclGrantedEvent/Revoked` + `SkillAclQueryService` + `AuditEventListener` 對應 handlers + 4 個 unit test | S(5) | S167 ✅ | ✅ v4.45.0 — 8 整檔 + 3 production + 3 test 修改；36/36 PASS；S154 backend sequencing 鋪路 |
| S168 | GraalVM native image — Boolean wrapper field workaround for primitive boolean readback（per JobRunr PR #1501 production-shipped fix 同 stacktrace；繞 oracle/graal#5672 SubstrateVM MethodHandle adaptation bug；fix `User.contactEmailPublic` + `NotificationPreference` 4 boolean fields 同類 latent；含 architecture.md 修正 production deploy mode 描述 + dev-standards 上游追蹤 checkpoint） | S(9) → M(11) | S154 ✅ | ✅ v4.49.0 — Round 2 fix (Approach B→C pivot)：5 個 field primitive boolean → Boolean wrapper + 拔 dead converter；Round 1 v4.48.0 Approach B prod 失敗教訓詳 spec §2.8 + §7.5；verify-all PASS=7/FAIL=0；AC-4 manual deploy 待 Round 2 ship 後驗收 |
| S2XX-cache | 未來 re-introduce ACL eval cache（profiling-driven，明確 SLA 觸發） | — | production traffic 起來 | ⏸ deferred |
| S096d6 | /publish/validate SSE pipeline events | M(8-10) | S098a2 | ⏸ deferred |
| S096f3 | Collections risk filter polish | XS(3-4) | S096f2 ✅ | ✅ v4.12.0 — RiskFilterSidebar 泛化 + CollectionsPage filter |
| S098a2 | SSE 事件串流 + per-step 動畫 | M(8) | S098a ✅ | ⏸ deferred |
| S098b3-2 | Backend 結構化 findings payload | S(6) | S098b3 ✅ | ✅ v4.13.0 — ValidationFinding + SkillValidationException + PublishFailedPage 多 row |
| S098c2 | Backend /diff endpoint | M(8) | S098c ✅ | ✅ v4.14.0 — VersionDiffResponse + SkillDiffQueryService + DiffFieldsPanel |
| S098c3 | File content diff（file-list diff V1；行級 diff defer） | M(6) | S098c2 ✅ | ✅ v4.16.0 — GET /file-list-diff + FileListDiffPanel（+/-/~ counts + path+size）|
| S099c | Cross-marketplace risk validation | S(7) | S099 | ✅ shipped v4.6.0 — tools/cross-validate.py 41 skills dry-run PASS |
| S099d | LLM description quality audit | M(12) | S099 | ✅ shipped v4.7.0 — tools/quality-audit.py 5-dim rubric claude-haiku |
| S099e1 | Prompt-injection pattern detector (LLM01) | M(8) | S099 | ✅ shipped v4.2.0 — 8 HIGH + 6 MEDIUM patterns, 17 tests |
| S099e2 | Resource DoS scanner (LLM04) | S(5) | S099 | ✅ shipped v4.5.0 — 3 HIGH + 3 MEDIUM patterns (fork bomb, dev/zero, infinite loop), 11 tests |
| S099e3 | Dependency vulnerability scanner (LLM05) | S(7) | S099 | ✅ shipped v4.3.0 — OSV.dev querybatch, requirements.txt+package.json, 13 tests |
| S099e4 | Hardcoded creds detector enhancement (LLM06) | S(4) | S099 | ✅ shipped v4.4.0 — 6 new SecretScanner patterns, 6 tests |
| S114b | ACL production scale — Slice + Caffeine | M(10) | S114a | ✅ v4.11.0 — Caffeine cache `skill-acl`；programmatic CacheManager；@CacheEvict on grant/revoke |
| S114c | ACL infra — connection pool 調校（read replica defer） | S(4) | S114b | ✅ v4.15.0 — initialization-fail-timeout=60000；read replica + PgBouncer defer |
| S129 | Server compression | XS(1) | — | ✅ v4.9.0 — application.yaml server.compression gzip |
| S131 | Error code naming convention alignment | XS(2-3) | — | ✅ v4.10.0 — GlobalExceptionHandler 13 碼 SCREAMING_SNAKE_CASE 對齊 |

---

## ⛔ Cancelled / Superseded

| SpecID | 標題 | 點數 | 狀態 |
|--------|------|------|------|
| S095 | Risk tier 4-level split | S(9) | ⛔ superseded by S096c |
| S136 | Skill Evaluation Scenarios — task-based eval | TBD | ⛔ cancelled 2026-05-07 — research absorbed into v2 prototype + S142；no Evals tab in current product scope |
| S096e2 | Onboarding wizard | S(7) | ⛔ 取消 2026-05-05（MVP 不需要）|
| S101 | META Skill Quality / Impact / Security Score System | META | ⛔ superseded 2026-05-05 — 由 Tessl 8-dim 研究 (S135 META) 取代 Quality 部分；Impact 部分移至 S136 Backlog |
| S101a | Quality Score backend + LLM judge | M(10) | ⛔ superseded by S135（Tessl 8-dim 對齊版） |
| S101b | Impact Score proxy metrics | M(8) | ⛔ superseded — 改走 S136 task-based eval（待討論）|
| S101c | Security Status simplified | S(6) | ⛔ superseded — 既有 risk_level + S099e3 已足夠，不需獨立 spec |
| S101d | Frontend SkillCard + Scores tab | S(8) | ⛔ superseded — 併入未來 UI rework spec（hero 三條進度條 + 4 tabs） |
| S101e | Quality Score weekly re-evaluation cron | XS(3) | ⛔ superseded by S135 子 spec（待 scope 確認） |
| S101f | Score audit log + admin recalculate UI | XS(3) | ⛔ superseded by S135 子 spec（待 scope 確認） |

---

## ✅ Shipped

### MVP

| SpecID | 標題 | 點數 | 版本 |
|--------|------|------|------|
| S000 | Project Init | S(10) | v0.1.0 |
| S001 | 技能瀏覽基本 UI | M(12) | — |
| S002 | 技能搜尋基本 API | S(11) | — |
| S003 | 技能發佈 API | M(12) | — |
| S004 | 技能發佈 UI | S(10) | — |
| S005 | 自動風險評估 | M(12) | — |
| S006 | 一鍵安裝 Web 下載 | S(9) | — |
| S007 | 語意搜尋 | M(14) | — |
| S008 | 使用數據分析 | S(11) | — |
| S009 | 設定檔最佳化 | XS(7) | — |
| S010 | 安全掃描升級 | M(12) | — |
| S011 | 開發環境 OAuth Mock | XS(8) | — |
| S012 | OAuth 開關 + LAB 模式 | XS(8) | — |
| S013 | GCP Cloud Run 部署 | S(11) | v1.0.0 |

### Phase 1 — PostgreSQL Migration

| SpecID | 標題 | 點數 | 版本 |
|--------|------|------|------|
| S014 | PostgreSQL 資料層遷移（含 S015 absorbed）| L(20) | v1.1.0 |

### Phase 2.5 — Project Infra

| SpecID | 標題 | 點數 | 版本 |
|--------|------|------|------|
| S019 | JaCoCo coverage gate + 80% threshold | XS(5) | v1.1.1 |
| S020 | Verification command registry + verify-all.sh | S(10) | v1.1.1 |
| S021 | Phase 2 doc-sync — PRD + architecture.md | S(8) | v1.1.1 |
| S022 | Frontend Verification Baseline | S(8) | v1.1.1 |

### Phase 2 — Row-Level ACL + Aggregate

| SpecID | 標題 | 點數 | 版本 |
|--------|------|------|------|
| S016 | Row-Level ACL 基礎建設（JSONB + GIN）| M(13) | v1.2.0 |
| S017 | ACL-Aware 語意搜尋 | S-M(11) | v1.3.0 |
| S018 | Skill Aggregate 充血演化 + SKILL.md 對齊 | M(13) | v1.4.0 |

### Phase 3 — Modulith Outbox

| SpecID | 標題 | 點數 | 版本 |
|--------|------|------|------|
| S023 | Spring Modulith Outbox Foundation | M(12) | v1.5.0 |
| S024 | Skill State-Based Aggregate Migration | M(13) | v2.0.0 |

### Phase 4 — Test Pyramid + Domain Polish

| SpecID | 標題 | 點數 | 版本 |
|--------|------|------|------|
| S144 | Skill delete | S(9) | v4.51.0 |
| S145 | 訂閱管理頁面 | S(8) | v4.52.0 |
| S169 | CQRS permission contract — role grants + ACL projections + viewer actions + 403 semantics | L(15) | v4.57.0 |
| S171 | Spring AI M6 model abstraction cleanup | M(14) | v4.58.0 |
| S025a | Mock Lift + Scenario Migration | M(13) | v2.1.0 |
| S025b | Slice 重組 + Workaround 移除 | M(12-13) | v2.2.0 |
| S026 | Public-Read Default ACL | XS(5) | v2.3.0 |
| S027 | Dev Mode Admin Bypass | XS(5) | v2.4.0 |
| S028 | Frontend SUSPENDED Status Rendering | XS(5) | v2.5.0 |
| S029 | Block Suspended Skill Download | XS(5) | v2.6.0 |
| S030 | Conflict-Class Error Mapping | XS(5) | v2.7.0 |
| S031 | Public PUBLISHED-Only Visibility | XS(5) | v2.8.0 |
| S032 | Version Name Consistency | XS(5) | v2.9.0 |
| S033 | Vector Store Status Sync | XS(5) | v2.10.0 |
| S034 | SearchProjection Owner from Event/Aggregate | XS(5) | v2.11.0 |
| S035 | Frontend Suspended Detail Page UX | XS(5) | v2.12.0 |
| S036 | Frontend MEDIUM Risk Message | XS(5) | v2.13.0 |
| S037 | Upload Size 413 + Frontend Size Pre-check | XS(5) | v2.14.0 |
| S038 | ACL List Recognizes `*:read` | XS(5) | v2.15.0 |
| S039 | Frontend Typed ApiError + 404 vs Server Error | XS(5) | v2.16.0 |
| S040 | Frontend Mutation Error i18n + Multipart | XS(5) | v2.17.0 |
| S041 | Skill Aggregate Input Validation | XS(5) | v2.18.0 |
| S042 | Aggregate description / category Validation | XS(5) | v2.19.0 |
| S043 | Keyword Search Also Matches Category | XS(5) | v2.20.0 |
| S044 | Keyword Trim Whitespace | XS(5) | v2.21.0 |
| S045 | Strip Error Stack Trace + 405 Handler | XS(5) | v2.22.0 |
| S046 | Semantic Search Fallback to Keyword | XS(5) | v2.23.0 |
| S047 | Installation Guide Only for PUBLISHED | XS(5) | v2.24.0 |
| S048 | FileDropZone Reject Non-`.zip` | XS(5) | v2.25.0 |
| S049 | ZipException → 400 VALIDATION_ERROR | XS(5) | v2.26.0 |
| S050 | SearchBar Placeholder Include Category | XS(5) | v2.27.0 |
| S051 | DuplicateKeyException → 409 DUPLICATE_RESOURCE | XS(5) | v2.28.0 |
| S052 | HttpMessageNotReadableException → 400 | XS(5) | v2.29.0 |
| S053 | Flexible Upload Formats + Canonical Zip Structure | S(7) | v2.30.0 |
| S054 | Aggregate Null-Param 400 + Placeholder Polish | XS(5) | v2.31.0 |
| S055 | ACL Tuple Input Validation | XS(5) | v2.32.0 |
| S056 | Version Semver Validation | XS(5) | v2.33.0 |
| S057 | DataIntegrityViolationException Catch-All | XS(5) | v2.34.0 |
| S058 | Flag Input Validation | XS(5) | v2.35.0 |
| S059 | Semantic Search PUBLISHED-Only Visibility | XS(5) | v2.36.0 |
| S060 | SkillCard Status Badge Defensive | XS(5) | v2.37.0 |
| S061 | Download Filename Includes Skill Name | XS(5) | v2.38.0 |
| S062 | SkillVersion JSON Hide Internals | XS(5) | v2.39.0 |
| S063 | Skill Aggregate isNew JsonIgnore | XS(5) | v2.40.0 |
| S064 | QueryCache Logger Skip 4xx ApiError | XS(5) | v2.42.0 |
| S065 | ApiError HMR-Safe + Query networkMode | XS(5) | v2.43.0 |
| S066 | METHOD_NOT_ALLOWED i18n Coverage | XS(5) | v2.44.0 |
| S067 | Version Input HTML5 Pattern Pre-Validation | XS(5) | v2.45.0 |
| S068 | PublishPage Form maxLength Constraint | XS(5) | v2.46.0 |
| S069 | AuditEventListener Null-Defense for ACL | XS(5) | v2.47.0 |
| S070 | Flyway V7 Cleanup Pre-S033 Vector Orphans | XS(5) | v2.48.0 |
| S071 | App Routing /skills Alias + NotFound Fallback | XS(3) | v2.49.0 |
| S072 | Flag Type Allowlist + Description Length Cap | XS(3) | v2.50.0 |
| S073 | allowed-tools YAML list interop | XS(3) | v2.51.0 |
| S074 | Skill Files Browser API | S(5) | v2.52.0 |
| S075 | FlagReadModel.isNew() JsonIgnore | XS(3) | v2.53.0 |
| S076 | Download Counter Atomic Increment | S(5) | v2.54.0 |
| S077 | Skill.downloadCount @ReadOnlyProperty | XS(3) | v2.55.0 |
| S078 | Skill.riskLevel @ReadOnlyProperty | XS(2) | v2.56.0 |
| S079 | SkillSuspendedException message polish | XS(1) | v2.56.1 |
| S080 | Missing param error shape 統一 | XS(2) | v2.57.0 |
| S081 | Design Token Migration | S(5) | v2.58.0 |
| S082 | SkillDetailPage Files Tab UI | S(5) | v2.59.0 |
| S083 | BorderBeam light theme tuning | XS(1) | v2.59.1 |
| S084 | UI Rework META | META | — |
| S085 | HomePage rework + reusable components | S(8) | v2.63.0 |
| S086 | PublishPage rework | XS(5) | v2.64.0 |
| S087 | SkillDetailPage rework | S(7) | v2.65.0 |
| S088 | AnalyticsPage rework | XS(5) | v2.66.0 |
| S089 | BorderBeam hand-roll BeamFrame (drop dep) | XS(3) | v2.62.0 |
| S090 | Semantic search `?limit=` configurable | XS(2) | v2.60.0 |
| S091 | LlmJudge prompt calibration | XS(3) | v2.61.0 |
| S092 | FE i18n VALIDATION_ERROR detail concat | XS(2) | v2.67.0 |
| S093 | Dev DB persistence (compose named volume) | XS(2) | v2.68.0 |
| S094 | UI Round 2 META | META | — |
| S094a | My Skills (Author Dashboard) | S(9) | v2.71.0 |
| S094b | Semantic Search Results /search | S(9-10) | v2.72.0 |
| S094c | Empty State Collection (4 tones) | XS(5) | v2.69.0 |
| S094d | Docs Walkthrough /docs/your-first-skill | XS(5) | v2.70.0 |
| S096 | UI v2 dark-theme META | META | — |
| S096a | ADR-003 + PRD update | XS(4) | v2.73.0 |
| S096b | DESIGN.md v2 + global theme migration | M(12) | v2.74.0 |
| S096c | Routing schema + Risk tier 4-level | M(12) | v2.75.0 |
| S096d1 | Inline-hex bulk migration to dark tokens | S(8) | v2.76.0 |
| S096d2 | SkillCard prototype polish + featured variant | S(7) | v2.77.0 |
| S096d3 | Per-skill stats endpoint + Sparkline | S(8) | v2.78.0 |
| S096d4a | /publish/review post-upload result page | XS(5) | v2.83.0 |
| S096d5a | Auto-poll /publish/review during scan | XS(3) | v2.84.0 |
| S096e1 | Landing page + stats endpoint | S(8) | v2.79.0 |
| S096f1 | Collections read-only stub | XS(5) | v2.81.0 |
| S096f2 | Collections full feature | M(13) | v3.8.0 |
| S096g1 | Request Board read-only stub | XS(5) | v2.80.0 |
| S096g2 | Request Board full feature | S(11) | v3.6.0 |
| S096h1 | Notifications stub + bell badge | XS(6) | v2.82.0 |
| S096h2 | Notifications full projection | M(12) | v3.7.0 |
| S097 | Swap BeamFrame to border-beam package | XS(4) | v2.85.0 |
| S098 | META v2 prototype completeness audit | META | — |
| S098a | Publish Step 2 /publish/validate page | XS(5) | v2.95.0 |
| S098a3 | PublishValidate upload-strip (frontend) | XS(2) | v3.0.0 |
| S098a3-2 | Backend bundle-info endpoint | XS(2) | v3.8.1 |
| S098b | Publish Failures /publish/failed page | XS(4) | v2.91.0 |
| S098b2 | PublishReviewPage HIGH-risk redirect | XS(2) | v2.92.0 |
| S098b3 | Validation breakdown UI shell | S(4) | v3.1.0 |
| S098c | Version Diff page (frontend-only) | S(6) | v2.96.0 |
| S098d | Homepage 3-column grid + sort chips | XS(3) | v2.90.0 |
| S098d2 | Homepage risk filter sidebar | S(5) | v2.99.0 |
| S098e | Skill Detail v2 polish | XS(5) | v2.93.0 |
| S098e2 | Reviews aggregate + ratings | S(11) | v3.5.0 |
| S098e3 | Flag 回報流程 + FlagsQueuePage | S(8) | v3.5.1 |
| S098f | Docs IA — Overview + Risk Tiers | XS(5) | v2.94.0 |
| S098f2 | Reference group 3 doc pages | S(6) | v2.97.0 |
| S098f3 | Publishing + API/Webhook docs groups | M(8) | v2.98.0 |
| S098g | i18n 繁中化 audit (pass 1 + 2) | S(7) | v2.88.0 |
| S098h | YourFirstSkillPage 配色對比修復 | XS(3) | v2.86.0 |
| S098h2 | EmptyState dark migration + i18n | XS(3) | v2.89.0 |
| S099a | OpenAPI 3.1 verification | XS(2) | v3.4.12 |
| S099b | PublishPage text input mode | XS(4) | v3.3.0 |
| S099b2 | yaml-frontmatter live validation | XS(3) | v3.3.3 |
| S099b3 | markdown preview pane | S(5) | v3.4.0 |
| S099e5 | Docs page Risk Scanner 涵蓋與限制 | S(3) | v3.3.1 |
| S100 | META Page Data Authenticity Audit | META | — |
| S100a | AnalyticsPage Top 10 link to skill detail | XS(2) | v3.2.6 |
| S100b | HomePage server-side sort | XS(3) | v3.3.5 |
| S100c | PublishPage author prefill from /me | XS(2) | v3.3.7 |
| S100d | ErrorState component + 2 demo migrations | XS(3) | v3.3.2 |
| S100e | AnalyticsPage Top 10 link defensive guard | XS(2) | v3.4.1 |
| S102 | Back-nav + footer link target fix-ups | XS(3) | v3.4.2 |
| S103 | Stub-page user-facing spec ID leak fix | XS(2) | v3.4.3 |
| S104 | Risk filter empty-state + pagination UX | XS(3) | v3.4.4 |
| S105 | EmptyState invite-tone steps decoupling | XS(3) | v3.4.5 |
| S106 | Sort `推薦` behavior alignment | XS(2) | v3.4.6 |
| S107 | Semantic search response projection completeness | S(5) | v3.4.7 |
| S108 | Vite dev proxy for SpringDoc + footer API link | XS(2) | v3.4.8 |
| S109 | Vite dev proxy for actuator endpoints | XS(1) | v3.4.9 |
| S110 | MySkillsPage zh-TW label compliance | XS(2) | v3.4.10 |
| S111 | RiskTiersPage zh-TW tier title compliance | XS(2) | v3.4.11 |
| S112 | Flag wiring full-stack | S(7) | v3.4.13 |
| S115 | JWT + ACL Safety Design | M(8-10) | v3.8.2 |
| S116 | Skill Visibility Toggle | S(8) | v3.8.3 |
| S117 | SkillVersion type sync fileCount | XS(1) | v3.10.3 |
| S118 | Collection DTO field naming alignment | XS(2) | v3.10.4 |
| S119 | Skill list rating projection completeness | XS(2) | v3.10.2 |
| S121 | SkillQueryService.search() ACL filter | S(4-5) | v3.8.4 |
| S122 | Read-side single-skill ACL gate | S(4-5) | v3.8.5 |
| S123 | Download endpoint ACL gate | XS(2) | v3.8.6 |
| S124 | getByAuthorAndName ACL gate | XS(2) | v3.10.5 |
| S125a | SkillSubscription backend infra | XS(4) | v3.9.0 |
| S125b | SkillSubscription endpoints + version listener | XS(3) | v3.10.0 |
| S125c | SkillSubscription frontend | XS(3) | v3.10.1 |
| S126 | Skill id format validation pre-PreAuthorize | XS(2-3) | v3.10.7 |
| S127 | NoResourceFoundException ErrorResponse | XS(1) | v3.10.6 |
| S128 | CORS configuration | XS(2-3) | v3.10.8 |
| S130 | Personal endpoints auth gate | XS(1) | v3.10.9 |
| S133 | Skill Markdown export (agent-friendly copy/open) | XS(8) | v3.11.0 |
| S134 | Real OAuth IdP local dev trial (Google OAuth) | S(9) | v3.12.0 |
| S138 | SB4+SS7 test debt recovery — 18 failures → 0 | S(7) | v3.13.0 |
| S135a | Backend Quality Score (Validation + LLM judge + GET /scores) | M(14) | v3.14.0 |
| S114a | RBAC ACL — Owner + Viewer roles + async projection | M(12) | v4.0.0 |
| S135b | Frontend Quality Display（hero bar + 品質 tab）| S(9-11) | v4.1.0 |
| S132 | CI — Cloud Build pipeline（LAB manual submit）| XS(7) | v4.17.0 |
| S139 | Login UI + Lazy Auth Gate + LAB Google OAuth E2E | S(10) | v4.18.0 |
| S140 | E2E Critical Path Backfill (P1-P6 + Quality, 6 happy-path specs) | S(11) | v4.19.0 |
