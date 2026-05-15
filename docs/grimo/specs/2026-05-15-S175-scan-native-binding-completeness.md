# S175 — Scan Native Binding Completeness Hotfix

> Status: ⏳ production deployed; fresh upload retest pending
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
