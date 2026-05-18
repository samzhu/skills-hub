# S198: SKILL.md Recommendations Not Hard Errors

> 規格：S198 | 大小：XS(7) | 狀態：✅ shipped v4.81.0
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

這筆 response 代表 [SkillValidator.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/validation/SkillValidator.java:76) 把 `SKILL.md` 超過 500 行當成上傳阻擋條件。但 agentskills.io 官方規格把 500 行寫在 progressive disclosure 建議，不是 validation 的硬性條件。S198 要把「官方建議」跟「官方必要格式」分清楚：必要格式錯誤才擋上傳；建議未達成時允許發佈，但在 `ValidationResult.warnings()`、quality score、UI finding 顯示扣分或提醒。

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
| `SKILL.md` 超過 500 行 | [SkillValidator.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/validation/SkillValidator.java:76) | `ValidationResult.valid=false`，upload 回 400。 | 改成 warning；upload 可成功；quality `lineCount` 扣分。 |
| frontmatter 後 body 空白 | [SkillValidator.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/validation/SkillValidator.java:116) | `body_present` error，upload 回 400。 | 保持 hard error，但錯誤說明要標成 Skills Hub 上架政策：registry 不收沒有 instructions body 的空 skill；不可說成 agentskills.io 官方格式錯。 |
| body 缺 examples / steps / output format | [SkillValidator.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/validation/SkillValidator.java:234) | 已是 warning，不擋 publish。 | 保持。 |
| `allowed-tools` list、`metadata.tags` list | S194 shipped | 相容匯入 warning + quality penalty，不擋。 | 保持；這不是官方 recommendation，而是非官方 shape 的 compatibility import。 |
| nested metadata object | S194 shipped | hard error。 | 保持；官方 metadata 是 key-value mapping，nested object 不是單純建議問題。 |

### 2.3 Hard Error / Warning 分流規則

Hard error 留給兩類情況：

1. 檔案無法被 registry 正確解析，或違反 agentskills.io 明確 schema。
2. Skills Hub 自訂上架政策：SKILL.md frontmatter 後面必須有非空 instructions body；registry 不收只有 metadata、沒有操作說明的空 skill。

| 類型 | 例子 | HTTP |
|------|------|------|
| 缺 `SKILL.md` | zip 裡找不到 `SKILL.md` | 400 |
| frontmatter 無法解析 | 沒有 `---` delimiters、YAML syntax error | 400 |
| 必填欄位缺失 | 缺 `name` 或 `description` | 400 |
| 明確欄位限制違反 | `name` 大寫 / 超長 / hyphen 開頭；`description` 超 1024；`compatibility` 超 500 | 400 |
| 明確 unsafe syntax | `allowed-tools` token 含 shell control chars | 400 |
| Skills Hub 上架政策 | frontmatter 後沒有任何 body instructions | 400 |

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
| AC-S198-6 | 必做 | Test | empty body 仍擋 upload，但說明為 Skills Hub 上架政策 |

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

**AC-S198-6: empty body 仍擋 upload，但說明為 Skills Hub 上架政策**
- Given（前提）`SKILL.md` 只有合法 frontmatter，沒有 instructions body
- When（動作）呼叫 `SkillValidator.validate(content)`
- Then（結果）`result.valid()` 是 `false`
- And（而且）`result.errors()` 含 `body_present`
- And（而且）錯誤文案或 failed page 說明要讓 user 看懂：`SKILL.md frontmatter 後面沒有使用說明內容；Skills Hub 不收只有 metadata、沒有 instructions body 的空 skill。`
- And（而且）不得把這條描述成 agentskills.io 官方 hard validation

## 4. 檔案規劃

| 檔案 | 動作 | 說明 |
|------|------|------|
| `backend/src/main/java/io/github/samzhu/skillshub/skill/validation/SkillValidator.java` | modify | 500 行檢查從 `errors` 改 `warnings`；`body_present` 保持 hard error，但錯誤訊息要清楚標成 Skills Hub 上架政策。 |
| `backend/src/main/java/io/github/samzhu/skillshub/score/QualityScoreService.java` | modify | `lineOk` 改讀 `warnings`；`lineCount` dimension 用 recommended 文案；分數扣在 quality。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/validation/SkillValidatorTest.java` | modify | `lineCountExceedsMax` 改成 warning test；新增 schema hard error 保護測試；保留 empty body hard error 測試並檢查文案不誤稱官方格式錯。 |
| `backend/src/test/java/io/github/samzhu/skillshub/score/QualityScoreServiceTest.java` | modify | 新增 long SKILL.md quality penalty test。 |
| `docs/grimo/specs/archive/2026-05-05-S135a-backend-quality-score.md` | no change | 歷史 spec 不改；S198 作為後續修正記錄。 |
| `docs/grimo/CHANGELOG.md` | shipping | ship 時記錄「官方建議不擋 upload，只扣 quality」。 |

## 5. 開放問題

| 問題 | 建議答案 | 理由 |
|------|----------|------|
| empty body 要不要擋？ | 已決策：擋。錯誤說明要寫成 Skills Hub 上架政策，不是 agentskills.io 官方 hard validation。 | 只有 frontmatter、沒有 instructions body 的 skill 對 agent 不可用；registry 可以拒收，但要把原因講清楚。 |
| 5000 tokens 要不要實作？ | 暫不做。 | Java 端沒有 tokenizer；用 line count warning 已能處理本次 production bug。 |
| upload failed page 是否要顯示 warning？ | 不需要，因為 warning 不導到 failed page。 | warning 應出現在 publish review / skill quality，不是 failure page。 |
| 是否要引用 `skills-ref validate`？ | 後續可加 research task，不放 S198 必做。 | 先修目前錯誤分流；引入 reference library 會是新依賴與 build decision。 |

## 6. Task Plan

POC：not required — S198 不新增套件、SDK、framework SPI、schema migration 或外部服務；改動集中在既有 `SkillValidator` error/warning 分流與既有 `QualityScoreService` VALIDATION axis scoring。Phase 0 pre-flight 已對照 PRD P2、agentskills.io 官方規格、S135a/S194 shipped findings、目前 validator/score tests；設計方向仍成立。

E2E：not required for planning — S198 的行為可由 backend unit tests 覆蓋：validator output 與 persisted quality score dimensions。它不改 frontend route、browser workflow、test seed endpoint、schema migration 或 packaged artifact behavior。Phase 4 仍需重新評估是否要補真實 upload command/module integration；若 task 實作改到 command upload path，需新增對應整合測試。

| 順序 | Task | 主要檔案 | 覆蓋 AC | 驗證方式 | 前置 |
|---:|---|---|---|---|---|
| 1 | [S198-T01 Validator 分清官方建議與平台上架政策](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/tasks/2026-05-18-S198-T01-validator-recommendations-vs-platform-policy.md) | `SkillValidator.java`, `SkillValidatorTest.java` | AC-S198-1, AC-S198-2, AC-S198-4, AC-S198-5, AC-S198-6 | `cd backend && ./gradlew test --tests io.github.samzhu.skillshub.skill.validation.SkillValidatorTest` | 無 |
| 2 | [S198-T02 Quality score 對 line count recommendation 扣分](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/tasks/2026-05-18-S198-T02-quality-line-count-recommended-penalty.md) | `QualityScoreService.java`, `QualityScoreServiceTest.java` | AC-S198-3 | `cd backend && ./gradlew test --tests io.github.samzhu.skillshub.score.QualityScoreServiceTest` | T01 |

### AC Coverage

| AC | Task | 可驗證輸出 |
|---|---|---|
| AC-S198-1 | T01 | 589 行 SKILL.md 回 `valid=true`，errors 不含 `skill_md_line_count`。 |
| AC-S198-2 | T01 | warnings 含 `skill_md_line_count` 且文案包含 `recommended max 500`。 |
| AC-S198-3 | T02 | VALIDATION `dimensions.lineCount.score < 100`，`totalScore < 100`，reasoning 為 `589 / 500 recommended lines`。 |
| AC-S198-4 | T01 | 缺 `name` / invalid YAML / bad `name` 仍回 hard error。 |
| AC-S198-5 | T01 | body examples / steps / output format recommendations 仍是 warnings。 |
| AC-S198-6 | T01 | empty body 仍 invalid，錯誤說明為 Skills Hub 上架政策，不誤稱官方 hard validation。 |

## 7. Implementation Results

Date: 2026-05-19

Verdict: **PASS**。S198 的 task files 已全部 PASS；本輪 `$verifying-quality S198` 重新跑 targeted AC command 與完整 `verify-all.sh`，local release gate 全綠。下一步是 `$shipping-release S198`，由 release tick 歸檔 spec、刪除 task files、更新 changelog / roadmap / tag。

### 7.1 Task Results

| Task | Result | Evidence |
|---|---|---|
| S198-T01 Validator 分清官方建議與平台上架政策 | PASS | `SkillValidatorTest` 32 tests / 0 failures / 0 errors |
| S198-T02 Quality score 對 line count recommendation 扣分 | PASS | `QualityScoreServiceTest` 9 tests / 0 failures / 0 errors |

### 7.2 Verification Evidence

| Command | Result |
|---|---|
| `cd backend && ./gradlew test --tests io.github.samzhu.skillshub.skill.validation.SkillValidatorTest --tests io.github.samzhu.skillshub.score.QualityScoreServiceTest` | PASS — `BUILD SUCCESSFUL in 2m`; XML reports `SkillValidatorTest` 32 tests / 0 failures / 0 errors and `QualityScoreServiceTest` 9 tests / 0 failures / 0 errors |
| `./scripts/verify-all.sh` | PASS — V01=PASS, V02=INFO line coverage 87.2% (covered=4868 / total=5582), V03=PASS, V04=PASS, V05=PASS, V06=PASS, V07=PASS, V08a=PASS, V08b=PASS; `Verdict: ✅ all CRITICAL passed; exit=0` |
| `./scripts/verify-all.sh`（shipping-release tick rerun） | PASS — V01=PASS, V02=INFO line coverage 87.2% (covered=4870 / total=5582), V03=PASS, V04=PASS, V05=PASS, V06=PASS, V07=PASS, V08a=PASS, V08b=PASS; `Verdict: ✅ all CRITICAL passed; exit=0` |

### 7.3 AC Results

| AC | Status | Evidence |
|---|---|---|
| AC-S198-1 | PASS | `SkillValidatorTest.lineCountRecommendationDoesNotBlockValidation` asserts 589-line `SKILL.md` returns `valid=true` and errors do not start with `skill_md_line_count:`. |
| AC-S198-2 | PASS | `SkillValidatorTest.lineCountRecommendationProducesWarning` asserts warnings contain `skill_md_line_count:` and `recommended max 500`. |
| AC-S198-3 | PASS | `QualityScoreServiceTest.lineCountRecommendationReducesValidationScore` asserts `lineCount.score < 100`, `totalScore < 100`, reasoning `589 / 500 recommended lines`, and warnings keep `recommended max 500`. |
| AC-S198-4 | PASS | `SkillValidatorTest.schemaErrorsRemainHardErrors` covers missing `name`, invalid YAML, and bad `name` as hard errors. |
| AC-S198-5 | PASS | `SkillValidatorTest.bodyQualityRecommendationsRemainWarnings` asserts missing body examples stays warning-only and `valid=true`. |
| AC-S198-6 | PASS | `SkillValidatorTest.emptyBodyRemainsPlatformPolicyHardError` asserts empty body remains invalid with Skills Hub policy wording. |

### 7.4 Quality Review

| Layer | Result | Detail |
|---|---|---|
| Automated tests | PASS | Targeted backend command and V01/V03 backend gates passed. |
| Coverage / integration | PASS | `verify-all.sh` ran frontend tests, lint/typecheck, frontend coverage, Playwright happy-path, AOT processing, and native image build; no CRITICAL failures. |
| Manual verification | N/A | S198 changes validator output and persisted quality score dimensions only; no new UI screen, manual workflow, schema migration, or production deploy requirement. |
| Testability gate | CLEAR | Every AC-S198-1 through AC-S198-6 has a matching `@DisplayName` and `@Tag` in backend tests. |

Code quality review:
- `SkillValidator.java` keeps the existing validation boundary; 500-line progressive disclosure is warning-only, while schema and platform-policy errors stay in `errors`.
- `QualityScoreService.java` keeps `VALIDATION_DIMENSION_COUNT = 7`; the line-count dimension now reads `skill_md_line_count:` warnings and reports the same split-line count as the validator.
- Changed production/test files contain no `TODO`, `FIXME`, `System.out`, `printStackTrace`, or local `new ObjectMapper()` / `new JsonMapper()` contract-test shortcut.

Design sync:
- §2 hard error / warning split still matches implementation.
- §4 file plan matches the actual changed files.
- No new dependency, schema, endpoint, browser flow, or glossary term was introduced.

### Final Size Re-score (per estimation-scale.md)

| Dimension | Initial | Actual | Rationale |
|---|---:|---:|---|
| Tech risk | 1 | 1 | 只改既有 `SkillValidator` 與 `QualityScoreService` 分流；S194/S135a 已驗證相同 warning/quality pattern。 |
| Uncertainty | 1 | 1 | §3 AC 在實作前已具體列出 589 行、schema hard error、empty body policy 與 quality 扣分行為；沒有 scope pivot。 |
| Dependencies | 1 | 1 | 只依賴已 ship 的 S135a/S194，沒有新外部系統、套件或 migration。 |
| Scope | 1 | 2 | 實際改動是 2 個 production files + 2 個 test files，落在單一 backend 行為面但超過 1-3 production/test 小改的最小值。 |
| Testing | 1 | 1 | AC 全由 backend unit tests 驗證；release tick 另跑標準 `verify-all.sh`，但 S198 本身沒有新增 Spring context、Docker 或 browser-only 行為。 |
| Reversibility | 1 | 1 | 可由單一 commit 還原；沒有 schema、API contract 或 persisted data format 變更。 |
| **Total** | **5 / XS** | **7 / XS** | 仍是 XS；初始 `XS(5)` 是舊 roadmap 點數，ship 後依六維制校正為 `XS(7)`。 |
