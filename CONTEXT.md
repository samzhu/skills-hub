# Skills Hub Context

Skills Hub is an internal marketplace and registry for AI agent skills. This context defines the product language used by specs, UI copy, and code review discussions.

## Language

**Skill Browsing**:
Viewing the skill catalog without a search intent.
_Avoid_: Empty search, blank semantic search

**Semantic Search**:
Finding skills from a non-empty natural-language or keyword-like user input by semantic relevance.
_Avoid_: Keyword mode, semantic fallback

**Search Entry Point**:
The single user-facing place where people search for skills.
_Avoid_: Dedicated search page, alternate search route

**Keyword Filter**:
An API-level filter that matches skill text fields without becoming a user-visible browse mode.
_Avoid_: Keyword mode, fallback search

**Intent Summary**:
A removed search-page explanation feature that summarized how the system interpreted a query.
_Avoid_: Search intent card, concept chips

## Relationships

- **Skill Browsing** starts when the search input is blank.
- **Semantic Search** on `/browse` starts when the search input has any non-blank text after debounce.
- The **Search Entry Point** is `/browse`; `/search` is deleted rather than redirected to avoid keeping an alternate search surface.
- A zero-result **Semantic Search** can continue by adjusting the input, clearing the input to return to **Skill Browsing**, or publishing a missing skill.
- A **Keyword Filter** may exist on an API, but `/browse` does not expose it as a separate user mode.
- Entering **Semantic Search** from **Skill Browsing** clears catalog filters; clearing the input returns to unfiltered **Skill Browsing**.
- **Intent Summary** is not part of the product search surface after `/search` removal.
- The search input should invite people to describe a task or search a skill, not to think in database fields such as name, description, or category.

## Example dialogue

> **Dev:** "If the user opens `/browse` and the search box is blank, do we run semantic search with an empty query?"
> **Domain expert:** "No. Blank input means **Skill Browsing**. Only non-blank input means **Semantic Search**."

## Flagged ambiguities

- "keyword mode" was used in UI copy and comments to describe `/browse` fallback behavior. Resolved: the user-facing concepts are **Skill Browsing** and **Semantic Search**; keyword search may remain an API capability but is not a `/browse` mode.
- "`/search`" was used as a dedicated semantic search page. Resolved: the product has one **Search Entry Point**, `/browse`; direct `/search` URLs fall through to the normal not-found route.
- "intent summary" was attached only to the removed `/search` page. Resolved: remove the UI component and API instead of keeping an unused LLM path.
