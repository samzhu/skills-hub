---
name: takeover
description: >
  Reads and internalizes a handover note left by a previous session, then
  archives it. Use at the start of a new session to resume work seamlessly.
  Use when the user says "takeover", "接班", "pick up where we left off",
  "resume handover", "continue from last session", "read handover",
  "what was I working on", or at the beginning of a session when
  .claude/handovers/HANDOVER.md exists.
  Pair with /handover which creates the note.
allowed-tools:
  - Read
  - Glob
  - Bash
metadata:
  author: howielab
  version: 2.0.0
  category: workflow-automation
  tags: [session-management, context-preservation, cross-agent]
---

# Takeover — Read the Shift Note and Resume

## Role: Incoming Shift Engineer

You are the engineer starting a new shift. Read the handover note, absorb
the context, archive the note, and present a clear action plan.

## Contract

```
Input:  .claude/handovers/HANDOVER.md
Output: Archived note + briefing to user + ready to work
Valid:  User confirms readiness to continue
Pair:   /handover (creates the note this skill reads)
```

---

## Step 1: Find the handover note

Check if `.claude/handovers/HANDOVER.md` exists.

**If it exists** → continue to Step 2.

**If it does NOT exist** → check `.claude/handovers/archive/` for any
archived notes. If archives exist, list them:

```
No active handover found. Archived notes:

  2025-07-20  api-migration
  2025-07-19  auth-refactor

These were already consumed. Start fresh or ask for context.
```

If no archives either → print:
"No handover notes found. Nothing to take over."

Stop here.

## Step 2: Read and internalize

Read `.claude/handovers/HANDOVER.md` in full.

Parse both layers:
- **Layer 1** (Portable Summary): topic, status, completed work, decisions,
  blockers, next steps, lessons learned, session summary
- **Layer 2** (Environment Details): branch, uncommitted changes, test
  status, key files

## Step 3: Archive the note

Move the consumed handover note to the archive:

```bash
mkdir -p .claude/handovers/archive
```

Derive archive filename from the frontmatter: `{date}-{topic-slug}.md`
- Use `date` from YAML frontmatter
- Use `topic` from frontmatter, converted to lowercase-hyphen slug

```bash
mv .claude/handovers/HANDOVER.md .claude/handovers/archive/{date}-{topic-slug}.md
```

## Step 4: Briefing

Present the handover to the user in this exact structure:

```
## Takeover Briefing

**Topic**: {topic}
**Status**: {status}
**Session type**: {session_type}

### What was done
{bullet list from completed}

### Key decisions
{each decision with rationale — keep it brief}

### Current blockers
{blockers with attempted approaches, or "(none)"}

### Action plan
{next_steps as numbered list, ready to execute}

### Watch out for
{lessons_learned that affect upcoming work}
```

If Layer 2 is present and the environment matches (same repo), also show:

```
### Environment
- Branch: `{branch}`
- Uncommitted changes: {count} files
- Last test run: {test_status}
```

## Step 5: Offer to continue

End with:

"Ready to continue from step 1 of the action plan, or do you want to
adjust the plan first?"

---

## Anti-Patterns

- Do NOT re-read the entire codebase — trust the handover note
- Do NOT silently ignore blockers — surface them prominently
- Do NOT skip the archive step — a consumed note must not be re-consumed
- Do NOT start working before the user confirms — always brief first
