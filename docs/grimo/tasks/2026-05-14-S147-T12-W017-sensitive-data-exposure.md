# S147-T12: W017 Sensitive data exposure

## 對應規格
S147：Issue-code scanner architecture

## 使用者情境（BDD）
Given（前提）skill 宣告會讀敏感資料，並把原文或摘要送到外部位置
When（動作）`SensitiveDataExposure` 掃描這個 package
Then（結果）回傳 `issueCode=W017`
And（而且）只有讀敏感資料、沒有外送位置時，不回傳 high-confidence 的 W017 finding

## 研究來源
- Snyk toxic-flow W017: https://github.com/snyk/agent-scan/blob/main/docs/issue-codes.md
- Toxic flow concept: https://snyk.io/articles/understanding-toxic-flows-mcp/

## 先做 POC
- 建立 `poc/S147/W017/`。
- POC 要先試 T01 的 `LlmJudge + LlmIssueRule` 路徑，不打真 Gemini；使用 canned JSON 和 `CapturingStubChatModel`。
- Fixture：
  - `email-to-webhook`: 讀 email/DM，並把摘要或原文送到 webhook → W017。
  - `vault-to-third-party`: 讀 credential vault，並把 token 送到 third-party debug endpoint → W017。
  - `local-redaction`: 讀 PII，但只在本機遮罩後回給使用者 → 不回報 high-confidence W017。
- POC 必須證明 `LlmJudge` prompt 包含 `W017` rule definition，且 canned JSON 回來後會變成 `issueCode=W017` finding。
- POC 跑完必須印出 `S147 W017 POC PASS`。

## 正式程式怎麼做
- Class 名稱：`SensitiveDataExposure`。
- 實作 `LlmIssueRule`，不要直接實作 `SecurityAnalyzer`，也不要自己呼叫 `ChatClient`。
- Phase 由 T01 擴充後的 `LlmJudge` 統一處理；這個 class 只提供 `issueCode=W017`、rule prompt、positive example、negative example、category。
- Rule id 範例：`W017_SENSITIVE_DATA_TO_WEBHOOK`, `W017_CREDENTIAL_VAULT_EXPOSURE`。
- 這是 package-level declared-flow，不是 runtime MCP graph proof。Finding message 要明講「package 宣告了這個 flow」。

## 單元測試
- `SensitiveDataExposureTest`
  - `@DisplayName("AC-S147-W017: sensitive data plus external sink reports W017")`
  - `@DisplayName("AC-S147-W017: local redaction flow does not report high-confidence W017")`

## 會改哪些檔案
- `poc/S147/W017/`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/SensitiveDataExposure.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/engines/LlmJudgeIssueRuleIntegrationTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/detectors/SensitiveDataExposureTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*SensitiveDataExposureTest" --tests "*LlmJudgeIssueRuleIntegrationTest"`

## 前置條件
- S147-T01 PASS

## 狀態
pending（待做）
