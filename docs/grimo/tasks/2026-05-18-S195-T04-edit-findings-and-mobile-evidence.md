# S195-T04: Edit page shows validation findings and mobile dropzone evidence

## 對應規格
S195：Skill edit upload validation UX

## 這個 task 要做什麼
`frontend/src/pages/SkillEditPage.tsx` 在 `addVersionMutation` 失敗時，要優先把第一筆 `severity === "error"` finding 的 `title` 顯示成錯誤主標。完成後，使用者不只看到 `SKILL.md validation failed`，還會看到 `metadata: key 'owner' nested object is not supported`，錯誤區塊也列出 `error · skill_md · ...`。同一個 task 也要補 mobile 寬度下 dropzone 不溢出的瀏覽器證據。

## 使用者情境（BDD）
Given（前提）`PUT /api/v1/skills/skill-docker/versions` 回 400 body 且 `findings[0].title` 是 `metadata: key 'owner' nested object is not supported`
When（動作）使用者在 edit page upload mode 點「儲存新版本」
Then（結果）錯誤區塊主標顯示 `metadata: key 'owner' nested object is not supported`
And（而且）次要文字顯示 `儲存新版本失敗：SKILL.md validation failed`
And（而且）列表顯示 `error · skill_md · metadata: key 'owner' nested object is not supported`
And（而且）畫面不只顯示 `SKILL.md validation failed`

Given（前提）viewport width 是 390px
When（動作）進入 edit page upload mode
Then（結果）「拖拽 zip 或 md 檔到此處」文字在卡片內可見
And（而且）主要 action 按鈕仍可見

## 研究來源
- `frontend/src/api/client.ts`：`ApiError.is()` 要用 name-based check，不要用 `instanceof` 做主要判斷。
- `frontend/src/pages/PublishFailedPage.tsx`：structured findings row 的既有顯示 pattern。
- `frontend/src/pages/SkillEditPage.tsx`：目前 `ErrorState` 只顯示 `localizeApiError(addVersionMutation.error)`。
- `e2e/tests/S187-skill-edit-page.spec.ts`：已有 edit page browser flow，可延伸驗 mobile edit controls 或新增 S195 spec。

## 先做 POC
- POC：not required — `ApiError.findings` 由 S195-T02 補齊，UI 只做 deterministic rendering；mobile 證據可用既有 Vite/Playwright/browser flow 驗。
- Fixture：
  - `findings[0].severity = "warning"` 且 `findings[1].severity = "error"`：主要訊息取 error 那筆。
  - `findings` 空或缺失：回到既有 `localizeApiError()` 文案。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/pages/SkillEditPage.tsx`
- 入口：`addVersionMutation.isError` render path
- 必要行為：
  - 新增 `primaryValidationMessage(error: unknown): string | null`，用 `ApiError.is(error)` 判斷。
  - 優先回傳第一筆 `severity === "error"` finding 的 `title`；沒有 error finding 才回第一筆 finding title；沒有 findings 回 null。
  - 新增 `ValidationFindingsList({ findings })`，每列顯示 `severity · section · title`，`hint` 有值時顯示在下一行。
  - error callout 主標用 primary finding title；次要文字固定包含 `儲存新版本失敗：${localizeApiError(error)}`。
  - 沒有 findings 時，現有錯誤顯示不退化。
  - mobile 寬度下 dropzone 外層用 `min-w-0`、`break-words` 或既有 Tailwind responsive class 確保文字和 actions 不互相擠壓。
- Finding / response / DB 欄位：
  - `section`: 目前後端 fixture 使用 `skill_md`。
  - `severity`: `error` 或 `warning`。
  - `title`: row 的主要文字；第一筆 error title 是 callout 主標。
  - `hint`: nullable；有值才 render。

## 單元測試 / 整合測試
- `frontend/src/pages/SkillEditPage.test.tsx`
  - `it("AC-S195-3: edit page promotes first error finding title as primary validation error", ...)`
  - `it("AC-S195-3: edit page falls back to localized message when findings are missing", ...)`
  - `it("AC-S195-6: mobile upload mode keeps dropzone and primary actions visible", ...)`
- `e2e/tests/S195-skill-edit-upload-validation-ux.spec.ts` 或延伸 `S187-skill-edit-page.spec.ts`
  - `test("AC-S195-6: mobile edit upload dropzone stays visible @S195 @ac-S195-6 @profile-single", ...)`
  - 只需驗 `page.setViewportSize({ width: 390, height: 844 })` 後，`拖拽 zip 或 md 檔到此處`、`儲存新版本`、`儲存分類` 可見；不需要打真實 invalid backend 400。

## 會改哪些檔案
- `frontend/src/pages/SkillEditPage.tsx`
- `frontend/src/pages/SkillEditPage.test.tsx`
- `e2e/tests/S195-skill-edit-upload-validation-ux.spec.ts`（或最小延伸既有 S187 e2e）

## 驗證方式
執行：
`cd frontend && npm test -- SkillEditPage`
`cd e2e && npx playwright test --grep @S195`

## 前置條件
- S195-T02 PASS
- S195-T03 PASS

## 狀態
PASS（2026-05-18）

## 實作結果
- `frontend/src/pages/SkillEditPage.tsx`：新增 `primaryValidationMessage()`、`versionErrorMessage()`、`ValidationFindingsList()`；`addVersionMutation` 失敗且 `ApiError.findings` 有值時，錯誤主標優先顯示第一筆 `severity === "error"` finding title，下面顯示 `儲存新版本失敗：SKILL.md validation failed` 與 findings row。
- `frontend/src/pages/SkillEditPage.test.tsx`：新增 `AC-S195-3` 測試，確認 first error finding title 會升成主標、次要文字保留 backend generic message、row 顯示 `error · skill_md · ...` 與 hint；也補 findings 缺失時回到既有 localized duplicate version 文案。
- `frontend/src/pages/SkillEditPage.test.tsx`：新增 `AC-S195-6` DOM 層 mobile upload mode 檢查，確認 dropzone 文案和主要 action 按鈕可見。
- `e2e/tests/S195-skill-edit-upload-validation-ux.spec.ts`：新增 `@S195 @ac-S195-6` Playwright mobile evidence，390px viewport 下進入 edit upload mode 後檢查 dropzone 文案與「儲存分類」「儲存新版本」可見且未超出 viewport。

## 驗證結果
- PASS：`cd frontend && npm test -- SkillEditPage`
- 結果：1 test file passed；12 tests passed。
- PASS：`cd e2e && npx playwright test --grep @S195`
- 結果：1 Playwright test passed。
