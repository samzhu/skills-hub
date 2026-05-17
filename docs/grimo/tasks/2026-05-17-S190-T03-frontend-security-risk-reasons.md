# S190-T03: Frontend Security Risk Reasons

## 對應規格
S190：Security Risk Reason UI

## 這個 task 要做什麼
Skill detail 的「安全性」tab 要把 `riskReasons[]` 顯示成非工程師看得懂的句子。LOW + 0 findings 不能只顯示「沒有需要處理的掃描發現」；要說清楚「掃描沒找到要改的問題，但這個技能可以要求 AI 使用工具 / package 有 scripts」。

## 使用者情境（BDD）
Given（前提）`riskLevel=LOW`、`findings=[]`、`riskReasons` 含 `ALLOWED_TOOLS_DECLARED` 與 `SCRIPTS_INCLUDED`
When（動作）使用者打開 Skill detail 的「安全性」tab
Then（結果）畫面顯示 `目前等級` 為 `低風險`
And（而且）顯示 `為什麼是低風險？`
And（而且）顯示 `這個技能可以要求 AI 使用工具`
And（而且）顯示 `掃描沒有找到需要修改的問題`
And（而且）顯示 `使用前請先確認你接受這些能力`
And（而且）顯示 `Bash`、`Write`、`Edit`
And（而且）顯示 `包含可執行腳本`
And（而且）顯示至少一個 `scripts/` 檔名。

Given（前提）`springboot-project-architect` 場景：`riskLevel=LOW`、`findings=[]`、`riskReasons` 只有 `ALLOWED_TOOLS_DECLARED`
When（動作）使用者打開「安全性」tab
Then（結果）畫面顯示 `Read`、`Glob`、`Grep`、`Bash`、`Write`、`Edit`、`WebFetch`、`WebSearch`
And（而且）主文案不是只顯示 `allowed-tools`。

Given（前提）`riskLevel=NONE`、`findings=[]`
When（動作）使用者打開「安全性」tab 或看到 `RiskBadge`
Then（結果）畫面顯示 `未發現風險`
And（而且）原因區顯示 `沒有工具宣告或 scripts/`
And（而且）掃描發現區顯示 `未發現安全問題`。

Given（前提）security report 有 HIGH finding `W008`
When（動作）使用者打開「安全性」tab
Then（結果）畫面仍顯示 `W008`
And（而且）顯示 `scripts/use-openai.sh:3`
And（而且）顯示 `修法：...`
And（而且）三動作區主要建議是 `先查看掃描發現`。

## 研究來源
- `docs/grimo/specs/2026-05-17-S190-security-risk-reason-ui.md` §2.3, §2.5, §4
- `frontend/src/api/security.ts`
- `frontend/src/components/v2/tabs/SecurityTab.tsx`
- `frontend/src/components/RiskBadge.tsx`
- `frontend/src/components/v2/tabs/SecurityTab.test.tsx`

## 先做 POC
- POC：not required — component 已以 props render，可用 Vitest + Testing Library 驗證 DOM text。
- Fixture：
  - `low-tools-scripts-report`: LOW + 0 findings + tools + scripts reason → 顯示 reason cards 和三動作。
  - `low-tools-only-report`: LOW + 0 findings + allowed-tools-only reason → 顯示 tools list。
  - `none-report`: NONE + 0 findings + no capabilities reason → label `未發現風險`。
  - `high-finding-report`: HIGH + W008 finding → finding row 不被 reason section 破壞。

## 正式程式怎麼做
- Class / file 名稱：
  - `frontend/src/api/security.ts`
  - `frontend/src/components/v2/tabs/SecurityTab.tsx`
  - `frontend/src/components/RiskBadge.tsx`
- 入口：`SecurityTab({ report, riskLevel })`
- 必要行為：
  - `SecurityReport` type 新增 optional/defensive `riskReasons?: SecurityRiskReason[]`。
  - 新增 `RiskReasonSection`，有 `riskReasons` 時顯示；舊 API 沒此欄位時不 crash。
  - LOW 標題是 `為什麼是低風險？`，NONE 可顯示 `為什麼是未發現風險？`，HIGH/MEDIUM 可顯示 `為什麼是高風險？` / `為什麼是中風險？`。
  - reason detail 以純文字 render；evidence 以 chips/list 顯示並允許長字斷行。
  - 三動作固定：LOW + empty findings 顯示 `下載技能`、`查看檔案`、`回報疑慮`，且 `下載技能` 不 disabled。
  - HIGH/MEDIUM + findings 時主要建議顯示 `先查看掃描發現`。
  - `NONE` user-facing label 改為 `未發現風險`；tooltip/caveat 保留「不代表 100% 安全」。
  - `LOW + findings=[]` 空狀態改成 `沒有需要修改的掃描發現` 並補 `scanner 沒有找到 issue code，不代表技能沒有任何能力風險`。
- Finding / response / DB 欄位：
  - `riskReasons[].evidence`: tools/scripts 顯示在 UI。
  - `riskReasons[].action`: 可用來決定 action copy，但不要讓 LOW 下載 disabled。

## 單元測試 / 整合測試
- `SecurityTab.test.tsx`
  - `@DisplayName("AC-S190-1: LOW tools + scripts reasons render non-engineer copy")`（Vitest `it(...)` 名稱含 AC）
  - `@DisplayName("AC-S190-1b: allowed-tools-only LOW renders all tool names and not only engineering field label")`
  - `@DisplayName("AC-S190-2: LOW + empty findings shows no-fix finding copy and caveat")`
  - `@DisplayName("AC-S190-3: NONE + empty findings renders 未發現風險 and no-capability reason")`
  - `@DisplayName("AC-S190-4: HIGH finding row remains visible with remediation")`
  - `@DisplayName("AC-S190-8: LOW actions show download/view/report and download is enabled")`
- `RiskBadge.test.tsx`
  - `@DisplayName("AC-S190-3: NONE renders 未發現風險 label")`
  - `@DisplayName("AC-S190-10: NONE caveat tooltip still says not 100% safe")`

## 會改哪些檔案
- `frontend/src/api/security.ts`
- `frontend/src/components/v2/tabs/SecurityTab.tsx`
- `frontend/src/components/v2/tabs/SecurityTab.test.tsx`
- `frontend/src/components/RiskBadge.tsx`
- `frontend/src/components/RiskBadge.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- SecurityTab.test.tsx RiskBadge.test.tsx`

## 前置條件
- S190-T01 PASS
- S190-T02 PASS

## 狀態
PASS

## Result
Date: 2026-05-18

Tests:
- `cd frontend && npm test -- SecurityTab.test.tsx RiskBadge.test.tsx` → PASS (22 tests)
- `cd frontend && npm run typecheck` → PASS

Files changed:
- `frontend/src/api/security.ts`
- `frontend/src/components/v2/tabs/SecurityTab.tsx`
- `frontend/src/components/v2/tabs/SecurityTab.test.tsx`
- `frontend/src/components/RiskBadge.tsx`
- `frontend/src/components/RiskBadge.test.tsx`

Notes:
- RED first failed because the UI still showed `無風險`, had no `riskReasons` section, no non-engineer allowed-tools/scripts explanation, and no LOW/HIGH action strip.
- GREEN renders `riskReasons[]` defensively, shows `為什麼是低風險？` / `為什麼是未發現風險？`, displays evidence chips for tools/scripts, and changes NONE label to `未發現風險`.
- LOW + empty findings now says `沒有需要修改的掃描發現` plus the caveat `scanner 沒有找到 issue code，不代表技能沒有任何能力風險。`
- LOW actions show enabled `下載技能`, plus `查看檔案` and `回報疑慮`; HIGH/MEDIUM findings show `先查看掃描發現`.
