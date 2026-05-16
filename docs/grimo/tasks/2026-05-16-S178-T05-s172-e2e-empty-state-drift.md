# S178-T05: S172 E2E Empty-State Drift

## 對應規格
S178：Browse Search Entry Point Cleanup

## 這個 task 要做什麼
`./scripts/verify-all.sh` 跑到 V07 時，`e2e/tests/S172-responsive-polish.spec.ts` 還期待 `/browse` 搜尋 0 筆時顯示舊 keyword empty-state 文案。S178 已把 `/browse` 搜尋框改成 semantic mode，所以這個 task 只更新該 E2E assertion，讓 V07 驗的是新畫面文案與既有 no-overflow 行為。

## 使用者情境（BDD）
Given（前提）Playwright 開 `/browse`

When（動作）在搜尋框輸入 `s172-no-result-query`

Then（結果）畫面顯示 `這個描述還沒有匹配的技能。`

And（而且）畫面有 `清除描述並瀏覽全部技能`

And（而且）畫面有連到 `/publish` 的 `發布這個技能`

And（而且）body 沒有水平 overflow。

## 研究來源
- `docs/grimo/specs/2026-05-15-S178-browse-search-request-routing.md`
- `frontend/src/pages/HomePage.tsx`
- `e2e/tests/S172-responsive-polish.spec.ts`
- `e2e/results/report.json`

## 先做 POC
- POC：not required — 這是既有 Playwright assertion 對齊新 UI 文案，不新增工具或 runtime path。

## 正式程式怎麼做
- Class / file 名稱：`e2e/tests/S172-responsive-polish.spec.ts`
- 入口：`AC-S172-16: browse empty suggestions and collection dialog expose real controls`
- 必要行為：
  - 將 heading assertion 從 `找不到符合的技能` 改成 `這個描述還沒有匹配的技能。`
  - 將 clear button assertion 從 `清除關鍵字並瀏覽全部技能` 改成 `清除描述並瀏覽全部技能`
  - 將 publish link assertion 從 `發布你自己的技能` 改成 `發布這個技能`
  - 保留 `切換到語意搜尋模式` 不出現與 body no-overflow 檢查。

## 單元測試 / 整合測試
- `e2e/tests/S172-responsive-polish.spec.ts`
  - Existing `@S172 @responsive-polish @happy-path` test covers this drift through V07.

## 會改哪些檔案
- `e2e/tests/S172-responsive-polish.spec.ts`
- `docs/grimo/specs/2026-05-15-S178-browse-search-request-routing.md`

## 驗證方式
執行：

```bash
cd e2e && npx playwright test --grep "@S172.*responsive-polish.*happy-path"
```

## 前置條件
- S178-T01 through S178-T04 PASS
- `./scripts/verify-all.sh` V07 has failed on the stale S172 empty-state assertion.

## 狀態
PASS

## 結果
Date: 2026-05-16

RED:
- `./scripts/verify-all.sh` reached V07 and failed because `e2e/tests/S172-responsive-polish.spec.ts:92` expected `找不到符合的技能`, while S178 changed `/browse` non-empty search into semantic mode with `這個描述還沒有匹配的技能。`.

GREEN:
- Updated the S172 responsive-polish E2E assertions to the current S178 semantic empty state:
  - heading `這個描述還沒有匹配的技能。`
  - clear action `清除描述並瀏覽全部技能`
  - publish link `發布這個技能`
- Kept the no-overflow assertion and the check that `切換到語意搜尋模式` is absent.

Verification:
- `cd e2e && npx playwright test --grep "@S172.*responsive-polish.*happy-path"` — PASS, 3 tests.
- `cd e2e && npx playwright test --grep @happy-path` — PASS, 9 tests.
