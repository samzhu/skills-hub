# S147-T02: Static issue-code detectors

## Spec
S147 — 掃描器語意分析缺口研究

## BDD
Given the backend report contract accepts Snyk-like `issueCode`
When a skill package contains a hardcoded secret, suspicious runtime download, malicious code pattern, unverifiable external dependency, system service modification, or missing `SKILL.md`
Then the scanner emits `W008`, `E005`, `E006`, `W012`, `W013`, or `W014` with file path, line, evidence, and remediation
And the implementation uses behavior-named detector classes instead of adding unrelated rules to `PatternScanner`

## Target Files
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/HardcodedSecretDetector.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/SuspiciousDownloadDetector.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/MaliciousCodePatternDetector.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/UnverifiableExternalDependencyDetector.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/SystemServiceModificationDetector.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/detectors/SkillManifestPresenceValidator.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/detectors/`

## Depends On
- S147-T01 PASS

## Status
pending
