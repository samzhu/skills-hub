---
name: handover
description: >
  Generates a structured handover note so any agent or human can resume work
  without context loss. Writes a single HANDOVER.md with two layers: a portable
  summary (Layer 1) and environment details (Layer 2).
  Use when the user says "handover", "交班", "換手", "shift change", "save progress",
  "wrap up session", "I need to switch", "pass the baton", "先到這裡", "存檔",
  "context is getting long", or before closing a long session.
  Pair with /takeover to resume in a new session.
allowed-tools:
  - Read
  - Glob
  - Grep
  - Bash
  - Write
metadata:
  author: howielab
  version: 2.0.0
  category: workflow-automation
  tags: [session-management, context-preservation, cross-agent]
---

# Handover — Write the Shift Note

## Role: Outgoing Shift Engineer

You are the engineer ending a shift. Write a handover note that lets the
next person — human, Claude, Gemini, Copilot, or any agent — pick up
exactly where you left off. Capture what matters, skip what can be
re-derived.

## Current Environment

- Branch: !`git branch --show-current 2>/dev/null || echo "(not a git repo)"`
- Uncommitted: !`git status --short 2>/dev/null | head -20 || echo "(no git)"`
- Recent commits: !`git log --oneline -5 2>/dev/null || echo "(no git)"`

## Contract

```
Input:  Current conversation context
Output: .claude/handovers/HANDOVER.md (single active file, always overwritten)
Valid:  Handover note written and confirmed
Pair:   /takeover (reads this file and archives it)
```

---

## Step 1: Classify session type

Ask yourself (do NOT ask the user) — what kind of work was this session?

| Type | Heuristic | Depth focus |
|------|-----------|-------------|
| `development` | Spec-driven coding, feature work | Progress delta — design docs are the source of truth |
| `debug` | Investigating a bug, fixing errors | **Detailed `attempted_approaches`** — what was tried, why it failed, exact errors |
| `research` | Exploring options, reading docs, comparing | Findings + unverified hypotheses + sources visited |
| `refactor` | Restructuring without behavior change | Change scope + invariants that must hold |
| `planning` | Architecture, design, roadmap discussions | Decisions made + options rejected + open questions |

## Step 2: Gather information

Scan the full conversation history. Extract the following. Do NOT
fabricate — if you don't have data for a field, write `(none)`.

**Layer 1 — Universal (any agent or human can read this):**

- `topic`: One-line description of what this session was about
- `session_type`: From Step 1
- `status`: `in_progress` | `blocked` | `completed`
- `completed`: Bullet list of what was accomplished (concrete, verifiable)
- `decisions`: Each with `what`, `why`, and `alternatives_rejected`
- `blockers`: Each with `description`, `attempted` approaches (with results), and `hypothesis`
- `next_steps`: Ordered list — specific enough to act on immediately
- `lessons_learned`: Non-obvious discoveries that save the next person time
- `conversation_summary`: 3-5 sentence narrative of the session arc

**Layer 2 — Environment-specific (same machine / same repo):**

- `branch`: Current git branch
- `working_dir`: Absolute path
- `uncommitted_changes`: Files modified but not committed
- `recent_commits`: Last 5 commits on current branch
- `test_status`: Last known test results
- `related_files`: Key files read or modified this session

## Step 3: Apply depth rules by session type

**development**: `completed` and `next_steps` detailed; `conversation_summary`
brief; include file paths for all modified files.

**debug**: `blockers.attempted` is the MOST IMPORTANT section. For each
failed approach, record: what was tried (exact command or code change),
what happened (exact error message), why it didn't work (root cause
hypothesis). This prevents the next person from repeating dead ends.

**research**: `lessons_learned` is critical. Record: sources consulted
(URLs, doc paths), key findings with evidence, unverified hypotheses
(mark as `(unverified)`), dead ends.

**refactor**: Document invariants that must NOT change: public API
contracts, test behavior, performance characteristics. Add under
`lessons_learned` as "INVARIANT: ...".

**planning**: Focus on `decisions`. The `why` and `alternatives_rejected`
are more valuable than the decision itself.

## Step 4: Write the handover note

Read the template at `${CLAUDE_SKILL_DIR}/template.md`.

Fill in all fields using the template structure exactly.

Write to: `.claude/handovers/HANDOVER.md`

Create the directory if it doesn't exist. If `HANDOVER.md` already exists,
overwrite it (the previous one was not consumed — this is intentional).

## Step 5: Confirm

Print:

```
Handover saved: .claude/handovers/HANDOVER.md

To resume in a new session, run:  /takeover

Layer 1 (portable summary) is readable by any agent or human.
Layer 2 (environment details) is for this repo/machine.
```

---

## Anti-Patterns

- Do NOT dump the entire conversation — summarize, don't transcribe
- Do NOT include code blocks longer than 10 lines — reference file paths instead
- Do NOT invent information — if uncertain, mark with `(unverified)`
- Do NOT skip Layer 2 — environment context saves real time on resume
- Do NOT ask the user what to include — analyze the conversation yourself
