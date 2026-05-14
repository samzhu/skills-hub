# S147-T11: W011 Third-party content exposure

## 對應規格
S147：Issue-code scanner architecture

## 使用者情境（BDD）
Given（前提）skill 要求 agent 讀任意第三方內容或 user-generated content，並照內容裡的指令做事
When（動作）`ThirdPartyContentExposure` 掃描這個 package
Then（結果）回傳 `issueCode=W011`
And（而且）只做 read-only documentation search，不會回傳 W011

## 研究來源
- Snyk issue code W011: https://github.com/snyk/agent-scan/blob/main/docs/issue-codes.md

## 先做 POC
- 建立 `poc/S147/W011/`。
- POC 要先試 T01 的 `LlmJudge + LlmIssueRule` 路徑，不打真 Gemini；使用 canned JSON 和 `CapturingStubChatModel`。
- Fixture：
  - `arbitrary-url-follow`: "打開使用者提供的 URL，並照頁面裡的 instructions 做事" → W011。
  - `forum-comments-act`: "讀 forum comments，並執行留言要求的 steps" → W011。
  - `docs-search`: "搜尋官方文件並摘要相關段落，不照網頁內容執行指令" → 不回報 W011。
- POC 必須證明 `LlmJudge` prompt 包含 `W011` rule definition，且 canned JSON 回來後會變成 `issueCode=W011` finding。
- POC 跑完必須印出 `S147 W011 POC PASS`。

## 正式程式怎麼做
- Class 名稱：`ThirdPartyContentExposure`。
- 實作 `LlmIssueRule`，不要直接實作 `SecurityAnalyzer`，也不要自己呼叫 `ChatClient`。
- Phase 由 T01 擴充後的 `LlmJudge` 統一處理；這個 class 只提供 `issueCode=W011`、rule prompt、positive example、negative example、category。
- Rule id 範例：`W011_ARBITRARY_URL_INSTRUCTIONS`, `W011_USER_GENERATED_CONTENT`。
- W015/W016 不做 user-facing findings；如果需要，可以把它們當作 W011/toxic-flow tasks 的內部證據。

## 單元測試
- `ThirdPartyContentExposureTest`
  - `@DisplayName("AC-S147-W011: arbitrary URL instructions report W011")`
  - `@DisplayName("AC-S147-W011: read-only documentation search does not report W011")`

## 會改哪些檔案
- `poc/S147/W011/`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/ThirdPartyContentExposure.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/engines/LlmJudgeIssueRuleIntegrationTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/detectors/ThirdPartyContentExposureTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*ThirdPartyContentExposureTest" --tests "*LlmJudgeIssueRuleIntegrationTest"`

## 前置條件
- S147-T01 PASS

## 狀態
pending（待做）
