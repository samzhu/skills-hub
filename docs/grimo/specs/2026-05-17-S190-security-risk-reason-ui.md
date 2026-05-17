# S190 — Security Risk Reason UI

> SpecID: S190
> Date: 2026-05-17
> Status: 📐 in-design — spec ready for review
> Type: Full-stack UX clarity spec
> Estimate: S(11)
> Depends: S147 ✅, S183 ✅, S142b ✅
> Prototype: [Skills Hub Security Reason UI](../ui/prototype/Skills%20Hub%20Security%20Reason%20UI.html)

---

## §1 Goal

`低風險` 但 `0 findings` 的 skill detail 安全頁要說清楚「為什麼是低風險、掃描有沒有抓到問題、使用者下一步能做什麼」。

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

---

## §2 Research And Design

### §2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
|---|---|---|
| [PRD.md P3](../PRD.md) | 純 markdown skill 要記錄掃描項目與通過/未通過；危險指令要列 issue code、檔案/行號、修法。 | `0 findings` 也要有「掃了什麼/為什麼低」的可讀說明。 |
| [ScanOrchestrator.java](../../../backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScanOrchestrator.java) | `findings.isEmpty()` 且有 scripts 或 allowed-tools → `RiskLevel.LOW`。 | LOW 不是 finding severity，而是 capability declaration 的結果。 |
| [SkillVersion.java](../../../backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersion.java) | `allowedTools` 已存在於 `skill_versions.allowed_tools`；`risk_assessment` 是 JSONB map，可加欄位。 | 不需 DB migration；response 可從既有欄位與新 JSONB key 組合。 |
| [SecurityTab.tsx](../../../frontend/src/components/v2/tabs/SecurityTab.tsx) | 目前空 findings 時只顯示 `沒有需要處理的掃描發現`。 | 要在空狀態上方新增「風險原因」與「三動作」。 |
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
      "label": "宣告工具能力",
      "detail": "此技能宣告可使用 Bash、Write、Edit；掃描器未在內容中找到具體問題。",
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
| B. 純文件 skill NONE + 0 findings | skill 只有 `SKILL.md`，無 scripts、無 allowed-tools | 使用者打開安全性 tab | 看到「無風險」原因是沒有工具宣告與 scripts；主動作仍是下載。 |
| C. HIGH + findings | skill 有 `W008` hardcoded secret | 使用者打開安全性 tab | 先看到高風險原因與「先修正」動作，再看到 `file:line` 與修法。 |

### §2.4 Approach Comparison

| Approach | 改哪裡 | 使用者會看到什麼 | 成本 / 風險 | Recommendation |
|---|---|---|---|---|
| A. 後端回 `riskReasons[]`，前端呈現 | `ScanOrchestrator.persist` 寫 JSONB；`SecurityReportResponse` 擴欄位；`SecurityTab` 顯示原因與三動作 | LOW + 0 findings 會顯示「Bash/scripts 造成低風險，但沒有要修的 issue」 | S 級；舊資料需 fallback | ⭐ Recommended |
| B. 只改前端文案 | 只改 `SecurityTab.tsx` empty state | 顯示通用句：「低風險可能來自工具或 scripts」 | XS；但無法列 Bash/Write/scripts 名稱，容易繼續困惑 |  |
| C. 把 LOW + 0 findings 改成 NONE | `ScanOrchestrator.classifyRiskLevel` | 畫面不再困惑，但有 scripts/allowed-tools 的 skill 會被標成無風險 | 破壞 S096c/S183 語意；不採用 |  |

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
│ LOW  宣告工具能力                                           │
│ 此技能宣告可使用 Bash、Write、Edit；掃描器未找到具體問題。     │
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

And 顯示 `宣告工具能力`

And 顯示 `Bash`、`Write`、`Edit`

And 顯示 `包含可執行腳本`

And 顯示至少一個 `scripts/` 檔名。

Verification：Frontend component test + backend response test。

### AC-S190-2 — LOW + 0 findings 的掃描發現空狀態不再讓人誤會成無風險

Given `riskLevel=LOW` 且 `findings=[]`

When 使用者查看「掃描發現」

Then 畫面顯示 `沒有需要修改的掃描發現`

And 顯示 `scanner 沒有找到 issue code，不代表技能沒有任何能力風險`

And 不顯示 `未發現安全問題`。

Verification：Frontend component test。

### AC-S190-3 — NONE + 0 findings 顯示純文件原因

Given skill 只有 `SKILL.md`，沒有 scripts，沒有 allowed-tools，且 `riskLevel=NONE`

When 使用者打開「安全性」tab

Then 畫面顯示 `無風險`

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

### NFR Coverage

| Category | AC | 說明 |
|---|---|---|
| Performance | AC-S190-5 | Response 只讀 `skill_versions` row 與 JSONB，不在 GET 時下載 zip。 |
| Security | AC-S190-2, AC-S190-4 | 不把 LOW 說成安全保證；finding evidence/remediation 仍以純文字 render。 |
| Reliability | AC-S190-6 | 舊資料沒有 `riskReasons` 也能 fallback，不 crash。 |
| Usability | AC-S190-1, AC-S190-8, AC-S190-9 | 使用者看到原因、三動作、prototype。 |
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
      "label": "宣告工具能力",
      "detail": "此技能宣告可使用 Bash、Write、Edit；掃描器未在內容中找到具體問題。",
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
| `docs/grimo/glossary.md` | Update | Add `風險原因 / Risk Reason`. |
| `docs/grimo/ui/prototype/Skills Hub Security Reason UI.html` | Add | Static reference HTML for reviewer. |

### Task Boundary Hints

| Task | Scope | AC |
|---|---|---|
| T01 Backend response contract | DTO + service fallback | AC-S190-5, AC-S190-6 |
| T02 Scanner persist reasons | `ScanOrchestrator` reason builder | AC-S190-7 |
| T03 Frontend security tab | `SecurityTab` reason section + actions + empty copy | AC-S190-1, AC-S190-2, AC-S190-3, AC-S190-4, AC-S190-8 |
| T04 Docs/prototype verification | HTML prototype + glossary/roadmap sync | AC-S190-9 |

### Doc Sync

| Doc | Required |
|---|---|
| PRD | No change. P3 already says scan result should record scanned items and pass/fail. |
| Roadmap | Add S190 Active row. |
| ADR | No. This is additive reporting/UI; it does not contradict architecture. |
| Glossary | Add `風險原因 / Risk Reason`. |
