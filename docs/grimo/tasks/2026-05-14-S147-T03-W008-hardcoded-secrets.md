# S147-T03: W008 Hardcoded secrets

## 對應規格
S147：Issue-code scanner architecture

## 使用者情境（BDD）
Given（前提）作者上傳的 package 文字裡有 API key、bearer token、帶密碼的 DB URL 或 private key block
When（動作）`HardcodedSecrets` 掃描這個 package
Then（結果）回傳 `issueCode=W008` finding，而且 evidence 必須遮罩
And（而且）完整 secret 原文不能出現在 evidence、message 或 remediation

## 研究來源
- Snyk issue code W008: https://github.com/snyk/agent-scan/blob/main/docs/issue-codes.md
- 現有可參考 scanner： `SecretScanner`

## 先做 POC
- 建立 `poc/S147/W008/`。
- Fixture：
  - `hardcoded-openai-key`: 測試用 fake `sk-` key → W008，而且 evidence 要遮罩。
  - `db-url-password`: `postgresql://user:secret123@host/db` → W008，而且 evidence 要遮罩。
  - `documentation-placeholder`: `YOUR_API_KEY_HERE` 只是 placeholder → 不回報 W008。
- POC 跑完必須印出 `S147 W008 POC PASS`。

## 正式程式怎麼做
- Class 名稱：`HardcodedSecrets`。
- 實作 `IssueDetector`。
- Phase：`STATIC`。
- Rule id 可以沿用 `SecretScanner` 裡穩定的名稱，但 finding `issueCode` 必須是 `W008`。
- 搬移或共用 masking helper；evidence 只有在安全時才保留前後幾個字元。

## 單元測試
- `HardcodedSecretsTest`
  - `@DisplayName("AC-S147-W008: hardcoded secret reports W008 with masked evidence")`
  - `@DisplayName("AC-S147-W008: placeholder API key does not report W008")`

## 會改哪些檔案
- `poc/S147/W008/`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/HardcodedSecrets.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/detectors/HardcodedSecretsTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*HardcodedSecretsTest"`

## 前置條件
- S147-T01 PASS

## 狀態
pending（待做）
