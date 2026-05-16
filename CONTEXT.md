# Skills Hub Context

Skills Hub is an internal marketplace and registry for AI agent skills. This context defines the product language used by specs, UI copy, and code review discussions.

## Language

**Skill Browsing**:
Viewing the skill catalog without a search intent.
_Avoid_: Empty search, blank semantic search

**Semantic Search**:
Finding skills from a non-empty natural-language or keyword-like user input by semantic relevance.
_Avoid_: Keyword mode, semantic fallback

**Search Embedding**:
Search-only data derived from the latest SKILL.md name and description so semantic search can rank skills by relevance.
_Avoid_: Skill content, skill metadata

**Skill Description Snapshot**:
A display copy of the latest SKILL.md description kept on the skill row so pages can render without reading the package file.
_Avoid_: Editable skill description, separate marketing description

**SKILL.md Edit**:
A versioned content change made by uploading or pasting a replacement SKILL.md.
_Avoid_: Skill metadata edit, description edit

**Version History**:
A read-only list of published skill versions.
_Avoid_: Version upload form, edit form

**Search Entry Point**:
The single user-facing place where people search for skills.
_Avoid_: Dedicated search page, alternate search route

**Keyword Filter**:
An API-level filter that matches skill text fields without becoming a user-visible browse mode.
_Avoid_: Keyword mode, fallback search

**Intent Summary**:
A removed search-page explanation feature that summarized how the system interpreted a query.
_Avoid_: Search intent card, concept chips

**Risk Level**:
The official platform classification of a skill's safety posture: no risk, low risk, medium risk, or high risk.
_Avoid_: Finding severity, scanner issue count

**Risk Lights**:
A compact four-light visual summary of a skill's **Risk Level**.
_Avoid_: Security categories, scanner rules, Shell/Paths/Secrets/Deps lights

**Security Finding**:
A concrete scanner-detected issue with enough detail for a person to review or fix it.
_Avoid_: Risk level, security category

**Security Report**:
The detailed scan result that explains why a skill has findings and where those findings are located.
_Avoid_: Risk badge, risk lights

**Public Visibility**:
Whether a skill is readable by everyone, determined by the skill's visibility state.
_Avoid_: Public ACL

**Explicit Grant**:
A role assignment from a skill owner to a user, group, or company.
_Avoid_: Public grant, visibility setting

**Public Grant**:
A grant-shaped visibility setting that makes a skill readable by everyone without becoming an ACL entry.
_Avoid_: Public ACL, explicit grant

**Vector Read Scope**:
A deprecated search-index copy of a skill's public visibility and explicit grant read access.
_Avoid_: Visibility truth, ACL truth

**Share Target**:
A user, group, or company that receives an explicit grant to a skill.
_Avoid_: Public, visibility target

**Visibility Command**:
A request that changes a skill's public or private visibility and returns the resulting visibility state.
_Avoid_: Grant delete, empty mutation

**Empty Response Mutation**:
A successful command endpoint that returns no response body.
_Avoid_: JSON mutation, void JSON response

## Relationships

- **Skill Browsing** starts when the search input is blank.
- **Semantic Search** on `/browse` starts when the search input has any non-blank text after debounce.
- A **Search Embedding** may be stored beside skill read state, but it is not part of the user-facing **Skill** concept.
- A **Skill Description Snapshot** changes when the latest SKILL.md changes; it is not edited independently.
- A **SKILL.md Edit** updates the latest package content and may refresh the **Skill Description Snapshot** and **Search Embedding**.
- **Version History** shows existing versions only; SKILL.md uploads and text edits happen in the edit page.
- The **Search Entry Point** is `/browse`; `/search` is deleted rather than redirected to avoid keeping an alternate search surface.
- A zero-result **Semantic Search** can continue by adjusting the input, clearing the input to return to **Skill Browsing**, or publishing a missing skill.
- A **Keyword Filter** may exist on an API, but `/browse` does not expose it as a separate user mode.
- Entering **Semantic Search** from **Skill Browsing** clears catalog filters; clearing the input returns to unfiltered **Skill Browsing**.
- **Intent Summary** is not part of the product search surface after `/search` removal.
- The search input should invite people to describe a task or search a skill, not to think in database fields such as name, description, or category.
- A **Risk Level** is the single user-facing answer to "how risky is this skill?"
- **Risk Lights** show the **Risk Level** at a glance; they do not represent security categories or individual scanner rules.
- A **Security Report** may contain many **Security Findings**, but the **Risk Level** is still the canonical summary shown in list/detail headers.
- A **Security Finding** can explain a **Risk Level**, but it is not itself the **Risk Level**.
- **Public Visibility** is represented by a **Public Grant**.
- An **Explicit Grant** targets a user, group, or company; it never targets "public".
- A **Public Grant** is not an **Explicit Grant** and does not expand into ACL entries.
- **Vector Read Scope** mirrors **Public Visibility** and **Explicit Grant** read access for semantic search; it is not the source of either concept.
- A **Share Target** is never public; public access is controlled by **Public Visibility** in the page header.
- A **Visibility Command** returns the resulting **Public Visibility** state so the UI can update immediately.
- A **Visibility Command** decides success from the skill's current **Public Visibility**, not from whether a Public Grant row exists.
- Skill detail responses expose **Public Visibility** so UI controls do not infer visibility from grants.
- Public access can only be changed through a **Visibility Command**; grant APIs accept **Share Targets** only.
- A **Visibility Command** is an owner sharing action; it uses the same owner/share permission language as grant management.
- An **Empty Response Mutation** must use a void client helper and must not be parsed as JSON.

## Example dialogue

> **Dev:** "If the user opens `/browse` and the search box is blank, do we run semantic search with an empty query?"
> **Domain expert:** "No. Blank input means **Skill Browsing**. Only non-blank input means **Semantic Search**."

> **Dev:** "The security report has zero findings, so should the detail header show no risk?"
> **Domain expert:** "Not by itself. The header shows the skill's **Risk Level**; the **Security Report** explains findings underneath."

> **Dev:** "Should the Share dialog show public as another grant target?"
> **Domain expert:** "Public access is a **Public Grant**, but it is not an **Explicit Grant**; the UI may expose it as visibility rather than as a share target."

> **Dev:** "Where should an owner make a skill public?"
> **Domain expert:** "Use the page header's **Public Visibility** control. The share dialog is only for **Share Targets**."

> **Dev:** "After clicking 'make private', should the frontend wait for another detail request?"
> **Domain expert:** "No. The **Visibility Command** returns the new visibility state."

## Flagged ambiguities

- "keyword mode" was used in UI copy and comments to describe `/browse` fallback behavior. Resolved: the user-facing concepts are **Skill Browsing** and **Semantic Search**; keyword search may remain an API capability but is not a `/browse` mode.
- "`/search`" was used as a dedicated semantic search page. Resolved: the product has one **Search Entry Point**, `/browse`; direct `/search` URLs fall through to the normal not-found route.
- "intent summary" was attached only to the removed `/search` page. Resolved: remove the UI component and API instead of keeping an unused LLM path.
- "four lights" was used as if each light mapped to a category. Resolved: **Risk Lights** map only to the skill's **Risk Level**.
- "findings count" was used as a possible substitute for risk. Resolved: **Security Findings** are supporting detail; **Risk Level** is the canonical header summary.
- "public grant" was used as if it were an explicit user/group/company ACL grant. Resolved: **Public Grant** represents **Public Visibility**, but it is not an **Explicit Grant** and does not expand into ACL entries.
- "public" appeared as a share target. Resolved: public is **Public Visibility** in the page header; **Share Targets** are user, group, or company only.
- "visibility/ACL projection" was used during S186 discussion to mean `vector_store.is_public` and `vector_store.acl_entries`. Resolved: call this **Vector Read Scope**; it is a deprecated search-index copy, while `skills.is_public` and `skills.acl_entries` remain the query-side read state.
