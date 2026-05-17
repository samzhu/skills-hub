# S187-T04: Backend latest SKILL.md description snapshot

## 對應規格
S187：Skill SKILL.md 編輯頁

## 這個 task 要做什麼
這個 task 完成後，使用者新增 skill 版本時，後端會把新 SKILL.md frontmatter 的 `description` 寫回 `skills.description`。下一次讀 list/detail/search 時，使用者看到的是 latest SKILL.md 的 description，不是舊版本描述。

## 使用者情境（BDD）
Given（前提）DB 有 `skills.id='skill-docker'` 且 `description='Old desc'`
When（動作）Alice 上傳新版本，SKILL.md frontmatter 有 `description='Compose deploy helper'`
Then（結果）`skills.description='Compose deploy helper'`
And（而且）`skill_versions.frontmatter.description='Compose deploy helper'`
And（而且）list/detail API 下一次讀取都顯示新 description

## 研究來源
- `docs/grimo/specs/2026-05-16-S187-skill-md-edit-page.md`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillUploadAllowedToolsTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillUploadExplicitNameTest.java`

## 先做 POC
- POC：not required — version publish path、Skill aggregate save、frontmatter parsing、duplicate version guard 都是現有 shipped 行為；S187 只把 validated frontmatter description 寫回 Skill row。

## 正式程式怎麼做
- Class / file 名稱：
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`
  - backend command/query tests
- 入口：`SkillCommandService.addVersion(...)`
- 必要行為：
  - `SkillValidator` 驗證通過後，從 validated metadata/frontmatter 取得 `description`。
  - 在同一個 transaction 載入 `Skill` aggregate，呼叫 `refreshDescriptionSnapshot(description, updatedBy)`。
  - `refreshDescriptionSnapshot` 驗證 description 不空白、不超過既有長度限制。
  - 新版本和 skill description snapshot 必須一起成功；若 add version 失敗，description 不改。
  - duplicate version 409 時，不修改 `skills.description`。

## 單元測試 / 整合測試
- backend command/query tests
  - `@DisplayName("AC-S187-5: 新版本 publish 更新 skills.description snapshot")`
  - `@DisplayName("AC-S187-7: duplicate version 不覆寫 description snapshot")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/*`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*SkillUpload*" --tests "*SkillCommand*"`

## 前置條件
- 無；可與 S187-T01~T03 平行，但 commit 時要避開衝突。

## 狀態
pending（待做）
