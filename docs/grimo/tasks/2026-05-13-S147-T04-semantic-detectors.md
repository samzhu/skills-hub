# S147-T04: Semantic issue-code detectors

## Spec
S147 — 掃描器語意分析缺口研究

## BDD
Given T03 confirms the semantic rubric and the user approves production implementation
When a skill instructs an agent to expose credentials, follow arbitrary third-party content, execute financial actions, read sensitive/workspace data, or modify local/shared resources
Then the scanner emits the matching `E004`, `W007`, `W009`, `W011`, `W017`, `W018`, `W019`, or `W020` issue code
And tests can verify parser and mapping behavior with fixed judgement fixtures when live AI credentials are unavailable

## Target Files
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/PromptInjectionDetector.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/CredentialHandlingDetector.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/ThirdPartyContentExposureDetector.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/SensitiveDataExposureDetector.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/WorkspaceDataExposureDetector.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/FinancialExecutionDetector.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/SharedResourceModificationDetector.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/LocalDataModificationDetector.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/engines/LlmJudge.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/detectors/`

## Depends On
- S147-T02 PASS
- S147-T03 PASS
- user confirmation after semantic POC

## Status
pending
