# S147-T16: Upload event scan pipeline and detail page alignment

## 對應規格
S147：Issue-code scanner architecture

## 使用者情境（BDD）
Given（前提）使用者上傳一個 skill zip，裡面同時有一個 static issue-code finding 和一個 semantic/declared-flow finding
When（動作）`SkillVersionPublishedEvent` 觸發 `ScanOrchestrator`
Then（結果）啟用中的 issue detectors 會走現有 event pipeline 執行
And（而且）`skill_versions.risk_assessment.findings` 會存 `issueCode/remediation/confidence`
And（而且）`/api/v1/skills/{id}/security-report` 會回傳動態 categories 和 finding summaries
And（而且）frontend security tab 會渲染 API 回來的 categories/findings，不再只寫死四張卡片

## 研究來源
- 現有 event 流程：`ScanOrchestrator` 已經監聽 `SkillVersionPublishedEvent`。
- 現有整合測試參考：`RiskAssessmentIntegrationTest`。

## 實作提醒
- 不要替換 `ScanOrchestrator`。
- 確認新的 `IssueDetector` beans 會被 Spring 當成 `SecurityAnalyzer` 找到。
- 確認新的 `LlmIssueRule` beans 會被 `LlmJudge` 注入；上傳流程只看到一個 `llm-judge` analyzer，但 `risk_assessment.findings` 仍要能存出 W007/W009/W011/W017/W018/W019/W020 這些 semantic issue codes。
- 保留用 `sourceEventId` 做 idempotency 的既有行為。
- 擴充 `frontend/src/api/security.ts` 裡的 frontend types。
- 更新 `SecurityHeroCard` 和 `SecurityTab`，改用 `categories/findings`。
- 保留 legacy `checks`，讓既有 callers 和 score calculation 繼續可用。

## 整合測試
- Backend：
  - `RiskAssessmentIntegrationTest`
    - `@DisplayName("AC-S147-PIPELINE: upload event stores issue-code findings in risk assessment")`
  - `SecurityReportControllerTest`
    - `@DisplayName("AC-S147-PIPELINE: security report exposes dynamic categories and finding summaries")`
- Frontend：
  - `SecurityTab.test.tsx`
    - `it('AC-S147-PIPELINE: renders dynamic security categories and issue-code findings')`
  - `SecurityHeroCard.test.tsx`
    - `it('AC-S147-PIPELINE: shows most severe returned category')`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScanOrchestrator.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/RiskAssessmentIntegrationTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/SecurityReportControllerTest.java`
- `frontend/src/api/security.ts`
- `frontend/src/hooks/useSecurityReport.ts`
- `frontend/src/components/v2/SecurityHeroCard.tsx`
- `frontend/src/components/v2/SecurityHeroCard.test.tsx`
- `frontend/src/components/v2/tabs/SecurityTab.tsx`
- `frontend/src/components/v2/tabs/SecurityTab.test.tsx`

## 驗證方式
執行：
- `cd backend && ./gradlew test --tests "*RiskAssessmentIntegrationTest" --tests "*SecurityReportControllerTest"`
- `cd frontend && npm test -- SecurityTab SecurityHeroCard`

## 前置條件
- S147-T02 PASS
- S147-T03 PASS
- S147-T04 PASS
- S147-T05 PASS
- S147-T06 PASS
- S147-T07 PASS
- S147-T08 PASS
- S147-T09 PASS
- S147-T10 PASS
- S147-T11 PASS
- S147-T12 PASS
- S147-T13 PASS
- S147-T14 PASS
- S147-T15 PASS

## 狀態
pending（待做）
