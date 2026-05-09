---
name: shipping-release
description: >
  Merges completed work, syncs documentation to reflect new reality,
  updates changelog, manages version tags. Auto-invokable by the model
  ONLY after QA gate passes (see "QA Gate Precondition" below).
  Use when the user says "ship it" / "merge", or when an upstream
  workflow (cron-loop / planning-tasks Phase 3 / verifying-quality
  PASS verdict) has confirmed all AC green and verify-all.sh exit=0.
  Do NOT use before implementation and verification are complete.
allowed-tools:
  - Read
  - Glob
  - Grep
  - Bash
  - Write
  - Edit
metadata:
  author: samzhu
  version: 1.1.0
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

## QA Gate Precondition (auto-invocation guard)

This skill is auto-invokable, but the model MUST verify ALL of the
following before doing anything destructive (commit / tag / push):

1. **Spec doc §7 Implementation Results** lists every AC with PASS
   verdict. Missing §7 or any FAIL → REJECT, route to `/planning-tasks
   [spec-id]` to finish implementation first.

2. **verify-all.sh exit 0** within the current tick — i.e. ran
   `scripts/verify-all.sh` and Verdict line shows "✅ all CRITICAL
   passed; exit=0". A stale earlier-tick PASS does NOT satisfy the
   gate; re-run if untouched files have changed since the last run.

3. **`git status` clean of unrelated changes** — only the spec's own
   diff should be staged. If working tree contains drive-by edits
   from another spec, REJECT and ask user to split.

4. **Touched test files actually run** — targeted gradle test for
   modified production classes must show non-zero test count + 0
   failures. A green build that ran zero tests is NOT a PASS.

If ANY precondition fails, the model MUST STOP, summarize the gap, and
either route to the upstream skill (`/planning-tasks [spec-id]` /
`/verifying-quality [spec-id]`) or ask the user to resolve. Never
"ship around" a failing gate by rationalizing it as low-risk.

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

### Archive + Cleanup (mandatory)

Run all three steps — no skipping.

**1. Archive the spec file:**
```bash
mkdir -p docs/grimo/specs/archive
mv docs/grimo/specs/YYYY-MM-DD-<spec-id>-*.md docs/grimo/specs/archive/
```

`specs/` must only contain `spec-roadmap.md` + in-progress spec files after this step.

**2. Delete task files:**
```bash
rm -f docs/grimo/tasks/*-<spec-id>-*.md
```

If `/planning-tasks` Phase 3 already deleted them, this is a no-op — run it anyway to confirm clean state.

**3. Delete POC directories (if any):**
```bash
rm -rf poc/<spec-id>/
```

**Verify clean state** — after the three steps, `git status` must show no untracked files under `docs/grimo/tasks/` or `poc/` for this spec.

### Update spec-roadmap.md

The roadmap is a lean index — SpecID / 標題 / 點數 / 相依 / 狀態 columns only.
Detail lives in the spec file (now archived); never put notes back into the roadmap.

1. Remove the spec row from the `## 🚧 Active` table.
2. Append a row to the appropriate phase section in `## ✅ Shipped`:

```markdown
| S002 | grimo-sandbox Docker image | XS(8) | v0.1.0 |
```

3. If the spec was in the `## 📝 待辦清單`, remove that checkbox entry.
4. If the spec was in the `## 🏁 Milestones` table, update that row's 狀態 to `✅` (or remove the row if the milestone version is now fully shipped).

**Fields:** SpecID · short title (≤ 40 chars) · 點數 · 版本 (from the git tag you just created, or `—` for patch-level sub-specs)

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
