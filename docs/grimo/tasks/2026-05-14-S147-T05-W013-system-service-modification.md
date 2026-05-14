# S147-T05: W013 System service modification

## 對應規格
S147：Issue-code scanner architecture

## 使用者情境（BDD）
Given（前提）package 裡的 script 或指令會修改 systemd、LaunchAgent/LaunchDaemon、cron、sudoers、shell 啟動檔或系統全域設定
When（動作）`SystemServiceModification` 掃描這個 package
Then（結果）回傳 `issueCode=W013`
And（而且）只在使用者指定的本機輸出路徑寫產生檔，不會回傳 W013

## 研究來源
- Snyk issue code W013: https://github.com/snyk/agent-scan/blob/main/docs/issue-codes.md

## 先做 POC
- 建立 `poc/S147/W013/`。
- Fixture：
  - `systemd-enable`: `systemctl enable skill-agent.service` → W013。
  - `launch-daemon`: 寫入 `/Library/LaunchDaemons/...plist` → W013。
  - `cron-persistence`: `crontab -e` 或寫入 cron entry → W013。
  - `generated-report`: 寫入 `./out/report.md` → 不回報 W013。
- POC 跑完必須印出 `S147 W013 POC PASS`。

## 正式程式怎麼做
- Class 名稱：`SystemServiceModification`。
- 實作 `IssueDetector`。
- Phase：`STATIC`。
- Rule id 範例：`W013_SYSTEMD_MODIFICATION`, `W013_LAUNCH_DAEMON`, `W013_CRON_PERSISTENCE`。

## 單元測試
- `SystemServiceModificationTest`
  - `@DisplayName("AC-S147-W013: system service modification reports W013")`
  - `@DisplayName("AC-S147-W013: local generated file write does not report W013")`

## 會改哪些檔案
- `poc/S147/W013/`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/SystemServiceModification.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/detectors/SystemServiceModificationTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*SystemServiceModificationTest"`

## 前置條件
- S147-T01 PASS

## 狀態
pending（待做）
