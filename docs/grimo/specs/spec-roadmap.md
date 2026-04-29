# Skills Hub — Spec Roadmap

> 最後更新：2026-04-29（**S023 ✅ Shipped v1.5.0 (M18)** — Spring Modulith Outbox Foundation 完成；Active 推進至 S024（Skill 充血聚合改寫，target v2.0.0）+ S025（Test Pyramid Realignment，由 S023 T07 known limitation 觸發）。**ADR-002 Phase 1 落地完成。**）

---

## 🎯 Active Work — Sequenced (Next Up)

**Phase 3 — 架構轉向（ADR-002 driven）**：詳 [`adr/ADR-002-skill-aggregate-state-based.md`](../adr/ADR-002-skill-aggregate-state-based.md)。S023 → S024 sequential ship；S023 為 outbox 基礎建設、S024 為 Skill 充血聚合改寫。

### Recommended Execution Order

```
S023 (Modulith Outbox Foundation) ─▶ S024 (Skill State-Based Aggregate)
     M(12)、ship as v1.5.0              M(13)、ship as v2.0.0（major bump per ADR-002 §5.1）
     ├─ spring-modulith-starter-jdbc    ├─ Skill = @Table + extends AbstractAggregateRoot
     ├─ V4 event_publication            ├─ SkillVersion 獨立 aggregate
     ├─ V5 shedlock                     ├─ AclEntry 維持 jsonb（per ADR-002 §2.2）
     ├─ Hybrid listener migration       ├─ AuditEventListener (domain_events 退 audit log)
     │   - 9 → @ApplicationModuleListener │   - SkillReadModel 等整組刪除
     │   - 2 保留 @EventListener         │   - SkillCommandService 縮為 3 行/method
     │     (FK target row 創建者)        ├─ V6 ALTER TABLE skills ADD version BIGINT
     ├─ ShedLock 7.7.0 + retry         └─ architecture.md / CLAUDE.md 同步改寫
     └─ event_publication metrics
```

| 順序 | Spec | Title | Points | Deps | Status |
|------|------|-------|--------|------|--------|
| 1 | S023 | Spring Modulith Outbox Foundation | M(12) | S018 ✅ + ADR-002 | ✅ — `v1.5.0` (M18) |
| 2 | **S024** | **Skill State-Based Aggregate Migration** | **M(13)** | S023 ✅ + S016 ✅ + S018 ✅ + ADR-002 | **🔲 Design** (target `v2.0.0` — major bump per ADR-002 §5.1) |
| 3 | **S025** | **Test Pyramid Realignment + Scenario migration** | **L(15-18)** | S023 ✅（known limitation 來源）| **🔲 Design** |

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
| Phase 3 | M19: Skill State-Based Aggregate Migration | S024 | M(13) | 260 | 🔲 Design (target `v2.0.0` — major bump per ADR-002 §5.1) |

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
