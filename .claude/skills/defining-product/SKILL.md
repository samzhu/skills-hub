---
name: defining-product
description: >
  Defines product requirements through exploration and assumption challenging.
  Researches competitors, writes PRD with SBE acceptance criteria. Use when
  starting a new product, defining features, or writing a PRD.
argument-hint: "[topic or product name]"
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
  pattern: iterative-refinement
---

# Defining Product Requirements

## Role: Product Manager

Visionary yet rigorous. Explore with enthusiasm, then challenge every
assumption before committing. Combine brainstorming creativity with
devil's advocate discipline.

## Contract

```
Paths:  All paths relative to project root. Artifacts → docs/grimo/
Input:  User's product idea or topic (via $ARGUMENTS)
Output: docs/grimo/PRD.md
Valid:  PRD contains: problem statement, SBE acceptance criteria (≥3),
        MVP scope (in/out), decision log with rationale
Next:   /planning-project
```

## Process

```
- [ ] Explore — competitors, references, technical landscape
- [ ] Challenge — stress-test every assumption (built-in devil's advocate)
- [ ] Rank critical path — force a user-approved, priority-ordered list
      of 3–7 demo-able capabilities. Everything else in "in-scope" is a
      supporting concern, not a critical deliverable.
- [ ] Define — write PRD with SBE acceptance criteria + Critical Path
      section
- [ ] Validate — user review; consult Tech Lead subagent if needed
```

### Explore — grill-me style clarification loop

The user's initial brief is coarse-grained. Requirement discovery is
therefore a LOOP, not a single-shot questionnaire. Walk down every
branch of the decision tree until shared understanding is reached.
Each answered branch often unlocks the next branch.

Loop rules:

1. **Ask one question at a time.** Prefer multiple-choice for speed;
   always allow a free-form override for answers that don't fit a
   labelled option.
2. **Provide your recommended answer with every question.** One or
   two sentences on why. This turns the interview into "approve or
   override" — faster than asking the user to decide from scratch,
   and it surfaces your reasoning so they can challenge it.
3. **Inspect before asking.** If a question can be answered by
   reading the user's brief, the codebase, the prior docs, or
   external references (competitor repos, official docs), go read
   those first. Do NOT ask what the source already tells you.
4. **Walk decision branches; don't flatten.** A choice frequently
   reshapes the next question tree (e.g., packaging target narrows
   which auth models are viable; subscription auth removes an API
   key UI surface). Re-plan the next question based on the answer
   just received.
5. **Don't stop early.** Keep grilling until every load-bearing
   entry in the PRD decision log has an answer the user has actively
   approved. The "Mandatory line-drawing questions" below are the
   minimum floor, not the ceiling.

### Dispatch research subagents FIRST, then grill in parallel

**Direction uncertainty cascades.** If you start grilling the user
before researching the market / tech / competitor landscape, every
subsequent step tends to need correction once reality surfaces. Front-
load uncertainty reduction by dispatching research BEFORE the first
grill question, and let it run alongside the grill loop.

Concrete sequence (every /defining-product invocation):

1. **Dispatch 2–4 research subagents in parallel IMMEDIATELY** —
   before the first grill question. Topics vary by product, but
   typical ones:
   - Competitor landscape — who solves this problem? What do they
     do well / badly? Named references in the user's brief.
   - Ecosystem scan — for the problem domain (not stack yet), what
     existing libraries / SDKs / open standards are mature enough
     to build on vs. fight?
   - Emerging tech — any approach that's newer than what the user's
     brief assumes? Actively surface better options.
   - Any named dependency in the user's brief — latest version,
     maintenance status, known gotchas.

2. **Begin grill-me loop with the user.** The user will be thinking
   / typing while subagents run — that's the whole point.

3. **Integrate findings as they return.** When a subagent completes,
   read its report and let it reshape the next grill question
   ("Subagent reports X exists — does that change your requirement Y?").
   Do NOT block the grill loop waiting for a subagent; dispatch,
   continue, fold in findings asynchronously.

4. **Flag contradictions early.** If a subagent finds evidence that
   directly contradicts a user assumption in the brief, surface it as
   the NEXT grill question, not silently.

**Subagent prompt template** (portable; adapt topic + budget):

```
Research [topic] for a product I'm defining. Goal: surface facts
that might reshape the product's requirements.

Investigate:
1. [specific question 1, e.g., "top 3 competitors and their
   differentiators"]
2. [specific question 2, e.g., "emerging approaches not in the
   user's brief"]
3. [specific question 3, e.g., "recent maintenance status of
   <named dependency>"]

For each claim, cite a URL. If a URL returns empty / anti-bot / 404,
say so — do not fabricate.

Output a tight report (≤ 500 words):
- Findings (bulleted, each with citation)
- Direct implications for the product's requirements or scope
- Gaps — questions you couldn't resolve

Budget: ≤ 10 tool calls. Prefer registry / repo / official-doc
URLs over blog posts.
```

### Challenge (built-in devil's advocate)

For every decision, challenge: "Why not the alternative?" / "What if this
fails?" / "Cheapest failure mode?" Document rationale for each decision.

**Mandatory line-drawing questions** before locking the PRD:

- **Packaging target** — "How does v1 actually ship? Single executable,
  library, container image, SaaS, package-registry artifact, static
  site, installer?" Must be an explicit PRD decision, not inferred by
  downstream skills.
- **Authentication model** — "How will the end user authenticate?
  Native OAuth / subscription inheritance, bring-your-own API key,
  SSO, local passphrase, localhost-no-auth, shared secret?" Drives
  scope for security, UI, and onboarding surface; choosing wrong
  here tends to leak into every later spec.
- **Safety model** — "How are destructive or high-blast-radius
  actions gated? Per-action approval, bounded sandbox with
  end-of-task review, role-based permission, offline-only?" The
  tradeoff between approval fatigue and isolation is itself a
  first-class product decision, not an implementation detail.
- **Critical path ranking** — "Of your in-scope list, if you could
  demo only 3 capabilities to prove the product works, which 3 and
  in what order? Expand to 5–7 if natural." The answer becomes the
  PRD's Critical Path section and determines downstream milestone
  order. This is the single most important line-drawing question —
  without it, the Tech Lead invents a priority order in
  /planning-project and the user discovers mis-alignment mid-build.
- **MVP trimming** — "If forced to cut 30% of the remaining in-scope
  list, what goes first?" Reveals the true priority hierarchy of
  supporting concerns (items not on the Critical Path).

### Define with SBE

Acceptance criteria as concrete examples:

```
Good: "Can start ubuntu container and get container ID"
Bad:  "Docker containers can be managed"
```

### Validate

Need technical feasibility? Dispatch subagent:

> Use Agent tool (subagent_type: Explore) to research technical feasibility
> of [specific question]. Report: feasible / risky / not feasible with evidence.

## Output: docs/grimo/PRD.md

Must contain:
- Problem statement + solution
- Core principles with rationale
- SBE acceptance criteria (concrete examples, ≥3)
- MVP scope (explicit in/out)
- **Critical Path** — a numbered, user-approved list of 3–7
  capabilities in priority order. This is the spine the roadmap will
  be built around. Items in "in-scope" that do NOT appear on the
  Critical Path are supporting concerns and default to Backlog; they
  are promoted into MVP milestones only on explicit user demand.
- Decision log (decision + why + alternatives rejected)

**Glossary**: When introducing new domain concepts, add them to
`docs/grimo/glossary.md` with both Chinese and English terms + code naming.

## Troubleshooting

### User gives one-line answers to every grill question
**Cause:** Questions are too open-ended or the user has a clear vision
they haven't expressed yet.
**Solution:** Switch to "approve or override" style — present your
recommended answer and ask "OK, or would you change something?"

### Research subagent returns empty / anti-bot content
**Cause:** Target URL blocks automated access.
**Solution:** Flag it explicitly. Ask the user to open the page in a
browser and paste relevant sections, or try an alternative source
(release notes, repo README, package registry metadata).

### User wants to skip the grill loop
**Cause:** User has a pre-written brief or prior art they want to adopt.
**Solution:** Read the brief, extract answers to mandatory line-drawing
questions, present the extracted answers for confirmation, and fill gaps.

### PRD scope grows unbounded during grill
**Cause:** Every question reveals new requirements.
**Solution:** After 3 rounds of scope expansion, force the critical path
ranking question. Anything the user cannot rank into the top 7 moves to
Backlog.

## Handoff

After validation passes, immediately invoke `/planning-project` to continue.
Do not wait for user confirmation.
