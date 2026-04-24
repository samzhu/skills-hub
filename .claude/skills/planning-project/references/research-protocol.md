# Project-Level Research Protocol

## When to Dispatch

Every `/planning-project` invocation. Research runs **before** the first
user grill question (Inventory / Packaging / Dep-style), so findings
reshape grill questions rather than being invalidated by premature
decisions.

## Dispatch Sequence

1. **Dispatch 3-5 research subagents in parallel IMMEDIATELY** —
   before the first user grill question. Typical topics:
   - **Competitor analysis** — how do similar tools solve this? What
     can we learn or avoid?
   - **Ecosystem scan** — for each technical domain surfaced by the
     PRD (persistence, communication, async, testing, serialization,
     observability, etc.), list 2-3 candidates with trade-offs and
     current maintenance status.
   - **Emerging tech** — approaches that didn't exist or weren't
     mainstream when the PRD was written.
   - **Per-named-dependency deep dives** — for every library,
     SDK, or framework the PRD names, fetch the registry page for
     latest version, read release notes, and verify primary API is
     not deprecated. Cross-check Maven Central / npm / PyPI /
     crates.io / NuGet directly (do NOT trust a single subagent
     claim on coordinates — pair with WebFetch verification).

2. **Begin the early grill questions** (Inventory, Packaging target,
   Dep-adoption style). User will be thinking while subagents run.

3. **Integrate findings as they return.** When a subagent reports,
   let its findings reshape subsequent grill questions
   ("Subagent found X supersedes Y — does that shift the packaging
   target?"). Do NOT block grilling on subagent completion.

4. **Flag contradictions aggressively.** If research contradicts a
   PRD assumption, surface it as a grill question BEFORE writing the
   architecture doc — easier to correct at PRD level than after a
   roadmap is locked.

## Subagent Prompt Template

Adapt topic and specific questions per dispatch:

```
Research [topic] for a project I'm architecting. Goal: surface facts
that could reshape framework selection or scope.

Investigate:
1. [question 1 — current stable version + maintenance status of
   <named dependency>; check the canonical registry page directly]
2. [question 2 — deprecated APIs or migration guides in recent
   releases]
3. [question 3 — alternative libraries with meaningful trade-offs]

For every version, groupId / package name, API signature, cite a
registry URL or source link. If the page returns anti-bot or 404,
say so — do not fabricate.

Output (<= 600 words):
- Findings per question, each with citation
- Implications for architecture decisions
- Gaps / items needing a second opinion

Budget: <= 15 tool calls. Prefer: canonical package registry pages,
official docs, the project's own README / CHANGELOG. Avoid blog
summaries as primary evidence.
```

## Verification Rule

**Never write a Maven/npm/PyPI coordinate into architecture.md based
on a single subagent claim.** Verify via a second WebFetch to the
registry before pinning.
