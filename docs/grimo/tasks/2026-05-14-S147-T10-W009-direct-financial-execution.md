# S147-T10: W009 Direct financial execution

## 對應規格
S147：Issue-code scanner architecture

## 使用者情境（BDD）
Given（前提）skill 要求 agent 直接轉帳、下單、提款或轉 crypto
When（動作）`DirectFinancialExecution` 掃描這個 package
Then（結果）回傳 `issueCode=W009`
And（而且）只做 portfolio analysis 或 balance lookup，不會回傳 W009

## 研究來源
- Snyk issue code W009: https://github.com/snyk/agent-scan/blob/main/docs/issue-codes.md

## 先做 POC
- 建立 `poc/S147/W009/`。
- POC 要先試 T01 的 `LlmJudge + LlmIssueRule` 路徑，不打真 Gemini；使用 canned JSON 和 `CapturingStubChatModel`。
- Fixture：
  - `market-order`: "使用 exchange API 直接下 market buy order" → W009。
  - `crypto-transfer`: "transfer USDC to address..." → W009。
  - `portfolio-analysis`: "分析 portfolio 配置並提出 rebalance 建議，不直接下單" → 不回報 W009。
- POC 必須證明 `LlmJudge` prompt 包含 `W009` rule definition，且 canned JSON 回來後會變成 `issueCode=W009` finding。
- POC 跑完必須印出 `S147 W009 POC PASS`。

## 正式程式怎麼做
- Class 名稱：`DirectFinancialExecution`。
- 實作 `LlmIssueRule`，不要直接實作 `SecurityAnalyzer`，也不要自己呼叫 `ChatClient`。
- Phase 由 T01 擴充後的 `LlmJudge` 統一處理；這個 class 只提供 `issueCode=W009`、rule prompt、positive example、negative example、category。
- Rule id 範例：`W009_MARKET_ORDER`, `W009_FUNDS_TRANSFER`。

## 單元測試
- `DirectFinancialExecutionTest`
  - `@DisplayName("AC-S147-W009: market order instruction reports W009")`
  - `@DisplayName("AC-S147-W009: portfolio analysis does not report W009")`

## 會改哪些檔案
- `poc/S147/W009/`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/DirectFinancialExecution.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/engines/LlmJudgeIssueRuleIntegrationTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/detectors/DirectFinancialExecutionTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*DirectFinancialExecutionTest" --tests "*LlmJudgeIssueRuleIntegrationTest"`

## 前置條件
- S147-T01 PASS

## 狀態
pending（待做）
