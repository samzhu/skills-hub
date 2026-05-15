# S176-T03: Version upload keeps package name independent

## 對應規格
S176：Explicit Publish Skill Name（發佈頁手填平台 skill name + 允許重名）

## 這個 task 要做什麼
S032 之前要求 `PUT /api/v1/skills/{id}/versions` 的 `SKILL.md name` 必須等於平台 `skills.name`。S176 改成平台 name 由 publish form `skillName` 決定，包內 `SKILL.md name` 只代表 package metadata，所以這個 guard 會阻擋合法使用情境。本 task 移除 S032 mismatch guard，並把舊 failing test 改成新的 passing BDD。

## 使用者情境（BDD）
Given（前提）skill A 的平台名稱是 `platform-skill`  
And（而且）新版本 zip 內 `SKILL.md` frontmatter `name="internal-package-v2"`  
When（動作）owner 呼叫 `PUT /api/v1/skills/{A}/versions`，version=`1.1.0`  
Then（結果）HTTP 200 / service call 成功，skill `latestVersion` 變成 `1.1.0`  
And（而且）新 `skill_versions.frontmatter->>'name' = "internal-package-v2"`  
And（而且）version 重複檢查仍保留；同一 skill 同 version 第二次上傳仍丟 `VersionExistsException`

## 研究來源
- `docs/grimo/specs/2026-05-15-S176-explicit-publish-skill-name.md §4.4`
- `docs/grimo/specs/archive/2026-05-01-S032-version-name-consistency.md`：舊 invariant 的前提是 `POST /upload` 從 `SKILL.md name` 建 aggregate；S176 已反轉此前提
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillUploadAllowedToolsTest.java`：目前有 `AC-S032` mismatch fail test

## Requires
- Java 25 toolchain
- Docker daemon reachable（backend Testcontainers / pgvector）
- S176-T02 PASS（平台 name 已由 explicit skillName 建立）

## 先做 POC
- POC：not required — 移除 service guard + 更新既有 test expectation 即可驗證。

## 正式程式怎麼做
- Class / file 名稱：
  - `SkillCommandService.java`
  - `SkillUploadAllowedToolsTest.java`
  - `SkillUploadExplicitNameTest.java`（若 T02 已放 version BDD，可延伸同檔）
- 入口：`SkillCommandService.addVersion(...)` / `PUT /api/v1/skills/{id}/versions`
- 必要行為：
  - 移除 `zipName != skill.getName()` 的 `IllegalArgumentException`。
  - `SkillValidator` 仍驗 `SKILL.md name` 格式；缺 name/格式錯仍是 validation error。
  - `PublishVersionCommand.frontmatter` 仍保存 validation metadata，包含 package `name`。
  - 版本號唯一檢查 `existsBySkillIdAndVersion` 不可被移除。

## 單元測試 / 整合測試
- `SkillUploadAllowedToolsTest`
  - 移除或改名舊 `AC-S032` fail test
  - `@DisplayName("AC-S176-5: addVersion accepts SKILL.md name different from platform skill name")`
- `SkillUploadExplicitNameTest`
  - 可補 `@DisplayName("AC-S176-5: addVersion stores package name in version frontmatter")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillUploadAllowedToolsTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillUploadExplicitNameTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*SkillUploadAllowedToolsTest" --tests "*SkillUploadExplicitNameTest"`

## 前置條件
- S176-T02 PASS

## 狀態
pending（待做）
