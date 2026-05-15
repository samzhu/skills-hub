# S175 — Scan Native Binding Completeness Hotfix

> Status: ✅ shipped v4.64.0
> Date: 2026-05-15
> Trigger: production upload scan failed after `POST /api/v1/skills/upload`
> Related: S173, S148, S148b

## 1. Problem

Cloud Run log at `2026-05-15T01:19:18.010Z` shows upload scan failed for skill
`bbe2f0c0-1255-4193-841c-376d022296a2`:

```text
ConversionFailedException: Failed to convert from HashMap to PGobject
Caused by: UnsupportedFeatureError: Record components not available for record class
io.github.samzhu.skillshub.security.scan.ScanNotice
```

The failing write path is `ScanOrchestrator.persist(...)`: it puts `List<SecurityFinding>`
and `List<ScanNotice>` into `riskAssessment`, then `JdbcConfiguration.MapToPGobjectConverter`
serializes the map with Jackson before `versionRepo.save(sv)`.

S173 registered `LlmJudgement` and `LlmJudgement.RiskClaim` only. That fixed Spring AI
structured output parsing but missed the later persistence JSON boundary.

## 2. Official Framework Basis

Spring Framework `@RegisterReflectionForBinding` is the right mechanism here because it
registers reflection hints for data binding / reflection-based serialization and includes
constructors, fields, properties, and record components for target classes and types used by
their properties / record components.

Spring Boot native-image docs also state that most controller hints are inferred, but direct
use of `WebClient`, `RestClient`, or `RestTemplate` may need explicit
`@RegisterReflectionForBinding`.

The Spring Boot GraalVM introduction explains why this only appeared in production native:
native images are built by static analysis from the application entry point, code not
reachable at image build time can be removed, and GraalVM needs explicit metadata for
dynamic reflection / serialization when Spring AOT cannot infer it. Therefore this hotfix
registers every scanner record that crosses a Jackson, RestClient, or validation binding
boundary outside normal controller inference.

Sources:
- Spring Boot GraalVM native image introduction: https://docs.spring.io/spring-boot/reference/packaging/native-image/introducing-graalvm-native-images.html
- Spring Framework Javadoc: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/aot/hint/annotation/RegisterReflectionForBinding.html
- Spring Boot native image advanced topics: https://docs.spring.io/spring-boot/reference/packaging/native-image/advanced-topics.html

## 3. Boundary Inventory

### 3.1 Must Register

| Boundary | Code path | Classes |
|---|---|---|
| `riskAssessment` JSONB write | `ScanOrchestrator.persist(...)` → `MapToPGobjectConverter` | `AnalysisOutput`, `SecurityFinding`, `ScanNotice`, `ScanResult` |
| LLM structured output | `LlmJudge` / Spring AI structured entity binding | `LlmJudgement`, `LlmJudgement.RiskClaim` |
| SARIF typed record → Map | `SarifReporter.render(...)` → `ObjectMapper.convertValue(...)` | all `SarifModels.*` records |
| OSV HTTP JSON body/response | `OsvClient.querybatch(...)` → `RestClient.body(...)` / `.body(OsvBatchResponse.class)` | all `OsvClient.*` DTO records |
| Metadata validation record components | `MetadataValidator` → `validator.validate(new SkillFrontmatter(...))` | `MetadataValidator.SkillFrontmatter` |

### 3.2 Excluded

| Classes | Reason |
|---|---|
| scanner internal `Rule`, `SignalHit`, `SecretPatternCatalog.Match` | Not serialized, deserialized, returned through controller JSON, or validated by reflection. |
| controller response records | Spring AOT can infer controller return/request types; the production failure was outside that HTTP boundary. |
| `ScanContext` | Constructed and accessed directly in Java; no Jackson / RestClient / validation binding path. |

## 4. Implementation Plan

1. Extend `ScanNativeConfig` so scanner aggregate output and risk assessment payload records are registered together with S173 LLM records.
2. Add package-local native config classes beside package-private DTOs:
   - `security.scan.sarif.SarifNativeConfig`
   - `security.scan.engines.ScanEngineNativeConfig`
3. Add tests that read the annotations and assert every class in §3.1 is present.

## 5. Acceptance Criteria

- AC-S175-1: `ScanNativeConfigTest` proves `AnalysisOutput`, `SecurityFinding`, `ScanNotice`, `ScanResult`, `LlmJudgement`, and `LlmJudgement.RiskClaim` are registered.
- AC-S175-2: `SarifNativeConfigTest` proves all `SarifModels.*` records are registered.
- AC-S175-3: `ScanEngineNativeConfigTest` proves OSV DTO records and `MetadataValidator.SkillFrontmatter` are registered.
- AC-S175-4: Existing S173 structured-output hint guard still passes.
- AC-S175-5: The targeted backend tests above pass.

## 6. Results

Implemented files:
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/ScanNativeConfig.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/sarif/SarifNativeConfig.java`
- `backend/src/main/java/io/github/samzhu/skillshub/security/scan/engines/ScanEngineNativeConfig.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/ScanNativeConfigTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/sarif/SarifNativeConfigTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/security/scan/engines/ScanEngineNativeConfigTest.java`

Verification:

```bash
cd backend
./gradlew test --tests "*ScanNativeConfigTest" \
  --tests "*SarifNativeConfigTest" \
  --tests "*ScanEngineNativeConfigTest" \
  --tests "*StructuredOutputNativeHintCoverageTest"
```

Result: `BUILD SUCCESSFUL in 2m 8s`. The command also completed `processTestAot`
before running the selected tests, so the new configuration classes do not break the Spring
test AOT phase.

### Production Deploy Evidence (2026-05-16)

S175 code commit `a934819 fix(scan): register native binding records` is older than the image deployed for S181/S180 recovery. That image is currently serving as Cloud Run revision `skillshub-00030-rd2`.

Revision check:

```text
gcloud run revisions list --service=skillshub --region=asia-east1 --project=cfh-vibe-lab --format='value(metadata.name,status.conditions[0].status,status.conditions[0].type,spec.containers[0].image)' --limit=5
skillshub-00030-rd2  True  Ready  asia-east1-docker.pkg.dev/cfh-vibe-lab/skillshub/skillshub@sha256:b88d8111289683ff0a11fe35222832988b181d604b220feab3ac74a4c4d44068
```

Native binding error log check after the S181/S180 deploy timestamp:

```text
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="skillshub" AND timestamp>="2026-05-15T19:22:00Z" AND (textPayload:"Record components not available" OR textPayload:"ScanNotice" OR textPayload:"ConversionFailedException" OR textPayload:"UnsupportedFeatureError")' --project=cfh-vibe-lab --limit=20 --format='value(timestamp,resource.labels.revision_name,severity,textPayload)'
<empty>
```

Fresh scan activity check on the current ready revision:

```text
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="skillshub" AND resource.labels.revision_name="skillshub-00030-rd2" AND timestamp>="2026-05-15T19:22:00Z" AND textPayload:"multi-engine scan completed"' --project=cfh-vibe-lab --limit=10 --format='value(timestamp,severity,textPayload)'
<empty>
```

Interpretation: deployment is no longer pending, and no matching native binding error has appeared since the current image started serving. This is passive evidence only; S175 still needs a fresh authenticated upload retest that produces a new scan completion log and proves `ScanNotice` persistence works on the production native image.

### Fresh Upload Retest (2026-05-16)

Chrome production URL:

```text
https://skillshub-644359853825.asia-east1.run.app/publish
```

Steps:

1. Chrome clicked `登入` from `/publish`; the page returned authenticated and showed author `朱尚禮 @csamzhu`.
2. Chrome switched to `貼上文本`.
3. Chrome submitted a new raw `SKILL.md` skill:

```text
skillName: s175-fresh-scan-20260515-210629
version: 1.0.0
category: Testing
metadata.s175: fresh-upload-retest
```

Browser result:

```text
URL: /publish/review?id=8ee45695-c16e-4586-9869-9fdbe110ca88
Page text:
「s175-fresh-scan-20260515-210629」 v1.0.0 已成功發佈
id: 8ee45695-c16e-4586-9869-9fdbe110ca88
低風險
狀態 PUBLISHED
```

Cloud Run request log:

```text
2026-05-15T21:06:42.563550Z skillshub-00030-rd2 POST /api/v1/skills/upload 201 latency=0.165019949s
```

Cloud Run application logs for skill `8ee45695-c16e-4586-9869-9fdbe110ca88`:

```text
2026-05-15T21:06:42.736Z SearchProjection onSkillCreated skillId=8ee45695-c16e-4586-9869-9fdbe110ca88 name=s175-fresh-scan-20260515-210629
2026-05-15T21:06:45.227Z Multi-engine scan triggered for skill 8ee45695-c16e-4586-9869-9fdbe110ca88 version 1.0.0 (15 engines)
2026-05-15T21:06:49.444Z Scan completed for skill 8ee45695-c16e-4586-9869-9fdbe110ca88 v1.0.0: level=LOW, findings=0
```

Native binding error query after the fresh upload timestamp:

```text
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="skillshub" AND timestamp>="2026-05-15T21:06:29Z" AND (textPayload:"Record components not available" OR textPayload:"ScanNotice" OR textPayload:"ConversionFailedException" OR textPayload:"UnsupportedFeatureError")' --project=cfh-vibe-lab --limit=50 --format='value(timestamp,resource.labels.revision_name,severity,textPayload)'
<empty>
```

Error severity query after the fresh upload timestamp:

```text
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="skillshub" AND timestamp>="2026-05-15T21:06:29Z" AND severity>=ERROR' --project=cfh-vibe-lab --limit=20 --format='value(timestamp,resource.labels.revision_name,severity,textPayload)'
<empty>
```

Result: PASS. The production native image accepted a fresh authenticated upload, completed the 15-engine scan, persisted the scan result, and produced no S175-family native binding error after the upload.

### Shipping Preflight — BLOCKED (2026-05-16)

`$shipping-release` preflight stopped before changelog / roadmap / archive edits because the worktree already contains non-S175 changes:

```text
git status --short
 M docs/grimo/specs/spec-roadmap.md
?? docs/grimo/specs/2026-05-15-S179-publish-author-anonymous-login-hint.md
```

S175 runtime evidence is ready: production Chrome uploaded `s175-fresh-scan-20260515-210629`, Cloud Run returned `POST /api/v1/skills/upload 201`, the 15-engine scan completed, and native binding / ERROR log queries after `2026-05-15T21:06:29Z` were empty.

Release actions still pending:

1. Clear or separately commit the unrelated `spec-roadmap.md` / S179 changes.
2. Run `./scripts/verify-all.sh` in the current tick after the worktree is clean of unrelated changes.
3. Add final release metadata for S175 (`§7 Implementation Results` / final size re-score if required by `$shipping-release`), update changelog, update roadmap, and archive the spec.

Result: BLOCKED by unrelated dirty state. No S175 code change is pending.

### Shipping Preflight Retest — BLOCKED (2026-05-16)

Current tick reran the repository verification command after the S179/S180 docs were
committed:

```bash
./scripts/verify-all.sh
```

Result:

```text
V01=FAIL V02=SKIP V03=PASS V04=PASS V05=PASS V06=PASS V07=PASS V08a=PASS V08b=PASS
Verdict: 1 CRITICAL failure(s); exit=1
```

V01 failure detail from `verify-all.log`:

```text
GlobalExceptionHandlerTest > AC-S181-1/2/3/5: IllegalStateException 409 logs request metadata and preserves response body FAILED
java.lang.AssertionError at GlobalExceptionHandlerTest.java:470

990 tests completed, 1 failed, 7 skipped
Execution failed for task ':test'.
```

Interpretation: S175 production evidence and native image build remain green, but
`$shipping-release` cannot run because the current full gate failed in an unrelated S181
backend test. The same verify-all run continued after V01 and proved:

- V03 `./gradlew jacocoTestCoverageVerification`: PASS.
- V04 `cd frontend && npm test`: PASS.
- V05 `cd frontend && npm run verify`: PASS.
- V06 `cd frontend && npm test -- --coverage`: PASS.
- V07 `cd e2e && npx playwright test --grep @happy-path`: PASS.
- V08a `./gradlew processAot`: PASS.
- V08b `./gradlew bootBuildImage`: PASS; built `docker.io/library/skillshub-verify:local`.

Worktree status also contains unrelated S183 / grants UI changes, so this tick only records
the S175 blocker note and does not stage those files.

Result: BLOCKED by current-tick verify-all V01 failure. Next tick should fix or isolate
`GlobalExceptionHandlerTest.stateConflictLogsRequestMetadataAndPreservesResponse`, rerun
`./scripts/verify-all.sh`, then complete S175 changelog / roadmap / archive via
`$shipping-release`.

### Shipping Preflight Follow-up — BLOCKED (2026-05-16)

S181 no longer blocks S175 shipping. Commit `6bba39f test(S181): stabilize state
conflict log assertion` changed
`GlobalExceptionHandlerTest.stateConflictLogsRequestMetadataAndPreservesResponse` to
read the Logback event directly instead of waiting for the shared captured console buffer.

Verification from the S181 fix tick:

```text
cd backend && ./gradlew test --tests io.github.samzhu.skillshub.shared.api.GlobalExceptionHandlerTest
BUILD SUCCESSFUL in 1m 59s

cd backend && ./gradlew clean test jacocoTestReport
BUILD SUCCESSFUL in 4m 40s
```

Current `$shipping-release` preflight still cannot archive S175/S181 because the worktree
contains unrelated S183/S184/grants changes that must not be staged into this release:

```text
git status --short
 M CONTEXT.md
 M docs/grimo/glossary.md
 M docs/grimo/specs/spec-roadmap.md
 M frontend/src/api/grants.ts
?? docs/grimo/specs/2026-05-16-S183-security-report-issue-code-ui.md
?? docs/grimo/specs/2026-05-16-S184-api-empty-response-contract.md
?? docs/grimo/ui/prototype/Skills Hub Security Report Issue Code UI.html
?? docs/grimo/ui/prototype/Skills Hub Security Risk Lights UI.html
?? frontend/src/api/grants.test.ts
```

Result: BLOCKED by unrelated dirty state. Next tick should split or commit those
S183/S184/grants changes separately, rerun `./scripts/verify-all.sh` in the release tick,
then complete S175/S181 changelog / roadmap / archive via `$shipping-release`.

### Release Result — PASS (2026-05-16)

S175 shipped in `v4.64.0` together with S181 because S181 was the release-gate blocker
discovered while shipping S175.

Final verification:

```text
./scripts/verify-all.sh
PASS — V01=PASS, V02=INFO line coverage 85.9%, V03=PASS, V04/V05/V06/V07=SKIP
because prerequisites were not met in the clean release worktree, V08a=PASS, V08b=PASS,
Verdict: all CRITICAL passed; exit=0.
```

Release summary:

- Production native image accepted a fresh authenticated upload:
  `s175-fresh-scan-20260515-210629`.
- Cloud Run logged `POST /api/v1/skills/upload 201`.
- The 15-engine scan completed for skill `8ee45695-c16e-4586-9869-9fdbe110ca88`.
- Native binding / `ScanNotice` / `ConversionFailedException` / `UnsupportedFeatureError`
  log queries after the upload timestamp returned empty.

### Final Size Re-score

| Dimension | Initial | Actual | Rationale |
|---|---:|---:|---|
| Tech risk | 1 | 2 | Root cause was clear after Cloud Run log inspection, but the fix crossed native-image reflection metadata. |
| Uncertainty | 0 | 1 | Production upload retest was needed to prove `ScanNotice` JSON persistence in the native image. |
| Dependencies | 1 | 1 | Stayed within Spring AOT / GraalVM hint mechanism already used by S173. |
| Scope | 1 | 1 | Production code change stayed limited to scanner native config classes and tests. |
| Testing | 0 | 1 | Required targeted Gradle tests plus processTestAot and production log evidence. |
| Reversibility | 0 | 0 | Extra reflection hints are additive and easy to remove if superseded by Spring AOT inference. |
| **Total** | **3 / XS** | **6 / XS** | Still XS; release was delayed by an unrelated S181 test stability blocker, not by larger S175 scope. |
