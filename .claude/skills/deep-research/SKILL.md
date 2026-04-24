---
name: deep-research
description: >
  Deep-dives into an open-source project or product and produces a structured
  set of developer design documents (deepwiki-style) covering architecture,
  protocols, data flows, and design decisions.
  Use when the user says "深入研究 XXX", "deep research XXX", "analyze XXX
  codebase", "研究 XXX 架構", "整理 XXX 設計文件", or asks to create deepwiki-
  style documentation for any external project.
argument-hint: "[product-name]"
allowed-tools:
  - Read
  - Glob
  - Grep
  - Bash
  - Write
  - Edit
  - Agent
  - WebFetch
  - WebSearch
metadata:
  author: samzhu
  version: 1.0.0
  category: workflow-automation
  pattern: sequential-orchestration
---

# Deep Research — Deepwiki-Style Codebase Analysis

## Role: Technical Researcher / Reverse-Engineering Analyst

Thorough, evidence-driven, structured. Every claim cites a file path or URL.
Read source code before summarizing — never paraphrase from assumptions.
Write for senior engineers who will build integrations against the target.

## Contract

```
Input:  Product/project name (user provides; may include GitHub URL)
Output: docs/deepwiki/{product-name}/  (README.md + module docs)
Valid:  Every doc has source citations; key flows have ASCII diagrams;
        design-decisions.md includes borrowing analysis for grimoAPP
Next:   (terminal — user decides next action)
```

---

## Process

```
Phase 0 — Locate target (BLOCKING — must confirm before proceeding)
- [ ] Multi-keyword search to identify the exact project
- [ ] Disambiguate if multiple candidates exist
- [ ] Confirm: GitHub URL, license, language, stars, one-line positioning

Phase 1 — Repository panorama
- [ ] Fetch full directory tree
- [ ] Read build config + README
- [ ] List all external dependencies with purposes
- [ ] Produce tech stack summary table

Phase 2 — Module-by-module source analysis (parallelise via sub-agents)
- [ ] For each major module: entry point, core abstractions, design patterns
- [ ] Class/protocol hierarchy with code snippets
- [ ] Concurrency / threading model
- [ ] Persistence model (entities, relationships)

Phase 3 — End-to-end data flows
- [ ] Identify 3-5 most important user scenarios
- [ ] Trace full call chain per scenario
- [ ] ASCII flow diagrams with cross-boundary annotations

Phase 4 — External protocols & APIs
- [ ] Identify all protocols the project uses or implements
- [ ] Fetch official specs (website, RFC, GitHub spec repo)
- [ ] Document message formats with concrete examples
- [ ] Comparison with similar protocols where relevant

Phase 5 — Design decisions & synthesis
- [ ] Check docs/, specs/, ADR/ for decision records
- [ ] Build decision table: decision | rationale | rejected alternatives
- [ ] Identify known tech debt and in-progress refactors
- [ ] Analyse borrowing value for grimoAPP (tie to our spec-roadmap)
- [ ] Write all output files
```

---

## Phase 0 — Locate Target

Search with multiple keyword combinations to avoid false positives:

```
"{name}" site:github.com
"{name}" open source framework
"{name}" {context-hint}            ← if user provides domain context
```

If multiple candidates exist, list them and ask the user to confirm
before proceeding. Never research the wrong project for 20 minutes.

**Output of Phase 0:** one-line positioning + GitHub URL + license + language + stars.

---

## Phase 1 — Repository Panorama

Use `gh api` to fetch repo structure without cloning:

```bash
# Full tree (recursive)
gh api repos/{owner}/{repo}/git/trees/HEAD?recursive=1 | jq '.tree[].path'

# Build config
gh api repos/{owner}/{repo}/contents/Package.swift   # or build.gradle, pom.xml, etc.
gh api repos/{owner}/{repo}/contents/README.md
```

Read **build config first** — it reveals the true dependency graph faster than
browsing source folders.

**Output of Phase 1:** directory tree + tech stack table + dependency list.

---

## Phase 2 — Module-by-Module Analysis

### Dispatch strategy: parallel sub-agents

Split modules across 2-4 sub-agents. Each agent's prompt MUST be
self-contained (include GitHub URL, known directory structure, what to fetch).

**Per-module checklist:**
1. Read entry file — understand module responsibility
2. Identify core abstractions (interface / protocol / trait / abstract class)
3. Trace key class relationships (inheritance, composition, delegation)
4. Note naming conventions and design patterns
5. Record concurrency model (threads, actors, coroutines, event loop)
6. Record error handling strategy
7. Extract representative code snippets (real code, not paraphrased)

### Sub-agent prompt template

```
Analyze module "{module_name}" in {github_url}.

Context: This is a {language} project that {one-line description}.
Known directory structure: {paste relevant subtree}.

For this module, fetch and analyze source files. Report:
1. Module responsibility (one paragraph)
2. Key classes/types table: name | role | file path
3. Core abstractions (interfaces/protocols) with signatures
4. Design patterns used (name the pattern, show the code)
5. Concurrency/threading model
6. How this module connects to other modules

Use `gh api` or WebFetch to read files. Include actual code snippets,
not paraphrased summaries. Report file paths for every claim.
```

---

## Phase 3 — End-to-End Data Flows

Identify the **3-5 most representative user scenarios** (not edge cases).
For each, trace the complete call chain:

```
[Entry point (UI / API / CLI)]
    → [Layer 1: routing / dispatch]
    → [Layer 2: business logic]
    → [Layer 3: persistence / external system]
    ← [Return path with state changes]
```

**Mark every boundary crossing:**
- Process boundary (IPC, subprocess, HTTP)
- Persistence boundary (DB write, file write)
- Network boundary (API call, message queue)

Use ASCII art diagrams. Annotate with class/function names and file paths.

---

## Phase 4 — External Protocols & APIs

For each protocol the project uses or implements:

1. **Fetch the official specification** — from the protocol's website or spec repo
2. **Document the full message lifecycle** — initialization → steady state → shutdown
3. **Show concrete message examples** — real JSON/protobuf, not abstract schemas
4. **List all defined methods/operations** in a table
5. **Compare with similar protocols** — what each handles, how they complement

If the protocol has an SDK, note language availability and maturity.

---

## Phase 5 — Design Decisions & Synthesis

### Decision table format

```markdown
| # | Decision | Rationale | Rejected Alternative |
|---|----------|-----------|---------------------|
| 1 | ...      | ...       | ...                 |
```

### Borrowing analysis for grimoAPP

For each relevant design pattern, evaluate:

1. **Directly applicable** — can adopt as-is for a specific spec
2. **Conceptually useful** — pattern is good but needs adaptation for our stack
3. **Not applicable** — reasons (different platform, different constraints)

Tie findings to specific specs in `docs/grimo/specs/spec-roadmap.md`.

---

## Output Structure

Read template at `references/output-template.md`.

Write all files to `docs/deepwiki/{product-name}/`:

```
docs/deepwiki/{product-name}/
├── README.md                  ← Index: overview, tech stack, file index, grimoAPP relevance
├── architecture.md            ← Directory structure, layering, core patterns, persistence
├── {core-protocol}.md         ← Primary protocol/API spec (name varies by project)
├── {key-subsystem-1}.md       ← Major subsystem deep dive (name varies)
├── {key-subsystem-2}.md       ← Another major subsystem (name varies)
├── data-flow.md               ← All end-to-end flow diagrams
└── design-decisions.md        ← Decision table, tech debt, borrowing analysis
```

**File naming:** kebab-case, descriptive, no generic names like `module-1.md`.

**Minimum 5 files, maximum 8.** Fewer means you're under-analyzing;
more means you're splitting too finely.

---

## Quality Gates

Before marking complete, verify every output file passes:

- [ ] **Citation gate** — every architectural claim has a file path or URL
- [ ] **Code gate** — real code snippets, not paraphrased pseudo-code
- [ ] **Diagram gate** — every data-flow file has at least one ASCII diagram
- [ ] **Table gate** — tech stack, dependencies, decisions use tables
- [ ] **Relevance gate** — design-decisions.md includes grimoAPP borrowing analysis
- [ ] **Language gate** — 繁體中文撰寫，技術名詞保留英文

---

## Anti-Patterns

- Do NOT research without confirming the target first (Phase 0 is blocking)
- Do NOT paraphrase code — quote actual source with file paths
- Do NOT write generic descriptions — every claim must be specific and verifiable
- Do NOT create more than 8 output files — consolidate related topics
- Do NOT skip the borrowing analysis — connecting to grimoAPP is the whole point
- Do NOT assume API behavior from names — read source code or official docs
- Do NOT embed long code blocks (>30 lines) — reference the file path instead

---

## Troubleshooting — Known Failure Modes

**Symptom:** Sub-agent returns shallow analysis ("this module handles X").
**Root cause:** Prompt didn't include directory structure or specific file paths.
**Fix:** Always paste the relevant subtree in the sub-agent prompt. Point to
specific files to start reading.

**Symptom:** `gh api` returns 404 for repository contents.
**Root cause:** Repository is private, or path is wrong.
**Fix:** Try WebFetch on the GitHub web URL. If private, inform user and stop.

**Symptom:** Protocol documentation is thin or outdated.
**Root cause:** Official spec site has limited content.
**Fix:** Check blog posts from the protocol creators, SDK source code, and
community implementations for additional detail.

**Symptom:** Output files are mostly tables with no narrative.
**Root cause:** Skipped Phase 3 (data flows) or Phase 5 (synthesis).
**Fix:** Data flows and design decisions require narrative explanation.
Tables alone don't convey architectural understanding.
