# Frontmatter Specification

Every SKILL.md begins with a YAML block delimited by `---`. The agent loads `name` + `description` first; only when the description matches the user's intent does the body load. Bad frontmatter = invisible skill.

## Limits

Two sources:

1. **Anthropic Complete Guide** — Anthropic's best-practices documentation. Some statements use command language ("Maximum X characters"), others advisory ("Keep under"). The Guide as a whole is documentation, not a normative spec.
2. **`validate-skill.py`** — this skill's local best-practice check. Not an upload acceptance gate.

| Field | Anthropic Complete Guide | `validate-skill.py` best-practice check |
|---|---|---|
| `name` | 1-64 chars, kebab-case | same — enforced |
| `description` | ≤ 1024 chars | same — enforced |
| `compatibility` | ≤ 500 chars | same — enforced |
| SKILL.md body | "Keep under 5,000 words" (advisory) | ≤ 500 lines — stricter; line count is mechanically verifiable, word count is not |

Passing the validator means: numbers in the Guide met, plus a more conservative line-count margin on body length.

## Required Fields

### `name`
- 1-64 characters.
- Kebab-case: lowercase letters, digits, single hyphens.
- No leading, trailing, or consecutive hyphens.
- Must EXACTLY match the parent folder name (case-sensitive).
- Must NOT start with `claude` or `anthropic` — those prefixes are reserved.

### `description`
- 1-1024 characters.
- Third-person imperative — never `I`, `me`, `my`, `mine`, `we`, `us`, `our`, `you`, `your`, `yours`.
- Contains BOTH:
  - **Positive trigger**: `Use when ...` followed by paraphrased phrases users actually say. Include trigger phrases in every language the skill should support.
  - **Negative trigger**: `Don't use for ...` (or `do not use`, `avoid`, `skip when`) listing the most likely false-positive domains.
- Contains no `<` or `>` characters. Angle brackets are forbidden because frontmatter is concatenated into the system prompt and angle brackets enable injection.
- Mention file types when relevant (e.g., "use when the user opens a `.proto` file").

## Optional Fields

### `license`
SPDX identifier (`MIT`, `Apache-2.0`, etc.). Required only when distributing publicly.

### `compatibility`
1-500 characters. Declares environment requirements: target product (Claude Code / claude.ai / API), required system packages, network access, MCP dependencies.

### `metadata`
Free-form key-value object. Recommended keys:

| Key | Purpose |
|---|---|
| `author` | Maintainer name or team |
| `version` | Semver string |
| `category` | `document-creation` / `workflow-automation` / `mcp-enhancement` |
| `pattern` | `sequential` / `multi-mcp` / `iterative-refinement` / `context-aware` / `domain-specific` |
| `mcp-server` | Paired MCP server name when applicable |
| `tags` | List of search tags |

### `allowed-tools`
Restricts the tool surface available while the skill runs. Two acceptable forms:

```yaml
allowed-tools: [Read, Edit, Bash]
```

```yaml
allowed-tools:
  - Read
  - Bash(python:*)
  - WebFetch
```

When the skill must invoke specific shell commands deterministically, prefer scoped Bash patterns (`Bash(git:status)`).

## Worked Example

```yaml
---
name: angular-vite-migrator
description: >
  Migrates Angular CLI projects from Webpack to Vite + esbuild. Replaces
  webpack plugins with Rollup equivalents, updates angular.json builders,
  and validates HMR. Use when the user wants to speed up Angular builds,
  switch the Angular builder to Vite, or remove webpack from an Angular
  workspace. Don't use for React, Vue, Svelte, or non-Angular Vite
  migrations, and don't use for plain Angular version upgrades.
license: MIT
compatibility: Requires Node 20+, Angular 17+, and Vite 5+. Network access required to install rollup plugins.
metadata:
  author: example-team
  version: 1.0.0
  category: workflow-automation
  pattern: sequential
allowed-tools:
  - Read
  - Edit
  - Bash
---
```

## Anti-Patterns

| Anti-pattern | Why it fails |
|---|---|
| `description: A skill for projects.` | Too vague — agent cannot route. |
| `description: <Helper for X>` | Angle brackets — injection vector + parser hostile. |
| `name: Claude-Helper` | Reserved prefix + non-kebab-case. |
| `description: I help you migrate ...` | First/second-person — fails STYLE ERROR. |
| Missing negative trigger | Skill over-triggers on adjacent domains. |
| Description repeating what's already in `name` | Wastes the 1024-char budget. |
