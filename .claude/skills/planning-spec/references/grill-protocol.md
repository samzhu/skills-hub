# Grill-Me Protocol

## Research Gate (MANDATORY)

**Do NOT ask the first grill question until Phase 2 (Research) is complete.** All research agents must have returned and their findings integrated into your working context.

**Why:** Grill questions drive design decisions. If those questions are based on assumptions instead of research facts, the approach comparison table will need to be rebuilt when research findings arrive. This wastes the user's time and erodes trust.

**The correct sequence is:**
1. Research completes → findings integrated
2. First grill question incorporates research findings
3. Approach comparison table is based on verified facts

## Loop Rules

1. **Ask one question at a time.** Prefer multiple-choice for speed; always allow free-form override. **S-sized exception:** may batch 2 closely-related questions in one turn if the answer to Q1 does not change Q2's options.
2. **Provide your recommended answer with every question.** One or two sentences on why. Convert the interview into "approve or override" — faster than asking the user to decide from scratch.
3. **Inspect before asking.** If the project files (roadmap, architecture doc, development standards, prior shipped specs, the codebase itself) already answer the question, read them instead. Do NOT ask what the source already reveals.
4. **Walk decision branches; don't flatten.** A spec-level decision (e.g., which library provides a capability) typically determines the next question (e.g., which adapter shape to expose, which errors translate across the port). Re-plan the next question based on the answer just received.
5. **Don't stop early.** Keep grilling until every load-bearing detail is pinned: scope boundaries, constraints, integration points, data shape, error strategy, and the acceptance-verification command.
6. **Incorporate research findings into questions.** When a research finding reveals an ecosystem capability or limitation, present it as context in the grill question. Example: "Research shows GeminiAgentModel already accepts Sandbox in its constructor. Should we use this pattern, or..."

## Focus Topics

- **Verification success picture (ask FIRST, before any technical question).** Before diving into implementation details, proactively paint 2-3 concrete scenarios of "what does done look like from the user's perspective." Present them as options with trade-offs, not as yes/no confirmations. Each option should include: (a) user actions, (b) expected outcome, (c) implementation cost difference. Let the user choose the depth.
  - **Good example:** "There are three verification depths: A) Registry-level — CLI commands work but the agent doesn't load skills (minimal). B) Projection — skills auto-copied to agent's native path before launch, agent loads them (moderate). C) Conversation integration — enabled skills influence agent responses, verified by human (full). I recommend B because..."
  - **Bad example:** "The verification scope is registry-level, do you agree?" — This offloads the most important scope decision to the user instead of proactively analyzing the options.
  - **Why this matters:** The user's mental model of "done" often differs from the implementer's assumption. Surfacing concrete scenarios early prevents mid-flight scope corrections that waste both parties' time. The HULA framework (arXiv 2024) shows that intervention at the planning stage costs far less than corrections after implementation.
- **Deliverable smell test.** For each item in the roadmap entry's deliverable list, question its fit: "does this spec really need it, or is it misfiled?" Common misfits: a cross-cutting primitive that only 1–2 modules consume (belongs to one owning module); a feature-specific type that happens to be mentioned in the roadmap (belongs to the feature's own spec). Moving an item out costs less than over-designing it here. Grill implementation detail only on deliverables that pass the smell test.
- **Scope boundaries** — "This spec covers X but not Y — correct?"
- **Constraints** — performance, compatibility, deployment, concurrency, platform limits.
- **Integration points** — "This will interact with [A] and [B]. Any existing on disk that I missed by inspecting?"
- **Assumptions from the roadmap** that must be pinned before design.
- **Research-informed decisions** — present research findings that affect design choices and ask user to confirm the direction.
- **Acceptance-verification command** — exactly which standard-pipeline command (per the QA strategy doc) gates this spec.

## Troubleshooting

### Dependency is in-progress but not a code-level dependency
**Cause:** The dependency is milestone ordering, not an import relationship.
**Solution:** Note the status and proceed with design. Record in §1 Goal: "parallel design; dep not blocking."

### Research finding contradicts the roadmap description
**Cause:** The roadmap is a coarse-grained draft; details may be outdated.
**Solution:** Surface the contradiction as the next grill question. When writing the spec, update the roadmap description in the same commit.

### User provides a new information source mid-grill
**Cause:** The user knows ecosystem context that the skill does not.
**Solution:** Immediately dispatch a research sub-agent to verify (fetch raw source code, not summaries), then fold findings into §2 Approach. Do not defer to after the grill loop.

### Research invalidates an already-confirmed grill answer
**Cause:** Research was incomplete or a late-arriving finding changes the trade-off.
**Solution:** Transparently tell the user: "Research finding X changes the trade-off for [decision]. The previous choice of [A] may need revision because [reason]. Revised options: ..." Do NOT silently proceed with the invalidated decision.

### Approach comparison needs rebuilding after research
**Cause:** The comparison table was presented before research completed (Phase 2 gate violation).
**Solution:** Acknowledge the process error. Present a new comparison table based on research facts. Note in the spec's §2 that the approach was revised post-research.

### User asks to research additional sources mid-grill
**Cause:** The user knows about relevant documentation, tutorials, or repos not covered in initial research.
**Solution:** Dispatch sub-agent immediately with raw source code fetching. BLOCK the grill loop until findings return — do not continue asking questions that may depend on the new findings.
