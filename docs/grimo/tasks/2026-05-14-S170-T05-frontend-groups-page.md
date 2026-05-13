# S170-T05: Groups management frontend

## Spec
S170 — Group tree principal model

## BDD
Given admin opens the group management page
When admin selects `Cloud`
Then the page shows child Group controls and the member list
And all visible labels and buttons are zh-TW

## Target Files
- `frontend/src/api/groups.ts`
- `frontend/src/pages/GroupsPage.tsx`
- `frontend/src/components/GroupTree.tsx`
- `frontend/src/components/GroupEditor.tsx`
- `frontend/src/App.tsx`
- `frontend/src/pages/GroupsPage.test.tsx`
- `docs/grimo/glossary.md`

## AC
- AC-13

## Depends On
- S170-T04

## Status
pending
