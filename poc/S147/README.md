# S147 T00 POC — detector contract

## What this proves

`contract-poc.mjs` models the production risk-assessment JSON contract without editing production code.

- Legacy findings with only `ruleId` + `analyzer` still build the current `shell / paths / secrets / deps` report.
- New Snyk-like findings can add `issueCode / remediation / confidence` while keeping `ruleId` for SARIF compatibility.
- Dynamic categories can show `Credentials`, `External Content`, and `Sensitive Data` from issue codes.
- Detector beans can remain direct `SecurityAnalyzer` implementations; no extra adapter is required for the next production task.

## Verification

```bash
node poc/S147/contract-poc.mjs
```

Expected output:

```text
S147 T00 POC PASS
legacy checks: shell=FAIL, paths=PASS, secrets=WARN, deps=PASS
dynamic categories: Credentials=FAIL, External Content=WARN, Sensitive Data=WARN
detectors: HardcodedSecretDetector, ThirdPartyContentExposureDetector, SensitiveDataExposureDetector
```
