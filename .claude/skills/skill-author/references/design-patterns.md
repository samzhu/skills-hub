# Skill Design Patterns

Read this to pick a category and pattern before authoring the body.

## Three-Level Progressive Disclosure

The Anthropic Complete Guide describes skill loading as three levels. Designing for these levels minimizes token consumption and prevents over-eager loading.

### Level 1 — Frontmatter
Always loaded into the agent's system prompt. The agent uses `name` + `description` to decide whether to advance to Level 2. Every byte costs tokens for every request — keep this minimal.

### Level 2 — SKILL.md body
Loaded when the agent decides the skill is relevant. Contains procedures and high-level decision logic. Bulk content belongs at Level 3, not here.

### Level 3 — Linked files (`scripts/`, `references/`, `assets/`)
Loaded only when SKILL.md explicitly commands the agent to read them. Use just-in-time references such as: `Read references/api-spec.md to identify the correct endpoint.` The agent will not see Level 3 files unless instructed.

### Level → Content Mapping

| Content type | Level |
|---|---|
| Skill identity, triggers, optional metadata | 1 (frontmatter) |
| Numbered procedures, decision trees, error handling overview | 2 (SKILL.md body) |
| Schemas, large rule lists, full templates, executable validation | 3 (subdirectories) |

Misplacing content is the single largest cause of bloated skills. When SKILL.md exceeds the line budget, the fix is almost always to move a Level-3 candidate that landed at Level 2.

## Three Categories

| Category | When | Body shape |
|---|---|---|
| **document-creation** | Produces consistent artifacts (docs, slides, code, designs). No external tools required. | Heavy `assets/` (templates, schemas), light `scripts/`. |
| **workflow-automation** | Multi-step process with validation gates, templates, iterative refinement. | Numbered procedures + decision trees + validation scripts. |
| **mcp-enhancement** | Embeds domain expertise on top of an MCP server's tool access. | Coordinates MCP calls, handles MCP errors, declares `compatibility` and `metadata.mcp-server`. |

Pick exactly one. The category tells the agent what kind of help to expect and informs the body shape.

## Five Common Patterns

### 1. Sequential workflow orchestration
Numbered chronological steps with explicit dependencies, validation at each gate, and rollback instructions. Use when the workflow is a strict pipeline (e.g., migration, deployment, refactor).

### 2. Multi-MCP coordination
Phase-separated calls across multiple MCP servers, data passed between phases, centralized error recovery. Use when one capability requires composing several MCP servers.

### 3. Iterative refinement
Draft → quality check → refinement loop → finalize. Define explicit stop criteria — without them, agents loop forever. Use for content generation, code review, design iteration.

### 4. Context-aware tool selection
Decision tree picks among multiple tools by context, with documented fallbacks. Use when the same outcome has multiple valid paths (e.g., "if the repo has Bun, use `bun install`; else `npm install`").

### 5. Domain-specific intelligence
Captures rules / compliance / governance not encoded in tools — applied before action. Use when business rules must gate tool execution (e.g., financial compliance, security policy).

## Composability + Portability

Two non-negotiable design principles, regardless of category or pattern.

### Composability
Do not assume the skill is the only capability available. The skill must:
- Cooperate with other skills loaded simultaneously.
- Not monopolize the agent's attention with self-referential instructions.
- Defer to more-specific skills when their triggers apply.

### Portability
The same SKILL.md must work across Claude Code, claude.ai, and the Anthropic API.
- Avoid host-specific commands without guards.
- Declare environment dependencies via `compatibility`.
- Restrict the tool surface via `allowed-tools` instead of assuming a host's defaults.

## Pattern → Body Skeleton

| Pattern | Procedures shape |
|---|---|
| Sequential | `Step 1 → Step 2 → Step 3 → Final Audit`. Each step has an explicit gate. |
| Multi-MCP | `Phase A (MCP-1) → Phase B (MCP-2) → Reconcile`. Data hand-off documented. |
| Iterative refinement | `Draft → Check → Refine → Stop?`. Stop criteria explicit at top. |
| Context-aware | `Detect context → Select branch → Execute branch → Verify`. Branches enumerated. |
| Domain-specific | `Apply rules → If violation: stop + report → Else: execute`. Rules in `references/`. |
