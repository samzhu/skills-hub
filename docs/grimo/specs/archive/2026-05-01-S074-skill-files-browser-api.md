# S074 — Skill Files Browser API

> **Status**: in-flight
> **Estimate**: S / 5 pts
> **User-driven**: 「希望在 skill 明細頁面可以瀏覽各檔案內容」

## §1 Problem

目前 SkillDetailPage 只能整包下載 zip；使用者要在 UI 上瀏覽 skill 包裡個別檔案內容（最常見：references / 子腳本 / 設定範例），必須先下載再解壓。本 spec 補 backend API，FE 渲染留 S076。

## §2 API Design

### `GET /api/v1/skills/{id}/files`

列出最新 PUBLISHED 版本 zip 內所有 entries。

**Response 200**:
```json
[
  {"path": "SKILL.md", "size": 1234, "type": "text/markdown"},
  {"path": "references/lookup.md", "size": 567, "type": "text/markdown"},
  {"path": "data.bin", "size": 51200, "type": "application/octet-stream"}
]
```

**Errors**:
- `403 SKILL_SUSPENDED`：skill 為 SUSPENDED（與 `/download` 一致 per S029）
- `404 NOT_FOUND`：skill 不存在或無已發佈版本

### `GET /api/v1/skills/{id}/files/{*path}`

取得單一 entry 內容。

**Response 200** body = file bytes
**Headers**:
- `Content-Type`: `text/plain; charset=utf-8` (text) | `application/octet-stream` (binary)
- `Content-Length`: actual size

**Errors**:
- `400 VALIDATION_ERROR`：path 含 `..` 或絕對路徑（zip slip 防禦）
- `403 SKILL_SUSPENDED`
- `404 NOT_FOUND`：path 在 zip 內不存在
- `413 PAYLOAD_TOO_LARGE`：單檔超 1 MB（避免 server-side 大檔解壓壓力 + frontend 渲染負擔）

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | GET `/files` 對 PUBLISHED 多檔 zip | 200 + 完整 entry list |
| AC-2 | GET `/files/SKILL.md` | 200 + body = SKILL.md text |
| AC-3 | GET `/files/{path}` 不存在 | 404 NOT_FOUND |
| AC-4 | GET `/files` SUSPENDED skill | 403 SKILL_SUSPENDED |
| AC-5 | GET `/files/../etc/passwd` (path traversal) | 400 VALIDATION_ERROR |
| AC-6 | GET 1.5 MB file | 413 PAYLOAD_TOO_LARGE |
| AC-7 | GET binary file (.bin) | 200 + Content-Type=application/octet-stream |

## §4 Implementation

新增 `FileBrowserController` + `FileBrowserService` 在 `skill.query` 模組（read-side）。

**FileBrowserService.listFiles(skillId)**：
- 走 `downloadAndRecord` 同樣 fail-fast：findById + status check + storage.download
- 但**不**呼叫 `recordDownload()`（read-only metadata, 不該觸發下載事件）
- `ZipInputStream` enumerate entries → `FileEntryResponse` list
- entry path 違 `..` 或絕對路徑 → log warn + skip（之前 upload 階段應該已過濾 per S053；雙重保險）
- MIME 由 副檔名 inference（`.md` → text/markdown, `.json` → application/json, etc.）

**FileBrowserService.readFile(skillId, path)**：
- 同 fail-fast
- path 含 `..` 或開頭 `/` → IllegalArgumentException → 400
- ZipInputStream 找到 entry → byte[] read
- size > MAX_FILE_SIZE (1 MB) → throw PayloadTooLargeException → 413

**Controller 路徑變數捕捉**：
- `@GetMapping("/skills/{id}/files/{*path}")` — Spring 5.3+ wildcard pattern
- `@PathVariable("path") String path` — 含子路徑

## §5 Test plan

`FileBrowserServiceTest` 在 `@DataJdbcTest` slice 不適用（需要 storage）；改 `@SpringBootTest`：
1. AC-1: list multi-file zip → response.size() == 3
2. AC-2: read SKILL.md → text matches expected frontmatter
3. AC-3: read non-existent path → throws NoSuchElementException → 404
4. AC-5: read `../etc/passwd` → throws IllegalArgumentException → 400
5. AC-6: read 2MB file → throws PayloadTooLargeException → 413

`FileBrowserControllerTest` 加 `@WebMvcTest` slice：mock service，verify HTTP status mapping。

## §6 Verification

- `./gradlew test --tests "*FileBrowser*"` 全 PASS
- Smoke：上傳 multi-file zip → curl `/files` 看 list → curl `/files/SKILL.md` 看內容 → curl `/files/../passwd` 看 400

## §7 Result

待 ship 後填。

**Result（填於 ship 後）**：
- 298 backend tests / 0 fail（291 → 298，新增 7 個 S074 unit test：path-traversal / MIME inference / null/blank/case）
- 重啟 backend → 真實 curl multi-file zip → 7/7 AC PASS
- Cosmetic：SkillSuspendedException message 是「cannot be downloaded」，對 /files 路徑略不貼切；message 改造留 polish round
- ship v2.52.0 (M70)
