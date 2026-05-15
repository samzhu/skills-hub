# S176-T02: Backend upload uses explicit skillName

## 對應規格
S176：Explicit Publish Skill Name（發佈頁手填平台 skill name + 允許重名）

## 這個 task 要做什麼
`SkillCommandService.uploadSkill()` 目前把 `SKILL.md` frontmatter `name` 寫進 `skills.name`。本 task 讓 production upload API 必收 multipart `skillName`，並把 `skillName` 寫進 `skills.name`；`SKILL.md name` 只留在 `skill_versions.frontmatter`。同時更新 test seed path 和 controller forgery tests，確保 author 還是 server 取，不能被 request 偽造。

## 使用者情境（BDD）
Given（前提）multipart request 有 `skillName="platform-skill"`，zip 內 `SKILL.md` frontmatter `name="internal-package-name"`  
When（動作）POST `/api/v1/skills/upload`  
Then（結果）HTTP 201，`skills.name = "platform-skill"`  
And（而且）`skill_versions.frontmatter->>'name' = "internal-package-name"`  
And（而且）若 DB 已有 `skills.name="transcribe-video"`，再次上傳 `skillName="transcribe-video"` 仍回 201 且產生不同 id  
And（而且）缺少 `skillName` 或送 `skillName="Bad Name!"` 時回 400，DB 不新增 row

## 研究來源
- `docs/grimo/specs/2026-05-15-S176-explicit-publish-skill-name.md §4.1`
- Spring Framework Multipart docs：普通 form field 和 `MultipartFile` 都可由 `@RequestParam` 取得
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`：目前 `validation.metadata().get("name")` 建 `CreateSkillCommand`
- `docs/grimo/specs/archive/2026-05-09-S154b-author-display-frontend.md`：frontend 不送 author；backend 從 `CurrentUserProvider` 取 author

## Requires
- Java 25 toolchain
- Docker daemon reachable（backend Testcontainers / pgvector）
- T01 PASS（DB 已允許 duplicate `skills.name`）

## 先做 POC
- POC：not required — 只改現有 controller/service signature 與既有 aggregate validation path。

## 正式程式怎麼做
- Class / file 名稱：
  - `SkillCommandController.java`
  - `SkillCommandService.java`
  - `TestDataController.java`
- 入口：`POST /api/v1/skills/upload`
- 必要行為：
  - Controller signature 加 `@RequestParam("skillName") String skillName`。
  - Service canonical signature 改為 `uploadSkill(byte[] uploadedBytes, String skillName, String version, String author, String category, Visibility visibility, @Nullable String authorNameSnapshot)`。
  - Service validation 後仍讀 `description` 和完整 `metadata`；但 `CreateSkillCommand.name()` 使用 request `skillName`。
  - 既有 test/support call sites 全部改成 explicit skillName；不要保留 production fallback 到 `SKILL.md name` 的 overload。
  - `TestDataController.seedSkill` 傳 `req.name()` 當平台 skillName；合成 SKILL.md 可維持同名。
- Response / DB 欄位：
  - response body: `{"id":"<uuid>"}`
  - `skills.name`: request `skillName`
  - `skill_versions.frontmatter`: 原 `SKILL.md` metadata

## 單元測試 / 整合測試
- `SkillUploadExplicitNameTest`
  - `@DisplayName("AC-S176-2: upload writes request skillName to skills.name and keeps SKILL.md name in frontmatter")`
  - `@DisplayName("AC-S176-3: upload allows duplicate platform skill names")`
  - `@DisplayName("AC-S176-4: upload rejects missing or invalid skillName without inserting rows")`
- `SkillPublishForgeryTest`
  - 更新 verify：service 收到 server user id + `skillName`，request author 仍不會進 DB
- `TestDataControllerTest`
  - 更新 verify：seed endpoint 呼叫新 `uploadSkill` signature，第一個 name arg 是 `req.name()`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/TestDataController.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillUploadExplicitNameTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillPublishForgeryTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/testsupport/TestDataControllerTest.java`
- 其他編譯失敗指出的 `uploadSkill(...)` test call sites

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*SkillUploadExplicitNameTest" --tests "*SkillPublishForgeryTest" --tests "*TestDataControllerTest"`

## 前置條件
- S176-T01 PASS

## 狀態
PASS（2026-05-15）

## 執行結果
- 新增 `SkillUploadExplicitNameTest`，驗 `SkillCommandService.uploadSkill(...)` 用 request `skillName` 寫 `skills.name`，`SKILL.md name` 保留在 `skill_versions.frontmatter`。
- `POST /api/v1/skills/upload` 現在必收 multipart `skillName`；Spring Framework multipart docs 說普通 form field 與 `MultipartFile` 都可用 `@RequestParam` 取得，故採用原生 `@RequestParam("skillName")`。
- 移除 upload service 端 fallback 到 `SKILL.md name` 的 overload；所有 backend/test upload call site 明確傳平台 skill name。
- 更新 `TestDataController` seed path，`req.name()` 會傳成平台 skill name；author 仍由 server/test request path 控制，沒有引入 caller 偽造 author 的路徑。
- 更新 `SkillPublishForgeryTest` 和 `TestDataControllerTest`，確保新 signature 下 author 仍是 server-derived，且缺少/非法 `skillName` 會走 HTTP 400。

## 驗證結果
PASS：`cd backend && ./gradlew test --tests "*SkillUploadExplicitNameTest" --tests "*SkillPublishForgeryTest" --tests "*TestDataControllerTest"`

實際結果：`BUILD SUCCESSFUL in 2m 32s`
