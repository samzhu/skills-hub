# Changelog

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
