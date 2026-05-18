# S198-T02: Quality score 對 line count recommendation 扣分

## 對應規格
S198：SKILL.md Recommendations Not Hard Errors

## 這個 task 要做什麼
`QualityScoreService` 要改成從 validator warning 判斷 `lineCount` quality penalty。589 行的 SKILL.md 上傳不應被 validator 擋住，但 VALIDATION axis 要低於 100，讓品質面板看得到 progressive disclosure 建議未達成。

## 使用者情境（BDD）
Given（前提）quality listener 讀到 589 行、frontmatter 合法、body 非空的 `SKILL.md`
When（動作）`QualityScoreService.evaluateAndPersist(...)` 建立 VALIDATION axis
Then（結果）`dimensions.lineCount.score < 100`
And（而且）`totalScore < 100`
And（而且）`dimensions.lineCount.reasoning` 顯示 `589 / 500 recommended lines`

Given（前提）`SKILL.md` 在 500 行以內且沒有 frontmatter compatibility warning
When（動作）建立 VALIDATION axis
Then（結果）`dimensions.lineCount.score == 100`
And（而且）既有 all-pass totalScore 仍是 `100.00`

## 研究來源
- `docs/grimo/specs/2026-05-18-S198-skill-md-recommendations-not-hard-errors.md`
- `backend/src/main/java/io/github/samzhu/skillshub/score/QualityScoreService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/score/QualityScoreServiceTest.java`
- S194 shipped：`frontmatterOfficialFormat` warning penalty dimension pattern

## 先做 POC
- POC：not required — 改既有 scoring formula 與 unit test，不新增 scoring axis 或資料表。

## 正式程式怎麼做
- Class / file 名稱：`QualityScoreService.java`
- 入口：`buildValidationScore(...)`
- 必要行為：
  - `lineOk` 不再讀 `result.errors()`；改從 `result.warnings()` 找 `skill_md_line_count`。
  - `lineCount` dimension 的 reasoning 改成 `<actual> / 500 recommended lines`。
  - warning list 仍保存到 dimensions，方便 UI/diagnostic 看到原始 warning。
  - `VALIDATION_DIMENSION_COUNT` 若公式維持 7，不要偷偷加第 8 維；若要改維度數，必須同步所有 validation score tests。

## 單元測試 / 整合測試
- `QualityScoreServiceTest`
  - `@DisplayName("AC-S198-3: quality score lineCount dimension deducts for recommended max warning")`
  - 保留或更新既有 all-pass validation score test，確認官方格式 + 500 行內仍回 `100.00`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/score/QualityScoreService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/score/QualityScoreServiceTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.score.QualityScoreServiceTest`

## 前置條件
- S198-T01 PASS

## Status
PASS

## Result

Date: 2026-05-19

Test:
- RED: `cd backend && ./gradlew test --tests io.github.samzhu.skillshub.score.QualityScoreServiceTest` — failed before code change with 9 tests / 1 failed: AC-S198-3.
- GREEN: `cd backend && ./gradlew test --tests io.github.samzhu.skillshub.score.QualityScoreServiceTest` — PASS, `BUILD SUCCESSFUL in 1m 56s`; XML reports 9 tests / 0 failures / 0 errors.

Files changed:
- `backend/src/main/java/io/github/samzhu/skillshub/score/QualityScoreService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/score/QualityScoreServiceTest.java`

Notes:
- 589 行 `SKILL.md` 現在讓 VALIDATION `lineCount.score < 100`，`totalScore < 100`，reasoning 是 `589 / 500 recommended lines`。
- `warnings` dimension 保留 `skill_md_line_count: ... recommended max 500`，讓 UI / diagnostic 可以顯示 validator 的原始 warning。
- 官方格式且 500 行內的 all-pass case 仍回 `100.00`。
