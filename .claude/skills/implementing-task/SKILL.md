---
name: implementing-task
description: >
  Implements a single BDD task through TDD (Red then Green then Refactor).
  Reads one task file, writes failing test, implements minimal code,
  updates task file with result. Use when planning-tasks routes here,
  or the user says "implement S002."
  Do NOT invoke directly — always called via /planning-tasks task loop.
argument-hint: "[spec-id]"
allowed-tools:
  - Read
  - Glob
  - Grep
  - Bash
  - Write
  - Edit
  - WebFetch
metadata:
  author: samzhu
  version: 1.0.0
  category: workflow-automation
  pattern: sequential-orchestration
---

# Implementing a Task

## Role: Senior Developer

Pragmatic, test-first, detail-oriented. Never skip Red phase.
Focus on ONE task at a time.

## Contract

```
Paths:  All paths relative to project root. Artifacts → docs/grimo/
Input:  docs/grimo/tasks/*-<spec-id>-*.md (next pending task file)
        docs/grimo/specs/*-<spec-id>-*.md (THE spec file, for reference)
        docs/grimo/architecture.md (framework versions + patterns)
        docs/grimo/development-standards.md
Output: Updated task file (Status → PASS/FAIL + Result section)
        Production code + test for this ONE task
Valid:  Test passes. Task file updated with result.
Next:   /planning-tasks [spec-id] (always return to loop controller)
```

## Prerequisites

Find the next pending task file for this spec:
1. Glob `docs/grimo/tasks/*-<spec-id>-*.md`
2. Read each, find first with `Status: pending` (respect dependency order)
3. If none pending: "All tasks complete. Run `/planning-tasks [spec-id]`."

**Before writing code**:
0. **Environment precondition check.** Scan the task file for a
   `## Requires` section, and read the BDD for any tool/binary
   prerequisites (SDK, compiler, database daemon, container runtime,
   cloud CLI, etc.). Verify each is present (`which <tool>`, daemon
   reachable, env var set). If a required precondition is missing,
   STOP. Do not start RED. Report to the user which precondition is
   missing and ask how to proceed: install now, defer the task
   (SUPERSEDED), or fail it (FAIL).
1. Read the architecture doc for framework versions and correct
   import paths. Use the EXACT versions and APIs documented there.
   Do not use deprecated APIs.
2. **For load-bearing build / framework / plugin / dependency-
   management / native-image config**, WebFetch the ecosystem's
   official docs page cited in the architecture doc. Do not rely on
   prior memory or research output — docs drift, plugins rename,
   DSLs change. Cite the URL in the task Result section.
3. Check if `poc/<spec-id>/` exists — if so, read the POC code for
   validated API usage patterns. The POC has already proven these work.
4. Check spec file section 6 for `### POC Findings` — use documented
   correct patterns instead of guessing.

## Process

```
- [ ] Read task file — understand BDD scenario
- [ ] Read architecture.md — correct API versions and patterns
- [ ] TDD Red — write failing test from BDD
- [ ] TDD Green — minimal implementation to pass
- [ ] TDD Refactor — clean up, stay green
- [ ] Update task file — Status + Result section
```

### TDD Red

Transform BDD scenario into one @Test:
- `Given` → test setup
- `When` → action call
- `Then` → assertion

Run test. It MUST fail. If it passes, the test is wrong or the
functionality already exists.

### TDD Red for infrastructure / config tasks

Not every task is a JUnit-style code change. Build scripts, config
files, ignore rules, CI pipelines, package manifests, formatter
configs — these don't have "failing tests" in the classical sense.
Legitimate RED patterns for infrastructure tasks (pick one that
matches the task):

- **config-absent** — grep the target file; the change we're about
  to make is absent (e.g., `grep -c 'new-plugin' build-manifest` → 0).
- **task-not-registered** — the ecosystem runner doesn't know the
  task yet (e.g., `gradlew tasks --all | grep new-task` → 0 matches;
  `npm run` doesn't list the new script).
- **command-fails** — the command that should succeed after the
  change currently fails (e.g., the ecosystem's native-build command
  errors because the native config block is missing).
- **file-absent** — a file the task creates does not yet exist.
- **marker-absent** — a CI job, hook, or pre-commit config that
  should reference new tooling does not yet.

GREEN = the same check now succeeds. Capture the RED/GREEN commands
verbatim in the task Result section so the verification is
reproducible by anyone auditing the spec later.

### TDD Green

Write minimal code (or config) to make the RED check pass. No extras
beyond this task's BDD scope.

### TDD Refactor

Clean up while keeping green. Follow the development-standards doc.

### Update task file

Read the update format from `references/task-result-format.md`.

**Glossary**: All new type/function names must match `docs/grimo/glossary.md`.

## Anti-Patterns

- Do NOT implement more than one task.
- Do NOT skip Red phase.
- Do NOT add features beyond this task's BDD scope.
- Do NOT run verifications that are outside your task's BDD scope. A
  task whose BDD *is* running the verification chain (e.g., a final
  verification task inside a spec) is allowed to run it — that is its
  scope, not "spec-level verification leaking into implementation".
- Do NOT use deprecated APIs — verify against the ecosystem's current
  official docs (not prior memory) before writing.
- Do NOT start RED with missing environment preconditions. Run the
  Prerequisites step 0 check first. A missing SDK, daemon, or binary
  is an environmental issue, not a task failure.

## Handoff

After updating the task file, immediately invoke `/planning-tasks [spec-id]`
to return to the loop controller. Do not wait for user confirmation.

## Escalate

Task not feasible (design issue, missing dependency) → set task Status to
FAIL, then invoke `/planning-tasks [spec-id]` with explanation.
