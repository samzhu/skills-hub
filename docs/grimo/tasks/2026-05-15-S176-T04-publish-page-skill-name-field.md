# S176-T04: PublishPage sends skillName field

## 對應規格
S176：Explicit Publish Skill Name（發佈頁手填平台 skill name + 允許重名）

## 這個 task 要做什麼
`frontend/src/pages/PublishPage.tsx` 目前只有檔案 / SKILL.md text、版本、分類、作者顯示與可見性，沒有平台 skill name 欄位。本 task 在發佈表單新增 required「技能名稱」Input，並讓 `frontend/src/api/skills.ts uploadSkill()` 把它 append 成 multipart `skillName`。前端仍不送 author，維持 S154b 的 server-authoritative ownership。

## 使用者情境（BDD）
Given（前提）使用者在 PublishPage 貼上 SKILL.md，frontmatter `name="internal-package-name"`  
And（而且）「技能名稱」欄位輸入 `platform-skill`  
When（動作）點擊「發佈技能」  
Then（結果）fetch body 的 FormData 包含 `skillName="platform-skill"`  
And（而且）FormData 不包含 `author`  
And（而且）缺少技能名稱時，「發佈技能」按鈕 disabled

## 研究來源
- `docs/grimo/specs/2026-05-15-S176-explicit-publish-skill-name.md §2.5`
- `frontend/src/pages/PublishPage.tsx`：現有 text/file mode、read-only author display、visibility radio
- `frontend/src/api/skills.ts`：現有 multipart upload helper 直接使用 `fetch`，避免手動 Content-Type boundary
- `docs/grimo/specs/archive/2026-05-09-S154b-author-display-frontend.md`：PublishPage author 不可編輯、不送 request author

## Requires
- Node.js 20
- `frontend/node_modules` present
- S176-T02 PASS（backend API 已接 `skillName`）

## 先做 POC
- POC：not required — 使用既有 Vitest + React Testing Library 測 PublishPage 行為。

## 正式程式怎麼做
- Class / file 名稱：
  - `frontend/src/pages/PublishPage.tsx`
  - `frontend/src/api/skills.ts`
- 入口：PublishPage submit handler、`uploadSkill(...)`
- 必要行為：
  - 新增 `const [skillName, setSkillName] = useState('')`。
  - 在 file/text 區塊前新增 label `技能名稱` + `Input`，`id="publish-skill-name"`。
  - `Input` 使用 `required`、`maxLength={64}`、`pattern="[a-z0-9-]{1,64}"`，placeholder 用 `transcribe-video`。
  - `submitDisabled` 加上 `skillName.trim().length === 0`。
  - mutation 呼叫 `uploadSkill(submitFile, skillName, version, category, visibility)`。
  - `uploadSkill` signature 改成 `(file, skillName, version, category, visibility)`，FormData append `skillName`。
- UI text：
  - 欄位 label：`技能名稱`
  - 說明文字：`小寫英數與 hyphen，最多 64 字元。這是平台列表顯示名稱。`

## 單元測試 / 整合測試
- `PublishPage.test.tsx`
  - `AC-S176-1: sends explicit skillName and never sends author`
  - `AC-S176-1: disables publish button until skillName is filled`
- 若已有 `skills.ts` API helper test：
  - 驗 `uploadSkill(file, "platform-skill", ...)` append `skillName`

## 會改哪些檔案
- `frontend/src/pages/PublishPage.tsx`
- `frontend/src/pages/PublishPage.test.tsx`
- `frontend/src/api/skills.ts`
- `frontend/src/api/skills.test.ts`（若存在）

## 驗證方式
執行：`cd frontend && npm test -- PublishPage.test.tsx`

## 前置條件
- S176-T02 PASS

## 狀態
pending（待做）
