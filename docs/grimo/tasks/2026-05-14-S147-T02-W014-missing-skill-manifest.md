# S147-T02: W014 Missing SKILL.md

## 對應規格
S147：Issue-code scanner architecture

## 使用者情境（BDD）
Given（前提）使用者上傳的 skill package 有 `README.md` 和 `scripts/setup.sh`，但根目錄沒有 `SKILL.md`
When（動作）`MissingSkillManifest` 掃描這個 package
Then（結果）回傳一筆 finding：`issueCode=W014`、severity `LOW`、category `Package Structure`
And（而且）remediation 要用白話告訴作者「請在根目錄補 `SKILL.md`」

## 研究來源
- Snyk issue code W014: https://github.com/snyk/agent-scan/blob/main/docs/issue-codes.md
- agentskills.io package requirement: https://agentskills.io/specification

## 先做 POC
- 建立 `poc/S147/W014/`。
- Fixture：
  - `missing-root-skill`: 有 `README.md`、`scripts/setup.sh`，但沒有 `SKILL.md` → 回報 W014.
  - `nested-skill-only`: 有 `nested/SKILL.md`，但根目錄沒有 `SKILL.md` → 回報 W014.
  - `valid-root-skill`: 根目錄有 `SKILL.md` → 不回報 W014。
- POC 跑完必須印出 `S147 W014 POC PASS`。

## 正式程式怎麼做
- Class 名稱：`MissingSkillManifest`。
- 實作 `IssueDetector`。
- Phase：`STATIC`。
- Rule id：`W014_MISSING_SKILL_MD`。
- `filePath` 可以是 `null`；`line` 可以是 `null`。
- 這裡只產生 finding，不在 detector 內擋掉上傳；如果 publish validation 其他地方要拒絕不合法 package，維持原本流程。

## 單元測試
- `MissingSkillManifestTest`
  - `@DisplayName("AC-S147-W014: package without root SKILL.md reports W014")`
  - `@DisplayName("AC-S147-W014: root SKILL.md package reports no finding")`

## 會改哪些檔案
- `poc/S147/W014/`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/MissingSkillManifest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/detectors/MissingSkillManifestTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*MissingSkillManifestTest"`

## 前置條件
- S147-T01 PASS

## 狀態
pending（待做）
