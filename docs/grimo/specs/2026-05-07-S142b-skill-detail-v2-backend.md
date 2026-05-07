# S142b — SkillDetailPage v2 Backend Supplement

> **Status**: 📐 in-design (sections 1-5 ready；§6 task plan + §7 result 由 /planning-tasks 後續加入)
> **Type**: Backend feature spec
> **Estimate**: S-M (8-10)
> **Depends**: S135a ✅ (QualityScoreController + skill_scores 表)、既有 `risk_findings` JSONB on `skill_versions`
> **Sibling**: [S142a](2026-05-07-S142a-skill-detail-v2-frontend.md) — frontend consumes this spec's API contract
> **Source**:
> - `docs/grimo/ui/prototype/Skills Hub Skill Detail v2.html` (2026-05-07 designer 更新)
> - 工程實作說明（user-provided 2026-05-07）
> - [v2 followup decisions](../ui/prototype/v2-followup-questions.md)

---

## §1 Goal

提供 v2 SkillDetailPage 所需的 backend supplement APIs，讓 frontend (S142a) 一次完整拿到 hero / sidebar 所需資料：

1. **SkillScore composite（89/100 hexagon）** — 公式 `round(0.6 × qualityTotal + 0.4 × securityScore)`，加進既有 `GET /api/v1/skills/{id}/scores` response
2. **SecurityReport 4-quad endpoint** — 新 `GET /api/v1/skills/{id}/security-report` 把既有 `risk_findings` JSONB 分組成 shell/paths/secrets/deps + 個別 status 與 detail
3. **Skill aggregate response 增補** — 加 `verified` / `latestVersionPublishedAt` / `license` / `compatibility[]` / `versionCount` / `openFlagCount` 投影到既有 `GET /api/v1/skills/{id}` response

```
v2 SkillDetailPage 對 backend 的依賴
─────────────────────────────────────────
GET /skills/{id}                      ← 增補 6 fields (verified / publishedAt / license / compat / versionCount / openFlagCount)
GET /skills/{id}/scores               ← 增補 1 field (skillScore composite)
GET /skills/{id}/security-report      ← 新 endpoint (4-quad + meta)
GET /skills/{id}/files                ← reuse 既有 (S074)
GET /skills/{id}/files/{*path}        ← reuse 既有 (S074)
GET /skills/{id}/versions             ← reuse 既有
GET /skills/{id}/stats                ← reuse 既有
GET /skills/{id}/flags                ← reuse 既有
```

### Out of Scope (留 S142a / 後續 spec)

| 項目 | 去向 |
|---|---|
| Frontend rework (page header / hero / tabs / sidebar / Files explorer) | [S142a](2026-05-07-S142a-skill-detail-v2-frontend.md) |
| `verified` 改成可審核 boolean column（curation workflow）| 後續 spec — 目前 derived from existing fields |
| Stats `weeklyDelta` 後端計算 | Frontend derive from existing `/stats` 14d array slice — backend 不重複 |
| SkillScore 寫入 `skill_scores` 為獨立 axis | 不做 — composite computed-on-read；歷史快照需求出現再評估 |
| Engine version / ruleSetVersion 進 DB column | 不做 — 目前 hardcode constant in service；版本變動時改 constant + redeploy |

---

## §2 Approach

### §2.1 Chosen approach (single approach — alternatives challenged)

**SkillScore + SecurityReport: computed-on-read（無新表 / 無新 projection）；Skill aggregate fields: 既有 query 加 LEFT JOIN + COUNT subquery 投影**。

#### 為何不寫入 `skill_scores` 新 row？
SkillScore 是 Quality + Security 的 composite，兩個來源都已 row-stored（Quality 在 `skill_scores`，Security 在 `risk_findings` JSONB）。重新 INSERT composite row：
- 需要 listener 同時 watch Quality 完成 + Security 完成 → 多 event coordination 複雜
- composite 本質是 derived value，不是業務事件
- 重算成本低（單 row + 4-quad reduce），on-read 完全 OK

#### 為何不為 SecurityReport 新 projection table？
`risk_findings` JSONB 已是 source of truth。projection table 等於把 JSONB unpack 成 row：
- 增加寫入成本（每 publish 多 INSERT 1-N rows）
- 4-quad reduce 是 view-layer 邏輯，不是業務分類
- query 模式：always per-skill-version，無需 cross-skill aggregate；JSONB read + in-process group-by O(N) where N = 該 skill 的 finding 數量（典型 0-20）

#### 為何不項目化 `verified` 為獨立 DB column？
`verified = (status === 'PUBLISHED' && riskLevel != null)` 是 derived from 既有 fields。新 column = 多一個需要維護一致性的欄位（status 變動、reactivate、re-scan 都要同步）。Computed on-read 零維護成本，且符合「我們確實檢查過」的 deterministic 語義。未來若改成可審核 boolean（curation workflow）再加 column。

#### 為何 Skill aggregate response 加 fields 而不是新 endpoint？
Detail page hero 一次需要 6 個增補 fields；如果分多 endpoint = N+1 problem on every detail view。Hot path 優化：single GET /skills/{id} 拿全部 hero 資料。

### §2.2 Skill Score composite formula（per 工程實作說明 + user 確認）

```
qualityScore  = 既有 GET /scores response 的 total (0-100, weighted avg of 3 axes)
                = round(0.2 × validation.totalScore + 0.4 × implementation.totalScore + 0.4 × activation.totalScore)

securityScore = 從 SecurityReport 的 4 個 check status 計算：
                  起始 100
                  每個 warn → -25
                  任何 fail → 強制設 25
                pass=100 / 1 warn=75 / 2 warn=50 / 3+ warn=25 / any fail=25

skillScore    = round(0.6 × qualityScore + 0.4 × securityScore)
```

範例：qualityScore=92, securityScore=100 → `round(0.6×92 + 0.4×100) = round(55.2 + 40) = 95`
範例：qualityScore=85, securityScore=100 → `round(51 + 40) = 91`
範例：qualityScore=92, 1 warn (75) → `round(55.2 + 30) = 85`

**Quality 404 (未評分)**：`GET /scores` 回 404 QUALITY_NOT_EVALUATED；同時 `skillScore` field absent — frontend 顯「—」hexagon (per D3 default)。

### §2.3 SecurityReport 4-quad mapping（per Phase 2 research findings）

`analyzer` field 為 partition key（不是 ruleId prefix 單獨）：

| Category | 條件 |
|---|---|
| **shell** | `analyzer == "pattern"` AND `ruleId.startsWith("DANGEROUS_COMMAND_") OR ruleId.startsWith("PIPE_TO_SHELL_")`，加上 `analyzer == "resource-dos"` 全部（fork bomb / dev-zero / infinite loop 等也是 shell-pattern 行為，折入此分類）|
| **paths** | `analyzer == "pattern"` AND `ruleId.startsWith("SENSITIVE_PATH_")` |
| **secrets** | `analyzer == "secret"` |
| **deps** | `analyzer == "dep-vuln"` |

Outliers（不入 4-quad，但仍貢獻 overall risk_level）:
- `analyzer == "prompt-injection"` (14 PI_ rules — OWASP LLM01) — roll-up 到 risk_level only
- `analyzer == "meta"` (3 META_ rules — cross-engine signals) — 同上
- `analyzer == "llm-judge"` (free-form ruleId) — 同上

理由：prototype 只設計 4 個 quad；增加 5th quad 違反設計約定。Risk pill 仍獨立顯示總風險等級，PI/META/LLM finding 透過 risk_level 反映。未來可開 polish spec 加 5th quad 或 expand。

### §2.4 Status 計算 per category

```
status(findings: List<SecurityFinding>) =
  if any finding.severity == HIGH      → "fail"
  else if any finding.severity == MEDIUM → "warn"
  else                                   → "pass"   // empty 或全 LOW
```

### §2.5 Overall status

```
overall = if any check.status == "fail"  → "fail"
          else if any check.status == "warn" → "warn"
          else                                → "pass"
```

### §2.6 Detail string

每個 check 的 `detail` field（optional）格式：
- 0 findings → null
- 1 finding → `"{ruleId} · line {lineNumber}: {snippet}"`（取自 `SecurityFinding.message + filePath + lineNumber`）
- 2+ findings → `"{N} findings: {ruleId1}, {ruleId2}, ..."` (取前 3 個 distinct ruleId)

### §2.7 Engine + RuleSet version 來源

目前無 DB 欄位追蹤；service 端 hardcode constant：
```java
public class SecurityReportService {
  static final String ENGINE_VERSION = "risk-scanner v1.0";
  static final String RULESET_VERSION = "2026-05";
  // ...
}
```
未來若需要 per-skill-version snapshot，加 column `risk_engine_version` / `risk_ruleset_version` 到 `skill_versions`。

### §2.8 Research Citations

| Source | 1-line summary |
|---|---|
| Phase 2 research finding (Explore agent 2026-05-07) | `analyzer` field 是 partition key (not ruleId prefix alone)；4-quad clean partition + 14 PI_ + 6 DoS + 3 META + LLM-generated outliers fold-in strategy |
| `S135a archived spec §4.3` | `ScoreResponse` 既有 shape，`skillScore` 加 field 不破壞既有 caller |
| `SecurityFinding.java` | record fields: ruleId, severity, message, filePath, analyzer, owaspAst |
| `RiskAssessment` JSONB on `skill_versions.risk_findings` | source of truth for security findings |
| 工程實作說明 §3 | SkillScore 公式 `0.6Q + 0.4S` + securityScore tier (pass=100/1warn=75/2warn=50/anyfail=25) |
| 工程實作說明 §8 | SecurityCheck shape `{status, detail}` + overall pass/warn/fail |

### §2.9 Sufficiency Gate — Confidence Classification

| Decision | Confidence | Action |
|---|---|---|
| `analyzer` field partition key | Validated（Phase 2 research subagent 確認）| Adopt |
| 4-quad mapping rule | Validated | Adopt |
| Outliers fold-in (DoS → shell, PI/META/LLM → risk_level only) | Validated | Adopt |
| Status calc (HIGH→fail / MEDIUM→warn / else→pass) | Validated（per 工程說明 §8 + 既有 RiskLevel mapping）| Adopt |
| skillScore computed-on-read | Validated（reuse 既有 ScoreResponse.from + 加 securityScore lookup）| Adopt |
| Skill aggregate field projection (LEFT JOIN + COUNT subquery) | Hypothesis（需確認 query plan 不爆 — `skill_versions` LEFT JOIN ORDER BY published_at DESC LIMIT 1 + 兩個 COUNT subquery）| POC during T01；如果 query 慢，改用 in-process compose（多 query 但每個都 indexed） |
| Engine + RuleSet version hardcoded constant | Pragmatic — defer DB column until version drift 真實出現 | Adopt（document in service Javadoc）|

---

## §3 Acceptance Criteria

> 命名格式對齊 `qa-strategy.md` §AC-to-Test Contract — `@DisplayName("AC-S142b-N: ...")` + `@Tag("AC-S142b-N")`。
> Verify command: `./gradlew clean test jacocoTestReport`（per `qa-strategy.md` V01）。

```
Scenario: AC-S142b-1 — Skill aggregate response 含增補 6 fields
  Given skill `demo-skill` v1.2.0 已 PUBLISHED + 已掃描 (riskLevel=LOW)
  When  GET /api/v1/skills/{id}
  Then  200 OK
  And   response 含 verified=true, latestVersionPublishedAt, license, compatibility[], versionCount, openFlagCount
  And   verified = (status==PUBLISHED && riskLevel != null)
  And   latestVersionPublishedAt = 最新版本的 publishedAt
  And   license / compatibility 從 latest version frontmatter 取
  And   versionCount = COUNT skill_versions WHERE skill_id=:id
  And   openFlagCount = COUNT flags WHERE skill_id=:id AND status='OPEN'

Scenario: AC-S142b-2 — Verified 對 DRAFT skill 為 false
  Given skill status=DRAFT, riskLevel=null（無 published version）
  When  GET /api/v1/skills/{id}
  Then  response.verified = false
  And   latestVersionPublishedAt / license / compatibility 為 null

Scenario: AC-S142b-3 — Verified 對 SUSPENDED skill 為 false
  Given skill status=SUSPENDED, 曾 PUBLISHED + 已掃描
  When  GET /api/v1/skills/{id}
  Then  response.verified = false
  (per D1: 只有當前 status === 'PUBLISHED' 才算 verified)

Scenario: AC-S142b-4 — GET /scores response 含 skillScore composite
  Given skill `demo-skill` 已評分（quality total=92）+ 已掃描（4 quad 全 pass → securityScore=100）
  When  GET /api/v1/skills/{id}/scores
  Then  200 OK
  And   response.skillScore = round(0.6 × 92 + 0.4 × 100) = round(55.2 + 40) = 95
  And   既有 fields (validation / implementation / activation / total) 不變

Scenario: AC-S142b-5 — Quality 404 時 GET /scores 不回 skillScore
  Given skill `pending-skill` 剛 publish 還沒評 quality（Quality 404）
  When  GET /api/v1/skills/{id}/scores
  Then  404 QUALITY_NOT_EVALUATED（既有行為）
  (frontend 看到 404 → SKILL SCORE 顯「—」per D3)

Scenario: AC-S142b-6 — SecurityReport 4-quad endpoint
  Given skill 含 risk_findings: 1 GITHUB_PAT (HIGH, secret) + 1 SENSITIVE_PATH_SSH (HIGH, pattern)
  When  GET /api/v1/skills/{id}/security-report
  Then  200 OK
  And   response.checks.shell.status = "pass"
  And   response.checks.paths.status = "fail"  (1 HIGH SENSITIVE_PATH_SSH)
  And   response.checks.secrets.status = "fail"  (1 HIGH GITHUB_PAT)
  And   response.checks.deps.status = "pass"
  And   response.overall = "fail"
  And   response.engineVersion = "risk-scanner v1.0"  (constant)
  And   response.ruleSetVersion = "2026-05"  (constant)
  And   response.scannedAt = SkillVersion.publishedAt OR risk_assessment.scanned_at

Scenario: AC-S142b-7 — SecurityReport detail string 格式
  Given skill 1 finding: GITHUB_PAT line=14 message="Hardcoded GitHub PAT"
  When  GET /api/v1/skills/{id}/security-report
  Then  response.checks.secrets.detail = "GITHUB_PAT · line 14: Hardcoded GitHub PAT"

  Given skill 3 secret findings: GITHUB_PAT, AWS_SECRET_KEY, OPENAI_KEY
  When  GET /api/v1/skills/{id}/security-report
  Then  response.checks.secrets.detail = "3 findings: GITHUB_PAT, AWS_SECRET_KEY, OPENAI_KEY"

Scenario: AC-S142b-8 — DoS findings 折入 shell category
  Given skill 含 1 FORK_BOMB (HIGH, resource-dos)
  When  GET /api/v1/skills/{id}/security-report
  Then  response.checks.shell.status = "fail"
  (per §2.3 outlier fold-in)

Scenario: AC-S142b-9 — PI / META / LLM findings 不入 4-quad
  Given skill 含 1 PI_ROLE_JAILBREAK (HIGH, prompt-injection) + 1 META_OPACITY (MEDIUM, meta) + 1 LLM-generated finding
  When  GET /api/v1/skills/{id}/security-report
  Then  response.checks 4 個 quad 都 status="pass"（無 findings 進入這 4 quad）
  And   skill.riskLevel 仍因 PI HIGH 升級為 HIGH（既有行為，不變）

Scenario: AC-S142b-10 — 未掃描 skill 的 SecurityReport
  Given skill 已 publish 但 risk_findings 為 null（scan 還沒跑）
  When  GET /api/v1/skills/{id}/security-report
  Then  404 SECURITY_NOT_SCANNED
  And   response.error = "SECURITY_NOT_SCANNED"
  And   response.message = "Security report will be available shortly after publish."

Scenario: AC-S142b-11 — Skill aggregate response 不破壞既有 caller
  Given 既有 frontend / E2E test 只用 Skill response 的 name / description / status / riskLevel 等舊 field
  When  S142b 增補新 field
  Then  既有 caller 不需改（純 field addition；既有 deserializer 有 unknownFields 容錯 OR Jackson default ignore）
  And   API contract test PASS（無 breaking change）
```

---

## §4 Interface Design

### §4.1 Module structure

```
io.github.samzhu.skillshub.security
├── (既有) SecurityFinding.java
├── (既有) RiskScanner / PatternScanner / SecretScanner / DependencyVulnScanner / etc
├── SecurityReportController.java                      [NEW]
├── SecurityReportService.java                         [NEW]
├── SecurityReportResponse.java                        [NEW] (DTO record)
└── SecurityCategoryMapper.java                        [NEW] (analyzer + ruleId → category logic)

io.github.samzhu.skillshub.score
├── (既有) QualityScoreController.java                  [MODIFIED — extend response]
├── (既有) ScoreResponse.java                           [MODIFIED — add skillScore field]
└── SkillScoreCalculator.java                          [NEW] (compose Quality + Security)

io.github.samzhu.skillshub.skill.query
├── (既有) SkillQueryService.java                       [MODIFIED — add field projection]
├── (既有) SkillQueryController.java                    [MODIFIED — return augmented Skill]
└── (既有) Skill.java response                          [MODIFIED — add 6 fields]
```

### §4.2 Skill aggregate response augmentation

```java
// existing Skill class (response side) — add 6 fields
public class Skill {
    // ... existing fields (id / name / description / author / category / latestVersion / riskLevel / status / downloadCount / averageRating / reviewCount / ownerId / createdAt / updatedAt)

    // S142b additions:
    private boolean verified;                       // derived: status==PUBLISHED && riskLevel != null
    private String latestVersionPublishedAt;        // ISO 8601 — from latest SkillVersion.publishedAt
    private String license;                         // from latest SkillVersion.frontmatter.license
    private List<String> compatibility;             // from latest SkillVersion.frontmatter.compatibility
    private long versionCount;                      // COUNT skill_versions WHERE skill_id=:id
    private long openFlagCount;                     // COUNT flags WHERE skill_id=:id AND status='OPEN'
}
```

`SkillQueryService` 既有 query 加 LEFT JOIN + COUNT subquery；如效能不佳改 in-process compose。

### §4.3 SkillScore composite — extend ScoreResponse

```java
// score/ScoreResponse.java — add skillScore field
public record ScoreResponse(
    String skillId,
    String skillVersionId,
    String skillVersion,
    Instant evaluatedAt,
    String evaluatorVersion,
    AxisResponse validation,
    AxisResponse implementation,
    AxisResponse activation,
    int total,                  // 既有 (Quality weighted avg)
    Integer skillScore          // NEW — composite per §2.2; absent if security not yet scanned
) { ... }
```

`QualityScoreController.getScores()` 多 1 步：
```java
// pseudo
var qualityRows = scoreRepo.findLatestBySkillId(skillId);
if (qualityRows.isEmpty()) throw new NotEvaluatedException(...);   // 既有 404

var qualityTotal = computeWeightedTotal(qualityRows);              // 既有
var securityScore = securityCategoryMapper.computeSecurityScore(skillId);   // NEW
var skillScore = (securityScore != null)
    ? Math.round(0.6 * qualityTotal + 0.4 * securityScore)
    : null;                                                         // null if security not scanned

return ScoreResponse.from(qualityRows, qualityTotal, skillScore);
```

### §4.4 SecurityReport endpoint

```
GET /api/v1/skills/{id}/security-report
GET /api/v1/skills/{id}/security-report?versionId={skillVersionId}    -- 指定版本

200 OK
{
  "skillId": "...",
  "skillVersionId": "...",
  "skillVersion": "1.2.0",
  "scannedAt": "2026-05-04T...",
  "engineVersion": "risk-scanner v1.0",
  "ruleSetVersion": "2026-05",
  "overall": "pass" | "warn" | "fail",
  "checks": {
    "shell":   { "status": "pass" | "warn" | "fail", "detail": "..." | null },
    "paths":   { "status": "pass" | "warn" | "fail", "detail": "..." | null },
    "secrets": { "status": "pass" | "warn" | "fail", "detail": "..." | null },
    "deps":    { "status": "pass" | "warn" | "fail", "detail": "..." | null }
  }
}

404 SECURITY_NOT_SCANNED
{ "error": "SECURITY_NOT_SCANNED", "message": "Security report will be available shortly after publish." }
```

### §4.5 SecurityCategoryMapper

```java
@Component
public class SecurityCategoryMapper {

    public enum Category { SHELL, PATHS, SECRETS, DEPS }
    public enum CheckStatus { PASS, WARN, FAIL }

    /**
     * Partition findings by analyzer + ruleId prefix per §2.3.
     * Outliers (PI_, META_, LLM-generated) returned in separate "excluded" list — not used in 4-quad,
     * but contribute to Skill.riskLevel via existing scanner pipeline.
     */
    public Map<Category, List<SecurityFinding>> partition(List<SecurityFinding> findings) { ... }

    /**
     * Compute status per §2.4: HIGH→fail / MEDIUM→warn / else→pass.
     */
    public CheckStatus computeStatus(List<SecurityFinding> findings) { ... }

    /**
     * Compute securityScore per §2.2: pass=100 / 1warn=75 / 2warn=50 / 3+warn=25 / anyfail=25.
     * Returns null if findings are null (skill not yet scanned).
     */
    public Integer computeSecurityScore(Map<Category, CheckStatus> categoryStatuses) { ... }

    /**
     * Compute detail string per §2.6.
     */
    public String formatDetail(List<SecurityFinding> findings) { ... }
}
```

### §4.6 No SQL migration

S142b 不引入新 table / column。所有新 endpoint 都 read-only 從既有 source 投影：
- Skill aggregate fields → SQL JOIN/COUNT on existing tables
- SecurityReport → JSONB read on `skill_versions.risk_findings`
- SkillScore → in-process compose from existing `skill_scores` rows + Security findings

如未來需要持久化 skillScore（performance），開 follow-up spec 加 `skills.skill_score INT NULL` column + listener projection。

---

## §5 File Plan

### §5.1 New files

```
backend/src/main/java/io/github/samzhu/skillshub/security/
├── SecurityReportController.java                                           [NEW]
├── SecurityReportService.java                                              [NEW]
├── SecurityReportResponse.java                                             [NEW] (DTO record)
├── SecurityCategoryMapper.java                                             [NEW] (analyzer + ruleId → category)
├── SecurityNotScannedException.java                                        [NEW] (→ 404 SECURITY_NOT_SCANNED via既有 ErrorResponseHandler)

backend/src/main/java/io/github/samzhu/skillshub/score/
└── SkillScoreCalculator.java                                               [NEW] (compose Quality + Security)

backend/src/test/java/io/github/samzhu/skillshub/security/
├── SecurityReportControllerTest.java                                       [NEW] (extends WebMvcSliceTestBase)
├── SecurityReportServiceTest.java                                          [NEW] (extends RepositorySliceTestBase)
└── SecurityCategoryMapperTest.java                                         [NEW] (pure unit — no Spring context)

backend/src/test/java/io/github/samzhu/skillshub/score/
└── SkillScoreCalculatorTest.java                                           [NEW] (pure unit)

backend/src/test/java/io/github/samzhu/skillshub/skill/query/
└── SkillQueryServiceTest.java                                              [MODIFIED — add 6 field projection assertions]
```

### §5.2 Modified files

| File | 變更 |
|---|---|
| `backend/src/main/java/.../skill/query/SkillQueryService.java` | 增 LEFT JOIN skill_versions LIMIT 1 + COUNT subqueries；map 6 new fields |
| `backend/src/main/java/.../skill/domain/Skill.java` 或 response DTO | 加 6 new fields（per §4.2）|
| `backend/src/main/java/.../score/QualityScoreController.java` | response 加 skillScore；call SkillScoreCalculator |
| `backend/src/main/java/.../score/ScoreResponse.java` | 加 `Integer skillScore` field |
| `backend/src/main/java/.../score/QualityScoreServiceTest.java` | 新增 skillScore assertion |
| `backend/src/test/resources/test-fixtures/` | 加 SecurityReport 測試用的 risk_findings JSONB fixture |
| `docs/grimo/architecture.md` | 加 SecurityReportController 到 module table；加 SkillScoreCalculator 到 score module flow |
| `docs/grimo/glossary.md` | 加 `SkillScore`（composite）/ `SecurityReport`（4-quad view）/ `verified`（derived）三 term |

### §5.3 No file pre-creation

S142a (frontend) 需要的 component / TypeScript types 不在 S142b 創建 — per planning-spec **Forbidden File-Plan Patterns**「XS or S spec MUST NOT pre-create files for downstream specs」。S142a 自己負責 frontend 部分。

---

## §6 Task Plan

> **POC**: not required — all design decisions Validated per §2.9。LEFT JOIN performance（spec §2.9 唯一 Hypothesis）為 implementation detail，不是 design hypothesis；於 T04 用 EXPLAIN ANALYZE 驗即可，不擋 task creation。
> **Task count**: 5 (refined from spec draft 7 — T03 controller 併入 T02 service；T06 verify 併入 T07 doc-sync)
> **Pattern**: TDD RED→GREEN→REFACTOR per task。

### Task Index

| Task | Topic | Depends | AC Coverage |
|------|-------|---------|-------------|
| **T01** | SecurityCategoryMapper pure unit (partition + status + score + detail) | — | AC-7 partial / AC-8 partial / AC-9 partial |
| **T02** | SecurityReport endpoint (Service + Controller + 404 path + WebMvcSlice + RepoSlice tests) | T01 | AC-6 / AC-7 / AC-8 / AC-9 / AC-10 |
| **T03** | SkillScoreCalculator + extend ScoreResponse + QualityScoreController integration | T01 | AC-4 / AC-5 |
| **T04** | SkillQueryService 6 field projection (LEFT JOIN + COUNT subqueries) | — | AC-1 / AC-2 / AC-3 / AC-11 |
| **T05** | doc-sync (architecture.md + glossary.md) + full V01 regression | T01-T04 | All AC re-verify |

### Execution Order

```
T01 (SecurityCategoryMapper)
  ├─→ T02 (SecurityReport endpoint)  ┐
  └─→ T03 (SkillScore composite)     ├─→ T05 (doc-sync + regression)
T04 (Skill aggregate fields)         ┘
```

T01 unblock 2 條 (T02 + T03)；T04 完全 parallel；T05 為 final regression gate。

### AC-to-Task Coverage Matrix

| AC | Covered by Task |
|----|------------------|
| AC-S142b-1 (Skill response 6 fields — PUBLISHED) | T04 + T05 (regression) |
| AC-S142b-2 (Verified DRAFT = false) | T04 + T05 |
| AC-S142b-3 (Verified SUSPENDED = false) | T04 + T05 |
| AC-S142b-4 (skillScore composite in /scores) | T03 + T05 |
| AC-S142b-5 (Quality 404 → no skillScore) | T03 + T05 |
| AC-S142b-6 (SecurityReport 4-quad endpoint) | T02 + T05 |
| AC-S142b-7 (detail string format) | T01 (unit) + T02 (integration) + T05 |
| AC-S142b-8 (DoS 折入 shell) | T01 + T02 + T05 |
| AC-S142b-9 (PI/META/LLM 不入 4-quad) | T01 + T02 + T05 |
| AC-S142b-10 (404 SECURITY_NOT_SCANNED) | T02 + T05 |
| AC-S142b-11 (API contract no breaking) | T04 + T05 (regression gate) |

每 AC 至少 2 個 covering task；T05 是 high-leverage final gate。

---

## §7 Implementation Results

> Pending — `/planning-tasks S142b` 後填入 task results + AC coverage matrix + verify command output。
