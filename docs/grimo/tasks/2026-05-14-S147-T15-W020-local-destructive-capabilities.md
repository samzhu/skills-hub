# S147-T15: W020 Local destructive capabilities

## 對應規格
S147：Issue-code scanner architecture

## 使用者情境（BDD）
Given（前提）skill 宣告會刪除或覆寫 local files、settings 或 workspace state，而且沒有明確 allowlist 或確認步驟
When（動作）`LocalDestructiveCapabilities` 掃描這個 package
Then（結果）回傳 `issueCode=W020`
And（而且）只在明確 output path 寫入產生檔，不會回傳 W020

## 研究來源
- Snyk toxic-flow W020: https://github.com/snyk/agent-scan/blob/main/docs/issue-codes.md
- Toxic flow concept: https://snyk.io/articles/understanding-toxic-flows-mcp/

## 先做 POC
- 建立 `poc/S147/W020/`。
- POC 要先試 T01 的 `LlmJudge + LlmIssueRule` 路徑，不打真 Gemini；使用 canned JSON 和 `CapturingStubChatModel`。
- Fixture：
  - `delete-workspace`: `rm -rf` 或「刪除符合條件的 project files」，但沒有 allowlist → W020。
  - `overwrite-settings`: 沒有確認步驟就修改 shell/editor settings → W020。
  - `write-output-dir`: 只把產生的 report 寫到 `./out/` → 不回報 W020。
- POC 必須證明 `LlmJudge` prompt 包含 `W020` rule definition，且 canned JSON 回來後會變成 `issueCode=W020` finding。
- POC 跑完必須印出 `S147 W020 POC PASS`。

## 正式程式怎麼做
- Class 名稱：`LocalDestructiveCapabilities`。
- 實作 `LlmIssueRule`，不要直接實作 `SecurityAnalyzer`，也不要自己呼叫 `ChatClient`。
- Phase 由 T01 擴充後的 `LlmJudge` 統一處理；這個 class 只提供 `issueCode=W020`、rule prompt、positive example、negative example、category。
- Rule id 範例：`W020_LOCAL_DELETE`, `W020_SETTINGS_OVERWRITE`。
- 如果既有 `PatternScanner` 也報 `DANGEROUS_COMMAND_RM_RF`，先保留兩筆 findings；除非後續明確實作 dedup。

## 單元測試
- `LocalDestructiveCapabilitiesTest`
  - `@DisplayName("AC-S147-W020: local destructive action reports W020")`
  - `@DisplayName("AC-S147-W020: explicit output directory write does not report W020")`

## 會改哪些檔案
- `poc/S147/W020/`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/LocalDestructiveCapabilities.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/engines/LlmJudgeIssueRuleIntegrationTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/detectors/LocalDestructiveCapabilitiesTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*LocalDestructiveCapabilitiesTest" --tests "*LlmJudgeIssueRuleIntegrationTest"`

## 前置條件
- S147-T01 PASS

## 狀態
pending（待做）
