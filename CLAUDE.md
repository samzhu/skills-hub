# Skills Hub

企業內部 AI Agent 技能市集與 Registry 平台。基於 agentskills.io SKILL.md 開放標準。

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
