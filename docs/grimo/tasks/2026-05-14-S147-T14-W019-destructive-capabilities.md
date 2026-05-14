# S147-T14: W019 Destructive capabilities

## 對應規格
S147：Issue-code scanner architecture

## 使用者情境（BDD）
Given（前提）skill 宣告會修改 cloud infrastructure、databases、repositories、CI/CD 或 team SaaS，而且沒有 dry-run 或人工確認邊界
When（動作）`DestructiveCapabilities` 掃描這個 package
Then（結果）回傳 `issueCode=W019`
And（而且）read-only 或 dry-run checks 不會回傳 W019

## 研究來源
- Snyk toxic-flow W019: https://github.com/snyk/agent-scan/blob/main/docs/issue-codes.md
- Toxic flow concept: https://snyk.io/articles/understanding-toxic-flows-mcp/

## 先做 POC
- 建立 `poc/S147/W019/`。
- POC 要先試 T01 的 `LlmJudge + LlmIssueRule` 路徑，不打真 Gemini；使用 canned JSON 和 `CapturingStubChatModel`。
- Fixture：
  - `terraform-apply-from-url`: 讀任意 URL instructions 後執行 `terraform apply` → W019。
  - `drop-db`: 指示 agent 執行破壞性 DB changes → W019。
  - `terraform-plan-only`: 只做 dry-run plan 並回報結果 → 不回報 W019。
- POC 必須證明 `LlmJudge` prompt 包含 `W019` rule definition，且 canned JSON 回來後會變成 `issueCode=W019` finding。
- POC 跑完必須印出 `S147 W019 POC PASS`。

## 正式程式怎麼做
- Class 名稱：`DestructiveCapabilities`。
- 實作 `LlmIssueRule`，不要直接實作 `SecurityAnalyzer`，也不要自己呼叫 `ChatClient`。
- Phase 由 T01 擴充後的 `LlmJudge` 統一處理；這個 class 只提供 `issueCode=W019`、rule prompt、positive example、negative example、category。
- Rule id 範例：`W019_INFRA_MUTATION`, `W019_DATABASE_DESTRUCTIVE_ACTION`。
- Financial transaction 仍歸 W009；W019 看的是共享基礎設施或共享資源 mutation。

## 單元測試
- `DestructiveCapabilitiesTest`
  - `@DisplayName("AC-S147-W019: shared destructive action reports W019")`
  - `@DisplayName("AC-S147-W019: dry-run infrastructure plan does not report W019")`

## 會改哪些檔案
- `poc/S147/W019/`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/DestructiveCapabilities.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/engines/LlmJudgeIssueRuleIntegrationTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/detectors/DestructiveCapabilitiesTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*DestructiveCapabilitiesTest" --tests "*LlmJudgeIssueRuleIntegrationTest"`

## 前置條件
- S147-T01 PASS

## 狀態
pending（待做）
