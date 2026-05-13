# S147 T00 Result — detector contract POC

## Verdict

PASS — `poc/S147/contract-poc.mjs` proves the T00 contract without changing production code.

## Command

```bash
node poc/S147/contract-poc.mjs
```

## Output

```text
S147 T00 POC PASS
legacy checks: shell=FAIL, paths=PASS, secrets=WARN, deps=PASS
dynamic categories: Credentials=FAIL, External Content=WARN, Sensitive Data=WARN
detectors: HardcodedSecretDetector, ThirdPartyContentExposureDetector, SensitiveDataExposureDetector
```

## Findings

- 舊 `riskAssessment.findings[]` 只有 `ruleId` + `analyzer` 時，仍可產生現有 `shell / paths / secrets / deps` 四格 report。
- 新 finding 可同時保留 `ruleId`，並新增 `issueCode / remediation / confidence`；這不會破壞 SARIF 相容性。
- `W008 / W011 / W017` 可由 `issueCode` 轉成動態 category：`Credentials / External Content / Sensitive Data`。
- Detector 不需要新增 adapter；直接保留 `SecurityAnalyzer` bean contract 即可支援下一步 production implementation。

## Next Tick

T01 可以開始改後端 finding/report contract，但要先處理或刻意避開目前工作區既有未提交的 S147 spec / roadmap / task 檔案變更。
