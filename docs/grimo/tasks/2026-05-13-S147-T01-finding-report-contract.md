# S147-T01: Finding and report contract

## Spec
S147 — 掃描器語意分析缺口研究

## BDD
Given T00 confirms the detector/report contract and the user approves production implementation
When the backend scanner emits a `SecurityFinding` with `issueCode`, `remediation`, and `confidence`
Then `/api/v1/skills/{id}/security-report` returns dynamic `categories` and `findings`
And old stored findings without `issueCode` still map through the existing `ruleId` and `analyzer` fallback
And existing four-key `checks` data remains present for backward compatibility

## Target Files
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/SkillIssueCode.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/SecurityFinding.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/SecurityReportResponse.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/SecurityReportService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/SecurityCategoryMapper.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/`

## Depends On
- S147-T00 PASS
- user confirmation after POC

## Status
blocked（2026-05-14 tick re-validated；waiting user confirmation after T00 PASS）
