# S192-T07: SkillCard fixture display data

## 對應規格
S192：作者顯示名稱一致性收斂

## 這個 task 要做什麼
Phase 4 `npm test` 發現 `SkillCard.test.tsx` 的 base fixture 仍期待 `author: 'samzhu'` 直接顯示；這和 S192 display-vs-id 規則衝突。此 task 把 fixture 改成 raw `author` 只代表平台識別碼，visible label 由 `authorHandle` 或 `authorDisplayName` 供給。

## 使用者情境（BDD）
Given（前提）SkillCard 收到 `author="u_a3f9c1"` 與 `authorHandle="samzhu"`
When（動作）卡片渲染作者列
Then（結果）畫面顯示 `samzhu`
And（而且）畫面不顯示 `u_a3f9c1`

## 研究來源
- `docs/grimo/specs/2026-05-17-S192-author-display-name-completion.md` AC-S192-8, AC-S192-11
- `frontend/src/lib/displayName.ts`
- Phase 4 failure: `cd frontend && npm test` failed at `frontend/src/components/SkillCard.test.tsx:42`

## 先做 POC
- POC：not required — test fixture correction only.

## 正式程式怎麼做
- 更新 `frontend/src/components/SkillCard.test.tsx` base fixture：
  - `author` 改成 `u_a3f9c1`
  - 新增 `authorHandle: 'samzhu'`
  - 基本欄位測試補 `queryByText('u_a3f9c1')` negative assertion

## 單元測試 / 整合測試
- `cd frontend && npm test -- SkillCard`

## 會改哪些檔案
- `frontend/src/components/SkillCard.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- SkillCard`

## 前置條件
- S192-T01~T06 PASS

## 狀態
PASS

## Result

- RED: `cd frontend && npm test` → failed at `frontend/src/components/SkillCard.test.tsx:42` because the fixture expected visible text `samzhu` while only providing `author: 'samzhu'`; after S192, `getDisplayName(...)` correctly refuses to display raw `author`.
- GREEN: `frontend/src/components/SkillCard.test.tsx` base fixture now uses `author: 'u_a3f9c1'` and `authorHandle: 'samzhu'`; the basic render test still sees `samzhu` and also verifies `u_a3f9c1` is not visible.
- Verification: `cd frontend && npm test -- SkillCard` → 2 files / 11 tests PASS.
