# S187-T02: Skill edit page shell and SKILL.md text mode

## 對應規格
S187：Skill SKILL.md 編輯頁

## 這個 task 要做什麼
這個 task 完成後，使用者開啟 `/skills/{id}/edit` 會看到完整的 SKILL.md 編輯頁。頁面預設是「貼上文本」mode，會讀取 latest `SKILL.md` 並放進 textarea；frontmatter 有 `name` 和 `description` 時顯示通過，缺 `description` 時「儲存新版本」不能按。

## 使用者情境（BDD）
Given（前提）`GET /api/v1/skills/skill-docker/files/SKILL.md` 回傳 latest SKILL.md 文字
When（動作）Alice 開啟 `/skills/skill-docker/edit`
Then（結果）頁面預設選中「貼上文本」
And（而且）textarea 內已有 latest SKILL.md
And（而且）frontmatter check 顯示 `name` 與 `description` 通過
And（而且）刪掉 `description:` 後「儲存新版本」disabled

## 研究來源
- `docs/grimo/specs/2026-05-16-S187-skill-md-edit-page.md`
- `frontend/src/pages/PublishPage.tsx`
- `frontend/src/hooks/useSkillFiles.ts`
- `frontend/src/lib/frontmatter.ts` 或現有 frontmatter validation helper

## 先做 POC
- POC：not required — text mode 可沿用 `/publish` 的 textarea + `validateFrontmatter` pattern，API 讀檔可沿用 `useSkillFile(id, 'SKILL.md')`。

## 正式程式怎麼做
- Class / file 名稱：
  - `frontend/src/pages/SkillEditPage.tsx`
  - `frontend/src/pages/SkillEditPage.test.tsx`
- 入口：`SkillEditPage`
- 必要行為：
  - 從 route param 取得 `id`，讀 `useSkill(id)` 與 `useSkillFile(id, 'SKILL.md')`。
  - 預設 mode 是 `text`，textarea 以 latest SKILL.md 內容預填。
  - 若 latest SKILL.md 讀取失敗，顯示錯誤並允許切換 upload mode。
  - 顯示 frontmatter check：`name` pass、`description` pass/fail。
  - text mode 下缺 `description` 或 `name` 時，「儲存新版本」disabled。
  - 編輯頁使用 zh-TW 文字，主要按鈕為「儲存新版本」、「儲存分類」、「取消」。

## 單元測試 / 整合測試
- `SkillEditPage.test.tsx`
  - `AC-S187-3: edit page 貼上文本 mode 預填 latest SKILL.md`
  - `AC-S187-3: 缺 description 時儲存新版本 disabled`

## 會改哪些檔案
- `frontend/src/pages/SkillEditPage.tsx`
- `frontend/src/pages/SkillEditPage.test.tsx`
- `frontend/src/App.tsx`

## 驗證方式
執行：`cd frontend && npm test -- SkillEditPage`

## 前置條件
- S187-T01 PASS

## 狀態
PASS（2026-05-17）

## 執行結果
- Red：`cd frontend && npm test -- SkillEditPage` → 失敗，`SkillEditPage` 尚未存在。
- Green：`cd frontend && npm test -- SkillEditPage` → PASS，2 tests。
- 實作：
  - `frontend/src/App.tsx` 將 `/skills/:id/edit` 接到 `SkillEditPage`。
  - `frontend/src/pages/SkillEditPage.tsx` 新增 edit page shell；預設「貼上文本」mode，讀 `useSkillFile(id, 'SKILL.md')` 後預填 textarea。
  - `frontend/src/pages/SkillEditPage.tsx` 沿用 `validateFrontmatter`，`name` / `description` 缺失時顯示檢查結果並停用「儲存新版本」。
  - `frontend/src/pages/SkillEditPage.test.tsx` 覆蓋 latest SKILL.md 預填與缺 `description` disabled。
