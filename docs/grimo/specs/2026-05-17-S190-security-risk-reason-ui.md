# S190 — Security Risk Reason UI

> SpecID: S190
> Date: 2026-05-17
> Status: ✅ QA PASS — ready for $shipping-release S190
> Type: Full-stack UX clarity spec
> Estimate: S(11)
> Depends: S147 ✅, S183 ✅, S142b ✅
> Prototype: [Skills Hub Security Reason UI](../ui/prototype/Skills%20Hub%20Security%20Reason%20UI.html)

---

## §1 Goal

`低風險` 但 `0 findings` 的 skill detail 安全頁要說清楚「為什麼是低風險、掃描有沒有抓到問題、使用者下一步能做什麼」。

本 spec 也收斂風險等級語言。`RiskLevel` 是整個 skill package 的總標籤；`finding` 是 scanner 找到的可修 issue；`riskReason` 是告訴使用者「為什麼這個總標籤不是 NONE」的解釋。這三個詞不能混用。

使用者看到 `transcribe-video` 這類 skill 時，現在畫面只顯示：

```text
目前等級：低風險
高風險 findings：0
中風險 findings：0
低風險 findings：0
掃描發現：沒有需要處理的掃描發現
```

實際資料是：

```text
SKILL.md 有 allowed-tools: Read / Edit / Write / Bash
package 有 scripts/check_deps.sh、scripts/transcribe.py、scripts/youtube_fetch.sh
scanner 沒抓到 issue code / file:line finding
```

所以產品語意應該是：

```text
安全等級：低風險
原因：此技能宣告可使用 Bash/Write/Edit，且套件包含 scripts/
掃描發現：0 筆，沒有需要修改的檔案或行號
建議動作：可下載；若你不接受 Bash/scripts 能力，先查看檔案或回報疑慮
```

2026-05-17 production case：

```text
skillId=56c9fb3e-b5ac-4f76-8489-904f3394520d
name=springboot-project-architect
Cloud Run log: Scan completed ... level=LOW, findings=0
zip entries: SKILL.md + references/*.md，沒有 scripts/
SKILL.md frontmatter: allowed-tools=[Read, Glob, Grep, Bash, Write, Edit, WebFetch, WebSearch]
```

這筆不是 scanner failure，也不是前端翻譯錯；它是「0 findings + 有 allowed-tools → LOW」的正常分級。但 UI 缺少 `allowed-tools` 造成 LOW 的說明，文件也有一處仍把純 `SKILL.md` 說成 LOW，導致使用者看起來像「沒有風險卻被硬標低風險」。

非工程師看到的句子應該像這樣：

```text
低風險
掃描沒有找到需要修改的問題。不過這個技能可以要求 AI 使用這些工具，所以使用前請先確認你接受這些能力：
Read、Glob、Grep、Bash、Write、Edit、WebFetch、WebSearch
```

畫面可以在旁邊補小字 `allowed-tools`，但不能只顯示 `allowed-tools` 這個欄位名；要先把它翻成「這個技能可以要求 AI 使用哪些工具」。

### Dependency Status

| Spec | 狀態 | 是否阻擋 |
|---|---|---|
| S147 | ✅ shipped v4.59.0 | Code-level dependency。已建立 issue-code `findings[]` 與掃描分類。 |
| S183 | ✅ shipped v4.65.0 | UI dependency。已把安全頁改成 risk lights + finding 明細；S190 在此基礎補原因區塊。 |
| S142b | ✅ shipped v4.1.0 | API dependency。`GET /security-report` 已回傳 `checks/categories/findings`；S190 擴充 response。 |
| S187/S189 | 📐 in-design | Ordering-only。S190 不碰 edit page 或 browse search contract。 |

### Out Of Scope

| 項目 | 理由 |
|---|---|
| 改風險分級規則 | `LOW + 0 findings` 目前是正確規則：有 scripts 或 allowed-tools，但沒有具體 issue。 |
| 新增 scanner issue code | 本 spec 只解釋現有掃描結果，不新增偵測規則。 |
| SARIF viewer | 現有 finding row 已顯示 issue code / file:line / evidence / remediation。 |
| 重新掃描所有舊版本 | 新 scan 會寫 `riskReasons`；舊資料由 `SkillVersion.allowedTools` fallback。 |
| 安全審核 queue 大改 | 三動作只在 skill detail 安全頁給 consumer/reviewer 下一步，不改審核流程。 |
| 全站文案大改 | 本 spec 只同步會造成風險等級誤解的文件句子；審核佇列、24 小時、reviewer approve 等發佈文案整理留 S191。 |

---

## §2 Research And Design

### §2.0 Canonical Risk Language

| Term | Canonical meaning | User-visible wording | Not this |
|---|---|---|---|
| `RiskLevel` / 風險等級 | 整個 skill package 的總分級，由 `findings`、`scripts/`、`allowed-tools` 共同決定。 | 未發現風險 / 低風險 / 中風險 / 高風險 | 不是 finding count，也不是安全保證。 |
| `finding` / 掃描發現 | scanner 找到的具體 issue，通常有 issue code、file:line、evidence、remediation。 | 掃描發現 / 需要修改的項目 | 不是每個低風險原因都會有 finding。 |
| `riskReason` / 風險原因 | 解釋整體 `RiskLevel` 的來源；可能來自 allowed-tools、scripts/ 或 findings。 | 為什麼是低風險？ / 風險原因 | 不是新的 scanner issue code，不一定有檔案行號可修。 |
| `capability declaration` / 能力宣告 | `allowed-tools` 或 `scripts/` 讓 skill 可以要求 agent 使用工具或執行本機程式。 | 宣告工具能力 / 包含可執行腳本 | 不是已證明的惡意行為。 |

Canonical tier definitions：

| Tier | Predicate | Meaning | Empty finding copy |
|---|---|---|---|
| `NONE` / 未發現風險 | `findings=[]` 且沒有 `scripts/` 且沒有 `allowed-tools` | scanner 沒抓到 issue，且 package 沒宣告額外工具/腳本能力。 | `未發現安全問題`；仍要保留「不代表 100% 安全」的 caveat。 |
| `LOW` / 低風險 | `findings=[]` 但有 `scripts/` 或 `allowed-tools`；或 findings 全為 LOW severity | 有能力宣告，或只有低嚴重度 finding；目前沒有需要阻擋下載的具體 issue。 | `沒有需要修改的掃描發現`，並顯示 `riskReasons[]`。 |
| `MEDIUM` / 中風險 | findings 最高 severity 是 MEDIUM | scanner 找到中等嚴重度 issue；使用前應先看 finding。 | 不適用；應有 finding reason。 |
| `HIGH` / 高風險 | findings 最高 severity 是 HIGH | scanner 找到高嚴重度 issue；使用前應先看 file:line 與修法。 | 不適用；應有 finding reason。 |

Language rules：

1. `NONE` 對外 label 改為「未發現風險」；旁邊必須能看到「scanner 未發現 known patterns，不代表 100% 安全」。
2. `LOW + findings=[]` 不可顯示成「未發現安全問題」；要顯示「沒有需要修改的掃描發現」並列出 allowed-tools/scripts 原因。
3. `allowed-tools` / `scripts/` 是能力原因，不是 finding；UI 不應要求作者「修掉」它，除非 policy 後續另定。
4. docs 中「只有 SKILL.md」的最小 skill 應對應 `NONE`，不是 `LOW`。
5. UI 文案要給非工程師看得懂：先說「這個技能可以要求 AI 使用哪些工具」，再列 `Read/Bash/Write/...`；`allowed-tools` 只能當補充技術欄位，不可當主文案。

### §2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
|---|---|---|
| [PRD.md P3](../PRD.md) | 純 markdown skill 要記錄掃描項目與通過/未通過；危險指令要列 issue code、檔案/行號、修法。 | `0 findings` 也要有「掃了什麼/為什麼低」的可讀說明。 |
| [glossary.md](../glossary.md) | `RiskLevel` 已定義為 NONE / LOW / MEDIUM / HIGH；`RiskReason` 已定義為與 finding 分開的可讀原因。 | S190 不需另造新詞，只要把現有詞落到 API/UI/docs。 |
| [ScanOrchestrator.java](../../../backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScanOrchestrator.java) | `findings.isEmpty()` 且有 scripts 或 allowed-tools → `RiskLevel.LOW`。 | LOW 不是 finding severity，而是 capability declaration 的結果。 |
| [RiskLevel.java](../../../backend/src/main/java/io/github/samzhu/skillshub/security/RiskLevel.java) | `NONE` 明確是 `0 findings + 無 scripts/ + 無 allowed-tools`；`LOW` 明確可由 capability declaration 造成。 | 等級定義以後端 enum/Javadoc 為主，文件要跟它對齊。 |
| [SkillVersion.java](../../../backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersion.java) | `allowedTools` 已存在於 `skill_versions.allowed_tools`；`risk_assessment` 是 JSONB map，可加欄位。 | 不需 DB migration；response 可從既有欄位與新 JSONB key 組合。 |
| [SecurityTab.tsx](../../../frontend/src/components/v2/tabs/SecurityTab.tsx) | 目前空 findings 時只顯示 `沒有需要處理的掃描發現`。 | 要在空狀態上方新增「風險原因」與「三動作」。 |
| [RiskTiersPage.tsx](../../../frontend/src/pages/docs/RiskTiersPage.tsx) | NONE 的定義已接近後端：只有 SKILL.md、無 scripts/、無 allowed-tools。 | 這頁可作為 canonical docs，但需檢查 MEDIUM/HIGH 文案是否與 S191 分工衝突。 |
| [YourFirstSkillPage.tsx](../../../frontend/src/pages/docs/YourFirstSkillPage.tsx) | 最小 skill callout 仍寫「auto-publish 為 LOW risk — 因為沒有 scripts/」。 | 文件已過時；S190 docs sync 應改成 `NONE`，否則使用者會繼續混淆。 |
| Production log 2026-05-17T15:22:15Z | `springboot-project-architect` 掃描完成 `level=LOW, findings=0`；zip 無 scripts，但 frontmatter 有 allowed-tools。 | S190 必須支援「allowed-tools-only LOW + 0 findings」這條路徑，不只 transcribe-video 的 scripts case。 |
| [Open Agent Skills specification](https://openagentskills.dev/docs/specification) | `scripts/` 是可選目錄，`allowed-tools` 是可選 pre-approved tools；支援程度依 agent 實作不同。 | UI 要把 scripts/allowed-tools 當成「能力原因」，不是掃描問題。 |
| [agentskills.io specification](https://agentskills.io/specification) | `scripts/` 包含 agent 可執行程式；`allowed-tools` 是實驗性欄位。 | `Bash/Write/Edit` 應以「此技能可要求代理做這些事」呈現。 |
| [OWASP LLM Top 10](https://owasp.org/www-project-top-10-for-large-language-model-applications) | LLM 風險包含 Prompt Injection、Sensitive Information Disclosure、Excessive Agency。 | 三動作要讓使用者能看檔案、下載、回報；不要把 LOW 說成完全安全。 |
| [Snyk Agent Scan issue codes](https://raw.githubusercontent.com/snyk/agent-scan/main/docs/issue-codes.md) | Snyk 把 issue code 與能力型 warning 分開；例如 W019/W020 描述 destructive capabilities。 | Skills Hub 也應分開「具體 findings」與「能力原因」。 |
| [Snyk Agent Scan JSON output](https://raw.githubusercontent.com/snyk/agent-scan/main/docs/json-output.md) | JSON report 以 `issues[]` 表示 policy violations，沒有 issue 時仍可表示 scan clean。 | `findings=[]` 只能代表沒有具體 issue，不代表沒有能力風險。 |

### §2.2 Existing API Contract

目前前端 type：

```ts
interface SecurityReport {
  skillId: string
  skillVersionId: string
  skillVersion: string
  scannedAt: string
  engineVersion: string
  ruleSetVersion: string
  overall: 'PASS' | 'WARN' | 'FAIL'
  checks: Record<string, SecurityCheck>
  categories: SecurityCategorySummary[]
  findings: SecurityFindingSummary[]
}
```

新增欄位：

```ts
interface SecurityRiskReason {
  code:
    | 'NO_FINDINGS_NO_CAPABILITIES'
    | 'ALLOWED_TOOLS_DECLARED'
    | 'SCRIPTS_INCLUDED'
    | 'FINDINGS_PRESENT'
    | 'LEGACY_ALLOWED_TOOLS'
  label: string
  detail: string
  impact: 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH'
  evidence: string[]
  action: 'DOWNLOAD_OK' | 'REVIEW_FIRST' | 'FIX_REQUIRED'
}

interface SecurityReport {
  // existing fields...
  riskReasons: SecurityRiskReason[]
}
```

範例 response：

```json
{
  "skillId": "9e88d2e3-0346-40da-a40b-23304031e0c1",
  "skillVersion": "1",
  "overall": "PASS",
  "findings": [],
  "riskReasons": [
    {
      "code": "ALLOWED_TOOLS_DECLARED",
      "label": "這個技能可以要求 AI 使用工具",
      "detail": "掃描沒有找到需要修改的問題。不過這個技能可以要求 AI 使用 Bash、Write、Edit，所以使用前請先確認你接受這些能力。",
      "impact": "LOW",
      "evidence": ["Bash", "Write", "Edit"],
      "action": "REVIEW_FIRST"
    },
    {
      "code": "SCRIPTS_INCLUDED",
      "label": "包含可執行腳本",
      "detail": "package 內含 scripts/；這代表技能可能要求 agent 執行本機程式。",
      "impact": "LOW",
      "evidence": ["scripts/check_deps.sh", "scripts/transcribe.py", "scripts/youtube_fetch.sh"],
      "action": "REVIEW_FIRST"
    }
  ]
}
```

### §2.3 User Journey Simulation

| Scenario | Given（前提） | When（動作） | Then（結果） |
|---|---|---|---|
| A. `transcribe-video` LOW + 0 findings | skill 有 `allowed-tools` 與 `scripts/`，但 `findings=[]` | 使用者打開安全性 tab | 看到「低風險」原因是 Bash/Write/Edit + scripts；掃描發現區仍顯示 0 筆。 |
| B. `springboot-project-architect` LOW + 0 findings | skill 無 scripts，但有 `allowed-tools=[Read, Glob, Grep, Bash, Write, Edit, WebFetch, WebSearch]` | 使用者打開安全性 tab | 看到「掃描沒有找到需要修改的問題。不過這個技能可以要求 AI 使用 Read、Glob、Grep、Bash、Write、Edit、WebFetch、WebSearch」；掃描發現區仍顯示 0 筆。 |
| C. 純文件 skill NONE + 0 findings | skill 只有 `SKILL.md`，無 scripts、無 allowed-tools | 使用者打開安全性 tab | 看到「未發現風險」原因是沒有工具宣告與 scripts；主動作仍是下載。 |
| D. HIGH + findings | skill 有 `W008` hardcoded secret | 使用者打開安全性 tab | 先看到高風險原因與「先修正」動作，再看到 `file:line` 與修法。 |

### §2.4 Approach Comparison

| Approach | 改哪裡 | 使用者會看到什麼 | 成本 / 風險 | Recommendation |
|---|---|---|---|---|
| A. 後端回 `riskReasons[]`，前端呈現 | `ScanOrchestrator.persist` 寫 JSONB；`SecurityReportResponse` 擴欄位；`SecurityTab` 顯示原因與三動作 | LOW + 0 findings 會顯示「Bash/scripts 造成低風險，但沒有要修的 issue」 | S 級；舊資料需 fallback | ⭐ Recommended |
| B. 只改前端文案 | 只改 `SecurityTab.tsx` empty state | 顯示通用句：「低風險可能來自工具或 scripts」 | XS；但無法列 Bash/Write/scripts 名稱，容易繼續困惑 |  |
| C. 把 LOW + 0 findings 改成 NONE | `ScanOrchestrator.classifyRiskLevel` | 畫面不再困惑，但有 scripts/allowed-tools 的 skill 會被標成未發現風險 | 破壞 S096c/S183 語意；不採用 |  |

Decision：採 Approach A。`riskReasons[]` 是 Security Report 的解釋層，不是新的 scanner finding。Finding 負責「哪個檔案哪一行要改」；Reason 負責「整體等級為何不是 NONE」。

### §2.5 Low-Fidelity UI Sketches

這是 layout contract，不是最終像素、不新增 design system、不允許加無關裝飾。HTML prototype 已放在：

```text
docs/grimo/ui/prototype/Skills Hub Security Reason UI.html
```

Desktop:

```text
安全性 tab

掃描摘要
┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐
│ 目前等級   │ │ 高風險      │ │ 中風險      │ │ 低風險      │
│ 低風險     │ │ 0           │ │ 0           │ │ 0           │
└────────────┘ └────────────┘ └────────────┘ └────────────┘

為什麼是低風險？
┌─────────────────────────────────────────────────────────────┐
│ LOW  這個技能可以要求 AI 使用工具                            │
│ 掃描沒有找到需要修改的問題。不過這個技能可以要求 AI 使用這些工具。│
│ Bash  Write  Edit                                           │
└─────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────┐
│ LOW  包含可執行腳本                                         │
│ package 內含 scripts/；這代表技能可能要求 agent 執行本機程式。 │
│ scripts/check_deps.sh  scripts/transcribe.py                 │
└─────────────────────────────────────────────────────────────┘

你可以做什麼？
[下載技能] [查看檔案] [回報疑慮]

掃描發現
沒有需要修改的掃描發現。這表示 scanner 沒有找到 issue code，不代表技能沒有任何能力風險。
```

Mobile:

```text
[掃描摘要 cards stacked]
[為什麼是低風險？ reason cards stacked]
[下載技能]
[查看檔案]
[回報疑慮]
[掃描發現 empty state]
```

### §2.6 Design Decisions

| Decision | 結論 | 原因 |
|---|---|---|
| Reason 不等於 Finding | `riskReasons[]` 與 `findings[]` 分開 | Finding 有 issue code / file:line / remediation；Reason 解釋等級來源，可能沒有修法。 |
| Reason 存在 JSONB | 新 scan 在 `risk_assessment.riskReasons` 寫入 | `risk_assessment` 本來就是掃描 read model；不需 DB migration。 |
| 舊資料 fallback | `SecurityReportService` 若 JSONB 無 `riskReasons`，用 `SkillVersion.allowedTools` 與 `findings` 補最小原因 | 讓已掃描的 production skill 不必全部重掃，也能解釋 `allowed-tools`。 |
| 三動作固定 | `下載技能`、`查看檔案`、`回報疑慮` | 對應 consumer 的三個實際下一步；不把 LOW 變成 blocker。 |
| 不在 Hero 塞長文 | Hero 繼續只顯示風險燈；詳細原因放安全性 tab | Header 保持掃描用；細節在 tab 內解釋。 |

### §2.7 Estimate

| Dimension | Score | Rationale |
|---|---:|---|
| Tech risk | 2 | 後端 JSONB 擴欄位 + frontend render，無新 dependency。 |
| Uncertainty | 2 | 舊資料 fallback 只能列 allowedTools，無法知道舊 scan 的 scripts 檔名；新 scan 可完整。 |
| Dependencies | 2 | 依賴 S147/S183/S142b shipped code。 |
| Scope | 2 | 觸及 scan persist、report DTO/service、frontend API type、SecurityTab。 |
| Testing | 2 | 需要 backend service/orchestrator tests + frontend component tests。 |
| Reversibility | 1 | Response additive；不改 DB schema、不破壞舊 caller。 |
| Total | 11 | S-sized spec。 |

---

## §3 Acceptance Criteria

驗證命令：

```bash
cd backend && ./gradlew test
cd frontend && npm test
cd frontend && npm run verify
```

Pass：所有帶 `AC-S190-*` 的 backend/frontend 測試都是綠燈，且 TypeScript / lint 沒有錯。

### AC-S190-1 — LOW + allowed-tools + scripts 顯示原因而不是只顯示空 findings

Given（前提）`transcribe-video` 版本的 `riskLevel=LOW`、`findings=[]`、`allowedTools=["Read","Edit","Write","Bash"]`，且新 scan 寫入 `riskReasons` 含 scripts

When（動作）使用者打開 Skill detail 的「安全性」tab

Then（結果）畫面顯示 `目前等級` 為 `低風險`

And（而且）顯示 `為什麼是低風險？`

And 顯示 `這個技能可以要求 AI 使用工具`

And 顯示 `掃描沒有找到需要修改的問題`

And 顯示 `使用前請先確認你接受這些能力`

And 顯示 `Bash`、`Write`、`Edit`

And 顯示 `包含可執行腳本`

And 顯示至少一個 `scripts/` 檔名。

Verification：Frontend component test + backend response test。

### AC-S190-1b — allowed-tools-only LOW 要用非工程師文案說明

Given（前提）`springboot-project-architect` 版本的 `riskLevel=LOW`、`findings=[]`、`allowedTools=["Read","Glob","Grep","Bash","Write","Edit","WebFetch","WebSearch"]`，且沒有 scripts

When（動作）使用者打開 Skill detail 的「安全性」tab

Then（結果）畫面顯示 `低風險`

And 顯示 `掃描沒有找到需要修改的問題`

And 顯示 `這個技能可以要求 AI 使用工具`

And 顯示 `Read`、`Glob`、`Grep`、`Bash`、`Write`、`Edit`、`WebFetch`、`WebSearch`

And 不只顯示 `allowed-tools` 這個工程欄位名。

Verification：Frontend component test + backend response test。

### AC-S190-2 — LOW + 0 findings 的掃描發現空狀態不再讓人誤會成未發現風險

Given `riskLevel=LOW` 且 `findings=[]`

When 使用者查看「掃描發現」

Then 畫面顯示 `沒有需要修改的掃描發現`

And 顯示 `scanner 沒有找到 issue code，不代表技能沒有任何能力風險`

And 不顯示 `未發現安全問題`。

Verification：Frontend component test。

### AC-S190-3 — NONE + 0 findings 顯示純文件原因

Given skill 只有 `SKILL.md`，沒有 scripts，沒有 allowed-tools，且 `riskLevel=NONE`

When 使用者打開「安全性」tab

Then 畫面顯示 `未發現風險`

And 顯示 `沒有工具宣告或 scripts/`

And 顯示 `未發現安全問題`。

Verification：Backend response test + Frontend component test。

### AC-S190-4 — HIGH + findings 仍以修法明細為主

Given security report 有一筆 `W008` HIGH finding，包含 `filePath=scripts/use-openai.sh`、`line=3`、`remediation`

When 使用者打開「安全性」tab

Then 畫面顯示 `高風險`

And `掃描發現` 區塊顯示 `W008`

And 顯示 `scripts/use-openai.sh:3`

And 顯示 `修法：...`

And 三動作區顯示主要建議 `先查看掃描發現`。

Verification：Frontend component test。

### AC-S190-5 — `GET /security-report` additive response contains `riskReasons`

Given latest skill version has `risk_assessment` JSONB containing `riskReasons`

When frontend calls `GET /api/v1/skills/{id}/security-report`

Then response body contains existing `findings`

And contains `riskReasons` array

And each item contains `code`、`label`、`detail`、`impact`、`evidence`、`action`。

Verification：Backend controller/service test。

### AC-S190-6 — Legacy report fallback uses `SkillVersion.allowedTools`

Given existing production row has `risk_assessment.findings=[]` but no `risk_assessment.riskReasons`

And `skill_versions.allowed_tools` contains `Bash` and `Write`

When `GET /security-report` is called

Then response contains `riskReasons[0].code=LEGACY_ALLOWED_TOOLS`

And `riskReasons[0].evidence` contains `Bash` and `Write`。

Verification：Backend service test。

### AC-S190-7 — New scan persists script reason into `risk_assessment`

Given uploaded package contains `scripts/check_deps.sh` and `scripts/transcribe.py`

When `ScanOrchestrator` persists the scan result

Then `risk_assessment.riskReasons` contains a `SCRIPTS_INCLUDED` item

And its `evidence` contains both script paths.

Verification：Backend scanner integration/unit test。

### AC-S190-8 — 三動作區對 LOW 不阻擋下載

Given `riskLevel=LOW` and `findings=[]`

When 使用者查看三動作區

Then 第一個 action 顯示 `下載技能`

And 第二個 action 顯示 `查看檔案`

And 第三個 action 顯示 `回報疑慮`

And `下載技能` 不顯示 disabled 狀態。

Verification：Frontend component test。

### AC-S190-9 — HTML prototype exists for reviewer comparison

Given repo checkout

When reviewer opens `docs/grimo/ui/prototype/Skills Hub Security Reason UI.html`

Then the file shows LOW + 0 findings, NONE + 0 findings, and HIGH + findings examples.

Verification：Inspection。

### AC-S190-10 — 文件中的風險等級定義跟後端一致

Given repo checkout

When reviewer searches docs/source for `LOW risk`、`無風險`、`未發現風險`、`風險層級`、`allowed-tools`、`scripts/`

Then `YourFirstSkillPage` 不再宣稱「只有 SKILL.md、沒有 scripts/」會是 LOW

And docs 明確說 `NONE = findings=[] + no scripts/ + no allowed-tools`

And docs 明確說 `LOW + findings=[]` 可能來自 `allowed-tools` 或 `scripts/`

And user-facing docs/components 不再把 `NONE` label 寫成 `無風險`，而是 `未發現風險`

And docs 不把 `finding`、`riskReason`、`RiskLevel` 混成同一件事。

Verification：Docs source scan + reviewer inspection。

### NFR Coverage

| Category | AC | 說明 |
|---|---|---|
| Performance | AC-S190-5 | Response 只讀 `skill_versions` row 與 JSONB，不在 GET 時下載 zip。 |
| Security | AC-S190-2, AC-S190-4 | 不把 LOW 說成安全保證；finding evidence/remediation 仍以純文字 render。 |
| Reliability | AC-S190-6 | 舊資料沒有 `riskReasons` 也能 fallback，不 crash。 |
| Usability | AC-S190-1, AC-S190-8, AC-S190-9, AC-S190-10 | 使用者看到原因、三動作、prototype；文件語言不再互相打架。 |
| Maintainability | AC-S190-5, AC-S190-7 | Reason schema 有固定 code/action/impact，後續 detector 可加新 reason。 |

### AC Well-Formedness Check

| AC | Singular | Unambiguous | Implementation-free | Verifiable | Bounded |
|---|---|---|---|---|---|
| AC-S190-1 | ✅ | ✅ | ✅ | ✅ | ✅ |
| AC-S190-2 | ✅ | ✅ | ✅ | ✅ | ✅ |
| AC-S190-3 | ✅ | ✅ | ✅ | ✅ | ✅ |
| AC-S190-4 | ✅ | ✅ | ✅ | ✅ | ✅ |
| AC-S190-5 | ✅ | ✅ | ✅ | ✅ | ✅ |
| AC-S190-6 | ✅ | ✅ | ✅ | ✅ | ✅ |
| AC-S190-7 | ✅ | ✅ | ✅ | ✅ | ✅ |
| AC-S190-8 | ✅ | ✅ | ✅ | ✅ | ✅ |
| AC-S190-9 | ✅ | ✅ | ✅ | ✅ | ✅ |
| AC-S190-10 | ✅ | ✅ | ✅ | ✅ | ✅ |

---

## §4 Interface Design

### Backend DTO

Add nested record to `SecurityReportResponse`:

```java
public record RiskReason(
        String code,
        String label,
        String detail,
        String impact,
        List<String> evidence,
        String action) {}
```

Extend response constructor:

```java
public record SecurityReportResponse(
        String skillId,
        String skillVersionId,
        String skillVersion,
        Instant scannedAt,
        String engineVersion,
        String ruleSetVersion,
        String overall,
        Map<String, CheckDetail> checks,
        List<CategorySummary> categories,
        List<FindingSummary> findings,
        List<RiskReason> riskReasons) {}
```

Backward-compatible constructor keeps existing tests/callers compiling with `riskReasons=List.of()`.

### Scan Persist Shape

`ScanOrchestrator.persist` writes:

```json
{
  "level": "LOW",
  "findings": [],
  "notices": [],
  "riskReasons": [
    {
      "code": "ALLOWED_TOOLS_DECLARED",
      "label": "這個技能可以要求 AI 使用工具",
      "detail": "掃描沒有找到需要修改的問題。不過這個技能可以要求 AI 使用 Bash、Write、Edit，所以使用前請先確認你接受這些能力。",
      "impact": "LOW",
      "evidence": ["Bash", "Write", "Edit"],
      "action": "REVIEW_FIRST"
    }
  ],
  "sarif": {},
  "scannedAt": "2026-05-17T00:00:00Z",
  "sourceEventId": "..."
}
```

### Reason Builder Rules

| Condition | Reason code | impact | action |
|---|---|---|---|
| `findings.isEmpty()` and no scripts and no allowedTools | `NO_FINDINGS_NO_CAPABILITIES` | `NONE` | `DOWNLOAD_OK` |
| `allowedTools` not empty | `ALLOWED_TOOLS_DECLARED` | `LOW` | `REVIEW_FIRST` |
| `scripts` not empty | `SCRIPTS_INCLUDED` | `LOW` | `REVIEW_FIRST` |
| `findings` not empty | `FINDINGS_PRESENT` | max finding severity | `FIX_REQUIRED` |
| legacy JSONB lacks `riskReasons`, but `version.allowedTools` not empty | `LEGACY_ALLOWED_TOOLS` | `LOW` | `REVIEW_FIRST` |

### Frontend Types

Add to `frontend/src/api/security.ts`:

```ts
export interface SecurityRiskReason {
  code: 'NO_FINDINGS_NO_CAPABILITIES' | 'ALLOWED_TOOLS_DECLARED' | 'SCRIPTS_INCLUDED' | 'FINDINGS_PRESENT' | 'LEGACY_ALLOWED_TOOLS' | string
  label: string
  detail: string
  impact: 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH' | string
  evidence: string[]
  action: 'DOWNLOAD_OK' | 'REVIEW_FIRST' | 'FIX_REQUIRED' | string
}
```

`SecurityReport.riskReasons?: SecurityRiskReason[]` may be optional in TS for defensive render, but backend should always output an array.

### Frontend Component Plan

```text
SecurityTab
├─ Scan metadata
├─ SummaryBlock grid
├─ RiskReasonSection
│  ├─ reason cards
│  └─ ActionRail
└─ FindingsSection
```

Pure helpers:

```ts
function riskReasonTone(impact: string): { color: string; border: string }
function emptyFindingMessage(riskLevel, riskReasons): string
function actionLabels(riskLevel, totalFindings): ['下載技能' | '先查看掃描發現', '查看檔案', '回報疑慮']
```

---

## §5 File Plan

| File | Action | Notes |
|---|---|---|
| `backend/src/main/java/io/github/samzhu/skillshub/security/SecurityReportResponse.java` | Update | Add `RiskReason` record and response field. |
| `backend/src/main/java/io/github/samzhu/skillshub/security/SecurityReportService.java` | Update | Read persisted `riskReasons`; fallback from `SkillVersion.allowedTools` and `findings`. |
| `backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScanOrchestrator.java` | Update | Build deterministic `riskReasons` from `ScanContext` and findings before persisting JSONB. |
| `backend/src/test/java/io/github/samzhu/skillshub/security/SecurityReportServiceTest.java` | Update | Cover AC-S190-5/6. |
| `backend/src/test/java/io/github/samzhu/skillshub/security/scan/ScanOrchestratorTest.java` or existing scanner test | Update | Cover AC-S190-7 with package containing scripts. |
| `frontend/src/api/security.ts` | Update | Add `SecurityRiskReason`. |
| `frontend/src/components/v2/tabs/SecurityTab.tsx` | Update | Add reason section + three actions + revised empty copy. |
| `frontend/src/components/v2/tabs/SecurityTab.test.tsx` | Update | Cover AC-S190-1/2/3/4/8. |
| `frontend/src/components/RiskBadge.tsx` | Update | Change `NONE` label from `無風險` to `未發現風險`; keep caveat tooltip. |
| `frontend/src/pages/docs/YourFirstSkillPage.tsx` | Update | Fix stale minimal-skill LOW claim; pure SKILL.md should be NONE. |
| `frontend/src/pages/docs/RiskTiersPage.tsx` | Review/update | Keep tier definitions aligned with backend and S190 language rules. |
| `frontend/src/pages/docs/UploadValidatePage.tsx` | Review/update | Ensure upload flow says allowed-tools/scripts produce LOW when findings are empty, not merely "trigger scan". |
| `docs/grimo/glossary.md` | Review/update | `風險原因 / Risk Reason` already exists; only adjust if wording drifts from S190 §2.0. |
| `docs/grimo/ui/prototype/Skills Hub Security Reason UI.html` | Add | Static reference HTML for reviewer. |

### Task Boundary Hints

| Task | Scope | AC |
|---|---|---|
| T01 Backend response contract | DTO + service fallback | AC-S190-5, AC-S190-6 |
| T02 Scanner persist reasons | `ScanOrchestrator` reason builder | AC-S190-7 |
| T03 Frontend security tab | `SecurityTab` reason section + actions + empty copy | AC-S190-1, AC-S190-2, AC-S190-3, AC-S190-4, AC-S190-8 |
| T04 Docs/prototype verification | HTML prototype + risk-tier docs sync + glossary/roadmap sync | AC-S190-9, AC-S190-10 |

### Doc Sync

| Doc | Required |
|---|---|
| PRD | No change. P3 already says scan result should record scanned items and pass/fail. |
| Roadmap | Add S190 Active row. |
| ADR | No. This is additive reporting/UI; it does not contradict architecture. |
| Glossary | Already has `風險原因 / Risk Reason`; verify `風險等級 / Risk Level` wording matches §2.0. |
| Docs pages | Fix stale risk-tier wording in `/docs/your-first-skill`, review `/docs/risk-tiers` and `/docs/upload-validate`. |

---

## §6 Task Plan

### Pre-Flight Validation

| Check | Result |
|---|---|
| PRD alignment | PASS。PRD P3 要求風險評估記錄掃描項目與通過/未通過；S190 只把既有 `LOW + 0 findings` 原因顯示出來，不改分級規則。 |
| Existing implementation | PASS。`ScanOrchestrator.classifyRiskLevel` 已是 `findings=[]` 且有 `scripts/` 或 `allowed-tools` → `LOW`；`SecurityReportService` 目前只回 `findings`，缺 `riskReasons`。 |
| Prior spec findings | PASS。S096c 已建立 4-tier；S142b/S147/S183 已建立 security report、findings 與安全頁。S073 已證明 `allowed-tools` YAML list 是 canonical shape，S190 task 必須補 `SkillVersion.allowedTools` persistence/parser coverage。 |
| Product language | PASS。`RiskLevel` / `finding` / `riskReason` 已在 §2.0 定義；NON-engineer copy 必須先說「這個技能可以要求 AI 使用工具」，不能只顯示 `allowed-tools`。 |
| Existing task collision | S187 task files 仍存在且屬另一條線；S190 task files 使用 `S190` prefix，不會覆蓋。 |

### POC Decision

POC: not required。

S190 不加新 SDK、不包 framework SPI、不改 DB schema。要做的是把既有 `risk_assessment` JSONB map 加 `riskReasons` key，並把既有 `SecurityReport` / `SecurityTab` 顯示補齊。唯一需要特別測的不是新技術，而是已知 interop 細節：S073 讓 validator 支援 YAML list，但 `SkillVersion.parseAllowedTools` / `Skill.parseAllowedTools` 仍是 `raw.toString().split("\\s+")`，task T01 要用測試把 `["Read","Bash"]` 這種 shape 存成乾淨 list。

### Task Order

| Task | File | Scope | AC |
|---|---|---|---|
| T01 | `docs/grimo/tasks/2026-05-17-S190-T01-backend-security-report-risk-reasons.md` | `SecurityReportResponse` 加 `RiskReason`；`SecurityReportService` 讀 JSONB/fallback；補 `allowed-tools` list parser coverage。 | AC-S190-5, AC-S190-6, AC-S190-1b backend, AC-S190-3 backend |
| T02 | `docs/grimo/tasks/2026-05-17-S190-T02-scanner-persist-risk-reasons.md` | `ScanOrchestrator.persist` 寫 deterministic `riskReasons`，含 allowed-tools-only、scripts、findings、NONE。 | AC-S190-7, AC-S190-1 backend, AC-S190-1b backend |
| T03 | `docs/grimo/tasks/2026-05-17-S190-T03-frontend-security-risk-reasons.md` | `SecurityTab` 顯示原因區、三動作、空 findings 新文案；`RiskBadge` / current-risk NONE label 改「未發現風險」。 | AC-S190-1, AC-S190-1b, AC-S190-2, AC-S190-3, AC-S190-4, AC-S190-8 |
| T04 | `docs/grimo/tasks/2026-05-17-S190-T04-docs-prototype-risk-language-sync.md` | docs pages / prototype / docs scan 同步風險語言，避免 `NONE` 被寫成「無風險」或純 SKILL.md 被說成 LOW。 | AC-S190-9, AC-S190-10 |

### Verification Chain

| Stage | Command |
|---|---|
| T01 backend response | `cd backend && ./gradlew test --tests '*SecurityReportServiceTest' --tests '*SecurityReportControllerTest' --tests '*SkillVersionAggregateTest'` |
| T02 scanner persist | `cd backend && ./gradlew test --tests '*ScanOrchestratorTest'` |
| T03 frontend UI | `cd frontend && npm test -- SecurityTab.test.tsx RiskBadge.test.tsx` |
| T04 docs/prototype | `rg -n "無風險|未發現風險|LOW risk|allowed-tools|scripts/" frontend/src/pages/docs docs/grimo/ui/prototype docs/grimo/glossary.md` |
| Final deterministic checks | `cd backend && ./gradlew test`; `cd frontend && npm test`; `cd frontend && npm run verify` |

### E2E Assessment

Browser E2E is not required at task-planning time. S190 changes a component that already receives `SecurityReport` props and can be verified with component tests plus backend response tests. The only real-artifact risk is API assembly (`GET /security-report` includes `riskReasons`), covered by `SecurityReportControllerTest`.

---

## §7 Development Results

### Task Results

| Task | Result | Evidence |
|---|---|---|
| T01 Backend response contract | PASS | `SecurityReportResponse` exposes `riskReasons`; `SecurityReportService` reads persisted reasons and falls back for legacy rows; allowed-tools YAML list parsing covered. |
| T02 Scanner persist reasons | PASS | `ScanOrchestrator` persists deterministic `riskReasons` for findings, allowed-tools, scripts, and NONE. |
| T03 Frontend security tab | PASS | `SecurityTab` renders reason cards, LOW empty finding copy, and action strip; `RiskBadge` shows `未發現風險` for NONE. |
| T04 Docs/prototype language sync | PASS | Docs/prototype no longer say pure `SKILL.md` / no `scripts/` is LOW; LOW + 0 findings is explained by `allowed-tools` or `scripts/`. |

### Verification Results

| Command | Result |
|---|---|
| `cd backend && ./gradlew test --tests '*SecurityReportServiceTest' --tests '*SecurityReportControllerTest' --tests '*SkillVersionAggregateTest'` | PASS |
| `cd backend && ./gradlew test --tests '*ScanOrchestratorTest'` | PASS |
| `cd frontend && npm test -- SecurityTab.test.tsx RiskBadge.test.tsx` | PASS — 2 files / 22 tests |
| `cd frontend && npm run typecheck` | PASS |
| `rg -n "無風險|未發現風險|LOW risk|allowed-tools|scripts/" frontend/src/pages/docs docs/grimo/ui/prototype docs/grimo/glossary.md` | PASS — no `LOW risk` or user-facing `無風險`; remaining hits are definitions/examples for `未發現風險`, `allowed-tools`, and `scripts/`. |
| `cd frontend && npm test` | PASS — 80 files / 473 tests |
| `cd backend && ./gradlew test` | PASS — BUILD SUCCESSFUL in 6m 2s |
| `cd backend && ./gradlew test --tests '*SecurityReportServiceTest' --tests '*SecurityReportControllerTest' --tests '*SkillVersionAggregateTest' --tests '*ScanOrchestratorTest'` | PASS — BUILD SUCCESSFUL in 2m 3s |
| `cd e2e && npx playwright test --grep @happy-path` | PASS — 10 tests passed in 38.2s after S140 expectation updated from `無風險` to `未發現風險`. |
| `./scripts/verify-all.sh` | PASS — V01/V03/V04/V05/V06/V07/V08a/V08b all PASS; V02 coverage 86.9%; exit=0. |

### QA Note

QA gate found one stale E2E expectation: S140 AC-3 still waited for `無風險` while S190 intentionally renders NONE as `未發現風險`. Updated `e2e/tests/S140-critical-path-publish.spec.ts`, reran V07 successfully, then reran the full `./scripts/verify-all.sh` with exit=0.

S190 is ready for `$shipping-release S190`.
