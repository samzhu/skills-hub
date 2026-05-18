# S198: SKILL.md Recommendations Not Hard Errors

> 規格：S198 | 大小：XS(5) | 狀態：📐 in-design
> 日期：2026-05-18
> 對應：PRD P2「驗證 SKILL.md 格式符合 agentskills.io 規範」、S135a quality score、S194 compatibility warning

---

## 1. 目標

`POST /api/v1/skills/upload` 現在遇到 589 行的 `SKILL.md` 會回：

```json
{
  "error": "VALIDATION_ERROR",
  "message": "SKILL.md validation failed",
  "findings": [
    {
      "section": "skill_md",
      "severity": "error",
      "title": "skill_md_line_count: SKILL.md has 589 lines (max 500)",
      "hint": null
    }
  ]
}
```

這筆 response 代表 [SkillValidator.java](/Users/samzhu/.codex/worktrees/81d8/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/validation/SkillValidator.java:76) 把 `SKILL.md` 超過 500 行當成上傳阻擋條件。但 agentskills.io 官方規格把 500 行寫在 progressive disclosure 建議，不是 validation 的硬性條件。S198 要把「官方建議」跟「官方必要格式」分清楚：必要格式錯誤才擋上傳；建議未達成時允許發佈，但在 `ValidationResult.warnings()`、quality score、UI finding 顯示扣分或提醒。

不做 production code 修改，直到本 spec 被確認並進入 task 實作。

## 2. 研究與設計

### 2.1 官方文件對照

| 官方來源 | 實際文字 / 規則 | 判斷 |
|----------|------------------|------|
| [agentskills.io Specification — SKILL.md format](https://agentskills.io/specification#skill-md-format) | `SKILL.md` must contain YAML frontmatter followed by Markdown content。 | 缺 frontmatter / YAML 無法 parse / 必填欄位缺失可以擋。 |
| [agentskills.io Specification — Frontmatter](https://agentskills.io/specification#frontmatter) | `name`、`description` 是 required；`name`/`description`/`compatibility` 有明確長度與格式限制；`metadata` 是 key-value mapping；`allowed-tools` 是 space-separated string。 | 這些是 schema / naming convention，可以做 hard error；其中相容匯入可依 S194 放行但扣分。 |
| [agentskills.io Specification — Body content](https://agentskills.io/specification#body-content) | Body content 沒有格式限制；建議 sections 是 step-by-step、examples、edge cases；長內容考慮拆 reference。 | examples / steps / output format / 過長內容都不該擋 upload。 |
| [agentskills.io Specification — Progressive disclosure](https://agentskills.io/specification#progressive-disclosure) | Instructions `< 5000 tokens recommended`；main `SKILL.md` under 500 lines。 | 500 行與 5000 tokens 是 recommended；應轉 warning + quality penalty。 |
| [agentskills.io Specification — Validation](https://agentskills.io/specification#validation) | 官方 `skills-ref validate` 檢查 frontmatter valid 與 naming conventions。 | 官方 validation 範圍偏 schema / naming，不是內容品質建議。 |
| [Best practices — Structure large skills with progressive disclosure](https://agentskills.io/skill-creation/best-practices#structure-large-skills-with-progressive-disclosure) | 500 行 / 5000 tokens 是 specification recommends；需要更多內容時移到 `references/`。 | 這是品質建議，適合扣分，不適合 400。 |

### 2.2 目前程式盤點

| 現況 | 位置 | 現在行為 | S198 判斷 |
|------|------|----------|-----------|
| `SKILL.md` 超過 500 行 | [SkillValidator.java](/Users/samzhu/.codex/worktrees/81d8/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/validation/SkillValidator.java:76) | `ValidationResult.valid=false`，upload 回 400。 | 改成 warning；upload 可成功；quality `lineCount` 扣分。 |
| frontmatter 後 body 空白 | [SkillValidator.java](/Users/samzhu/.codex/worktrees/81d8/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/validation/SkillValidator.java:116) | `body_present` error，upload 回 400。 | 需要確認：官方說 SKILL.md 是 metadata + instructions，但 body 又寫「無格式限制」。建議改成 warning 或至少拆出產品自訂 policy，不再聲稱是 agentskills.io hard validation。 |
| body 缺 examples / steps / output format | [SkillValidator.java](/Users/samzhu/.codex/worktrees/81d8/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/validation/SkillValidator.java:234) | 已是 warning，不擋 publish。 | 保持。 |
| `allowed-tools` list、`metadata.tags` list | S194 shipped | 相容匯入 warning + quality penalty，不擋。 | 保持；這不是官方 recommendation，而是非官方 shape 的 compatibility import。 |
| nested metadata object | S194 shipped | hard error。 | 保持；官方 metadata 是 key-value mapping，nested object 不是單純建議問題。 |

### 2.3 Hard Error / Warning 分流規則

Hard error 只留給「檔案無法被 registry 正確解析或違反明確 schema」：

| 類型 | 例子 | HTTP |
|------|------|------|
| 缺 `SKILL.md` | zip 裡找不到 `SKILL.md` | 400 |
| frontmatter 無法解析 | 沒有 `---` delimiters、YAML syntax error | 400 |
| 必填欄位缺失 | 缺 `name` 或 `description` | 400 |
| 明確欄位限制違反 | `name` 大寫 / 超長 / hyphen 開頭；`description` 超 1024；`compatibility` 超 500 | 400 |
| 明確 unsafe syntax | `allowed-tools` token 含 shell control chars | 400 |

Warning / quality penalty 留給「官方建議、best practices、內容品質」：

| 類型 | 例子 | HTTP |
|------|------|------|
| Progressive disclosure 建議 | `SKILL.md` 超過 500 行、body 過長 | 200，品質扣分 |
| Body 品質建議 | 沒有 examples、沒有 steps、沒有 output format | 200，品質扣分或顯示 warning |
| 可讀性 / 結構建議 | 太多通用說明、reference 拆分建議 | 200，品質扣分或顯示 warning |

### 2.4 做法比較

| 做法 | 採用 | 理由 |
|------|------|------|
| A: 只把 500 行 hard error 改 warning | no | 修掉眼前 bug，但留下 `body_present` 這類「產品自訂 vs 官方必要」混淆。 |
| B: 盤點 validator 所有 hard error，建立 official-required / platform-policy / quality-warning 分類 | yes | 從根因修，避免下一個官方建議又被擋 upload。 |
| C: 完全照 `skills-ref validate`，移除所有本地額外規則 | no | 會丟掉 `allowed-tools` injection token、nested metadata 等平台安全/相容性防線；MVP 仍需要基本風險防護。 |

## 3. 驗收條件（SBE）

驗證命令：

```bash
cd backend && ./gradlew test --tests io.github.samzhu.skillshub.skill.validation.SkillValidatorTest --tests io.github.samzhu.skillshub.score.QualityScoreServiceTest
```

| AC | 優先級 | 驗證方式 | 標題 |
|----|--------|----------|------|
| AC-S198-1 | 必做 | Test | 589 行 `SKILL.md` 不擋 upload validator |
| AC-S198-2 | 必做 | Test | 589 行 `SKILL.md` 產生 line-count warning |
| AC-S198-3 | 必做 | Test | quality score lineCount dimension 扣分 |
| AC-S198-4 | 必做 | Test | 缺 `name` / invalid YAML / bad `name` 仍回 hard error |
| AC-S198-5 | 必做 | Test | body quality recommendations 仍是 warnings |
| AC-S198-6 | 需確認 | Decision | empty body 是 warning 還是平台自訂 hard policy |

**AC-S198-1: 589 行 SKILL.md 不擋 upload validator**
- Given（前提）`SKILL.md` 有合法 frontmatter 與 589 行 body
- When（動作）呼叫 `SkillValidator.validate(content)`
- Then（結果）`result.valid()` 是 `true`
- And（而且）`result.errors()` 不含 `skill_md_line_count`

**AC-S198-2: 589 行 SKILL.md 產生 line-count warning**
- Given（前提）同一份 589 行 `SKILL.md`
- When（動作）呼叫 `SkillValidator.validate(content)`
- Then（結果）`result.warnings()` 含 `skill_md_line_count`
- And（而且）warning 文字說明 `recommended max 500`，不可再寫 `max 500` 造成 hard limit 語感

**AC-S198-3: quality score lineCount dimension 扣分**
- Given（前提）quality listener 讀到 589 行 `SKILL.md`
- When（動作）`QualityScoreService` 建立 VALIDATION axis
- Then（結果）`dimensions.lineCount.score < 100`
- And（而且）`totalScore < 100`
- And（而且）reasoning 顯示 `589 / 500 recommended lines`

**AC-S198-4: 明確 schema 錯誤仍回 hard error**
- Given（前提）`SKILL.md` 缺 `name`
- When（動作）呼叫 `SkillValidator.validate(content)`
- Then（結果）`result.valid()` 是 `false`
- And（而且）`result.errors()` 含 `Missing required field: name`

**AC-S198-5: body quality recommendations 仍是 warnings**
- Given（前提）body 沒有 example heading 或 code fence
- When（動作）呼叫 `SkillValidator.validate(content)`
- Then（結果）`result.valid()` 是 `true`
- And（而且）`result.warnings()` 含 `body_examples`

**AC-S198-6: empty body policy 需確認**
- Given（前提）`SKILL.md` 只有合法 frontmatter，沒有 instructions body
- When（動作）呼叫 `SkillValidator.validate(content)`
- Then（結果）目前是 `body_present` hard error
- Decision（待決策）若要嚴格對齊官方 validation，改 warning；若產品要維持「registry 不收空 skill」，spec 需標明這是 Skills Hub 自訂上架政策，不是 agentskills.io 官方必要格式

## 4. 檔案規劃

| 檔案 | 動作 | 說明 |
|------|------|------|
| `backend/src/main/java/io/github/samzhu/skillshub/skill/validation/SkillValidator.java` | modify | 500 行檢查從 `errors` 改 `warnings`；`body_present` 依 AC-S198-6 決策處理。 |
| `backend/src/main/java/io/github/samzhu/skillshub/score/QualityScoreService.java` | modify | `lineOk` 改讀 `warnings`；`lineCount` dimension 用 recommended 文案；分數扣在 quality。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/validation/SkillValidatorTest.java` | modify | `lineCountExceedsMax` 改成 warning test；新增 schema hard error 保護測試。 |
| `backend/src/test/java/io/github/samzhu/skillshub/score/QualityScoreServiceTest.java` | modify | 新增 long SKILL.md quality penalty test。 |
| `docs/grimo/specs/archive/2026-05-05-S135a-backend-quality-score.md` | no change | 歷史 spec 不改；S198 作為後續修正記錄。 |
| `docs/grimo/CHANGELOG.md` | shipping | ship 時記錄「官方建議不擋 upload，只扣 quality」。 |

## 5. 開放問題

| 問題 | 建議答案 | 理由 |
|------|----------|------|
| empty body 要不要擋？ | 建議先改 warning，但在 publish review / quality 顯示很低分。 | 官方 validation 著重 frontmatter/naming；body content 無格式限制。空 body 不是好 skill，但不應冒充官方格式錯。 |
| 5000 tokens 要不要實作？ | 暫不做。 | Java 端沒有 tokenizer；用 line count warning 已能處理本次 production bug。 |
| upload failed page 是否要顯示 warning？ | 不需要，因為 warning 不導到 failed page。 | warning 應出現在 publish review / skill quality，不是 failure page。 |
| 是否要引用 `skills-ref validate`？ | 後續可加 research task，不放 S198 必做。 | 先修目前錯誤分流；引入 reference library 會是新依賴與 build decision。 |

