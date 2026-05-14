# S147-T13: W018 Workspace data exposure

## 對應規格
S147：Issue-code scanner architecture

## 使用者情境（BDD）
Given（前提）skill 宣告會讀 workspace files、source code、本機筆記或 project artifacts，並送到外部位置
When（動作）`WorkspaceDataExposure` 掃描這個 package
Then（結果）回傳 `issueCode=W018`
And（而且）正常 local code review，只把結果留在使用者對話裡，不會回傳 W018

## 研究來源
- Snyk toxic-flow W018: https://github.com/snyk/agent-scan/blob/main/docs/issue-codes.md
- Toxic flow concept: https://snyk.io/articles/understanding-toxic-flows-mcp/

## 先做 POC
- 建立 `poc/S147/W018/`。
- POC 要先試 T01 的 `LlmJudge + LlmIssueRule` 路徑，不打真 Gemini；使用 canned JSON 和 `CapturingStubChatModel`。
- Fixture：
  - `repo-to-api`: 讀 repository source，並把檔案送到任意 API → W018。
  - `notes-to-chatbot`: 讀本機 notes，並送到 third-party chat endpoint → W018。
  - `local-code-review`: 讀 repo，但只摘要給使用者 → 不回報 W018。
- POC 必須證明 `LlmJudge` prompt 包含 `W018` rule definition，且 canned JSON 回來後會變成 `issueCode=W018` finding。
- POC 跑完必須印出 `S147 W018 POC PASS`。

## 正式程式怎麼做
- Class 名稱：`WorkspaceDataExposure`。
- 實作 `LlmIssueRule`，不要直接實作 `SecurityAnalyzer`，也不要自己呼叫 `ChatClient`。
- Phase 由 T01 擴充後的 `LlmJudge` 統一處理；這個 class 只提供 `issueCode=W018`、rule prompt、positive example、negative example、category。
- Rule id 範例：`W018_WORKSPACE_TO_EXTERNAL_API`, `W018_LOCAL_NOTES_EXPOSURE`。
- Message 要講清楚：package 宣告了 exposure；這不是 runtime access proof。

## 單元測試
- `WorkspaceDataExposureTest`
  - `@DisplayName("AC-S147-W018: workspace data plus external sink reports W018")`
  - `@DisplayName("AC-S147-W018: local code review does not report W018")`

## 會改哪些檔案
- `poc/S147/W018/`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/WorkspaceDataExposure.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/engines/LlmJudgeIssueRuleIntegrationTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/detectors/WorkspaceDataExposureTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*WorkspaceDataExposureTest" --tests "*LlmJudgeIssueRuleIntegrationTest"`

## 前置條件
- S147-T01 PASS

## 狀態
pending（待做）
