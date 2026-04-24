---
name: shipping-release
description: >
  Merges completed work, syncs documentation to reflect new reality,
  updates changelog, manages version tags. Use after QA/auto-verify
  passes, when the user says "ship it" or "merge."
  Do NOT use before implementation and verification are complete.
disable-model-invocation: true
allowed-tools:
  - Read
  - Glob
  - Grep
  - Bash
  - Write
  - Edit
metadata:
  author: samzhu
  version: 1.0.0
  category: workflow-automation
  pattern: sequential-orchestration
---

# Shipping a Release

## Role: Release Engineer

Methodical, careful with shared state. Verify before merge, document
before tag. Documents are living — update them to match reality.

## Contract

```
Paths:  All paths relative to project root. Artifacts → docs/grimo/
Input:  docs/grimo/specs/*-<spec-id>-*.md (spec file with section 7: results PASS)
        docs/grimo/specs/spec-roadmap.md
Output: Merge commit, updated docs, spec-roadmap ✅, archived spec
Valid:  Spec section 7 shows all AC PASS, tests pass, docs synced
Next:   /planning-tasks next (continue loop)
```

## Prerequisites

Read the spec file. Verify section 7 (Implementation Results) shows all
AC passed. If no section 7 or any AC failed:
"Run `/planning-tasks [spec-id]` first."

## Process

```
- [ ] Pre-flight: verify spec results PASS + verification gate + git status clean
- [ ] Clean up: archive/delete PoC directories if any
- [ ] Commit: clear message referencing spec ID
- [ ] Doc sync: update product & design docs to reflect new reality
- [ ] Archive: move completed spec to archive
- [ ] Changelog: update docs/grimo/CHANGELOG.md
- [ ] Status: spec-roadmap.md → ✅
- [ ] Tag: version tag if milestone complete
```

### Pre-flight verification gate

Before shipping, run the project's deterministic verification script.
Look for a `verify-all.sh` (or equivalent) in the project's scripts
directory. If it exists, run it — it encodes the full Verification
Command Registry and handles environment detection, skip logic, and
result logging without LLM inference.

If no script exists, read the QA strategy doc for a Verification
Command Registry table and run each command manually.

**Gate rule:** ALL CRITICAL commands must PASS. SKIP-if-unavailable
commands may skip (with documented reason). If any CRITICAL command
fails → REJECT, fix first.

Cross-check: the spec's §8 QA Review section must reference execution
of the verification script (or all registry commands). If the QA
review only ran a subset, flag as incomplete and re-run.

### Commit message format

```
feat(sandbox): implement S002 grimo-sandbox image build
```

### Doc Sync — after shipping

New capability shipped = documents must reflect new reality.

```
- [ ] PRD.md — mark shipped feature as done in MVP roadmap
- [ ] architecture.md — does implementation match? update if deviated
- [ ] development-standards.md — learned new best practice? add it
- [ ] spec-roadmap.md — status ✅ (done below)
```

Only update docs where reality diverged. Skip docs still accurate.

### Archive completed spec

Move the ONE finished spec file to `specs/archive/`:

```bash
mkdir -p docs/grimo/specs/archive
mv docs/grimo/specs/YYYY-MM-DD-<spec-id>-*.md docs/grimo/specs/archive/
```

`specs/` only keeps `spec-roadmap.md` + in-progress specs.
`specs/archive/` holds completed specs for reference.

**Note**: Task files in `docs/grimo/tasks/` and POC directories in
`poc/<spec-id>/` should already be cleaned up by `/planning-tasks`
Phase 3. If any remain, delete them now.

### Update spec-roadmap.md

When all specs in a milestone are `✅`, collapse the milestone:

**Before (in progress):**
```markdown
## Milestone 1: Docker Sandbox（Phase 0a）
**Goal**: Run Claude Code inside Docker container
**Done when**: S001-S003 all done
**Tag**: `v0.1.0`

| # | Spec | Points | Status |
|---|------|--------|--------|
| S001 | Docker basic ops | XS(8) | ✅ |
| S002 | grimo-sandbox Image | XS(8) | ✅ |
| S003 | Container auth | S(10) | ✅ |
```

**After (collapsed):**
```markdown
## Milestone 1: Docker Sandbox ✅ `v0.1.0` (2026-04-15)
3/3 specs complete. Details → `specs/archive/2026-04-*-s00[1-3]-*`
```

### CHANGELOG

```markdown
## [Unreleased]
### Added
- S002: grimo-sandbox Docker image with Claude CLI + Node.js
```

### Milestone tag

When all specs in a milestone are `✅`:
```
git tag -a v0.1.0 -m "Milestone 1: Docker sandbox operational"
```

## Handoff

After all steps complete, immediately invoke `/planning-tasks next`
to continue the loop. Do not wait for user confirmation.

## Escalate

Pre-flight fails → resolve issues, then invoke `/verifying-quality [spec-id]`
to re-verify.
