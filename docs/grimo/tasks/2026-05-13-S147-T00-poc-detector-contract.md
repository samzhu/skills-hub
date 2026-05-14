# S147-T00: POC detector contract

## Spec
S147 — 掃描器語意分析缺口研究

## BDD
Given the current scanner stores `riskAssessment` JSON with legacy `ruleId` and `analyzer` fields
When an isolated POC under `poc/S147/` models `issueCode`, remediation text, confidence, direct `SecurityAnalyzer` detector beans, and dynamic report categories
Then old JSON can still build the current security report
And new Snyk-like findings can build category summaries for `Credentials`, `External Content`, and `Sensitive Data`
And no production code under `backend/src/main` or `frontend/src` is changed by this task

## Target Files
- `poc/S147/`
- `docs/grimo/specs/2026-05-08-S147-scanner-semantic-gap-research.md`

## Depends On
- none

## Status
PASS（2026-05-14）

## Verification
`node poc/S147/contract-poc.mjs`

Output:

```text
S147 T00 POC PASS
legacy checks: shell=FAIL, paths=PASS, secrets=WARN, deps=PASS
dynamic categories: Credentials=FAIL, External Content=WARN, Sensitive Data=WARN
detectors: HardcodedSecretDetector, ThirdPartyContentExposureDetector, SensitiveDataExposureDetector
```

## Notes
- 舊 `ruleId + analyzer` finding 可繼續生成既有 `checks.shell/paths/secrets/deps`。
- 新 finding 可同時輸出 `issueCode / remediation / confidence`，且保留 `ruleId`。
- 下一步是 `S147-T01`，但依 task dependency 仍需 user confirm 才能進 production implementation。
