# S192-T09 — E2E seed 作者顯示資料

> Status: PASS
> Spec: S192
> Created: 2026-05-17

## Purpose

`cd e2e && npx playwright test tests/S140-critical-path-browse-search.spec.ts tests/S140-critical-path-skill-detail.spec.ts --grep @happy-path` 目前失敗在兩個畫面文字：

- `e2e/tests/S140-critical-path-browse-search.spec.ts:60` 找不到 `docker-compose-helper` 卡片內的 `alice`。
- `e2e/tests/S140-critical-path-skill-detail.spec.ts:33` 找不到 `作者：alice`。

S192 後 `getDisplayName(...)` 不再把 raw `author` 當人名 fallback。S140 的 `profiles.single` / `profiles.paged` 舊 seed 只建立 skill，沒有建立 `users` 顯示資料，所以真正的 browser 頁面會顯示空白作者 label。

## BDD

Given（前提）S140 Playwright profile 透過 `/internal/test/seed/skill` seed `docker-compose-helper`，而 skill row 的 `author` 是 technical identifier `alice`。

When（動作）使用者打開 `/browse` 搜尋 `docker`，或打開 `/skills/{skillId}` detail page。

Then（結果）SkillCard 與 PageHeader 顯示作者文字 `Alice`。

And（而且）route / install command 仍可使用 technical segment `alice`；一般作者 label 不直接顯示 raw `author`。

## Target Files

- `backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/SeedSkillRequest.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/TestDataController.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/testsupport/TestDataControllerTest.java`
- `e2e/tests/_fixtures.ts`
- `e2e/tests/S140-critical-path-browse-search.spec.ts`
- `e2e/tests/S140-critical-path-skill-detail.spec.ts`

## Verification

RED:

```bash
cd e2e && npx playwright test tests/S140-critical-path-browse-search.spec.ts tests/S140-critical-path-skill-detail.spec.ts --grep @happy-path
```

Result: FAIL。browse card 找不到 `alice`；detail page 找不到 `作者：alice`；backend log 出現 `S192 user_display_missing userId=alice`。

GREEN:

```bash
cd backend && ./gradlew test --tests "*TestDataControllerTest"
cd e2e && npx playwright test tests/S140-critical-path-browse-search.spec.ts tests/S140-critical-path-skill-detail.spec.ts --grep @happy-path
```

Result: PASS。Java slice 4 tests completed；S140 browse/detail 2 tests passed，畫面分別看到 `Alice` 與 `作者：Alice`。
