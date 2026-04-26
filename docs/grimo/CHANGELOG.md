# Changelog

## [v0.9.0] - 2026-04-26

### Added
- S010: Multi-engine security scanner pipeline — replaces S005 single-regex scanner with 5 independently-toggleable engines + SARIF 2.1.0 output
  - `SecurityAnalyzer` SPI + `Phase` (STATIC/LLM/META) execution model in `security.scan` package
  - 5 engines: `PatternScanner` (8 regex rules, OWASP AST06), `SecretScanner` (10 patterns inspired by gitleaks MIT + masking), `MetadataValidator` (Jakarta Bean Validation against agentskills.io frontmatter), `LlmJudge` (Spring AI ChatClient + Gemini 2.5 Flash + structured output via `BeanOutputConverter`), `MetaAnalyzer` (cross-engine deterministic rules: META_EXFIL_PATTERN, META_MULTI_ENGINE_SIGNAL, META_OPACITY)
  - `ScanOrchestrator` (`@EventListener` on `SkillVersionPublishedEvent`) — Phase 1 STATIC parallel via `Executors.newVirtualThreadPerTaskExecutor()`, Phase 2 LLM sequential, Phase 3 META sequential; max-severity aggregation; per-engine try/catch isolation
  - `SarifReporter` — hand-rolled Jackson POJO renderer (no third-party SARIF lib due to dead-library landscape); HIGH/MEDIUM/LOW → error/warning/note + `security-severity` 浮點字串 for future GHAS integration; one `runs[]` per enabled engine
  - `ScannerAiConfig` — Spring AI Manual Configuration for `GoogleGenAiChatModel` + `ChatClient`; `AllNestedConditions` dual gate (`engines.llm.enabled=true` AND `genai.api-key` present)
  - `SkillVersionReadModel.riskAssessment Map<String,Object>` field stores level + findings + notices + SARIF + scannedAt
  - `SkillshubProperties.Scanner` nested `@ConfigurationProperties` record with per-engine toggles
- New dependencies: `spring-ai-google-genai`, `spring-ai-client-chat` (chat artifact NOT transitive from genai)

### Fixed
- Cross-module `@EventListener` race condition: `SkillProjection` and `ScanOrchestrator` both listen to `SkillVersionPublishedEvent` with default LOWEST_PRECEDENCE → undefined order. Explicit `@Order(HIGHEST_PRECEDENCE)` on SkillProjection + `@Order(LOWEST_PRECEDENCE)` on ScanOrchestrator. (Race existed since S005 but was masked because S005 didn't depend on `skill_versions` document being pre-written.)

### Removed
- S005 legacy classes: `RiskAssessmentListener`, `RiskScanner`, `RiskFinding`, old `ScanResult` (replaced by `ScanOrchestrator` + `PatternScanner` + `SecurityFinding` + new `ScanResult` with SARIF map)

## [v0.8.0] - 2026-04-25

### Changed
- S009: Config optimisation — `@ConfigurationProperties` best practices aligned across all YAML and Java config
  - Removed all `${...}` placeholder indirection from `skillshub.*` and `spring.data.mongodb.uri`
  - Eliminated Spring AI auto-config / Manual Config conflict: `spring.ai.model.embedding.text: none` + `GoogleGenAiEmbeddingConnectionAutoConfiguration` excluded in base
  - Centralised `gemini-embedding-2` model name and `768` dimensions into `SkillshubProperties.GenAI` via `@DefaultValue`
  - `springdoc` disabled by default in `application.yaml`; enabled only in `config/application-dev.yaml`
  - `config/application-secrets.properties.example` updated to dot-notation (`skillshub.genai.api-key=...`)

## [v0.7.0] - 2026-04-25

All 9 specs complete — full MVP shipped.

### Added
- S007: Semantic search — Spring AI 2.0.0-M4 + Gemini embedding (`gemini-embedding-2`, 768 dims) + dual VectorStore (`SimpleVectorStore` dev / `FirestoreVectorStore` prod)
  - `GET /api/v1/search/semantic?q=...` endpoint with cosine similarity ranking (topK=10, threshold=0.3)
  - `SearchProjection` (@EventListener) seeds VectorStore on `SkillCreatedEvent` + `SkillVersionPublishedEvent`
  - `FirestoreVectorStore` extends `AbstractObservationVectorStore` via Firestore native SDK (`findNearest()`)
  - `SearchConfig` @ConditionalOnProperty switches backend; `NoOpEmbeddingModel` fallback for local dev
  - Frontend: `useSemanticSearch` TanStack Query hook; `HomePage` dual-mode (semantic/keyword); `SkillCard` score badge (`XX% 相符`)

### Fixed
- `package-info.java` `allowedDependencies` now references `"skill :: domain"` named interface (required for `SkillCreatedEvent` access in Spring Modulith)
- `application.yaml` (test + local): suppress `GoogleGenAiTextEmbeddingAutoConfiguration` conflict via `spring.ai.model.embedding.text: none`

## [v0.6.0] - 2026-04-25

MVP release covering Milestones 0-4 + 6 (8/9 specs shipped).

### Added
- S000: Project init — backend/frontend scaffold, ES+CQRS infra, Spring Modulith modules
- S001: Skill domain model + Command/Query API with Event Sourcing
- S002: Skill browse & search UI — keyword search, category filter, detail page (React 19 + shadcn/ui)
- S003: Skill upload + versioning — GCS storage, multipart upload, version management
- S004: Skill publish UI — drag-drop upload form, version history, add version
- S005: Risk assessment engine — pattern scanner, event-driven risk evaluation, community flagging
- S006: Skill download API + UI — download endpoints, SkillDownloaded events, install guide
- S008: Analytics dashboard — overview stats API, top skills, metric cards

### Not Yet Implemented
- S007: Semantic search (Spring AI + Gemini + Firestore Vector) — in design phase
