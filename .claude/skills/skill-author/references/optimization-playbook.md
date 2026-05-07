# Skill Optimization Playbook

Audit findings → canonical fixes. Use during OPTIMIZE mode after running `validate-skill.py --skill-dir <path>`.

Each entry: validator code or symptom → severity → fix recipe.

## BLOCKER findings (skill is invisible or unsafe)

### `NAME ERROR` — name violates kebab-case or length
**Fix**: rewrite name as kebab-case, 1-64 chars, lowercase letters / digits / single hyphens. Rename folder + frontmatter `name` to match.

### `NAME ERROR` — frontmatter name does not match folder name
**Fix**: rename folder to match `name`, OR change `name` to match folder. The two MUST be identical.

### `NAME ERROR` — name starts with `claude` or `anthropic`
**Fix**: choose a non-reserved identifier. Add a domain qualifier instead (e.g., `claude-helper` → `repo-bootstrapper`).

### `DESCRIPTION ERROR` — contains `<` or `>`
**Fix**: rewrite description without angle brackets. Use words instead of XML-like tags. Frontmatter is concatenated into the system prompt; angle brackets are an injection vector.

### `DESCRIPTION ERROR` — over 1024 chars
**Fix**: cut filler words. Move detailed scoping notes to `references/`. Keep only what the agent needs to route correctly: capability + positive trigger + negative trigger.

### `DESCRIPTION ERROR` — missing positive trigger
**Fix**: add `Use when <paraphrase 1>, <paraphrase 2>, <paraphrase 3>.` Include phrases users actually say, in every language the skill supports.

### `DESCRIPTION ERROR` — missing negative trigger
**Fix**: add `Don't use for <out-of-scope 1>, <out-of-scope 2>.` List the most likely false-positive domains. Without this the skill over-triggers.

### `STYLE ERROR` — first/second-person pronouns
**Fix**: rewrite description in third-person imperative. "I help migrate ..." → "Migrates ...". "You can use this ..." → "Use when ...".

## MAJOR findings (skill works but degrades)

### `LENGTH ERROR` — SKILL.md body > 500 lines
**Fix**:
1. Identify the largest block — usually a schema, template, or rule list.
2. Move verbatim to `references/<topic>.md` (kebab-case filename, descriptive).
3. Replace the block in SKILL.md with a single instruction: "Read `references/<topic>.md` to identify <X>."
4. Repeat until SKILL.md ≤ 500 lines.

### `STRUCTURE ERROR` — unexpected subdirectory
**Fix**: only `scripts/`, `references/`, `assets/` are allowed. Move content into one of those, or delete it. Rename to fit the canonical purpose:
- Executable code → `scripts/`
- Documentation read by the agent → `references/`
- Templates / static files copied into output → `assets/`

### `STRUCTURE ERROR` — nested files inside subdirectories
**Fix**: flatten. Rename files with topic prefixes. Example: `references/db/v1/schema.md` → `references/db-v1-schema.md`.

### `STRUCTURE ERROR` — empty subdirectory
**Fix**: delete the empty subdirectory. Empty subdirectories are noise.

### `STRUCTURE ERROR` — README.md / CHANGELOG.md / INSTALLATION.md inside skill folder
**Fix**: delete from skill folder. Human-facing docs go in the parent repo, not in the skill. If the doc is genuinely needed by the agent, rewrite as `references/<topic>.md` in third-person imperative.

### Prose instructions instead of numbered steps
**Fix**: rewrite as numbered procedures, third-person imperative, with explicit decision points. Replace "you might want to" with "If <condition>, run <command>. Otherwise, skip to Step N."

### Fragile logic in prose (regex, parsing, validation)
**Fix**: extract a script under `scripts/`. The script must accept CLI args, print success info to stdout, print diagnostics to stderr, and exit non-zero on failure. SKILL.md then says: "Run `python3 scripts/<name>.py --arg X`. If exit code is non-zero, follow stderr to self-correct."

### Missing `## Error Handling` section
**Fix**: add `## Error Handling` enumerating each script's failure modes and the recovery the agent should attempt before asking the user. Format: condition → cause → recovery.

### Hardcoded path into Claude Code sensitive directory
**Symptom**: SKILL.md instructs the agent to write outputs to `.claude/<sub>/`, `.git/<sub>/`, `.vscode/<sub>/`, `.idea/<sub>/`, or `.husky/<sub>/` (e.g. `.claude/handovers/HANDOVER.md`, `.claude/progress/<log>.md`).
**Why it matters**: Claude Code treats `.git/`, `.claude/`, `.vscode/`, `.idea/`, `.husky/` as hardcoded sensitive files. Writes into these directories prompt the user for permission **even when** `--dangerously-skip-permissions` (bypassPermissions) or `skipDangerousModePermissionPrompt: true` is set — this is an independent safety guard, separate from the normal allow / deny flow, designed to block prompt-injection attacks that try to mutate the agent's own hooks or settings to escape the sandbox. Skills that default outputs into these paths interrupt unattended workflows (cron loops, long sessions) and force users to maintain an ever-growing per-file allow list to suppress prompts.
**Fix**: pick any path that is NOT one of the five sensitive directories. The skill author / user decides where outputs belong based on project layout — common choices include `docs/<area>/`, repo root, or any other non-dot directory, but no path is mandated. Document the chosen path in SKILL.md `## Procedures` and in the contract block (Output: ...). Only retain a sensitive path when the user explicitly requested it (e.g. they want the file gitignored under `.claude/`); record that override decision in SKILL.md so future audits know it was deliberate.

## MINOR findings (style)

### Synonym drift across SKILL.md
**Symptom**: same concept referred to as "module", "component", "widget" in different sections.
**Fix**: pick one canonical term and search-replace. Document the choice if the domain has multiple valid terms.

### Description repeats the name
**Symptom**: `name: angular-vite-migrator`, description starts with "Angular Vite Migrator skill ...".
**Fix**: drop the redundant phrase. Use the budget for triggers.

### Missing examples
**Fix**: add `## Examples` section with at least one positive (should trigger and succeed) and one negative (should NOT trigger). Examples teach pattern matching faster than rules.

### Critical instructions buried mid-body
**Fix**: move to top, OR add an explicit `## Important` / `## Critical` header. The agent reads top-to-bottom and may stop at the first apparent answer.

## MCP-specific findings

For `mcp-enhancement` skills only. Cross-reference `references/mcp-integration.md` for required failure modes.

### Missing MCP failure-mode coverage
**Symptom**: `## Error Handling` lacks one or more of: connection / auth / tool-not-found / rate-limit / schema-drift.
**Fix**: append the missing modes per the templates in `references/mcp-integration.md` § Required MCP Failure Modes.

### Silent retry on auth failure
**Symptom**: SKILL.md instructs the agent to "retry on 401" or similar.
**Fix**: replace with surface-error-verbatim + ask user to refresh credentials. Auth failures are user-actionable; retries hide them.

### Tool-name guessing on `tool not found`
**Symptom**: SKILL.md instructs "if tool not found, try alternate name".
**Fix**: remove the fallback. Fail loudly with the original error. Tool discovery belongs in procedures, not error recovery.

### Missing phase-to-MCP mapping (multi-MCP)
**Symptom**: procedures call multiple MCPs without stating which phase uses which server.
**Fix**: prepend a phase-to-MCP table to `## Procedures`. Document the data hand-off (which fields cross MCP boundaries) and add inter-phase validation.

## Optimization Workflow

Per Step O4 of SKILL.md, present findings as a single diagnosis report grouped by severity:

```
BLOCKER (N):
  - <file>:<line> — <finding> → <proposed fix> (~<N> lines changed)
MAJOR (N):
  - ...
MINOR (N):
  - ...
```

Wait for user approval before editing. After applying, re-run `validate-skill.py --skill-dir <path>` and walk `references/checklist.md` again.
