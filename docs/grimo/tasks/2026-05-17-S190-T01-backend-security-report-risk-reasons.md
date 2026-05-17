# S190-T01: Backend Security Report Risk Reasons

## 對應規格
S190：Security Risk Reason UI

## 這個 task 要做什麼
`GET /api/v1/skills/{id}/security-report` 回應要多 `riskReasons`。使用者打開安全頁時，前端不能只看到 `findings=[]`，也要拿到「為什麼是 LOW / NONE」的原因。這個 task 只處理 API response 與舊資料 fallback，不改 scanner 寫入邏輯。

## 使用者情境（BDD）
Given（前提）`skill_versions.risk_assessment` 已有 `riskReasons`，內容包含 `code`、`label`、`detail`、`impact`、`evidence`、`action`
When（動作）前端呼叫 `GET /api/v1/skills/{id}/security-report`
Then（結果）response body 保留既有 `findings`
And（而且）response body 多 `riskReasons` array，每筆都有上述六個欄位。

Given（前提）production 舊資料 `risk_assessment.findings=[]`，但沒有 `risk_assessment.riskReasons`
And（而且）`skill_versions.allowed_tools` 含 `Bash`、`Write`
When（動作）前端呼叫 `GET /api/v1/skills/{id}/security-report`
Then（結果）response body 含 `riskReasons[0].code=LEGACY_ALLOWED_TOOLS`
And（而且）`riskReasons[0].evidence` 含 `Bash`、`Write`
And（而且）`riskReasons[0].detail` 用白話說「這個技能可以要求 AI 使用工具」。

Given（前提）`SkillVersion.publish` 收到 frontmatter `allowed-tools` 的 YAML list shape，例如 `["Read","Glob","Bash"]`
When（動作）建立 `SkillVersion`
Then（結果）`version.getAllowedTools()` 是 `["Read","Glob","Bash"]`
And（而且）不會出現 `"[Read,"` 或 `"Bash]"` 這種 `toString()` 切壞的 token。

## 研究來源
- `docs/grimo/specs/2026-05-17-S190-security-risk-reason-ui.md` §2.0, §2.2, §4
- `docs/grimo/specs/archive/2026-05-01-S073-allowed-tools-yaml-list-interop.md` §4：YAML list 要依 Java type 分流，不可用 `toString()` 當 token source。
- `backend/src/main/java/io/github/samzhu/skillshub/security/SecurityReportResponse.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/SecurityReportService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersion.java`

## 先做 POC
- POC：not required — response 是 additive DTO 欄位，`risk_assessment` 已是 JSONB map；不加 dependency、不改 schema。
- Fixture：
  - `persisted-risk-reasons`: risk JSON already contains `riskReasons` → API 原樣回傳 typed DTO。
  - `legacy-allowed-tools`: risk JSON has `findings=[]` only, version allowedTools contains `Bash Write` → API fallback 回 `LEGACY_ALLOWED_TOOLS`。
  - `yaml-list-allowed-tools`: frontmatter value is `List.of("Read","Glob","Bash")` → persisted allowedTools 是乾淨 list。

## 正式程式怎麼做
- Class / file 名稱：
  - `SecurityReportResponse.java`
  - `SecurityReportService.java`
  - `SkillVersion.java`
  - `Skill.java`（若 command path 也使用同一解析 helper）
- 入口：`SecurityReportService.getReport(...)`
- 必要行為：
  - `SecurityReportResponse` 新增 nested record `RiskReason(String code, String label, String detail, String impact, List<String> evidence, String action)`。
  - 主 response 新增 `List<RiskReason> riskReasons`。
  - 舊 constructor 保持 source-compatible，預設 `riskReasons=List.of()`。
  - `SecurityReportService` 先讀 `riskAssessment.get("riskReasons")`；有值就 convert 成 DTO list。
  - 若 JSONB 沒有 `riskReasons`，但 `version.getAllowedTools()` 非空，回 `LEGACY_ALLOWED_TOOLS`。
  - 若 JSONB 沒有 `riskReasons`、`findings=[]`、`allowedTools=[]`，回 `NO_FINDINGS_NO_CAPABILITIES`，detail 包含 `沒有工具宣告或 scripts/`。
  - `SkillVersion.parseAllowedTools` 和 `Skill.parseAllowedTools` 必須支援 `List<?>`，與 S073 validator 行為一致。
- Finding / response / DB 欄位：
  - `riskReasons[].code`: JSONB 原值或 fallback code。
  - `riskReasons[].label`: 非工程師可讀文字，例如 `這個技能可以要求 AI 使用工具`。
  - `riskReasons[].detail`: 白話原因，不只寫 `allowed-tools`。
  - `riskReasons[].evidence`: tools list 或空 list。

## 單元測試 / 整合測試
- `SecurityReportServiceTest`
  - `@DisplayName("AC-S190-5: risk_assessment.riskReasons → GET security report contains riskReasons")`
  - `@DisplayName("AC-S190-6: legacy LOW report without riskReasons uses SkillVersion.allowedTools")`
  - `@DisplayName("AC-S190-3: legacy NONE report without capabilities returns no-findings reason")`
- `SecurityReportControllerTest`
  - `@DisplayName("AC-S190-5: GET /security-report JSON exposes riskReasons")`
- `SkillVersionAggregateTest`
  - `@DisplayName("AC-S190-6: allowed-tools YAML list persists clean allowedTools tokens")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/security/SecurityReportResponse.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/SecurityReportService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersion.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/SecurityReportServiceTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/SecurityReportControllerTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillVersionAggregateTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests '*SecurityReportServiceTest' --tests '*SecurityReportControllerTest' --tests '*SkillVersionAggregateTest'`

## 前置條件
- 無

## 狀態
PASS

## Result
Date: 2026-05-17

Tests:
- `cd backend && ./gradlew test --tests '*SecurityReportServiceTest' --tests '*SecurityReportControllerTest' --tests '*SkillVersionAggregateTest'` → PASS (`BUILD SUCCESSFUL in 4m 19s`)
- `cd backend && ./gradlew test --tests '*SecurityReportServiceTest' --tests '*SecurityReportControllerTest' --tests '*SkillVersionAggregateTest' -x processTestAot` → PASS (`BUILD SUCCESSFUL in 19s`) after fixture wording sync

Files changed:
- `backend/src/main/java/io/github/samzhu/skillshub/security/SecurityReportResponse.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/SecurityReportService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersion.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/SecurityReportServiceTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/SecurityReportControllerTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillVersionAggregateTest.java`

Notes:
- RED first failed because `SecurityReportResponse.riskReasons()` and `RiskReason` did not exist.
- GREEN adds additive `riskReasons` response data, legacy fallback from `SkillVersion.allowedTools`, `NO_FINDINGS_NO_CAPABILITIES` fallback, and YAML list parsing for `allowed-tools`.
- Fallback detail now says `這個技能可以要求 AI 使用工具：Bash、Write` so the UI can show a non-engineer-readable reason instead of only `allowed-tools`.
