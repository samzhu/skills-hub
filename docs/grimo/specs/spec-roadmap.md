# Skills Hub — Spec Roadmap

> 最後更新：2026-04-27（S014 ship 後重整：將執行順序前置；archived milestones 壓縮為 archive 指標；S015 absorbed、S013 隸屬 M11 等歷史脈絡保留在「Absorbed / Notes」）

---

## 🎯 Active Work — Sequenced (Next Up)

**Phase 2.5（Project Infra）已於 `v1.1.1`（2026-04-28）完成 ✅** — coverage gate（backend JaCoCo + frontend vitest）與 verification registry 全到位；接下來 Phase 2（Domain）：M+ 規模的 Row-Level ACL / Aggregate 充血落地。

### Recommended Execution Order

```
S019 ─▶ S020 ─▶ S021 ─▶ S022   Phase 2.5（Project Infra · M17 · 31 pts）
                          │
                          ▼
                     S016 ─▶ S017   Phase 2（Domain · M14/M15 · 24 pts）
                              │
                              └─▶ S018 (paused, 待 S016 ship 後重啟 design 精修)
```

| 順序 | Spec | Title | Points | Deps | Status |
|------|------|-------|--------|------|--------|
| 1 | S019 | JaCoCo coverage gate + 80% line threshold | XS(5) | — | ✅ |
| 2 | S020 | Verification command registry + `scripts/verify-all.sh` | S(10) | S019 ✅ | ✅ |
| 3 | S021 | Phase 2 doc-sync — PRD.md + architecture.md | S(8) | — (可與 1/2 平行) | ✅ |
| 4 | **S022** | **Frontend Verification Baseline**（vitest coverage tooling + 樣板 component test + ESLint root-cause + V06 enrollment）| **S(8)** | **S020**（registry + verify-all.sh 為 V06 enrollment 前提）| **✅ — `specs/archive/2026-04-28-S022-frontend-verification-baseline.md`** |
| 5 | S016 | Row-Level ACL 基礎建設（JSONB acl_entries + GIN）| M(13) | S014 ✅ | 🔲 Backlog |
| 6 | S017 | ACL-Aware 語意搜尋（PgVectorStore + ACL SQL composition）| S-M(11) | S016 | 🔲 Backlog |
| 7 | S018 | Skill Aggregate 充血演化 + SKILL.md 對齊 + Suspend/Reactivate Events | M(13) | S014 ✅ + S016（graceful degrade `hasRole('admin')` 占位）| ⏳ Design — `specs/2026-04-27-S018-skill-aggregate-rich-domain.md`（revised 2026-04-28）|

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
| **Phase 2** | **M14: Row-Level ACL 基礎建設** | **S016** | **M(13)** | **211** | **🔲 Backlog** |
| **Phase 2** | **M15: ACL-Aware 語意搜尋** | **S017** | **S-M(11)** | **222** | **🔲 Backlog** |
| **Phase 2** | **M16: Skill Aggregate 充血演化 + SKILL.md 對齊** | **S018** | **M(13)** | **235** | **⏳ Design** |

**MVP（v1.0.0）**：14 specs / 147 story points 已完成 🎉
**Phase 1（PostgreSQL 遷移 v1.1.0）**：1 spec / 20 story points 已完成（S015 absorbed）
**Phase 2.5（Project Infra）**：4 specs / 31 story points 已完成 `v1.1.1`（2026-04-28）— S019 JaCoCo gate / S020 verification registry + verify-all.sh / S021 PostgreSQL doc-sync / S022 Frontend verification baseline
**Phase 2（Row-Level ACL + Aggregate 充血）**：3 specs / 35 story points 規劃中

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

── Phase 2（Domain · Row-Level ACL + Aggregate · 規劃中）─
S014 ✅ ──▶ S016 (Row-Level ACL 基礎建設)
                 │
                 ├──▶ S017 (ACL-Aware 語意搜尋)
                 └──▶ S018 (Skill Aggregate 充血演化 + Suspend/Reactivate)
                          ↑
                          S014 ✅ (event store JDBC) + S016 (PermissionEvaluator)
```

---

## 📦 In-Flight Milestone Details

### Milestone 17: Project Infra — Coverage Gate / Verify Registry / Phase 2 Doc-Sync ✅ `v1.1.1` (2026-04-28)

4/4 specs complete. Details → `specs/archive/2026-04-27-S019-*` / `2026-04-27-S020-*` / `2026-04-27-S021-*` / `2026-04-28-S022-*`

### Milestone 14: Row-Level ACL 基礎建設 🔲 Backlog

**Goal**: 用 JSONB acl_entries + GIN(jsonb_path_ops) 為 skill 加 row-level 權限；@PreAuthorize 接 SkillCommandService

**Done when**: S016 done

**Backlog 對應**: B1（權限控制）+ B7（組織層級管理）+ B8（軟結構）

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S016 | Row-Level ACL 基礎建設 | M(13) | S014 ✅ | 🔲 |

### Milestone 15: ACL-Aware 語意搜尋 🔲 Backlog

**Goal**: 向量搜尋 SQL 同時做 GIN ACL filter + HNSW 排序，使用者只搜得到有 read 權限的 chunk

**Done when**: S017 done

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S017 | ACL-Aware 語意搜尋（PgVectorStore + ACL SQL composition） | S-M(11) | S016 | 🔲 |

### Milestone 16: Skill Aggregate 充血演化 + SKILL.md 對齊 ⏳ Design

**Goal**: Skill aggregate 從「部分重建」演化為完整充血模型；對齊 [agentskills.io](https://agentskills.io/) SKILL.md 標準（`allowed-tools` 升 first-class + `SkillValidator` 嚴格化）；新增 suspend/reactivate 業務動作；修 SkillProjection 的 status 不轉換 BUG + hardcoded sequence

**Done when**: S018 done；新增 15 個 AC 全綠；既有 read model status 從「永遠 DRAFT」改為正確轉換；`SkillVersionReadModel.allowedTools` 為 first-class column；`SkillValidator` 拒收違反 SKILL.md spec 的 frontmatter

**Driver**: PRD §Backlog B1（管理者下架不合規 skill）+ development-standards §27（aggregate 狀態轉換合法性）+ agentskills.io 互通性（client 讀 `allowed-tools` 授權）

**Spec**: `docs/grimo/specs/2026-04-27-S018-skill-aggregate-rich-domain.md`（initial 2026-04-27；revised 2026-04-28）

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S018 | Skill Aggregate 充血演化 + SKILL.md 對齊 + Suspend/Reactivate Events | M(13) | S014 ✅（event store JDBC）+ S016（PermissionEvaluator；graceful degrade 占位 `hasRole('admin')`） | ⏳ Design |

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

### Event Sourcing 進階功能

| 優先級 | 功能 | 說明 |
|--------|------|------|
| ES-B1 | Event Replay | 從 domain_events 重建 read model |
| ES-B2 | Aggregate Snapshot | 定期快照 aggregate 狀態，加速載入 |
| ES-B3 | Event Upcasting | 事件 schema 版本遷移 |
| ES-B4 | Saga / Process Manager | 跨 aggregate 的長流程協調 |

### Project Infrastructure（拆自其他 spec 的 follow-up）

| ID | 標題 | 觸發來源 | 範圍預估 | 主要動機 |
|----|------|---------|---------|---------|
| **S023** | **Spring Modulith outbox migration**（全模組 `@EventListener` → `@ApplicationModuleListener`、加 `spring-modulith-starter-jdbc` dep、啟用 `EVENT_PUBLICATION` outbox 表）| S018 revise（2026-04-28）— Modulith outbox 屬全模組 scope，不適合塞 S018 | M-L(12-15) | (a) Listener 失敗從「propagate 回 publisher → save rollback」改為「event 留 outbox `status=FAILED` 待 retry」（strong → eventual consistency；observable）；(b) 統一 `@ApplicationModuleListener` 對齊 development-standards §29；(c) 失敗 retry 機制免手動 ops。**取捨**：失敗語義改變需審慎評估每個既有 listener 對 strong consistency 的依賴 |
