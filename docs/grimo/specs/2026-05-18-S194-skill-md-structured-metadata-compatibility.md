# S194: SKILL.md Structured Metadata Compatibility

> 規格：S194 | 大小：S(10) | 狀態：📐 in-design
> 日期：2026-05-18
> 對應：PRD P2「上傳合法 skill」、S135a `metadata_value_string` hard rule、production failure `PUT /api/v1/skills/{id}/versions` 400

---

## 1. 目標

讓 `handover.zip` 這類 `SKILL.md` 內含 `allowed-tools` YAML list 或 `metadata.tags: [...]` 的 skill 可以建立新版本，但系統仍清楚標示這是相容匯入，不是 agentskills.io 官方 frontmatter 格式，並在品質評分的 VALIDATION 分數扣分。

目前上傳 `/Users/samzhu/workspace/github-samzhu/skills-hub/.agents/skills/handover` 打包的 zip 時，Cloud Run 在 `2026-05-18T05:20Z / 05:22Z / 05:27Z` 連續回 400。後端已進入 [SkillCommandService.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java:193) 抽出 `SKILL.md`，失敗點是 [SkillValidator.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/validation/SkillValidator.java:200) 要求 `metadata` 內每個 value 都是 `String`。

使用者看到的是「儲存新版本失敗」，DB 不新增 `skill_versions` row。這個 spec 要把「不合官方格式但常見的 frontmatter 形狀」從阻斷錯誤改成 compatibility warning，讓版本可以進入後續品質評分；品質評分要扣分並列出原因，避免把非官方標準格式顯示成滿分。

## 2. 研究與設計

### 2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
|------|----------|--------------|
| [agentskills.io specification](https://agentskills.io/specification) | `metadata` 被定義為 optional map，key/value 皆為 string；`allowed-tools` 被定義為 space-separated string。 | `metadata_value_string` 不能直接刪掉；`allowed-tools` YAML list 也不能宣稱官方合規。若放寬，必須寫成 Skills Hub 的 compatibility extension。 |
| [PRD.md](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/PRD.md:91) | P2 要求上傳資料夾後驗證 `SKILL.md` 符合 agentskills.io 規範；驗證失敗要回具體欄位與原因。 | 放寬不能吞錯；要把非官方 frontmatter 格式記錄成 warning，讓 user 知道哪個欄位不是官方格式。 |
| [S135 meta spec](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/specs/archive/2026-05-05-S135-meta-skill-quality-score-system.md:172) | S135a 把 `metadata_value_string` 設成 hard error。 | S194 是有意識地調整 S135a：從 hard gate 改為 compatibility warning。 |
| [SkillValidator.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/validation/SkillValidator.java:175) | `allowed-tools` 已支援 YAML list 與 legacy string，但註解說「與 canonical agentskills.io spec 對齊」不準。 | list 可以繼續相容，但要產生 official-format warning 並扣 VALIDATION 分。 |
| [MapJsonbConverterTest.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/test/java/io/github/samzhu/skillshub/shared/persistence/MapJsonbConverterTest.java:69) | JSONB converter 測過 `tags` list 與 nested metadata round-trip。 | 儲存層可保存 list，不需 migration；主要改 validator 與 quality scoring。 |
| [QualityScoreService.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/score/QualityScoreService.java:120) | 目前只把 `ValidationResult.warnings()` 放進 dimensions，`totalScore` 仍只看 6 個 hard rule pass count。 | S194 要把 metadata compatibility warning 變成實際分數 penalty，而不是只保存文字。 |
| [handover/SKILL.md](/Users/samzhu/workspace/github-samzhu/skills-hub/.agents/skills/handover/SKILL.md:11) | `allowed-tools` 是 YAML list，`metadata.tags` 是 YAML flow sequence。 | S194 的真實 fixture 就用 handover skill，需同時覆蓋兩個 compatibility warning。 |

### 2.2 架構設計

資料流維持不變：

```text
PUT /api/v1/skills/{id}/versions
  -> PackageService.normalizeToZip(...)
  -> PackageService.extractSkillMd(...)
  -> SkillValidator.validate(...)
  -> SkillCommandService.addVersion(...)
  -> skill_versions.frontmatter JSONB 保存原始 parsed metadata
  -> QualityScoreService 把 frontmatter compatibility warning 納入 VALIDATION 扣分與分數理由
```

設計變更：

| 欄位 | 現在行為 | S194 行為 | 品質分數 |
|------|----------|-----------|----------|
| `allowed-tools: "Read Glob Grep"` | valid | valid | 不扣分 |
| `allowed-tools: [Read, Glob, Grep]` | valid | valid + warning `allowed-tools uses YAML list; agentskills.io expects a space-separated string` | VALIDATION 扣分 |
| `metadata.author: "howielab"` | valid | valid | 不扣分 |
| `metadata.version: "2.0.0"` | valid | valid | 不扣分 |
| `metadata.tags: [a, b]` | error，阻擋 publish | valid + warning `metadata: key 'tags' uses non-string value; agentskills.io expects string values` | VALIDATION 扣分 |
| `metadata.foo: 123` | error | valid + warning，因 YAML number 會保存成 JSON number | VALIDATION 扣分 |
| `metadata.deep: {x: y}` | error | error，nested object 暫不支援，避免任意深度資料造成 UI/score/scan 不可預測 | 不產生 score，因版本不建立 |

為什麼 list/number/boolean 可放寬、nested object 仍擋：

| 型別 | 允許 | 理由 |
|------|------|------|
| string | yes | 官方格式。 |
| number / boolean | yes + warning | 常見 YAML 解析結果；JSONB 可保存；轉成 string 會破壞原始 frontmatter。 |
| list of scalar | yes + warning | `tags` 是實際生產失敗案例；JSONB 可保存；安全掃描只讀文本，不執行。 |
| nested map / list of map | no | 沒有現有 UI/API 消費者；深層任意資料會增加 payload 與顯示責任。 |

### 2.3 做法比較

| 做法 | 採用 | 理由 |
|------|------|------|
| A: 保持 hard error，只改錯誤顯示 | no | user 仍無法上傳 `handover.zip`；只改善症狀。 |
| B: 所有 metadata value 轉成 string | no | `tags` 會變成 Java `ArrayList.toString()` 形狀，和原始 YAML 不一致；之後 API 消費者也難判斷值型別。 |
| C: 接受 scalar/list metadata，nested map 仍擋，並產 warning | yes | 讓真實 skill 可發佈，同時保留官方格式提醒與 bounded input。 |
| D: 完全接受任意 JSON metadata | no | 沒有產品需求；會把 UI 顯示、score、OpenAPI schema 都變成無界範圍。 |

### 2.4 Task 邊界提示

| Task 候選 | Class / file | 來源 | 正向情境 | 反向情境 | POC |
|-----------|--------------|------|----------|----------|-----|
| T01 | `SkillValidator` / `SkillValidatorTest` | handover fixture + S135a | `allowed-tools` list 與 `metadata.tags` list 通過且 warnings 含欄位名 | nested object 仍 invalid | not required |
| T02 | `QualityScoreServiceTest` | S135a validation score | frontmatter compatibility warning 讓 VALIDATION totalScore 低於 100，dimensions 列出 warning 與 penalty | 官方格式 frontmatter 不扣分且 totalScore 可維持 100 | not required |
| T03 | `SkillCommandServiceUploadAllowedToolsTest` 或新 test | S187 addVersion path | handover zip 新增版本成功，`skill_versions.frontmatter.allowed-tools` 與 `metadata.tags` 保留 list | missing `SKILL.md` 仍 400 | not required |
| T04 | docs page / spec sync | `/docs/frontmatter` | docs 說明官方格式與 Skills Hub compatibility warning | 不宣稱 list 型欄位為官方合規 | not required |

## 3. 驗收條件（SBE）

驗證命令：

執行：`./scripts/verify-all.sh`
通過條件：所有帶 `S194` AC id 的測試都是綠燈。

| AC | 優先級 | 驗證方式 | 標題 |
|----|--------|----------|------|
| AC-S194-1 | 必做 | Test | handover metadata tags list 可新增版本 |
| AC-S194-2 | 必做 | Test | 非官方 frontmatter 變 warning，不再阻擋 publish |
| AC-S194-3 | 必做 | Test | nested metadata object 仍回 validation error |
| AC-S194-4 | 必做 | Test | string-only metadata 不產 compatibility warning |
| AC-S194-5 | 建議 | Inspection | docs 說清楚官方格式與相容行為 |
| AC-S194-6 | 必做 | Test | frontmatter compatibility warning 會扣 VALIDATION 分 |

**AC-S194-1: handover compatibility frontmatter 可新增版本**
- Given（前提）已存在 skill `brainstorm-ideas-existing`，且 user 對該 skill 有 write 權限
- And（而且）上傳 zip 內有 `SKILL.md`，frontmatter 含 `allowed-tools` YAML list 與 `metadata.tags: [session-management, context-preservation, cross-agent]`
- When（動作）呼叫 `PUT /api/v1/skills/{id}/versions`
- Then（結果）HTTP status 是 200
- And（而且）`skill_versions` 新增一筆 version row
- And（而且）該 row 的 `frontmatter` JSONB 保留 `allowed-tools` 為 array
- And（而且）該 row 的 `frontmatter` JSONB 保留 `metadata.tags` 為 array

**AC-S194-2: 非官方 frontmatter 變 warning，不再阻擋 publish**
- Given（前提）`SKILL.md` frontmatter 含 `allowed-tools: [Read, Glob]`、`metadata.score: 10` 或 `metadata.enabled: true`
- When（動作）執行 SKILL.md validation
- Then（結果）`ValidationResult.valid == true`
- And（而且）`ValidationResult.warnings` 含對應 key 與 compatibility warning 訊息
- And（而且）warning message 明確寫出官方 agentskills.io 期待的格式

**AC-S194-3: nested metadata object 仍回 validation error**
- Given（前提）`SKILL.md` frontmatter 含 `metadata.owner: {team: platform}`
- When（動作）執行 SKILL.md validation
- Then（結果）`ValidationResult.valid == false`
- And（而且）errors 含 `metadata: key 'owner' nested object is not supported`
- And（而且）`PUT /api/v1/skills/{id}/versions` 不新增 `skill_versions` row

**AC-S194-4: string-only metadata 不產 compatibility warning**
- Given（前提）`SKILL.md` frontmatter 含 `metadata.author: howielab`、`metadata.category: workflow-automation`
- When（動作）執行 SKILL.md validation
- Then（結果）`ValidationResult.valid == true`
- And（而且）`ValidationResult.warnings` 不含 metadata type warning

**AC-S194-5: docs 說清楚官方格式與相容行為**
- Given（前提）使用者打開 `/docs/frontmatter`
- When（動作）查看 `allowed-tools` 與 `metadata` 欄位說明
- Then（結果）頁面顯示 `allowed-tools` 官方格式是空白分隔字串
- And（而且）頁面顯示 `metadata` 官方格式是 string key/value
- And（而且）頁面顯示 Skills Hub 會接受常見非官方格式並標為 compatibility warning
- And（而且）頁面顯示此 compatibility warning 會降低品質評分 VALIDATION 分數

**AC-S194-6: frontmatter compatibility warning 會扣 VALIDATION 分**
- Given（前提）`SKILL.md` frontmatter 符合所有 hard rules，但含 `allowed-tools: [Read, Glob]` 或 `metadata.tags: [session-management, context-preservation]`
- When（動作）`QualityScoreService` 產生 VALIDATION score
- Then（結果）`skill_scores` 的 VALIDATION `total_score` 小於 `100.00`
- And（而且）`dimensions` 內含 `frontmatterOfficialFormat` 或同等 key，`score` 低於 100
- And（而且）`reasoning` 說明哪些欄位使用 compatibility mode 接受
- And（而且）同一份 SKILL.md 若把 `allowed-tools` 改成空白分隔字串、`metadata.tags` 改成字串，VALIDATION `total_score` 回到 `100.00`

### 非功能需求檢查

| 分類 | 對應驗收 | 說明 |
|------|----------|------|
| Performance | AC-S194-3 | nested object 擋掉無界深度；不新增遞迴掃描。 |
| Security | AC-S194-3 | metadata 是 user input，nested object 不放行，避免任意結構擴散到 UI/API。 |
| Reliability | AC-S194-1, AC-S194-6 | addVersion 成功後才寫 row；validation error 仍不寫 row；compatibility warning 不阻擋 score 產生。 |
| Usability | AC-S194-5 | user 看得到官方格式與相容 warning。 |
| Maintainability | AC-S194-2, AC-S194-6 | warning 保留 S135a quality signal，且分數扣分集中在 VALIDATION axis，不把 compatibility 行為藏起來。 |

## 4. 介面與 API 設計

`ValidationResult` 介面不改：

```java
public record ValidationResult(
    boolean valid,
    Map<String, Object> metadata,
    List<String> errors,
    List<String> warnings
) {}
```

`SkillValidator` 新增私有判斷方法：

```java
private void validateMetadataFieldTypes(Map<String, Object> parsed, List<String> errors, List<String> warnings)
private void validateAllowedToolsOfficialFormat(Map<String, Object> parsed, List<String> warnings)
```

行為：

| `metadata.*` input value | output |
|-------------|--------|
| `String` | no warning |
| `Number` / `Boolean` | warning |
| `List<?>` 且每個 item 是 scalar | warning |
| `Map<?, ?>` | error |
| `List<?>` 內含 map/list | error |

| `allowed-tools` input value | output |
|-----------------------------|--------|
| `String` | no warning |
| `List<?>` 且每個 item 是合法 tool token | warning |
| 其他型別或非法 token | error |

`QualityScoreService` 不新增 API 欄位；只把 `ValidationResult.warnings()` 既有資料納入 validation score reasoning。

VALIDATION scoring 調整：

```java
private static final int FRONTMATTER_COMPATIBILITY_WARNING_SCORE = 80;
```

行為：

| warnings | `frontmatterOfficialFormat` dimension | totalScore |
|----------|------------------------------------|------------|
| 無 frontmatter compatibility warning | `score=100`, `reasoning="frontmatter follows agentskills.io official format"` | 若其他 hard rules 全過，仍為 `100.00` |
| 有 1+ frontmatter compatibility warning | `score=80`, `reasoning` 列出 key，例如 `allowed-tools accepted as YAML list; metadata.tags accepted as structured metadata` | 若其他 hard rules 全過，VALIDATION 低於 `100.00` |

分數計算改為取 validation dimensions 平均值，而不是只算 6 個 hard rule pass count：

```java
var scores = List.of(lineScore, bodyScore, nameScore, descScore, fieldsScore, toolsScore, frontmatterFormatScore);
var totalScore = average(scores);
```

這樣 `allowed-tools` list 與 `metadata.tags` array 不會擋 publish，但滿分只留給官方 frontmatter 格式。

## 5. 檔案規劃

| 檔案 | 動作 | 說明 |
|------|------|------|
| `backend/src/main/java/io/github/samzhu/skillshub/skill/validation/SkillValidator.java` | modify | allowed-tools list 與 metadata 非 string scalar/list 改為 bounded compatibility warning；nested object/list 仍 error。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/validation/SkillValidatorTest.java` | modify | 新增 S194 AC cases。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillUploadAllowedToolsTest.java` | modify | addVersion handover-style frontmatter 成功案例。 |
| `backend/src/test/java/io/github/samzhu/skillshub/score/QualityScoreServiceTest.java` | modify | frontmatter compatibility warning 不阻擋 score 產生，但會扣 VALIDATION 分並在 reasoning 顯示原因。 |
| `frontend/src/pages/docs/FrontmatterPage.tsx` | modify | allowed-tools / metadata 官方格式與 Skills Hub compatibility warning 說明。 |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
