# S198-T01: Validator 分清官方建議與平台上架政策

## 對應規格
S198：SKILL.md Recommendations Not Hard Errors

## 這個 task 要做什麼
`SkillValidator.validate(...)` 要把 `SKILL.md` 超過 500 行從 hard error 改成 warning，讓 589 行的合法 skill 可以 publish。`body_present` 仍然擋 upload，但錯誤訊息要說清楚：這是 Skills Hub 上架政策，因為 registry 不收只有 metadata、沒有 instructions body 的空 skill。

## 使用者情境（BDD）
Given（前提）`SKILL.md` 有合法 frontmatter 與 589 行 body
When（動作）呼叫 `SkillValidator.validate(content)`
Then（結果）`result.valid()` 是 `true`
And（而且）`result.errors()` 不含 `skill_md_line_count`
And（而且）`result.warnings()` 含 `skill_md_line_count`，文字包含 `recommended max 500`

Given（前提）`SKILL.md` 缺 `name`
When（動作）呼叫 `SkillValidator.validate(content)`
Then（結果）`result.valid()` 是 `false`
And（而且）`result.errors()` 含 `Missing required field: name`

Given（前提）`SKILL.md` 只有合法 frontmatter，第二個 `---` 後沒有 instructions body
When（動作）呼叫 `SkillValidator.validate(content)`
Then（結果）`result.valid()` 是 `false`
And（而且）`result.errors()` 含 `body_present`
And（而且）錯誤訊息包含 Skills Hub 上架政策語意，不把它說成 agentskills.io 官方格式錯

Given（前提）body 沒有 example heading 或 code fence
When（動作）呼叫 `SkillValidator.validate(content)`
Then（結果）`result.valid()` 是 `true`
And（而且）`result.warnings()` 含 `body_examples`

## 研究來源
- `docs/grimo/specs/2026-05-18-S198-skill-md-recommendations-not-hard-errors.md`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/validation/SkillValidator.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/validation/SkillValidatorTest.java`
- agentskills.io Specification：500 lines 是 progressive disclosure recommendation，非 hard validation
- S194 shipped：frontmatter compatibility warnings pattern

## 先做 POC
- POC：not required — 只調整既有 validator 分流，SnakeYAML/frontmatter parser 與 warning pattern 已由 S194/S135a 驗證。

## 正式程式怎麼做
- Class / file 名稱：`SkillValidator.java`
- 入口：`SkillValidator.validate(String skillMdContent)`
- 必要行為：
  - `lineCount > 500` 不再直接 `return ValidationResult.of(false, ...)`；改加入 `warnings`。
  - warning 文案使用 `recommended max 500`，不要只寫 `max 500`。
  - `body_present` 繼續加到 `errors`，但文案要讓 user 看懂是 Skills Hub 上架政策：`SKILL.md frontmatter 後面沒有使用說明內容；Skills Hub 不收只有 metadata、沒有 instructions body 的空 skill。`
  - schema hard errors 保持：缺 frontmatter、YAML parse fail、缺 `name` / `description`、bad `name`、nested metadata object、unsafe `allowed-tools` token。
  - body examples / steps / output format recommendations 仍是 warnings。

## 單元測試 / 整合測試
- `SkillValidatorTest`
  - `@DisplayName("AC-S198-1: 589 行 SKILL.md 不擋 upload validator")`
  - `@DisplayName("AC-S198-2: 589 行 SKILL.md 產生 line-count recommended warning")`
  - `@DisplayName("AC-S198-4: 明確 schema 錯誤仍回 hard error")`
  - `@DisplayName("AC-S198-5: body quality recommendations 仍是 warnings")`
  - `@DisplayName("AC-S198-6: empty body 仍擋 upload 但說明為 Skills Hub 上架政策")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/skill/validation/SkillValidator.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/validation/SkillValidatorTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.skill.validation.SkillValidatorTest`

## 前置條件
- 無

## 狀態
pending（待做）
