# S170 Design Checkpoint — Group tree principal model

## Tick

2026-05-14 01:37 Asia/Taipei

## Read Commands

```bash
git worktree list
sed -n '1,260p' docs/grimo/specs/2026-05-13-S170-group-tree-principal-model.md
sed -n '1,220p' docs/grimo/specs/2026-05-13-S169-cqrs-permission-contract.md
```

## Repo State

- `git worktree list` shows only `/Users/samzhu/workspace/github-samzhu/skills-hub` on `main`.
- S147-T01 remains blocked because `docs/grimo/tasks/2026-05-13-S147-T01-finding-report-contract.md` requires user confirmation after T00 POC.
- Existing uncommitted changes in S147 spec / roadmap / task files were not touched by this tick.

## S170 Facts Read This Tick

- `groups.kind` is display-only. DB behavior, child rules, membership rules, ACL rules, and delete behavior must not change by `COMPANY / DEPARTMENT / TEAM / OTHER`.
- `groups`, `group_closure`, and `group_members` are enough for the scenario anchor: `Acme -> Cloud -> Platform Team`, plus root `AI Enablement`.
- `PrincipalContextService.currentPrincipalKeys()` must output Bob's `user:u_bob` plus direct groups and all ancestors as `group:<id>`.
- Group ids use `g_<6hex>`; ACL principal keys use `group:<id>`, where `<id>` is the `groups.id` value.

## S169 Handoff Facts

- S169 should not know whether `group:g_d4e5f6` is a company, department, team, or other human label.
- S169 consumes only S170 principal keys and then expands roles into ACL entries such as `group:g_d4e5f6:read`.
- S169 detail/action work depends on S170's group principal model; therefore S170 should finish its §3 AC and file plan before S169 planning-tasks starts.

## Next Tick Candidate

Continue S170 design by adding §3 SBE acceptance criteria that prove:

1. `TEAM` can be a root group.
2. `kind` does not change parent/child or membership behavior.
3. Bob can belong to both `Platform Team` and root `AI Enablement`.
4. `PrincipalContextService` returns direct group principals plus ancestor group principals without dropping either path.
