# Skills Hub

> 企業內部的 AI Agent 技能登錄中心 — 用同一份 SKILL.md bundle 在 Claude Code、Cursor、Gemini CLI 等任一相容 agent 之間共享，每次上傳都自動風險評分。

基於開放標準 [agentskills.io](https://agentskills.io) v1.2 — 不綁特定 agent 工具。

## 為什麼存在

組織內愈來愈多人寫 SKILL.md 給 AI agent 使用，但缺乏：
- **一處集中** 看誰寫了什麼 / 哪個版本最新
- **自動風險評分** 防止有人不小心 publish 含 `curl | bash` 等危險指令的 skill
- **語意搜尋** 讓 user 能用自然語言找到對的 skill 而不是猜檔名
- **trust signal** — 對齊 [OWASP LLM Top 10](https://owasp.org/www-project-top-10-for-large-language-model-applications/) 的 governance

Skills Hub 就是這層平台。

## 技術架構

| Layer | Choice |
|-------|--------|
| Backend | Spring Boot 4.0.6 + Java 25 + Gradle 9.4.1 + Spring Modulith 2.0.6 |
| DB | PostgreSQL 16 + pgvector |
| Frontend | React 19 + Vite 8 + TypeScript 6 + Tailwind 4 + shadcn/ui |
| AI | Spring AI 2.0.0-M4 + Gemini (Vertex AI) for semantic search embeddings |
| Storage | Google Cloud Storage（skill zip packages） |
| Deploy | Docker container → GCP Cloud Run |
| API Docs | OpenAPI 3.1（SpringDoc） — `/v3/api-docs` 與 `/swagger-ui.html`（local profile） |

詳見 [`docs/grimo/architecture.md`](./docs/grimo/architecture.md)。

## Quick start

```bash
# Backend (Spring Boot 4 + Java 25)
cd backend && ./gradlew bootRun

# Frontend (React 19 + Vite 8)
cd frontend && npm run dev
# Open http://localhost:5173

# Tests
cd backend && ./gradlew test
cd frontend && npx vitest run

# OCI image
cd backend && ./gradlew bootBuildImage
```

完整 build / verify pipeline 見 [`CONTRIBUTING.md`](./CONTRIBUTING.md)。

## 專案結構

```
skills-hub/
├── AGENTS.md                          ← Codex repo instructions
├── CLAUDE.md                          ← Claude Code repo instructions
├── .codex/loop.md                     ← Codex automation tick state machine
├── CONTRIBUTING.md                    ← 入手指南
├── backend/                           ← Spring Boot 後端
│   ├── src/main/java/io/github/samzhu/skillshub/
│   │   ├── shared/                    ← DomainEvent, EventStore
│   │   ├── skill/                     ← Skill aggregate (command + query)
│   │   ├── security/                  ← Risk assessment / Flag
│   │   ├── search/                    ← Keyword + semantic search
│   │   ├── analytics/                 ← Stats projections
│   │   ├── community/                 ← Collections / Requests stubs
│   │   ├── notification/              ← Notification stubs
│   │   ├── audit/                     ← Audit projection
│   │   └── storage/                   ← GCS integration
│   └── src/main/resources/
└── frontend/                          ← React 19 SPA
    ├── src/
    │   ├── components/                ← UI primitives
    │   ├── pages/                     ← Route-level pages
    │   ├── hooks/                     ← React Query hooks
    │   ├── api/                       ← Backend API clients
    │   └── lib/                       ← Utilities (api-error / mini-markdown)
    └── package.json
```

## 文件導讀

| 想做什麼 | 看哪份 |
|----------|--------|
| 建立 / 修 / ship 新功能 | [`WORKFLOW.md`](./WORKFLOW.md) + [`AGENTS.md`](./AGENTS.md) / [`CLAUDE.md`](./CLAUDE.md) + [`CONTRIBUTING.md`](./CONTRIBUTING.md) |
| 了解產品定位 / Critical Path | [`docs/grimo/PRD.md`](./docs/grimo/PRD.md) |
| 框架 / 模組架構 / data flow | [`docs/grimo/architecture.md`](./docs/grimo/architecture.md) |
| 程式約定 / package layout / testing | [`docs/grimo/development-standards.md`](./docs/grimo/development-standards.md) |
| 看現在 backlog / 哪些 spec 在跑 | [`docs/grimo/specs/spec-roadmap.md`](./docs/grimo/specs/spec-roadmap.md) |
| 看 Codex loop automation | [`docs/grimo/codex-loop-automation.md`](./docs/grimo/codex-loop-automation.md) |
| 看 Claude cron-bounded agent 工作流 | [`docs/grimo/adr/ADR-004-cron-bounded-agent-workflow.md`](./docs/grimo/adr/ADR-004-cron-bounded-agent-workflow.md) |
| Risk scanner 涵蓋什麼威脅 | `/docs/risk-scanner-scope`（dev server 起來後可看）+ [`docs/grimo/specs/2026-05-02-S099-trust-maturity-meta.md`](./docs/grimo/specs/2026-05-02-S099-trust-maturity-meta.md) |

## 目前狀態

- **MVP shipped**: v1.0.0（14 specs / 147 pts，2026-04-28）
- **Phase 4 (UI v2 + polish)**: v2.85.0（S097 BeamFrame swap）
- **Phase 5 (trust + integrity)**: v3.4.0 — S099 META 5/8 + S100 META ✅ + S101 META awaiting confirm

跨 phase 細節見 [`docs/grimo/CHANGELOG.md`](./docs/grimo/CHANGELOG.md) + [`docs/grimo/progress-log.md`](./docs/grimo/progress-log.md)。

## License

依專案根目錄 LICENSE（如未來新增）。
