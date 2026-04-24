---
name: retro
description: >
  Evidence-based retrospective that produces a reusable trigger-action
  checklist. Use when the user says "retro", "retrospective", "lessons
  learned", "what went wrong", "post-mortem", "review this session",
  "what could be improved", or equivalent in any language. Also use
  proactively when a task has obviously circled — direction changed 3+
  times or the user corrected the assistant more than twice. Works for
  any task type in any project: coding, research, design, writing,
  planning.
metadata:
  version: 2.0.0
  category: workflow-automation
  pattern: iterative-refinement
---

# Retrospective Protocol

Produce an evidence-grounded retrospective that yields a reusable
artifact, not a feel-good reflection. Execute all seven steps in
order. No step may be skipped or merged.

## Design Constraints

This skill is **portable across projects**. Every artifact it
produces — trigger-action checklists, skill files, CLAUDE.md
entries — must follow these rules:

1. **Conversation in user's language, artifacts in English.**
   All persisted artifacts (skill files, CLAUDE.md entries) must
   be in English for cross-project portability. However, the
   conversation output (Steps 1-5.5) must use the user's
   preferred language (as declared in the project's CLAUDE.md or
   conversation context) so the user can review findings in their
   native language before deciding what to persist.

2. **Write principles, not project specifics.** Do not embed
   project-specific file paths, class names, framework versions,
   tool commands, or architecture details into persisted artifacts.
   Write the general principle and let the executor adapt to the
   local project context. A checklist item that only makes sense
   inside one codebase is not a lesson learned — it's a TODO.

3. **No hardcoded paths.** Never write absolute paths in
   artifacts. Use relative references within the skill directory
   or generic descriptions ("the project's architecture doc").

These constraints follow the **Portability** principle from the
official skill-building guide (see `references/skill-building-guide.md`):
_"Skills work identically across Claude.ai, Claude Code, and API.
Create a skill once and it works across all surfaces without
modification."_

## Banned phrases

These indicate vague reflection instead of root-cause analysis.
If you catch yourself writing one, delete it and write a concrete
observation instead.

- Apologetic openers: "I should have", "Thanks for pointing out",
  "You're right", "Let me reflect", "Great question"
- Vague filler: "would have been better", "insufficient context",
  "could have been more careful", "will pay more attention"
- Self-credit for user work: "we figured it out together",
  "with your help" — user interventions are autonomy gaps,
  not collaboration

## Step 1 — Turning Points Ledger

List every point where approach, design, or direction materially
changed. For each, tag the trigger source:

- **(A) User-triggered** — user provided a link, correction,
  keyword, or contradicting fact
- **(B) Self-discovered** — found without user prompting

Output the **A:B ratio** as a headline number. This ratio is the
primary measure of how autonomous the session was.

## Step 2 — 5 Whys with Evidence

For each (A) turning point, run 5 Whys. Each Why answer must cite
a concrete artifact — not an abstract explanation:

- A file that was not read (with path or name)
- A keyword that was not searched (exact string)
- A tool that was not invoked (tool name)
- An assumption that was not verified (where/how to verify it)
- A document that was available but ignored

If a Why cannot be grounded in a concrete artifact, it is wrong —
retry.

## Step 3 — Root Cause (single selection)

Pick exactly one primary root cause. Multi-select is not allowed.

a) Started proposing solutions before inventorying existing
   resources or constraints
b) Treated already-decided constraints as open for re-discussion
c) Shallow research — names and summaries only, no source code,
   docs, or worked examples
d) Scope too wide — too many unknowns at once, no narrowing
e) Ignored information the user already provided earlier in the
   conversation
f) Other (must be a concrete, non-abstract description)

## Step 4 — Counterfactual

Answer honestly: "If the user had not intervened at all, where
would I have stopped, with what wrong answer, and what downstream
damage would it have caused?"

Grounding rule: the counterfactual must be consistent with the A:B
ratio from Step 1. If A was high, do not claim "I would have
figured it out eventually."

## Step 5 — Trigger-Action Checklist (main deliverable)

Format each item exactly:

> When {specific trigger}, before {specific phase},
> must {specific action with tool / keyword / path / exit criterion}.

Requirements:

- Each {trigger} must be recognizable from future conversation
  patterns, not abstract intent
- Each {action} must specify: which tool, what keyword or path,
  what condition proves completion
- Banned action verbs: "consider", "think about", "be careful",
  "pay attention to", "keep in mind"
- Minimum 3 items, maximum 7
- **All items must be in English** (Design Constraint 1)
- **All items must be project-agnostic** (Design Constraint 2) —
  write "the project's build file" not "build.gradle.kts"

## Step 5.5 — Human Feedback (BLOCKING)

Present Steps 1-5 output to the user. Number every checklist
item. Then ask explicitly:

> What did you observe that I missed? Which numbered items
> should be persisted as skill updates?

**Rules:**
- STOP and WAIT for the user's response. Do not continue.
- User feedback overrides self-generated findings. If the user
  says "point 3 is wrong, the real issue was X" — replace it.
  Do not argue.
- The user may add items the assistant cannot self-diagnose
  (communication style, research breadth, domain assumptions).
  Convert these to trigger-action format, number them, and add
  to the checklist.
- **Selection model:** the user picks item numbers to persist
  (e.g., "1, 3, 5"). Unselected items are considered ignorable
  by the user — do NOT persist them. Only proceed to Step 6
  with the selected items.
- The user may also provide adjustment notes for selected items.
  Incorporate adjustments before persisting.
- If the user says "looks good" or "all" — persist everything.

**Why this step exists:** The assistant has blind spots only the
human can see. The A:B ratio already proves things were missed.
The selection model respects the user's judgment about what is
worth codifying vs. what is noise.

## Step 6 — Persist the Artifact

After incorporating human feedback, determine where to save the
checklist so it auto-triggers in future sessions.

### Concrete-to-principle translation

Steps 1-5.5 deliberately use concrete, project-specific language
(file names, tool commands, framework APIs) — this makes root
cause analysis precise. But persisted artifacts must be portable.

**Rule: discuss in specifics, persist in principles.**

Before writing any file, translate each item:
- Replace specific tool names with generic descriptions
  ("the build system's dependency resolver" not "dependencyInsight")
- Replace specific file paths with role descriptions
  ("the project's architecture doc" not "architecture.md")
- Replace specific flags or commands with the general pattern
  ("flags that control permission bypass" not
  "--dangerously-skip-permissions")
- Keep the trigger condition recognizable from conversation
  patterns, but strip implementation details from the action

If an item cannot be generalized without losing its meaning, it
belongs in the project's configuration file (CLAUDE.md), not in
a portable skill.

### Portability gate

Before writing any file, verify every item against the Design
Constraints at the top of this skill. Specifically:

| Check | Pass criterion |
|-------|---------------|
| Language | All text is in English |
| Project specifics | No file paths, class names, framework versions, or tool commands from the current project |
| Hardcoded paths | No absolute paths; only relative or generic references |
| Usefulness test | Would this item help someone working on a completely different codebase with a different tech stack? |

If any item fails the usefulness test, rewrite it as a general
principle. If it cannot be generalized, it belongs in the
project's CLAUDE.md as a project-specific note, not in a
portable skill.

### Location decision

| Question | Location |
|----------|----------|
| Useful in any project by this user? | `~/.claude/skills/` (personal) |
| Useful only in this project? | `.claude/skills/` (project) |
| Fewer than 5 items, project-scoped? | `CLAUDE.md` |

### Skill file quality (from official guide)

When creating or updating a skill file, follow the official
skill-building best practices in `references/skill-building-guide.md`:

- SKILL.md under 500 lines; move details to `references/`
- YAML frontmatter: `name` in kebab-case, `description` under
  1024 characters, includes WHAT + WHEN trigger phrases
- No XML angle brackets in frontmatter
- No README.md inside the skill folder
- Instructions are specific and actionable, not vague

Include the exact path and the phrase or command that will invoke
the persisted artifact.

## Step 7 — One-Line Verdict

Close with exactly one sentence:

> The single most expensive mistake this session was {X}, costing
> roughly {N} rounds of conversation; checklist saved at {path},
> auto-triggers on {phrase}.

No closing emoji, no "hope this helps", no "let me know if you
need anything else."

## Examples

**Example A — research task that circled**

User provided 4 links before the assistant discovered an existing
library. Step 1: A:B = 4:1. Step 3: (a). Step 5 produces:
"When asked to build on top of an existing framework, before
proposing any architecture, must list the framework's existing
primitives by searching the official docs and reading top 3
reference pages, exit criterion: can name three public APIs and
their signatures."

**Example B — coding task with repeated failures**

Step 1: A:B = 2:3. Step 3: (c). Step 5 produces:
"When writing code against a library, before first implementation,
must read the library's public API via source or generated docs
(not summary blogs), exit criterion: can name three public classes
and their constructors."

## Output budget

Steps 1-4 combined: one screen. Step 5: main deliverable — put
the most effort here. Steps 6-7: one-liners each.
