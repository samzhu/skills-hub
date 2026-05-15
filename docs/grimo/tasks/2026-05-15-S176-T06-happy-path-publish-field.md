# S176-T06: Happy-path publish E2E fills explicit skillName

## 對應規格
S176：Explicit Publish Skill Name（發佈頁手填平台 skill name + 允許重名）

## 這個 task 要做什麼
Phase 4 `verify-all.sh` 跑到 V07 時，既有 `@happy-path` 發佈流程仍沿用 S176 前的表單操作，沒有填「技能名稱」，導致「發佈技能」按鈕維持 disabled。本 task 要更新既有 S140 發佈瀏覽器測試，讓主流程也輸入平台 `skillName`。

## 使用者情境（BDD）
Given（前提）使用者在 `/publish` 貼上合法 SKILL.md  
And（而且）PublishPage 有必填「技能名稱」欄位  
When（動作）既有 `@happy-path` 發佈流程填入 `skillName="ac3-publish-helper"` 後點「發佈技能」  
Then（結果）頁面跳到 `/publish/validate?id=<id>` 並完成 `/publish/review?id=<id>`  
And（而且）V07 `cd e2e && npx playwright test --grep @happy-path` 通過

## 根因
`e2e/test-results/S140-critical-path-publish-*/error-context.md` 顯示 `發佈技能` button disabled；DOM snapshot 內 `textbox "技能名稱"` 是空的。S176-T04 正確讓缺 `skillName` 時不能 submit，但 S140 happy-path test 尚未填新欄位。

## 正式程式怎麼做
- 修改 `e2e/tests/S140-critical-path-publish.spec.ts`
- 在貼上文本前後填入 `page.getByLabel('技能名稱').fill('ac3-publish-helper')`
- 不改 production code

## 驗證方式
執行：`cd e2e && npx playwright test --grep @happy-path`

## 前置條件
- S176-T01~T05 PASS
- Phase 4 `verify-all.sh` 已暴露 V07 failure

## 狀態
PASS（2026-05-15）

## Result

`e2e/tests/S140-critical-path-publish.spec.ts` 現在會在貼上 SKILL.md 前填入：

```ts
await page.getByLabel('技能名稱').fill('ac3-publish-helper');
```

RED：

```bash
SKIP_NATIVE=1 ./scripts/verify-all.sh
```

Result：V07 `cd e2e && npx playwright test --grep @happy-path` FAIL。Playwright `error-context.md` 顯示 `發佈技能` button disabled，DOM snapshot 內 `textbox "技能名稱"` 為空。

GREEN：

```bash
cd e2e && /Users/samzhu/.nvm/versions/node/v20.19.3/bin/npx playwright test --grep @happy-path
```

Result：`9 passed (27.9s)`。
