# S188-T04: Version Label Display, Docs, and Full Verify

## 對應規格
S188：版本標籤可自訂與自動流水號

## 這個 task 要做什麼
把 UI 與文件中剩下的 semver-only 文案改成「版本標籤」。完成後，browse list、detail header、版本頁籤與使用者文件都能接受 `v1` / `v2` / `v2026.05-hotfix` 這種顯示，不再要求 `MAJOR.MINOR.PATCH`。

## 使用者情境（BDD）
Given（前提）DB 有 `skills.latest_version='2'`，版本列表有 `1`、`2`
When（動作）使用者開啟 browse list、skill detail header、Versions tab、download link
Then（結果）畫面顯示 `v2` 與 `v1`
And（而且）不出現「格式：MAJOR.MINOR.PATCH」或 semver-only hint
And（而且）PRD / glossary / architecture 已描述 version label 與自動流水號

## 研究來源
- `docs/grimo/specs/2026-05-16-S188-version-label-auto-sequence.md` §3 AC-S188-7
- `docs/grimo/PRD.md`：P2 技能發佈流程
- `docs/grimo/glossary.md`：版本術語
- `docs/grimo/architecture.md`：read model 版本欄位描述

## 先做 POC
- POC：not required — 這是文案、display assumption 與完整驗證收尾。

## 正式程式怎麼做
- Class / file 名稱：frontend display/tests 與 docs。
- 入口：browse list、skill detail、Versions tab、download link、docs pages。
- 必要行為：
  - 搜尋 `semver`、`MAJOR.MINOR.PATCH`、`1.0.0`、`1.1.0` 的 user-facing 文案，保留 historical spec/archive 內容，只改現行產品文件與 UI。
  - 現行 UI 顯示 `v${version}` 時不能假設 version 可被 semver parse。
  - PRD P2 改成未填建立 `v1`，後續未填建立下一個流水號。
  - glossary 把 `version` 說明改成 Version Label。
  - architecture read model 欄位描述改成 latest version label。
  - spec §7 記錄 T01-T04 驗證命令與結果。
- Finding / response / DB 欄位：
  - `version`: display-only string，不做 semver parse。

## 單元測試 / 整合測試
- 現有 frontend display tests
  - `@DisplayName("AC-S188-7: UI displays numeric version labels without semver assumptions")`
- 文件檢查
  - `rg -n "MAJOR\\.MINOR\\.PATCH|semver|1\\.0\\.0|1\\.1\\.0" frontend/src docs/grimo/PRD.md docs/grimo/glossary.md docs/grimo/architecture.md`

## 會改哪些檔案
- frontend display components/tests that still assume semver
- `docs/grimo/PRD.md`
- `docs/grimo/glossary.md`
- `docs/grimo/architecture.md`
- `docs/grimo/specs/2026-05-16-S188-version-label-auto-sequence.md`

## 驗證方式
執行：`cd backend && ./gradlew test --tests '*VersionLabel*' --tests '*SkillCommand*' --tests '*SkillUpload*'`

執行：`cd frontend && npm test -- --run`

執行：`rg -n "semver|SemVer|MAJOR\\.MINOR\\.PATCH|格式.*版本|必填.*版本|version: 1\\.0\\.0|v1\\.0\\.0|v1\\.1\\.0" frontend/src docs/grimo/PRD.md docs/grimo/glossary.md docs/grimo/architecture.md | rg -v "MVP v1\\.0\\.0|Phase 1 v1\\.1\\.0|PostgreSQL migration"`

## 前置條件
- S188-T03 PASS

## 狀態
PASS（2026-05-17）

## 實作結果
- `frontend/src/pages/docs/VersioningPage.tsx` 不再說 `MAJOR.MINOR.PATCH` 或 strictly greater；改成「留白建立 1/2/3，自訂可用 2026.05-hotfix / release-1」。
- `frontend/src/pages/docs/FrontmatterPage.tsx` 說明 `SKILL.md` frontmatter 的 `version` 是選填 metadata，不是平台發布版本必填欄位。
- `frontend/src/pages/docs/UploadValidatePage.tsx`、`RestApiPage.tsx`、`YourFirstSkillPage.tsx`、`EventPayloadPage.tsx` 的範例改成 optional version / numeric version label。
- `frontend/src/types/skill.ts` 把 `latestVersion` / `SkillVersion.version` 註解改成版本標籤。
- `docs/grimo/PRD.md` P4 指定版本下載例子改成 `v1`。
- Browse card、detail header、Versions tab、collection modal、version diff、download filename tests 改用 `1` / `2` / `2026.05-hotfix`，確認 UI 只做顯示，不 parse semver。

## 驗證結果
- RED：`rg -n "semver|SemVer|MAJOR\\.MINOR\\.PATCH|1\\.0\\.0|1\\.1\\.0|格式.*版本|必填.*版本|version: 1\\.0\\.0|v1\\.0\\.0|v1\\.1\\.0" frontend/src docs/grimo/PRD.md docs/grimo/glossary.md docs/grimo/architecture.md docs/grimo/specs/2026-05-16-S188-version-label-auto-sequence.md docs/grimo/tasks/2026-05-17-S188-T04-display-docs-and-full-verify.md` 找到 `VersioningPage.tsx`、`FrontmatterPage.tsx`、`UploadValidatePage.tsx`、`RestApiPage.tsx`、`EventPayloadPage.tsx`、`YourFirstSkillPage.tsx` 與多個 display tests 仍有 semver-only 或 `1.0.0` 假設。
- GREEN：`cd frontend && npm test -- SkillCard.test.tsx PageHeader.test.tsx VersionsTabV2.test.tsx VersionList.test.tsx VersionDiffPage.test.tsx CreateCollectionModal.test.tsx PublishValidatePage.test.tsx PublishPage.test.tsx` 通過；Vitest 印出 `Test Files 8 passed`、`Tests 63 passed`。
- GREEN：`cd frontend && npm run typecheck` 通過。
- GREEN：`cd backend && ./gradlew test --tests '*VersionLabel*' --tests '*SkillCommand*' --tests '*SkillUpload*'` 通過；Gradle 印出 `BUILD SUCCESSFUL in 2m 25s`。
- GREEN：`cd frontend && npm test -- --run` 通過；Vitest 印出 `Test Files 79 passed`、`Tests 450 passed`。
- GREEN：`rg -n "semver|SemVer|MAJOR\\.MINOR\\.PATCH|格式.*版本|必填.*版本|version: 1\\.0\\.0|v1\\.0\\.0|v1\\.1\\.0" frontend/src docs/grimo/PRD.md docs/grimo/glossary.md docs/grimo/architecture.md | rg -v "MVP v1\\.0\\.0|Phase 1 v1\\.1\\.0|PostgreSQL migration"` 沒有輸出；現行 UI / PRD / glossary / architecture 沒有殘留 semver-only 或必填版本提示。
