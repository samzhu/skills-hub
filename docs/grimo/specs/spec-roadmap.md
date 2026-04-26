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
| M9: 開發環境 OAuth Mock | S011 | XS(8) | 128 | 🔵 |

**Total: 12 specs, 128 story points — 11/12 specs shipped (120 points done)**

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

S009 ──▶ S011 (dev OAuth mock)          🔵
```

## Milestone 8: 安全掃描升級 ✅ `v0.9.0` (2026-04-26)
1/1 specs complete. Details → `specs/archive/2026-04-25-S010-multi-engine-scanner.md`

---

## Milestone 9: 開發環境 OAuth Mock `v0.10.0`
Goal: dev/CI 環境本地核發 JWT，把 Spring Security OAuth2 Resource Server 通路跑通；現有 API 仍維持匿名可達（Feature First, Security Later）
Done when: S011 done

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S011 | 開發環境 OAuth Mock 整合 | XS(8) | S009 | ⏳ Dev (T1, T2 ✅; QA blocked by S010 cascade) |

### S011: 開發環境 OAuth Mock 整合

**Description:** docker-compose 加入 navikt/mock-oauth2-server，`./gradlew bootRun` 自動帶起；提供三組假身分（admin / developer / viewer），各帶 `sub` / `roles` / `groups` / `company_id` / `dept_id` / `scope` 等 claim。Spring Security 顯式配置 SecurityFilterChain：`/api/v1/me` 與 `/api/v1/admin/**` 需 JWT，其他端點 permitAll。研究來源：`docs/deepwiki/mock-oauth2-server/`。

**SBE Acceptance Criteria:**
```
Scenario: bootRun 自動帶起 mock-oauth2-server
  Given 開發者執行 ./gradlew bootRun
  Then docker-compose 啟動 mongodb + mock-oauth2-server
  And /skills-hub-dev/.well-known/openid-configuration 可訪問

Scenario: 三組 client_id 取得不同身分 JWT
  Given mock 已 ready
  When client_credentials grant 帶 client_id=admin-client / developer-client / viewer-client
  Then 各自取得對應 roles / groups / dept_id 的 JWT

Scenario: /api/v1/me 回傳 token claims
  Given 已取得 JWT
  When GET /api/v1/me 帶 Bearer token
  Then 回傳 sub / roles / groups / companyId / deptId

Scenario: /api/v1/admin/echo 拒絕非 admin
  Given viewer-client 的 token
  Then GET /api/v1/admin/echo 回 403

Scenario: 既有 API 仍可匿名訪問
  Given 不帶 token
  When GET /api/v1/skills
  Then 回 200（與 S001 一致）
```

**Estimation:**
| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 1 | Spring Security OAuth2 RS 與 mock-oauth2-server 都成熟 |
| Uncertainty | 1 | deepwiki 研究 + Spring Security 7 API 已逐項驗證 |
| Dependencies | 1 | 取消 1 個 starter 註解 + 1 個 Docker image |
| Scope | 2 | 7 個生產檔 + 4 個測試檔 |
| Testing | 2 | Testcontainers (AC-1~3) + MockMvc jwt() (AC-4~7) |
| Reversibility | 1 | dev 基礎設施，可完全還原 |
| **Total** | **8** | **XS** |

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
