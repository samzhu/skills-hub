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

| M8: 安全掃描升級 | S010 | M(12) | 120 | 🔵 |

**Total: 11 specs, 120 story points — 10/11 specs shipped (108 points done)**

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

S005 ──▶ S010 (多引擎安全掃描)         🔵
S007 ──┘
```

## Milestone 8: 安全掃描升級 `v0.9.0`
Goal: 多引擎安全掃描 Pipeline，各引擎可獨立開關
Done when: S010 done

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S010 | 多引擎安全掃描 Pipeline | M(12) | S005, S007 | 🔵 in-design |

### S010: 多引擎安全掃描 Pipeline

**Description:** 將 S005 的 regex-only 掃描器升級為 5 引擎 Pipeline（PatternScanner + SecretScanner + MetadataValidator + LlmJudge + MetaAnalyzer），各引擎可獨立開關。兩階段架構：Phase 1 靜態並行 → enrichment → Phase 2 LLM 語意分析。Spring AI Manual Config + Gemini。SARIF 2.1.0 輸出。

**SBE Acceptance Criteria:**
```
Scenario: 多引擎掃描
  Given skill zip 含 scripts/ + SKILL.md
  When SkillVersionPublishedEvent 觸發
  Then 5 引擎（依設定啟用）並行/序列掃描
  And 產出 findings (HIGH/MEDIUM/LOW) + notices
  And 產出 SkillRiskAssessedEvent + SARIF JSON

Scenario: 引擎獨立開關
  Given skillshub.scanner.engines.llm.enabled=false
  When 掃描執行
  Then LlmJudge 不呼叫，其他引擎正常
```

**Estimation:**
| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 2 | LLM-as-judge 新 pattern，API 已驗證 |
| Uncertainty | 1 | deepwiki 研究完成 |
| Dependencies | 2 | 5 個新外部依賴 |
| Scope | 3 | 5 引擎 + pipeline + config + SARIF |
| Testing | 2 | Mock LLM + pattern fixtures |
| Reversibility | 1 | 擴展 security module |
| **Total** | **12** | **M** |

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
