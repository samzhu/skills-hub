# S195-T02: addVersion preserves validation findings

## 對應規格
S195：Skill edit upload validation UX

## 這個 task 要做什麼
`frontend/src/api/skills.ts` 的 `addVersion()` 現在讀得到 400 JSON body，但丟 `ApiError` 時只帶 `status/message/error`。完成後，`PUT /api/v1/skills/{id}/versions` 回 `findings[]` 時，`ApiError.findings` 會保留完整陣列，edit page 才能把第一筆可修正錯誤顯示給使用者。

## 使用者情境（BDD）
Given（前提）`PUT /api/v1/skills/skill-docker/versions` 回 400 body：
```json
{
  "error": "VALIDATION_ERROR",
  "message": "SKILL.md validation failed",
  "findings": [
    {
      "section": "skill_md",
      "severity": "error",
      "title": "metadata: key 'owner' nested object is not supported",
      "hint": null
    }
  ]
}
```
When（動作）`addVersion("skill-docker", file, "2")` 執行
Then（結果）函式 throw `ApiError`
And（而且）`error.findings[0].title` 等於 `metadata: key 'owner' nested object is not supported`
And（而且）`localizeApiError(error)` 仍回 `驗證失敗：SKILL.md validation failed`

## 研究來源
- `frontend/src/api/client.ts`：`apiFetch` 與 `apiFetchVoid` 已把 `findings` 傳進 `new ApiError(...)`。
- `frontend/src/api/skills.ts`：`addVersion()` 走 raw `fetch`，目前只解析 `{ message, error }`。
- `frontend/src/types/skill.ts`：`ValidationFinding` 欄位是 `section`, `severity`, `title`, `hint`。
- `frontend/src/lib/api-error-messages.ts`：`VALIDATION_ERROR` 仍要保留 generic backend message 的繁中前綴。

## 先做 POC
- POC：not required — pattern 已由 `apiFetch` 在同 repo 驗證；此 task 只讓 raw multipart fetch 對齊同一 `ApiError` constructor。
- Fixture：
  - `findings[]` response：`ApiError.findings` 等於 response body。
  - non-JSON response：fallback message 仍是 `Version upload failed: <status>`。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/api/skills.ts`
- 入口：`addVersion(skillId, file, version?)`
- 必要行為：
  - error body type 改為 `{ message?: string; error?: string; findings?: ValidationFinding[] }`。
  - throw `new ApiError(res.status, ..., b.error, b.findings)`。
  - 可重用 `ValidationFinding` import；不要改 `uploadSkill()`，除非同樣以最小一致性方式保留 findings 且不擴大 S195 task。
- Finding / response / DB 欄位：
  - `findings`: 原封不動保存在 `ApiError.findings`。

## 單元測試 / 整合測試
- `frontend/src/api/skills.test.ts`
  - `it("AC-S195-4: addVersion preserves response findings on validation error", ...)`
  - `it("AC-S195-4: addVersion keeps fallback message when error body is not JSON", ...)` 若既有 coverage 沒有 fallback。
- `frontend/src/lib/api-error-messages.test.ts`
  - 若需要，補一個 `VALIDATION_ERROR` + findings 的 assertion，確認 `localizeApiError()` 仍只看 `error.message`。

## 會改哪些檔案
- `frontend/src/api/skills.ts`
- `frontend/src/api/skills.test.ts`
- `frontend/src/lib/api-error-messages.test.ts`（只有需要補 localize assertion 時）

## 驗證方式
執行：`cd frontend && npm test -- skills.test api-error-messages`

## 前置條件
- 無

## 狀態
pending（待做）
