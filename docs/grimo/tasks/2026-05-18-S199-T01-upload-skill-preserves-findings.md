# S199-T01: uploadSkill 保留 structured findings

## 對應規格
S199：Publish Failed Actionable Validation Copy

## 這個 task 要做什麼
`uploadSkill()` 遇到 `POST /api/v1/skills/upload` 回 400 時，要像 `addVersion()` 一樣把 response body 裡的 `findings[]` 傳進 `ApiError`。這樣 `PublishPage.onError()` 導到 `/publish/failed` 時，failed page 才拿得到第一筆 validation finding。

## 使用者情境（BDD）
Given（前提）`POST /api/v1/skills/upload` 回 400，body 是 `{ error: "VALIDATION_ERROR", message: "SKILL.md validation failed", findings: [{ section: "skill_md", severity: "error", title: "skill_md_line_count: SKILL.md has 589 lines (max 500)", hint: null }] }`
When（動作）`uploadSkill(file, "Team Skill", "", "DevOps")` throw `ApiError`
Then（結果）`ApiError.findings[0].title` 是 `skill_md_line_count: SKILL.md has 589 lines (max 500)`
And（而且）`ApiError.message` 仍是 `SKILL.md validation failed`
And（而且）沒有 findings 的 error body 仍走既有 localized fallback

## 研究來源
- `docs/grimo/specs/2026-05-18-S199-publish-failed-actionable-validation-copy.md`
- `frontend/src/api/skills.ts`
- `frontend/src/api/skills.test.ts`
- S195 shipped：`addVersion()` 已保留 `ApiError.findings`

## 先做 POC
- POC：not required — 直接對齊既有 `addVersion()` error parsing pattern。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/api/skills.ts`
- 入口：`uploadSkill()`
- 必要行為：
  - 將 error body type 從 `{ message?: string; error?: string }` 擴成 `{ message?: string; error?: string; findings?: ValidationFinding[] }`。
  - `throw new ApiError(res.status, ..., b.error, b.findings)`。
  - 不改 multipart FormData keys：`skillName`、`file`、non-blank `version`、`category`、`visibility`。

## 單元測試 / 整合測試
- `frontend/src/api/skills.test.ts`
  - `AC-S199-1: uploadSkill 保留 response findings`
  - negative case：沒有 findings 時 `ApiError.findings` 是 `undefined` 或空值，不 crash

## 會改哪些檔案
- `frontend/src/api/skills.ts`
- `frontend/src/api/skills.test.ts`

## 驗證方式
執行：`cd frontend && npm test -- skills.test`

## 前置條件
- 無

## 狀態
pending（待做）
