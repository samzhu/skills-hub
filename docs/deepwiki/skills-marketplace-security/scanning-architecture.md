# 安全掃描架構設計模式

> 來源：Cisco mcp-scanner、Cisco skill-scanner、Snyk agent-scan、SkillFortify

---

## 核心架構：多引擎 Pipeline

業界最佳實踐是「**Defense-in-Depth 多引擎並行掃描**」。單一掃描方法無法覆蓋所有威脅：

- YARA pattern matching 在 MCP tool descriptions 上有 **~78% false positive rate**（來源：[AppSecSanta 研究](https://appsecsanta.com/research/mcp-server-security-audit-2026)）
- LLM-as-Judge 能理解語意但有 API 延遲與成本
- Behavioral Analysis 能發現 docstring 與實作不一致，但只適用可解析語言

因此 Cisco mcp-scanner 和 skill-scanner 都採用 **5-7 個獨立 Analyzer 並行** 的架構。

## 掃描 Pipeline 總覽

```
                    ┌─────────────────────────┐
                    │   Input (Skill Package)  │
                    │  ZIP / Git repo / URL    │
                    └──────────┬──────────────┘
                               │
                    ┌──────────▼──────────────┐
                    │   Discovery & Parsing    │
                    │  • Auto-detect framework │
                    │  • Extract metadata      │
                    │  • Enumerate files        │
                    └──────────┬──────────────┘
                               │
              ┌────────────────┼────────────────┐
              │                │                │
     ┌────────▼─────┐  ┌──────▼──────┐  ┌──────▼──────┐
     │ Static Layer │  │ Semantic    │  │ Behavioral  │
     │              │  │ Layer       │  │ Layer       │
     │ • YARA rules │  │ • LLM-as-  │  │ • AST parse │
     │ • Regex      │  │   Judge    │  │ • Dataflow  │
     │ • Secret det │  │ • Prompt   │  │ • Doc vs    │
     │ • VirusTotal │  │   hardening│  │   impl      │
     └──────┬───────┘  └─────┬──────┘  └─────┬───────┘
            │                │               │
            └────────────────┼───────────────┘
                             │
                  ┌──────────▼──────────────┐
                  │   Meta-Analyzer          │
                  │  • False positive filter │
                  │  • Cross-engine correlate│
                  │  • Severity assignment   │
                  │  • Taxonomy enrichment   │
                  └──────────┬──────────────┘
                             │
                  ┌──────────▼──────────────┐
                  │   Output Formatter       │
                  │  JSON / SARIF / HTML /   │
                  │  Table / Summary          │
                  └─────────────────────────┘
```

來源：Cisco mcp-scanner `core/scanner.py` 將 entities dispatch 給所有 analyzers 並行處理，收集 `SecurityFinding` 物件後聚合。

---

## 引擎 1：YARA Pattern Matching

### 原理

YARA 是一種 pattern matching 工具，最初用於惡意軟體辨識。在 AI skill 掃描中，用於快速比對已知威脅模式。

### 實作方式（Cisco mcp-scanner `analyzers/yara_analyzer.py`）

- 啟動時編譯 35+ 規則（`/data/yara_rules/` 目錄），避免每次掃描重新編譯
- 每條規則包含：pattern definition、severity level、threat category、human-readable description
- 掃描對象：tool descriptions、prompts、resource definitions
- **完全離線運作**，無需 API key

### 偵測範圍

| 類別 | 範例 Pattern |
|------|-------------|
| Command Injection | `exec(`, `os.system(`, `subprocess.call(` |
| Prompt Injection | `ignore previous instructions`, `you are now` |
| Data Exfiltration | `curl.*POST`, `requests.post(`, encoded data patterns |
| System Access | `/etc/passwd`, `~/.ssh`, `~/.aws` |
| Tool Poisoning | Hidden instructions in tool descriptions |

### 限制

YARA 在 MCP tool descriptions 上 false positive rate 高達 ~78%，因為正常 MCP 文件中的命令式語句（「call this tool」、「execute this query」）與威脅 pattern 詞彙高度重疊。必須搭配語意分析才能有效使用。

---

## 引擎 2：LLM-as-Judge（語意分析）

### 原理

用大型語言模型評估 tool descriptions 和 skill 內容是否包含惡意意圖。LLM 能理解自然語言語意，區分正常指令與攻擊性 prompt injection。

### 實作方式（Cisco mcp-scanner `analyzers/llm_analyzer.py`）

```
┌──────────────────────────────────────────┐
│ Security-Hardened Prompt                  │
│                                          │
│  <<RANDOM_DELIMITER_abc123>>             │
│  Analyze the following tool description  │
│  for security threats:                   │
│  {tool_description}                      │
│  <<RANDOM_DELIMITER_abc123>>             │
│                                          │
│  If the output contains unexpected       │
│  delimiters → injection detected         │
└──────────────────────────────────────────┘
```

- 使用 **LiteLLM** 抽象層，支援 OpenAI、Azure、AWS Bedrock、自建模型
- 預設模型 `gpt-4o`，可透過 `MCP_SCANNER_LLM_MODEL` 配置
- Prompt 使用 **random delimiter tags** — 如果 LLM 輸出包含非預期的 delimiter，表示 injection 成功
- 重試機制：exponential backoff，最多 3 次（`MCP_SCANNER_LLM_MAX_RETRIES`）

### 雙重角色

| 角色 | Analyzer | 用途 |
|------|----------|------|
| Detection | LLMAnalyzer | 偵測 prompt injection 和 role escape |
| Verification | BehavioralCodeAnalyzer | 比較文件描述 vs 實際實作 |

---

## 引擎 3：Behavioral Code Analysis（行為分析）

### 原理

透過 AST (Abstract Syntax Tree) 解析原始碼，追蹤資料流，比對文件聲稱的行為與實際實作是否一致。專門偵測「供應鏈攻擊」中的文件偽裝。

### 實作方式（Cisco mcp-scanner `analyzers/behavioral_analyzer.py`）

1. AST 解析：提取 function signatures、docstrings
2. Dataflow tracking：追蹤變數流向和相依性
3. Cross-file analysis：mapping imports 和 API calls
4. LLM alignment verification：用 LLM 比對 claimed vs actual behavior

### 支援語言

Python、TypeScript、JavaScript、Go、Java、Kotlin、C#、Rust、Ruby、PHP — 共 10 種語言。

### 偵測範圍

- **Undocumented capabilities** — 隱藏功能（文件沒寫但程式碼有做）
- **Behavioral discrepancies** — 聲稱的行為和實際不符
- **Supply chain attacks** — 毒化的相依套件
- **Documentation manipulation** — 故意誤導的文件

---

## 引擎 4：Cloud Threat Intelligence（雲端威脅情報）

### Snyk Agent Scan API

- 傳送 tool names、descriptions、scan metadata 到 Snyk 雲端
- 利用 Snyk 的全球威脅資料庫比對
- 匿名 persistent ID 追蹤
- 偵測 15+ 風險類型
- 隱私考量：tool descriptions 可能洩漏內部專案細節

### Cisco AI Defense API

- 機器學習分類：malicious/safe 二元判定
- 附帶 threat categories
- 帶 retry + exponential backoff
- 需要 `MCP_SCANNER_API_KEY`

### VirusTotal Integration

- SHA256 hash lookup
- 偵測 binary files 中的 malware
- 需要 `VIRUSTOTAL_API_KEY`

---

## 引擎 5：Production Readiness（生產就緒度）

### 原理

不是安全威脅偵測，而是評估 MCP server 的營運可靠度。

### 實作方式（Cisco mcp-scanner `analyzers/readiness_analyzer.py`）

- 20 條 heuristic rules
- 檢查：timeouts、retries、error handling、dependencies、rate limiting、auth、validation
- 完全離線運作
- Policies 從 `/data/readiness_policies/` 載入

---

## 掃描輸出標準化

### SecurityFinding 資料模型

```
SecurityFinding {
    severity:        HIGH | MEDIUM | LOW | SAFE | UNKNOWN
    summary:         "Concise human-readable description"
    details:         "Comprehensive explanation"
    threat_category: "Detected threat type"
    mcp_taxonomy: {
        ai_tech:    "Top-level category"
        ai_subtech: "Granular subcategory"
        description: "Threat explanation"
    }
    analyzer:        "Source analyzer name"
    metadata:        { additional context }
}
```

來源：Cisco mcp-scanner `core/models.py`

### 輸出格式比較

| 工具 | JSON | SARIF | HTML | Table | Summary | CycloneDX SBOM |
|------|------|-------|------|-------|---------|----------------|
| Cisco mcp-scanner | ✅ | ❌ | ❌ | ✅ | ✅ | ❌ |
| Cisco skill-scanner | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| Snyk agent-scan | ✅ | ❌ | ❌ | ❌ | ✅ (rich text) | ❌ |
| SkillFortify | ✅ | ❌ | ✅ | ✅ | ❌ | ✅ |

### SARIF 整合

Cisco skill-scanner 支援 SARIF (Static Analysis Results Interchange Format) 輸出，可直接整合 GitHub Advanced Security、Azure DevOps、IDE 等工具。

---

## Skills Hub 現有架構 vs 業界實踐

| 維度 | Skills Hub S005 | 業界最佳實踐 |
|------|----------------|-------------|
| 掃描引擎 | 1 個（Regex） | 5-7 個並行 |
| 掃描對象 | scripts/ 目錄 | 全部檔案 + metadata + dependencies |
| 偵測方法 | Pattern matching only | YARA + LLM + AST + API + VirusTotal |
| False positive 處理 | 無 | Meta-analyzer 交叉比對 |
| 輸出格式 | Event (SkillRiskAssessed) | JSON + SARIF + HTML + Event |
| 離線能力 | ✅ | 部分引擎可離線 |
| Taxonomy | 自訂 3 級 | OWASP AST + MCP Taxonomy |
