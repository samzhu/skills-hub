# S183/S184 Diff Split Blocker — 2026-05-16

## Context

Heartbeat tick `2026-05-15T23:35:29Z` resumed after S175/S181 shipped as
`v4.64.0`. The current repo state contains active S183 UI work and active S184
API/visibility work in the same dirty checkout.

## Observed Repo State

`docs/grimo/specs/spec-roadmap.md` says:

```text
S183 | Security Risk Lights + Issue Findings UI | ⏳ Dev — task tests PASS；frontend verify blocked by S184 fixture drift
```

`docs/grimo/specs/2026-05-16-S183-security-report-issue-code-ui.md` has
`Status: ✅ Done` and §7 implementation results, but `docs/grimo/tasks/` has no
S183 task files. `docs/grimo/specs/2026-05-16-S184-api-empty-response-contract.md`
also exists and is marked `Status: ✅ implemented`.

## Why This Tick Did Not Commit S183 Code

The dirty diff mixes S183 and S184 in the same files, so staging by file would
create a cross-spec commit.

Concrete examples:

- `frontend/src/components/v2/PageHeader.tsx` contains S183 `skill.riskLevel`
  threading for `SecurityHeroCard`, and S184 `VisibilityToggleButton
  visibility={skill.visibility}` wiring in the same file.
- `frontend/src/types/skill.ts` contains S184 `visibility` type changes that
  S183 tests now depend on.
- `frontend/src/api/skills.ts`, `frontend/src/api/grants.ts`,
  `frontend/src/components/VisibilityToggleButton.tsx`, and related tests are
  S184 empty-response / visibility-command work, not S183 security-report UI.
- Backend files such as `SkillCommandController.java`,
  `SkillGrantController.java`, and `SkillGrantService.java` contain S184
  visibility command changes.

Committing all dirty files would mix S183 + S184. Committing only S183-looking
frontend files would likely leave typecheck/test state depending on unstaged
S184 changes.

## Required Next Step

Before shipping either spec, split the checkout into one of these safe shapes:

1. Commit S184 first if its API/visibility contract is the dependency that makes
   frontend typecheck green, then re-run S183 frontend verification and commit
   S183 UI separately.
2. Or manually stage S183 hunks only with an index-level split, verify the staged
   S183 patch in a clean worktree, then commit S183 before S184.

Do not stage the whole dirty tree as one commit.

## Verification This Tick

No new tests were run in this tick. This note is a blocker/progress artifact only.

## Result

BLOCKED by mixed S183/S184 dirty state. The next tick should split the dirty
checkout before running `$planning-tasks S183` / `$planning-tasks S184` or
`$shipping-release`.
