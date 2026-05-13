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
pass

## Result
- RED: `npm test -- --run src/pages/GroupsPage.test.tsx` 先失敗於 `Failed to resolve import "./GroupsPage"`，確認群組管理頁尚未存在。
- GREEN: 新增 `/groups` route、`fetchGroupTree()` / `searchGroups()` API client、`GroupTree` 與 `GroupEditor`，管理者點選 `Cloud` 後可看到子群組控制、成員區塊、`group:<id>` principal 與 zh-TW 按鈕文案。
- Verified: `npm test -- --run src/pages/GroupsPage.test.tsx src/App.test.tsx` → 2 files / 5 tests PASS。
