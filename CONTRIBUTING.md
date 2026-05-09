# Contributing to Skills Hub

> 給協作者 / 維運者的入手指南。完整 product spec + tech stack 細節在 [`CLAUDE.md`](./CLAUDE.md)。

## 流程簡介

Skills Hub 採 **spec-first ship pipeline**：每個 user-facing 變動先有一份 spec doc（goal / approach / ACs / file plan），實作完搬到 archive。所有設計討論在 PR 前已 capture 在 spec 中。

完整流程：

```
/defining-product → /planning-project → /planning-spec S00N
    → /planning-tasks S00N ⟺ /implementing-task (loop)
    → /verifying-quality → /shipping-release
```

詳見 [`.claude/skills/`](./.claude/skills/) 下的 skill 文件。

## 入手前必讀

1. **[`CLAUDE.md`](./CLAUDE.md)** — Principles + tech stack + build commands。
2. **[`docs/grimo/PRD.md`](./docs/grimo/PRD.md)** — 產品定位 / Critical Path / MVP scope。
3. **[`docs/grimo/architecture.md`](./docs/grimo/architecture.md)** — 框架版本鎖定 / module map / data flow。
4. **[`docs/grimo/development-standards.md`](./docs/grimo/development-standards.md)** — 程式約定 / package layout / testing rules。
5. **[`docs/grimo/qa-strategy.md`](./docs/grimo/qa-strategy.md)** — 驗證 pipeline。

## Build / Verify

```bash
# Backend (Spring Boot 4 + Java 25 + Gradle)
cd backend && ./gradlew bootRun                    # dev
cd backend && ./gradlew test                       # tests
cd backend && ./gradlew bootBuildImage             # OCI image

# Frontend (React 19 + Vite 8 + TS 6)
cd frontend && npm run dev                         # dev server
cd frontend && npx vitest run                      # tests
cd frontend && npx tsc --noEmit                    # typecheck
```

## Cron-bounded 自動化開發

本專案大量使用「cron-bounded agent」自動連續 ship — 詳見 [`docs/grimo/claude-code-loop-tutorial.md`](./docs/grimo/claude-code-loop-tutorial.md) 與 [`docs/grimo/loop-testing-methodology.md`](./docs/grimo/loop-testing-methodology.md)。

**何時用 cron loop**：
- ✅ Polish iteration / dark theme migration / i18n sweep / test backfill — 每 tick 在 wall budget 內可獨立 ship
- ✅ XS / S 級 spec — 整個流程（plan → implement → verify → document → persist → commit）一 tick 完成

**何時轉 /schedule cloud agent**：
- ❌ M+ 級 spec 含 backend Spring Modulith aggregate work（需 >1 cron tick budget）
- ❌ 跨 stack 互動需 backend running 才能 verify
- ❌ Schema migration / 重 data layer rework

## 寫 commit 訊息

採用 [Conventional Commits](https://www.conventionalcommits.org/)。Subject ≤72 字元，body 解釋 **why** 而非 **what**：

```
feat(frontend): ship S0XX — short title (vX.Y.Z)

Why this change exists. Trim rationale if applicable. Verify metrics
(tests passed, build size). META progress N/total if applicable.

Co-Authored-By: ...
```

## 規範語言

- UI（前端使用者可見字串）：**繁體中文**（per CLAUDE.md「UI 語言: 繁體中文」原則）
- Backend API 錯誤訊息：英文（前端負責翻譯）
- Spec docs / commit body / inline 註解：以中文為主，混雜技術名詞（class name / spec ID / framework name）保留英文

## 找待辦

- **Active backlog**：[`docs/grimo/specs/spec-roadmap.md`](./docs/grimo/specs/spec-roadmap.md) — 看 📋 / 📐 / 🚧 / ⏸ icons
- **E2E test gaps**：[`docs/grimo/test-cases.md`](./docs/grimo/test-cases.md) — 7 rounds × 33 ACs
- **Bug ledger**：同 test-cases.md（A→Z 編號 monotonic）
- **Tick history**：[`docs/grimo/progress-log.md`](./docs/grimo/progress-log.md)

## License

依專案根目錄 LICENSE（如未來新增）。
