# S205-T01: Download Content-Disposition header contract

## 對應規格
S205：Download Filename UTF-8 Content-Disposition

## 這個 task 要做什麼
兩條 download API 要回 `Content-Disposition`，中文顯示名稱要用 RFC 5987 `filename*`，不要把裸中文塞進 header。`OAuth 專家` 最新版下載時，response header 要包含 `filename*=UTF-8''OAuth%20%E5%B0%88%E5%AE%B6-1.zip`；指定版本也要沿用同一套規則。

目前 HEAD 已經有對應 implementation 與 AC-S205-1~4 測試。這個 task 開始時先跑驗證命令；如果已通過，只更新本 task 狀態和 spec §7 evidence，不重寫既有程式。若不加 `clean` 時先卡在 `build/generated/aotTestSources` 找不到 generated initializer，代表 build 目錄殘留 AOT 產物；用本 task 的 `clean test --tests ...` 重新生成。

## 使用者情境（BDD）
Given（前提）skill id 是合法 UUID，`skills.name="OAuth 專家"`，`latestVersion="1"`，且使用者有 read permission
When（動作）呼叫 `GET /api/v1/skills/{id}/download`
Then（結果）HTTP 200，response body 是 zip bytes
And（而且）`Content-Disposition` 含 `filename*=UTF-8''OAuth%20%E5%B0%88%E5%AE%B6-1.zip`
And（而且）`Content-Disposition` 不含裸字串 `OAuth 專家`
And（而且）`Team/OAuth\Expert` 這種顯示名稱會變成 `Team-OAuth-Expert-1.zip`，不保留 `/` 或 `\`

## 研究來源
- `docs/grimo/specs/2026-05-20-S205-download-filename-utf8-content-disposition.md` §2.1 / §4
- Spring Framework `ContentDisposition.Builder.filename(String, Charset)` 官方 Javadoc
- RFC 6266 Content-Disposition
- RFC 5987 extended parameter encoding
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryController.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillQueryControllerApiContractTest.java`

## 先做 POC
- POC：not required — 使用 Spring 官方 `ContentDisposition` API；slice tests 已能鎖住 header string。

## 正式程式怎麼做
- Class / file 名稱：
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryController.java`
  - `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillQueryControllerApiContractTest.java`
- 入口：
  - `GET /api/v1/skills/{id}/download`
  - `GET /api/v1/skills/{id}/versions/{version}/download`
- 必要行為：
  - `contentDisposition(skill, version)` 組 `safeFilenamePart(skill.getName()) + "-" + safeFilenamePart(version) + ".zip"`。
  - `safeFilenamePart(...)` 要 `trim()`、把 `/` 和 `\` 改為 `-`、空白 fallback 成 `skill`。
  - 用 `ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString()` 產 header。
  - 不自己手寫 percent-encoding，避免 `%` 被重複 encode。
- Response header：
  - `Content-Disposition`: RFC 6266 header value，非 ASCII 走 `filename*=UTF-8''...`。

## 單元測試 / 整合測試
- `SkillQueryControllerApiContractTest`
  - `@DisplayName("AC-S205-1: 最新版中文顯示名稱 download header 用 UTF-8 filename*")`
  - `@DisplayName("AC-S205-2: 指定版本中文顯示名稱 download header 用 UTF-8 filename*")`
  - `@DisplayName("AC-S205-3: ASCII 顯示名稱 download header 仍保留可讀檔名")`
  - `@DisplayName("AC-S205-4: 顯示名稱路徑分隔符會改成連字號")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryController.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillQueryControllerApiContractTest.java`
- `docs/grimo/specs/2026-05-20-S205-download-filename-utf8-content-disposition.md`

## 驗證方式
執行：`cd backend && ./gradlew clean test --tests io.github.samzhu.skillshub.skill.query.SkillQueryControllerApiContractTest`

## 前置條件
- 無

## 狀態
PASS（2026-05-20：`cd backend && ./gradlew clean test --tests io.github.samzhu.skillshub.skill.query.SkillQueryControllerApiContractTest` BUILD SUCCESSFUL）
