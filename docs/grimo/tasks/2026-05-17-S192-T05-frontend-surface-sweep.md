# S192-T05: Frontend author label surface sweep

## 對應規格
S192：作者顯示名稱一致性收斂

## 這個 task 要做什麼
把 PublishReview、semantic result cards、MySkills subscription row、ReviewsPanel、CommentList、AnalyticsPage 等 user-facing surfaces 對齊 S192 helper 規則。完成後一般 UI 不再把 `.author` / `.authorId` 直接當人名顯示。

## 使用者情境（BDD）
Given（前提）API payload 中 `author="u_f7eb3a"` / `authorId="u_f7eb3a"`，且 display companion field 是 `Sam Zhu`
When（動作）使用者打開發佈結果、首頁 semantic card、我的技能訂閱、評論列表、需求留言或 analytics page
Then（結果）普通可見文字顯示 `Sam Zhu` 或完全不顯示作者欄
And（而且）route link / install command 仍可使用 technical segment

## 研究來源
- `docs/grimo/specs/2026-05-17-S192-author-display-name-completion.md` AC-S192-1, AC-S192-3, AC-S192-4, AC-S192-5, AC-S192-6, AC-S192-12
- `frontend/src/pages/PublishReviewPage.tsx`
- `frontend/src/pages/HomePage.tsx`
- `frontend/src/pages/MySkillsPage.tsx`
- `frontend/src/pages/AnalyticsPage.tsx`
- `frontend/src/components/ReviewsPanel.tsx`
- `frontend/src/components/CommentList.tsx`

## 先做 POC
- POC：not required — existing Vitest/RTL component tests cover these surfaces.

## 正式程式怎麼做
- Class / file 名稱：affected React pages/components
- 入口：rendered visible text and links
- 必要行為：
  - PublishReview 作者 row uses `getDisplayName`
  - HomePage semantic cards consume `authorDisplayName` / `authorHandle`
  - MySkills subscription row stops `row.authorDisplayName ?? row.author`
  - ReviewsPanel and CommentList display companion fields, but delete button compares ids
  - AnalyticsPage visible row text stays skill name/download count only; route may use technical segment

## 單元測試 / 整合測試
- `PublishReviewPage.test.tsx`
  - `AC-S192-1`
- `HomePage.test.tsx` or hook/component test
  - `AC-S192-3`
- `AnalyticsPage.test.tsx`
  - `AC-S192-4`
- `ReviewsPanel.test.tsx`
  - `AC-S192-5`
- `CommentList.test.tsx`
  - `AC-S192-6`
- `MySkillsPage.test.tsx`
  - visible author label guard

## 會改哪些檔案
- `frontend/src/pages/PublishReviewPage.tsx`
- `frontend/src/pages/HomePage.tsx`
- `frontend/src/pages/MySkillsPage.tsx`
- `frontend/src/pages/AnalyticsPage.tsx`
- `frontend/src/components/ReviewsPanel.tsx`
- `frontend/src/components/CommentList.tsx`
- related frontend tests and types

## 驗證方式
執行：`cd frontend && npm test -- PublishReviewPage HomePage MySkillsPage AnalyticsPage ReviewsPanel CommentList`

## 前置條件
- S192-T04 PASS
- S192-T02 PASS
- S192-T03 PASS

## 狀態
PASS

## Result
Date: 2026-05-17
Test: `PublishReviewPage.test.tsx`, `HomePage.test.tsx`, `MySkillsPage.test.tsx`, `AnalyticsPage.test.tsx`, `ReviewsPanel.test.tsx`, `RequestDetailPage.test.tsx`
Files changed:
- `frontend/src/pages/PublishReviewPage.tsx` (modified)
- `frontend/src/pages/PublishReviewPage.test.tsx` (new)
- `frontend/src/pages/HomePage.test.tsx` (modified)
- `frontend/src/pages/MySkillsPage.tsx` (modified)
- `frontend/src/pages/MySkillsPage.test.tsx` (modified)
- `frontend/src/pages/AnalyticsPage.test.tsx` (modified)
- `frontend/src/components/ReviewsPanel.tsx` (modified)
- `frontend/src/components/ReviewsPanel.test.tsx` (modified)
- `frontend/src/components/CommentList.tsx` (modified)
- `frontend/src/pages/RequestDetailPage.test.tsx` (modified)
Notes:
- RED: `cd frontend && npm test -- PublishReviewPage HomePage MySkillsPage AnalyticsPage ReviewsPanel CommentList RequestDetailPage` → 5 failed（4 個 raw author id visible label；1 個 Analytics href encoding 測試期待修正）。
- GREEN: same command → 6 files / 48 tests PASS。
- Extra check: `cd frontend && npm run typecheck` → PASS。
