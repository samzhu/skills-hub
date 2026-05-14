# S169-T06: Share modal 角色化 UI 與 detail action gating

## Spec
S169 — CQRS permission contract

## BDD
Given skill detail API 提供 `viewerPermissions` 與 share target API 可查 user/group/public
When 使用者打開 SkillDetailPage 與 Share modal
Then action button 顯示由 `viewerPermissions` 控制，不再用 `skill.ownerId === me.sub`
And Share modal 只顯示 `可檢視` / `可編輯` role 選項，不暴露 read/write/delete 原始操作
And target picker 支援 people、Groups、public

## Target Files
- `frontend/src/types/skill.ts`
- `frontend/src/api/shareTargets.ts`
- `frontend/src/pages/SkillDetailPage.tsx`
- `frontend/src/pages/SkillDetailPage.test.tsx`
- `frontend/src/components/ShareModal.tsx`
- `frontend/src/components/ShareModal.test.tsx`

## Depends On
- S169-T03 PASS
- S169-T04 PASS

## Status
pending
