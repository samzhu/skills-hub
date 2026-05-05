---
name: <kebab-case-name>
description: >
  <One sentence — what the skill does in third-person imperative.> Use when
  <paraphrased trigger 1>, <paraphrased trigger 2>, <paraphrased trigger 3>.
  Don't use for <out-of-scope 1>, <out-of-scope 2>.
metadata:
  author: <name>
  version: 0.1.0
  category: <document-creation | workflow-automation | mcp-enhancement>
  pattern: <sequential | multi-mcp | iterative-refinement | context-aware | domain-specific>
allowed-tools:
  - Read
  - Edit
  - Bash
---

# <Skill Title>

<One-paragraph capability summary in third-person. State what the skill does, what it produces, and the boundary of its scope.>

## Procedures

### Step 1: <Action phase>
1. <Imperative instruction.>
2. <Reference an asset, e.g., "Read `assets/<file>` to structure the output.">

### Step 2: <Action phase>
1. <Decision tree, e.g., "If <condition>, run `scripts/<name>.py`. Otherwise, skip to Step 3.">
2. <Just-in-time read: "Read `references/<topic>.md` to identify <X>.">
3. Run `python3 scripts/<name>.py --arg X` to <deterministic action>. If exit code is non-zero, follow stderr to self-correct.

### Step 3: <Validation phase>
1. <Verification step.>
2. <Report outcome to the user.>

## Examples

**Positive — should trigger and succeed**:
> User: "<realistic in-scope prompt>"
> Skill: <expected high-level behavior>

**Negative — should NOT trigger**:
> User: "<similar-sounding but out-of-scope prompt>"
> Skill: defer to other capabilities.

## Error Handling

* If `scripts/<name>.py` exits non-zero with `<error code>`: <cause>. <Recovery — re-run with corrected arg, or escalate to user.>
* If `references/<topic>.md` lacks the expected entry: <cause>. <Recovery.>
* If <runtime condition>: <cause>. <Recovery.>
