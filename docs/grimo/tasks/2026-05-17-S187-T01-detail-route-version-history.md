# S187-T01: Detail edit route and version-history-only tab

## 對應規格
S187：Skill SKILL.md 編輯頁

## 這個 task 要做什麼
這個 task 完成後，使用者在 skill 詳情頁點「編輯」會進入 `/skills/{id}/edit`，不再打開小型 `EditSkillModal`。同時，詳情頁的「版本」tab 只保留 Version History，不能再在詳情頁直接新增版本。這會把「看版本紀錄」和「建立新版本」拆成兩個畫面。

## 使用者情境（BDD）
Given（前提）Alice 對 `skill-docker` 有 write permission，並開啟 `/skills/skill-docker`
When（動作）Alice 點 PageHeader 的「編輯」
Then（結果）瀏覽器 URL 變成 `/skills/skill-docker/edit`
And（而且）畫面不打開 `EditSkillModal`
And（而且）Alice 點「版本」tab 時，只看到版本紀錄，沒有「新增版本」、file dropzone、版本號 input、上傳按鈕

## 研究來源
- `docs/grimo/specs/2026-05-16-S187-skill-md-edit-page.md`
- `frontend/src/pages/SkillDetailPage.tsx`
- `frontend/src/pages/SkillDetailPage.test.tsx`
- `frontend/src/components/v2/tabs/VersionsTabV2.tsx`

## 先做 POC
- POC：not required — 只改既有 React route/navigation 與既有 tab composition，不新增套件、SDK、DB schema 或 framework SPI。

## 正式程式怎麼做
- Class / file 名稱：
  - `frontend/src/App.tsx`
  - `frontend/src/pages/SkillDetailPage.tsx`
  - `frontend/src/pages/SkillDetailPage.test.tsx`
- 入口：Skill detail page 的 PageHeader edit action 與 Versions tab render block。
- 必要行為：
  - 新增 `/skills/:id/edit` route，先可指向 placeholder 或後續 T02 的 `SkillEditPage`。
  - `SkillDetailPage` 的 edit button 改成 `navigate(`/skills/${skill.id}/edit`)`。
  - 移除 `editOpen` state 與 `EditSkillModal` render path。
  - 「版本」tab 只 render `VersionsTabV2`，移除 inline `AddVersionForm`。
  - 保留 `canEdit` 對 edit button 顯示的既有 permission 行為。

## 單元測試 / 整合測試
- `SkillDetailPage.test.tsx`
  - `AC-S187-1: 詳情頁編輯按鈕導向 edit page`
  - `AC-S187-2: 版本頁籤只顯示 Version History`

## 會改哪些檔案
- `frontend/src/App.tsx`
- `frontend/src/pages/SkillDetailPage.tsx`
- `frontend/src/pages/SkillDetailPage.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- SkillDetailPage`

## 前置條件
- 無

## 狀態
pending（待做）
