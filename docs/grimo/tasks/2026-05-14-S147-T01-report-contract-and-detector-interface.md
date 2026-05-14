# S147-T01: Report contract and detector interface

## 對應規格
S147：Issue-code scanner architecture

## 使用者情境（BDD）
Given（前提）資料庫裡有舊版 `riskAssessment.findings` JSON，只有 `ruleId/analyzer`，也有新版 finding，帶 `issueCode/remediation/confidence`
When（動作）前端呼叫 `/api/v1/skills/{id}/security-report`
Then（結果）舊版 `checks.shell/paths/secrets/deps` 還在，舊 UI 不會壞
And（而且）新版 `categories[]` 和 `findings[]` 會回傳 issueCode、remediation、confidence、file/line、evidence
And（而且）之後每個 issue detector 都能實作同一個介面，`ScanOrchestrator` 仍只需要吃 `SecurityAnalyzer`
And（而且）既有 `LlmJudge` 可以用 canned JSON POC 回傳 `issueCode/remediation/confidence`，後面語意型檢查不用各自重做 LLM pipeline

## 研究來源
- Snyk issue taxonomy： https://github.com/snyk/agent-scan/blob/main/docs/issue-codes.md
- 要保留的既有程式：`ScanOrchestrator` 已經注入 `List<SecurityAnalyzer>`，也已經會寫 `skill_versions.risk_assessment`。
- 要優先重用的既有程式：`LlmJudge` 已經是 `Phase.LLM`，會讀 Phase 1 findings、frontmatter、`SKILL.md`、scripts；`LlmJudgeTest.CapturingStubChatModel` 已經能攔截 prompt 並回 canned JSON。

## 先做 POC
- 建立 `poc/S147/T01-llm-issue-contract/`。
- POC 不打真 Gemini，只用現有測試模式：`ChatClient.create(new CapturingStubChatModel(cannedJson))`。
- Fixture：
  - `w007-json`: canned JSON 內含 `issueCode=W007`、`remediation`、`confidence=HIGH` → `SecurityFinding` 也要帶同樣欄位。
  - `rule-prompt`: 建一個測試用 `LlmIssueRule`，例如 `issueCode=W007`，確認 `LlmJudge` user prompt 會包含 issue code、rule prompt、positive example、negative example。
  - `disabled-llm`: `new LlmJudge(Optional.empty(), List.of(rule))` → 不回 findings，只回 notice，維持現有 graceful skip。
- POC 跑完必須印出 `S147 T01 LLM ISSUE CONTRACT POC PASS`。

## 實作提醒
- 新增 `SkillIssueCode`, `IssueCategory`, `Confidence`。
- 新增 `IssueDetector extends SecurityAnalyzer`，給 static detector 用。
- 新增 `LlmIssueRule`，給 semantic/declared-flow issue definition 用；它不直接呼叫 LLM，而是被 `LlmJudge` 收集。
- 擴充 `SecurityFinding`，新增 `issueCode`、`remediation`、`confidence`；舊欄位要保留，constructor 變動要同步更新測試。
- 擴充 `LlmJudgement.RiskClaim`，新增 `issueCode`、`remediation`、`confidence`。
- 擴充 `LlmJudge` constructor，注入 `List<LlmIssueRule>`；沒有 rule 時仍可正常運作。
- `LlmJudge` user prompt 要列出可回報的 issue codes 與每個 rule 的 positive/negative example，並要求 LLM 只能回 `SkillIssueCode` enum 內的 code。
- 擴充 `SecurityReportResponse`，新增 `categories` 和 `findings`，保留 `checks`。
- 更新 `SecurityCategoryMapper`，優先用 `issueCode` 分類；舊資料才 fallback 到 `analyzer/ruleId`。

## 單元測試
- `LlmJudgeIssueCodeContractTest`
  - `@DisplayName("AC-S147-1: LlmJudge maps issueCode remediation confidence into SecurityFinding")`
  - `@DisplayName("AC-S147-1: LlmJudge prompt includes registered LlmIssueRule definitions")`
  - `@DisplayName("AC-S147-1: disabled LlmJudge still returns notice without findings")`
- `SecurityReportServiceTest`
  - `@DisplayName("AC-S147-1: legacy risk JSON still returns checks")`
  - `@DisplayName("AC-S147-1: issue-code findings return categories and finding summaries")`
- `SecurityCategoryMapperTest`
  - `@DisplayName("AC-S147-1: issueCode maps to dynamic category before legacy fallback")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/SkillIssueCode.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/IssueCategory.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/Confidence.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/SecurityFinding.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/IssueDetector.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/LlmIssueRule.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/engines/LlmJudge.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/engines/LlmJudgement.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/SecurityCategoryMapper.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/SecurityReportResponse.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/SecurityReportService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*LlmJudgeIssueCodeContractTest" --tests "*SecurityReportServiceTest" --tests "*SecurityReportControllerTest" --tests "*SecurityCategoryMapperTest"`

## 前置條件
- 無。

## 狀態
pending（待做）
