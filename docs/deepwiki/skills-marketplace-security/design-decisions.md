# 設計決策與 Skills Hub 借鑑分析

> 研究日期：2026-04-25
> 上下文：Skills Hub S005 風險評估引擎已實作，需規劃下一階段安全升級

---

## 設計決策表

| # | 決策 | 理由 | 被否決的替代方案 |
|---|------|------|----------------|
| D1 | 多引擎並行掃描優於單引擎 | YARA 有 ~78% FP rate，單一方法無法覆蓋所有威脅。Cisco mcp-scanner 用 5 引擎解決此問題 | 單一 LLM 掃描（太慢且有 API 依賴） |
| D2 | 先加 SKILL.md prompt injection 偵測 | ToxicSkills 發現 91% 惡意 skills 結合 prompt injection + malware，目前 S005 完全未覆蓋 SKILL.md 內容 | 優先加 binary scanning（影響面較小） |
| D3 | 採用 LLM 語意分析而非純 pattern | YARA 無法區分正常 MCP 指令和攻擊 pattern（語意相同，意圖不同） | 增加更多 regex rules（FP 問題無法解決） |
| D4 | Trust Score 優於 Pass/Fail | MCPSkills.io 14 信號和 MS AGT 0-1000 分數提供更細緻的信任度量，使用者可自行判斷 | 二元通過/不通過（對邊界案例不友善） |
| D5 | Meta-analyzer 整合多引擎結果 | Cisco skill-scanner 的 Meta-Analyzer 專門做 false positive filtering 和 cross-correlation，解決引擎間的矛盾 | 取所有引擎的 union（FP 太多） |
| D6 | SARIF 作為掃描結果標準格式 | 可直接整合 GitHub Advanced Security、IDE、CI/CD，避免自訂格式 | 自訂 JSON schema（需要每個整合端自行解析） |
| D7 | Event-driven 掃描架構維持 S005 模式 | 已驗證的架構（SkillVersionPublished → scan → SkillRiskAssessed），符合 Skills Hub 的 ES+CQRS 模式 | 同步 API 掃描（阻塞上傳流程） |
| D8 | 漸進式升級而非全面重寫 | S005 的事件流和 read model 更新機制可復用，只需擴展 RiskScanner 內部 | 重新設計整個 security module（破壞性太大） |

---

## 技術債分析

### Skills Hub S005 目前的技術債

| 技術債 | 嚴重度 | OWASP 對應 | 說明 |
|--------|--------|-----------|------|
| **只掃描 scripts/**  | HIGH | AST01, AST08 | SKILL.md 的 prompt injection、references/ 的 poisoned context 完全未掃描 |
| **無 prompt injection 偵測** | HIGH | AST01, ASI01 | 91% 惡意 skills 使用 prompt injection，目前零覆蓋 |
| **無 metadata 驗證** | MEDIUM | AST04 | 無法偵測 typosquatting、假冒作者、誤導性描述 |
| **無 secret scanning** | MEDIUM | AST01 | 10.9% skills 含硬編碼 secrets（ToxicSkills 數據） |
| **無 trust scoring** | MEDIUM | AST09 | 缺乏作者信譽、版本歷史等信任指標 |
| **無 SBOM** | LOW | AST09 | 無法產出合規用的技能清單 |
| **無 code signing** | LOW | AST02 | 技能完整性無法驗證 |
| **Regex FP 無處理** | LOW | AST08 | 沒有 cross-correlation 或 FP filtering |

### 業界進行中的改善

| 平台 | 進行中改善 |
|------|-----------|
| VS Code Marketplace | Copycat detection、publisher-signing、security-sensitive behavior identification |
| Snyk | Runtime agent protection (Evo)、Agent Guard for real-time protection |
| Microsoft AGT | Quantum-safe signing (ML-DSA-65)、inter-agent trust protocol |
| OWASP | Universal Skill Format proposal 中包含 permission declarations 和 scan status provenance |

---

## Skills Hub 借鑑分析

### 直接可用（Directly Applicable）

| # | 借鑑項目 | 來源 | 對應 Spec | 實作方式 |
|---|---------|------|----------|---------|
| B1 | **多引擎掃描 Pipeline** | Cisco mcp-scanner 架構 | 新 Spec: S010 安全掃描升級 | 在 `RiskScanner` 內加入多個 `Analyzer` interface 實作，並行執行後由 `MetaAnalyzer` 整合 |
| B2 | **SKILL.md prompt injection 偵測** | Snyk ToxicSkills 八大分類 | S010 | 用 Spring AI + Gemini（複用 S007 基礎設施）分析 SKILL.md markdown body |
| B3 | **全檔案掃描（不只 scripts/）** | Cisco skill-scanner | S010 | 擴展 `PackageService.extractScripts()` 為 `extractAllFiles()`，掃描 SKILL.md + references/ + assets/ |
| B4 | **Secret scanning** | VS Code Marketplace、MCPSkills.io | S010 | 加入 API key、token 的 regex patterns（AWS、GCP、GitHub token patterns） |
| B5 | **社群回報 SLA** | VS Code Marketplace 1-day SLA | 營運流程 | 現有 SkillFlagged event 已支援，需加 SLA tracking |
| B6 | **SARIF 輸出格式** | Cisco skill-scanner | S010 | 掃描結果支援 SARIF 匯出，便於 CI/CD 整合 |

### 概念可用但需適配（Conceptually Useful）

| # | 借鑑項目 | 來源 | 需適配原因 | 建議 Spec |
|---|---------|------|-----------|----------|
| C1 | **Trust Score 系統** | MCPSkills.io 14 signals、MS AGT 0-1000 | Skills Hub 是 registry 不是 runtime，需定義自己的信號維度 | S011 信任評分 |
| C2 | **Publisher 驗證** | VS Code verified publisher | Skills Hub MVP 無 auth，需等 auth 系統建好 | Post-MVP |
| C3 | **YARA 引擎** | Cisco mcp-scanner | Skills Hub 是 Java 不是 Python，需找 Java YARA library 或改用等效 pattern engine | S010 用 Java 原生 regex + pattern files |
| C4 | **Behavioral Code Analysis** | Cisco mcp-scanner behavioral analyzer | 需 AST parser for shell scripts（Java 生態較少） | 中期考慮，可先用 LLM 替代 |
| C5 | **Agent Skill BOM (ASBOM)** | SkillFortify CycloneDX 1.6 | CycloneDX 有 Java library，但需定義 skill-specific component types | S012 ASBOM |
| C6 | **Auto-removal + Block list** | VS Code Marketplace | 需定義 removal policy 和 auto-uninstall 機制 | Post-MVP governance |

### 不適用（Not Applicable）

| # | 項目 | 來源 | 不適用原因 |
|---|------|------|-----------|
| N1 | Dynamic Sandbox (VM) | VS Code Marketplace | Skills Hub 不執行 skills，只做 registry。Runtime 隔離是 client 端責任 |
| N2 | Policy Engine (< 0.1ms) | MS AGT StatelessKernel | Skills Hub 是上傳時掃描，不是 runtime policy enforcement |
| N3 | Toxic Flow detection | Snyk agent-scan | 需要連接 MCP servers 分析 tool 之間的資料流，不適用於靜態 registry |
| N4 | Trust Decay | MS AGT | 適用於 runtime trust，不適用於 registry 的靜態信任評估 |
| N5 | VirusTotal Integration | Cisco mcp-scanner | Skills 主要是文字檔（Markdown + shell scripts），binary malware 較少見 |

---

## 建議的 Spec Roadmap（安全升級）

基於以上分析，建議以下新 specs 加入 Skills Hub roadmap：

### S010: 安全掃描引擎升級（建議 Milestone 8）

**Goal**: 將 S005 的 regex-only scanner 升級為多引擎 pipeline

**Scope**:
- 掃描範圍擴展到全部檔案（SKILL.md + references/ + assets/）
- 加入 Pattern Scanner（YARA-equivalent Java patterns）
- 加入 LLM Semantic Analyzer（Spring AI + Gemini）
- 加入 Secret Scanner（API key/token detection）
- 加入 Metadata Validator（frontmatter completeness + typosquatting）
- 加入 Meta-Analyzer（false positive filtering）
- 風險分級從 3 級擴展到 5 級（CRITICAL/HIGH/MEDIUM/LOW/SAFE）
- 掃描結果包含 OWASP AST classification

**Estimation**: L (18-20 points) — 需要多個 analyzer 實作 + LLM 整合 + 測試

**Dependencies**: S005 (✅), S007 (✅ Spring AI infra reuse)

### S011: 信任評分系統（建議 Milestone 9）

**Goal**: 為每個 skill 和 author 產出多維度信任分數

**Scope**:
- Author 信譽（發佈歷史、flag 記錄）
- Skill 品質信號（metadata completeness、version frequency、description quality）
- Security 信號（歷次掃描結果、最近掃描時間）
- Community 信號（下載數、flag 數、使用率）
- 綜合 Trust Score 計算
- SkillCard 和 SkillDetailPage 顯示 trust badge

**Estimation**: M (12-14 points)

**Dependencies**: S010 (掃描結果作為 security signal input), S008 (✅ analytics data)

### S012: Agent Skill BOM (ASBOM)（建議 Backlog）

**Goal**: 產出 CycloneDX 格式的技能物料清單

**Scope**:
- 列出 skill 的所有元件（SKILL.md, scripts, references, assets）
- 記錄掃描狀態和結果
- 匯出 CycloneDX 1.6 JSON/XML
- 支援企業合規需求

**Estimation**: S (8-10 points)

---

## 風險評估

| 風險 | 影響 | 機率 | 緩解措施 |
|------|------|------|---------|
| LLM 掃描成本過高 | 每次上傳 ~$0.01-0.05 API 費用 | 中 | 先用 pattern scanner 篩選，只對可疑 skills 啟用 LLM |
| LLM 掃描延遲 | 2-5 秒增加上傳等待 | 高 | 非同步處理（現有 event-driven 架構已支援） |
| False Positive 導致誤判 | 良性 skill 被標高風險 | 中 | Meta-Analyzer + 社群 flag 機制 + manual review |
| YARA Java 生態支援 | Java 無原生 YARA library | 低 | 用 Java Pattern + 外部 YARA rule file 格式 |
| 攻擊者繞過掃描 | 新型攻擊模式未被規則覆蓋 | 中 | LLM 語意分析 + 定期 rescan + 社群回報 |

---

## 結論

Skills Hub 的 S005 是一個合格的 MVP 起點，但與業界最佳實踐有顯著差距。**最緊迫的升級是 SKILL.md prompt injection 偵測**（AST01 + ASI01），因為 91% 的已知惡意 skills 使用此攻擊手法，而 S005 對此零覆蓋。

建議的升級路徑：
1. **S010 多引擎掃描** — 解決 AST01, AST04, AST08
2. **S011 信任評分** — 解決 AST09
3. **S012 ASBOM** — 解決合規需求

這三個 specs 合計約 40-44 story points，可以分 2-3 個 milestone 實施。
