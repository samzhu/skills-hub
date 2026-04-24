# Spec File Template

File: `docs/grimo/specs/YYYY-MM-DD-<spec-id>-<topic>.md`

```markdown
# <spec-id>: [Topic]

> Spec: <spec-id> | Size: XS/S/M(N) | Status: ⏳ Design
> Date: YYYY-MM-DD

---

## 1. Goal

[One paragraph describing what this spec validates/builds]

## 2. Approach

| Approach | Chosen | Rationale |
|----------|--------|-----------|
| A: ... | yes | ... |
| B: ... | no | ... |

## 3. SBE Acceptance Criteria

AC-1: [title]
Given [precondition]
When  [action]
Then  [expected result]

AC-2: ...

## 4. Interface / API Design

[Code signatures, struct definitions, key patterns]

## 5. File Plan

| File | Action | Description |
|------|--------|-------------|
| path/to/file | new/modify | ... |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->

## 6. Task Plan

POC: required / not required — [rationale]

| # | Task | AC | Status |
|---|------|----|--------|
| T01 | ... | AC-1 | pending |

Execution order: T01 → T02 → ...

### POC Findings
<!-- Added after POC validation, if POC was required -->
- Verified packages: [package@version]
- Correct API patterns: [code snippets]
- Gotchas: [issues discovered]

## 7. Implementation Results

### Verification
- Tests: pass/fail
- Lint: pass/fail
- Format: pass/fail

### Key Findings
[What was learned during implementation — API gotchas, patterns, etc.]

### Correct Usage Patterns
[Code snippets showing the RIGHT way to use the API, based on actual
implementation experience. This is the most valuable part — future
specs reference this.]

### AC Results

| AC | Result | Notes |
|----|--------|-------|
| AC-1 | pass | ... |
| AC-2 | pass | ... |
```
