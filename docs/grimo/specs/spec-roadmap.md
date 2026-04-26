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
| M10: OAuth 開關 + LAB 模式 | S012 | XS(8) | 136 | 🔵 |
| M11: GCP Cloud Run 部署 | S013 | S(11) | 147 | 🔵 |

**Total: 14 specs, 147 story points — 12/14 specs shipped (128 points done)**

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
              └──▶ S012 (OAuth toggle + LAB)   🔵

S013 (GCP Cloud Run 部署腳本，獨立)     🔵
```

## Milestone 8: 安全掃描升級 ✅ `v0.9.0` (2026-04-26)
1/1 specs complete. Details → `specs/archive/2026-04-25-S010-multi-engine-scanner.md`

---

## Milestone 9: 開發環境 OAuth Mock ✅ `v0.10.0` (2026-04-27)
1/1 specs complete. Details → `specs/archive/2026-04-25-S011-dev-oauth-mock.md`

---

## Milestone 10: OAuth 開關 + LAB 模式 `v0.11.0`
Goal: 加 `skillshub.security.oauth.enabled` toggle，LAB 環境關掉 OAuth 直接用預設 lab user 測試功能；提供 `CurrentUserProvider` 為未來 audit 欄位準備
Done when: S012 done

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S012 | OAuth 開關 + LAB 模式 | XS(8) | S011 | 🔵 in-design |

### S012: OAuth 開關 + LAB 模式

**Description:** 加入 `skillshub.security.oauth.enabled`（預設 true）開關。LAB 環境設 `false` → SecurityFilterChain 全 permitAll、JwtDecoder bean 不建立、`LabSecurityFilter` 注入預設 `lab-user` + `ROLE_admin` Authentication；`/api/v1/me`、`/api/v1/admin/echo`、既有 S001~S010 endpoints 全部可訪問。新增 `CurrentUserProvider` 抽象，未來 audit 欄位（createdBy 等）統一從這個介面取 userId — OAuth 模式回 JWT subject、LAB 模式回固定 `lab-user`。

**SBE Acceptance Criteria:**
```
Scenario: OAuth enabled (default) 行為與 S011 一致
  Given 沒設定 oauth.enabled
  When 跑 S011 既有 9 個測試
  Then 全部通過

Scenario: OAuth disabled，permitAll 生效
  Given oauth.enabled=false
  When 不帶 token 打 /api/v1/me
  Then 200 + sub=lab-user, roles=[admin]
  When 不帶 token 打 /api/v1/admin/echo?msg=hello
  Then 200 + echo=hello, by=lab-user

Scenario: CurrentUserProvider 統一介面
  Given 兩種模式
  When 呼叫 currentUserProvider.userId()
  Then OAuth 模式回 JWT subject；LAB 模式回 lab-user
```

**Estimation:**
| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 1 | Spring Security 標準 SPI |
| Uncertainty | 1 | API 在 S011 已驗證 |
| Dependencies | 1 | 無新外部依賴 |
| Scope | 2 | 3 modify + 6 add = 9 檔 |
| Testing | 2 | 兩種模式各測一遍 |
| Reversibility | 1 | 純 toggle 可還原 |
| **Total** | **8** | **XS** |

---

## Milestone 11: GCP Cloud Run 部署 `v1.0.0`
Goal: 一組 bash 腳本支援全新 GCP 專案上一鍵部署 Skills Hub 到 Cloud Run；開發者只需 export 三個 env var 即可
Done when: S013 done

| # | Spec | Points | Dependencies | Status |
|---|------|--------|--------------|--------|
| S013 | GCP Cloud Run 部署腳本 | S(11) | S009 (gcp profile) | 🔵 in-design |

### S013: GCP Cloud Run 部署腳本與打包流程

**Description:** 在 `scripts/gcp/` 提供 6 個 bash 腳本（01-bootstrap, 02-create-secrets, 03-build-push, 04-deploy, 99-teardown + .env.example）+ README。01 bootstrap 啟用 6 個 GCP API 並 provision Artifact Registry repo + Firestore Enterprise (MongoDB compat) + GCS bucket + Service Account + 7 個 min IAM roles。03 build-push 用 gradle bootBuildImage 產 OCI image + git short SHA + latest 雙 tag 推到 AR。04 deploy 用 gcloud run deploy 帶完整 env vars + Secret Manager 引用 + allow-unauthenticated。腳本全 idempotent，跑兩次不報錯。

**SBE Acceptance Criteria:**
```
Scenario: 三步啟動部署
  Given 全新 GCP 專案，export GCP_PROJECT_ID + GCP_REGION + SKILLSHUB_GENAI_API_KEY
  When 依序跑 01~04 腳本
  Then Cloud Run service URL 可訪問
  And /actuator/health 回 200

Scenario: 腳本 idempotent
  Given AC-3 已跑過一次
  When 再跑一次 01-bootstrap.sh
  Then exit 0，不重複建立資源

Scenario: Image 雙 tag
  When 03-build-push.sh
  Then AR 上同時有 :<git-short-sha> 與 :latest
```

**Estimation:**
| Dimension | Score | Reason |
|-----------|-------|--------|
| Technical risk | 1 | gcloud / docker / bash 成熟工具 |
| Uncertainty | 2 | 多 GCP API + 命令 minor drift |
| Dependencies | 2 | 6 個 GCP API 需 orchestrate |
| Scope | 2 | 7 add + 1 modify = 8 檔 |
| Testing | 2 | manual / dry-run；bash -n + shellcheck advisory |
| Reversibility | 2 | 99-teardown 拆除；但部署消耗 GCP quota |
| **Total** | **11** | **S** |

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
