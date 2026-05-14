# S147: 掃描器語意分析缺口研究

> Spec: S147 | Size: META(research) | Status: ⏳ Plan（2026-05-13；POC task first，確認後才進 production implementation）
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

公開文件只揭露到 taxonomy 與高層行為，沒有公開 system prompt、rule set 細節或 Batch Skill Analysis API 的 request/response schema；這部分只能從產品頁面與 issue taxonomy 做推論，而不是直接實作層驗證。

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
我們的 PromptInjectionScanner → 其實是 regex（不是 LLM judge）→ 覆蓋範圍只到顯性文字模式
我們現有的 LlmJudge → 可做語意補強，但目前是泛用安全判斷，不是 prompt-injection 專用 rubric
```

---

## 3. 研究問題

### RQ-1：PromptInjectionScanner 現況審查
- `PromptInjectionScanner.java` 的 regex 設計是否涵蓋 W011（indirect URL browsing）？
- 它能否偵測「skill 指示 agent 讀取不可信來源 → 被第三方內容操控」這類語意？
- `LlmJudge.java` 的 prompt 是否已能承接 W011 / E004 語意判斷？目前測試案例覆蓋哪些情境？誤報率？

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
| Snyk agent-scan PyPI | https://pypi.org/project/snyk-agent-scan/ |
| Snyk issue codes 完整定義 | https://github.com/snyk/agent-scan/blob/main/docs/issue-codes.md |
| Snyk + Tessl 合作說明 | https://snyk.io/blog/snyk-tessl-partnership/ |
| Tessl Registry Security Scores 說明 | https://tessl.io/blog/the-tessl-registry-now-has-security-scores-powered-by-snyk/ |
| Snyk Labs Skill Inspector announcement | https://labs.snyk.io/resources/agent-scan-skill-inspector/ |
| Snyk Labs Skill Inspector（互動掃描工具） | https://labs.snyk.io/experiments/skill-scan/ |
| OpenClaw ClawHub docs | https://docs.openclaw.ai/tools/clawhub |

### 研究論文 / 部落格

| 資源 | URL | 重點摘要 |
|------|-----|---------|
| ToxicSkills 研究 | https://snyk.io/blog/toxicskills-malicious-ai-agent-skills-clawhub/ | 36% of ~4,000 public skills 含 prompt injection；100% 惡意 skill 同時有 code payload + NL injection |
| OWASP Agentic Skills Top 10 | https://owasp.org/www-project-agentic-skills-top-10/ | 標準化 agent skill 安全分類，Snyk taxonomy 對齊此標準 |
| OWASP Top 10 for LLM Applications | https://owasp.org/www-project-top-10-for-large-language-model-applications/ | LLM 安全 Top 10，indirect prompt injection = LLM01 |

### 我們的程式碼

| 位置 | 說明 |
|------|------|
| `backend/src/main/java/.../security/scan/engines/PromptInjectionScanner.java` | Static prompt-injection regex — 需審查 E004 覆蓋 |
| `backend/src/main/java/.../security/scan/engines/LlmJudge.java` | LLM semantic judge — 需補 Snyk-like issue code rubric |
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

- [x] RQ-1 ~ RQ-4 各有書面結論（2026-05-13 §7）
- [x] Gap 優先順序表完成（2026-05-13 §7.3）
- [x] sub-spec 清單草稿（標題 + size 估算 + 優先序）（2026-05-13 §7.3）
- [x] planning-tasks task plan 建立（2026-05-13 §6.1 ~ §6.4）
- [ ] user confirmation（T00 已於 2026-05-14 PASS；待確認後啟動 T01 production implementation）
- [ ] implementation tasks 全部 PASS 後，結果合併回 §7 / §10，臨時 task 檔刪除

### 6.1 planning-tasks 決策（2026-05-13）

`docs/grimo/tasks/2026-05-13-S147-T00-poc-detector-contract.md` 是第一個 task。這次不直接改 production code，因為 S147 有兩個尚未在專案內驗證的設計點：

1. `SecurityFinding` 新增 `issueCode / remediation / confidence` 後，舊的 `riskAssessment` JSON 是否仍可被 `/api/v1/skills/{id}/security-report` 正確讀出。
2. `SecurityTab` 從固定 `shell / paths / secrets / deps` 改成動態 category 後，detail 頁是否仍能呈現舊資料，且能顯示 Snyk-like issue code。

POC: required。T00 只能寫入 `poc/S147/` 與本 spec 的 POC findings，不碰 `backend/src/main` 或 `frontend/src`。T00 PASS 後，user 確認再開始 T01 之後的 production implementation。

第二個 POC 是 T03：`W007 / W011 / E004 / W017 / W018 / W019 / W020` 都是自然語言行為意圖，不應直接把未驗證 prompt 放進 production scanner。T03 會先用 unsafe/benign corpus 驗證 rubric 與輸出 JSON shape，再讓 T04 實作。

### 6.2 Implementation Acceptance Criteria

| AC | 驗收行為 |
|----|----------|
| AC-S147-POC-1 | T00 在 `poc/S147/` 證明新 `issueCode` schema、舊 JSON fallback、動態 category response 可以同時成立，且沒有 production code diff。 |
| AC-S147-1 | 後端 `SecurityFinding` 可輸出 `issueCode / remediation / confidence`；舊 `ruleId / analyzer` 資料仍能被 report API 讀出。 |
| AC-S147-2 | 靜態 detector 以 clean code class name 輸出 `E005 / E006 / W008 / W012 / W013 / W014`，不再把新規則塞進單一土炮 `PatternScanner`。 |
| AC-S147-3 | T03 POC 用 corpus 證明 semantic rubric 能區分 unsafe 與 benign skill，尤其 `W011` 任意第三方內容、`W007` credential handling、`W017` sensitive data exposure。 |
| AC-S147-4 | LLM/semantic detectors 輸出 `E004 / W007 / W009 / W011 / W017 / W018 / W019 / W020`；沒有 AI credential 時，測試仍能用固定 judgement fixture 驗證 parser 與 mapping。 |
| AC-S147-5 | Detail 頁安全性 tab 改吃 API 回傳的動態 categories；使用者能看到 issue code、分類、檔案/行號、建議修法。 |

### 6.3 Task Plan

| Order | Task | Depends On | Scope |
|-------|------|------------|-------|
| 0 | `2026-05-13-S147-T00-poc-detector-contract.md` | none | POC：schema + analyzer extension + report category contract，不改 production code。 |
| 1 | `2026-05-13-S147-T01-finding-report-contract.md` | T00 + user confirm | 後端 finding schema、issue code enum、report API dynamic categories。 |
| 2 | `2026-05-13-S147-T02-static-detectors.md` | T01 | 靜態 detector batch：`E005 / E006 / W008 / W012 / W013 / W014`。 |
| 3 | `2026-05-13-S147-T03-poc-semantic-rubric.md` | T01 | POC：semantic rubric + corpus，不改 production scanner。 |
| 4 | `2026-05-13-S147-T04-semantic-detectors.md` | T02 + T03 + user confirm | Semantic detector batch：`E004 / W007 / W009 / W011 / W017 / W018 / W019 / W020`。 |
| 5 | `2026-05-13-S147-T05-detail-page-alignment.md` | T04 | 前端 detail 安全性 tab 對齊 dynamic categories 與 issue-code findings。 |

### 6.4 POC Findings

T00（`node poc/S147/contract-poc.mjs`）已在 2026-05-14 執行，輸出：

```text
S147 T00 POC PASS
legacy checks: shell=FAIL, paths=PASS, secrets=WARN, deps=PASS
dynamic categories: Credentials=FAIL, External Content=WARN, Sensitive Data=WARN
detectors: HardcodedSecretDetector, ThirdPartyContentExposureDetector, SensitiveDataExposureDetector
```

T00 驗證結論：

- Design hypothesis verdict: PASS。`issueCode + remediation + confidence` 可以和既有 `ruleId + analyzer` 並存。
- 舊 JSON fallback：成立；legacy findings 仍可生成 `checks.shell/paths/secrets/deps`。
- 動態 categories response：成立；`W008/W011/W017` 可映射到 `Credentials/External Content/Sensitive Data`。
- Detector contract：成立；直接維持 `SecurityAnalyzer` bean contract，不需要再加 adapter。

T01 目前狀態：blocked（task dependency 仍要求 user confirmation after POC）。

T03 尚未執行；完成後要補：

- Semantic rubric verdict
- unsafe / benign corpus 結果
- 需要放進 production `LlmJudge` 或獨立 detector 的 prompt 片段

---

## 7. 研究結論（2026-05-13）

### 7.1 本地掃描器現況（實體依據）

`backend/src/main/java/io/github/samzhu/skillshub/security/scan/engines/PatternScanner.java` 現在只有 8 條 HIGH regex：`rm -rf`、`chmod 777`、`curl|bash`、`wget|sh`、`~/.ssh`、`~/.aws`、`/etc/passwd`、`/etc/shadow`。因此 Snyk 的 `E005` suspicious download URL、`W009` direct financial execution、`W013` system service modification 都不會被穩定打出對應 finding。

`backend/src/main/java/io/github/samzhu/skillshub/security/scan/engines/PromptInjectionScanner.java` 是 STATIC phase regex engine，共 8 條 HIGH + 6 條 MEDIUM rule；它能抓「ignore previous instructions」、「reveal system prompt」、「fake SYSTEM header」、「send token to URL」這類顯性文字，但不會理解「這個 skill 要 agent 去讀論壇留言 / 任意 URL，因此第三方內容可反過來指揮 agent」。

`backend/src/main/java/io/github/samzhu/skillshub/security/scan/engines/LlmJudge.java` 已經存在，會把 Phase 1 findings、frontmatter、SKILL.md、scripts 丟給 Gemini 做語意判斷；但目前 system prompt 是泛用安全審查，只有危險指令、外部 script、description-vs-impl mismatch 等例子，沒有把 Snyk `E004/W007/W009/W011/W013` 逐項翻成判斷 rubric，也沒有 calibrated corpus 來量化誤報。

### RQ-1：PromptInjectionScanner 現況審查

- `PromptInjectionScanner` 不是 LLM；它只適合做 `E004` 的 high-confidence 字串命中，不應承擔 W011。
- `LlmJudge` 才是本專案可承接 W011 的位置；要改的是 prompt rubric、輸出 ruleId taxonomy、測試 corpus，不是再新增一個不受控的第二套 LLM engine。
- `W011` 要求辨識「讀第三方內容」+「內容可能進入 agent context」+「後續 agent 會依內容採取行動」三段同時成立。單純出現 URL、browser、crawl、fetch 都不該直接報 W011，否則 docs/search 類 benign skill 會大量誤報。

### RQ-2：Snyk agent-scan 開源實作深度分析

- Snyk 公開 `agent-scan` CLI / issue code taxonomy，但公開 repo 與 PyPI 文件只保證 CLI 行為、支援 agents、CLI flags、資料送往 Agent Scan API；沒有公開可直接移植的 model prompt、detector internals、Batch Skill Analysis API schema。
- Snyk 官方文件說明 `agent-scan` 會做 local checks + Agent Scan API validation；若要把結果放進自己的 registry，需聯絡 Snyk 取得 designated APIs，不能用一般 Agent Scan API 做大量掃描。
- 可直接借鏡的是分類與產品工作流：Skill Inspector 支援貼 marketplace/GitHub URL 或拖放 skill folder；Tessl 把 scan result 放到 publish、browse、install、remediation 四個節點。

### RQ-3：Regex 可行性評估

| Gap | Regex 可行性 | 建議位置 | 實作邊界 |
|-----|------------|----------|----------|
| E005 Suspicious download URL | 高 | `PatternScanner` | URL shortener、personal file host、download-and-execute、binary archive fetch；只出現官方 docs URL 不報 |
| W013 System service modification | 高 | `PatternScanner` | `systemctl enable/start`、LaunchAgent/LaunchDaemon、crontab persistence、sudoers、startup script |
| W009 Direct financial execution | 中 | `PatternScanner` + `LlmJudge` | regex 只抓「market order / transfer / swap / withdraw / send payment」等窄動詞；投資分析、報表、read-only balance 不報 |
| W007 Insecure credential handling | 中 | `LlmJudge` + `SecretScanner` | `SecretScanner` 抓硬編碼 secret；`LlmJudge` 抓「要求 agent 把 token 原文輸出 / 貼到 prompt / 寫入 log」 |
| W011 Third-party content exposure | 低 | `LlmJudge` | 必須用三段條件判斷；純 regex 不做主 finding，只可提供 evidence hints |
| E004 Prompt injection in skill | 中 | `PromptInjectionScanner` + `LlmJudge` | regex 抓顯性攻擊；LLM 判斷混淆語言、base64/Unicode/分段 instruction、description-vs-behavior mismatch |

### RQ-4：補強優先順序建議

1. 先做 `E005/W013`：跑 `PatternScanner` 就能在 publish 後直接多出明確 finding，成本低，誤報可控。
2. 再做 `W009/W007`：先用窄規則加測試 corpus，讓金融交易與 credential handling 只在「agent 真的能動錢 / 真的會暴露 secret」時報。
3. 最後做 `W011/E004 semantic rubric`：改 `LlmJudge` system prompt 與 `LlmJudgement.RiskClaim.ruleId`，讓結果能輸出 Snyk-like code，例如 `W011_THIRD_PARTY_CONTENT_EXPOSURE`，並用 benign/unsafe corpus 壓誤報。
4. 不建議 MVP 直接串 Snyk API：一般 Agent Scan API 文件明講大規模 registry usage 需 designated API；我們先把自家 scanner taxonomy 對齊，再評估企業版 Snyk/Tessl-like external signal。

### 7.2 Snyk / 市場功能對照

| 產品 | 市面功能 | 對 Skills Hub 的設計啟示 |
|------|----------|--------------------------|
| Snyk Agent Scan CLI 0.5.1 | `uvx snyk-agent-scan@latest --skills` 可掃本機 agent skills；支援 Claude Code、Cursor、Windsurf、Gemini CLI、Codex 等；輸出 human report 或 JSON；MCP 掃描預設需要互動 consent | 我們的 registry publish scan 不應執行 skill；只掃 package content。若未來掃 MCP config，必須加「即將執行 command」的 consent UI 或 sandbox |
| Snyk Skill Inspector | 支援貼 marketplace URL / GitHub repository / 拖放本地 skill folder；主打安裝前檢查 malicious skills、leaked secrets、insecure config | Skills Hub publish step 可加「貼 GitHub URL 匯入並掃描」；detail page 可提供「重新掃描此版本」與 shareable security report |
| Tessl Registry + Snyk | 每個 skill page、搜尋卡片、CLI search/install 都顯示 Security；新 skill publish 前掃；既有 skills backfill；scan async 約 5-15 秒；install high/critical 時 CLI gate；安裝 pin 到已掃描版本 | 我們已有 detail security tab 與 risk level；下一步要把 `scanStatus/scannedAt/ruleIds/versionCommit` 放上 browse card、install/download dialog，避免 user 看到綠燈卻下載未掃的新版本 |
| ClawHub / OpenClaw docs | 開放 publish，但 GitHub 帳號需滿 1 週；提供 public browsing、vector search、versioning、zip downloads、stars/comments、report/moderation hooks；>3 unique reports auto-hide | Skills Hub 可借「report finding / human review queue / abuse auto-hide」；GitHub age check 對企業內部不是核心，但 external import 可做 reputation hint |

### 7.3 建議拆分的 sub-spec

| Spec | 優先 | Size | 內容 | 驗收重點 |
|------|------|------|------|----------|
| `S147a` | P1 | XS | `PatternScanner` 補 `E005` suspicious download URL + `W013` system service modification | 上傳含 `curl https://bit.ly/x -o /tmp/a && chmod +x` 的 skill 後，riskAssessment.findings 有 `E005_*`；含 `systemctl enable` 有 `W013_*` |
| `S147b` | P2 | XS-S | `W009` financial execution narrow detector + benign finance analysis corpus | 「analyze portfolio」不報；「place market order / withdraw USDC」報 MEDIUM |
| `S147c` | P3 | S-M | `LlmJudge` Snyk-like rubric：`E004/W007/W011` 語意判斷、ruleId mapping、corpus 測試 | `Navigate to target URL and follow page instructions` 報 W011；read-only docs URL 不報；明顯 prompt injection 報 E004 |
| `S147d` | P4 | XS | Secret / credential boundary audit：W007 vs W008 分工、前端文案、masking consistency | 硬編碼 token 報 W008；要求 agent 原文輸出 token 報 W007；前端不顯示原始 secret |
| `S147e` | P5 | S | Security signal lifecycle UX：browse card、download/install dialog、scan pending/backfilled/stale version | user 在搜尋卡片與下載前都看得到 `scannedAt`、highest severity、是否為已掃描版本 |

### 7.4 Source-backed facts

- Snyk issue codes：`E004` 是 skill prompt injection；`E005` 是 suspicious download URL；`W007/W008/W009/W011/W012/W013/W014` 分別涵蓋 credential handling、hardcoded secrets、financial execution、third-party content、unverifiable dependency、system service modification、missing SKILL.md。（Source: Snyk agent-scan `docs/issue-codes.md`）
- Snyk CLI：0.5.1 released 2026-05-01；`--skills` 會掃 agent skills；支援 JSON output；local checks 後也會呼叫 Agent Scan API；要把結果放進自己的 registry 需 contact Snyk designated APIs。（Source: PyPI `snyk-agent-scan`）
- Snyk Skill Inspector：2026-02-13 發佈 self-service website；可貼 URL/GitHub repo 或拖放 local folder；檢測 prompt injection、malicious code、suspicious downloads、hardcoded secrets、credential handling、third-party content、unverifiable dependencies、financial access、system modification。（Source: Snyk Labs）
- Tessl：2026-03-17 公告每個 skill 都有 Snyk security score；Batch Skill Analysis API async 約 5-15 秒；結果顯示於 skill page、browse/search、CLI install gate；安裝 pin 到被掃過的版本。（Source: Tessl blog / Snyk partnership blog）
- ClawHub：官方 docs 說明 public registry 提供 browsing、vector search、versioning、zip downloads、stars/comments、moderation hooks、CLI API；publish 開放但 GitHub 帳號需滿一週，>3 unique reports auto-hide。（Source: OpenClaw ClawHub docs）

---

## 8. 整合設計補充（2026-05-13）

### 8.1 現有程式可保留 / 應淘汰

`backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScanOrchestrator.java` 已經是可保留的核心：它下載 zip、建立 `ScanContext`、依 `Phase.STATIC / LLM / META` 跑 `List<SecurityAnalyzer>`、合併 findings、寫入 `skill_versions.risk_assessment`。S147 後續不需要重寫 orchestrator。

`SecurityAnalyzer` 也保留；它就是最小擴充點。後續每個 issue family 直接做成一個 analyzer bean，透過 `skillshub.scanner.engines.{name}.enabled` 開關替換實作。

`PatternScanner` / `SecretScanner` / `PromptInjectionScanner` 可以逐步廢棄為 legacy umbrella scanners。它們現在把多種風險混在同一個 class，命名也不是 Snyk-like issue family；新設計應拆成具體 detector，舊 class 先保留避免一次改太大，等新 detectors 測試補齊後再移除。

### 8.2 建議後端模組形狀

不要新增一層過度抽象的 `SkillRiskDetector` adapter；直接讓每個 detector 實作 `SecurityAnalyzer` 最乾淨，因為現有 orchestrator 已支援多 bean 自動注入。

建議檔案結構：

```text
security.scan
├── SkillIssueCode.java                         # E004 / E005 / W007 / W008 / W009 / W011 / W013
├── SecurityFinding.java                        # 加 issueCode 欄位；保留 ruleId 相容 SARIF
└── detectors
    ├── HardcodedSecretDetector.java            # W008
    ├── CredentialHandlingDetector.java         # W007
    ├── SuspiciousDownloadDetector.java         # E005
    ├── FinancialExecutionDetector.java         # W009
    ├── ThirdPartyContentExposureDetector.java  # W011
    ├── PromptInjectionDetector.java            # E004
    └── SystemServiceModificationDetector.java  # W013
```

命名規則：class name 講「使用者會遇到的風險行為」，不要叫 `W007Scanner`；`issueCode()` / finding.issueCode 則負責對應標準代碼。

### 8.3 W007 / W008 分工

| Issue | Detector | Phase | 判斷方式 | 可替換邊界 |
|------|----------|-------|----------|------------|
| W008 hardcoded secrets | `HardcodedSecretDetector` | STATIC | regex / detector library；輸出遮罩 evidence | 未來可把 regex 換成 gitleaks/trufflehog style engine，只要輸出 `SecurityFinding(issueCode=W008, ruleId=...)` |
| W007 insecure credential handling | `CredentialHandlingDetector` | LLM | 判斷 skill 是否要求 agent 將 secret 原文輸出、寫 log、貼進 prompt、傳給第三方 | 未來可把 LLM prompt 換成規則引擎或外部服務；其他 scanner 不受影響 |

`W007` 不應放進 `SecretScanner`。前者是「處理方式不安全」，後者是「檔案內已含 secret」。兩者在前端可以同屬 `credentials` 區，但後端 detector 必須分開。

### 8.4 Finding schema 相容策略

現在 `SecurityFinding` 只有 `ruleId / severity / message / filePath / line / evidence / analyzer / owaspAst`。建議新增欄位：

```java
SkillIssueCode issueCode;
String remediation;
String confidence; // HIGH / MEDIUM / LOW，可選；先給 LLM 類 finding 用
```

相容策略：新增欄位不要移除舊欄位。`SecurityCategoryMapper` 先用 `issueCode` 分組；若舊資料沒有 `issueCode`，fallback 到現在的 `analyzer + ruleId prefix`。

### 8.5 Detail 頁呈現影響

`frontend/src/pages/SkillDetailPage.tsx` 已在「安全性」tab 呼叫 `useSecurityReport(id)`，所以主要 UI 入口正確。問題在 `frontend/src/api/security.ts` 與 `SecurityTab.tsx` 目前固定 4 格：

```text
shell / paths / secrets / deps
```

後端 `SecurityCategoryMapper` 也只把 `pattern/resource-dos/secret/dep-vuln` 塞進這 4 格；`prompt-injection / llm-judge / meta` 現在全部被忽略。結果是：即使未來 W007 / W011 被掃出來，detail 頁也可能只看到 overall riskLevel，不會看到對應安全檢查卡片。

建議把 API 改成向後相容的 V2：

```ts
interface SecurityReport {
  overall: 'PASS' | 'WARN' | 'FAIL'
  checks: Record<string, SecurityCheck> // 先保留 shell/paths/secrets/deps key
  findings: SecurityFindingSummary[]    // 新增：detail table 用
  categories: SecurityCategorySummary[] // 新增：動態卡片用
}
```

detail 頁卡片建議從 4 格改為 6 格，但保留既有 4 格順序：

```text
Shell / Paths / Credentials / Dependencies / Prompt Safety / External Content
```

mapping：

| UI category | Issue codes |
|-------------|-------------|
| Shell | E005, W013, E006, resource DoS |
| Paths | sensitive path access |
| Credentials | W007, W008 |
| Dependencies | W012, dependency vulnerabilities |
| Prompt Safety | E004 |
| External Content | W011 |

`SecurityHeroCard` 與 `SecurityTab` 不應硬寫 `QUAD_ORDER`，改吃 API 回傳的 `categories`。這樣未來新增 W019 / W020 不需要改主要版面，只多一張 category card。

### 8.6 推薦遷移順序

1. 後端先新增 `SkillIssueCode` + `issueCode` 欄位，保留舊 `ruleId/analyzer`。
2. 新增 `SuspiciousDownloadDetector` / `SystemServiceModificationDetector`，先不刪 `PatternScanner`。
3. 把 `SecretScanner` 重命名或包裝成 `HardcodedSecretDetector`，輸出 `W008`。
4. 新增 `CredentialHandlingDetector`，先使用 `LlmJudge` 同一個 ChatClient，但獨立 prompt rubric，輸出 `W007`。
5. 更新 `SecurityCategoryMapper` 與 `/security-report`，讓 W007/W008 顯示在 Credentials。
6. 前端 `SecurityReport` 改為動態 categories，detail 安全性 tab 顯示 issue code、檔案、行號、建議修法。
7. 新 detectors 覆蓋既有案例後，移除 legacy umbrella scanners 或只保留為 compatibility adapter。

---

## 9. Agent Scan 全 Issue Code 採用分析（2026-05-13）

`https://raw.githubusercontent.com/snyk/agent-scan/main/docs/issue-codes.md` 列出的所有 code 可以分三種：Skill Hub 現在就該掃、Skill Hub 可轉譯成 skill 行為掃、未來支援 MCP inventory / installed-agent scan 時再掃。

### 9.1 Skill issue codes：現在就適合納入 Skill Hub

| Code | Snyk 名稱 | Skill Hub detector class | Phase | 採用建議 | 前端 category |
|------|-----------|--------------------------|-------|----------|---------------|
| E004 | Prompt injection in skill | `PromptInjectionDetector` | STATIC + LLM | 立即納入。先保留現有 regex，再加 LLM rubric 抓混淆語言、分段指令、base64/Unicode。 | Prompt Safety |
| E005 | Suspicious download URL in skill | `SuspiciousDownloadDetector` | STATIC | 立即納入。抓 URL shortener、personal file host、下載後執行、未 pin binary/script。 | Shell |
| E006 | Malicious code patterns in skill | `MaliciousCodePatternDetector` | STATIC + META | 納入但不要做成一個大雜燴。用 META 匯總多個高風險 signal，例如 exfil + obfuscation + persistence。 | Shell / Prompt Safety |
| W007 | Insecure credential handling in skill | `CredentialHandlingDetector` | LLM | 立即納入。判斷「要求 agent 把 secret 原文輸出、寫 log、貼到 prompt、傳給第三方」。 | Credentials |
| W008 | Hardcoded secrets in skill | `HardcodedSecretDetector` | STATIC | 立即納入。現有 `SecretScanner` 可改名或包裝成此 detector。 | Credentials |
| W009 | Direct financial execution capability | `FinancialExecutionDetector` | STATIC + LLM | 納入。只在付款、提款、轉帳、下 market order 等直接動錢時報；投資分析不報。 | Financial Actions |
| W011 | Exposure to untrusted third-party content | `ThirdPartyContentExposureDetector` | LLM | 納入。三段條件：讀第三方內容、內容進 agent context、agent 會依內容行動。 | External Content |
| W012 | Unverifiable external dependency | `UnverifiableExternalDependencyDetector` | STATIC | 納入。比 E005 更精準：runtime fetch 的內容會控制 prompt 或執行 code。 | Dependencies |
| W013 | System service modification | `SystemServiceModificationDetector` | STATIC | 納入。抓 systemd、LaunchAgent、cron、sudoers、startup scripts、root/system-wide config。 | System Changes |
| W014 | Missing SKILL.md file | `SkillManifestPresenceValidator` | STATIC | 已由 publish validation 部分覆蓋，但 security report 可用 low severity 顯示「掃描不完整」。 | Package Structure |

### 9.2 MCP / toxic-flow codes：轉譯後才適合 Skill Hub

Skill Hub 目前掃的是 skill zip，不會啟動 MCP server，也不 inventory 使用者本機 agent config。因此 MCP code 不該原樣照搬，但有些可以轉成「skill instructions 宣告的 agent 行為風險」。

| Code | Snyk 名稱 | Skill Hub detector class | 採用方式 | 理由 |
|------|-----------|--------------------------|----------|------|
| E001 | Prompt injection in tool description | `McpToolPromptInjectionDetector` | Future: 只有當 skill bundle 內含 MCP tool metadata 或 MCP config 時才掃。 | 純 SKILL.md 沒有 tool description source。 |
| E002 | Cross-server tool reference | `McpToolShadowingDetector` | Future: 需要 MCP server inventory。 | 需要知道多個 MCP server 的 tool namespace，現在 Skill Hub 沒這個資料。 |
| W001 | Suspicious words in tool description | `SuspiciousToolDescriptionDetector` | Future / low priority。 | 容易誤報，且不是 skill bundle 的核心欄位。 |
| W015 | Untrusted content detected | `UntrustedContentExposureDetector` | Adapt: 可和 W011 合併或作為 W011 的 high-confidence 子規則。 | Skill 指示 agent 讀 public website、social posts、forum comments 時可掃。 |
| W016 | Potential untrusted content detected | `PotentialUntrustedContentDetector` | Adapt: 作為 W011 low-confidence finding，不單獨做第一版。 | 和 W011 差別是 confidence，不是不同業務概念。 |
| W017 | Sensitive data exposure | `SensitiveDataExposureDetector` | Adapt: 納入。掃 skill 是否明確整合 email、DM、banking、credential vault、private chat history。 | User 指出的命名正確：class 應該叫 `SensitiveDataExposureDetector`，不是 `W017Scanner`。 |
| W018 | Workspace data exposure | `WorkspaceDataExposureDetector` | Adapt: 納入。掃 skill 是否讀 source code、workspace files、local notes、project docs。 | 這是 Skill Hub 企業場景很重要，屬於 IP / proprietary code exposure。 |
| W019 | Destructive capabilities | `SharedResourceModificationDetector` | Adapt: 納入但拆清楚。共享基礎設施、team SaaS、repo、DB、cloud resources 才報 W019。 | 和 W013 的 system service 不同，這是「影響團隊資源」。 |
| W020 | Local destructive capabilities | `LocalDataModificationDetector` | Adapt: 納入。刪 local files、覆寫 settings、改 workspace state。 | 和 W019 差在 blast radius，前端可同屬 Destructive Actions。 |

### 9.3 不要一個 code 一張表，但要一個行為一個 detector

Detector class 命名原則：

| 好命名 | 不建議命名 | 原因 |
|--------|------------|------|
| `SensitiveDataExposureDetector` | `W017Detector` | class 名直接說明風險行為；code 放在 `issueCode` 欄位。 |
| `WorkspaceDataExposureDetector` | `WorkspaceScanner` | exposure 是風險，workspace 是資料來源；名稱要包含行為。 |
| `CredentialHandlingDetector` | `SecretOutputScanner` | W007 不只 output，也可能是 log、prompt、第三方提交。 |
| `UnverifiableExternalDependencyDetector` | `UrlScanner` | W012 的重點是 runtime dependency 可改變 agent 行為，不是 URL 本身。 |
| `SharedResourceModificationDetector` | `DestructiveScanner` | W019 需要和 W020 分開，因為共享資源影響更大。 |

### 9.4 建議第一版 detector 清單

第一版不要一次做全部 19 個 code 的完整準確度；先把 report schema 與 detector 邊界做好，然後分批補規則。

| Batch | Detector classes | 目的 |
|-------|------------------|------|
| Batch 1 | `HardcodedSecretDetector`, `SuspiciousDownloadDetector`, `SystemServiceModificationDetector`, `SkillManifestPresenceValidator` | 低成本靜態規則，先替換現有土炮 scanner 的清楚部分。 |
| Batch 2 | `CredentialHandlingDetector`, `ThirdPartyContentExposureDetector`, `PromptInjectionDetector` | LLM rubric，解 Snyk 最大差距：自然語言行為意圖。 |
| Batch 3 | `SensitiveDataExposureDetector`, `WorkspaceDataExposureDetector`, `FinancialExecutionDetector` | 企業場景最重要：email/credential/private code/financial operation 進 agent context。 |
| Batch 4 | `UnverifiableExternalDependencyDetector`, `LocalDataModificationDetector`, `SharedResourceModificationDetector`, `MaliciousCodePatternDetector` | 補 supply chain、destructive action、META 彙整升級。 |
| Future MCP | `McpToolPromptInjectionDetector`, `McpToolShadowingDetector`, `SuspiciousToolDescriptionDetector` | 等 Skill Hub 支援 MCP config / installed agent inventory，再納入。 |

### 9.5 Detail 頁 category 更新

目前 detail 頁只有 `Shell / Paths / Secrets / Deps`。完整 adoption 後建議改成這 8 類，仍維持可掃讀：

| UI category | Issue codes | 顯示文字 |
|-------------|-------------|----------|
| Prompt Safety | E001, E004, W001 | Prompt 注入 |
| External Content | W011, W015, W016 | 第三方內容 |
| Credentials | W007, W008 | 憑證處理 |
| Sensitive Data | W017, W018 | 敏感資料 |
| Downloads & Dependencies | E005, W012, dependency CVE | 下載與依賴 |
| Destructive Actions | W019, W020, W013 | 破壞性操作 |
| Financial Actions | W009 | 金融操作 |
| Package Structure | W014, metadata validation | 套件結構 |

`SecurityTab` 可顯示這 8 類卡片；`SecurityHeroCard` 只顯示 overall + 最嚴重前 1 個 category，避免 detail hero 太吵。
