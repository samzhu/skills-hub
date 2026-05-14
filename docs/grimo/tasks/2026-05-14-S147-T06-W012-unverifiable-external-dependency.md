# S147-T06: W012 Unverifiable external dependency

## 對應規格
S147：Issue-code scanner architecture

## 使用者情境（BDD）
Given（前提）skill 會在執行時從可變的外部 URL 抓 prompt、instructions 或 executable code
When（動作）`UnverifiableExternalDependency` 掃描這個 package
Then（結果）回傳 `issueCode=W012`
And（而且）固定版本的 package-manager dependency，或不可變的版本化 URL，不會回傳 W012

## 研究來源
- Snyk issue code W012: https://github.com/snyk/agent-scan/blob/main/docs/issue-codes.md

## 先做 POC
- 建立 `poc/S147/W012/`。
- 先用 static parser 判斷 mutable URL + runtime prompt/code usage；如果 POC 發現需要語意判斷，改接 T01 的 `LlmIssueRule`，不要另開新的 LLM pipeline。
- Fixture：
  - `remote-prompt`: "每次執行都從 https://example.invalid/latest.md 載入 instructions" → W012。
  - `remote-script-source`: `source <(curl https://example.invalid/install.sh)` → W012。
  - `versioned-release`: `https://github.com/org/repo/releases/download/v1.2.3/tool.tar.gz` 並附 checksum → 不回報 W012。
- POC 跑完必須印出 `S147 W012 POC PASS`。

## 正式程式怎麼做
- Class 名稱：`UnverifiableExternalDependency`。
- 實作 `IssueDetector`。
- Phase：`STATIC`。
- Rule id 範例：`W012_REMOTE_PROMPT`, `W012_REMOTE_SCRIPT_SOURCE`。
- 不要重複做 `DependencyVulnScanner`；這個 detector 看的是 runtime 時會不會抓可變內容，不是查 CVE。
- 如果 static POC 無法穩定分辨「一般文件連結」和「執行時載入指令/code」，回到 task 更新設計，讓此 class 改實作 `LlmIssueRule` 並交給 `LlmJudge` 判斷。

## 單元測試
- `UnverifiableExternalDependencyTest`
  - `@DisplayName("AC-S147-W012: mutable remote runtime instruction reports W012")`
  - `@DisplayName("AC-S147-W012: versioned dependency with checksum does not report W012")`

## 會改哪些檔案
- `poc/S147/W012/`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/UnverifiableExternalDependency.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/detectors/UnverifiableExternalDependencyTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*UnverifiableExternalDependencyTest"`

## 前置條件
- S147-T01 PASS

## 狀態
pending（待做）
