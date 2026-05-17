# S190-T02: Scanner Persists Risk Reasons

## 對應規格
S190：Security Risk Reason UI

## 這個 task 要做什麼
新掃描結果寫入 `skill_versions.risk_assessment` 時，要同步寫 `riskReasons`。這樣新上傳的 skill 不需要等 fallback，API 就能直接列出 allowed-tools、scripts 或 findings 造成目前風險等級的原因。

## 使用者情境（BDD）
Given（前提）package 有 `scripts/check_deps.sh` 與 `scripts/transcribe.py`
When（動作）`ScanOrchestrator` 完成掃描並 persist result
Then（結果）`risk_assessment.riskReasons` 含 `code=SCRIPTS_INCLUDED`
And（而且）`evidence` 含兩個 script path。

Given（前提）package 沒有 scripts，但 `SKILL.md` frontmatter 有 `allowed-tools: [Read, Glob, Bash, Write]`
When（動作）`ScanOrchestrator` 完成掃描並 persist result
Then（結果）`risk_assessment.level=LOW`
And（而且）`risk_assessment.riskReasons` 含 `code=ALLOWED_TOOLS_DECLARED`
And（而且）`detail` 用白話說「這個技能可以要求 AI 使用工具」。

Given（前提）package 只有 `SKILL.md`，無 scripts，無 allowed-tools，且 findings 空
When（動作）`ScanOrchestrator` 完成掃描並 persist result
Then（結果）`risk_assessment.level=NONE`
And（而且）`riskReasons` 含 `NO_FINDINGS_NO_CAPABILITIES`。

Given（前提）scanner 找到 HIGH finding `W008`
When（動作）`ScanOrchestrator` 完成掃描並 persist result
Then（結果）`riskReasons` 含 `FINDINGS_PRESENT`
And（而且）`impact=HIGH`
And（而且）`action=FIX_REQUIRED`。

## 研究來源
- `docs/grimo/specs/2026-05-17-S190-security-risk-reason-ui.md` §2.6, §4
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScanOrchestrator.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/RiskLevel.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/ScanOrchestratorTest.java`

## 先做 POC
- POC：not required — `ScanOrchestrator.persist` 已寫 `risk_assessment` map；task 是在同一 map 加 deterministic key。
- Fixture：
  - `scripts-only`: package entries contain `scripts/check_deps.sh`, `scripts/transcribe.py` → `SCRIPTS_INCLUDED`。
  - `allowed-tools-only`: frontmatter contains list tools, no scripts → `ALLOWED_TOOLS_DECLARED`。
  - `pure-docs`: no scripts/tools/findings → `NO_FINDINGS_NO_CAPABILITIES`。
  - `high-finding`: findings contain HIGH → `FINDINGS_PRESENT`。

## 正式程式怎麼做
- Class / file 名稱：`ScanOrchestrator.java`
- 入口：`persist(SkillVersionPublishedEvent event, RiskLevel finalLevel, List<SecurityFinding> allFindings, Map<String, AnalysisOutput> perEngine, Map<String, Object> sarif)`
- 必要行為：
  - 在 `riskAssessment.put(...)` 前建立 `riskReasons` list。
  - 從 event / scan context 能取得的資料列出 allowed-tools 與 scripts。
  - `ALLOWED_TOOLS_DECLARED` evidence 是乾淨 tool names。
  - `SCRIPTS_INCLUDED` evidence 是 `scripts/` 檔案路徑。
  - `FINDINGS_PRESENT` 不取代 `findings[]`；只補總結原因。
  - reason order 固定：findings reason、allowed-tools reason、scripts reason、none reason（若都沒有）。
  - 不改 `classifyRiskLevel` 的分級規則。
- Finding / response / DB 欄位：
  - `risk_assessment.riskReasons`: List<Map<String,Object>> 或 DTO record 轉 map 後可被 Jackson 存 JSONB。
  - 每筆欄位：`code`、`label`、`detail`、`impact`、`evidence`、`action`。

## 單元測試 / 整合測試
- `ScanOrchestratorTest`
  - `@DisplayName("AC-S190-7: package with scripts persists SCRIPTS_INCLUDED risk reason")`
  - `@DisplayName("AC-S190-1b: allowed-tools-only scan persists ALLOWED_TOOLS_DECLARED risk reason")`
  - `@DisplayName("AC-S190-3: pure docs scan persists NO_FINDINGS_NO_CAPABILITIES reason")`
  - `@DisplayName("AC-S190-4: findings scan persists FINDINGS_PRESENT reason")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScanOrchestrator.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/ScanOrchestratorTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests '*ScanOrchestratorTest'`

## 前置條件
- S190-T01 PASS

## 狀態
PASS

## Result
Date: 2026-05-18

Tests:
- `cd backend && ./gradlew test --tests '*ScanOrchestratorTest' -x processTestAot` → PASS (`BUILD SUCCESSFUL in 23s`)
- `cd backend && ./gradlew test --tests '*ScanOrchestratorTest'` → PASS (`BUILD SUCCESSFUL in 3m 48s`)

Files changed:
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScanOrchestrator.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/ScanOrchestratorTest.java`

Notes:
- RED first failed because `risk_assessment.riskReasons` was absent for scripts, allowed-tools-only, pure-docs, and HIGH-finding scans.
- GREEN persists deterministic `riskReasons` in the same `riskAssessment` map as `level`, `findings`, `notices`, `sarif`, `scannedAt`, and `sourceEventId`.
- New reason codes covered by tests: `SCRIPTS_INCLUDED`, `ALLOWED_TOOLS_DECLARED`, `NO_FINDINGS_NO_CAPABILITIES`, `FINDINGS_PRESENT`.
- `classifyRiskLevel(...)` was not changed; this task only explains the already-computed level.
