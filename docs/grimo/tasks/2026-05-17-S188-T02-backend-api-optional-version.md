# S188-T02: Backend Optional Version API

## 對應規格
S188：版本標籤可自訂與自動流水號

## 這個 task 要做什麼
把 `/api/v1/skills/upload` 和 `/api/v1/skills/{id}/versions` 的 `version` multipart 欄位從必填改成可省略。完成後，curl 或前端不送 `version` 時，後端會自己產 `1` 或下一個純數字版本；送 unsafe label 時會在寫 DB 與 storage 前失敗。

## 使用者情境（BDD）
Given（前提）DB 沒有 `docker-helper`
When（動作）Alice 上傳 multipart，包含 `file`、`skillName`、`category`，但沒有 `version`
Then（結果）HTTP 201
And（而且）`skills.latest_version='1'`
And（而且）`skill_versions.version='1'`
And（而且）storage path 是 `skills/{skillId}/1/skill.zip`

## 研究來源
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java`：目前 `@RequestParam("version")` 是 required
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`：目前 service 直接用傳入 version 寫 aggregate / storage / SkillVersion
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`：目前 `recordVersionPublished` 強制 semver
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersionRepository.java`：既有 `existsBySkillIdAndVersion` 與 `findBySkillIdOrderByPublishedAtDesc`

## 先做 POC
- POC：not required — 行為都可用既有 repository、service test 與 MockMvc/multipart test 驗證。

## 正式程式怎麼做
- Class / file 名稱：`SkillCommandController`、`SkillCommandService`、`Skill`、`SkillVersionPublishedEvent` 等版本說明文件。
- 入口：`POST /api/v1/skills/upload`、`PUT /api/v1/skills/{id}/versions`。
- 必要行為：
  - controller 的 `version` 改 `required = false`。
  - service 在呼叫 `recordVersionPublished` 與建立 storage path 前先 resolve version label。
  - `Skill.recordVersionPublished` 不再強制 semver，只接受 S188 safe label。
  - duplicate version 繼續回 409 `VERSION_EXISTS`。
  - unsafe label 回 400，且不新增 DB row、不呼叫 `storageService.upload(...)`。
  - 並發空白版本若撞到 DB unique，轉成 409 或既有 version conflict response。
- Finding / response / DB 欄位：
  - `skills.latest_version`: resolved version label。
  - `skill_versions.version`: resolved version label。
  - `storagePath`: `skills/{skillId}/{resolvedVersion}/skill.zip`。

## 單元測試 / 整合測試
- `SkillCommandServiceTest` 或現有 upload/version tests
  - `@DisplayName("AC-S188-1: upload without version stores version 1")`
  - `@DisplayName("AC-S188-2: add version without version stores max numeric plus one")`
  - `@DisplayName("AC-S188-3: custom version label is stored and duplicate returns conflict")`
  - `@DisplayName("AC-S188-4: unsafe version label returns 400 before storage upload")`
  - `@DisplayName("AC-S188-8: concurrent blank version keeps one row and reports version conflict")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersionPublishedEvent.java`
- backend command/service/controller tests

## 驗證方式
執行：`cd backend && ./gradlew test --tests '*SkillCommand*' --tests '*SkillUpload*' --tests '*VersionLabel*'`

## 前置條件
- S188-T01 PASS

## 狀態
PASS（2026-05-17）

## 實作結果
- `SkillCommandController` 的 `/upload` 與 `/{id}/versions` multipart `version` 改成 optional。
- `SkillCommandService` 先用 `VersionLabelPolicy` resolve 實際 label，再寫 `Skill.latestVersion`、`skill_versions.version` 與 `storagePath`。
- `Skill.recordVersionPublished` 改用 S188 safe label 驗證，不再拒絕 `1`、`2`、`2026.05-hotfix`、`0.1.0` 這類非 semver-only label。
- 既有 duplicate version path 保留 `VersionExistsException`，controller 仍回 409 `VERSION_EXISTS`。

## 驗證結果
- RED：`cd backend && ./gradlew test --tests '*SkillUploadExplicitNameTest' --tests '*SkillCommandControllerSecurityTest' --tests '*SkillPublishForgeryTest'` 先失敗 6 個 AC-S188 測試，包含 upload/PUT 缺 version 與 custom label 被 semver 檢查擋住。
- GREEN：同一 command 通過，Gradle printed `BUILD SUCCESSFUL in 2m 45s`。
