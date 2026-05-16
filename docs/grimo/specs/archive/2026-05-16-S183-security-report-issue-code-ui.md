# S183 — Security Risk Lights + Issue Findings UI

> SpecID: S183
> Date: 2026-05-16
> Status: ✅ Done — shipped locally 2026-05-16
> Type: Frontend feature spec
> Estimate: S(10)
> Depends: S147 ✅, S142a ✅, S142b ✅

---

## §1 Goal

Skill detail 頁面要把 S147 scanner 已經產出的 `findings[]` 畫出來，並把上方「安全性」卡片改成對應 `skill.riskLevel` 的 4 個總風險燈：無風險 / 低風險 / 中風險 / 高風險。

[frontend/src/api/security.ts](../../../frontend/src/api/security.ts) 已經接住 S147 的 `categories[]` 與 `findings[]`，但 [frontend/src/components/v2/SecurityHeroCard.tsx](../../../frontend/src/components/v2/SecurityHeroCard.tsx) 和 [frontend/src/components/v2/tabs/SecurityTab.tsx](../../../frontend/src/components/v2/tabs/SecurityTab.tsx) 還停在 S142b MVP 的 `checks.shell / checks.paths / checks.secrets / checks.deps` 呈現方式。

S183 要完成三件事：

1. 上方 `SecurityHeroCard` 只顯示總風險：`安全性`、`高風險`、`1 個綠燈 · 3 個紅燈`，四個燈不再對應 Shell / Paths / Secrets / Deps。
2. 「安全性」tab 不再重複畫四個燈，也不再顯示 Shell / Paths / Secrets / Deps 四格；tab 只放掃描 metadata、severity 摘要、S147 finding 明細。
3. Finding 明細顯示 `issueCode`、`ruleId`、`severity`、`filePath`、`line`、`evidence`、`remediation`、`confidence`，讓使用者知道掃到哪裡與怎麼修。

### Dependency Status

| Spec | 狀態 | 是否阻擋 |
|---|---|---|
| S147 | ✅ shipped v4.59.0 | Code-level dependency。`SecurityReport` 已有 `categories/findings`。 |
| S142a | ✅ shipped v4.22.0 | UI dependency。`SecurityHeroCard`、`SecurityTab`、HeroMetricsRow 已存在。 |
| S142b | ✅ shipped v4.1.0 | API dependency。`GET /security-report` 已存在且保留 legacy `checks`。 |
| S184 | active spec | 不重疊。S184 處理 API empty response contract，S183 只處理安全報告 UI。 |

### Out Of Scope

| 項目 | 理由 |
|---|---|
| 新增或修改 scanner rule | S147 已完成；S183 只畫既有資料。 |
| 後端 response shape 改版 | `SecurityReport` 已有需要欄位；前端只做 defensive render。 |
| Category overview | 使用者已確認不需要用 category 對應 UI；issue code 明細即可。 |
| SARIF viewer / file inline highlight | MVP 只顯 `filePath:line` 與 evidence；跳 Files tab 定位可後續做。 |
| 人工審核 queue | 高風險審核流程不是本 spec。 |
| Capability reason explanation | `SecurityReportResponse` 目前沒有 `riskReasons[]` / `capabilities[]`；S183 不讓前端猜 `LOW + 0 findings` 的原因。 |

---

## §2 Research And Design

### §2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
|---|---|---|
| [PRD.md P3](../PRD.md) | package 內危險指令要列出 issue code、危險項目、檔案/行號與修法建議。 | 安全性 tab 必須顯示 finding 明細，不只顯整體 PASS/WARN/FAIL。 |
| [PRD.md D27](../PRD.md) | 風險層級是 4-tier：NONE / LOW / MEDIUM / HIGH；0 finding 要和 low finding 分開。 | 四燈總風險使用 `無風險 / 低風險 / 中風險 / 高風險`。 |
| [architecture.md security API](../architecture.md) | `/security-report` 回傳 legacy `checks` 與 issue-code `categories/findings`。 | 不改 API；`findings[]` 只負責 tab 明細與 counts，不負責 header 四燈。 |
| [PageHeader.tsx](../../../frontend/src/components/v2/PageHeader.tsx) | `PageHeader` 已經同時持有 `skill` 與 `report`，但目前只把 `report` 傳給 `HeroMetricsRow`。 | S183 要把 `skill.riskLevel` 往下傳給 `SecurityHeroCard`，讓四燈對齊平台正式風險等級。 |
| [SecurityHeroCard.tsx](../../../frontend/src/components/v2/SecurityHeroCard.tsx) | 現在四段 bar 直接綁 `shell/paths/secrets/deps`，下方還顯示 Shell / Paths / Secrets / Deps label。 | 需要改成四個總風險燈，不再顯示舊 label。 |
| [SecurityTab.tsx](../../../frontend/src/components/v2/tabs/SecurityTab.tsx) | 現在 tab 只顯 shield hero + 4-quad cards。 | 要移除重複四格，改成 metadata + summary + finding list。 |
| [GitHub code scanning docs](https://docs.github.com/en/code-security/concepts/code-scanning/about-code-scanning-alerts) | Code scanning alert 的 severity 用來表示問題對 codebase 增加多少風險。 | S183 在 report 明細中保留 finding `severity`，但 header 總風險仍用平台 `skill.riskLevel`。 |
| [Snyk Priority Score docs](https://docs.snyk.io/manage-risk/prioritize-issues-for-fixing/priority-score) | 安全產品會先給一個排序/優先程度，再讓使用者看 issue 明細。 | 頂部先顯總風險燈，tab 內再顯 findings。 |
| [OWASP Risk Rating Methodology](https://owasp.org/www-community/OWASP_Risk_Rating_Methodology) | 風險最終會被收斂成 final severity rating。 | UI 不把每個風險來源都變成一顆燈；燈只表達最後等級。 |
| [snyk/agent-scan issue-codes.md](https://github.com/snyk/agent-scan/blob/main/docs/issue-codes.md) | Agent Scan 用 issue groups（Compromised Skills / Vulnerable Skills 等）整理 issue code；每個 issue code 自帶 severity badge，例如 E004/E005/E006 是 critical，W007/W008/W012 是 high，W009/W011/W013 是 medium，W014 是 low。 | Security tab 的 finding 明細可以依 severity count/filter；不應把 category/group 畫成四燈。 |
| [snyk/agent-scan json-output.md](https://github.com/snyk/agent-scan/blob/main/docs/json-output.md) | JSON 輸出是 path -> `ScanPathResult`，每個 result 有 `issues[]`；政策檢查範例也是從 `issues[].code` 過濾 real issues。 | S183 tab 的資料模型維持 `findings[]` 列表與 severity counts，符合 scanner output 的常見形狀。 |
| [snyk/agent-scan printer.py](https://github.com/snyk/agent-scan/blob/main/src/agent_scan/printer.py) | Text report 依 issue severity 排序，並用 `critical/high/medium/low` 做 summary counts；若 issue 沒帶 severity，fallback 是 W=medium、E=high。 | S183 的「高/中/低 findings」摘要是合理分類；但 Skills Hub 的 header 四燈仍以平台 `skill.riskLevel` 為準。 |
| [prototype HTML](../ui/prototype/Skills%20Hub%20Security%20Risk%20Lights%20UI.html) | 使用者已確認：四燈只留在上方 metric；安全性 tab 不再重複。 | 此檔作為 low-fidelity visual reference，不是 production CSS。 |

### §2.2 Existing API Contract

```ts
interface SecurityReport {
  overall: 'PASS' | 'WARN' | 'FAIL'
  checks: {
    shell: SecurityCheck
    paths: SecurityCheck
    secrets: SecurityCheck
    deps: SecurityCheck
  }
  categories: SecurityCategorySummary[]
  findings: SecurityFindingSummary[]
}

interface SecurityFindingSummary {
  ruleId: string
  issueCode: string | null
  severity: 'HIGH' | 'MEDIUM' | 'LOW' | null
  message: string
  remediation: string | null
  confidence: 'HIGH' | 'MEDIUM' | 'LOW' | null
  filePath: string | null
  line: number | null
  evidence: string | null
}
```

S183 不新增 API 欄位。`categories[]` 保留在 type 裡，但本 spec 不新增 category overview。

### §2.3 Chosen Approach

採用「總風險燈 + finding 明細」：

```text
SkillDetailPage
├─ HeroMetricsRow
│  └─ SecurityHeroCard
│     ├─ label: 安全性
│     ├─ value: from skill.riskLevel
│     ├─ subtext: 4 個綠燈 / 3 個綠燈 · 1 個紅燈 / ...
│     └─ 4 lights: red lights accumulate from the right
└─ Tab: 安全性
   ├─ scan metadata
   ├─ risk level + severity summary
   └─ finding list + severity filter
```

風險燈計算規則只寫在程式與 spec，不在 UI 畫面上當說明文字。資料來源是 `skill.riskLevel`：

| `skill.riskLevel` | UI value | 燈號 |
|---|---|---|
| `NONE` | 無風險 | 綠 綠 綠 綠 |
| `LOW` | 低風險 | 綠 綠 綠 紅 |
| `MEDIUM` | 中風險 | 綠 綠 紅 紅 |
| `HIGH` | 高風險 | 綠 紅 紅 紅 |
| `null` | 未評估 | 不顯示四燈或顯示 neutral placeholder |

`report.findings[]` 只用於安全性 tab 的「高/中/低 findings」數量、filter 與明細列表。原因：`LOW` 風險可能是「0 findings 但有 capability declaration」，若 header 用 `findings[]` 算，會把這種技能錯顯為 `無風險`。

安全性 tab 的 `目前等級` 同樣讀 `skill.riskLevel`，不可從 `findings[]` 重新推導。當 `skill.riskLevel=LOW` 但 `findings=[]` 時，tab 仍顯示 `目前等級：低風險`，三個 findings count 皆為 0，finding list 顯示沒有需修正的 issue。

Decision：`LOW + 0 findings` 代表 skill 有 capability declaration 或其他低階能力風險，但 scanner 沒找到具體需修正的 issue。UI 不可把這種狀態寫成 `無風險` 或 `未發現安全問題`。

Decision：finding list 空狀態依 `skill.riskLevel` 分支。`riskLevel=NONE && findings=[]` 顯示 `未發現安全問題`；`riskLevel=LOW/MEDIUM/HIGH && findings=[]` 顯示 `沒有需要處理的掃描發現`。

Decision：S183 不顯示 `LOW + 0 findings` 的 capability 原因。若未來要解釋「為什麼低風險但沒有 findings」，需另開後端/API spec 增加 `riskReasons[]` 或 `capabilities[]`，再由前端顯示。

Decision：production UI 完全不顯示 S142b legacy `checks.shell / paths / secrets / deps` 四格；只保留資料相容 fallback，避免舊 response 或異常 response 讓元件 crash。

Snyk Agent Scan 的做法可作參考但不照抄：它的 report 以 `issues[]` 為主，並依 issue severity 做排序與 counts；它沒有 Skills Hub 這種獨立存在於 skill read model 的 `riskLevel`。所以 S183 採混合模型：`riskLevel` 是一眼判斷，`findings[]` 是審查與修正明細。

Snyk issue reference 有 `critical`，但 Skills Hub S147 前端 contract 目前只有 `HIGH / MEDIUM / LOW`。S183 不新增 `CRITICAL` UI tier；Snyk `critical` 類 issue code（例如 E004/E005/E006）在 Skills Hub 仍顯示為 `HIGH` finding，並讓 `skill.riskLevel=HIGH` 驅動一綠三紅。

Decision：使用者確認 MVP 先不用分到 `critical`；安全性 tab 只呈現高 / 中 / 低 findings。

### §2.4 Alternatives Considered

| 選項 | 改哪裡 | 使用者會看到什麼 | 成本 / 風險 |
|---|---|---|---|
| A. 總風險燈只放 Hero，tab 放 findings | `SecurityHeroCard.tsx` + `SecurityTab.tsx` | 上方一眼看到風險等級；tab 看到掃描摘要與修法明細 | 採用。符合使用者已確認的 prototype。 |
| B. Hero 與 tab 都放四燈 | 同 A | 進 tab 後又看到一次四燈 | 不採用。使用者已指出重複。 |
| C. 四燈或 tab 對應 Shell / Paths / Secrets / Deps | 保留目前結構，小改 label | 燈號看似分類，但 S147 issue code 不一定屬於這四類 | 不採用。Production UI 不再顯示舊四格。 |
| D. 做 category overview | `SecurityTab` 多一段 categories grid | 可以看分類 counts，但會把使用者拉回 category 心智模型 | 不採用。使用者已確認不用 category 對應。 |

### §2.5 Low-Fidelity UI Sketch

此 sketch 只定義畫面結構，不是最終像素、不新增 design system、不允許加無關裝飾。

Desktop:

```text
Skill Detail Header
┌─────────────────────────────┐ ┌─────────────────────────────┐ ┌─────────────────────────────┐
│ Skill Score                  │ │ 品質                        │ │ 安全性                      │
│ 72                           │ │ 92%                         │ │ 高風險                      │
│ 安全風險拉低整體評分         │ │ Validation 100 ...          │ │ 1 個綠燈 · 3 個紅燈          │
│                              │ │                             │ │ [綠][紅][紅][紅]             │
└─────────────────────────────┘ └─────────────────────────────┘ └─────────────────────────────┘

Tabs: SKILL.md | 品質 | 版本 | 評論 | 安全性 | 旗標 | 檔案

安全性 tab
┌────────────────────────────────────────────────────────────┐
│ [shield]                                                   │
│ 安全性                                                     │
│ 掃描 2026/05/16 · risk-scanner v1.0 · 規則集 2026-05        │
└────────────────────────────────────────────────────────────┘

掃描摘要
┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐
│ 目前等級   │ │ 高風險      │ │ 中風險      │ │ 低風險      │
│ 高風險     │ │ 3           │ │ 0           │ │ 1           │
└────────────┘ └────────────┘ └────────────┘ └────────────┘

掃描發現
[全部] [高] [中] [低]                                  顯示 4 / 4 筆
┌────────────────────────────────────────────────────────────┐
│ W008  HIGH                         高信心                  │
│ Hardcoded secret detected                                  │
│ scripts/use-openai.sh:3        ruleId OPENAI_KEY            │
│ evidence: sk-a...aaaa                                      │
│ 修法：請移除寫死在 package 文字裡的 secret...               │
└────────────────────────────────────────────────────────────┘
```

Mobile:

```text
[Skill Score]
[品質]
[安全性]
  高風險
  1 個綠燈 · 3 個紅燈
  [綠][紅][紅][紅]

安全性 tab
[scan metadata]
[目前等級]
[高風險 findings]
[中風險 findings]
[低風險 findings]
[全部][高][中][低]
[finding row full width]
```

### §2.6 Estimate

| Dimension | Score | Rationale |
|---|---:|---|
| Tech risk | 1 | React/Vitest 既有 patterns；不新增 dependency。 |
| Uncertainty | 1 | 使用者已用 HTML prototype 修正並確認方向。 |
| Dependencies | 3 | 依賴 S147 + S142a + S142b 三個 shipped specs。 |
| Scope | 2 | 主要改 5 個既有 production components 的 prop/threading/render；不新增 route 或 API。 |
| Testing | 2 | 需要 Vitest component tests 覆蓋 header prop threading、risk lights、finding list；mobile wrapping 可用 browser/manual 補。 |
| Reversibility | 1 | 純前端呈現，不改 API / DB。 |
| Total | 10 | S-sized spec。 |

### §2.7 NFR Coverage Sweep

| Category | 對應 AC | 說明 |
|---|---|---|
| Performance | AC-S183-8, AC-S183-9 | Severity filter 是 client-side state；點 filter 不打 API。 |
| Security | AC-S183-5 | `evidence` / `remediation` 只能當純文字 render，不可注入 HTML。 |
| Reliability | AC-S183-6, AC-S183-11, AC-S183-12 | null / missing fields 要有 fallback；`riskLevel` 與 `findings` 不一致時也不讓 UI 自相矛盾。 |
| Usability | AC-S183-1, AC-S183-7, AC-S183-10, AC-S183-13 | 四燈只出現在 Hero；tab 不重複；手機長文字不溢出；空狀態文字不誤導。 |
| Maintainability | AC-S183-11 | 風險計算與 finding formatting 需拆成純函式並有測試。 |

---

## §3 Acceptance Criteria

驗證命令：

```bash
cd frontend && npm test
cd frontend && npm run verify
```

Pass：所有帶 `AC-S183-*` 的 Vitest 測試都是綠燈，且 TypeScript / lint 沒有錯。

### AC-S183-1 — Header 依 HIGH riskLevel 顯示高風險四燈

Given（前提）`skill.riskLevel=HIGH`
When（動作）使用者打開 Skill detail header
Then（結果）畫面顯示 `安全性`
And（而且）顯示 `高風險`
And 顯示 `1 個綠燈 · 3 個紅燈`
And 四個燈依序是綠、紅、紅、紅。

### AC-S183-2 — Header 依 NONE riskLevel 顯示無風險四燈

Given `skill.riskLevel=NONE`
When 使用者打開 Skill detail header
Then 畫面顯示 `無風險`
And 顯示 `4 個綠燈`
And 四個燈都是綠色。

### AC-S183-3 — Header 依 LOW riskLevel 顯示低風險四燈

Given `skill.riskLevel=LOW`
When 使用者打開 Skill detail header
Then 顯示 `低風險`
And 顯示 `3 個綠燈 · 1 個紅燈`
And 四個燈依序是綠、綠、綠、紅。

### AC-S183-4 — Header 依 MEDIUM riskLevel 顯示中風險四燈

Given `skill.riskLevel=MEDIUM`
When 使用者打開 Skill detail header
Then 顯示 `中風險`
And 顯示 `2 個綠燈 · 2 個紅燈`
And 四個燈依序是綠、綠、紅、紅。

### AC-S183-5 — Finding evidence 與 remediation 以純文字顯示

Given finding 有 `evidence=<img src=x onerror=alert(1)>` 與 `remediation=請移除寫死在 package 文字裡的 secret`
When 使用者打開「安全性」tab
Then evidence block 顯示字串 `<img src=x onerror=alert(1)>`
And 頁面沒有執行 HTML 或 script
And 顯示 `修法：請移除寫死在 package 文字裡的 secret`。

### AC-S183-6 — Finding row 顯示 issue code、檔案、行號、ruleId

Given `report.findings[0]` 是 `issueCode=W008`, `ruleId=OPENAI_KEY`, `filePath=scripts/use-openai.sh`, `line=3`
When 使用者打開「安全性」tab
Then 畫面顯示 `W008`
And 顯示 `scripts/use-openai.sh:3`
And 顯示 `OPENAI_KEY`。

### AC-S183-7 — 安全性 tab 不重複四燈，也不顯示舊四格分類

Given 頁面上方已依 `skill.riskLevel` 顯示四個風險燈
When 使用者切到「安全性」tab
Then tab 內不再出現第二組四個風險燈
And tab 內不顯示 `Shell`、`Paths`、`Secrets`、`Deps` 四格 label
And tab 內顯示 `掃描摘要` 與 `掃描發現`。

### AC-S183-8 — 高風險 filter 只在前端切換 findings

Given report 有 HIGH / MEDIUM / LOW 各一筆 finding
When 使用者點 `高`
Then finding list 只顯 HIGH finding
And 顯示 `顯示 1 / 3 筆`
And 不重新呼叫 `/api/v1/skills/{id}/security-report`。

### AC-S183-9 — 全部 filter 顯示全部 findings

Given report 有 HIGH / MEDIUM / LOW 各一筆 finding
And 使用者已點過 `高`
When 使用者點 `全部`
Then 三筆 finding 都顯示。

### AC-S183-10 — Mobile layout 長文字不溢出

Given viewport width 390px
And finding 有很長的 `filePath`、`evidence`、`remediation`
When 使用者打開「安全性」tab
Then finding row 在單欄內換行
And 檔案路徑、evidence、remediation 不遮住後續內容。

### AC-S183-11 — Helper functions 有穩定 fallback

Given finding 的 `issueCode=null`, `severity=null`, `filePath=null`, `line=null`, `confidence=null`, `remediation=null`
When `SecurityTab` render
Then finding row 顯示 `LEGACY`
And 顯示 `整個套件`
And 顯示 `未提供修法建議`
And component 不 throw error。

### AC-S183-12 — 低風險但無 findings 顯示沒有需要處理的掃描發現

Given `skill.riskLevel=LOW`
And `report.findings=[]`
When 使用者打開「安全性」tab
Then `目前等級` 顯示 `低風險`
And `高風險 findings` 顯示 `0`
And `中風險 findings` 顯示 `0`
And `低風險 findings` 顯示 `0`
And finding list 顯示 `沒有需要處理的掃描發現`。

### AC-S183-13 — 無風險且無 findings 顯示未發現安全問題

Given `skill.riskLevel=NONE`
And `report.findings=[]`
When 使用者打開「安全性」tab
Then `目前等級` 顯示 `無風險`
And finding list 顯示 `未發現安全問題`。

### AC Well-Formedness Check

| AC | Singular | Unambiguous | Implementation-free | Verifiable | Bounded |
|---|---|---|---|---|---|
| AC-S183-1 | ✅ | ✅ | ✅ | ✅ Test | ✅ |
| AC-S183-2 | ✅ | ✅ | ✅ | ✅ Test | ✅ |
| AC-S183-3 | ✅ | ✅ | ✅ | ✅ Test | ✅ |
| AC-S183-4 | ✅ | ✅ | ✅ | ✅ Test | ✅ |
| AC-S183-5 | ✅ | ✅ | ✅ | ✅ Test | ✅ |
| AC-S183-6 | ✅ | ✅ | ✅ | ✅ Test | ✅ |
| AC-S183-7 | ✅ | ✅ | ✅ | ✅ Test | ✅ |
| AC-S183-8 | ✅ | ✅ | ✅ | ✅ Test | ✅ |
| AC-S183-9 | ✅ | ✅ | ✅ | ✅ Test | ✅ |
| AC-S183-10 | ✅ | ✅ | ✅ | ✅ Demo/Inspection | ✅ |
| AC-S183-11 | ✅ | ✅ | ✅ | ✅ Test | ✅ |
| AC-S183-12 | ✅ | ✅ | ✅ | ✅ Test | ✅ |
| AC-S183-13 | ✅ | ✅ | ✅ | ✅ Test | ✅ |

---

## §4 Interface Design

### §4.1 Frontend Helper Signatures

`SecurityHeroCard.tsx` 可新增純函式：

```ts
import type { RiskLevel } from '../../types/skill'

interface SecurityHeroCardProps {
  riskLevel: RiskLevel | null | undefined
  active: boolean
  onClick: () => void
}

interface RiskLightSummary {
  level: RiskLevel
  label: '無風險' | '低風險' | '中風險' | '高風險'
  greenCount: 1 | 2 | 3 | 4
  redCount: 0 | 1 | 2 | 3
  lights: Array<'green' | 'red'>
  subText: string
}

function riskLightSummary(riskLevel: RiskLevel | null | undefined): RiskLightSummary | null
```

Implementation note（2026-05-16）：helper functions 保持 module-private，不從 React component 檔案 export；`frontend` 的 ESLint `react-refresh/only-export-components` 會把 component 檔案 export 非 component helper 視為 warning，而 verify 使用 `--max-warnings 0`。

`SecurityTab.tsx` 可新增純函式：

```ts
import type { RiskLevel } from '../../../types/skill'
import type { SecurityFindingSummary, SecurityReport } from '../../../api/security'

type SeverityFilter = 'ALL' | 'HIGH' | 'MEDIUM' | 'LOW'

interface SecurityTabProps {
  riskLevel: RiskLevel | null | undefined
  report: SecurityReport | null | undefined
}

function riskLevelLabel(riskLevel: RiskLevel | null | undefined): string
function emptyFindingMessage(riskLevel: RiskLevel | null | undefined): string
function findingLocation(finding: SecurityFindingSummary): string
function findingCode(finding: SecurityFindingSummary): string
function findingConfidenceLabel(finding: SecurityFindingSummary): string | null
function findingRemediation(finding: SecurityFindingSummary): string
function sortFindings(findings: SecurityFindingSummary[]): SecurityFindingSummary[]
function filterFindings(findings: SecurityFindingSummary[], filter: SeverityFilter): SecurityFindingSummary[]
function severityCounts(findings: SecurityFindingSummary[]): { high: number; medium: number; low: number; total: number }
```

### §4.2 Field Source Mapping

| UI 欄位 | 來源 | Fallback |
|---|---|---|
| Header 風險等級 label | `skill.riskLevel` | `null` 時顯示 `未評估` |
| 燈號 | `RiskLightSummary.greenCount/redCount` | riskLevel null 時不顯示四燈或顯示 neutral placeholder |
| Tab 目前等級 | `skill.riskLevel` | `null` 時顯示 `未評估` |
| 掃描時間 | `report.scannedAt` | `—` |
| Engine | `report.engineVersion` | `—` |
| Rule set | `report.ruleSetVersion` | `—` |
| Finding code | `finding.issueCode` | `LEGACY` |
| Finding location | `finding.filePath + ':' + line` | `整個套件` |
| Evidence | `finding.evidence` | 不顯示 evidence block |
| Remediation | `finding.remediation` | `未提供修法建議` |
| Empty finding message | `skill.riskLevel` + `report.findings.length` | `NONE` 顯示 `未發現安全問題`；`LOW/MEDIUM/HIGH` 顯示 `沒有需要處理的掃描發現` |

### §4.3 Sorting

Finding rows 依序排序：

```ts
severityOrder: HIGH -> MEDIUM -> LOW -> null
then issueCode ascending, with null last
then filePath ascending, with null last
then line ascending, with null last
```

### §4.4 Styling Rules

| Element | Rule |
|---|---|
| Hero lights | 4 equal-width dots/bars；無文字 label；red 從右側累加。 |
| Security tab | 不放第二組四燈；不放 Shell / Paths / Secrets / Deps 四格。 |
| Severity summary | 4 summary blocks：目前等級（from `skill.riskLevel`）/ 高風險 findings / 中風險 findings / 低風險 findings。 |
| Finding row | Repeated item card；border radius ≤ 8px；HIGH/MEDIUM/LOW 用左 border 或 pill 色。 |
| issueCode / ruleId / filePath | Monospace；長字串 `overflow-wrap:anywhere`。 |
| evidence | `white-space: pre-wrap`；以 React text node render，不用 `dangerouslySetInnerHTML`。 |
| zh-TW labels | UI 文字用繁中；`HIGH/MEDIUM/LOW`、`ruleId`、`issueCode` 保留原文。 |

---

## §5 File Plan

| File | Action | 說明 |
|---|---|---|
| `frontend/src/components/v2/PageHeader.tsx` | modify | 將 `skill.riskLevel` 傳給 `HeroMetricsRow`。 |
| `frontend/src/components/v2/PageHeader.test.tsx` | modify | 加 header 傳遞 `skill.riskLevel` 的測試，避免 `report.findings` 被拿來推導四燈。 |
| `frontend/src/components/v2/HeroMetricsRow.tsx` | modify | 將 `riskLevel` 傳給 `SecurityHeroCard`。 |
| `frontend/src/components/v2/HeroMetricsRow.test.tsx` | modify | 加 HeroMetricsRow 傳遞 `riskLevel` 的測試。 |
| `frontend/src/components/v2/SecurityHeroCard.tsx` | modify | 將四段 bar 從 legacy checks 改為 `riskLevel` risk light summary；移除 Shell/Paths/Secrets/Deps label。 |
| `frontend/src/components/v2/SecurityHeroCard.test.tsx` | modify | 加 AC-S183-1/2/3/4 測試；保留 click 與 null riskLevel 測試。 |
| `frontend/src/pages/SkillDetailPage.tsx` | modify | 將 `skill.riskLevel` 傳入 `SecurityTab`。 |
| `frontend/src/components/v2/tabs/SecurityTab.tsx` | modify | 移除 legacy quads；新增 scan metadata、目前等級、severity summary、finding list、severity filter。 |
| `frontend/src/components/v2/tabs/SecurityTab.test.tsx` | modify | 加 AC-S183-5/6/7/8/9/11/12/13 測試；保留 loading/null fallback。 |
| `docs/grimo/ui/prototype/Skills Hub Security Risk Lights UI.html` | reference | 已確認的設計參考，不作 production source。 |
| `docs/grimo/specs/spec-roadmap.md` | modify | 更新 S183 標題與估點。 |

### Task Boundary Hints

| Task | Scope | AC |
|---|---|---|
| T01 | `PageHeader` / `HeroMetricsRow` / `SecurityHeroCard` riskLevel threading + risk light helper + UI | AC-S183-1,2,3,4 |
| T02 | `SkillDetailPage` / `SecurityTab` riskLevel prop + remove legacy quads + scan metadata + risk/severity summary | AC-S183-7,12,13 |
| T03 | Finding list row + fallback formatting | AC-S183-5,6,11 |
| T04 | Severity filter + responsive verification | AC-S183-8,9,10 |

### POC

POC: not required。這是既有 React component + Vitest + CSS-in-JS 呈現變更，不引入新 framework、不改 API、不改 DB。

---

---

## §6 Task Plan

### Phase 0 Pre-Flight Result

Pre-flight PASS（2026-05-16）。`docs/local/` 不存在，無可重用 local research notes。已交叉檢查：

- [PRD.md](../PRD.md) D27：風險等級維持 `NONE / LOW / MEDIUM / HIGH`，S183 的四燈只讀 `skill.riskLevel`。
- [S142a shipped spec](archive/2026-05-07-S142a-skill-detail-v2-frontend.md)：`PageHeader` / `HeroMetricsRow` / `SecurityHeroCard` / `SecurityTab` 是既有 v2 component path，Vitest component test 是正確驗證層。
- [S142b shipped spec](archive/2026-05-07-S142b-skill-detail-v2-backend.md)：legacy `checks` 是 `/security-report` 相容欄位，不該繼續作為 S183 四燈 UI 的資料來源。
- [S147 shipped spec](archive/2026-05-08-S147-scanner-semantic-gap-research.md)：`SecurityReport.findings[]` 已包含 `issueCode`、`remediation`、`confidence`，前端可以直接 render。

No contradiction found；不用回到 `$planning-spec`。

### POC Decision

POC: not required。S183 不新增 package、SDK、外部 API、DB schema 或 framework SPI；只修改既有 React component props、render helper、local state filter 與 Vitest tests。

### Task Order

| Task | File | Scope | Depends | AC |
|---|---|---|---|---|
| T01 | `docs/grimo/tasks/2026-05-16-S183-T01-risk-lights-hero.md` | `PageHeader` / `HeroMetricsRow` / `SecurityHeroCard` riskLevel threading + 四燈 helper | — | AC-S183-1,2,3,4 |
| T02 | `docs/grimo/tasks/2026-05-16-S183-T02-security-tab-summary.md` | `SkillDetailPage` / `SecurityTab` riskLevel prop + summary + empty states + remove legacy quads | T01 | AC-S183-7,12,13 |
| T03 | `docs/grimo/tasks/2026-05-16-S183-T03-finding-list.md` | `SecurityTab` finding row + fallback formatting + plain-text evidence/remediation | T02 | AC-S183-5,6,11 |
| T04 | `docs/grimo/tasks/2026-05-16-S183-T04-filter-responsive.md` | Severity filter + responsive verification | T03 | AC-S183-8,9,10 |

### AC Coverage Matrix

| AC | Covered by Task |
|---|---|
| AC-S183-1 | T01 |
| AC-S183-2 | T01 |
| AC-S183-3 | T01 |
| AC-S183-4 | T01 |
| AC-S183-5 | T03 |
| AC-S183-6 | T03 |
| AC-S183-7 | T02 |
| AC-S183-8 | T04 |
| AC-S183-9 | T04 |
| AC-S183-10 | T04 |
| AC-S183-11 | T03 |
| AC-S183-12 | T02 |
| AC-S183-13 | T02 |

### Verification Chain

Each task runs its focused Vitest command. Final S183 verification runs:

```bash
cd frontend && npm test
cd frontend && npm run verify
```

E2E gate assessment: browser E2E is not planned for task creation because S183 uses no backend seed, no external boundary, and no route-level behavior beyond existing Skill detail tab rendering. Manual/browser inspection is still required for AC-S183-10 mobile wrapping after implementation.

---

<!-- Section 7 added by /planning-tasks after implementation -->

---

## §7 Implementation Results

### Summary

S183 implemented on 2026-05-16. Skill detail now shows risk lights from `skill.riskLevel` in the header, and the Security tab renders S147 `findings[]` as severity counts, filters, and finding detail rows.

### Files Changed

| File | Change |
|---|---|
| `frontend/src/components/v2/SecurityHeroCard.tsx` | Replaced legacy `report.checks` four-segment UI with `riskLevel` risk lights. |
| `frontend/src/components/v2/HeroMetricsRow.tsx` | Passes `riskLevel` to `SecurityHeroCard`. |
| `frontend/src/components/v2/PageHeader.tsx` | Passes `skill.riskLevel` to `HeroMetricsRow`; no longer passes `report` into the header metric chain. |
| `frontend/src/pages/SkillDetailPage.tsx` | Passes `skill.riskLevel` to `SecurityTab`. |
| `frontend/src/components/v2/tabs/SecurityTab.tsx` | Removed legacy quads; added scan metadata, current risk, severity counts, findings list, fallback formatting, and local severity filter. |
| `frontend/src/components/v2/*.test.tsx`, `frontend/src/components/v2/tabs/SecurityTab.test.tsx` | Added AC-S183 tests for header risk lights, prop threading, tab summary, finding rows, and filter behavior. |
| `frontend/src/components/*test.tsx`, `frontend/src/components/v2/*test.tsx`, `frontend/src/api/client.test.ts` | Fixture/typecheck cleanup needed by existing S184 dirty `Skill.visibility` changes so frontend verify can run green. |

### Verification

```bash
cd frontend && npm test -- SecurityHeroCard HeroMetricsRow PageHeader SecurityTab SkillDetailPage
# PASS: 5 files / 44 tests

cd frontend && npm run verify
# PASS: ESLint + TypeScript

cd frontend && npm test
# PASS: 77 files / 440 tests
```

Browser E2E not required: S183 does not add route wiring, backend seed data, persistence, subprocesses, credentials, or framework assembly behavior. AC-S183-10 was verified by code inspection of `SecurityTab.tsx`: finding cards, location, evidence, and remediation use `minWidth: 0`, `overflowWrap: 'anywhere'`, and `whiteSpace: 'pre-wrap'` where needed.

### Key Findings

- Header risk lights are intentionally independent from `/security-report`. `SecurityHeroCard` receives `riskLevel` only; `report.findings[]` cannot change the four-light score.
- `LOW + []` renders `目前等級：低風險` and `沒有需要處理的掃描發現`; `NONE + []` renders `未發現安全問題`.
- `SecurityTab` keeps all scanner-provided strings as React text nodes. `evidence=<img src=x onerror=alert(1)>` renders as text and does not create an `<img>` element.
- Helper functions stay module-private inside component files to satisfy `react-refresh/only-export-components`.

### AC Results

| AC | Result | Evidence |
|---|---|---|
| AC-S183-1 | ✅ PASS | `SecurityHeroCard.test.tsx` HIGH riskLevel test |
| AC-S183-2 | ✅ PASS | `SecurityHeroCard.test.tsx` NONE riskLevel test |
| AC-S183-3 | ✅ PASS | `SecurityHeroCard.test.tsx`, `HeroMetricsRow.test.tsx`, `PageHeader.test.tsx` LOW riskLevel tests |
| AC-S183-4 | ✅ PASS | `SecurityHeroCard.test.tsx` MEDIUM riskLevel test |
| AC-S183-5 | ✅ PASS | `SecurityTab.test.tsx` plain-text evidence/remediation test |
| AC-S183-6 | ✅ PASS | `SecurityTab.test.tsx` finding row field test |
| AC-S183-7 | ✅ PASS | `SecurityTab.test.tsx` no legacy quads test |
| AC-S183-8 | ✅ PASS | `SecurityTab.test.tsx` HIGH filter test with fetch spy |
| AC-S183-9 | ✅ PASS | `SecurityTab.test.tsx` ALL filter reset test |
| AC-S183-10 | ✅ PASS | Code inspection: responsive wrapping styles on finding row/evidence/location/remediation |
| AC-S183-11 | ✅ PASS | `SecurityTab.test.tsx` null finding fallback test |
| AC-S183-12 | ✅ PASS | `SecurityTab.test.tsx` LOW + empty findings test |
| AC-S183-13 | ✅ PASS | `SecurityTab.test.tsx` NONE + empty findings test |

### Pending Verification

None.

### QA Review

Verdict: PASS（2026-05-16, independent subagent）

QA re-ran:

```bash
cd frontend && npm test -- SecurityHeroCard HeroMetricsRow PageHeader SecurityTab SkillDetailPage
# PASS: 5 files / 44 tests

cd frontend && npm run verify
# PASS: ESLint + TypeScript
```

QA findings:

- AC-S183-1 through AC-S183-13 have matching test or inspection evidence.
- `SecurityHeroCard.tsx` / `SecurityTab.tsx` production UI no longer renders `Shell` / `Paths` / `Secrets` / `Deps`, and no longer reads `report.checks`.
- Header risk lights flow is `PageHeader(skill.riskLevel)` → `HeroMetricsRow(riskLevel)` → `SecurityHeroCard(riskLevel)`.
- Security tab current level is `SkillDetailPage(skill.riskLevel)` → `SecurityTab(riskLevel)`.
- `report.findings[]` is used only for counts, filter, and finding detail rows.
- S184 dirty changes were present in the worktree, but QA found no impact on S183 risk lights or findings presentation.

### Shipping Preflight Blocker（2026-05-16）

`./scripts/verify-all.sh` 在 2026-05-16T02:27:31Z 執行，結果不能進 `$shipping-release`：

```text
Results: V01=FAIL V02=SKIP V03=FAIL V04=PASS V05=PASS V06=PASS V07=PASS V08a=PASS V08b=PASS
Verdict: ❌ 2 CRITICAL failure(s); exit=1
```

失敗點：

| Command step | 看到的失敗 | 目前判斷 |
|---|---|---|
| V01 `cd backend && ./gradlew clean test jacocoTestReport` | `S016EndToEndSmokeTest.java:198` 仍期待 `DELETE /grants/{grantId}` 回 `202 Accepted`；目前 S184 已把同步刪除改成 `204 No Content`。 | 需要 S184 task 更新舊測試期待值，確認 grant revoke 後 DB / ACL 仍正確。 |
| V01 / V03 backend test | `SkillGrantServiceTest.java:227` 的 AC-9 仍期待 `principalType="public"` 可直接走 external grant API；目前 S184 設計改為 public 只能走 `PUT /visibility`。 | 需要 S184 task 移除或改寫舊 public-grant 測試，補上 public target 被拒收與 visibility command 成功測試。 |

已通過但不能抵消 backend failure：

```text
V04 frontend npm test PASS
V05 frontend npm run verify PASS
V06 frontend coverage PASS
V07 Playwright @happy-path PASS
V08a processAot PASS
V08b bootBuildImage PASS
```

Resolution：S184 backend contract cleanup 已更新 `S016EndToEndSmokeTest` 與 `SkillGrantServiceTest`，讓舊測試對齊 `DELETE /grants/{grantId}` 回 `204 No Content`、外部 grants API 拒絕 `principalType="public"` 的新契約。

### Release Verification（2026-05-16）

```text
./scripts/verify-all.sh
Results: V01=PASS V02=INFO V03=PASS V04=PASS V05=PASS V06=PASS V07=PASS V08a=PASS V08b=PASS
Counts: PASS=8, FAIL=0, SKIP=0
Verdict: ✅ all CRITICAL passed; exit=0
```

Release note：S183 本身是 frontend UI spec，但本次 release gate 也包含 S184 的 backend/frontend visibility contract cleanup，因為 S183 shipping preflight 的 blocker 來自 S184 已實作的新 API 契約。

### Final Size Re-score

| Dimension | Initial | Actual | Rationale |
|---|---:|---:|---|
| Tech risk | 1 | 1 | React/Vitest component changes only; no new framework or API shape for S183. |
| Uncertainty | 1 | 1 | Prototype direction and `riskLevel` source stayed unchanged. |
| Dependencies | 3 | 3 | Still depends on shipped S147/S142a/S142b contracts. |
| Scope | 2 | 2 | Implemented the planned header risk lights, security tab findings, filters, and fallback rendering. |
| Testing | 2 | 2 | Focused Vitest, full frontend verify, and full `verify-all.sh` ran green. |
| Reversibility | 1 | 1 | Pure frontend presentation; rollback is limited to component render changes. |
| **Total** | **10 / S** | **10 / S** | No bucket shift. |
