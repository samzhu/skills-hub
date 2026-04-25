# 各市集平台安全機制比較

> 來源：VS Code Marketplace、Smithery.ai、MCPSkills.io、MS Agent Governance Toolkit

---

## 安全模型光譜

不同平台的安全投入程度差異極大，可以用一個光譜來描述：

```
自助式（使用者自負）                              全託管（平台負責）
├────────┼────────┼────────┼────────┼────────┤
Smithery  npm    MCPSkills  Skills  VS Code
(無審核)  (CVE   (Trust    Hub     Marketplace
          only)  Score)   (S005)   (多層掃描)
```

---

## VS Code Extension Marketplace — 最成熟的模型

### 五層防禦掃描 Pipeline

```
Extension 上傳
    │
    ▼
┌──────────────────────┐
│ 1. Initial Scan      │  Microsoft Defender + 多引擎防毒掃描
│    Publication-time   │  擋住已知惡意軟體 signatures
└──────────┬───────────┘
           │
    ▼
┌──────────────────────┐
│ 2. Post-Publication  │  發佈後二次掃描
│    Rescan            │  捕捉初次掃描遺漏的威脅
└──────────┬───────────┘
           │
    ▼
┌──────────────────────┐
│ 3. Dynamic Detection │  沙箱環境 (clean room VM)
│    Sandbox           │  執行 extension 觀察 runtime 行為
└──────────┬───────────┘
           │
    ▼
┌──────────────────────┐
│ 4. Periodic Rescan   │  定期重新掃描所有 extensions
│    Full marketplace  │  用新規則發現舊威脅
└──────────┬───────────┘
           │
    ▼
┌──────────────────────┐
│ 5. Community Report  │  使用者回報機制
│    1 business day    │  一個工作天內初始回應
└──────────────────────┘
```

### 信任指標系統

| 信號 | 說明 |
|------|------|
| **Verified Publisher** | Domain ownership 驗證 + 6 個月以上良好記錄 → 藍色勾勾 |
| **Signature Verification** | 所有 extension 發佈時加密簽章，安裝時驗證 |
| **Secret Scanning** | 阻擋包含 API keys 或 credentials 的 extension 發佈 |
| **Name Squatting Prevention** | 阻擋冒充官方 publisher 的行為 |
| **Usage Monitoring** | 異常 download/usage pattern 偵測 |

### 執法數據

2026 年已審查 136 個 extensions，移除 110 個含惡意程式碼的 extension。被移除的 extension 會加入 block list，VS Code 會自動卸載已安裝的惡意 extension。

### 進行中的改善

- Copycat detection — 檢查重複的 repository links 和 logos
- 加強 publisher 入職審核
- 識別安全敏感行為（obfuscation、remote code execution）
- Publisher-signing requirements

來源：[Security and Trust in Visual Studio Marketplace](https://developer.microsoft.com/blog/security-and-trust-in-visual-studio-marketplace)

---

## Smithery.ai — 最大 MCP Registry（低安全投入）

### 安全模型

Smithery 採用「**使用者自負責**」模式：

- 無正式的安全審核流程
- 無自動化掃描 pipeline
- 推薦使用者用環境變數管理 tokens
- 建議避免 untrusted fields
- 仰賴第三方（MCPSkills.io）提供安全驗證

### 已知安全事件

2025 年 6 月 path traversal 漏洞：
- `dockerBuildPath` 參數允許容器逃逸
- 暴露 builder machine 上數千個 API keys
- 由 GitGuardian 發現並回報

### 社群疑慮

- CLI 工具曾是 minified 的，難以審計
- 開發團隊後來承諾開源所有程式碼

---

## MCPSkills.io — 信任評分平台

### 14 信號信任評分模型

MCPSkills.io 不是 registry，而是提供信任評分服務：

```
┌─────────────────────────────────────────┐
│  Input: GitHub repo / npm package /     │
│         Smithery URL                    │
└──────────────┬──────────────────────────┘
               │
    ┌──────────▼──────────┐
    │  14 Signals across  │
    │  4 Dimensions       │
    │                     │
    │  📊 GitHub API      │
    │  📦 npm registry    │
    │  🔒 OpenSSF Score   │
    │  🔍 Safety scan     │
    └──────────┬──────────┘
               │
    ┌──────────▼──────────┐
    │  Trust Score Output │
    │  + Safety Report    │
    └─────────────────────┘
```

### 安全掃描項目

Source code 直接檢查：
- Prompt injection patterns
- Shell execution commands
- Credential access patterns
- Network exfiltration patterns
- Obfuscated payloads
- ClawHavoc 和 ToxicSkills 已知 patterns

### MCP/Skill 額外信號

MCP servers 和 AI skills 額外多 2 個信號，且安全權重更高。

### 限制

> 「高分代表所有維度的靜態指標強，但沒有自動化工具能捕捉所有問題。」

---

## Microsoft Agent Governance Toolkit — 企業級治理

### Agent Marketplace 模組

| 功能 | 實作方式 |
|------|---------|
| **Plugin Signing** | Ed25519 + quantum-safe ML-DSA-65 加密簽章 |
| **Verification** | 發佈時簽章，安裝時驗證 |
| **Trust Tiers** | 四級權限環 (Ring 0-3) 基於 trust score |
| **SBOM** | CycloneDX 格式的 Agent Bill of Materials |
| **Capability Gating** | 信任等級決定可用 capabilities |

### 信任評分系統

```
Trust Score: 0 — 1000

Ring 0 (≥900): 完整系統存取
Ring 1 (≥700): 跨 agent 協作
Ring 2 (≥400): 標準工具使用
Ring 3 (<400): 唯讀、沙箱化
```

### 信任衰減機制

> 「Agent 的 trust score 會隨時間衰減而無正面信號，模擬信任需要持續展示的現實。」

### Policy Engine

```python
kernel = StatelessKernel()
ctx = ExecutionContext(
    agent_id="analyst-1",
    policies=[
        Policy.read_only(),
        Policy.rate_limit(100, "1m"),
        Policy.require_approval(...)
    ]
)
```

- **每個 tool call、resource access、inter-agent message** 在執行前都經過 policy 評估
- **確定性（非概率性）** 評估
- 延遲 < 0.1ms per action
- 吞吐量 9.3K-72K ops/sec
- 支援 YAML、OPA/Rego、Cedar policy 語言

來源：[Microsoft Agent Governance Toolkit](https://github.com/microsoft/agent-governance-toolkit)

---

## npm / PyPI — 傳統套件生態的教訓

### 為何 Agent Skills 可借鏡

Agent Skills 生態與早期 npm/PyPI 高度相似：
- 低發佈門檻
- 無強制 code signing
- 使用者信任 registry 品牌
- 攻擊者利用 typosquatting 和 dependency confusion

### Socket.dev 行為分析方法

- 整合 GitHub PR — 自動 flag 可疑的 dependency changes
- 偵測行為指標：filesystem access、network calls、shell execution
- 在 `npm install` 時即時 flag 可疑套件
- **能在 CVE 發佈前偵測惡意意圖**（vs npm audit 只檢查已知 CVE）

### Snyk 惡意套件偵測

- 2024 年偵測 3,600+ 惡意套件（npm 3,000+、PyPI 600+）
- 結合 public vulnerability DB + proprietary research
- 動態分析工具跨 ecosystem 偵測惡意套件

---

## 平台安全機制對照表

| 機制 | VS Code | Smithery | MCPSkills | MS AGT | npm | Skills Hub (S005) |
|------|---------|----------|-----------|--------|-----|-------------------|
| 自動掃描 | ✅ 多引擎 | ❌ | ✅ Source code | ✅ Policy | ✅ CVE | ✅ Regex |
| Dynamic sandbox | ✅ Clean room VM | ❌ | ❌ | ❌ | ❌ | ❌ |
| Code signing | ✅ | ❌ | ❌ | ✅ Ed25519 | ✅ npm provenance | ❌ |
| Publisher verification | ✅ Domain | ❌ | ✅ GitHub | ✅ DID | ❌ | ❌ |
| Trust score | ❌ (badge only) | ❌ | ✅ 14 signals | ✅ 0-1000 | ❌ | ❌ |
| Community report | ✅ 1 day SLA | ❌ | ❌ | ❌ | ✅ | ✅ SkillFlagged |
| Auto-removal | ✅ Block list | ❌ | ❌ | ✅ Kill switch | ✅ | ❌ |
| SBOM | ❌ | ❌ | ❌ | ✅ CycloneDX | ❌ | ❌ |
| Periodic rescan | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ |
| Secret scanning | ✅ | ❌ | ✅ | ❌ | ✅ | ❌ |
