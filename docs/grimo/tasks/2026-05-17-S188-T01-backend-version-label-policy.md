# S188-T01: Backend VersionLabelPolicy

## 對應規格
S188：版本標籤可自訂與自動流水號

## 這個 task 要做什麼
新增一個後端 `VersionLabelPolicy`，把「空白版本轉成流水號」和「自訂版本標籤安全檢查」集中在同一個 class。完成後，程式可以拿到 `1`、`2`、`2026.05-hotfix` 這類合法標籤，也會在 `/`、`\`、空白、`..`、超過 20 字元、純數字 `0` 時直接丟錯。

## 使用者情境（BDD）
Given（前提）某個 skill 已有版本 `1`、`2`、`2026.05-hotfix`
When（動作）後端呼叫 `nextOrRequested(null, existingVersions)`
Then（結果）回傳 `3`
And（而且）呼叫 `nextOrRequested("release-1", existingVersions)` 回傳 `release-1`
And（而且）呼叫 `nextOrRequested("../prod", existingVersions)` 丟出固定英文錯誤訊息

## 研究來源
- `docs/grimo/specs/2026-05-16-S188-version-label-auto-sequence.md` §2.3 版本標籤規則
- `backend/src/main/resources/db/migration/V1__initial_schema.sql`：`version` 欄位長度是 `VARCHAR(20)`
- `docs/grimo/specs/archive/2026-05-01-S056-version-semver-validation.md`：原 semver 驗證的安全目的

## 先做 POC
- POC：not required — S188 已在 spec 內列出 regex 與本機 code path；這個 task 可直接用 unit test 驗證。

## 正式程式怎麼做
- Class / file 名稱：`backend/src/main/java/io/github/samzhu/skillshub/skill/command/VersionLabelPolicy.java`
- 入口：`SkillCommandService` 後續 task 會呼叫；本 task 先完成 policy class 與 test。
- 必要行為：
  - `initialOrRequested(null)` 與 `initialOrRequested("   ")` 回傳 `"1"`。
  - `nextOrRequested(null, ["1", "2", "2026.05-hotfix"])` 回傳 `"3"`。
  - 自訂標籤允許 ASCII letters / digits / dot / underscore / hyphen，長度 1-20。
  - 自訂標籤拒絕 `/`、`\`、空白、`..`、純數字 `"0"`。
  - 錯誤訊息固定：`Version must be 1-20 characters and contain only letters, numbers, dot, underscore, or hyphen`。
- Finding / response / DB 欄位：
  - `version`: 回傳值會在 T02 寫入 `skills.latest_version`、`skill_versions.version` 與 GCS path。

## 單元測試 / 整合測試
- `VersionLabelPolicyTest`
  - `@DisplayName("AC-S188-1: initial blank version becomes 1")`
  - `@DisplayName("AC-S188-2: blank next version uses max numeric plus one")`
  - `@DisplayName("AC-S188-3: custom version label is preserved")`
  - `@DisplayName("AC-S188-4: unsafe version label is rejected")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/VersionLabelPolicy.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/VersionLabelPolicyTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests '*VersionLabelPolicyTest'`

## 前置條件
- 無

## Status
PASS

## Result
Date: 2026-05-17
Test: `initialBlankVersionBecomesOne`, `blankNextVersionUsesMaxNumericPlusOne`, `customVersionLabelIsPreserved`, `unsafeVersionLabelIsRejected` (`backend/src/test/java/io/github/samzhu/skillshub/skill/command/VersionLabelPolicyTest.java`)
Files changed:
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/VersionLabelPolicy.java` (new)
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/VersionLabelPolicyTest.java` (new)
Verification:
- RED：`cd backend && ./gradlew test --tests '*VersionLabelPolicyTest'` failed at `compileTestJava` because `VersionLabelPolicy` did not exist.
- GREEN：`cd backend && ./gradlew test --tests '*VersionLabelPolicyTest'` passed; Gradle printed `BUILD SUCCESSFUL in 2m 5s`.
Notes: T01 only adds the policy class and tests. `SkillCommandService`, controller optional request params, and aggregate semver replacement remain T02 scope.
