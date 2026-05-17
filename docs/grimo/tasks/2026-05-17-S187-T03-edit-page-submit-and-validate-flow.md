# S187-T03: Edit page version submit and validation flow

## 對應規格
S187：Skill SKILL.md 編輯頁

## 這個 task 要做什麼
這個 task 完成後，edit page 的 text mode 和 upload mode 都會用既有 `addVersion(id, file, version?)` 建立新版本。成功後導到 `/publish/validate?id={id}&mode=version`；重複版本回 409 時，使用者留在 edit page 並看到錯誤，不覆寫舊版本。

## 使用者情境（BDD）
Given（前提）Alice 在 `/skills/skill-docker/edit` 選「上傳檔案」
When（動作）Alice 選 `skill.zip`、輸入版本 `1.1.0`、點「儲存新版本」
Then（結果）前端呼叫 `PUT /api/v1/skills/skill-docker/versions`，multipart 內有 `file=skill.zip` 與 `version=1.1.0`
And（而且）成功後導到 `/publish/validate?id=skill-docker&mode=version`
And（而且）duplicate version 回 409 時，頁面停在 edit page 並顯示錯誤訊息

## 研究來源
- `docs/grimo/specs/2026-05-16-S187-skill-md-edit-page.md`
- `frontend/src/api/skills.ts`
- `frontend/src/api/skills.test.ts`
- `frontend/src/pages/PublishPage.tsx`
- `frontend/src/pages/PublishValidatePage.tsx`

## 先做 POC
- POC：not required — S188 已驗證 `addVersion` 空白 version 不 append FormData；這裡只 reuse 該 contract。

## 正式程式怎麼做
- Class / file 名稱：
  - `frontend/src/pages/SkillEditPage.tsx`
  - `frontend/src/pages/SkillEditPage.test.tsx`
  - `frontend/src/pages/PublishValidatePage.tsx`
  - `frontend/src/pages/PublishValidatePage.test.tsx`
- 入口：Skill edit page submit handler 與 PublishValidatePage query params。
- 必要行為：
  - upload mode：送 selected `File` + optional version。
  - text mode：用 `new File([skillMdText], 'SKILL.md', { type: 'text/markdown' })` 送出。
  - version 空白時傳 `undefined` 或 trim 後不送，沿用 S188 後端自動流水號。
  - 成功後 `navigate(`/publish/validate?id=${id}&mode=version`)`。
  - `PublishValidatePage` 讀 `mode=version` 時，文案顯示「新版本驗證中」或等價文字；riskLevel 不為 null 後導回 `/skills/${id}`。
  - 沒有 `mode=version` 時，建立新 skill flow 維持導到 `/publish/review?id=${id}`。
  - duplicate version / API error 顯示錯誤，不導頁。

## 單元測試 / 整合測試
- `SkillEditPage.test.tsx`
  - `AC-S187-4: upload mode 建立新版本後進驗證中`
  - `AC-S187-7: duplicate version 不覆寫舊版本且留在 edit page`
- `PublishValidatePage.test.tsx`
  - `AC-S187-10: version 驗證完成後導回 detail`
  - `AC-S187-10: create flow 仍導向 publish review`

## 會改哪些檔案
- `frontend/src/pages/SkillEditPage.tsx`
- `frontend/src/pages/SkillEditPage.test.tsx`
- `frontend/src/pages/PublishValidatePage.tsx`
- `frontend/src/pages/PublishValidatePage.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- SkillEditPage PublishValidatePage`

## 前置條件
- S187-T02 PASS

## 狀態
PASS（2026-05-17）

## 執行結果
- Red：`cd frontend && npm test -- SkillEditPage PublishValidatePage` → 失敗，edit page 尚未提供 `Skill 套件` / `版本號` 欄位，`PublishValidatePage` 尚未支援 `mode=version`。
- Green：`cd frontend && npm test -- SkillEditPage PublishValidatePage` → PASS，2 files / 10 tests。
- 實作：
  - `frontend/src/pages/SkillEditPage.tsx` 新增 version input、upload file input，text mode 以 `new File([skillMdText], 'SKILL.md')` 送出。
  - `frontend/src/pages/SkillEditPage.tsx` 儲存時呼叫 `addVersion(id, file, version)`；成功後導到 `/publish/validate?id={id}&mode=version`。
  - `frontend/src/pages/SkillEditPage.tsx` duplicate version / API error 顯示 `localizeApiError(...)`，不導頁。
  - `frontend/src/pages/PublishValidatePage.tsx` 讀 `mode=version` 時顯示「新版本驗證中」，riskLevel 完成後導回 `/skills/{id}`；create flow 維持導到 `/publish/review?id={id}`。
