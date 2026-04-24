# AI Development Workflow Reference

> For AI agents executing the development pipeline.
> Source of truth: the 7 SKILL.md files in `.claude/skills/`.
> This document is a read-only summary — skills define behavior.

---

## Pipeline Overview

Seven skills form a decomposition chain from product vision to shipped code:

```
/defining-product        Product-level: define what to build
       |
/planning-project        Spec-level: architecture, spec breakdown, estimation, QA strategy
       |
/planning-spec S00N      Solution-level: analyze requirements, design approach, define interfaces
       |
/planning-tasks S00N     Task-level: BDD task chain + loop controller (hub of the pipeline)
       |  ^
       v  |
/implementing-task S00N  Code-level: TDD per task (Red -> Green -> Refactor)
       |
  [subagent QA]          Independent verification via /verifying-quality (spawned by planning-tasks)
       |
/shipping-release        Release: commit, doc sync, changelog, archive, version tag
       |
/planning-tasks next     Loop back to find the next spec
```

## Skill Contracts

Each skill defines explicit Input/Output contracts. Artifacts always
live in `docs/grimo/` regardless of where skills are installed.

| Skill | Role | Input | Output |
|---|---|---|---|
| `/defining-product` | Product Manager | User requirements | `docs/grimo/PRD.md` |
| `/planning-project` | Tech Lead | PRD.md | architecture.md, development-standards.md, qa-strategy.md, specs/spec-roadmap.md |
| `/planning-spec` | SA/SD Analyst | spec-roadmap.md, architecture.md | Spec file sections 1-5 (design) |
| `/planning-tasks` | Lead Engineer | Spec file sections 1-5 | Task files (temporary) + spec file sections 6-7 |
| `/implementing-task` | Senior Developer | One task file | Production code + test + updated task file |
| `/verifying-quality` | QA Engineer | Spec file (all sections) + code + tests | QA verdict appended to spec section 7 |
| `/shipping-release` | Release Engineer | Spec file (section 7 PASS) | Commit, archived spec, CHANGELOG, doc sync |

## Artifact Model: Single Living Spec File

Each spec is ONE file with incrementally added sections:

```
docs/grimo/specs/YYYY-MM-DD-S<NNN>-<slug>.md

  Section 1: Goal                  <- /planning-spec creates
  Section 2: Approach + Decisions  <- /planning-spec creates
  Section 3: SBE Acceptance Criteria <- /planning-spec creates
  Section 4: Interface/API Design  <- /planning-spec creates
  Section 5: File Plan             <- /planning-spec creates
  ---
  Section 6: Task Plan + POC       <- /planning-tasks adds
  Section 7: Implementation Results <- /planning-tasks adds (Phase 4)
             + QA Review           <- /verifying-quality appends
```

After shipping: file moves to `docs/grimo/specs/archive/`.
Task files (`docs/grimo/tasks/`) are temporary — deleted after
consolidation into section 7.

## Spec Status Flow

```
🔲 --> ⏳ Design --> ⏳ Plan --> ⏳ Dev --> ✅ Done
```

| Status | Set by | Meaning |
|---|---|---|
| `🔲` | — | Not started |
| `⏳ Design` | `/planning-spec` | Sections 1-5 complete |
| `⏳ Plan` | `/planning-tasks` Phase 2 | Task files created, section 6 added |
| `⏳ Dev` | `/planning-tasks` Phase 3 | First task started |
| `✅ Done` | `/planning-tasks` Phase 4 | All tasks pass, section 7 written, QA pass |

Note: There is no separate `⏳ QA` status. QA runs as a subagent
inside Phase 4 and the result is embedded in the spec file.

## The Task Loop (planning-tasks is the Hub)

`/planning-tasks` is the central coordinator. It manages 3 phases:

### Phase 0: Pre-Flight Validation
- Read existing research notes — don't re-research known findings
- Re-read the PRD — verify spec aligns with product positioning
- **Existing stack audit** — ask "does the current stack already solve
  this use case?" Test with a lightweight POC if needed. Inspecting
  source code alone is insufficient for behavior questions.
- Cross-validate spec design against product requirements and POC findings
- Question the approach: is the spec adding complexity for a problem
  the existing framework already handles?
- If contradiction found → escalate to `/planning-spec`

### Phase 1: POC Validation (conditional)
- **Runs BEFORE task files** — validates the approach, not just the SDK
- **Correct POC sequence:** (1) test existing stack capabilities first,
  (2) identify the gap, (3) only then test proposed new dependency
- POC must test the design hypothesis, not just API mechanics
- A POC that confirms "new library works" without first confirming
  "existing stack can't do this" is incomplete
- Document findings in spec section 6
- POC reveals simpler approach → escalate to `/planning-spec`
- POC fails → stop and report

### Phase 2: Create Task Files
- Break spec into BDD task files in `docs/grimo/tasks/`
- Task files must reflect POC findings (validated patterns)
- Add section 6 to spec file
- Status -> `⏳ Plan`

### Phase 3: Task Loop
- Find next pending task -> invoke `/implementing-task`
- `/implementing-task` completes one task -> returns here
- Re-check: more pending? -> loop. All PASS? -> Phase 4
- Status -> `⏳ Dev`

### Phase 4: Consolidation + Independent QA
1. **Deterministic checks** (inline): run test suite, verify compilation
2. **Consolidate** into spec section 7: results, findings, usage patterns
3. **Clean up**: delete task files and POC directory
4. **Spawn verification subagent**: use Agent tool to run
   `/verifying-quality` in a fresh context

**Why subagent?** Same-session self-review has blind spots. A fresh
agent context re-reads everything independently and catches:
- Javadoc that drifted from implementation
- Missing edge-case test coverage
- AC-to-test mapping gaps
- Development-standards violations

After subagent returns PASS -> tell user to run `/shipping-release`.
After subagent REJECT -> fix findings, re-verify.

## Verification Model

Two-stage verification, both executed during Phase 4:

| Stage | Method | What it checks |
|---|---|---|
| **Deterministic** (inline) | `verify-all.sh` (Verification Command Registry) | ALL registered commands: unit, integration, E2E — deterministic, no LLM inference |
| **Independent QA** (subagent) | `/verifying-quality` in fresh context | Spec compliance, AC-to-test mapping, code quality, Javadoc accuracy, design-section sync |

**Verification Command Registry.** `/verifying-quality` owns and
maintains the registry table (in the QA strategy doc) and its
executable form (`verify-all.sh`). On every QA run it reconciles
the registry against the build file and QA targets — adding missing
commands, flagging stale entries, and proposing specs when tooling
is absent (e.g., coverage target exists but no coverage plugin).

No LLM inference needed for the deterministic checks — the script
handles command ordering, environment detection, and skip logic.

This applies to **all spec sizes** (XS through L+). The subagent
approach replaces the old model where only L+ specs got QA review.

## Semi-Auto Mode

`/planning-tasks auto` adjusts automation level by spec size:

| Size | Behavior |
|---|---|
| **XS/S** | Full auto: plan -> task loop -> consolidate -> subagent QA -> report |
| **M** | Stop after design for user confirmation, then auto through rest |
| **L+** | Stop at every phase boundary |

Stop conditions: task FAIL, QA REJECT (CRITICAL), user interrupts.

## Methodology Chain: SBE -> BDD -> TDD

| Method | Question | Where |
|---|---|---|
| **SBE** (Specification by Example) | What exactly should this do? | `/planning-spec` section 3 |
| **BDD** (Behavior-Driven Development) | How does the system behave? | `/planning-tasks` task files |
| **TDD** (Test-Driven Development) | Red -> Green -> Refactor | `/implementing-task` |

Transformation: SBE example -> becomes BDD Given/When/Then -> becomes @Test.

## Escalation (one step back)

Each skill only escalates to the immediately previous skill:

```
/shipping-release   -> /verifying-quality  (pre-flight failed)
/verifying-quality  -> /planning-tasks     (QA rejected)
/implementing-task  -> /planning-tasks     (task not feasible)
/planning-tasks     -> /planning-spec      (too large or vague)
/planning-spec      -> /planning-project   (requirements unclear)
```

## Document Sync (Living Documents)

Three trigger points update project documents:

| Trigger | Skill | Updates |
|---|---|---|
| Architecture decision | `/planning-project` | architecture.md, PRD.md, development-standards.md |
| Product decision | `/planning-spec` | PRD.md, spec-roadmap.md |
| Feature shipped | `/shipping-release` | PRD.md, architecture.md, development-standards.md, CHANGELOG.md |

Principle: only update where reality diverged from documentation.

## Milestone Management

Specs are grouped into milestones in `spec-roadmap.md`.
When all specs in a milestone are `✅`:

1. Collapse the milestone section to a one-line summary
2. Create a version tag (if defined for the milestone)
3. Spec files are already archived in `specs/archive/`

## Quick Reference

| Intent | Command |
|---|---|
| Define product requirements | `/defining-product [name]` |
| Technical planning + spec breakdown | `/planning-project` |
| Design a spec's solution | `/planning-spec S002` |
| Break spec into tasks + start loop | `/planning-tasks S002` |
| Find next actionable spec | `/planning-tasks next` |
| Run semi-auto loop | `/planning-tasks auto` |
| Implement one task (called by loop) | `/implementing-task S002` |
| Independent QA (called by loop as subagent) | `/verifying-quality S002` |
| Ship completed spec | `/shipping-release` |
