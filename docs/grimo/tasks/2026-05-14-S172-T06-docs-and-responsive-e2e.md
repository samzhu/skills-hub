# S172-T06: Docs card readability + responsive E2E guard

## 對應規格
S172：Production UI Responsive Polish

## 這個 task 要做什麼
完成最後一層瀏覽器驗證：修正 `/docs/overview` tablet 卡片過擠，並新增 Playwright `@responsive-polish` 測試，跑過這次 production audit 的路由與 viewport。完成後，S172 不只靠 component test，也會用真瀏覽器檢查整頁沒有 body 水平 overflow。

## 使用者情境（BDD）
Given（前提）S172-T01 到 S172-T05 都已完成  
When（動作）執行 `cd e2e && npx playwright test --grep @responsive-polish`  
Then（結果）測試會在 390、768、900、1440 寬度開啟 `/`、`/browse`、`/collections`、`/my-skills`、`/publish`、`/docs/overview`、`/skills/{id}`  
And（而且）每個頁面都符合 `document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1`  
And（而且）`/browse` 0-result suggestions 都是 link/button  
And（而且）`/collections` 建立 dialog 有 my-skills dropdown、已選技能清單，沒有 UUID textarea  
And（而且）`/my-skills` tab button 沒有 `bg-white` 或 computed white background。

## 研究來源
- `docs/grimo/specs/2026-05-14-S172-production-ui-responsive-polish.md` AC-S172-7、AC-S172-16。
- `docs/grimo/qa-strategy.md` V07：browser E2E 由 Playwright 管理。
- `.agents/skills/playwright-expert/SKILL.md` DESIGN mode：E2E file 命名、tag、fixture profile 規則。
- `e2e/tests/S140-critical-path-skill-detail.spec.ts`：現有 `profiles.single(request)` seed pattern。
- `frontend/src/pages/docs/OverviewPage.tsx`：目前 feature card grid 是 `md:grid-cols-3`。

## 先做 POC
- POC：not required — Playwright workspace 與 `profiles.single` 已存在；這個 task 是新增 S172 測試與調整既有 CSS breakpoint。

## 正式程式怎麼做
- File 名稱：`frontend/src/pages/docs/OverviewPage.tsx`、`e2e/tests/S172-responsive-polish.spec.ts`。
- 入口：docs overview feature-card grid；Playwright E2E suite。
- 必要行為：
  - `OverviewPage` feature-card grid 不要在 tablet 太早切三欄；建議 `sm:grid-cols-2 xl:grid-cols-3` 或等價 min-width。
  - Playwright test 使用 `@S172 @responsive-polish @happy-path` tag。
  - 使用 `profiles.single(request)` 取得 `skillId`，不要假設 production UUID 固定存在。
  - 對每個 viewport 與 route 執行 no-overflow assertion。
  - Browse 0-result 可用 query/search input 操作產生 0 筆；若缺穩定 locator，先補 production UI 的 label/test id，而不是用 brittle CSS chain。
  - Collections dialog test 應用 role/label 找「建立集合」、dropdown、「已選技能」，並 assert 找不到「技能 ID 清單」。
  - MySkills tabs computed style white check 可用 `getComputedStyle(el).backgroundColor`，不得等於 `rgb(255, 255, 255)`。

## 單元測試 / 整合測試
- `OverviewPage.test.tsx`（若現有 docs page test 不涵蓋 class，可新增）
  - `@DisplayName("AC-S172-7: docs feature cards use two columns before xl")`
- `e2e/tests/S172-responsive-polish.spec.ts`
  - `test("AC-S172-16: audited routes have no body horizontal overflow @S172 @responsive-polish @happy-path")`
  - `test("AC-S172-16: browse empty suggestions and collection dialog expose real controls @S172 @responsive-polish @happy-path")`
  - `test("AC-S172-16: my-skills lifecycle tabs are dark at mobile width @S172 @responsive-polish @happy-path")`

## 會改哪些檔案
- `frontend/src/pages/docs/OverviewPage.tsx`
- `frontend/src/pages/docs/OverviewPage.test.tsx`
- `e2e/tests/S172-responsive-polish.spec.ts`

## 驗證方式
執行：`cd frontend && npm test -- OverviewPage.test.tsx`  
執行：`cd e2e && npx playwright test --grep @responsive-polish`

## 前置條件
- S172-T01 PASS
- S172-T02 PASS
- S172-T03 PASS
- S172-T04 PASS
- S172-T05 PASS

## 狀態
pending（待做）
