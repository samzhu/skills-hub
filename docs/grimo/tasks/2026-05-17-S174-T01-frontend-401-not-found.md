# S174-T01: Frontend 401 Not-Found State

## 對應規格
S174：Skill detail anonymous 401 not-found UX

## 這個 task 要做什麼
`SkillDetailPage` 收到 detail API 401 時，畫面要和 400 / 403 / 404 一樣顯示「找不到此技能」。這會讓匿名使用者開到不可見或 production 仍回 401 的 skill detail URL 時，不再看到「載入技能時發生錯誤」和 retry 提示。

## 使用者情境（BDD）
Given（前提）使用者開 `/skills/00000000-0000-0000-0000-000000000000`，frontend fetch mock 回 401
When（動作）`SkillDetailPage` 進入 error state
Then（結果）畫面顯示「找不到此技能」
And（而且）不顯示「請稍後重試或重新整理頁面」
And（而且）「返回列表」連到 `/browse`

## 研究來源
- `docs/grimo/specs/2026-05-17-S174-skill-detail-anonymous-not-found-ux.md`
- `frontend/src/pages/SkillDetailPage.tsx`
- `frontend/src/pages/SkillDetailPage.test.tsx`

## 先做 POC
- POC：not required — 只擴充既有 S153 error status list；沒有新套件、SDK、瀏覽器 fixture 或框架假設。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/pages/SkillDetailPage.tsx`
- 入口：`SkillDetailPage` error render branch
- 必要行為：
  - `ApiError.status === 401` 時歸類為 `isUnviewable`
  - 400 / 403 / 404 既有行為不可變
  - 500 / network error 仍顯示 retry 提示

## 單元測試 / 整合測試
- `SkillDetailPage.test.tsx`
  - `S174 AC-S174-3: 401 Unauthorized shows 找不到此技能 (no retry hint)`
  - 既有 `AC-2: 500 server error shows generic error + retry hint` 保持通過

## 會改哪些檔案
- `frontend/src/pages/SkillDetailPage.tsx`
- `frontend/src/pages/SkillDetailPage.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- SkillDetailPage`

## 前置條件
- 無

## 狀態
PASS

## Result

RED：
- `cd frontend && npm test -- SkillDetailPage` -> FAIL，新增 `S174 AC-S174-3` test 找不到「找不到此技能」；畫面仍顯「載入技能時發生錯誤」和「請稍後重試或重新整理頁面」。

GREEN：
- `frontend/src/pages/SkillDetailPage.tsx` 將 `ApiError.status === 401` 加入 `isUnviewable`，保留 500 / network error retry 分支。
- `cd frontend && npm test -- SkillDetailPage` -> PASS，1 file / 14 tests。
