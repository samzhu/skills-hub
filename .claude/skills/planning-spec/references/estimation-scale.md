# Spec Estimation Scale Reference

Skills Hub uses **story points** as the official estimation system for
roadmap rows, shipped outcome accounting, velocity summaries, and marketing
outcome reports.

Appendix A keeps the six-factor diagnostic as background reference for why
some point values need more planning depth. It is not a required spec field
and is never stored in `spec-roadmap.md`.

## Official Story Point Model

Use one Fibonacci deck value as the spec estimate:

```
1, 2, 3, 5, 8, 13, 20
```

`20` is a parent-spec planning signal, not a normal implementation target.
Split it into smaller specs before `/planning-tasks`.

| Story points | Planning size | Design depth | User interaction |
|---:|---|---|---|
| 1 | Micro | Direct recommendation; no approach comparison | Clarify only if requirement is ambiguous |
| 2 | XS | Skip approach comparison; state chosen path | 3-question intake + up to 1 grill question |
| 3 | S | Brief comparison | 3-4 questions, confirm approach |
| 5 | S-M | Focused comparison + API/test outline | Confirm approach + one key interface |
| 8 | M | Full comparison + interface definition | Confirm approach + key interfaces |
| 13 | L | Deep design + PoC spike may be needed | Confirm at each phase boundary |
| 20 | XL parent only | Must be decomposed | N/A |

## Recording Rules

- `story_points`: the official Fibonacci delivery estimate stored in
  `docs/grimo/specs/spec-roadmap.md`.
- `planning_size`: Micro / XS / S / S-M / M / L / XL.

New roadmap rows should put the story point value in the `點數` column.
Prefer a plain deck value such as `8`. If a human-readable label is helpful,
use `M(8)`, where `8` is still the official story point value.

Examples:

- `story_points = 1`, `planning_size = Micro`.
- `story_points = 2`, `planning_size = XS`.
- `story_points = 8`, `planning_size = M`.

Do not store the Appendix A diagnostic score as the roadmap point value. A
diagnostic score of 6 maps to `story_points = 1`.

### Adjustment Rules

Move **up one Fibonacci step** at final re-score when the implementation
adds material work that was not visible during design:

- New persistent schema or data migration was added.
- Frontend + backend + database all changed in one spec.
- Real browser E2E, native image, production deploy, Docker, Cloud Run,
  or another external environment became part of the acceptance evidence.
- A Round-N pivot changed the implementation approach.
- The spec touched 3+ Spring Modulith modules or 9+ production files.

Move **down one Fibonacci step** when final implementation proves smaller:

- The spec is docs-only, config-only, or test-only.
- Existing code already satisfied part of the acceptance criteria.
- The shipped change touched only one narrow surface and no persisted state.
- A planned backend/frontend half was cancelled or split into another spec.

Do not move more than one step without splitting or writing a short ADR.
Never use fractional story points. If a range appears in historical
records, such as `S(9-10)`, use the midpoint only for historical
accounting and replace it with one deck value during re-score.

## Usage Notes

- **Three scoring moments**:
  1. **Initial estimate** — choose `story_points` from roadmap scope before
     grill.
  2. **Re-score after grill** — `/planning-spec` may shift `story_points`
     after questions reveal hidden complexity or simplify scope.
  3. **Final re-score at ship** — `/shipping-release` re-scores
     `story_points` against actual implementation evidence. Final
     `story_points` lands on `spec-roadmap.md`; the initial score stays in
     the spec as historical record.
- **Why re-score at ship matters:** The roadmap is the project's delivery
  point history. Stale estimates hide systematic underestimation patterns.
  Future planners use this data to calibrate new specs. Recurring shifts such
  as `8 points` shipping as `13 points` surface as useful signal.
- **When in doubt, score higher.** Per McConnell [5], underestimation
  is the most common estimation failure mode. Overestimation just
  means more design rigor, which is cheap.
- **20 = mandatory split.** If the spec reaches 20 points, decompose it into
  2+ specs before proceeding. Do not ship a normal spec as 20 points.

---

## Legacy Outcome Accounting

`spec-roadmap.md` contains two point styles:

1. **Current story points** — new specs use the Fibonacci deck above:
   `1`, `2`, `3`, `5`, `8`, `13`, or parent-only `20`.
2. **Legacy labeled points** — early shipped specs may use labels like
   `XS(1)`, `S(5)`, `M(8)`, `L(20)`, or ranges like `S(9-10)`.

For historical outcome summaries, do not reinterpret old labels through
Appendix A. Use the numeric value inside parentheses:

- `XS(7)` counts as 7 story points.
- `S(9-10)` counts as 9.5 story points for historical accounting only.
- `S(9) -> M(11)` counts as the final confirmed value, 11 story points.
- `META`, cancelled, superseded, deferred, and unshipped specs count as 0.
- Count each `SpecID` once even if it appears in multiple roadmap sections.
- If a milestone says a spec shipped but the roadmap has no point row, read
  the archived spec and use its final re-score.

In old rows, values below 6 are still valid story points. They are not
six-factor diagnostic scores, because the six-factor diagnostic minimum is
6.

---

## Appendix A — Six-Factor Diagnostic

This appendix is a reference table only. Use it to understand why a selected
story point value may need more or less planning depth.

```
six_factor_score =
  tech_risk
  + uncertainty
  + dependencies
  + scope
  + testing
  + reversibility
```

Default diagnostic mapping:

| Six-factor score | Default story points | Planning size |
|---:|---:|---|
| 6 | 1 | Micro |
| 7-8 | 2 | XS |
| 9-10 | 3 | S |
| 11-12 | 5 | S-M |
| 13-14 | 8 | M |
| 15-16 | 13 | L |
| 17-18 | 20 parent only | XL |

The mapping is a starting point, not a second point system. If actual scope
evidence contradicts the diagnostic score, keep `story_points` as the
official value and record the reason.

### Dimension Definitions and Rubrics

### 1. Technical Risk (技術風險)

How likely is it that a technical assumption will prove wrong during
implementation?

Grounded in COCOMO II's **RESL (Risk Resolution) scale factor**, which
scores from "20% risk eliminated" (Very Low) to "100% risk eliminated"
(Extra High), and Boehm's Top-10 Software Risk Items checklist [4].

| Score | Label | Criteria |
|-------|-------|----------|
| 1 | Low | Well-known APIs; team has prior experience; documented patterns exist in the codebase. Corresponds to COCOMO II RESL "High" (risk largely eliminated). |
| 2 | Medium | New API surface but well-documented; or known API used in a novel combination. Corresponds to RESL "Nominal" (some residual risk). |
| 3 | High | Undocumented/pre-1.0 API; known open issues; requires spike to validate; library internals may need inspection. Corresponds to RESL "Low" (significant unresolved risk). Triggers parallel research sub-agents per SKILL.md Research section. |

### 2. Uncertainty (不確定性)

How well-defined are the requirements before design starts?

Grounded in McConnell's **Cone of Uncertainty** [5], which shows
estimates at project inception can vary 0.25x–4.0x, narrowing as
requirements stabilize. Also informed by PERT three-point estimation
[3] where the spread between optimistic and pessimistic values
quantifies uncertainty.

| Score | Label | Criteria |
|-------|-------|----------|
| 1 | Clear | Requirements fully specified in roadmap; no open questions; SBE drafts are concrete. McConnell's "post-requirements" phase (0.67x–1.5x). |
| 2 | Mostly clear | 1-2 open design questions that the grill loop should resolve; SBE drafts need refinement. McConnell's "post-product-definition" phase (0.5x–2.0x). |
| 3 | Ambiguous | Multiple open questions; scope may shift during grill; SBE drafts are placeholders. McConnell's "initial concept" phase (0.25x–4.0x). |

### 3. Dependencies (依賴關係)

How many upstream specs or external systems must be in place, and
how stable are they?

Grounded in COCOMO II's **SITE (Multi-site Development)** and **TEAM
(Team Cohesion)** factors [1], SEI's Taxonomy-Based Risk Identification
"external interface" risk category [6], and Mike Cohn's multi-team
planning guidance (Chapter 18 of *Agile Estimating and Planning*) [7].

| Score | Label | Criteria |
|-------|-------|----------|
| 1 | Standalone | Depends only on `core` (OPEN) or no other module; no external system. |
| 2 | Single dependency | Depends on 1 shipped spec + 1 external system (e.g., Docker Daemon). |
| 3 | Multiple dependencies | Depends on 2+ specs or 2+ external systems; or depends on an unshipped spec. |

### 4. Scope (範疇)

How many files, interfaces, and module boundaries does this spec touch?

Grounded in Mike Cohn's **"Amount of Work"** dimension of story points
[7] and COCOMO II's **CPLX (Product Complexity)** cost driver, which
scores across five sub-domains: control logic, computational logic,
device-dependent operations, data management, and UI management [1].

| Score | Label | Criteria |
|-------|-------|----------|
| 1 | Minimal | 1-3 production files; single module; no cross-module wiring. |
| 2 | Moderate | 4-8 production files; single module with API surface; or touches 2 modules. |
| 3 | Large | 9+ production files; or 3+ modules; or new module boundary setup. |

### 5. Testing (測試複雜度)

How complex is the test setup, and how many test types (T0–T6) are
involved?

Grounded in Ferrer, Chicano & Alba's quantitative model of testing
complexity [12], NASA SWEHB Section 7.6 on test estimation [13]
(which identifies test environment setup as an independent estimation
variable), and COCOMO II's **RELY (Required Reliability)** cost driver [1].

| Score | Label | Criteria |
|-------|-------|----------|
| 1 | Simple | Pure JUnit (T0) only; no Spring context, no external system. |
| 2 | Moderate | Needs Spring slice or `@ApplicationModuleTest` (T1-T2); or simple Testcontainers. |
| 3 | Complex | Needs Docker Daemon running (T3-T4); or multi-container; or real CLI binary; or flaky-prone external I/O. |

### 6. Reversibility (可逆性)

If the spec's design proves wrong, how hard is it to undo?

Grounded in Jeff Bezos' **Type 1 / Type 2 Decision** framework [9]
("one-way door" vs "two-way door"), Martin Fowler's argument that
reducing irreversibility is the key to taming complexity [10], Kent
Beck's identification of irreversibility as one of four "complexity
monsters" [11], and IFPUG FPA's **GSC14 (Facilitate Change)** [2].

| Score | Label | Criteria |
|-------|-------|----------|
| 1 | Two-way door | Internal types only; no published API; no persisted state; no downstream consumers yet. Can be fully reverted in one commit. |
| 2 | Partially reversible | Published API (`@NamedInterface`) with 1-2 consumers; or persisted schema with migration path. Revert requires coordinated changes. |
| 3 | One-way door | Published API with 3+ consumers; or breaking change to shipped data format; or external system integration that others depend on. Revert is a project-level event. |

---

### Worked Examples

#### S001 — Core Domain Primitives → score 7 / XS / 2 points

| Dimension | Score | Rationale |
|-----------|-------|-----------|
| Tech risk | 1 | Pure Java records, no framework API |
| Uncertainty | 1 | Types fully enumerated in roadmap |
| Dependencies | 1 | No dependencies |
| Scope | 2 | 8 files in one module |
| Testing | 1 | Pure JUnit |
| Reversibility | 1 | No consumers yet |

Default mapping: six-factor score 7 -> XS -> 2 story points.

#### S002 — Module Skeleton + Modulith Verify → score 9 / S / 3 points

| Dimension | Score | Rationale |
|-----------|-------|-----------|
| Tech risk | 1 | `ApplicationModules.verify()` is well-documented |
| Uncertainty | 1 | Module list decided; policy codified |
| Dependencies | 2 | Depends on S001; needs Modulith on classpath |
| Scope | 2 | 6 `package-info.java` + 2 test classes + doc-sync |
| Testing | 2 | `@ApplicationModuleTest` slice |
| Reversibility | 1 | Empty modules, easily changed |

Default mapping: six-factor score 9 -> S -> 3 story points.

#### S003 — Sandbox SPI + Bind-Mount Adapter → score 13 / M / 8 points

| Dimension | Score | Rationale |
|-----------|-------|-----------|
| Tech risk | 3 | Implementing third-party SPI with unsupported bind-mount pattern |
| Uncertainty | 2 | Lifecycle management pattern needed grill to clarify |
| Dependencies | 2 | S002 shipped + Docker Daemon required |
| Scope | 2 | ~7 files in `sandbox` module + new `api/` sub-package |
| Testing | 3 | Testcontainers + Docker Daemon; `@DisabledInNativeImage` |
| Reversibility | 1 | No downstream consumers yet |

Default mapping: six-factor score 13 -> M -> 8 story points.

---

## Skills Hub Marketing Outcome Checkpoint

Python recalculation on 2026-06-02 for
`docs/grimo/marketing-outcome.md`, using `ccusage 20.0.6` and
`tools/reestimate_story_points.py`.

The period's Codex and Gemini activity belonged to `skills-hub`; Claude was
filtered to the `skills-hub` project. The token/cost formula is:

```text
all agents - all Claude + Claude skills-hub project
```

| Field | Correct value | Notes |
|---|---:|---|
| Date range | 2026-04-24 to 2026-05-19 | Asia/Taipei, inclusive 26 days |
| Commands | `npx ccusage@latest --json --since 2026-04-24 --until 2026-05-19 --timezone Asia/Taipei`; `npx ccusage@latest claude daily --json --since 20260424 --until 20260519 --timezone Asia/Taipei`; `npx ccusage@latest claude daily --json --since 20260424 --until 20260519 --timezone Asia/Taipei --project=-Users-samzhu-workspace-github-samzhu-skills-hub` | Combined with Python arithmetic |
| Input tokens | 118,415,608 | uncached input |
| Output tokens | 15,712,934 | model output |
| Cache create tokens | 35,399,159 | cache write |
| Cache read tokens | 4,570,901,843 | cache hit |
| Total tokens | 4,740,430,679 | 47.40 億 tokens |
| Token cost | $3,362.38 | `ccusage` computed USD cost after Claude project filtering |
| Outcome points | 1,276 | Fibonacci story points through `v4.86.0 / S202`; generated by `tools/reestimate_story_points.py` |
| Counted specs | 235 | `META`, cancelled, superseded, deferred, and rolled-up child specs count as 0 |
| Weekly delivery | 343.5 points/week | `1276 / 26 * 7` |
| Cost per point | $2.64 | `$3362.38176865 / 1276` |

Roadmap cross-check:

| Roadmap denominator | Counted specs | Story points | Use when |
|---|---:|---:|---|
| Through `v4.86.0 / S202` | 235 | 1,276 | Marketing outcome date range: 2026-04-24 to 2026-05-19 |
| Current roadmap through `v4.90.0 / S206` | 239 | 1,318 | Includes work after the `ccusage` date range, so do not pair with the 2026-05-19 cost total |

Do not use the old `10,375,768,169 tokens` / `$5,757.85` / `1663 points`,
`7,479,655,240 tokens` / `$4,924.01` / `1731 points`, or
`7,766,996,172 tokens` / `$5,207.22` / `1731 points` figures for the
marketing outcome. The local `ccusage` run, Claude project filter, and Python
story-point re-estimate above are the current accounting source of truth.

---

## References

[1] Boehm, B.W. et al. *Software Cost Estimation with COCOMO II*.
    Prentice Hall, 2000. ISBN: 0-13-026692-2.
    Also: Boehm, B.W. (1996). "Cost Models for Future Software Life
    Cycle Processes: COCOMO 2.0." *Annals of Software Engineering*.
    https://link.springer.com/article/10.1007/BF02249046

[2] Albrecht, A.J. (1979). "Measuring Application Development
    Productivity." *Proceedings of the IBM Applications Development
    Symposium*. Standardized by IFPUG: *Function Point Counting
    Practices Manual, Release 4.3.1*. https://ifpug.org/ifpug-standards/fpa

[3] Malcolm, D.G. et al. (1959). "Application of a Technique for
    Research and Development Program Evaluation." *Operations Research*,
    7(5), 646-669. Modern application: PMI, *PMBOK Guide*, 6th Ed.,
    Section 6.4.

[4] Boehm, B.W. (1991). "Software Risk Management: Principles and
    Practices." *IEEE Software*, 8(1), 32-41.
    DOI: 10.1109/52.62930

[5] McConnell, S. *Software Estimation: Demystifying the Black Art*.
    Microsoft Press, 2006. ISBN: 978-0-7356-0535-0.

[6] Carr, M. et al. (1993). *Taxonomy-Based Risk Identification*.
    CMU/SEI-93-TR-006.
    https://www.sei.cmu.edu/documents/1077/1993_005_001_16166.pdf

[7] Cohn, M. *Agile Estimating and Planning*. Prentice Hall, 2005.
    ISBN: 978-0-13-147941-8. Summary:
    https://www.mountaingoatsoftware.com/blog/what-are-story-points

[8] Scaled Agile, Inc. "WSJF." *Scaled Agile Framework (SAFe)*.
    https://framework.scaledagile.com/wsjf
    Theory: Reinertsen, D.G. *The Principles of Product Development
    Flow*. Celeritas Publishing, 2009.

[9] Bezos, J. "2015 Letter to Shareholders." Amazon.com, Inc., 2016.
    https://s2.q4cdn.com/299287126/files/doc_financials/annual/2015-Letter-to-Shareholders.PDF

[10] Fowler, M. "Is Design Dead?" martinfowler.com, 2004.
     https://martinfowler.com/articles/designDead.html

[11] Beck, K. "Taming Complexity with Reversibility." TidyFirst
     (Substack), November 17, 2023.
     https://tidyfirst.substack.com/p/taming-complexity-with-reversibility

[12] Ferrer, J., Chicano, F. & Alba, E. (2013). "Estimating software
     testing complexity." *Information and Software Technology*, 55(12),
     2125-2139. DOI: 10.1016/j.infsof.2013.07.007

[13] NASA. "Software Test Estimation and Testing Levels." *SWEHB*,
     Section 7.6.
     https://swehb.nasa.gov/display/SWEHBVD/7.6+-+Software+Test+Estimation+and+Testing+Levels

[14] Anthropic. "Pricing." Claude API Docs. Accessed 2026-06-02.
     https://platform.claude.com/docs/en/about-claude/pricing
