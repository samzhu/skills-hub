# Changelog

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
