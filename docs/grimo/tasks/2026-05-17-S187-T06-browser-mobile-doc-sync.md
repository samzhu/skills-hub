# S187-T06: Browser/mobile assembly and documentation sync

## 對應規格
S187：Skill SKILL.md 編輯頁

## 這個 task 要做什麼
這個 task 完成後，S187 的 browser path 會用真頁面組裝跑一次：詳情頁點「編輯」到 edit page、手機版 edit page 不重疊、建立新版本進 version validation、驗證完成回 detail。若 docs 或 API 說明還寫「description 可直接在 modal 編輯」，本 task 也要同步改掉。

## 使用者情境（BDD）
Given（前提）viewport 寬度 390px，Alice 對 `skill-docker` 有 write permission
When（動作）Alice 開啟 `/skills/skill-docker/edit`
Then（結果）mode tabs、textarea、version input、「儲存新版本」按鈕在單欄 layout 中依序顯示
And（而且）textarea / preview / button text 不互相覆蓋
And（而且）新增版本完成後，`/publish/validate?id=skill-docker&mode=version` 會在 riskLevel 完成時導回 `/skills/skill-docker`

## 研究來源
- `docs/grimo/specs/2026-05-16-S187-skill-md-edit-page.md`
- `docs/grimo/architecture.md`
- `docs/grimo/development-standards.md`
- `docs/grimo/qa-strategy.md`
- `frontend/src/pages/docs/RestApiPage.tsx`
- `frontend/src/pages/docs/VersioningPage.tsx`
- `e2e/tests/`

## 先做 POC
- POC：not required — 這是 browser assembly / docs sync task；不新增未知 API。若 Playwright fixture 缺 seed endpoint，再在本 task 內記錄 blocker 並新增最小 test fixture work。

## 正式程式怎麼做
- Class / file 名稱：
  - `e2e/tests/S187-skill-edit-page.spec.ts`（如需要 browser evidence）
  - `frontend/src/pages/SkillEditPage.tsx`
  - docs / frontend docs pages that mention direct description edit
  - `docs/grimo/specs/2026-05-16-S187-skill-md-edit-page.md`
- 入口：browser route `/skills/{id}/edit` 與 docs search result。
- 必要行為：
  - 390px viewport 下 edit page 主要控制不可重疊。
  - version validation mode 文案和導向符合 AC-S187-10。
  - 若新增 Playwright spec，tag 使用 `@S187`、`@ac-S187-8`、`@ac-S187-10`，fixture 走既有 `POST /internal/test/seed/skill`。
  - 掃描 docs/source：`rg "EditSkillModal|description.*edit|直接.*description|新增版本" docs/grimo frontend/src/pages/docs frontend/src`，把已過時描述改成 latest SKILL.md description snapshot。
  - S187 spec §7 consolidation 時記錄 E2E / no-E2E rationale 與所有 AC 結果。

## 單元測試 / 整合測試
- `SkillEditPage.test.tsx`
  - `AC-S187-8: 編輯頁手機版主要控制可見`
- optional Playwright:
  - `S187-skill-edit-page.spec.ts`
  - `@S187 @ac-S187-8 @ac-S187-10`

## 會改哪些檔案
- `frontend/src/pages/SkillEditPage.tsx`
- `frontend/src/pages/SkillEditPage.test.tsx`
- `frontend/src/pages/docs/*` if stale text exists
- `docs/grimo/specs/2026-05-16-S187-skill-md-edit-page.md`
- `e2e/tests/S187-skill-edit-page.spec.ts` if browser evidence is required

## 驗證方式
執行：`cd frontend && npm test -- SkillEditPage PublishValidatePage`
必要時再執行：`cd e2e && npx playwright test --grep @S187`

## 前置條件
- S187-T01 PASS
- S187-T02 PASS
- S187-T03 PASS
- S187-T04 PASS
- S187-T05 PASS

## 狀態
PASS（2026-05-17）

## 實作結果
- `frontend/src/pages/SkillEditPage.tsx`：手機寬度下 header action 改成可換行/單欄的穩定 layout，390px viewport 不會把「取消 / 儲存分類 / 儲存新版本」擠出畫面。
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`：`addVersion(...)` / `publishVersion(...)` 在新版本儲存後把 `skills.risk_level` 清成 `NULL`，讓 `/publish/validate?id=...&mode=version` 先顯示「新版本驗證中」，等新掃描完成再回詳情頁。
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillRepository.java`：新增 `clearRiskLevel(...)` SQL，因 `riskLevel` 是 `@ReadOnlyProperty`，不能靠 `skillRepo.save(skill)` 寫回資料庫。
- `e2e/tests/S187-skill-edit-page.spec.ts`：新增 `@S187 @ac-S187-8 @ac-S187-10` browser path，走 `POST /internal/test/seed/skill` fixture，驗證詳情頁按「編輯」到 edit page、390px 控制可見、儲存新版本進 version validation、掃描完成回 detail。
- docs/API stale scan：`frontend/src/pages/docs/*` 未找到仍宣稱 description 可直接在 modal/API 編輯的過時文件；S187 spec 保留舊 modal 描述作為「現況掃描」歷史背景，不改成 shipped reality。

## 驗證結果
- `cd backend && ./gradlew test --tests '*SkillAggregateTest' --tests '*SkillUploadAllowedToolsTest' -x processTestAot -x compileAotTestJava -x processAotTestResources` -> PASS
- `cd frontend && npm test -- SkillEditPage PublishValidatePage` -> PASS（2 files / 12 tests）
- `cd e2e && npx playwright test --grep @S187` -> PASS（1 test）
