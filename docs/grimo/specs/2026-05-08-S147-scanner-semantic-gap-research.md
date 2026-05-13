# S147: 掃描器語意分析缺口研究

> Spec: S147 | Size: META(research) | Status: ⏸ deferred（2026-05-08 暫緩，等研究啟動時機；恢復後再決定是否拆 sub-spec 實作）
> Date: 2026-05-08
> Origin: site audit 2026-05-08 — 對比 Snyk agent-scan 結果與我們的掃描器輸出，發現多個 regex 無法覆蓋的語意分析盲點（W011/E004/W009/W013）；需研究補強方向再決定是否拆 sub-spec 實作。

---

## 1. Goal

研究 Snyk agent-scan 的語意分析能力（hybrid LLM + deterministic rules），評估我們的 `PatternScanner` / `PromptInjectionScanner` 與 Snyk 之間的差距，並產出可行的補強建議與優先順序。

**非目標：**
- 不在此 spec 實作任何掃描器變更（實作留給後續 sub-spec）
- 不複製 Snyk 完整功能（定位差異：我們是企業內部 registry，Snyk 是公開市場）

---

## 2. 背景

### 2.1 Snyk agent-scan — 完整 Issue Code 分類

來源：[github.com/snyk/agent-scan/blob/main/docs/issue-codes.md](https://github.com/snyk/agent-scan/blob/main/docs/issue-codes.md)

#### Compromised MCP Servers（E 系列：MCP 伺服器遭入侵）

| Code | Severity | 說明 |
|------|----------|------|
| E001 | Critical | **Prompt injection in tool description** — MCP tool 描述內嵌惡意 prompt，可覆寫 agent 行為 |
| E002 | High | **Tool shadowing** — 跨 server 工具名稱衝突，惡意 server 可覆寫合法工具 |
| W001 | Low | **Suspicious words in tool description** — 工具描述含可疑關鍵字 |

#### Toxic Flows（W 系列：有毒資料流）

| Code | Severity | 說明 |
|------|----------|------|
| W015 | Medium | **Untrusted third-party content via MCP** — 透過 MCP 接觸不可信第三方內容 |
| W016 | Low | **Potential untrusted content** — 可能接觸不可信內容 |
| W017 | Medium | **Sensitive data exposure** — 可能洩漏 PII、財務資料、credentials |
| W018 | Low | **Workspace/local file access** — 讀取 workspace 或本地檔案 |
| W019 | Medium | **Destructive infrastructure capabilities** — 具備破壞性雲端/基礎設施操作能力 |
| W020 | Low | **Local destructive capabilities** — 具備本地破壞性操作能力 |

#### Compromised Skills（E 系列：SKILL.md 遭入侵）

| Code | Severity | 說明 |
|------|----------|------|
| E004 | Critical | **Prompt injection in skill instructions** — SKILL.md 自然語言指令中嵌入惡意 prompt，可操控 agent 行為 |
| E005 | Critical | **Suspicious download URL in skill** — skill 含可疑下載 URL，可能在 agent 執行時 fetch 惡意內容 |
| E006 | Critical | **Malicious code patterns** — 包含資料外洩、後門、RCE 等惡意代碼模式 |

#### Vulnerable Skills（W 系列：有漏洞的 Skills）

| Code | Severity | 說明 |
|------|----------|------|
| W007 | High | **Insecure credential handling** — 不安全的 credential 處理方式 |
| W008 | High | **Hardcoded secrets in skill** — skill 中硬編碼 secrets（API key、token、密碼等） |
| W009 | Medium | **Direct financial execution capability** — skill 具備直接執行金融交易的能力（買賣、轉帳） |
| **W011** | Medium | **Third-party content exposure** — skill 指示 agent 瀏覽任意 URL / 讀取 user-generated content，間接 prompt injection 風險 |
| **W012** | High | **Unverifiable external dependency** — skill 在 runtime 從外部 URL fetch 指令或代碼，外部來源可靜默修改 agent 行為 |
| W013 | Medium | **System service modification** — skill 具備修改系統服務/daemon 的能力 |
| W014 | Low | **Missing SKILL.md file** — skill package 缺少 SKILL.md 規格文件 |

### 2.2 Snyk 引擎架構

Snyk agent-scan 使用 **hybrid 引擎**：
- **Deterministic rules**：靜態 pattern matching（對應 E005、W008、W012 等程式碼層 patterns）
- **LLM semantic analysis**：解讀 SKILL.md 自然語言的行為意圖（對應 W011、E004、W009、W013）
- **模型**：Calibrated proprietary model（非公開，在 Tessl Registry 以 Batch Skill Analysis API 形式提供，5–15 秒/skill）

### 2.3 已知差距（本次 audit 發現）

| Snyk Code | Severity | 說明 | 我們的現況 |
|-----------|----------|------|-----------|
| W012 | High | Unpinned GitHub Actions (`@master/@main/@HEAD`) | ⛔ 不做；S146 cancelled 2026-05-13 |
| **W011** | Medium | Third-party content exposure（indirect prompt injection） | ⬜ 無規則 |
| E004 | Critical | Prompt injection in skill instructions | ⚠️ 有 `PromptInjectionScanner`，覆蓋範圍未知 |
| E005 | Critical | Suspicious download URL | ⬜ 無規則 |
| W007 | High | Insecure credential handling | ⬜ 無規則 |
| W008 | High | Hardcoded secrets | ⚠️ 有 `SecretScanner`，覆蓋程度需驗證 |
| W009 | Medium | Direct financial execution capability | ⬜ 無規則 |
| W013 | Medium | System service modification | ⬜ 無規則 |
| W019 | Medium | Destructive infrastructure capabilities | ⚠️ 部分由 PatternScanner rm -rf/chmod 777 覆蓋 |

### 2.4 W011 實際案例（本次 audit 觸發）

Snyk 對 `auditing-terraform-infrastructure-for-security` skill 的 W011 finding：

> **W011: Third-party content exposure detected (indirect prompt injection risk)**
>
> "Third-party content exposure detected (high risk: 0.90). SKILL.md explicitly instructs the agent to 'Navigate to target URL' and interact with live pages (click nav links, submit forms, test auth flows), which entails browsing arbitrary third-party web pages whose content can steer navigation and subsequent actions."

關鍵觀察：偵測依據是**自然語言語意**，不是程式碼 pattern — regex 無法處理。

### 2.5 核心挑戰

```
Snyk W011/E004 → LLM 語意分析 → 偵測「行為意圖」
我們的 PatternScanner → regex → 只看程式碼 pattern
我們的 PromptInjectionScanner → LLM judge → 但 prompt 設計與覆蓋範圍不明
```

---

## 3. 研究問題

### RQ-1：PromptInjectionScanner 現況審查
- `PromptInjectionScanner.java` 的 LLM prompt 設計是否涵蓋 W011（indirect URL browsing）？
- 它能否偵測「skill 指示 agent 讀取不可信來源 → 被第三方內容操控」這類語意？
- 目前測試案例覆蓋哪些情境？誤報率？

### RQ-2：Snyk agent-scan 開源實作深度分析
- `github.com/snyk/agent-scan` 的 LLM prompt / rule definition 有多少是公開的？
- 可否參考其 system prompt 設計改進我們的 `PromptInjectionScanner`？
- Batch Skill Analysis API 有沒有公開 spec 可串接？

### RQ-3：各缺口的 regex 可行性評估

| Gap | Regex 可行？ | 可能規則 | 預估誤報 |
|-----|------------|---------|---------|
| E005 Suspicious download URL | ✅ 高 | `(curl\|wget).*https?://` + URL blocklist | 低 |
| W009 Financial execution | ⚠️ 中 | `execute.*trade\|buy.*token\|transfer.*USDC` | 中 |
| W013 System service modification | ✅ 高 | `systemctl\|service.*start\|/etc/init\.d/` | 低 |
| W011 Third-party URL browsing | ❌ 低 | regex 難以區分說明文件中的 URL vs. 真正指示 agent 瀏覽 | 高 |
| W007 Insecure credential handling | ⚠️ 中 | 結合 SecretScanner 擴充 | 中 |

### RQ-4：補強優先順序建議
- 哪些 gap 用 regex 即可補（低成本、低誤報）？
- 哪些需要 LLM judge（高成本、高準確）？
- 哪些對企業內部 registry 場景最重要（vs. 公開市場才需要）？

---

## 4. 參考資料

### 官方文件

| 資源 | URL |
|------|-----|
| Snyk agent-scan GitHub | https://github.com/snyk/agent-scan |
| Snyk issue codes 完整定義 | https://github.com/snyk/agent-scan/blob/main/docs/issue-codes.md |
| Snyk + Tessl 合作說明 | https://snyk.io/blog/snyk-tessl-partnership/ |
| Tessl Registry Security Scores 說明 | https://tessl.io/blog/the-tessl-registry-now-has-security-scores-powered-by-snyk/ |
| Snyk Labs Skill Inspector（互動掃描工具） | https://labs.snyk.io/experiments/skill-scan/ |

### 研究論文 / 部落格

| 資源 | URL | 重點摘要 |
|------|-----|---------|
| ToxicSkills 研究 | https://snyk.io/blog/toxicskills-malicious-ai-agent-skills-clawhub/ | 36% of ~4,000 public skills 含 prompt injection；100% 惡意 skill 同時有 code payload + NL injection |
| OWASP Agentic Skills Top 10 | https://owasp.org/www-project-agentic-skills-top-10/ | 標準化 agent skill 安全分類，Snyk taxonomy 對齊此標準 |
| OWASP Top 10 for LLM Applications | https://owasp.org/www-project-top-10-for-large-language-model-applications/ | LLM 安全 Top 10，indirect prompt injection = LLM01 |

### 我們的程式碼

| 位置 | 說明 |
|------|------|
| `backend/src/main/java/.../security/scan/engines/PromptInjectionScanner.java` | LLM judge path — 需審查 prompt 設計 |
| `backend/src/main/java/.../security/scan/engines/PatternScanner.java` | 靜態 regex rules — 現有 8 條規則 |
| `backend/src/main/java/.../security/scan/engines/SecretScanner.java` | Hardcoded secret 偵測 |
| `backend/src/main/java/.../security/scan/engines/DependencyVulnScanner.java` | 依賴漏洞掃描 |
| `backend/src/test/java/.../security/scan/` | 現有掃描器測試 |

---

## 5. 預期產出

1. **PromptInjectionScanner 現況評估**（RQ-1 結論 + W011/E004 覆蓋 gap 量化）
2. **Gap 優先順序表**（regex 可補 / LLM 必要 / 暫不做 三欄）
3. **後續 sub-spec 清單草稿**，例如：
   - S147a: PatternScanner — E005/W013 regex 補強（XS）
   - S147b: PatternScanner — W009 financial execution regex（XS）
   - S147c: PromptInjectionScanner prompt 改版（W011 indirect URL browsing + E004 NL injection）（S-M）
   - S147d: SecretScanner 覆蓋驗證與補強 W008（XS）

---

## 6. 完成條件

- [ ] RQ-1 ~ RQ-4 各有書面結論
- [ ] Gap 優先順序表完成
- [ ] sub-spec 清單草稿（標題 + size 估算 + 優先序）
- [ ] 此 spec 歸檔至 `archive/`，已決定實作的 sub-spec 開立至 `specs/`
