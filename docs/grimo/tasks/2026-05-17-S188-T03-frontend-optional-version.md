# S188-T03: Frontend Optional Version Forms

## 對應規格
S188：版本標籤可自訂與自動流水號

## 這個 task 要做什麼
把前端發布與新增版本的版本欄位改成可留空。完成後，使用者在 `/publish` 或詳情頁版本上傳表單不填版本，也能按送出；前端送出的 `FormData` 不包含 `version`，讓後端依 S188 自動產號。

## 使用者情境（BDD）
Given（前提）Alice 開啟 `/publish`，選了 zip / 填了 skill name / category / visibility
When（動作）Alice 把「版本號」留空後點發布
Then（結果）前端呼叫 `uploadSkill(...)`
And（而且）送出的 multipart 沒有 `version`
And（而且）畫面不出現 browser required 或 semver pattern 錯誤

## 研究來源
- `frontend/src/api/skills.ts`：目前 `uploadSkill` / `addVersion` 會 append version
- `frontend/src/pages/PublishPage.tsx`：目前初始值 `1.0.0`，input required + semver pattern
- `frontend/src/pages/SkillDetailPage.tsx`：目前 AddVersionForm required + semver pattern
- S188 §4.3：blank version 不 append FormData

## 先做 POC
- POC：not required — 可直接用 Vitest / Testing Library 驗 FormData call 與 input attributes。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/api/skills.ts`、`PublishPage.tsx`、`SkillDetailPage.tsx`；若 S187 已建立 `SkillEditPage.tsx`，也同步套用 optional version。
- 入口：發布表單與新增版本表單。
- 必要行為：
  - API function 的 `version` 參數改成 optional。
  - `version.trim()` 有內容才 append。
  - `/publish` 初始 version 改空字串。
  - input 移除 `required` 與 semver-only `pattern`。
  - hint 改成「可留空；未填由系統建立 1 / 下一個流水號」。
  - AddVersionForm 送出按鈕不再因 `!version` disabled。
- Finding / response / DB 欄位：
  - `FormData.version`: blank 時不存在；非 blank 時是 trim 後字串。

## 單元測試 / 整合測試
- `PublishPage.test.tsx`
  - `@DisplayName("AC-S188-5: publish blank version does not append version")`
- `SkillDetailPage.test.tsx` 或對應 AddVersionForm test
  - `@DisplayName("AC-S188-6: add version blank version does not append version")`
- `skills.test.ts`
  - `@DisplayName("AC-S188-5: uploadSkill omits blank version from FormData")`
  - `@DisplayName("AC-S188-6: addVersion omits blank version from FormData")`

## 會改哪些檔案
- `frontend/src/api/skills.ts`
- `frontend/src/pages/PublishPage.tsx`
- `frontend/src/pages/SkillDetailPage.tsx`
- `frontend/src/pages/SkillEditPage.tsx`（若 S187 已存在）
- frontend tests for these paths

## 驗證方式
執行：`cd frontend && npm test -- --run PublishPage SkillDetailPage`

## 前置條件
- S188-T02 PASS

## 狀態
pending（待做）
