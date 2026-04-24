# Task Result Format

Add Result section to the task file after implementation.

## PASS

```markdown
## Status
PASS

## Result
Date: YYYY-MM-DD
Test: [test_function_name] ([test file path])
Files changed:
- [file path] (new/modified)
Notes: —
```

## FAIL

```markdown
## Status
FAIL

## Result
Date: YYYY-MM-DD
Reason: [why it failed]
Suggestion: [e.g., revisit design, split task, clarify requirement]
```
