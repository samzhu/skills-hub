---
name: skill-author
description: >
  Authors new agent skills and optimizes existing ones to the agentskills.io
  spec. Generates compliant SKILL.md with progressive disclosure, validates
  frontmatter for security and discoverability, audits legacy skills against
  a structural checklist, and proposes targeted fixes. Use when the user
  asks to create a skill, build a skill for X, scaffold a new skill, audit
  an existing skill, optimize a skill, review skill structure, or says
  建立新 skill, 做一個 skill, 優化既有 skill, 稽核 skill. Don't use for
  general prompt engineering, MCP server implementation, or human-facing
  documentation that lives outside a skill folder.
metadata:
  author: skills-hub
  version: 1.0.0
  category: workflow-automation
  pattern: iterative-refinement
allowed-tools:
  - Read
  - Write
  - Edit
  - Bash
  - Glob
  - Grep
---

# Skill Author

Authors agent skills that follow the agentskills.io specification end-to-end (folder layout, frontmatter, progressive disclosure, deterministic scripting, error handling) and audits existing skills against the same spec.

## Mode Selection

Determine the mode from the user request before any other action.

* **AUTHOR mode** — user requests a new skill. Triggers: "create / build / scaffold / make a skill", "建立 skill", "做一個 skill". Execute Steps A1 → A6.
* **OPTIMIZE mode** — user references an existing skill folder or asks to review/improve it. Triggers: "audit / review / optimize / improve / fix this skill", "優化 / 稽核 / 改 skill". Execute Steps O1 → O5.

If the request is ambiguous, ask the user which mode applies before continuing.

---

## AUTHOR Mode

### Step A1: Capture Intent
1. Extract from the user request four artifacts: target capability (one sentence, third-person verb + domain object), 2-3 concrete use cases, 1-2 negative scenarios where the skill must NOT trigger, and explicit success criteria (what observable outcome means the skill worked — e.g., "triggers on phrases like X, produces output of shape Y, completes without user correction in N turns").
2. If any of the four is missing or vague, ask the user before continuing.
3. Pick a kebab-case `name`: 1-64 chars, lowercase letters, digits, single hyphens. Must NOT start with `claude` or `anthropic`. Must not collide with any existing folder under `.claude/skills/`.

### Step A2: Draft Frontmatter
1. Read `references/frontmatter-spec.md` to identify required and optional fields.
2. Draft `name` and `description`. Description must be ≤ 1024 chars, third-person, contain BOTH a positive trigger ("Use when ...") and a negative trigger ("Don't use for ..."), and contain no `<` or `>` characters.
3. Add optional fields only when justified — `license` (public distribution), `compatibility` (environment requirements), `metadata` (author / version / category / pattern), `allowed-tools` (tool restriction).
4. Run `python3 scripts/validate-skill.py --name "<name>" --description "<description>"`. If exit code is non-zero, rewrite per stderr output and re-run until clean.

### Step A3: Initialize Directory
1. Create `<name>/` at the target location (default `.claude/skills/<name>/`).
2. Create only the subdirectories needed for actual content: `scripts/`, `references/`, `assets/`. Do NOT create empty subdirectories.
3. Do NOT create `README.md`, `CHANGELOG.md`, `INSTALLATION.md`, or any human-targeted file inside the skill folder.

### Step A4: Author SKILL.md Body
1. Copy `assets/skill-template.md` as the starting point.
2. The body must contain, in order: capability summary, `## Procedures` (numbered steps in third-person imperative), `## Examples` (≥ 1 positive + 1 negative), `## Error Handling` (condition → cause → recovery).
3. Keep total file ≤ 500 lines. If logic exceeds 500 lines, offload bulk content to `references/<topic>.md` and replace it with a one-line just-in-time read instruction such as "Read `references/<topic>.md` to identify <X>."
4. Use forward slashes and relative paths only. Use one canonical term per concept — no synonym drift.
5. Read `references/design-patterns.md` to confirm the chosen category and pattern fit the skill's actual workflow, and to verify content is placed at the correct progressive-disclosure level.
6. If category is `mcp-enhancement` or any procedure invokes an MCP server, additionally read `references/mcp-integration.md` and ensure the required MCP failure modes are covered in `## Error Handling`.

### Step A5: Bundle Determinism
1. Identify operations that are fragile or repetitive (regex parsing, validation, boilerplate generation).
2. For each, place a single-purpose script in `scripts/<topic>.py` (or `.sh` / `.mjs`) that accepts CLI arguments, writes success info to stdout, writes diagnostics to stderr, and exits non-zero on failure so the agent can self-correct.
3. Do NOT bundle library code or general utilities — scripts must be small and skill-specific.

### Step A6: Final Audit
1. Run `python3 scripts/validate-skill.py --skill-dir <path>`. All checks must pass.
2. Read `references/checklist.md` and verify every item is Pass.
3. Run discovery validation per `references/testing-protocol.md` § Layer 1 — paste the new frontmatter into a fresh LLM context, confirm trigger phrases route correctly and similar-but-out-of-scope phrases do not.
4. If the skill targets cross-host distribution (claude.ai upload, Anthropic API, organization deploy), read `references/distribution.md` and walk its pre-distribution checklist.
5. Report the created skill path and the checklist results to the user.

---

## OPTIMIZE Mode

### Step O1: Locate and Read
1. Resolve the target skill folder from the user request. If multiple match, list candidates and ask the user to pick.
2. List the folder contents: SKILL.md, subdirectories, any extra files. Read SKILL.md in full.

### Step O2: Run Structural Checks
1. Execute `python3 scripts/validate-skill.py --skill-dir <path>`. Capture every violation reported on stderr.
2. Cross-reference against `references/checklist.md` § 1-6. Mark each item as Pass / Fail / N/A.

### Step O3: Diagnose
1. Read `references/optimization-playbook.md` to map each Fail to a canonical fix.
2. Classify findings by severity: BLOCKER (skill is invisible or unsafe — wrong frontmatter, reserved name prefix, XML angle brackets), MAJOR (skill works but degrades — over-long SKILL.md, prose instructions, missing error handling), MINOR (style — synonym drift, missing examples).
3. If the skill is `mcp-enhancement` or invokes an MCP, additionally check that the failure modes in `references/mcp-integration.md` are all covered.

### Step O4: Propose Diff
1. Write a single diagnosis report to the user listing every finding: location → severity → proposed fix → estimated lines changed.
2. Wait for user approval before editing files. Do NOT edit silently — skill optimization can change trigger behavior or break callers.

### Step O5: Apply and Re-validate
1. Apply approved fixes file by file. After each file, re-read it to confirm the edit landed correctly.
2. Re-run `python3 scripts/validate-skill.py --skill-dir <path>` and walk the checklist again.
3. Report the final state: BLOCKERS resolved, MAJORS resolved, remaining MINORS, and any deferred items the user declined.

---

## Examples

**Positive — AUTHOR mode**:
> User: "建立一個會幫我把 Angular 專案從 Webpack 遷移到 Vite 的 skill"
> Skill: capture intent → name `angular-vite-migrator` → draft frontmatter with positive triggers ("migrate Angular to Vite", "switch Angular builder to esbuild") and negative ("React, Vue, non-Angular projects") → run validator → scaffold folder under `.claude/skills/` → author SKILL.md per template → bundle a `scripts/check-builders.py` for fragile angular.json parsing → run final audit and report.

**Positive — OPTIMIZE mode**:
> User: "幫我審查 .claude/skills/foo/ 這個 skill 是否符合最佳實踐"
> Skill: read folder → run validator → walk checklist → produce diagnosis report (e.g., "BLOCKER: description contains `<tag>` angle brackets; MAJOR: SKILL.md 820 lines, move §3 to references/; MINOR: synonyms 'module' and 'component' both appear") → wait for approval → apply fixes → re-validate.

**Negative — should NOT trigger**:
> User: "幫我寫一個 README 介紹我的 npm package"
> Skill: defer — this is human-facing documentation, not an agent skill.

> User: "幫我設計一個 MCP server 來連 Slack"
> Skill: defer — MCP server implementation is out of scope; only skill packaging around an MCP is in scope.

---

## Error Handling

* **`validate-skill.py` exits with `NAME ERROR`** → name violates kebab-case, length, or starts with `claude` / `anthropic`. Rewrite per `references/frontmatter-spec.md` § name and re-run.
* **`validate-skill.py` exits with `DESCRIPTION ERROR`** → description over 1024 chars, missing positive or negative trigger, or contains `<` / `>`. Rewrite per `references/frontmatter-spec.md` § description and re-run.
* **`validate-skill.py` exits with `STYLE ERROR`** → first/second-person pronouns detected. Rewrite description in third-person imperative.
* **`validate-skill.py` exits with `STRUCTURE ERROR`** → unexpected subdirectory, nested files, or forbidden human-doc files. Apply fix from `references/optimization-playbook.md` § structure findings.
* **`validate-skill.py` exits with `LENGTH ERROR`** → SKILL.md body exceeds 500 lines. Identify the largest block, move to `references/<topic>.md`, replace with a JiT read instruction. Re-run validator.
* **Discovery validation false-positive on unrelated prompt** → strengthen negative triggers in description; add domain qualifiers. Re-run Layer 1 testing.
* **Discovery validation false-negative on intended prompt** → add the missing user phrasing as a positive trigger. Re-run Layer 1 testing.
* **OPTIMIZE mode finds skill missing `scripts/` for fragile logic** → propose script extraction in Step O4; never auto-create executable code without approval.
