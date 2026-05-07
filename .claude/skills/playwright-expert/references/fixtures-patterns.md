# Fixtures Patterns — Test Data + State Seeding

Read just-in-time during DESIGN Step 3 (per-AC translation). Drives
the per-AC decision: which fixture profile does this AC need, and is
the seeding mechanism for that profile already in place?

## Why this matters

E2E tests fail more often from missing or polluted state than from
broken assertions. A "happy-path" run on an empty DB and on a DB
with 1000 rows are different tests; without explicit fixture
profiles, Playwright specs become flaky and order-dependent.

The translation guide alone is not enough — every AC implicitly
asks for a starting state. DESIGN must surface that requirement
and route any missing seeding capability back to the spec.

## Four patterns (pick by signal)

| # | Pattern | How it works | When to pick |
|---|---|---|---|
| 1 | **Backend test API** | Backend exposes `POST /internal/test/seed/<entity>` and `POST /internal/test/reset` only under a non-production profile (e.g. `local`, `e2e`). Playwright `beforeEach` calls them. | Repeatable seeding, complex aggregates, hermetic. **Default for Skills Hub.** |
| 2 | **Direct DB seed** | Playwright global setup connects via JDBC / psql, runs SQL fixtures. | Greenfield projects without an API; or read-only seeding when the domain layer is irrelevant. **Avoid** when the domain has invariants (event publication, audit log) — bypassing the aggregate breaks them. |
| 3 | **Per-test production API CRUD** | Each test creates entities through the real `POST /api/v1/...` endpoint, asserts behavior, deletes in `afterEach`. | An entity is ALREADY exposed via production API and creation is cheap. Pairs well with pattern 1 for setup, 3 for per-test detail. |
| 4 | **DB snapshot / restore** | Capture a known-good DB state, restore before each suite. | Heavy datasets, slow seeding, suites where speed dominates. Hard to maintain — schema migrations break snapshots. |

**Skills Hub recommendation: pattern 1 (primary) + pattern 3 (where production API already exists).**

Reasons:
- The Spring Data JDBC + Modulith outbox pattern (per ADR-002)
  makes pattern 2 risky — direct INSERTs skip `@DomainEvents` and
  break the audit log + outbox.
- Skills Hub already runs Spring Security as `permitAll` (MVP) so
  exposing a `local`/`e2e`-only test endpoint has no auth burden.
- Testcontainers per-run means no persistent DB — pattern 4's
  snapshot benefit is moot.

## State taxonomy — what to test

Each AC implicitly needs ONE of these starting profiles. DESIGN
must label every AC with its profile.

| Profile | Use case | Skills Hub example |
|---|---|---|
| `empty` | First-time UX, no data state | Skill list page renders empty-state copy when no skill exists |
| `single` | Minimal positive case | Search returns the one skill matching a keyword |
| `paged` | Pagination boundary | List with 25 skills paginates at 20 per page |
| `full` | Stress / sort behavior | Search across 100+ skills returns sorted top-10 |
| `mixed-visibility` | Authorisation / filtering | User sees own + public skills; not draft / archived skills owned by others |
| `multi-role` | Role-gated UI | Author sees Edit button; viewer does not |
| `boundary` | Size / encoding limits | Skill name 200 chars; multi-language description; emoji in tag |

Tag the test accordingly: `@profile-empty`, `@profile-paged`, etc.
This also gives `--grep` filtering when a fixture profile breaks.

## Per-AC decision protocol

DESIGN Step 3 (per AC):

1. Identify the implicit starting profile from the AC text.
2. Check whether the seeding mechanism for that profile exists:
   - Production API for the entities? `grep` the OpenAPI doc.
   - Backend test endpoint? `grep` for `@Profile("local")` or
     `@Profile("e2e")` controllers under `internal/test/...`.
3. If missing → emit a finding the same way as a missing locator:
   "AC-N requires backend seed endpoint `POST /internal/test/seed/skill`"
   so the caller adds it to the spec's task plan.
4. If present → the rendered spec test uses it via the helper at
   `assets/fixtures-helper-template.ts` (copy + adapt per spec).

A spec where every AC needs a missing seed endpoint is a signal
that this spec depends on a backend testing infrastructure spec —
escalate the same way as a UNTESTABLE classification in
`verifying-quality`. Do not proceed by writing CSS-chain locators
or by faking state through UI clicks.

## Anti-patterns to refuse

- **Seeding through the UI itself**: clicking "Add" to create
  fixture data inside `beforeEach`. Slow, fragile, and confuses
  fixture errors with assertion errors.
- **Shared mutable state across tests**: a single seeded user that
  accumulates data across tests. Order-dependent suite, flaky
  parallelisation.
- **Hard-coded DB rows assumed to exist** (e.g. "skill ID 42 must
  be the search target"). Migrations or other tests' seeds will
  collide.
- **Cleanup in `afterAll` instead of `afterEach`**: if a test
  fails mid-suite, residue persists for downstream tests. Always
  reset in `beforeEach` (idempotent), not `afterEach` (skipped on
  exception).
