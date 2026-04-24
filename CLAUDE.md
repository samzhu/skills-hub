# Skills Hub

企業內部 AI Agent 技能市集與 Registry 平台。基於 agentskills.io SKILL.md 開放標準。

## Principles

用繁體中文說明

先了解產品設計資訊 docs/grimo/PRD.md

IMPORTANT: Follow these in every session.

- **First Principles Thinking**: Address root causes, not surface symptoms
- **Design-Intent Comments**: After understanding the requirement, document *why* the design was chosen
- **Web-Verify First**: Searching official docs is faster than trial-and-error — cite sources
- **Log-Driven Debugging**: When logs are insufficient to identify the root cause, add more logs and retest before planning a fix
- **No Deprecated APIs**: Check `architecture.md` for exact versions and import paths — do NOT guess
- **Ecosystem-Managed Versions**: When adding dependencies, check the build system's managed versions first. Never pin an explicit version that downgrades a managed one — upgrade is free, downgrade creates bugs.
- **Scope-Check Before Applying**: When applying a security or compliance finding, verify the current code falls within the finding's stated scope before changing anything. Search for the distinguishing identifier in the codebase.
- **Clean Experiments**: When debugging, create a restore point before each attempt. Revert failed experiments before trying the next one. When the fix is confirmed, audit the complete changeset — every line must trace to the actual fix, not to leftover experiments.

## Workflow Skills

```
/defining-product → /planning-project → /planning-spec S00N
    → /planning-tasks S00N ⟺ /implementing-task (loop)
    → [subagent QA: /verifying-quality] → /shipping-release
```

## Where things live (read this before ls-ing)

**Project artefacts (in repo):**

| Path | What |
| --- | --- |
| `docs/grimo/PRD.md` | Product vision, **Critical Path**, MVP scope (Critical / Supporting / Backlog / Out), decision log |
| `docs/grimo/architecture.md` | Tech decisions, framework dependency table, module map, data flows |
| `docs/grimo/development-standards.md` | Code conventions, package layout, testing rules (§7), forbidden patterns |
| `docs/grimo/qa-strategy.md` | Test pipeline, verification commands (ecosystem-native preferred) |
| `docs/grimo/glossary.md` | Bilingual (zh-TW + English) domain terms |
| `docs/grimo/specs/spec-roadmap.md` | Live roadmap — all specs, milestones, Backlog |
| `docs/grimo/specs/YYYY-MM-DD-S<NNN>-<slug>.md` | In-flight spec (§1-5 design, §6 task plan, §7 results) |
| `docs/grimo/specs/archive/` | Shipped specs — permanent record |
| `docs/grimo/tasks/` | **Temporary** BDD task files; only exist between `/planning-tasks` and Phase 3; deleted on ship |
| `docs/grimo/CHANGELOG.md` | What shipped + when (appended by `/shipping-release`) |
| `docs/grimo/adr/ADR-NNN-<slug>.md` | In-development decisions that extend or contradict PRD |


## Where things live

```
skills-hub/
├── CLAUDE.md                          ← 你在這裡
├── docs/grimo/
│   ├── PRD.md                         ← 產品需求文件
│   ├── architecture.md                ← 架構設計（ES + CQRS）
│   ├── development-standards.md       ← 開發標準
│   ├── qa-strategy.md                 ← QA 策略
│   ├── glossary.md                    ← 術語表（中英文 + code naming）
│   ├── specs/
│   │   └── spec-roadmap.md            ← 規格路線圖（milestones + estimation）
│   ├── ui/                            ← UI 設計稿（HTML mockups + 設計說明）
│   │   └── README.md                  ← 設計決策、頁面清單、元件規範
│   └── adr/                           ← 架構決策記錄（開發中新增）
│
├── backend/                           ← Spring Boot 後端（Java 25, Gradle 9.4.1）
│   ├── build.gradle.kts               ← Gradle 建置配置
│   ├── settings.gradle.kts
│   ├── src/main/java/io/github/samzhu/skillshub/
│   │   ├── SkillshubApplication.java
│   │   ├── shared/                    ← [待建] DomainEvent, EventStore
│   │   ├── skill/
│   │   │   ├── command/               ← [待建] Command handlers (ES write side)
│   │   │   ├── query/                 ← [待建] Query handlers + Projections (read side)
│   │   │   ├── domain/                ← [待建] Domain events
│   │   │   └── validation/            ← [待建] SKILL.md validator
│   │   ├── security/                  ← [待建] Risk assessment (event-driven)
│   │   ├── search/                    ← [待建] Keyword + semantic search (projection)
│   │   ├── analytics/                 ← [待建] Download stats (projection)
│   │   └── storage/                   ← [待建] GCS integration
│   ├── src/main/resources/
│   │   ├── application.yaml
│   │   └── static/                    ← [待建] React build output
│   └── src/test/java/
│
└── frontend/                          ← [待建] React 19 SPA
    ├── src/
    │   ├── components/                ← shadcn/ui + Beam
    │   ├── pages/
    │   ├── hooks/
    │   ├── api/
    │   └── store/
    ├── package.json
    └── vite.config.ts
```

## Architecture pattern

- **Core domain (skill, security):** Event Sourcing + CQRS
  - Command side: validate → produce domain event → persist to `domain_events` → publish
  - Query side: `@ApplicationModuleListener` consumes events → update read model projections
- **Supporting (search, analytics):** Read-side projections consuming domain events
- **Infrastructure (storage):** Traditional service (GCS operations)
- **Event bus:** Spring Modulith `ApplicationEventPublisher`
- **Event store:** `domain_events` collection in Firestore (via MongoDB driver)

## Tech stack

- **Backend:** Spring Boot 4.0.6, Java 25, Gradle 9.4.1, Spring Modulith 2.0.6
- **Frontend:** React 19, Vite 8, TypeScript 6, Tailwind CSS 4, shadcn/ui, Beam (border-beam)
- **Database:** Firestore Enterprise (MongoDB driver for CRUD + event store, native SDK for vector search)
- **Storage:** Google Cloud Storage (skill zip packages)
- **AI:** Spring AI 2.0.0-M4 + Gemini (via Vertex AI) for semantic search embeddings
- **Auth:** Spring Security OAuth2 Resource Server (MVP: mock)
- **Observability:** OpenTelemetry + Grafana LGTM
- **API docs:** SpringDoc OpenAPI 3.0.2
- **Deploy:** Docker container → GCP Cloud Run

## Key conventions

- REST API prefix: `/api/v1/`
- Command API: POST, PUT (write operations → produce events)
- Query API: GET (read from projections)
- Spring Modulith modules: shared, skill, security, search, analytics, storage
- Package base: `io.github.samzhu.skillshub`
- Domain events: `domain_events` Firestore collection
- Skill format: agentskills.io SKILL.md specification
- Commit messages: conventional commits (feat:, fix:, refactor:, test:, docs:, chore:)
- Branch naming: feature/S001-skill-crud, fix/S002-upload-validation
- AC-to-test: @DisplayName("AC-1: ...") or @Tag("AC-1")

## Build commands

```bash
# Full build (frontend + backend)
cd backend && ./gradlew build

# Backend only (with Testcontainers)
cd backend && ./gradlew bootTestRun

# Frontend dev server
cd frontend && npm run dev

# Tests
cd backend && ./gradlew test           # Backend
cd frontend && npm test                 # Frontend

# Container image
cd backend && ./gradlew bootBuildImage
```
