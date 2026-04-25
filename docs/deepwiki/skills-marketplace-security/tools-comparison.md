# 開源掃描工具深度比較

> 來源：Snyk agent-scan、Cisco mcp-scanner、Cisco skill-scanner、SkillFortify

---

## 工具定位比較

```
              MCP 伺服器掃描                    Agent Skill 掃描
                   │                               │
     ┌─────────────┼──────────────┐    ┌──────────┼──────────────┐
     │             │              │    │          │              │
  Snyk          Cisco          Cisco  Cisco    SkillFortify   Snyk
  agent-scan    mcp-scanner    skill- skill-                  agent-scan
  (both)        (MCP focus)    scanner scanner                (--skills)
                               (both)  (skill focus)
```

---

## 工具概覽表

| 維度 | Snyk agent-scan | Cisco mcp-scanner | Cisco skill-scanner | SkillFortify |
|------|----------------|-------------------|--------------------|--------------| 
| **GitHub** | [snyk/agent-scan](https://github.com/snyk/agent-scan) | [cisco-ai-defense/mcp-scanner](https://github.com/cisco-ai-defense/mcp-scanner) | [cisco-ai-defense/skill-scanner](https://github.com/cisco-ai-defense/skill-scanner) | [qualixar/skillfortify](https://github.com/qualixar/skillfortify) |
| **語言** | Python | Python | Python 96.2% + YARA 2.5% | Python |
| **授權** | 商用（免費 CLI） | Apache 2.0 | Apache 2.0 | MIT |
| **掃描對象** | MCP servers + Skills | MCP servers | Skills + MCP servers | Skills |
| **引擎數** | 2（Local + Cloud API） | 5-7 | 7 | Formal verification |
| **離線能力** | 部分（inspect 指令） | 部分（YARA + Behavioral） | 部分 | 完全 |
| **CI/CD 整合** | ✅ JSON output | ✅ Static mode + JSON | ✅ SARIF output | ✅ Exit codes |
| **SBOM** | ❌ | ❌ | ❌ | ✅ CycloneDX 1.6 |

---

## Snyk agent-scan 詳細分析

### 架構

```
snyk-agent-scan CLI
├── scan command ─── Discovery → Connect → Local checks → Cloud API → Report
├── inspect command ─── Enumerate tools/prompts/resources (offline)
├── whitelist command ─── Manage approved entities
└── evo command ─── Push results to Snyk Evo platform
```

### Auto-Discovery 機制

透過 `well_known_clients.py` 定義的路徑自動發現已安裝的 agents：

| Agent | 平台 |
|-------|------|
| Claude Code / Desktop | macOS, Linux, Windows |
| Cursor | macOS, Linux, Windows |
| VS Code | macOS, Linux, Windows |
| Windsurf | macOS, Linux, Windows |
| Gemini CLI | macOS, Linux |
| Amazon Q | macOS, Linux |
| Codex | macOS, Linux |
| Kiro | macOS, Linux |

### 偵測的 15+ 風險

| 代碼 | 類別 |
|------|------|
| E001 | Prompt Injection |
| E002 | Tool Poisoning |
| E004 | Tool Shadowing |
| E006 | Toxic Flows |
| W007 | Malware Payloads |
| W008 | Untrusted Content |
| W011 | Credential Mismanagement |
| ... | Hardcoded Secrets, etc. |

### Toxic Flow 偵測

獨特的「Toxic Flow」偵測 — 分析多個 tools 之間的資料依賴鏈，找出 cross-tool 攻擊路徑：

```
Tool A (讀取 credentials)
    │ 資料流
    ▼
Tool B (HTTP POST 到外部)
    → Toxic Flow detected!
```

### 隱私考量

- 傳送 tool names、descriptions、scan metadata 到 Snyk Cloud
- **tool descriptions 可能洩漏內部專案細節**
- 可用 `--opt-out` 停用匿名追蹤
- 受管制產業（HIPAA、SOC2）需評估資料外傳風險

---

## Cisco mcp-scanner 詳細分析

### 五大 Analyzer 架構

```
┌─────────────────────────────────────────────────────────┐
│                    Scanner class                         │
│  Dispatches entities to analyzers concurrently           │
├────────┬────────┬────────┬───────────┬─────────────────┤
│ YARA   │ LLM    │ API    │ Behavioral│ Readiness       │
│Analyzer│Analyzer│Analyzer│ Analyzer  │ Analyzer        │
├────────┼────────┼────────┼───────────┼─────────────────┤
│35+ rules│LiteLLM│Cisco AI│ AST parse │ 20 heuristic    │
│Offline  │gpt-4o │Defense │ 10 langs  │ rules           │
│<0.1ms   │~2-5s  │~1-3s   │ ~1-5s     │ Offline         │
└────────┴────────┴────────┴───────────┴─────────────────┘
         ↓ all produce SecurityFinding objects ↓
┌─────────────────────────────────────────────────────────┐
│  Threat Taxonomy Enrichment (MCP Taxonomy classification)│
│  → AITech / AISubtech standardization                    │
└─────────────────────────────────────────────────────────┘
```

### 連線模式

| 模式 | 說明 | 用途 |
|------|------|------|
| Remote (SSE/HTTP) | 透過 `--server-url` 連線，支援 OAuth | 掃描遠端 MCP servers |
| Stdio | 啟動 child process，`--stdio-command` | 掃描本地 MCP servers |
| Config file | 讀取已知位置的 MCP config | 批量掃描 |
| Static/Offline | 處理 pre-generated JSON | CI/CD + air-gapped |

### 輸出格式

```bash
# Summary（快速評估）
mcp-scanner --format summary

# JSON（程式化處理）
mcp-scanner --format raw

# By severity（風險優先排序）
mcp-scanner --format by_severity

# By analyzer（引擎比較）
mcp-scanner --format by_analyzer
```

---

## Cisco skill-scanner 詳細分析

### 七大 Analyzer

| # | Analyzer | 方法 | 離線 |
|---|----------|------|------|
| 1 | **Static Analysis** | YAML + YARA pattern matching | ✅ |
| 2 | **Bytecode Analyzer** | .pyc integrity verification | ✅ |
| 3 | **Pipeline Analyzer** | Command taint analysis in shell pipelines | ✅ |
| 4 | **Behavioral Analyzer** | AST dataflow analysis (Python) | ✅ |
| 5 | **LLM Analyzer** | Semantic analysis | ❌ 需 API |
| 6 | **Meta-Analyzer** | False positive filtering + prioritization | ✅ |
| 7 | **VirusTotal/AI Defense** | Hash-based + cloud scanning | ❌ 需 API |

### 支援的 Skill 格式

| 格式 | 說明 |
|------|------|
| OpenAI Codex Skills | Codex skill format |
| Cursor Agent Skills | agentskills.io specification |
| Claude Code `.claude/commands/*.md` | Lenient mode |
| Non-standard markdown | Lenient mode |
| Custom metadata filename | 預設 SKILL.md，可配置 |

### Meta-Analyzer：False Positive 過濾

這是 skill-scanner 的獨特設計 — 一個專門處理其他 analyzer 結果的 meta-layer：
- 交叉比對多引擎結果
- 過濾已知 false positive patterns
- 優先排序（prioritization）
- 產出最終 severity assignment

### SARIF 輸出

```bash
skill-scanner --format sarif --output results.sarif
```

SARIF (Static Analysis Results Interchange Format) 可直接整合：
- GitHub Advanced Security (Code Scanning alerts)
- Azure DevOps
- VS Code Problems panel
- SonarQube

---

## SkillFortify 詳細分析

### Formal Verification 方法

與其他工具的 heuristic/pattern-based 方法不同，SkillFortify 採用 **formal threat model (DY-Skill)**：

- 基於 **sound static analysis** 而非 heuristic patterns
- **POLA (Principle of Least Authority)** 權限合規檢查
- Constraint-based dependency resolution with conflict detection
- Multi-signal trust propagation through dependency chains

### SBOM 生成

```bash
skillfortify sbom --format cyclonedx --output asbom.json
```

產出 **CycloneDX 1.6** 合規的 Agent Skill Bill of Materials (ASBOM)，用於：
- 法規遵循（EU AI Act、SOC2）
- 供應鏈稽核
- 資產盤點

### 22 個支援的 Framework

Auto-discovery 支援：
- Claude 生態系（`.claude/` directories）
- MCP 基礎設施（`mcp.json`, `mcp_config.json`）
- LangChain（`BaseTool`, `@tool` decorators）
- CrewAI（`crew.yaml`）
- AutoGen、OpenAI Agents SDK、Google ADK
- Dify、n8n、Flowise、Mastra、PydanticAI
- CAMEL-AI、MetaGPT、Haystack

### Trust Level 分級

| Level | 說明 |
|-------|------|
| FORMALLY_VERIFIED | 通過 formal verification |
| SIGNED | 有加密簽章 |
| SCANNED | 通過自動掃描 |
| UNSIGNED | 未經驗證 |

### Lockfile 機制

```bash
skillfortify lock --output skill-lock.json
```

產出 deterministic `skill-lock.json`，確保可重現的配置。類似 `package-lock.json` 的概念，但用於 AI skills。

---

## 整合建議：Skills Hub 該選哪個？

| 場景 | 推薦工具 | 理由 |
|------|---------|------|
| **快速原型** | Cisco skill-scanner | Apache 2.0、支援 SKILL.md、7 引擎、SARIF |
| **企業部署** | Snyk agent-scan + SkillFortify | Snyk 的威脅情報 + SkillFortify 的 SBOM |
| **自建引擎** | 參考 Cisco mcp-scanner 架構 | Apache 2.0、模組化、可自訂 YARA rules |
| **CI/CD Pipeline** | Cisco skill-scanner (SARIF) | 直接整合 GitHub Actions |
| **法規遵循** | SkillFortify (CycloneDX) | SBOM 生成滿足 EU AI Act |

### Skills Hub 推薦路徑

考慮到 Skills Hub 使用 Java/Spring Boot 技術棧：

1. **Phase 1**: 將 S005 的 regex scanner 升級為 YARA-equivalent Java pattern matching
2. **Phase 2**: 整合 LLM 語意分析（可用 Spring AI + Gemini，複用 S007 基礎設施）
3. **Phase 3**: 加入 Skill metadata 驗證（SKILL.md frontmatter completeness）
4. **Phase 4**: 整合外部掃描工具（呼叫 Cisco skill-scanner CLI 或 Snyk API）
5. **Phase 5**: 實作 Trust Score 系統（參考 MCPSkills.io 14 signals）
