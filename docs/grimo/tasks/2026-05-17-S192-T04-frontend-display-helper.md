# S192-T04: Frontend display helper split

## 對應規格
S192：作者顯示名稱一致性收斂

## 這個 task 要做什麼
前端目前 `getDisplayName(...)` 最後會回傳 `author`，所以 UI 在資料缺失時會顯示 `u_<id>`。這個 task 把 helper 拆成「人類可讀 label」與「技術 route/install segment」兩種用途。

## 使用者情境（BDD）
Given（前提）一個 skill 有 `author="u_f7eb3a"`, `authorDisplayName="Sam Zhu"`，但沒有 handle
When（動作）UI 顯示作者 label 與 install command
Then（結果）作者 label 顯示 `Sam Zhu`
And（而且）install command 可以使用 `u_f7eb3a` 作為 technical segment

## 研究來源
- `docs/grimo/specs/2026-05-17-S192-author-display-name-completion.md` §2.5, AC-S192-11, AC-S192-12
- `frontend/src/lib/displayName.ts`
- `frontend/src/lib/displayName.test.ts`

## 先做 POC
- POC：not required — pure TypeScript helper，無新 dependency。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/lib/displayName.ts`
- 入口：all user-facing author labels and technical route/install call sites
- 必要行為：
  - `getDisplayName` 不回 raw `u_<id>`；缺 display data 時回空字串或明確 fallback label，讓測試逼資料來源補齊
  - 新增 `getAuthorRouteSegment`：prefer `authorHandle`，fallback `author`
  - 更新 types/comments 表明 `author` 是 behavior-bearing id，不是 label

## 單元測試 / 整合測試
- `displayName.test.ts`
  - `@DisplayName("AC-S192-11: getDisplayName never returns raw platform user id")`
  - `@DisplayName("AC-S192-12: getAuthorRouteSegment may fall back to platform user id for commands and routes")`

## 會改哪些檔案
- `frontend/src/lib/displayName.ts`
- `frontend/src/lib/displayName.test.ts`
- `frontend/src/types/skill.ts`
- frontend API type files as needed

## 驗證方式
執行：`cd frontend && npm test -- displayName`

## 前置條件
- S192-T01 PASS

## 狀態
PASS

## Result
Date: 2026-05-17
Test: `displayName.test.ts` (`frontend/src/lib/displayName.test.ts`)
Files changed:
- `frontend/src/lib/displayName.ts` (modified)
- `frontend/src/lib/displayName.test.ts` (modified)
- `frontend/src/components/v2/InstallCard.tsx` (modified)
- `frontend/src/types/skill.ts` (modified)
- `frontend/src/api/reviews.ts` (modified)
- `frontend/src/api/skills.ts` (modified)
Notes:
- RED: `cd frontend && npm test -- displayName` → 5 failed（`getDisplayName` 仍回 `u_a3f9c1`；`getAuthorRouteSegment` 尚不存在）。
- GREEN: `cd frontend && npm test -- displayName` → 1 file / 8 tests PASS。
- Extra check: `cd frontend && npm test -- displayName InstallCard` → 2 files / 15 tests PASS；`cd frontend && npm run typecheck` → PASS。
