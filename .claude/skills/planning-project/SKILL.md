---
name: planning-project
description: >
  Tech-lead role that turns an approved PRD into an architecture doc,
  development standards, a QA strategy, and an estimated spec roadmap.
  Use when the user says "plan the project", "create the roadmap", "break
  PRD into specs", "pin framework versions", "design the architecture",
  or immediately after /defining-product completes. Prefer newer,
  well-maintained libraries. Do NOT trigger for single-spec design —
  route to /planning-spec instead.
argument-hint: "[status]"
allowed-tools:
  - Read
  - Glob
  - Grep
  - Bash
  - Write
  - Edit
  - Agent
  - WebFetch
  - WebSearch
metadata:
  author: samzhu
  version: 1.0.0
  category: workflow-automation
  pattern: sequential-orchestration
---

# Planning Project Architecture & Specs

## Role: Tech Lead

Experienced, data-driven, decisive. Translate product vision into
engineering plans. Research before deciding, estimate before committing.

## Contract

```
Paths:  All paths relative to project root. Artifacts → docs/grimo/
Input:  docs/grimo/PRD.md (must exist with SBE criteria)
Output: docs/grimo/architecture.md
        docs/grimo/adr/*.md
        docs/grimo/development-standards.md
        docs/grimo/qa-strategy.md (how quality is verified)
        docs/grimo/specs/spec-roadmap.md (with milestones + estimation)
Valid:  Every spec has: description, dependencies, SBE criteria, story points
        Specs grouped into milestones with completion conditions
        QA strategy defined with verification pipeline
        Framework dependency table with exact versions
Next:   /planning-spec [first-spec-id]
```

## Usage

```
/planning-project            # Full planning flow
/planning-project status     # Report progress, suggest next spec
```

## Prerequisites

Read `docs/grimo/PRD.md`. If missing: "No PRD found. Run `/defining-product` first."

**Check project directory**: Inspect the source code directory. If no
source code exists (empty/new project), the first spec MUST be
`S000: Project Init` — set up workspace structure, dependency config,
module skeleton, CI config, etc.

## Process

```
- [ ] Inventory — list the project directory; detect any existing
      scaffold (language-specific starters, generated templates,
      partial skeletons). Record findings in the architecture doc as
      a "State at planning" note.
- [ ] Confirm packaging target — single executable / library /
      container image / SaaS / static site / installer. Shapes the
      build configuration and many downstream architecture decisions.
- [ ] Confirm dependency-adoption style — front-load every pin in a
      central manifest, vs lazy-add per spec as each dep is actually
      consumed. Both are valid; the user must pick.
- [ ] Check project state → empty? Include S000 Project Init spec
- [ ] Market & tech landscape → competitors, ecosystem, emerging tools
- [ ] Technical feasibility research → verify SDK/API maturity
- [ ] Framework version pinning → exact versions + API validation
- [ ] Verify every package coordinate via the registry — do NOT trust
      a single subagent claim. Use WebFetch against the canonical
      package registry, or dispatch a second, independently-prompted
      subagent. Confirm group/scope/namespace as well as version.
- [ ] Architecture decisions → architecture doc
- [ ] Development standards → development-standards doc
- [ ] QA strategy → qa-strategy doc + verification scripts
- [ ] Spec breakdown → each spec validates one assumption
- [ ] Agile estimation → six dimensions → story points
- [ ] Milestones — **milestone N corresponds to PRD Critical Path
      item N**, one-to-one. Supporting concerns (in-scope but not on
      the Critical Path) default to Backlog; promote only on explicit
      user demand. Do NOT re-order the Critical Path based on
      technical-dependency feel — if item K's dependencies aren't
      ready, insert a "foundation for K" sub-milestone before M(K),
      never a re-ranking.
- [ ] Prioritize → dependency graph + execution order
- [ ] ADR (only if needed) → adr directory
- [ ] **Write CLAUDE.md "Where things live" index.** Claude Code
      auto-loads CLAUDE.md every session (zero tool calls). Without
      an artefact directory index there, every future session spends
      3–5 tool calls re-discovering layout. Preserve any user-
      supplied principles / workflow sections; **add or refresh** the
      "Where things live" section using the template below.
```

### Market & Technical Landscape Research — parallel with early grill, not after

**Direction uncertainty cascades.** If you lock stack decisions
before researching what the ecosystem actually looks like today,
every downstream spec tends to need correction when reality surfaces.
Front-load uncertainty reduction by dispatching research **before**
the first grill question and let subagents run alongside early grill.

Read `references/research-protocol.md` for the full dispatch sequence,
subagent prompt template, and verification rules.

### Framework Version Pinning & API Validation

**CRITICAL: Research must go deep enough to validate actual API usage.**

For every framework/library/SDK selected:

1. **Pin exact version** — not just the package name, but the specific
   version number (e.g. "0.19.4" not just "latest")
2. **Verify API is current** — check if the APIs you plan to use are
   deprecated. Read actual source code or API docs, not just descriptions
3. **Write actual import paths** — document the full module path for
   primary types/functions you will use
4. **Produce a code snippet** — for the primary use pattern, verify it
   works against the pinned version
5. **Check for migration guides** — if a library is mid-migration (old
   API deprecated, new API emerging), document the NEW API exclusively

Output a **Framework Dependency Table** in `architecture.md`:

```markdown
## Framework Dependency Table

| Package | Version | Primary Import / Module | Verified |
|---------|---------|----------------------|----------|
| ... | x.y.z | module::Type | yes/no |
```

**Principle: Prefer latest stable API. Never plan against deprecated APIs.**

When a subagent reports a package version, dispatch a follow-up to:
- Fetch the actual API docs or source
- Confirm primary type/function names are not deprecated
- Identify any deprecation warnings in the modules you plan to use

### Technical Feasibility & Best Practices Research

**Source priority** (high → low):
1. **Official docs** — language/framework official docs, blog, examples
2. **Official recommendations** — recommended packages, official cookbook
3. **Community consensus** — Awesome lists, widely adopted by major projects
4. **Third-party reviews** — personal blogs, benchmarks (cross-verify)

For each key technology, verify:

| Check | How |
|-------|-----|
| Official best practices? | Official docs / book / cookbook |
| Latest stable version + release date? | Package registry |
| API matches our needs? | API docs |
| Official examples or starter template? | Official repo |
| Known issues or breaking changes? | GitHub issues / CHANGELOG |
| Maintenance status? | GitHub insights |
| Better alternatives? | Package registry trends |

**Technology selection principle:**
- Prefer **latest stable version** of every dependency
- When comparable, choose the one with more recent activity
- Every selection must include **links to official sources** as evidence
- If a library is mid-migration, use the NEW API path exclusively

Record findings in `architecture.md`.

### Updating PRD from research

When research reveals better options than what the PRD specified:

| Change level | Example | Action |
|---|---|---|
| **Tech selection** | Different edition/version | Update PRD decision log directly |
| **Architecture** | Different DB/framework | Update PRD decision log + architecture.md |
| **Scope change** | Drop a subsystem | Escalate → `/defining-product` (needs PM) |

### Project Init Spec (S000)

When the project directory has no source code, include `S000: Project Init`:

```
S000 scope:
- Workspace/project config file (package manager manifest)
- Module/package skeleton for each module in architecture
- CI config (if applicable)
- Linter/formatter config
- .gitignore
- Verify build/install succeeds with zero errors
```

S000 has no business logic — purely scaffolding. Size: XS or S.

### ADR Policy

PRD's decision log is the single source of truth for initial decisions.
Only create ADRs in `docs/grimo/adr/` for **new decisions that emerge
during development**. Do NOT duplicate PRD decisions as ADRs.

### Estimation (six dimensions, 1-3 each)

| Dimension | 1 Low | 2 Medium | 3 High |
|---|---|---|---|
| Technical risk | Known | New but documented | Unproven |
| Uncertainty | Clear | Approach clear, details TBD | Need PoC |
| Dependencies | Self-contained | 1-2 internal | External/blocking |
| Scope | 1-3 files | 4-10 files | 10+ files |
| Testing | Unit | Integration | E2E/manual |
| Reversibility | Easy | Some effort | Hard |

6-8→XS, 9-11→S, 12-14→M, 15-16→L, 17-18→XL(decompose).

### Milestones

Group specs into milestones with clear completion conditions:

```markdown
## Milestone 1: [Name] (Phase 0a)
Goal: [one sentence]
Done when: S001-S003 all done

| # | Spec | Points | Status |
|---|------|--------|--------|
| S001 | ... | XS(8) | 🔲 |
```

Each milestone → version tag when complete.

### Critical Path anchor

The PRD is expected to contain a **Critical Path** section — an
ordered list of demo-able capabilities, ranked by the user. The
roadmap MUST mirror that order, one milestone per Critical Path item.

If a Tech Lead instinct says "technically X should come before Y", but
the user ranked Y higher, insert a tiny "prepare for Y" milestone
instead of re-ranking. The roadmap's milestone sequence is the user's
priority made concrete; the Tech Lead's job is to fill in the
engineering gaps between each critical step, not to re-shuffle them.

If the PRD lacks a Critical Path section, STOP and route back to
`/defining-product` — inventing a priority order here and having the
user correct it mid-build is the single largest source of waste in
this pipeline.

### QA Strategy — prefer ecosystem, scripts are last resort

Decide the verification pipeline in this order; stop at the first "yes":

1. **Ecosystem-native tooling.** WebFetch the ecosystem's official docs
   for testing, coverage, and architecture / boundary rules. Most
   mainstream stacks already ship a one-line command for each concern.
   Examples across ecosystems (pick yours):
     - JVM / Gradle:  `./gradlew test`, `./gradlew jacocoTestCoverageVerification`
     - Node:          `npm test`, `npm run coverage`
     - Python:        `pytest`, `pytest --cov --cov-fail-under=N`
     - Go:            `go test ./...`, `go test -cover`
     - Rust / Cargo:  `cargo test`, `cargo llvm-cov --fail-under-lines N`
     - .NET:          `dotnet test --collect:"XPlat Code Coverage"`
   Use those. No shell wrapper, no bash reinvention.

2. **Mainstream battle-tested libraries.** If step 1 doesn't cover a
   concern, look for a high-usage, actively maintained, free library
   (with official or well-cited docs) that plugs into the build. Pin
   its version in the Framework Dependency Table.

3. **Custom scripts — last resort, fail-loud only.** Only when steps
   1-2 genuinely have no answer, write a script. Scripts MUST:
     - Document WHY the script exists (what gap in 1-2 it fills).
     - **Fail loudly on "nothing to check" conditions.** If a parser
       finds zero items it MUST exit non-zero unless the caller passes
       `--allow-empty`. Silent-pass is forbidden; it hides drift bugs.
     - Carry a dogfood test in the QA pipeline that feeds the script
       a known-good fixture and asserts the expected output.

Record the chosen pipeline in the QA strategy doc as **concrete
commands**, not as "a pipeline of shell scripts". Example good form:

    PR gate:        <ecosystem test command>
    Coverage:       <ecosystem coverage command with threshold>
    Arch/boundary:  <ecosystem arch-rule test, e.g., an ArchUnit test
                     or equivalent>

**AC-to-test contract (enforced via ecosystem, not scripts).** Each
spec's acceptance criteria become test names or tags:
`@DisplayName("AC-3 ...")`, `@Tag("AC-3")`, `test_ac3_...`,
`t.Run("AC-3/...", ...)`, etc. The standard test runner's own exit
code is the pass/fail signal. A spec is "covered" iff at least one
test references each live AC id. Enforce this with ONE
ecosystem-native mechanism (a single JUnit test, an rspec matcher, an
xunit theory, a Go test, ...), not with grep-the-markdown scripts.

### Glossary

Maintain `docs/grimo/glossary.md` as the project's ubiquitous language.
When architecture decisions introduce new domain concepts, add entries.

### CLAUDE.md "Where things live" — template

Read `references/claude-md-template.md` for the full template.
Drop the template into the project's top-level `CLAUDE.md`, replacing
`<project>` with the actual project directory name. Preserve any
user-supplied principles or workflow sections already present.

## Doc Sync — after ADR changes

```
- [ ] architecture.md reflects new decision?
- [ ] PRD.md scope affected?
- [ ] development-standards.md affected?
- [ ] spec-roadmap.md needs new/modified specs?
- [ ] CLAUDE.md "Where things live" — still matches reality?
      (new convention, moved directory, new source root?)
```

## Status check

When invoked with `status`:
1. Read `docs/grimo/specs/spec-roadmap.md`
2. Find specs where dependencies `✅` and status `🔲`
3. Report by milestone and suggest next

## Troubleshooting

**Subagent reports a package coordinate that feels off.**
Cause: libraries often split into sibling artifacts under different
groups, scopes, or namespaces (e.g., a family may mix `com.acme.x:foo`
with `com.acme:bar`, or `@acme/core` with `@acme-ext/sandbox`).
Fix: WebFetch the canonical registry URL for each coordinate before
writing it into the architecture doc. If the registry 404s, try the
sibling group/namespace permutation, then report uncertainty to the
user rather than guessing.

**User pastes a concrete config (build manifest, lockfile, Dockerfile
fragment) in answer to a multi-choice question.**
Treat the pasted config as the authoritative answer. Do not force-map
it back to a labelled option. Mirror the actual pins/settings in the
decision log.

**Research subagent claims a feature exists but the source page
returned anti-bot or empty content.**
Flag it explicitly in the findings. Do not silently fabricate. Ask the
user to open the page in a browser, or try a different canonical
source (release notes, repo source, package registry metadata).

**PRD contradicts research findings.**
Apply the "Updating PRD from research" table: tech-level deltas update
the PRD decision log directly; architecture deltas update PRD +
architecture doc; scope deltas escalate back to /defining-product.

## Handoff

After roadmap is complete, immediately invoke `/planning-spec [first-spec-id]`
to start designing the first spec. Do not wait for user confirmation.

## Return from /planning-spec

When requirements are unclear: re-evaluate spec, consult PRD, update roadmap.
