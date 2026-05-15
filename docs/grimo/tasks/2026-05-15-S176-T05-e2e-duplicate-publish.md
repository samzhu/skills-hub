# S176-T05: Browser E2E duplicate publish

## 對應規格
S176：Explicit Publish Skill Name（發佈頁手填平台 skill name + 允許重名）

## 這個 task 要做什麼
Backend unit tests 會證明 service 和 DB 可以收 duplicate `skills.name`；frontend component test 會證明 FormData 有 `skillName`。本 task 要用 Playwright 打開真正的 `/publish` 頁面，透過 browser 操作送出 duplicate platform skill name，驗證組裝後的 Spring Boot + React + DB flow 真的能跑。

## 使用者情境（BDD）
Given（前提）E2E profile 已啟動，資料庫先用 `/internal/test/reset` 清空  
And（而且）使用者登入或使用 LAB/mock auth 狀態可進入 `/publish`  
When（動作）在 `/publish` 第一次輸入 `skillName="transcribe-video"`，SKILL.md frontmatter `name="internal-package-one"`，送出  
Then（結果）頁面跳到 `/publish/validate?id=<first-id>`  
When（動作）回到 `/publish`，第二次輸入相同 `skillName="transcribe-video"`，SKILL.md frontmatter `name="internal-package-two"`，送出  
Then（結果）頁面跳到 `/publish/validate?id=<second-id>`  
And（而且）`first-id` 和 `second-id` 不同  
And（而且）測試透過 API/list 或 DB-backed response 看到同名平台 skill 有兩筆

## 研究來源
- `docs/grimo/specs/2026-05-15-S176-explicit-publish-skill-name.md §6`
- `docs/grimo/qa-strategy.md`：Browser E2E 使用 Playwright 1.59.1，fixture seeding Pattern 1，tag 格式 `@S176 @ac-N`
- `e2e/playwright.config.ts`：webServer 啟動 Spring Boot + Vite，profile 使用 local/dev/e2e
- `backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/TestDataController.java`：reset/seed endpoint

## Requires
- Node.js 20
- `e2e/node_modules` present
- Java 25 toolchain
- Docker daemon reachable（backend Testcontainers / pgvector）
- S176-T01, T02, T03, T04 PASS

## 先做 POC
- POC：not required — 使用既有 Playwright workspace 與 TestDataController fixture pattern。

## 正式程式怎麼做
- Class / file 名稱：
  - `e2e/tests/S176-explicit-publish-skill-name.spec.ts`
  - `frontend/src/pages/PublishPage.tsx`（若 T04 未加穩定 locator，補 `data-testid`）
- 入口：browser `/publish`
- 必要行為：
  - Test tag 包含 `@S176 @ac-1 @ac-2 @ac-3`。
  - 使用 text mode 或可程式生成 File 的 file mode；選最穩定、不依賴本機檔案路徑的方式。
  - 驗證兩次 submit 都到 `/publish/validate?id=...`。
  - 若需要查同名兩筆，可呼叫 `/api/v1/skills?query=transcribe-video` 或既有 list API；不得直連 production DB。
  - 測試完成後 reset test data，避免污染下一個 E2E。
- UI locator：
  - `publish-skill-name` input
  - text mode tab / SKILL.md textarea
  - submit button `發佈技能`

## 單元測試 / 整合測試
- `e2e/tests/S176-explicit-publish-skill-name.spec.ts`
  - `test("AC-S176-1/2/3: publish duplicate platform skill names through browser", ...)`

## 會改哪些檔案
- `e2e/tests/S176-explicit-publish-skill-name.spec.ts`
- `frontend/src/pages/PublishPage.tsx`（只在需要 locator 時）

## 驗證方式
執行：`cd e2e && npx playwright test --grep @S176`

## 前置條件
- S176-T01 PASS
- S176-T02 PASS
- S176-T03 PASS
- S176-T04 PASS

## 狀態
PASS（2026-05-15）

## Result

新增 `e2e/tests/S176-explicit-publish-skill-name.spec.ts`，用 Playwright 打開 `/publish`：

- 第一次輸入平台 `skillName="transcribe-video"`，SKILL.md frontmatter `name="internal-package-one"`，送出後跳到 `/publish/validate?id=<first-id>` 並完成 `/publish/review?id=<first-id>`。
- 第二次輸入同一個平台 `skillName="transcribe-video"`，SKILL.md frontmatter `name="internal-package-two"`，送出後跳到 `/publish/validate?id=<second-id>` 並完成 `/publish/review?id=<second-id>`。
- 測試確認 `first-id` 和 `second-id` 不同，且 `GET /api/v1/skills?keyword=transcribe-video&page=0&size=20` 回來的 `content[]` 內有兩筆 `name="transcribe-video"`，id 正好是上述兩筆。

RED：

```bash
cd e2e && /Users/samzhu/.nvm/versions/node/v20.19.3/bin/npx playwright test --grep @S176
```

Result：`Error: No tests found`。

GREEN：

```bash
cd e2e && /Users/samzhu/.nvm/versions/node/v20.19.3/bin/npx playwright test --grep @S176
```

Result：`1 passed (22.1s)`。
