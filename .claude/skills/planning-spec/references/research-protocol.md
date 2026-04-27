# Research Protocol

## Ground Rule: Read Project Source Code Before External Research

When starting solution design for an existing codebase, before dispatching any external research (docs, SDKs, web searches), must read the project's own source code for every integration point that will be modified. Trace the full call chain from entry point to persistence layer using codebase search tools.

**Exit criterion:** Can draw the complete call chain with actual class names, interfaces, and decorator stacks before any external research begins.

**Rationale:** External research without codebase grounding produces solutions that violate existing contracts. The codebase is the single source of truth; external docs are supplementary. Sub-agent research reports summarize what libraries offer, but only the project's own source code reveals how those libraries are actually wired — which decorators wrap which interfaces, which event flows are in play, and which integration points the new design must preserve.

**Anti-pattern:** Dispatching parallel sub-agents to research SDK docs and framework APIs, then proposing an approach that bypasses an existing decorator chain because the decorator was never read. The user corrects the oversight; the same oversight recurs in a different form because the root cause (not reading the project code) was not addressed.

**Enforcement:** Phase 2 starts with codebase reads (Grep, Read), not with sub-agent dispatches. Sub-agents are dispatched only after the integration-point call chains are documented.

## Hard Gate Rule

Research is Phase 2 of the planning-spec process. It MUST complete before Phase 3 (Clarify/Grill). Do NOT ask the user any grill questions until all research agents have returned and findings are integrated into your working context.

**Rationale:** Grilling before research leads to approach comparisons based on assumptions. When research findings arrive, they invalidate earlier assumptions, causing multiple approach pivots and wasted cycles. Research first → grill with facts → one-shot approach selection.

## Roadmap vs Spec Research — Scope Distinction

Roadmap planning（`/planning-project`）lists broad direction and coarse dependencies. It does NOT deeply research library APIs. **Spec planning（`/planning-spec`）is where deep research happens.** Never assume the roadmap's SBE draft or description has validated any API surface. Treat every library interaction as unverified until raw source confirms it.

## Step -1: Scan Existing Research (MANDATORY)

**Before doing ANY new research, check what's already been researched.**

Re-research is the most expensive form of waste — it consumes agent time, user patience, and context window. Prior research artifacts may already contain the answers needed.

**Action:**
1. Scan `docs/local/` for research notes related to this spec's topic
2. Check prior shipped specs' §7 Findings for validated API patterns
3. Check the spec file itself — it may already have §2.3 Research Citations from a prior design round
4. Read any competitive analysis or technology evaluation docs that touch this spec's domain

**If prior research exists:**
- Read it FIRST before dispatching any research agents
- Note what's still valid vs what needs re-verification (version changes, API drift)
- Only dispatch agents for gaps not covered by existing research

**If prior research contradicts the current spec design:**
- Flag the contradiction immediately — it may invalidate the entire approach
- This is a signal that the spec needs redesign, not more research on the wrong approach

**Skip ONLY when:** This is the first spec in the project (no prior artifacts exist).

## Step 0: Prior Art / Ecosystem Scan

Before investigating specific APIs, ask: **does the upstream ecosystem already provide a ready-made solution for this spec's goal?**

Check:
- The framework or library's GitHub org for existing images, templates, or starters
- Official docs for recommended patterns that match this spec's goal
- Container registries (Docker Hub, GHCR) for pre-built images if the spec involves containers
- Community repos for prior implementations of the same pattern

If a viable upstream solution exists, evaluate reuse vs build-own as the **first grill question**, before diving into implementation details.

### Org-Level Repo Inventory (sub-rule of Step 0)

**When the spec depends on libraries from a GitHub org, list ALL repos in that org before designing any interface.**

A single org often contains multiple related repos — SDK, provider adapters, session management, starters — each with distinct API surfaces. Researching only the repo mentioned in `architecture.md` misses sibling repos that may already solve the spec's problem or provide design patterns to align with.

**Action:**
1. `WebFetch` the org's repo list: `https://api.github.com/orgs/{org}/repos?per_page=100`
2. For each repo whose name relates to this spec's domain, fetch `git/trees/main?recursive=1` to list its top-level packages
3. Record the full repo list and relevant packages in §2.3 Research Citations

**Exit criterion:** You can name every repo in the org and have read the README of each domain-relevant repo.

**Example:** An org ships a high-level client (`org/agent-client`), a low-level SDK (`org/agent-sdk`), and a session management library (`org/session`). Researching only the client misses the SDK's runtime APIs (mid-session model switching, hooks) and the session library's query/compaction patterns — both of which directly inform the spec's design.

## Step 0.25: Identify Applicable Industry Standards (MANDATORY)

**Before evaluating any framework/library, identify whether an industry standard governs this spec's domain.**

Skipping this step leads to designing around a framework's idiosyncratic behavior when a cross-platform standard exists. Example: designing a custom SKILL.md format when agentskills.io defines the standard that 26+ platforms follow.

**Action:**
1. Search for open standards that cover this spec's domain (e.g., agentskills.io for skills, OpenAPI for APIs, OCI for containers, A2A for agent interop)
2. Fetch the standard's specification document — identify required vs optional fields, format rules, validation criteria
3. Record the standard in §2.3 Research Citations as the **first entry** — standards anchor all subsequent research

**Once a standard is identified:**
- All subsequent framework research evaluates **compliance with the standard**, not just "does the API work"
- If a framework's parser/serializer only partially implements the standard, this is a **critical finding** that must be flagged before designing around the framework
- The spec's validation rules derive from the standard, not from the framework's implementation

**Research order enforced by this step:**
```
Standard → Framework capabilities → Gap analysis → Gap remediation
NOT: Framework capabilities → Design around framework → Discover standard later
```

**Skip ONLY when:** The spec's domain has no applicable standard (purely project-internal concerns like config layout, internal data flow).

## Step 0.5: Exhaust Pinned Libraries' Own API Surface (MANDATORY)

**Before researching how Library A integrates with Library B, map what Library A already offers on its own.**

Skipping this step is the #1 cause of multi-round user corrections. The root cause: assuming a library's scope from its name instead of reading its actual public API. 30 seconds of package browsing prevents multiple rounds of correction.

**Action:**
1. For each pinned library that this spec touches:
   - Fetch the repo tree (top-level packages only)
   - List every public **interface** and **abstract class** in the relevant module
   - Flag interfaces whose name matches this spec's domain concepts
2. For each flagged interface:
   - Fetch raw source to read the **full method list and Javadoc**
   - Record: "Library X already provides interface Y with methods [list]"
   - Note extension points: SPI, decorator hooks, builder parameters, advisor chains
3. Record findings in §2.3 Research Citations under "Library API Surface"
4. Any interface that already models the spec's domain concept becomes a **MANDATORY grill question**: "Should we use/extend this existing abstraction or build our own? What does it NOT provide that we need?"

**Anti-patterns this step prevents:**
- Designing a custom wrapper without first discovering the library already has an extension point designed for exactly that purpose
- Building a custom integration bridge when the library already has an SPI for it
- Researching "how to bridge Library A with Library B" when Library A's own API already covers the use case

**Skip ONLY when:** The spec touches exclusively standard library APIs or surfaces already fully mapped by a prior shipped spec's §7 Findings (with raw source citations, not just prose descriptions).

### Wrapper / Subclass Decision Gate (sub-rule of Step 0.5)

**MANDATORY when:** The spec proposes wrapping, subclassing, or replacing a framework primitive (VectorStore, EventBus, Repository, Service SPI, Filter, Interceptor, etc.).

A "wrapper" decision is load-bearing — it commits the spec to a specific extension surface. Choosing the wrong base class (e.g., wrapping a concrete class when the framework has a designated abstract base, or registering a Bean when the framework's builder is per-call) cascades workarounds across all downstream specs.

**Action**: Before writing the wrapper proposal, fetch raw source for:
1. The class being wrapped — read its constructor, key method bodies, and any Builder
2. Its parent class or interface chain — find the canonical extension point (often `Abstract*` named base classes designed for subclassing)
3. Any Builder / factory the framework provides for this primitive — these reveal the framework's intent for instantiation lifetime

**Exit criterion**: Can quote three concrete patterns from the source — constructor signature, key method body (not just signature), Builder API surface — and explain how each constrains the wrapper design. Specifically, can answer:
- Is there an `Abstract*` base designed for subclassing? (extension point)
- Does the Builder pattern hint at per-call vs singleton lifetime?
- Are there `final` methods or `private` fields that block customization?

**Anti-pattern this gate prevents:** Designing a wrapper around the concrete class, then discovering mid-implementation that the framework provides a designated abstract base — and refactoring to the abstract base after task files were already written.

### Source Code, Not Bytecode (sub-rule of Step 0.5 / 0.75)

**MANDATORY when:** Inspecting compiled bytecode (`javap`, `.class` extraction, decompiler output, IDE-rendered method summaries) as a research shortcut.

Bytecode signatures reveal **what methods exist**. They do NOT reveal:
- **Method body** — the actual SQL templates, conditional logic, side effects
- **Inline comments / Javadoc design intent** — stripped during compilation
- **Default field initializers and static blocks** — visible but harder to reason about than source
- **Lambda body capture semantics** — synthetic method names obscure intent

**Rule**: Bytecode inspection is acceptable for fast triage but **never sufficient evidence for "Validated" confidence**. Whenever bytecode reveals a signature relevant to the design, the next step must be fetching the corresponding `.java` source file from the upstream repo and reading the method body.

**Action**: Whenever a research note cites bytecode (`javap`, decompile, IDE summary):
1. Fetch the matching source file: `https://raw.githubusercontent.com/{owner}/{repo}/{ref}/{path}.java`
2. Read the method body for every signature relied on by the design
3. Cite both the bytecode line AND the source URL with line range in §2.3

**Exit criterion**: Research notes for any "Validated" claim include a source file URL with line range, not bytecode signatures alone.

**Anti-pattern this gate prevents:** Marking a design decision as "Validated" because `javap -p` confirmed a method exists with the expected signature, then discovering during implementation that the method body has constraints (hardcoded SQL columns, mandatory side-effects, parameter ordering) that invalidate the design.

### Library Surface Completeness Gate (sub-rule of Step 0.5)

Step 0.5 is not complete until EVERY pinned library this spec touches
has been fully scanned. "Fully scanned" has a concrete exit criterion:

**Exit criterion:** For each library, you can name every top-level
package AND every public class in those packages. Not just the classes
mentioned in `architecture.md` — ALL of them.

**Enforcement sequence:**
1. Fetch the repo tree (`https://api.github.com/repos/{owner}/{repo}/git/trees/main?recursive=1`)
   or use `WebFetch` on the GitHub directory listing
2. List every top-level package (e.g., `tools/`, `advisors/`, `skills/`,
   `interceptors/`, `listeners/`)
3. For each package that MIGHT be relevant to this spec's domain, list
   the public classes inside
4. Record findings in §2.3: "Library X has packages: [list]. Domain-
   relevant packages: [list]. Public classes in those packages: [list]."

**The test that proves completeness:**
Ask yourself: "Is there a package in this library I have NOT looked
inside?" If the answer is "I don't know" — the scan is incomplete.

**Anti-pattern this gate prevents:**
Treating `spring-ai-agent-utils` as "a Skills library" because
`architecture.md` only lists `SkillsTool.Skill` and `SkillsFunction`
— while the library also contains `advisors/AutoMemoryToolsAdvisor`,
`advisors/AutoMemoryToolsSystemPromptAdvisor`, and other cross-cutting
abstractions. The architecture doc lists what the PROJECT uses, not
what the LIBRARY provides. These are different sets.

### Existing Stack Before New Dependencies (sub-rule of Step 0.5)

**Before evaluating any NEW dependency, validate what the existing stack already provides for this use case.**

This is a sequencing rule: research the capabilities of what you already have BEFORE researching what you might add. The question "does the existing stack already solve this?" must be answered first.

**Enforcement sequence:**
1. Map existing dependencies' capabilities for this spec's goal
2. Identify the specific gap (if any) that existing dependencies cannot fill
3. Only THEN evaluate candidate new dependencies to fill that gap
4. If the existing stack covers 80%+ of the requirement, design around it — don't add a dependency for the remaining 20%

**Why this order matters:** Researching a new library's API creates anchoring bias — once you've mapped how Library X solves the problem, you'll design around it even if the existing stack could have solved it more simply. Research the existing stack first to avoid this trap.

**The question that must be answered before adding any dependency:**
- "What does the current stack provide TODAY for this use case?"
- This must be answered by inspecting actual behavior (source code, tests, POC runs), not by assuming capabilities from names or docs.

## Step 0.75: Dependency Behavior Deep Dive (MANDATORY for load-bearing deps)

**After mapping public APIs (Step 0.5), read to private-method level for every load-bearing dependency.**

Step 0.5 maps "what methods exist." This step answers "what do those methods actually do inside." The distinction matters because a method named `loadDirectory()` might internally use a parser that only supports flat key:value — knowledge that changes the entire design.

**Action:**
1. For each load-bearing class (parser, loader, serializer, adapter, factory):
   - Read the **complete source**, including private methods and internal helpers
   - Identify internal engines: what parser does the YAML loader actually use? What HTTP client does the fetcher use? Is it custom or a standard library?
   - Map capability boundaries: what inputs does it handle correctly vs incorrectly?

2. **Test with standard-compliant inputs.** If Step 0.25 identified a standard, check whether the dependency's parser/serializer handles the standard's full range:
   - Feed a representative standard-compliant input through the code path mentally (or in a POC)
   - Document what works and what breaks
   - Example: agentskills.io allows nested `metadata:` maps — does the library's YAML parser support nested maps?

3. **Classify interface vs implementation quality:**

   | Classification | Meaning | Design action |
   |---|---|---|
   | **Interface-sound, implementation-sound** | API contract correct, internals work as expected | Use directly |
   | **Interface-sound, implementation-defective** | API contract is reasonable, but internals have gaps (wrong parser, missing error handling, standard non-compliance) | **Rewrite same interface** — keep method signatures, replace internals |
   | **Interface-defective** | API design itself is wrong for our use case (e.g., all static, no SPI, wrong return types) | Build custom, do not wrap |
   | **No interface** | Framework has no abstraction for this concern | Build from scratch |

4. **Check extensibility mechanics:**
   - Is the class `final`? Are methods `static`? → Cannot override
   - Are there `protected` methods? → Designed for subclassing
   - Are there SPI interfaces the class implements? → Implement the SPI directly
   - Does the class use `new InternalDep()` hardcoded? → Cannot inject alternatives

**Record findings in §2.3** under "Dependency Behavior Deep Dive" — include what works, what doesn't, and which classification applies.

### Persistence / Storage Layer Audit (sub-rule of Step 0.75)

**MANDATORY when:** The spec wraps, delegates to, or stores data
through any third-party Repository, DAO, ChatMemoryRepository,
SessionRepository, or equivalent persistence abstraction.

A persistence layer's public API (`save()`, `add()`, `get()`) reveals
WHAT you can call. It does NOT reveal:
- **What survives the round-trip** — which fields are persisted vs discarded
- **Write semantics** — append-only INSERT vs full-replace (DELETE + re-INSERT)
- **Serialization format** — plain text vs JSON vs binary
- **Schema constraints** — column types, CHECK constraints, reserved words

These four properties are load-bearing for any spec that relies on
persisted data. Discovering them at POC time wastes the entire spec
design + task planning effort.

**Enforcement sequence:**
1. Fetch the Repository implementation's raw source code (not the
   interface — the IMPLEMENTATION class)
2. Read `save()` / `saveAll()` / `add()` line by line. Answer:
   - Does it DELETE before INSERT? (full-replace) Or INSERT only? (append)
   - Which fields from the domain object are actually written to SQL?
   - Is metadata (Map<String, Object>) serialized or discarded?
3. Read the `RowMapper` / deserialization code. Answer:
   - Which fields are reconstructed on read?
   - What is LOST in the round-trip? (metadata? nested objects? type info?)
4. Read the schema SQL file bundled with the library. Answer:
   - Does the schema match the current database version? (reserved words,
     CHECK constraints, data types)
   - Are there columns for ALL the data the spec needs to store?

**Exit criterion:** You can answer "After a round-trip through this
persistence layer, these fields survive: [list]. These fields are
LOST: [list]. Write semantics: [append/replace]. Schema compatibility
with our DB: [yes/issue]."

**Record findings in §2.3** under "Persistence Layer Audit."

**Anti-pattern this gate prevents:**
Using `JdbcChatMemoryRepository` because its `ChatMemory.add()` API
looks like append-only storage — without reading `saveAll()` which
does DELETE-all + re-INSERT, discards all Message metadata (model,
tokens, duration), and stores only plain text content. Discovering
this at POC time after the spec and task plan are written wastes an
entire design cycle.

### Downstream Consumer Schema Check (sub-rule of Step 0.75)

**MANDATORY when:** The spec designs or adopts a database schema.

Before accepting ANY schema (third-party or custom), enumerate all
known downstream consumers of the stored data and verify the schema
has fields for everything they need.

**Enforcement sequence:**
1. List every downstream consumer from the roadmap:
   - Cost tracking → needs: tokens_in, tokens_out, model, provider
   - Session replay / cross-CLI switch → needs: user_message, assistant_message, session_id, timestamp
   - Session export / audit → needs: all of the above + duration, finish_reason
   - Long-term memory distillation → needs: conversation content + metadata
2. For each consumer, list required fields
3. Verify the schema has a column for EACH required field
4. Fields that are missing → either add to schema or explicitly mark
   as "deferred — will be added by spec S0XX"

**Exit criterion:** Every downstream consumer's required fields are
either present in the schema OR explicitly marked as deferred with a
spec ID.

**Anti-pattern this gate prevents:**
Adopting a 4-column schema (conversation_id, content, type, timestamp)
when downstream cost tracking needs token counts and model name.
The gap is discovered only when the cost module starts, requiring a
schema migration and data backfill.

**Skip ONLY when:** The dependency's behavior has been fully validated by a prior shipped spec's §7 Findings (with runtime-verified findings, not just API mapping).

### PRD Acceptance Criteria Scan Before Schema Defaults (sub-rule of Step 0.75)

**When a schema column uses an enum/classification value (e.g., `provider`, `status`, `type`), grep the PRD for ALL acceptance criteria that reference that concept before writing any DEFAULT value.**

Hardcoding a DEFAULT assumes a single-value world. The PRD may define multi-value scenarios that the schema must accommodate from day one.

**Action:**
1. For each enum/classification column in the schema, search the PRD:
   `grep -i "{column concept}" {PRD path}`
2. List every AC that references or constrains the column's value domain
3. If ANY AC implies multiple values → remove DEFAULT, make the column dynamic
4. Record the AC references in §2 Approach as design rationale

**Exit criterion:** Every enum/classification column's DEFAULT (or lack thereof) is justified by a specific PRD AC reference.

**Example:** A schema has `provider VARCHAR(20) DEFAULT 'providerA'`. PRD AC says "user can switch from providerA to providerB mid-session." The DEFAULT hardcodes a single-provider assumption — remove it, make the value dynamic via a strategy/extractor pattern.

### Ecosystem Query Pattern Alignment (sub-rule of Step 0.75)

**When the spec designs a query/filter API (e.g., `EventFilter`, `SearchCriteria`), search the same ecosystem for existing filter designs and align field names and conventions.**

Designing a query API in isolation leads to naming drift. When the spec's filter later needs to interoperate with upstream or sibling libraries, misaligned field names create unnecessary mapping code.

**Action:**
1. Search related repos for existing filter/criteria records:
   `grep -r "Filter\|Criteria\|Query" {related-repos}`
2. For each match, read the record's fields and factory methods
3. Align the spec's filter with the upstream convention (field names, types, factory method signatures)
4. For each field in the spec's filter, annotate: "aligns with {upstream}.{field}" or "project-specific, no upstream equivalent"

**Exit criterion:** Every field in the spec's filter API has either an upstream alignment citation or an explicit "project-specific" annotation.

**Example:** You design `EventFilter(lastN, excludeSynthetic)` with 2 fields. A sibling library in the same ecosystem has `EventFilter` with 9 fields (from, to, messageTypes, keyword, lastN, page, pageSize, branch, excludeSynthetic). Aligning upfront avoids a redesign when downstream consumers need the richer query capabilities.

### Deployment Infrastructure Audit (sub-rule of Step 0.75)

**MANDATORY when:** The spec includes any deployment configuration — connection strings, connection pool sizes, secret references, network topology, or any value that depends on the target environment's quotas or limits.

A deployment config's numeric values are not "fill in later" details — they are load-bearing design decisions that depend on the target infrastructure's quotas. Writing a connection pool size without knowing the database's max-connection limit means the value is either guessed or copied from a generic example, neither of which survives production load. Similarly, choosing a connection method (direct IP / proxy / native connector) by anchoring on a popular blog pattern bypasses the cloud provider's currently-recommended pattern for the specific compute-to-data shape.

**Two failure modes this gate catches:**

1. **Architecture chosen by anchoring** — the spec proposes a connection method based on a generic pattern (deepwiki research, library example, prior project) without checking the cloud provider's current recommended method for the specific compute-to-data pairing.
2. **Resource numbers chosen by default** — the spec writes pool sizes, batch sizes, or rate limits using framework defaults without computing how those numbers fit the target environment's binding constraints.

**Enforcement sequence:**

1. **Identify the deployment shape** as a triple: `compute-service × data-service × network-model`. Examples: "serverless container × managed Postgres × private network", "Kubernetes pod × managed Postgres × public IP via auth proxy".
2. **Web-search for the cloud provider's latest official guide** for this exact triple. Prefer the cloud provider's own documentation pages over blog posts and library READMEs, even when blog posts rank higher in search results. Cite the specific URL.
3. **Confirm or ask for the missing infrastructure parameters** that affect numeric config values: instance tier / size class, network mode (public vs private), identity model (password vs IAM auth), expected scaling factor (max replicas / instances). Either ask the user explicitly, or state in the spec "no recommendation until {parameter} is confirmed" and stop.
4. **Compute every numeric config value from a documented limit** — no bare numbers in the deployment config. Each `pool-size: N` / `max-batch: N` / `timeout: N` must have an inline comment showing the derivation: `quota_limit − reserved ÷ scaling_factor = N`.

**Exit criterion:** Every connection-related setting and every numeric value in the deployment config either (a) cites an official-doc URL for the recommended pattern, or (b) shows an inline budget formula that derives from a documented limit and a confirmed infrastructure parameter.

**Anti-pattern this gate prevents:**

Writing a `maximum-pool-size: 10` from the framework's default value when the target managed database's `max_connections = 25` and the compute service auto-scales to 5+ instances. Discovering connection exhaustion in production after deployment costs more than the 30-minute web search would have. The fix is upstream: ask the deployment-shape and tier first, derive pool size from the limit, and only then write the yaml.

### Custom-vs-Official Implementation Decision Gate (sub-rule of Step 0.75)

**MANDATORY when:** The spec considers either using a framework's official implementation as-is, OR writing a custom implementation that extends the framework's SPI / interface. This includes "should we wrap the official class?", "should we extend the base class?", and "should we re-implement the interface?" decisions.

The decision "use official vs write custom" cannot be made from package descriptions, README files, or summary docs. The gap that justifies a custom implementation lives in the framework's source code — specifically in **which extension points are exposed** and **which behaviors are hardcoded**. Two specific failure modes recur:

1. **Recommend custom by anchoring on a sibling pattern** — the project already has a similar custom implementation for a different domain (e.g., a custom store for system A), and the spec extrapolates to system B without checking if system B's official package already covers the requirement or exposes a sufficient extension point.
2. **Recommend official by surface-reading** — the official package looks complete in the README, so the spec adopts it; later at implementation time, a hardcoded internal behavior blocks the requirement (e.g., the official insert path doesn't write a custom column the spec needs), forcing a mid-implementation pivot.

**Enforcement sequence:**

1. **Read the framework's actual source for the official implementation** (raw source code, not docs / READMEs / summary blogs). Identify:
   - The unified interface or SPI the framework defines.
   - The concrete official implementation's public API (constructor, builder, key methods).
   - The official implementation's **internal behavior on the dimensions relevant to this spec's requirement**. Examples: for an ACL requirement, does the INSERT/SELECT path support custom filter columns? For a custom embedding store, does the official path expose a native client / decorator hook? Read the relevant private methods.

2. **Map the requirement against the source findings.** Three outcomes:

   | Outcome | Meaning | Decision |
   |---|---|---|
   | **No gap** | Official implementation already covers the requirement | Use official as-is. Cite the source line proving coverage in §2 Approach. |
   | **Gap closeable via SPI / native client / decorator** | Official lacks the feature directly but exposes a documented extension hook | Use official + extension. Cite which hook in §4 Interface Design. |
   | **Gap requires bypassing the official implementation** | A hardcoded internal behavior blocks the requirement and no extension point bridges it | Custom implementation justified. Cite the specific source line(s) where the gap appears. |

3. **Record the decision in §2 Approach** with the chosen path, the source URL, and (if custom) the specific line(s) proving the gap cannot be bridged.

**Exit criterion:** The recommendation explicitly states either "use official because {gap-X is non-existent / closeable via hook-Y}" or "custom impl because {specific source line proves gap-X cannot be bridged}". A recommendation that compares feature lists from docs, READMEs, or package descriptions is not sufficient — the decision MUST be grounded in source code.

**Anti-pattern this gate prevents:**

Recommending a custom wrapper because the official package "looks limited" from the README, then later discovering the official package has an extension point designed for exactly the use case — wasting the custom implementation's design effort. Or the inverse: recommending the official package because it "should work" from the README, then discovering at implementation time that a hardcoded internal behavior (e.g., a fixed INSERT column list) blocks the requirement — forcing a mid-spec pivot to a custom implementation.

## Steps 1–5: Dispatch Sequence

1. **List ALL load-bearing APIs this spec touches.** Read the roadmap deliverables, architecture doc, and any SBE drafts. Name each API by library + entrypoint (e.g., `<library>: <class/annotation/function>`). One entry per distinct surface. **Be exhaustive** — under-scoping the API list is the #1 cause of repeated research rounds. **Include interfaces discovered in Step 0.5** — these are often the most important APIs and the ones most likely to be missed if Step 0.5 was skipped.
2. **Round 1: Dispatch breadth agents in parallel.** Budget per sub-agent: 10 tool calls max. S-sized specs: 2–3 agents. M+ specs: 3–5 agents. Include at minimum: (a) org-level repo inventory if spec touches a GitHub org, (b) one agent per pinned library for API surface listing.
3. **WAIT for all Round 1 agents to return.** Integrate findings. Triage: which discoveries need deep research? Dispatch Round 2 agents immediately for newly discovered surfaces (see Iterative Discovery Pattern above).
4. **After the last round completes, integrate ALL findings into working context.** Fold each finding into a research summary. If a finding contradicts the roadmap's SBE draft (e.g., roadmap assumes method X, docs show X is deprecated and Y is current), note it for the first grill question.
5. **Cite every source in §2.** No uncited version numbers, no uncited API signatures. The citation is the audit trail when `/implementing-task` later re-fetches the same doc.

## Sub-agent Prompt Template

Adapt to the specific API surface:

    Research [library@version]'s [specific API / entrypoint / pattern]
    for a spec I'm designing. Library version is pinned in the project's
    architecture doc. Goal: confirm the current official idiom and flag
    any drift.

    Investigate (≤ 10 tool calls):

    CRITICAL: Fetch RAW SOURCE CODE from GitHub (e.g.,
    raw.githubusercontent.com/.../SomeClass.java), not documentation
    page summaries. Docs may lag behind or omit critical details like
    constructor signatures, field visibility, and interface default methods.

    1. Current stable API signature — names, parameter order, return
       shape. Cite the exact source file URL.
    2. Constructor / Builder parameters — EVERY field. Note which accept
       Sandbox, which use System.setProperty, which have extension points.
    3. Deprecated / removed APIs near this surface — anything the spec
       should avoid reaching for.
    4. Recommended usage pattern — the canonical example from the
       official docs or test suite.
    5. Gotchas called out in the docs or source — nullability, concurrency,
       build/compile-time constraints relevant to this surface.
    6. Internal engines and capability boundaries — for parsers, serializers,
       adapters: what engine does the implementation use internally? What
       inputs does it handle correctly vs incorrectly? Read private methods
       to answer this. Example: "MarkdownParser uses custom line-split, not
       SnakeYAML — cannot parse nested YAML maps or arrays."
    7. Extensibility classification — is the class final? Are key methods
       static (cannot override)? Are there protected hooks? Does the class
       implement an SPI interface that could be re-implemented? Are internal
       dependencies hardcoded (new InternalDep()) or injectable?

    Output (≤ 500 words):
    - Answer per question, each with a citation URL (raw source preferred).
    - One-paragraph "implication for this spec" — what the spec's §2
      Approach should lock in based on the findings.
    - Capability boundary summary: "supports X, does NOT support Y."
    - Extensibility verdict: "can extend via [mechanism]" or "cannot extend,
      must rewrite/wrap because [reason]."
    - Gaps / items needing a second fetch.

    Do NOT fabricate. If a docs page 404s or is behind anti-bot, say so.

## Raw Source Code Rule

**Fetch raw source, not docs summaries.** For load-bearing API decisions, always fetch the actual source file (e.g., `https://raw.githubusercontent.com/<org>/<repo>/refs/heads/main/<path>.java`). Documentation pages may:
- Lag behind the actual API
- Omit critical details (constructor parameters, field visibility, default methods)
- Summarize instead of showing exact signatures

When a sub-agent returns a finding that is the load-bearing decision of the spec, verify it against the raw source before presenting to the user.

## Iterative Discovery Pattern (replaces the former "One Round Rule")

Research follows a **breadth-first discovery chain**: each round may reveal new repos, libraries, or design patterns that require a follow-up round. This is normal and expected — not a sign of under-scoping.

### Round structure

```
Round 1: BREADTH — cast a wide net
  Dispatch 3-5 sub-agents in parallel:
    - Org-level repo inventory (Step 0 sub-rule)
    - README scan of all domain-relevant repos
    - Pinned library API surface listing (Step 0.5)
  Exit: can name every repo in the org + every top-level package in relevant repos

      ↓ Triage: which discoveries need deep research?

Round 2: DEPTH — targeted deep dives
  Dispatch sub-agents for newly discovered surfaces:
    - Read source of key interfaces found in Round 1
    - Compare design patterns across sibling repos (e.g., EventFilter in spring-ai-session vs our design)
    - Validate behavior of load-bearing APIs (Step 0.75)
  Exit: every load-bearing API classified as Validated / Hypothesis / Unknown

      ↓ Triage: did Round 2 reveal anything new?

Round 3 (if needed): ALIGNMENT — ecosystem pattern sync
  Dispatch sub-agents for newly discovered patterns:
    - e.g., Round 2 found spring-ai-session has CAS compaction → research its schema + strategy framework
    - e.g., Round 2 found ClaudeSyncClient.setModel() → research implications for cost routing
  Exit: no new domain-relevant discoveries
```

### Rules

1. **Each round dispatches sub-agents in parallel** — never sequentially. If Round 2 needs 3 deep dives, dispatch all 3 at once.
2. **Maximum 3 rounds.** If Round 3 still produces new discoveries, stop and document the remaining unknowns for the user to triage. Infinite research loops are worse than documented gaps.
3. **Triage between rounds is fast.** Spend ≤ 2 minutes deciding what Round N+1 needs. List the discoveries, tag each as "needs deep research" or "noted, no action needed", dispatch immediately.
4. **The grill loop starts ONLY after the last round completes.** No grilling between rounds.

### What triggers a new round (legitimate discovery chain)

| Round 1 discovers... | Round 2 action |
|----------------------|----------------|
| Sibling repo in the same org not mentioned in architecture.md | Read its README + list packages |
| A library provides an interface that matches the spec's domain concept | Deep dive: read full source, check extensibility |
| An upstream design pattern (EventFilter, CAS, branch isolation) | Compare with spec's current design, align or document divergence |
| A related SDK at a different abstraction layer | Map its API surface and relationship to the already-known library |

### What does NOT trigger a new round (scope creep)

- "This library is interesting but not related to this spec" → note in §2.3, no follow-up
- "The upstream has a feature we might use someday" → note in Appendix, no follow-up
- "There are 20 more repos in this org" → only research repos whose README mentions this spec's domain concepts

### Anti-pattern: Reactive single-target research

Research is reactive — the user provides a URL, agent researches that one repo, designs, then the user provides another URL, triggering re-research and redesign. This repeats N times.

```
BAD:  user URL → research 1 repo → design → user URL → re-research → redesign (×N)
GOOD: Round 1 → list all org repos → Round 2 → deep dive all in parallel → design once
```

**Root cause:** No org-level breadth scan in Round 1. The agent only researched what was explicitly pointed to, missing sibling repos that informed the design.

## Verify Before Writing

If a sub-agent's finding is the load-bearing decision of the spec (the whole §2 Approach hinges on it), do a second WebFetch to confirm before committing the spec file. A spec with a wrong API signature becomes a task loop of corrections.

## Confidence Classification — Validated vs Hypothesis

After all research agents return, classify EACH load-bearing design
decision before writing the spec:

| Confidence | Evidence required | Spec annotation |
|---|---|---|
| **Validated** | Raw source confirms API exists with expected signature and behavior. Or a prior shipped spec's §7 proved it in production code. **Or a POC test has exercised the API and confirmed actual runtime behavior.** Citation must include source file URL with line range — **bytecode signatures, doc summaries, or URL alone are insufficient**. | Cite source URL with line range in §2.3. Another reviewer must be able to re-verify each claim without consulting the original author. |
| **Hypothesis** | Docs suggest it works. Source shows the API exists. But actual runtime behavior (return values, error paths, integration with other APIs) is unproven. | Mark `[needs POC validation]` in §2. Declare `POC: required` with specific test plan. **Do NOT write a committed approach around a hypothesis.** |
| **Unknown** | Could not determine from docs or source. Behavior depends on runtime interaction, undocumented conventions, or versions we haven't tested. | **Do not design around it.** Either dispatch a targeted research agent to resolve, or ask the user. |

**Validated Evidence Format (mandatory)**:
- Source URL pointing to a specific revision (`raw.githubusercontent.com/.../{ref}/path/File.java`) with line range
- A reproducible command, or a worked snippet, when behavior depends on runtime interaction
- For decisions assigned `Validated` based on POC: cite the POC test class and assertion, not just "POC passed"

If you cannot produce the above format, the claim is **not Validated** — downgrade to Hypothesis and add a POC plan.

### Rewrite-Same-Interface Pattern

When Step 0.75 classifies a dependency as **"Interface-sound, implementation-defective"**, the recommended design action is:

1. **Keep the same public method signatures** (constructor, return types, parameter types)
2. **Replace the internal implementation** (swap parser engine, fix error handling, add standard compliance)
3. **Produce the same output types** as the original — so downstream consumers (builders, factories, callbacks) continue to work

This pattern avoids building a parallel type system while fixing the implementation gap. The framework's own types flow through unchanged; only the code that produces them is replaced.

**When to apply:**
- Framework's interface/record is well-designed but its parser/loader/adapter has capability gaps
- The framework's output type is consumed by other framework components (e.g., `SkillsTool.Skill` is consumed by `SkillsFunction` → `ToolCallback`)
- Replacing the output type would break downstream integration

**When NOT to apply:**
- The interface itself is wrong (wrong abstractions, missing fields) → design a new interface
- The framework has proper SPI/extension points → implement the SPI instead of rewriting

**Example:** A library's `MarkdownParser` uses a custom flat line-splitter (cannot handle nested YAML). Interface is sound (`MarkdownParser(String)` → `getFrontMatter()` / `getContent()`). You rewrite with a proper YAML engine internally, keep same method signatures, produce same `Map<String, Object>` output — but values are now correctly typed (nested maps, lists, not just strings). Downstream consumers that accept `Map<String, Object>` continue to work unchanged.

### API Surface Mapping vs Behavior Validation

This is the single most important distinction in research. Confusing the
two leads to specs designed around assumptions that collapse at POC time.

| Type | What it answers | Method | Sufficient for design? |
|---|---|---|---|
| **API surface mapping** | "What methods exist?" | Read source code, list interfaces | Necessary but NOT sufficient |
| **Behavior validation** | "What do those methods actually DO?" | Run the code in a POC | **Required** for load-bearing decisions |

**When behavior validation is required (mandatory POC):**
- The spec's core value proposition is "add capability X that the
  framework lacks" — you must PROVE the framework lacks X, not assume it
- The spec bridges two libraries that have never been integrated before
  in this project — prove the integration works
- The spec relies on a specific runtime behavior (return values, state
  changes, error semantics) that docs don't explicitly guarantee

**When API surface mapping is sufficient (no POC needed):**
- The spec uses a well-documented, widely-used API in its standard way
- A prior shipped spec already validated this exact pattern in §7
- The API's behavior is trivially predictable from its signature

**Anti-pattern: Designing a complex solution for a non-existent gap.**
Research may show that Library A "lacks" persistence. But if you only
mapped Library A's API without testing its actual behavior, you might
discover (too late) that Library A delegates persistence to an
underlying system that already handles it. The gap was assumed, not
verified. A 15-minute POC would have caught this before days of
spec design and task planning.

## Research Persistence Rules

Research findings MUST be persisted in the spec file's §2.3 Research Citations section — not just in the conversation context.

- **Not just URLs.** Each citation includes a one-sentence finding summary explaining what was discovered and why it matters.
- **Format:** `[finding summary]: [URL]`
- **Group by topic.** Organize citations by library or surface, not by order of discovery.
- **Record contradictions.** If a finding contradicts the roadmap or architecture doc, note the contradiction explicitly so spec reviewers see it immediately.
- **Include raw source URLs.** Prefer `raw.githubusercontent.com` links over docs page URLs for API signatures.

This ensures future spec revisions don't need to re-research, downstream specs can reference upstream findings, and `/implementing-task` has an audit trail for API decisions.

## Cross-Cutting Research Persistence

When research during spec planning produces findings that go **beyond the scope of the current spec** (e.g., competitive analysis, technology selection rationale, memory architecture research, rejected alternatives with detailed reasoning), persist them in `docs/local/` as a research note file.

**When to create a `docs/local/` research note:**
- Research covers a topic that multiple future specs will reference (e.g., a database comparison that informs the current spec and future related specs)
- Competitive analysis is updated with new findings (e.g., Hermes self-evolution analysis)
- A technology was deeply evaluated and rejected — the rejection rationale saves future re-research

**Format:** `docs/local/<topic>-research.md` with sections: conclusions table, key findings, rejected alternatives, reference sources.

**What stays in the spec vs what goes to `docs/local/`:**
- Spec §2.3: citations directly relevant to THIS spec's approach decision
- `docs/local/`: broader research that informs the ecosystem, not just one spec

**Anti-pattern:** Research findings that exist only in the conversation context. If the conversation is lost, the research must be re-done. Persist early.

## User-Provided References — Fetch Before Responding

When the user provides reference URLs alongside a design redirection or correction (e.g., "you should use X instead of Y, see {URL}"), **WebFetch every URL in full and quote the specific passage that contradicts the prior choice — before responding with a new design**.

**Rule**: Do not respond with "I see, let me redesign" or any abstract acknowledgment. The response must include:

> "according to {URL}, {exact quote} — this means {implication for our design}"

for at least one quoted passage from the user's references.

**Why this matters**: Acknowledging a redirect without quoting the source produces a redesign based on the assistant's interpretation of the user's words, not based on the source the user actually cited. This re-introduces the same shallow-research failure mode the user is trying to correct.

**Action sequence**:
1. WebFetch every user-provided URL in parallel
2. For each URL, identify the specific passage relevant to the redirect
3. Quote each relevant passage in the response, with the URL
4. State the implication for the current design
5. Only after the above, propose the redesign

**Anti-pattern this rule prevents**: User cites GitHub source URLs to point out a design flaw. Assistant says "got it, switching to approach X" and proceeds with redesign — without WebFetching the URLs. The new design carries assumptions the user didn't intend, requiring another correction round.

**Exit criterion**: Every user-provided URL appears in the response with at least one quoted passage. If a URL has no quotable passage relevant to the redirect, state that explicitly (e.g., "the third URL is the GitHub root, no specific passage to quote — used as repo entry point").

## Spec Change Tracking

When research during planning leads to **new spec proposals** (e.g., current spec planning discovers a sub-topic that warrants its own spec), or changes to existing spec dependencies/estimates:

1. Record the new/changed spec in `spec-roadmap.md` immediately (status 🔲)
2. Update the dependency graph if the new spec changes the critical path
3. Note the source in the v-note at the top of the roadmap (e.g., "new spec split from current spec during planning")
4. Add tech debt entries if architecture.md or other docs drift from the new design
