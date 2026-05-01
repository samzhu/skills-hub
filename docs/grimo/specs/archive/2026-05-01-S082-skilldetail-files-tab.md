# S082 — SkillDetailPage Files tab UI（接 S074 backend API）

> **Status**: in-flight
> **Type**: feature (UI consumes S074 API)
> **Estimate**: S / 5 pts
> **User-driven**: 「希望在 skill 明細頁面可以瀏覽各檔案內容」(tick 62 提出)

## §1 Problem

S074 (M70) 已 ship backend API（`GET /skills/{id}/files` list + `/files/{*path}` read），但 frontend SkillDetailPage 還是 3 tabs（概要 / 版本歷史 / 風險評估），無檔案瀏覽 UI。User 仍需下載整包 zip 解壓才能看 references / scripts 內容。

## §2 Approach

加 4th tab「檔案」到 SkillDetailPage：
- 左側：file tree（path / size / type）
- 右側：選中檔案的內容預覽
- text 類 MIME (`text/*`, `application/json`, `application/yaml`, `application/javascript`, `application/typescript`) → 直接 plain-text 顯示在 `<pre>` block
- binary (`application/octet-stream`, `image/*`) → 顯示 fallback 訊息 + size + 「下載整包 zip」hint
- 1MB+ 檔案：API 已 413；UI 顯示「檔案過大，請下載 zip 查看」

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | PUBLISHED skill 點「檔案」tab | 顯示 file tree（API 回的 entries） |
| AC-2 | 點選 SKILL.md | 右側顯示 SKILL.md 文字內容（`<pre>`，等寬字） |
| AC-3 | 點選 binary（如 .bin / .png） | 顯示 「此為 binary 檔案」+ size + 提示下載 zip |
| AC-4 | 點選 >1MB 檔案 | 顯示 「檔案過大，無法預覽」（捕 413 PAYLOAD_TOO_LARGE） |
| AC-5 | DRAFT / SUSPENDED skill | tab 仍可點，但內容顯示對應提示（DRAFT: 「無已發布版本」/ SUSPENDED: 「已停用，無法瀏覽」對齊 backend 403 / 404） |
| AC-6 | API 失敗 (network / 5xx) | 顯示 fallback 訊息，不破整頁 |

## §4 Implementation

新增：
- `api/skills.ts`：`fetchSkillFiles(skillId)` + `fetchSkillFile(skillId, path)`
- `types/skill.ts`：`SkillFile` 介面
- `hooks/useSkillFiles.ts` / `useSkillFile.ts`
- `components/FilesPanel.tsx`：tree + viewer
- `pages/SkillDetailPage.tsx`：加 4th `TabsTrigger value="files"` + `TabsContent`

不重構整頁視覺（per user「把手上的做完再做新的」— S081 token migration 後續 page rework 留 S085+）。本 spec 純加新 tab + binding。

## §5 Test plan

- `frontend test` 既有 11 tests / 0 fail
- Smoke：開 detail page → 點檔案 tab → 看 anthropic/pdf 12 entries → 點 reference.md 看 16K 文字 → 點 binary 確認 fallback
- DRAFT skill 點 tab → 看 friendly empty / 404
- SUSPENDED skill 點 tab → 看 stopped state

## §6 Verification

frontend tests + chrome smoke。

## §7 Result

待 ship 後填。

**Result（填於 ship 後）**：
- 11 frontend tests / 0 fail；npm run build 成功（30.81 KB CSS / 396 KB JS / 273ms）
- Smoke (anthropic/pdf, 12 entries)：點「檔案」tab → 12 entries 正確列出（reference.md / forms.md / LICENSE.txt / 6 個 scripts/*.py）；預設選 SKILL.md → `<pre>` 顯示 frontmatter + body ✓
- API 串接：FilesPanel 用 useSkillFiles + useSkillFile hooks 接 S074 backend
- Edge cases handled：
  - 1 MB+ → 413 PAYLOAD_TOO_LARGE → 「檔案過大，無法預覽」
  - SUSPENDED → 403 → 「此技能已被停用，無法瀏覽檔案」
  - DRAFT (no PUBLISHED version) → 404 → 「此技能尚未發布版本」
  - binary → fallback「無法預覽」+ size + 提示
  - image (`image/*`) → inline `<img>` 顯示
- ship v2.59.0 (M78)
