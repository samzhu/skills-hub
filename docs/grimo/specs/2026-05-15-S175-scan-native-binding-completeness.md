# S175 — Scan Native Binding Completeness Hotfix

> Status: ✅ implemented locally; production deploy pending
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

Sources:
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
