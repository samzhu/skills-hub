# S170 AC Readiness Check

## Tick

2026-05-14 02:07 Asia/Taipei

## Read Commands

```bash
git worktree list
git status --short
nl -ba docs/grimo/specs/2026-05-13-S170-group-tree-principal-model.md | sed -n '250,520p'
```

## Result

S170 already has §3 SBE acceptance criteria and §5 test-file mapping. This tick did not add duplicate AC text.

## AC Coverage Read This Tick

- AC-1 covers root + child Group creation and `group_closure` self/ancestor rows.
- AC-2 and AC-3 prove `kind` is display-only for child creation and direct membership.
- AC-4 through AC-7 cover `PrincipalContextService` output, coexistence of physical department membership plus root Team membership, membership removal, and subtree move behavior.
- AC-8 covers cycle rejection.
- AC-9 and AC-10 cover tree/search APIs and `principalKey = group:<id>`.
- AC-11 covers archive behavior and active principal removal.
- AC-12 covers duplicate sibling slug rejection.
- AC-13 covers zh-TW group management UI.
- AC-14 anchors the unit test suite to `Acme -> Cloud -> Platform Team` plus root `AI Enablement`.
- AC-15 covers `g_<6hex>` group id generation and collision retry.

## Readiness Verdict

S170 is ready for `/planning-tasks` to create implementation tasks from the existing §5 Test Files table.

Recommended first task: backend migration + `GroupIdGenerator`, because AC-1 and AC-15 give the smallest foundation slice and unblock the rest of the backend tests.

## Unrelated Worktree Changes

Existing uncommitted S147 spec / roadmap / task files are still present and were not touched by this tick.
