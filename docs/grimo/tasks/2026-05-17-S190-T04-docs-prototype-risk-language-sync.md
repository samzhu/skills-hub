# S190-T04: Docs And Prototype Risk Language Sync

## 對應規格
S190：Security Risk Reason UI

## 這個 task 要做什麼
文件和 prototype 要跟新的風險語言一致。使用者在 docs 看到的定義要跟 UI 一樣：`NONE` 是「未發現風險」，不是保證安全；純 `SKILL.md`、沒有 scripts、沒有 allowed-tools 是 NONE；`LOW + findings=[]` 可能是因為 allowed-tools 或 scripts。

## 使用者情境（BDD）
Given（前提）repo checkout
When（動作）reviewer 打開 `docs/grimo/ui/prototype/Skills Hub Security Reason UI.html`
Then（結果）prototype 有 LOW + 0 findings、NONE + 0 findings、HIGH + findings 三種例子。

Given（前提）reviewer 搜尋 docs/source 中 `LOW risk`、`無風險`、`未發現風險`、`風險層級`、`allowed-tools`、`scripts/`
When（動作）檢查 docs pages
Then（結果）`YourFirstSkillPage` 不再宣稱「只有 SKILL.md、沒有 scripts/」會是 LOW
And（而且）docs 明確說 `NONE = findings=[] + no scripts/ + no allowed-tools`
And（而且）docs 明確說 `LOW + findings=[]` 可能來自 `allowed-tools` 或 `scripts/`
And（而且）user-facing docs/components 不再把 `NONE` label 寫成 `無風險`
And（而且）docs 不把 `finding`、`riskReason`、`RiskLevel` 混成同一件事。

## 研究來源
- `docs/grimo/specs/2026-05-17-S190-security-risk-reason-ui.md` §2.0, §2.5, §5
- `frontend/src/pages/docs/YourFirstSkillPage.tsx`
- `frontend/src/pages/docs/RiskTiersPage.tsx`
- `frontend/src/pages/docs/UploadValidatePage.tsx`
- `docs/grimo/glossary.md`
- `docs/grimo/ui/prototype/Skills Hub Security Reason UI.html`

## 先做 POC
- POC：not required — docs/prototype 是 static source update；用 source scan + reviewer inspection 驗證。
- Fixture：
  - `your-first-skill`: 最小 SKILL.md doc → NONE，不是 LOW。
  - `risk-tiers`: tier definitions → NONE/LOW/MEDIUM/HIGH 與 backend enum 對齊。
  - `prototype`: 三種 scenario 都在 HTML 內可見。

## 正式程式怎麼做
- Class / file 名稱：
  - `frontend/src/pages/docs/YourFirstSkillPage.tsx`
  - `frontend/src/pages/docs/RiskTiersPage.tsx`
  - `frontend/src/pages/docs/UploadValidatePage.tsx`
  - `docs/grimo/glossary.md`
  - `docs/grimo/ui/prototype/Skills Hub Security Reason UI.html`
- 入口：docs pages / static prototype
- 必要行為：
  - `YourFirstSkillPage` 最小 skill 文案改成 `未發現風險` / `NONE`。
  - `RiskTiersPage` 保持或補齊：NONE 是 no findings + no scripts + no allowed-tools；LOW 可由 allowed-tools/scripts 且 0 findings 造成。
  - `UploadValidatePage` 說清楚有 scripts/allowed-tools 會掃描；如果沒有 issue findings，等級可能是 LOW，原因會顯示在安全頁。
  - `docs/grimo/glossary.md` 若還有 `無風險` 作為 user-facing NONE label，要改成 `未發現風險`。
  - Prototype 保留 LOW + tools/scripts、NONE pure docs、HIGH finding examples。
- Finding / response / DB 欄位：
  - 無 DB/API 欄位；這是 docs/prototype sync。

## 單元測試 / 整合測試
- 若 docs page 已有 component tests，更新對應期望字串。
- 若沒有 docs page tests，以 source scan 作為驗證：
  - `rg -n "無風險|未發現風險|LOW risk|allowed-tools|scripts/" frontend/src/pages/docs docs/grimo/ui/prototype docs/grimo/glossary.md`

## 會改哪些檔案
- `frontend/src/pages/docs/YourFirstSkillPage.tsx`
- `frontend/src/pages/docs/RiskTiersPage.tsx`
- `frontend/src/pages/docs/UploadValidatePage.tsx`
- `docs/grimo/glossary.md`
- `docs/grimo/ui/prototype/Skills Hub Security Reason UI.html`
- Docs page tests, if present.

## 驗證方式
執行：`rg -n "無風險|未發現風險|LOW risk|allowed-tools|scripts/" frontend/src/pages/docs docs/grimo/ui/prototype docs/grimo/glossary.md`

## 前置條件
- S190-T03 PASS

## 狀態
PASS（2026-05-18）

## 實作結果
- `frontend/src/pages/docs/YourFirstSkillPage.tsx`：最小 skill 改成 `未發現風險（NONE）`；`LOW` 改為「findings=[] 但有 scripts/ 或 allowed-tools」。
- `frontend/src/pages/docs/RiskTiersPage.tsx`：`NONE` 標題改成 `未發現風險`，並明確寫出 `findings=[] + no scripts/ + no allowed-tools`。
- `frontend/src/pages/docs/UploadValidatePage.tsx` / `FrontmatterPage.tsx`：補上 `allowed-tools` / `scripts/` 造成 LOW 的非工程師說明。
- `docs/grimo/glossary.md`：`Verified` 不再用「無風險」語句描述。
- `docs/grimo/ui/prototype/Skills Hub Security Reason UI.html`：NONE 對照例子顯示 `未發現風險` 且不把它說成安全保證。
- 舊 prototype 中「沒有 scripts/ 就 LOW」的明顯文案已改成 `NONE` / no detected risk。

## 驗證結果
- `rg -n "無風險|未發現風險|LOW risk|allowed-tools|scripts/" frontend/src/pages/docs docs/grimo/ui/prototype docs/grimo/glossary.md`：PASS；沒有 `LOW risk` 或 user-facing `無風險`，剩餘命中都是 `scripts/`、`allowed-tools`、`未發現風險` 的定義與例子。
- `cd frontend && npm test -- SecurityTab.test.tsx RiskBadge.test.tsx`：PASS，2 files / 22 tests。
- `cd frontend && npm run typecheck`：PASS。
