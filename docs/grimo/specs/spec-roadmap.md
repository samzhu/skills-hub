# Skills Hub — Spec Roadmap

## Milestone 0: Project Init ✅ `v0.1.0` (2026-04-25)
1/1 specs complete. Details → `specs/archive/2026-04-24-S000-project-init.md`

## Milestone 1: 技能瀏覽與搜尋 ✅ `v0.2.0` (2026-04-25)
2/2 specs complete. Details → `specs/archive/2026-04-25-S00[12]-*`

## Milestone 2: 技能發佈流程 ✅ `v0.3.0` (2026-04-25)
2/2 specs complete. Details → `specs/archive/2026-04-25-S00[34]-*`

## Milestone 3: 自動風險評估 ✅ `v0.4.0` (2026-04-25)
1/1 specs complete. Details → `specs/archive/2026-04-25-S005-*`

## Milestone 4: 一鍵安裝 — Web 下載 ✅ `v0.5.0` (2026-04-25)
1/1 specs complete. Details → `specs/archive/2026-04-25-S006-*`

---

## Milestone 5: 語意搜尋 ✅ `v0.7.0` (2026-04-25)
Goal: 使用者用自然語言描述需求，AI 推薦最適合的技能
Done when: S007 done

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S007 | 語意搜尋（Spring AI + Gemini + Firestore Vector） | M(14) | S001 | ✅ |

### S007: 語意搜尋

**Description:** SearchProjection 監聽 SkillCreated / SkillVersionPublished events，透過 Spring AI 整合 Gemini embedding API 產生 embedding，存入 Firestore（原生 SDK）。搜尋時用 `findNearest()` 做向量搜尋。前端搜尋框支援「關鍵字」/「語意」切換。

**SBE Acceptance Criteria:**
```
Scenario: 自然語言搜尋
  Given 平台上有 "docker-compose-helper" 和 "k8s-deployment" 等 skills
  When 使用者輸入「我想把應用部署到容器環境」
  Then 回傳語意相關的 skills
  And 結果按語意相關度排序

Scenario: 任務導向推薦
  Given 使用者輸入「幫我寫單元測試」
  Then 推薦測試相關的 skills
  And 附上匹配理由

Scenario: 無相關結果
  Given 沒有匹配的 skill
  Then 顯示「未找到匹配的技能」
```

**Estimation:**
| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 3 | Firestore native SDK + MongoDB driver 混合 |
| Uncertainty | 2 | Embedding quality tuning |
| Dependencies | 2 | Spring AI, Gemini API, Firestore native SDK |
| Scope | 3 | ~8 files |
| Testing | 2 | Integration test with mock embeddings |
| Reversibility | 2 | Vector index migration |
| **Total** | **14** | **M** |

---

## Milestone 6: 使用數據分析 ✅ `v0.6.0` (2026-04-25)
1/1 specs complete. Details → `specs/archive/2026-04-25-S008-*`

---

## Milestone 7: 設定檔最佳化 ✅ `v0.8.0` (2026-04-25)
1/1 specs complete. Details → `specs/archive/2026-04-25-S009-config-optimization.md`

---

## Summary

| Milestone | Specs | Total Points | 累計 | Status |
|-----------|-------|-------------|------|--------|
| M0: Project Init | S000 | S(10) | 10 | ✅ |
| M1: 技能瀏覽與搜尋 | S001, S002 | M(12) + S(11) = 23 | 33 | ✅ |
| M2: 技能發佈流程 | S003, S004 | M(12) + S(10) = 22 | 55 | ✅ |
| M3: 自動風險評估 | S005 | M(12) | 67 | ✅ |
| M4: 一鍵安裝（Web 下載） | S006 | S(9) | 76 | ✅ |
| M5: 語意搜尋 | S007 | M(14) | 90 | ✅ |
| M6: 使用數據分析 | S008 | S(11) | 101 | ✅ |
| M7: 設定檔最佳化 | S009 | XS(7) | 108 | ✅ |

| M8: 安全掃描升級 | S010 | M(12) | 120 | ✅ |
| M9: 開發環境 OAuth Mock | S011 | XS(8) | 128 | ✅ |
| M10: OAuth 開關 + LAB 模式 | S012 | XS(8) | 136 | ✅ |
| M11: GCP Cloud Run 部署 | S013 | S(11) | 147 | ✅ |
| M12: PostgreSQL 資料層遷移 + PgVectorStore 接管 + Firestore 全清 | S014（含 S015 absorbed） | L(20) | 167 | ✅ `v1.1.0` |
| ~~M13: 自訂 PgVectorStore~~ | ~~S015~~ | — | — | 🚫 ABSORBED into S014（2026-04-27） |
| M14: Row-Level ACL 基礎建設 | S016 | M(13) | 180 | 🔲 Backlog |
| M15: ACL-Aware 語意搜尋 | S017 | S-M(11) | 191 | 🔲 Backlog |
| M16: Skill Aggregate 充血演化 | S018 | S(11) | 202 | ⏳ Design |

**MVP（v1.0.0）已完成：14 specs, 147 story points 🎉**
**Phase 2（PostgreSQL + Row-Level ACL + Aggregate 充血）規劃中：4 specs, 55 story points**（S015 absorbed into S014；T2 ship 後決策一次拆 Firestore，避免 SearchConfig 雙條件分支與 google-cloud-firestore dep 持續耦合 — 詳 ADR-001 §4.5）

### Dependency Graph

```
S000 ──▶ S001 ──▶ S002             ✅
              │
              ├──▶ S003 ──▶ S004   ✅
              │       │
              │       ├──▶ S005    ✅
              │       │
              │       └──▶ S006 ──▶ S008  ✅
              │
              └──▶ S007                ✅

S009 (獨立，無依賴)                    ✅

S005 ──▶ S010 (多引擎安全掃描)         ✅
S007 ──┘

S009 ──▶ S011 (dev OAuth mock)          ✅
              │
              └──▶ S012 (OAuth toggle + LAB)   ✅

S013 (GCP Cloud Run 部署腳本，獨立)     ✅

── Phase 2（依 ADR-001：Firestore → PostgreSQL）──
ADR-001 ──▶ S014 (PostgreSQL 資料層遷移 + PgVectorStore 接管 + Firestore 全清)
                └─▶ S016 (Row-Level ACL 基礎建設)
                      ├─▶ S017 (ACL-Aware 語意搜尋)
                      └─▶ S018 (Skill Aggregate 充血演化 + Suspend/Reactivate)
                          ↑
                          S014 (event store JDBC) + S016 (PermissionEvaluator)

# S015 已併入 S014（2026-04-27 T2 ship 後決策；ADR-001 §4.5）
```

## Milestone 8: 安全掃描升級 ✅ `v0.9.0` (2026-04-26)
1/1 specs complete. Details → `specs/archive/2026-04-25-S010-multi-engine-scanner.md`

---

## Milestone 9: 開發環境 OAuth Mock ✅ `v0.10.0` (2026-04-27)
1/1 specs complete. Details → `specs/archive/2026-04-25-S011-dev-oauth-mock.md`

---

## Milestone 10: OAuth 開關 + LAB 模式 ✅ `v0.11.0` (2026-04-27)
1/1 specs complete. Details → `specs/archive/2026-04-27-S012-oauth-toggle-lab-mode.md`

---

## Milestone 11: GCP Cloud Run 部署 ✅ `v1.0.0` (2026-04-27)
1/1 specs complete. Details → `specs/archive/2026-04-27-S013-gcp-deploy-scripts.md`

---

## Milestone 12: PostgreSQL 資料層遷移 + PgVectorStore 接管 + Firestore 全清 ✅ `v1.1.0` (2026-04-27)
1/1 specs complete (含 S015 absorbed). Details → `specs/archive/2026-04-27-S014-postgresql-migration.md` + `adr/ADR-001-postgresql-migration.md`

---

## Milestone 16: Skill Aggregate 充血演化 + Suspend/Reactivate ⏳ Design (2026-04-27)
Goal: Skill aggregate 從「部分重建」演化為完整充血模型；新增 suspend/reactivate 業務動作；修 SkillProjection 的 status 不轉換 BUG + hardcoded sequence
Done when: S018 done；新增 12 個 AC 全綠；既有 read model status 從「永遠 DRAFT」改為正確轉換
Driver: PRD §Backlog B1（管理者下架不合規 skill）+ development-standards §27（aggregate 狀態轉換合法性）
Spec: `docs/grimo/specs/2026-04-27-S018-skill-aggregate-rich-domain.md`

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S018 | Skill Aggregate 充血演化 + Suspend/Reactivate Events | S(11) | S014（event store JDBC）+ S016（PermissionEvaluator） | ⏳ Design |

---

## ~~Milestone 13: Spring AI PgVectorStore 接管向量寫入~~ 🚫 ABSORBED into S014（2026-04-27）

> 原 S015 scope（PgVectorStore 接管 + FirestoreVectorStore 刪除 + google-cloud-firestore dep 移除）併入 S014 T7。決策依據：T2 mega ship 後 Mongo deps 已乾淨，若分批保留 Firestore 至 S015，`SearchConfig` 雙條件分支 + `google-cloud-firestore` dep tree 將持續耦合一整輪 spec → 一次拆乾淨少一輪 PR review。詳 ADR-001 §4.5 + S014 spec §1 / §2.1 決策 #2 / #10。

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| ~~S015~~ | ~~Spring AI PgVectorStore 接管向量寫入~~ | — | — | 🚫 ABSORBED → S014 T7 |

---

## Milestone 14: Row-Level ACL 基礎建設 🔲 Backlog
Goal: 用 JSONB acl_entries + GIN(jsonb_path_ops) 為 skill 加 row-level 權限；@PreAuthorize 接 SkillCommandService
Done when: S016 done
Backlog 對應: B1（權限控制）+ B7（組織層級管理）+ B8（軟結構）

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S016 | Row-Level ACL 基礎建設 | M(13) | S015 | 🔲 |

---

## Milestone 15: ACL-Aware 語意搜尋 🔲 Backlog
Goal: 向量搜尋 SQL 同時做 GIN ACL filter + HNSW 排序，使用者只搜得到有 read 權限的 chunk
Done when: S017 done

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S017 | ACL-Aware 語意搜尋（PgVectorStore + ACL SQL composition） | S-M(11) | S016 | 🔲 |

---

### Backlog (安全掃描 — 企業級升級方向)

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

### Backlog (ES 進階功能)

| 優先級 | 功能 | 說明 |
|--------|------|------|
| ES-B1 | Event Replay | 從 domain_events 重建 read model |
| ES-B2 | Aggregate Snapshot | 定期快照 aggregate 狀態，加速載入 |
| ES-B3 | Event Upcasting | 事件 schema 版本遷移 |
| ES-B4 | Saga / Process Manager | 跨 aggregate 的長流程協調 |
