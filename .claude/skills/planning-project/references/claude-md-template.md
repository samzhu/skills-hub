# CLAUDE.md "Where things live" Template

Drop this section into the project's top-level `CLAUDE.md`, between
the user's principles and any workflow-pipeline reference. Preserve
everything else that's already there.

Replace `<project>` with the actual project directory name and adapt
paths to the language/ecosystem. The left-column paths are the
**promise** — new files of that kind land there; readers know where
to look.

```markdown
## Where things live (read this before ls-ing)

**Project artefacts (in repo):**

| Path | What |
| --- | --- |
| `docs/<project>/PRD.md` | Product vision, Critical Path, MVP scope, decision log |
| `docs/<project>/architecture.md` | Tech decisions, framework table, module map, data flows |
| `docs/<project>/development-standards.md` | Code conventions, testing rules, forbidden patterns |
| `docs/<project>/qa-strategy.md` | Test pipeline + verification commands |
| `docs/<project>/glossary.md` | Domain terms (bilingual if the team works across languages) |
| `docs/<project>/specs/spec-roadmap.md` | Live roadmap — specs, milestones, Backlog |
| `docs/<project>/specs/YYYY-MM-DD-S<NNN>-<slug>.md` | In-flight spec (section 1-5 design, section 6 task plan, section 7 results) |
| `docs/<project>/specs/archive/` | Shipped specs (permanent record) |
| `docs/<project>/tasks/` | **Temporary** BDD task files; only exist during a spec's loop; deleted at Phase 3 |
| `docs/<project>/CHANGELOG.md` | What shipped and when |
| `docs/<project>/adr/ADR-NNN-<slug>.md` | In-development decisions that extend or contradict PRD |
| `<production-source-root>` | Production code (language-specific root) |
| `<test-source-root>` | Tests. If an integration-test split is used, state the naming rule here (e.g., `*Test` vs `*IT`) |
| `.claude/skills/` | Workflow skills — rarely edited, portable |

**User runtime state (if any, outside repo, never committed):**

List any external state dirs the project uses (`~/.<project>/`,
cloud-storage paths, etc.), and the env var / property used to
override their location.
```

Rationale: CLAUDE.md is the only 0-tool-call session entrypoint.
Skills that read it benefit immediately (no ls / glob round-trip);
skills that write artefacts have a canonical destination (no
convention drift between specs). The table format keeps entries
skim-able and forces "what" alongside "where" — paths without
semantics are noise.
