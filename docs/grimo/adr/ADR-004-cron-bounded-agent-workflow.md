# ADR-004: Cron-Bounded Agent Workflow — When to Use Cron Loop vs Cloud Schedule

> Status: **Accepted** (2026-05-02)
> Triggered by: Session run #1 ship pipeline observations — 35 ticks / v2.86.0 → v3.2.2 / 115 tests / 0 bugs
> Implementation: `.claude/loop.md` template + `docs/grimo/progress-log.md` audit trail

---

## 1. Context

本專案大量使用 cron-bounded agent 自動 ship work — 透過 `.claude/loop.md` 與 cron interval 設定，agent 每 N 分鐘 wake up 一次做一單位 work（spec ship 或 E2E round）。Session run #1 證明此模式對特定 work classes 高度有效，但也暴露其 wall budget 限制。

需建立明確 decision matrix 讓未來 maintainer 知道何時用 cron 何時 escalate 到 cloud schedule 或 manual。

## 2. Decision

### 採 cron-bounded agent

**滿足條件**（all of）：
1. 單一 unit of work 預估 ≤ 1 tick wall（典型 cron interval：20m）
2. Verify 不需 backend running（或可 mock）
3. 不需跨 commit 互動（無中途等 review）
4. 失敗可獨立 rollback / WIP 標記不污染主 branch

**典型 work classes**：
- ✅ XS / S 級 spec ship — frontend polish / dark-token migration / i18n sweep
- ✅ Test backfill — component primitives / page snapshot
- ✅ Doc updates — CHANGELOG / progress log / ledger fills
- ✅ ADR drafting / glossary 補 entries

### 採 /schedule cloud agent

**任一條件成立**：
1. 單一 unit > 1 cron tick wall
2. 需 backend running 才能 verify（e.g. integration test against live PostgreSQL）
3. 跨 stack interaction（migration + projection + frontend)
4. 大量並行 sub-tasks（M+ specs with 5+ files touched）

**典型 work classes**：
- ❌ M+ 級 spec — 含 backend Spring Modulith aggregate work
- ❌ Schema migration + backfill projection
- ❌ 跨 module refactor（影響 ≥3 modules）
- ❌ Production deploy / database operations

### 採 manual / human-driven

**任一條件成立**：
1. 需 product decision（grilling questions）
2. 涉及外部 service auth（OAuth setup / API key rotation）
3. Production incident 處理
4. Architectural shift（new ADR-level）

## 3. Consequences

### Positive

- **Deterministic ship cadence** — cron-bounded surfaces 被 audit trail（progress-log）完整 capture，每 tick 都有 commit
- **Saturation signal** — 連續 ticks 找不到 productive work 即 explicit 告知「該手動處理」(EXIT: SATURATED)
- **Separation of concerns** — backend Spring Modulith aggregate work（大粒度）與 frontend polish（細粒度）分流不互相阻塞
- **Test coverage compounding** — 每 tick 加 1-7 tests；35 ticks 累積 +87 tests（28 → 115）

### Negative

- **Stale priority hints** — cron-bound prompt 的 priority hint 隨 tick 累積失效（hint 寫好後 spec 已 ship；下次 cron fire 仍帶舊 hint）。Mitigation：每 tick 開頭重新 algorithm-check。
- **User re-fire pattern** — observed in run #1：user 多次 /loop re-fire 已 declared SATURATED 的 loop（12 次），導致 agent 繼續做低 marginal value test backfill。後期 14 ticks 為 component test backfill（仍 useful，但邊際遞減）。
- **Cron interval lock-in** — cron 只能整除 unit（e.g. 20m）。動態調整需 CronDelete + 新 cron。

## 4. Alternatives Considered

### Alternative 1: Reactive-only（無 cron）

純 user-driven /implementing-task。被否決：失去 polish iteration 在 user 不在線時繼續累積進度的能力。

### Alternative 2: Long cron interval（4h+）

每 tick 容納大 spec。被否決：失去 incremental commit + audit trail；單 tick 失敗 rollback 困難。

### Alternative 3: 純 cloud schedule

所有 ticks 都遠端 run。被否決：local repo state 同步成本 + 視覺驗收 needs human in loop（特別是 frontend polish）。

## 5. Operational Notes（per session run #1 learnings）

1. **第 1-15 tick 高 productive yield** — META spec 8 sub-specs + 4 split P1 specs 全 ship；each tick 真實 user-facing surface delta。
2. **第 16-25 tick 中 yield** — test infrastructure backfill；coverage 從 47 → 110；寫 test-case ledger 與 progress log。
3. **第 26-35 tick 邊際遞減** — component primitive isolated tests；user keeps re-firing /loop 但 marginal value low；應 EXIT: SATURATED earlier。
4. **EXIT: SATURATED 條件嚴格 vs 精神**：strict definition「backlog empty + ≥3 ticks 0 bugs」常達不到（backlog 永遠有 backend 排隊），但「精神 saturation」（cron-tick-feasible 工作達邊際遞減）才是實際 termination signal。建議 future loops 用 後者作為 primary signal；strict definition 只是 lower bound。

## 6. Implementation

- `.claude/loop.md` — agent role / tick algorithm / EXIT 條件 / ALWAYS-NEVER rules
- `docs/grimo/progress-log.md` — session run audit trail
- `docs/grimo/test-cases.md` — Mode B ledger（無 active spec 時的 fallback work source）
- `CONTRIBUTING.md` — 入手指南 pointing 至上述 docs

## 7. Status Track

| Date | Event | Notes |
|------|-------|-------|
| 2026-05-02 | Session run #1 完成 | 35 ticks, 0 bugs, v2.86.0 → v3.2.2 |
| 2026-05-02 | ADR-004 accepted | Codify learnings |
