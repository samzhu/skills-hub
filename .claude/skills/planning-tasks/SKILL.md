---
name: planning-tasks
description: >
  Loop controller for per-spec development. Breaks a designed spec into
  BDD task files, runs the task loop (implementing-task ping-pong),
  performs final verification via independent subagent, consolidates
  results back to spec, and cleans up temporary task files.
  Use to start a spec, break into tasks, find next, or run auto loop.
argument-hint: "[spec-id] or [next] or [auto]"
allowed-tools:
  - Read
  - Glob
  - Grep
  - Bash
  - Write
  - Edit
  - Agent
metadata:
  author: samzhu
  version: 3.1.0
  category: workflow-automation
  pattern: context-aware-routing
---

# Planning Tasks — The Spec Loop Controller

## Role: Lead Engineer

Practical, organized, methodical. Break designs into incremental verification
steps. Run the task loop. Perform final verification. Always know what's next.

## Contract

```
Paths:  All paths relative to project root. Artifacts → docs/grimo/
Input:  docs/grimo/specs/*-<spec-id>-*.md (THE spec file, sections 1-5)
        docs/grimo/development-standards.md
Output: docs/grimo/tasks/YYYY-MM-DD-<spec-id>-<task-id>.md (temporary task files)
        Updated spec file (sections 6-7 added)
Valid:  Every SBE criterion has a corresponding task.
        Task files form ordered verification chain.
        After completion: results in spec file, task files deleted.
Next:   /implementing-task [spec-id] (task loop)
        /shipping-release (all pass + QA pass)
```

## Key Principles

### Validate Before Planning

Never create task files based on unverified design assumptions. The
spec's §2 Approach was written during design phase — it may contain
assumptions about library capabilities, API compatibility, or
architecture patterns that have not been tested against real code.

**Rule: POC validates the approach BEFORE task files are created.**
Task files encode the implementation plan. If the approach is wrong,
every task file is wasted work. Discovering a design flaw in POC
costs minutes; discovering it mid-task-loop costs hours.

### Read Before Researching

Before spawning research agents or fetching external docs, exhaust
what the project already knows:

1. **Existing research notes** — check `docs/local/` for prior
   research on the same libraries or patterns.
2. **Prior spec findings** — shipped specs' §7 may contain validated
   API usage patterns. A re-research of something already proven is
   pure waste.
3. **PRD decisions and principles** — the spec's approach must align
   with product-level decisions. Cross-validate, don't assume.

**Anti-pattern:** Spawning 3 parallel research agents to investigate
an API that was already thoroughly analyzed in a local research note.
One targeted agent to fill gaps is sufficient.

### Challenge Inherited Assumptions

The spec's §2 Approach inherits assumptions from the roadmap and PRD.
These assumptions were written with less information than you have now.
Before planning tasks, explicitly verify:

- Does the spec's chosen approach match the PRD's stated principles?
- Does the spec assume a library feature that doesn't actually exist?
- Is there a simpler approach the spec didn't consider because
  research at design time was incomplete?
- Has the upstream library evolved since the spec was designed?

When a contradiction is found, **stop and escalate** to
`/planning-spec [spec-id]` for a design revision — do not silently
plan tasks around a flawed design.

### Spec File is the Living Document

- Task files in `docs/grimo/tasks/` are **temporary work items**
- After all tasks pass → results consolidated into spec file section 7
- Task files are then **deleted** (the spec file is the permanent record)

### Independent Verification via Subagent

After all tasks pass, quality verification is performed by a **subagent**
— a fresh context that re-reads the spec, re-reads the code, and evaluates
independently. This catches blind spots that same-session self-review misses.

### POC Before Production

- When a spec introduces **new packages, SDKs, or unfamiliar APIs**, validate
  in an isolated `poc/<spec-id>/` folder first — never experiment directly in
  the project codebase.
- POC scope goes beyond "do objects construct" — it validates whether
  the **design approach itself** is correct. A POC that proves the SDK
  works but doesn't test the core design hypothesis is incomplete.
- Only after POC passes → create task files and implement, referencing
  POC findings for correct API usage.

### POC Must Validate the Design Hypothesis, Not Just the SDK

A common failure mode: the POC confirms "the new dependency's API
works" but never tests "do we actually NEED this dependency?" This
leads to specs that introduce dependencies to solve problems the
existing stack already handles.

**Correct POC sequence:**
1. **First:** Test what the existing stack can do for this use case
   (run the current framework's APIs, observe actual behavior)
2. **Then:** Identify the specific gap the existing stack cannot fill
3. **Only then:** Test whether the proposed new dependency fills that gap

**If the POC reveals the existing stack already solves the problem,**
escalate to `/planning-spec [spec-id]` for a design revision. Do NOT
proceed with task planning for an approach that's more complex than
necessary.

**A POC that only tests "does Library X's API work?" without first
testing "does our existing stack already do this?" is incomplete.**

## Usage

```
/planning-tasks S002      # Break S002 into task files + start loop
/planning-tasks next      # Find next spec from roadmap
/planning-tasks auto      # Semi-auto loop (see below)
```

## When invoked with `next`

1. Read `docs/grimo/specs/spec-roadmap.md`
2. Find specs where dependencies `✅` and status is not `✅`
3. Artifact-driven routing:

| Status | Check | Action |
|---|---|---|
| `🔲` | — | → `/planning-spec [id]` |
| `⏳ Design` | Spec file has sections 1-5? | Yes → Phase 0. No → `/planning-spec [id]` |
| `⏳ Plan` | Task files exist? | Yes → Phase 3. No → Phase 0 |
| `⏳ Dev` | All tasks PASS? | Yes → Phase 4. No → Phase 3 |
| `✅` | — | Skip, find next |

## When invoked with a spec-id

Check which phase to enter based on existing artifacts.

### Phase 0: Pre-Flight Validation

**Always run this phase first.** Even if task files already exist,
re-validate when re-entering the spec after a pause.

**Step 1: Read existing knowledge.**

Before any new research or planning, read what the project already
knows. This prevents redundant research and catches stale assumptions.

- Scan for existing research notes (e.g., `docs/local/`) related to
  this spec's domain. If found, read them — they likely contain API
  findings, rejected alternatives, and design rationale that inform
  task planning.
- Check prior shipped specs' §7 for validated API patterns this spec
  depends on. Reuse proven patterns, don't re-research them.

**Step 2: Cross-validate spec design against product requirements.**

Read the product requirements document. Check:
- Does the spec's §2 Approach align with stated product principles?
- Do the product-level decisions (e.g., "own the session" vs
  "delegate to framework") still hold given what we now know?
- Does the spec reference libraries/APIs that the product requirements
  assumed work differently than they actually do?

**Step 3: Question the approach.**

Ask explicitly:
- "Does the framework/library the spec wraps already solve this
  problem natively?" (e.g., the CLI might already persist sessions
  — no need for an external persistence layer.)
- "Is there a simpler approach the spec didn't consider?"
- "Are we adding a dependency to solve a problem that doesn't exist?"

If any contradiction is found → **stop and escalate** to
`/planning-spec [spec-id]` for design revision. Do NOT plan tasks
around a flawed design.

If pre-flight passes → proceed to Phase 1.

### Phase 1: POC Validation (conditional)

**This phase runs BEFORE task file creation.** POC validates the
approach so task files are based on proven assumptions, not
speculative design.

Skip if spec §2 does not declare `POC: required` AND Phase 0 did
not identify any unvalidated assumptions.

**Where does the POC plan come from?**

The POC plan originates in `/planning-spec`. During spec design,
research classifies each design decision as Validated, Hypothesis,
or Unknown. Decisions classified as **Hypothesis** produce a POC
plan in spec §2, specifying:
- What to test (the design hypothesis)
- Why research couldn't answer it
- Suggested POC scope

If `/planning-spec` did thorough research, this phase is either
skipped or executes a well-defined POC plan. If Phase 0 discovers
the spec lacks a POC plan despite having unvalidated assumptions,
**escalate to `/planning-spec`** to add one — do not improvise a
POC without a clear hypothesis.

**Fallback assessment (when spec §2 doesn't classify confidence):**

| Signal | POC needed? |
|---|---|
| Spec introduces packages/SDKs **never used before** in this project | **Always Yes** — even if spec research is thorough, API semantics (return values, exception behavior, builder patterns) must be verified against real code |
| Spec integrates with unfamiliar external APIs | Yes |
| **Spec's approach wraps/extends a framework SPI** | **Yes** — verify the SPI's actual behavior before designing a wrapper. The SPI may already provide what the spec builds manually |
| **Spec references CLI flags or commands run in a different environment than the developer's host** (containers, CI runners, different OS/user) | **Yes** — flags that work on the developer's host may be blocked or behave differently in the target execution environment. Test the exact command in the target environment before encoding it into task files. |
| High uncertainty on whether approach will work | Yes |
| Spec only modifies existing, well-understood code | No |
| All packages already validated by prior specs | No |

**POC scope — validate the APPROACH, not just the SDK:**

A POC that proves "the SDK's objects construct successfully" but
doesn't test the core design hypothesis is incomplete. The POC must
answer the **design question**, not just the API question.

Examples:
- Wrong scope: "Can I create a `SessionService` and call
  `appendEvent()`?" (tests the SDK)
- Right scope: "Can `AgentSession.resume()` restore conversation
  context across JVM restarts?" (tests the design hypothesis)

The right POC question comes from §2 Approach — what is the spec
betting on? Test that bet.

**POC execution:**

1. Create `poc/<spec-id>/` directory with minimal setup — or a test
   class in the main project if dependencies are already available.
   Prefer the lightest approach that answers the hypothesis.
2. Implement the **minimum code** to validate:
   - The core design hypothesis (does the approach work?)
   - Key integration points connect
   - Correct usage patterns discovered
3. Run POC tests — all must pass
4. Document findings in the spec file section 6 under `### POC Findings`:
   - **Design hypothesis verdict** — does the approach hold?
   - Correct API usage patterns (code snippets)
   - Gotchas discovered (deprecated APIs, version quirks)
   - Verified dependency versions
5. POC passes and approach confirmed → proceed to Phase 2
6. POC passes but reveals simpler approach → **stop and escalate**
   to `/planning-spec [spec-id]` with findings. The spec design
   needs revision before task planning.
7. POC fails → stop and report to user with findings

Record the decision in the spec file section 6 header:
`POC: required` or `POC: not required` with rationale.

**POC directory is temporary** — cleaned up in Phase 4 after results
are consolidated into the spec file.

### Phase 2: Create Task Files

Skip if task files already exist for this spec.

Before planning, check:
- Each acceptance criterion → one @Test? (if not, too coarse)
- Estimated size ≤ M? (if not, split)
- Criteria are concrete SBE examples? (if abstract, refine)

Too large or abstract → escalate to `/planning-spec [spec-id]`.

**Task granularity by spec size:**

| Spec size | Target task count | Rationale |
|---|---|---|
| XS (6–8) | 1–2 | Often a single AC = single task |
| S (9–11) | 3–4 | One per AC + one infra task (deps, scaffolding) |
| M (12–14) | 4–6 | One per AC + infra + integration |
| L+ (15+) | Split into sub-specs first | — |

Merge trivial setup steps (add dependency, create interface) into a
single infrastructure task when they share the same verification
command. Each task should carry enough work to justify a full
RED → GREEN → REFACTOR cycle — if RED is just "file does not exist"
and GREEN is creating a 5-line file, the task is too small.

**E2E smoke test task (mandatory when stubs replace real systems):**

Ask: "Do unit tests for this spec stub or mock any external
boundary — infrastructure, subprocesses, third-party services,
credential stores?"

If **yes** → the task plan MUST include a final task that exercises
the feature through its real entry point against real dependencies,
verifying every AC's data assertions return non-empty, conformant
output. Stubs prove logic; only a real run proves assembly.

If **no** (pure logic, no stubbed boundaries) → skip.

**Create individual task files:**

Read the template from `references/task-file-template.md`.

**Important:** Task files must reflect POC findings. If the POC
revealed different API patterns than the spec assumed, the task
files must use the validated patterns, not the spec's original
assumptions. Note any divergence.

**Add section 6 (Task Plan) to the spec file** — a lightweight index
of all tasks, their AC mapping, execution order, and POC decision.

Update `spec-roadmap.md` status to `⏳ Plan`.

### Phase 3: Task Loop

1. Scan task files: `docs/grimo/tasks/*-<spec-id>-*.md`
2. Find first task with `Status: pending` (respect dependency order)
3. If found → `/implementing-task [spec-id]`
4. If all tasks `PASS` → proceed to Phase 4
5. If any task `FAIL` → stop and report to user

After `/implementing-task` returns, re-enter Phase 3 (check next task).

Update `spec-roadmap.md` status to `⏳ Dev` on first task start.

### Phase 4: Consolidation + Independent Verification

All tasks PASS. Five steps:

**Step 1: Deterministic checks (inline)**

Run the project's standard pipeline commands. Each must exit 0.

```
<ecosystem test command>        # e.g., gradlew test, pytest, npm test
<ecosystem compile check>       # e.g., gradlew compileTestJava
```

If any fails, identify the failing task and re-enter Phase 3.

**Step 1.5: E2E artifact verification (integration seam gate)**

**This step is NOT optional — it must be actively evaluated for
every spec.** Do not skip it without an explicit written rationale.
The most common failure mode is assuming "unit tests passed, so
the artifact works" — unit tests prove components, not assembly.

Ask: "Does this spec's implementation rely on behavior that only
activates in the real artifact — framework wiring, schema
initialization, event serialization, subprocess communication, or
credential injection that unit tests bypass with stubs?"

If **yes** → build the artifact, run it, and verify the feature
end-to-end in an **isolated environment** (temporary directory for
persistent state — never delete or overwrite the user's real data).
Design **boundary-condition scenarios** that push known constraints:
if the change enables larger payloads, test with payloads that
exceed the previous limit; if the change alters data flow, verify
the data actually arrives at the terminal consumer intact.
Record evidence (command + output) in the spec §7.
If E2E reveals failures → record findings in spec §7, revert
status to `⏳ Dev`, create new task files for fixes, re-enter
Phase 3. **Do not hotfix without task files.**

If **no** → skip, proceed to Step 2. Record the rationale in §7:
"E2E not required — no integration seams identified."

**Step 2: Consolidate results into spec file**

Add section 7 (Implementation Results) to the spec file:
- Verification results (tests, lint, format)
- Key findings from implementation
- Correct usage patterns (code snippets — the most valuable part)
- AC results table
- **Pending verification list** — any test that compiled but could not
  run (e.g. integration tests skipped due to missing environment).
  Mark each with `⏳` and the command needed to verify later.

**Sync design sections with implementation.** Review spec §2
(Approach) and §4 (Interface/API Design) for statements that
diverged during implementation. For each divergence, either:
- Update §2/§4 inline with a `[Implementation note]` annotation, or
- Record it in §7 Key Findings with a forward reference.

The goal: a reader of §2/§4 should not be misled by stale design
assumptions. §7 is the ground truth; §2/§4 should at minimum
cross-reference it.

**Register tech debt.** If implementation discovered issues that
belong to future work (architecture doc inaccuracies, skipped ITs,
known limitations), add them to the project's tech debt tracking
section in the roadmap doc. Use the types defined in the
development standards (bug / drift / skip).

Update spec file status line to `✅ Done`.

**Step 3: Clean up temporary files**

```bash
rm docs/grimo/tasks/*-<spec-id>-*.md
rm -rf poc/<spec-id>/
```

Task details are now preserved in spec section 6 (plan + POC findings) +
section 7 (results). The spec file is the single permanent record.

**Step 4: Spawn independent verification subagent**

**Why a subagent?** Same-session verification has blind spots — the
implementer tends to confirm their own work. A fresh agent context
provides independent scrutiny of test coverage, code quality, and
spec compliance.

Use the **Agent tool** to spawn a verification subagent:

```
Agent tool parameters:
  description: "QA verify [spec-id]"
  prompt: |
    You are an independent QA reviewer. Run /verifying-quality for spec [spec-id].
    
    The spec file is at: docs/grimo/specs/<spec-file>.md
    (or docs/grimo/specs/archive/<spec-file>.md if already archived)
    
    Read the spec file (all sections), then independently:
    1. Run ./gradlew test and ./gradlew compileTestJava
    2. Verify every AC in §3 has a matching @DisplayName test
    3. Read all production code and test files listed in §5
    4. Check code quality against docs/grimo/development-standards.md
    5. Check Javadoc accuracy — does it match actual implementation?
    6. Check for design drift between §2/§4 and actual code
    7. Append QA Review section to spec §7 with verdict (PASS/REJECT)
    
    Return your verdict and any findings.
  subagent_type: "general-purpose"
```

**After subagent returns:**
- **PASS** → proceed to Routing
- **REJECT with CRITICAL** → address findings, re-enter Phase 3
- **REJECT with only IMPORTANT/MINOR** → if subagent already fixed
  them in-place, accept as PASS. Otherwise fix and re-verify.

### Routing

After Phase 4 Step 5 (subagent QA) passes:

| Spec size | Action |
|---|---|
| All sizes | Stop. Tell the user the spec is ready to ship. Instruct them to run `/shipping-release`. |

This skill cannot auto-invoke shipping — shipping is intentionally
gated because its actions (commit, tag, archive) require explicit
user authorization.

## Post-Verification Bug Re-Entry Protocol

When E2E artifact testing, QA subagent, or user manual testing
reveals bugs **after tasks have been consolidated**, the fix MUST
go through the spec — not be applied as an ad-hoc hotfix.

**Principle: the spec file is the single source of truth. Every
change to shipped code must be traceable to a task in the spec.**

Procedure:

1. **Record findings in spec §7.** Add an "E2E Verification" or
   "Post-Ship Bug" subsection documenting: what was tested, what
   failed, the root cause analysis.
2. **Revert spec status.** Change status from `✅ Done` back to
   `⏳ Dev (bug fix)`. Update the roadmap entry to match.
3. **Assess design impact.** For each bug, ask: "Does this
   invalidate a design assumption in §2 Approach?" If yes →
   run a targeted POC to discover the correct assumption before
   planning fixes. Record POC findings in §6.
4. **Create new task files.** One task per bug (or group tightly
   related bugs). Each task has BDD, root cause, target files.
   Add these to the spec §6 task plan as a "Round N" section.
5. **Re-enter Phase 3.** Execute the task loop normally.
6. **Re-run Phase 4.** Including E2E verification. The same bug
   category that was found must be re-tested with evidence.

**Banned shortcut:** Directly editing production code to fix a
post-verification bug without a task file. This leaves no audit
trail and risks introducing untracked regressions.

## Semi-Auto Mode (`auto`)

```
/planning-tasks auto
```

| Size | Behavior |
|---|---|
| **XS/S** | Full auto: pre-flight → POC → plan → task loop → consolidate → subagent QA → report. Only stop on failure or design revision. |
| **M** | Stop after pre-flight for user confirmation. Then auto through POC → task loop → subagent QA → report. |
| **L+** | Stop at every phase boundary. Equivalent to manual mode. |

Stop conditions:
- Any task `FAIL`
- Subagent QA REJECT with CRITICAL
- Spec needs human judgment (M+ design decisions)
- No more specs available
- User interrupts

## Scope Change Mid-Flight

When the user changes requirements while the task loop is running
("drop that", "we don't need it", "defer AC-N to later"), handle the
change at this skill level. Do NOT let `/implementing-task`
reinterpret scope mid-RED.

Procedure:

1. **Stop the current task.** If a task was in progress when the
   change arrived, mark its status `SUPERSEDED` with a one-line note
   (`removed YYYY-MM-DD by user: <reason>`). SUPERSEDED is NOT a
   failure — it is a valid terminal state and Phase 4 treats it as
   "covered by explicit deferral".
2. **Propagate the change:**
   - Delete task files rendered obsolete.
   - Update `Depends On` fields of surviving tasks that previously
     depended on the removed task.
   - Update the spec's §3 acceptance criteria: mark the removed AC
     as deferred (heading convention per the QA strategy doc) with
     date and reason.
   - Update the spec's §6 task plan (table, POC rationale, AC
     coverage).
   - Update the roadmap entry if the overall spec scope collapsed.
3. **Resume the loop.** Re-enter Phase 3 against the new task graph.
   Find the next pending task.
4. **Audit downstream impact.** If the change touches
   project-level decisions, update the relevant docs in the same
   sweep. A scope change that doesn't propagate creates stale
   references. Walk this checklist explicitly for every scope change
   — state either "touched" or "intentionally not touched — reason:
   …" for each entry. No silent skips.

   ```
   - [ ] The in-flight spec file (§3 ACs, §4 interfaces, §5 file
         plan, §6 task plan, §7 results)
   - [ ] The roadmap doc (entry description, dependency graph,
         milestone table, AC summary)
   - [ ] The architecture doc (module map, ports, framework table,
         data flows, storage)
   - [ ] The development-standards doc (if a convention changes)
   - [ ] The QA strategy doc (if test pipeline changes)
   - [ ] The PRD decision log (if a prior decision is reversed or
         refined; note date + reason)
   - [ ] The glossary (if domain terms added / renamed / removed)
   - [ ] The memory directory (add a project memory entry when the
         change is load-bearing for future sessions — scope
         re-plans, stack migrations, policy reversals)
   ```

## Handoff

After Phase 2 or when Phase 3 finds a pending task, immediately invoke
`/implementing-task [spec-id]`. Do not wait for user confirmation.

After Phase 4 Step 5 (subagent QA passes), **stop and tell the user
the spec is ready to ship**. Instruct them to run `/shipping-release`.

## Troubleshooting — Known Failure Modes

### Task files created based on wrong design assumptions
**Symptom:** POC reveals the spec's approach is fundamentally wrong.
All task files must be discarded.
**Root cause:** Phase ordering violation — task files were created
before POC validated the approach.
**Fix:** Always run Phase 0 (pre-flight) and Phase 1 (POC) before
Phase 2 (task files). If you catch yourself creating tasks before
running POC, stop and reorder.

### Redundant research — existing notes ignored
**Symptom:** You spawned 3+ research agents to investigate something
the project already has a research note about.
**Root cause:** Skipped Phase 0 Step 1 (read existing knowledge).
**Fix:** Before ANY external research, scan the project's research
notes directory and prior spec findings. One targeted agent to fill
gaps is better than three agents re-covering known ground.

### Spec design contradicts product requirements
**Symptom:** User points out "go back and read the PRD" — the spec's
approach violates a stated product principle or decision.
**Root cause:** Skipped Phase 0 Step 2 (cross-validate with PRD).
The spec was designed with assumptions that diverged from product
intent.
**Fix:** Always cross-validate the spec's §2 Approach against the
PRD's principles and decision log before planning tasks. Catch
contradictions early — escalate to `/planning-spec` for revision.

### POC tests SDK but misses the design question
**Symptom:** POC proves "the library works" but doesn't test whether
the spec's *approach* is correct. Mid-task-loop, you discover the
library already solves the problem natively — no custom code needed.
**Root cause:** POC scope was too narrow — tested API mechanics
instead of the design hypothesis.
**Fix:** The POC question must come from the spec's §2 Approach.
Ask: "What is the spec betting on?" and test that bet. Example:
instead of "can I call `SessionService.appendEvent()`?", ask
"can `AgentSession.resume()` restore context across restarts?"

### Adding a dependency to solve a problem that doesn't exist
**Symptom:** The spec introduces a complex dependency (with schema,
config, bridge code) when the framework already handles the problem.
**Root cause:** Skipped Phase 0 Step 3 (question the approach).
The spec assumed a gap that doesn't exist.
**Fix:** Before accepting any new dependency, ask: "Does the
framework already solve this?" Test the framework's native
capability in POC before designing a wrapper.

## Escalate

Spec too large or criteria too vague → invoke `/planning-spec [spec-id]` to return to SA/SD.
