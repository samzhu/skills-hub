# S199-T02: PublishFailedPage 顯示具體 validation 主因與下一步

## 對應規格
S199：Publish Failed Actionable Validation Copy

## 這個 task 要做什麼
`/publish/failed` 的第一屏要把第一筆 finding 轉成 user 看得懂的繁中主因，並顯示下一步。known rule 要有 mapping，unknown rule 保留原始訊息。沒有 router state findings 時，要明確說這次失敗頁缺少詳細錯誤，不要只顯示 generic `SKILL.md validation failed`。

## 使用者情境（BDD）
Given（前提）router state 帶入 finding title `skill_md_line_count: SKILL.md has 589 lines (max 500)`
When（動作）render `/publish/failed?state=A`
Then（結果）top callout 主標顯示 `SKILL.md 太長：589 行，目前上限 500 行。`
And（而且）錯誤 row 顯示同一個繁中主因，下一步文字包含 `references/`
And（而且）細節列仍顯示 `原始訊息：skill_md_line_count: SKILL.md has 589 lines (max 500)`

Given（前提）finding title 是 `Missing required field: name`
When（動作）render failed page
Then（結果）top callout 顯示 `SKILL.md frontmatter 缺少 name。`
And（而且）下一步文字包含 `name: my-skill`

Given（前提）finding title 是 `body_present: SKILL.md has no body content after frontmatter`
When（動作）render failed page
Then（結果）頁面顯示 `SKILL.md frontmatter 後面沒有使用說明內容。`
And（而且）下一步文字說明 `Skills Hub 不收只有 metadata、沒有 instructions body 的空 skill。`

Given（前提）URL 只有 `?state=A&msg=驗證失敗：SKILL.md validation failed`
When（動作）render failed page
Then（結果）頁面顯示 `這次失敗頁沒有收到詳細錯誤內容。`
And（而且）頁面提示 `重新上傳一次` 或 `查看 /api/v1/skills/upload response`

## 研究來源
- `docs/grimo/specs/2026-05-18-S199-publish-failed-actionable-validation-copy.md`
- `frontend/src/pages/PublishFailedPage.tsx`
- `frontend/src/pages/PublishFailedPage.test.tsx`
- S198：`body_present` 是 Skills Hub 上架政策，不是 agentskills.io 官方 hard validation

## 先做 POC
- POC：not required — 純 frontend mapping 與 RTL tests，不新增 route 或 API schema。

## 正式程式怎麼做
- Class / file 名稱：`frontend/src/pages/PublishFailedPage.tsx`
- 入口：`StateAFrontmatterError`
- 必要行為：
  - 新增 pure mapper，例如 `formatValidationFinding(finding)`，輸出 `{ title, nextStep, rawTitle }`。
  - line-count parser 從 title 抽 `{actual}` / `{limit}`；parse 失敗時 fallback 原文。
  - known mappings：`skill_md_line_count:`、`Missing required field: name`、`Missing required field: description`、`No YAML frontmatter found`、`Invalid YAML:`、`body_present:`。
  - top callout 不再固定顯示 `驗證在第 2 步停止 — 沒有任何資料寫入。`，但仍要說明目前沒有資料寫入 registry。
  - structured findings 存在時不顯示 query `msg` fallback；沒有 findings 但有 generic msg 時顯示 detail-unavailable fallback。

## 單元測試 / 整合測試
- `frontend/src/pages/PublishFailedPage.test.tsx`
  - `AC-S199-2: failed page top callout 顯示第一筆 finding 的繁中主因`
  - `AC-S199-3: line-count finding 顯示具體行數與 references 下一步`
  - `AC-S199-4: known field errors 顯示具體修法`
  - `AC-S199-5: 沒有 findings 時顯示 detail-unavailable fallback`
  - `AC-S199-6: raw backend title 不消失`

## 會改哪些檔案
- `frontend/src/pages/PublishFailedPage.tsx`
- `frontend/src/pages/PublishFailedPage.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- PublishFailedPage`

## 前置條件
- S199-T01 PASS

## 狀態
pending（待做）
