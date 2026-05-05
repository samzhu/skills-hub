# Skill Audit Checklist

Run before declaring a skill complete (AUTHOR mode) or when assessing existing skills (OPTIMIZE mode). Every item must Pass.

## 1. Metadata

- [ ] `name`: 1-64 chars, kebab-case (lowercase, digits, single hyphens).
- [ ] `name` does not start with `claude` or `anthropic`.
- [ ] `name` exactly matches the parent folder name.
- [ ] `description`: ≤ 1024 chars, ≥ 1 char.
- [ ] `description` contains a positive trigger ("Use when ...").
- [ ] `description` contains a negative trigger ("Don't use for ..." or equivalent).
- [ ] `description` is third-person — no `I`, `me`, `my`, `we`, `our`, `you`, `your`.
- [ ] `description` contains no `<` or `>` characters.
- [ ] `compatibility`, if present, is ≤ 500 chars.

## 2. Structure

- [ ] Folder contains exactly one `SKILL.md` (case-sensitive).
- [ ] Subdirectories limited to `scripts/`, `references/`, `assets/`.
- [ ] No empty subdirectories — every present subdirectory has content.
- [ ] All subdirectory contents are flat (one level deep).
- [ ] No `README.md`, `CHANGELOG.md`, `INSTALLATION.md`, `CONTRIBUTING.md`, or other human-targeted docs inside the folder.
- [ ] No nested skill folders.
- [ ] All paths inside SKILL.md are relative and use forward slashes.

## 3. Body

- [ ] SKILL.md body ≤ 500 lines.
- [ ] Body is third-person imperative ("Run X", "Read Y") — not "I will" or "you should".
- [ ] Numbered procedures with deterministic decision trees.
- [ ] Single canonical term per concept — no synonym drift.
- [ ] Critical instructions appear at the top, or under explicit `## Important` / `## Critical` headers.
- [ ] At least one positive example AND one negative example provided.
- [ ] Mode selection (if multi-mode) is the first decision the body asks the agent to make.

## 4. Scripts

- [ ] Each script is single-purpose — no library code.
- [ ] CLI-style: accepts arguments, writes stdout on success, stderr on failure.
- [ ] Exit code is non-zero on failure (allows agent self-correction).
- [ ] Fragile / repetitive operations (regex, parsing, validation, boilerplate) are scripted, not prosed.
- [ ] Scripts are referenced explicitly from SKILL.md by relative path.

## 5. References & Assets

- [ ] Bulk schemas, templates, or rule sets that would balloon SKILL.md live in `references/` or `assets/`.
- [ ] Each `references/*.md` is read just-in-time — SKILL.md commands the agent to read it only when the relevant step is reached.
- [ ] Templates in `assets/` are minimal placeholders, not full implementations.

## 6. Error Handling & Testing

- [ ] `## Error Handling` section present in SKILL.md.
- [ ] Each entry follows: condition → cause → recovery.
- [ ] Validation step explicitly named (which script, which exit codes, what the agent does on failure).
- [ ] Discovery validation (Layer 1 of testing protocol) passed: paraphrased trigger prompts route correctly.
- [ ] Negative-trigger prompts do NOT activate the skill in Layer 1 testing.

## 7. MCP Integration (only if category is `mcp-enhancement` or skill invokes an MCP)

- [ ] `metadata.mcp-server` declared.
- [ ] `compatibility` lists the MCP server requirement and auth method.
- [ ] `## Error Handling` covers all five required MCP failure modes from `references/mcp-integration.md`: connection / auth / tool-not-found / rate-limit / schema-drift.
- [ ] No silent retry on auth failure.
- [ ] No tool-name guessing on `tool not found`.
- [ ] For multi-MCP: phase-to-MCP mapping stated; inter-phase data hand-off documented; validation between phases.

## 8. Distribution Readiness (only if shipping cross-host)

- [ ] Folder zips successfully; zip top-level folder name matches `name`.
- [ ] `metadata.version` set and bumped on each release.
- [ ] `license` declared.
- [ ] Repository `README.md` (for humans) lives OUTSIDE the skill folder.
- [ ] Tested on at least one target host other than the development host.
