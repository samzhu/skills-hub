---
topic: "{topic}"
session_type: "{development|debug|research|refactor|planning}"
status: "{in_progress|blocked|completed|handed_off}"
date: "{YYYY-MM-DD}"
---

# Handover: {topic}

## Layer 1 — Portable Summary

> This section is readable by any agent (Claude, Gemini, Copilot, human).
> It contains everything needed to understand and resume this work.

### Completed

- {What was accomplished — concrete, verifiable items}

### Decisions

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| {what was decided} | {reasoning} | {other options considered and why not} |

### Blockers

<!-- Remove this section if status is "completed" -->

**{Blocker description}**

| Attempt | Result | Why It Failed |
|---------|--------|---------------|
| {what was tried} | {what happened} | {hypothesis for failure} |

Current hypothesis: {best guess for resolution}

### Next Steps

1. {First action — specific enough to act on immediately}
2. {Second action}
3. {Third action}

### Lessons Learned

- {Non-obvious discovery that saves the next person time}
- {Framework quirk, undocumented behavior, etc.}

### Session Summary

{3-5 sentence narrative: what the session set out to do, key turning
points, where it ended up. Written for someone with zero context.}

---

## Layer 2 — Environment Details

> This section is for resuming in the same repo/machine.
> Skip this when handing off to a different environment.

| Property | Value |
|----------|-------|
| Branch | `{git branch}` |
| Working Directory | `{absolute path}` |
| Test Status | {last known results} |

### Uncommitted Changes

```
{git status --short output}
```

### Recent Commits

```
{git log --oneline -5 output}
```

### Key Files

- `{path/to/modified/file}` — {what was changed and why}
- `{path/to/important/reference}` — {why it matters}
