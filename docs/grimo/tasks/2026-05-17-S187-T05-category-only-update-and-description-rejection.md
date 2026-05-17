# S187-T05: Category-only update and direct description rejection

## 對應規格
S187：Skill SKILL.md 編輯頁

## 這個 task 要做什麼
這個 task 完成後，`PUT /api/v1/skills/{id}` 只能用來更新 category；caller 直接送 `description` 會得到 400，DB 的 `skills.description` 不變。前端 edit page 的「儲存分類」只送 `{ "category": "DevOps" }`，不送 description。

## 使用者情境（BDD）
Given（前提）DB 有 `skills.id='skill-docker'` 且 `description='Old desc'`
When（動作）caller 發 `PUT /api/v1/skills/skill-docker` body `{"description":"Manual desc"}`
Then（結果）HTTP 400
And（而且）response error message 是 `description must be updated by publishing a SKILL.md version`
And（而且）DB 的 `skills.description` 仍是 `Old desc`
And（而且）Alice 在 edit page 儲存分類時，request body 是 `{"category":"DevOps"}`，不含 `description`

## 研究來源
- `docs/grimo/specs/2026-05-16-S187-skill-md-edit-page.md`
- `frontend/src/api/skills.ts`
- `frontend/src/components/EditSkillModal.tsx`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/UpdateSkillCommand.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillUpdateControllerTest.java`

## 先做 POC
- POC：not required — controller fail-fast、category-only body、frontend mutation body 都是既有 code path 的收斂。

## 正式程式怎麼做
- Class / file 名稱：
  - `frontend/src/api/skills.ts`
  - `frontend/src/pages/SkillEditPage.tsx`
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/command/UpdateSkillCommand.java`
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java`
  - `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`
- 入口：frontend category save button 與 backend `PUT /api/v1/skills/{id}`。
- 必要行為：
  - frontend `updateSkill` 型別改成 category-only，或用新的 `updateSkillCategory` helper。
  - `SkillEditPage` 的 category save 只送 `category`。
  - backend 若 JSON body 包含 `description`，回 400，不局部套用 category。
  - category-only 仍成功更新 category。
  - 退役或刪除 `EditSkillModal` 及其 description edit test，避免 dead UI 繼續送 description。

## 單元測試 / 整合測試
- `SkillUpdateControllerTest`
  - `@DisplayName("AC-S187-6: direct description update 被拒絕")`
  - `@DisplayName("AC-S187-9: category-only update 仍成功")`
- `SkillEditPage.test.tsx`
  - `AC-S187-9: edit page 可更新 category`

## 會改哪些檔案
- `frontend/src/api/skills.ts`
- `frontend/src/pages/SkillEditPage.tsx`
- `frontend/src/pages/SkillEditPage.test.tsx`
- `frontend/src/components/EditSkillModal.tsx`
- `frontend/src/components/EditSkillModal.test.tsx`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/UpdateSkillCommand.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillUpdateControllerTest.java`

## 驗證方式
執行：`cd frontend && npm test -- SkillEditPage && cd ../backend && ./gradlew test --tests "*SkillUpdateControllerTest"`

## 前置條件
- S187-T02 PASS
- S187-T04 PASS

## 狀態
PASS（2026-05-17）

## 實作結果
- `frontend/src/pages/SkillEditPage.tsx` 新增「分類」欄位與「儲存分類」動作，送出 body 只有 `{"category":"..."}`。
- `frontend/src/api/skills.ts` 把 `updateSkill` 型別收斂成 category-only；`frontend/src/components/EditSkillModal.tsx` 與舊測試刪除。
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java` 在 `PUT /api/v1/skills/{id}` 看到 body 含 `description` 時直接回 400，訊息為 `description must be updated by publishing a SKILL.md version`，不會呼叫 update service。
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/UpdateSkillCommand.java` 與 `Skill.update(...)` 改成只處理 category；description snapshot 仍只由 S187-T04 的 SKILL.md version publish path 更新。

## 驗證結果
- RED：`cd frontend && npm test -- SkillEditPage` → 先失敗，找不到 label `分類`。
- RED：`cd backend && ./gradlew test --tests "*SkillUpdateControllerTest"` → 先失敗，direct description update 仍未被拒絕。
- GREEN：`cd frontend && npm test -- SkillEditPage` → 5 tests passed。
- GREEN：`cd backend && ./gradlew test --tests "*SkillUpdateControllerTest" -x processTestAot -x compileAotTestJava -x processAotTestResources` → 6 tests passed。
