---
name: verifying-quality
description: >
  Independent QA review with testability gate. Performs three-layer
  verification (automated, integration, manual) and blocks shipping
  when required tests are missing. If a spec should be integration-tested
  but no test infrastructure exists, REJECTS and proposes a testing spec.
  Use after all tasks pass, when auto-verify raises issues, or when the
  user requests QA review.
argument-hint: "[spec-id]"
allowed-tools:
  - Read
  - Glob
  - Grep
  - Bash
  - Write
  - Edit
  - Agent
  - WebSearch
metadata:
  author: samzhu
  version: 6.1.0
  category: workflow-automation
  pattern: domain-specific-intelligence
---

# Verifying Quality — Independent QA Review

## Role: QA Engineer

Thorough, skeptical, evidence-driven. Your job is to find what the
implementer missed — and to block shipping when verification is
incomplete, not just when tests fail.

**Core principle: If it should be tested but can't be, that's not
"pending" — that's a gap. Fill the gap before shipping.**

## Why Independent Verification

Same-session verification has blind spots — the implementer confirms
their own assumptions. This skill follows the **QA-Checker pattern**
(CodeAgent, EMNLP 2024): a supervisory agent independently reviews
another agent's work.

The outer harness (Fowler, Harness Engineering 2026) has three layers:
1. **Preventive controls** — catch issues before they happen
2. **Feedback loops** — self-correct without human intervention
3. **Quality gates** — block shipping when loops can't self-correct

This skill is the quality gate.

## Process Overview

```
Step 0: Gather context — read spec, QA strategy, dev standards
Step 1: Layer 1 — Automated checks (unit, compile, lint)
Step 2: Layer 2 — Coverage & integration
Step 3: Layer 3 — Manual verification readiness
Step 4: Testability gate — can every AC actually be verified?
Step 5: Execute tests in isolated environment, capture evidence
Step 6: Code quality review
Step 7: Design sync check
Step 8: Verdict (evidence-based)
Step 9: Record results to spec file & handoff
```

## Process

### 0. Gather context

Read the project's key documents to understand its conventions:

1. **The spec file** — all sections (design through results)
2. **QA strategy / test documentation** — discover the project's
   test pipeline commands, coverage targets, test classification
3. **Development standards / coding conventions** — naming rules,
   forbidden patterns, architectural constraints
4. **Glossary / domain model** — terminology consistency

Use any prior implementation results as context, but **re-verify
independently** — do not trust prior findings blindly.

### 0.5 Build and maintain verification command inventory

**This skill OWNS the verification infrastructure.** The verification
script and registry table are assets maintained by `/verifying-quality`,
not by other skills or by the implementer.

#### Locate existing infrastructure

Look for two artifacts in the project:

1. **Verification Command Registry** — a structured table in the
   project's QA strategy or test documentation listing every
   verification command, its severity, and environment prerequisites.
2. **Deterministic verification script** — an executable (e.g.,
   `verify-all.sh`) that encodes the registry as runnable code.

#### Reconcile: detect gaps between registry and reality

Scan the project's build configuration (e.g., `build.gradle.kts`,
`package.json`, `Makefile`, CI config) for test tasks, coverage
tools, and quality gates. Compare against the registry.

**Gap detection checklist:**

| What to check | Where to look | Gap signal |
|---|---|---|
| Test tasks beyond `test` | Build file (`integrationTest`, `contractTest`, `e2eTest`, etc.) | Build registers task but registry does not list it |
| Coverage tooling | QA strategy targets vs build plugins | QA doc states coverage target but no plugin configured |
| Lint / format gates | Build file, CI config | QA doc mentions linting but no command in registry |
| Architecture checks | Build file (modulith verify, ArchUnit) | Tests exist but not in registry |

#### Act on gaps

| Gap type | Action |
|---|---|
| **Build task exists, not in registry** | Add it to registry + script now (this is a maintenance task, not a spec) |
| **QA doc states a target but tooling is not configured** (e.g., coverage ≥ 75% but no JaCoCo plugin) | `REJECT-BLOCKED` — propose a spec to configure the tooling. Record in verdict: "QA strategy requires X but no tooling exists to measure it." |
| **New test infra was shipped by a spec but not added to registry** | Add it to registry + script now. Flag as IMPORTANT finding: "Spec S0XX shipped `<task>` but did not update the verification registry." |
| **Registry lists a command that no longer exists in build** | Remove from registry + script. Flag as MINOR finding. |

**After reconciliation, the registry and script must be in sync.**
If you made changes, commit them as part of the QA review.

### 1. Layer 1 — Automated checks

**If a deterministic verification script exists**, run it. The script
handles command ordering, environment detection, skip logic, and
result logging — no LLM inference needed for the deterministic layer.

**If no script exists**, create one. Use the registry table as the
source of truth. The script should:
- Run each CRITICAL command and fail on non-zero exit
- Run each SKIP-if-unavailable command with an environment check
  (e.g., `which <binary>`) and skip gracefully when unavailable
- Log results with timestamps to a persistent file
- Exit 0 only if all CRITICAL commands pass

**If neither registry nor script exists** (greenfield project),
discover commands from the build file and QA docs, create both
the registry table and the script, then run it.

Record the full output of the verification script as evidence.
A PASS without captured output is incomplete.

### 2. Layer 2 — Coverage & integration

**Coverage:** Check if the QA strategy states a coverage target. If
yes, verify that coverage tooling is configured in the build and
included in the verification script. If tooling is missing →
`REJECT-BLOCKED` with a proposal to configure it (e.g., "Add JaCoCo
plugin + coverage verification task to build + V4 entry in registry").
If tooling exists, the verification script in Step 1 already ran it.
Check results for changed files against the project's stated targets.
Flag new production files with 0% coverage.

**Integration tests:** Already handled by the verification script
(Step 1) if registered. If the spec touches external systems not yet
covered by a registered command, flag as a gap per Step 0.5.

### 3. Layer 3 — Manual verification readiness

Identify ACs that require human interaction — interactive CLI
sessions, UI behavior, end-to-end user workflows that cannot be
fully automated. Check whether instructions exist for a human to
execute these verifications.

### 4. Testability gate

**For each AC, classify its verification status:**

| Classification | Definition | Action |
|---|---|---|
| `VERIFIED` | Automated test exists, ran, and passed | Record output as evidence |
| `EXECUTABLE` | Test exists but could not run (environment missing) | Run now if possible; otherwise mark pending with prereqs |
| `MANUAL-READY` | Written instructions exist for human verification | Confirm instructions are complete and actionable |
| `MANUAL-MISSING` | Needs human verification but no instructions exist | Write the instructions, reclassify as MANUAL-READY |
| `UNTESTABLE` | **Should be verifiable but no mechanism exists** | **REJECT — propose a spec to build the verification capability** |

**The UNTESTABLE classification triggers a hard stop.**

An AC is UNTESTABLE when:
- It describes observable behavior (output, side effects, state
  changes) that SHOULD have verification — automated or scripted
- But no test, no script, and no written instructions exist
- And the gap cannot be filled by writing instructions alone —
  it requires building new test infrastructure

**When UNTESTABLE is found:**

1. Document which ACs are untestable and why
2. Propose a **testing infrastructure spec** — what capability is
   missing, what it should verify, suggested approach
3. REJECT with verdict `BLOCKED-BY-TESTABILITY`
4. Route to the project's spec planning workflow to design the
   testing capability
5. After the testing spec ships, re-run this verification on the
   original spec

**This is not bureaucracy — it's the difference between "we think
it works" and "we proved it works."**

### 5. Execute tests and capture evidence

For every VERIFIED and EXECUTABLE AC, **actually run the
verification** and capture the output.

#### Isolated test environment (hermetic testing)

When the spec produces a runnable artifact (compiled binary,
packaged application, container image, installable tool), **do not
test in the source tree.** The source tree carries implicit context
(config files, cached state, sibling directories) that masks
whether the artifact works standalone.

**Principle: test the artifact, not the source tree.**

Protocol:
1. **Clean build** — run the project's clean + build commands
2. **Create isolated directory** — empty directory outside the
   source tree (or a subdirectory gitignored by the project)
3. **Copy only the artifact** — the built output, nothing else
4. **Set up minimal fixtures** — only test data the spec requires
5. **Run from the isolated directory** — capture all output
6. **Compare** — actual output vs expected per the AC
7. **Record evidence** — write results back to the spec file
8. **Tear down** — delete the isolated directory

When to use: any spec that produces an artifact with user-visible
behavior. Skip for pure library/internal API specs where unit tests
in the source tree are sufficient.

#### Mandatory E2E gate — integration seam principle

Unit tests with stubs prove components in isolation. They do NOT
prove the assembled system works. When a spec's implementation
relies on framework wiring, runtime initialization, or cross-process
communication that only activates in the real artifact, **hermetic
artifact testing is REQUIRED before shipping**.

**Principle: if the behavior depends on something the test harness
replaces with a stub, a fake, or a manual call, then the real
assembly path is unverified.**

Examples of integration seams that stubs hide:
- DI container bean resolution order and overrides
- Database schema auto-initialization at startup
- Event/message serialization through framework infrastructure
- Credential or environment variable injection from OS-level stores
- Subprocess metadata shape differences between stubs and real output

**Detection rule:** During Step 0, scan the spec's approach and file
plan. Ask: "Is there any behavior that only activates when the real
artifact starts, that unit tests bypass by calling methods directly
or injecting stubs?" If yes → hermetic artifact testing is REQUIRED.
Classify its absence as CRITICAL.

**Code size ≠ integration risk.** A spec may be XS in code changes
but high in integration complexity (e.g., credential injection into
containers, subprocess orchestration, cross-service auth). When a
spec integrates with infrastructure — Docker, subprocesses,
credential stores, network services — explicitly list every
integration seam and justify why each does or does not need
artifact-level testing. Do NOT conflate small code size with low
verification need.

**This gate cannot be waived.** A spec that ships without verifying
its integration seams has an unverified assembly — the same risk
category as an untested AC.

**Execution protocol when E2E gate triggers:**

1. Clean-build the artifact
2. **Create an isolated persistent state directory** (temporary
   directory, NOT the user's real data directory) — configure the
   artifact to use this temp directory for databases, caches, and
   working files. **Never delete or overwrite the user's real
   persistent state** (home directory config, production databases)
   for testing purposes. If the artifact requires environment
   variables or config overrides to redirect state, set them.
   Tear down the temp directory after testing.
3. Start it with real dependencies — not stubs, not mocks
4. Trigger the feature through its real entry point — not by
   calling internal methods
5. **Design boundary-condition scenarios that exercise the
   feature's limits, not just its happy path.** Trivial inputs
   that produce minimal outputs cannot validate a pipeline
   designed for production-scale data. Specifically:
   - **Size boundaries:** If data passes through columns, buffers,
     or serialization with size constraints, design inputs that
     produce outputs exceeding the known capacity limits.
   - **Content complexity:** If the feature processes structured
     data, use inputs with nested structures, special characters,
     and multi-language content — not flat, single-field payloads.
   - **Cross-boundary data flow:** If data crosses a boundary
     (container, subprocess, network), verify it actually crosses
     with meaningful content — not just that the boundary exists.
   Exit criterion: at least one test scenario deliberately pushes
   a known constraint to verify it does not silently truncate,
   reject, or corrupt data.
6. Verify every AC's data assertions against the actual response.
   An empty value where the AC requires content is a FAIL
7. **Collect infrastructure logs** (application logs, container
   logs, subprocess output) showing the full execution trace.
   Timestamped log entries confirming each pipeline stage
   completed are stronger evidence than API response JSON alone.
8. Record full request + response + log evidence

**When E2E reveals failures — route back, don't pass:**

- `REJECT-FIX` with root cause analysis
- Recommend creating new task files via the project's task
  planning workflow (fixes must be traceable, not ad-hoc hotfixes)
- If the failure reveals a design flaw, recommend routing back to
  spec design for revision

**Anti-pattern: declaring E2E "not required" when all tests use
stubs.** If every external boundary in the test suite is stubbed,
E2E is MORE required, not less — the stubs are exactly the
integration seams that need real verification.

#### Evidence standard

Evidence means captured execution output — not "code review
suggests it works."

For each verified AC, record:
- The command that was run
- The actual output (or a meaningful summary)
- The comparison to expected behavior
- PASS or FAIL

For tests involving non-deterministic output (LLM responses, AI
tools), apply **Golden Path testing** — verify structure and
presence, not exact content:
- Output is non-empty
- Format matches expected schema
- Error inputs produce clean messages (no stack traces)

**Do NOT mark an AC as PASS without execution evidence.**

### 6. Code quality review

Check against the project's own standards. Common areas:

- **Naming conventions** — types, methods, packages match project norms
- **Architectural constraints** — correct module/layer placement
- **Immutability** — domain types properly encapsulated
- **Dependency injection** — follows project convention
- **Forbidden patterns** — check the project's explicit ban list
- **Documentation accuracy** — comments match implementation?
  This is the most common blind spot in AI-generated code.
- **Security** — no hardcoded secrets, no injection vectors.
  AI-generated code has a 2.74x higher XSS introduction rate
  (GitClear 2025) — scrutinize input handling.
- **No orphaned TODO/FIXME** in changed files
- **Dependency versions** — verify that explicitly pinned versions
  are not overriding ecosystem-managed versions. Use the build
  system's dependency resolution tools to confirm actual resolved
  versions. An explicit version that downgrades a managed version
  is a CRITICAL finding — it reintroduces fixed bugs and forces
  unnecessary workarounds.

### 7. Design-section sync check

Compare the spec's design sections against actual implementation:
- Statements that no longer match reality
- Missing annotations marking implementation divergences
- Findings that should have updated the design documentation

### 8. Verdict

**Four-layer result table:**

```markdown
| Layer | Result | Detail |
|-------|--------|--------|
| Automated tests | PASS/FAIL | [commands + outcome] |
| Coverage / Integration | PASS/SKIP/PENDING | [coverage or IT status] |
| Manual verification | READY/MISSING/N-A | [instructions location] |
| Testability gate | CLEAR/BLOCKED | [all ACs verifiable?] |
```

**Verdict outcomes:**

| Verdict | Condition | Next action |
|---------|-----------|-------------|
| `PASS` | All layers pass, all ACs have evidence or are MANUAL-READY | Ship |
| `REJECT-FIX` | Tests fail, quality issues, or missing instructions | Fix, re-verify |
| `REJECT-BLOCKED` | UNTESTABLE ACs — verification infrastructure gap | Build test capability first |

**Severity levels for individual findings:**

| Level | Definition | Effect |
|-------|-----------|--------|
| CRITICAL | Missing AC coverage, test doesn't verify what it claims, security issue, build breakage, UNTESTABLE AC | Blocks shipping |
| IMPORTANT | Documentation drift, missing edge-case coverage, standards violation | Should fix before shipping |
| MINOR | Style nit, cosmetic issue | Note for future |

### 9. Record results and handoff

**All evidence goes into the spec file — the single permanent record.**

Append results to the spec's implementation/results section. Testing
instructions (how to test) may live separately, but test outcomes
and evidence (what happened when we tested) belong in the spec.

After recording evidence, **tear down the isolated test environment.**
The spec file preserves the proof; the test directory is ephemeral.

**Handoff:**

- **PASS** → Spec is ready to ship.

- **REJECT-FIX** → Return findings. Fix issues, then re-verify.

- **REJECT-BLOCKED** → Testability gate activated.
  1. Document which ACs are blocked and what verification capability
     is missing
  2. Propose a testing spec — title, scope, suggested approach
  3. The blocked spec does NOT ship until the testing capability
     exists and re-verification passes

### 9.5 Self-healing loop — coverage gap remediation

When the coverage gate (Step 2) reports that line coverage is below
the project's stated target, this skill can **autonomously drive
the fix** rather than just reporting REJECT-FIX.

#### Trigger condition

The coverage verification command (e.g., `jacocoTestCoverageVerification`)
fails with a message like "covered ratio is X, but expected minimum is Y".

#### Protocol

1. **Diagnose** — Parse the coverage report (CSV preferred for
   scripting, XML as fallback). Identify the classes/packages with
   the lowest coverage that are dragging the aggregate below target.
   Rank by "lines missed" descending.

2. **Scope a coverage spec** — Create a lightweight spec whose sole
   goal is "raise coverage from X% to ≥ Y%". The spec's AC is the
   coverage gate passing. The file plan lists the test files to add
   or extend. Prioritize by impact: a class with 47 uncovered lines
   moves the needle more than one with 4.

3. **Route to `/planning-tasks`** — Hand the coverage spec to the
   task loop. `/planning-tasks` breaks it into BDD tasks (one per
   class or cluster of related classes), then ping-pongs with
   `/implementing-task` to write the tests.

4. **Re-verify** — After all tasks complete, `/planning-tasks`
   spawns `/verifying-quality` again. The coverage gate now runs
   with the new tests. If it passes → PASS. If still below target
   → loop back to step 1 with the remaining gap.

#### Loop safety

- **Max iterations: 3.** If coverage still fails after 3 rounds,
  REJECT-FIX to the user with a detailed gap analysis. The
  remaining uncovered code likely needs design discussion (dead
  code removal, refactoring), not just more tests.
- **Each iteration must reduce the gap.** If an iteration adds
  tests but the coverage ratio does not improve, stop and report
  — the new tests are not covering the intended classes.
- **Do not generate meaningless tests.** Tests must assert real
  behavior (Given/When/Then), not just call methods to inflate
  coverage. The code quality review (Step 6) applies to generated
  test code too.

## Troubleshooting

### UNTESTABLE vs. MANUAL-READY
**Rule of thumb:** If a developer can follow written instructions
and verify behavior in under 5 minutes, it's MANUAL-READY. If
verification requires building new tooling, it's UNTESTABLE.

### Evidence missing despite passing tests
**Cause:** Tests ran but output wasn't captured.
**Fix:** Re-run with output capture. A PASS without recorded
evidence is incomplete.

### Integration test environment unavailable
**Decision tree:**
- Can you set up the environment now? → Do it, run the test
- Is it a CI-only concern? → Mark EXECUTABLE with prereqs
- Is there no test at all? → UNTESTABLE if the AC warrants one

### AI-generated code quality drift
**Cause:** AI-generated code accumulates complexity over time
(cognitive complexity +39%, code duplication +12.3% per
GitClear/Qodo 2025 data).
**Fix:** Flag duplicated logic, unnecessary abstractions, overly
complex control flow.

### UX gaps discovered during E2E testing
**Cause:** E2E or user demonstration reveals that a feature works
technically but the user experience is poor — missing guidance,
confusing setup flow, no onboarding instructions.
**Fix:** Record the UX observation as a candidate spec in the
project's roadmap or backlog with a one-sentence description.
Do not let UX discoveries evaporate — they are product insights
that emerge only when testing the real artifact with real users.

### REJECT-BLOCKED feels heavy for a small change
**Response:** The cost of shipping unverified behavior exceeds the
cost of a small testing spec. Test infrastructure is an investment
that pays off on every subsequent change to the same capability.
