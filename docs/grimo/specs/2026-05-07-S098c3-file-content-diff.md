# S098c3 — File Content Diff（Version 檔案列表差異）

**Status:** 📋 planned
**Size:** M(6)（trim from L(12)；per-file 行級 diff defer — 本 spec 只做 file-list diff）
**Depends on:** S098c2 ✅（VersionDiffResponse + `/diff` endpoint）
**Target version:** v4.16.0

---

## §1 Goal

S098c2 的 `/diff` endpoint 比較 metadata 欄位（description / riskLevel / allowedTools / fileSize / fileCount）。但開發者更常想知道的是：**兩版本之間哪些具體檔案新增/刪除/修改了？** 本 spec 新增 `GET /api/v1/skills/{id}/file-list-diff?from=&to=` endpoint，回傳兩版本 zip 包中檔案列表的結構化差異，讓 `VersionDiffPage` 可以展示「+3 個檔案，-1 個檔案，5 個修改」的摘要。

**場景：** 作者比對 v1.0.0 → v2.0.0，看到 `scripts/install.sh` 為新增（+），`README.md` 大小從 1.2KB → 2.4KB（modified），`old-script.sh` 被移除（-）。

**不是：** 單一檔案的行級 diff（需要 diff library + streaming，留 S098c3b follow-up）；VersionDiffPage 以外的頁面改動。

---

## §2 Approach

### 2.1 Backend 新 endpoint

```
GET /api/v1/skills/{id}/file-list-diff?from={v1}&to={v2}
```

Response DTO `FileListDiffResponse`：

```java
public record FileListDiffResponse(
    String skillId,
    String fromVersion,
    String toVersion,
    int addedCount,
    int removedCount,
    int modifiedCount,
    int unchangedCount,
    List<FileDiffEntry> entries   // 只含 added + removed + modified（省略 unchanged 減少 payload）
) {
    public record FileDiffEntry(
        String path,
        String changeType,    // "added" | "removed" | "modified"
        Long fromSize,        // null = not present in from
        Long toSize           // null = not present in to
    ) {}
}
```

### 2.2 Algorithm

1. 從 `SkillVersionRepository.findBySkillIdAndVersion` 取兩 `SkillVersion`（複用 S098c2 路徑）
2. 透過 `StorageService.download(storagePath)` 取兩包 zip bytes
3. 用 `ZipInputStream` 解析兩包的 entry path → size Map（複用 `FileBrowserService` 既有 ZipInputStream 模式）
4. 比較兩個 Map：
   - path 只在 to：`added`
   - path 只在 from：`removed`
   - path 在兩者但 size 不同：`modified`
   - path 在兩者且 size 相同：`unchanged`（不輸出到 entries）

> **Trade-off**：用 size 作為 modified 判斷依據（不算 hash）— 快速、不需解壓內容、可能有 false negative（size 相同但內容不同）。MVP 可接受；精準 hash 留 follow-up。

### 2.3 New service `SkillFileDiffService`

```java
@Service
public class SkillFileDiffService {
    // ...依賴 SkillVersionRepository + StorageService
    public FileListDiffResponse listDiff(String skillId, String fromVer, String toVer) { ... }
    private Map<String, Long> listEntries(byte[] zipBytes) { ... }  // path → size
}
```

### 2.4 Controller — 加新 endpoint 至 SkillQueryController

```java
@GetMapping("/skills/{id}/file-list-diff")
@PreAuthorize("hasPermission(#id, 'Skill', 'read')")
FileListDiffResponse fileListDiff(
    @PathVariable UUID id,
    @RequestParam String from,
    @RequestParam String to) {
    return skillFileDiffService.listDiff(id.toString(), from, to);
}
```

### 2.5 Frontend — VersionDiffPage 加 file list diff panel

新增 `useFileListDiff(skillId, from, to)` hook + `FileListDiffPanel` component。

顯示：
- 標題「檔案變化（+{added} / -{removed} / ~{modified}）」
- `FileDiffEntry` list：新增綠 `+`、刪除紅 `-`、修改橘 `~`，附 path + size delta

### 2.6 Trim / Defer

- **Defer：** 單一檔案行級 diff（需 diff lib；S098c3b follow-up）
- **Defer：** unchanged 檔案展開（省略可減少 payload 30-50%）
- **Core（本 spec）：** backend file-list-diff endpoint + frontend 摘要 panel

---

## §3 Acceptance Criteria

**AC-1 — file-list-diff endpoint 回 structured response**
```
Given: skill A v1.0.0（含 SKILL.md, README.md）與 v2.0.0（含 SKILL.md, README.md, scripts/run.sh）
When:  GET /api/v1/skills/{id}/file-list-diff?from=v1.0.0&to=v2.0.0
Then:  200 + body.addedCount=1（scripts/run.sh）
       body.entries 含 {path:"scripts/run.sh", changeType:"added"}
```

**AC-2 — 版本不存在時 400**
```
Given: 不存在的版本 v9.9.9
When:  GET /api/v1/skills/{id}/file-list-diff?from=v1&to=v9.9.9
Then:  400 + error body（reuse IllegalArgumentException → GlobalExceptionHandler 路徑）
```

**AC-3 — VersionDiffPage 顯示 FileListDiffPanel**
```
Given: AC-1 的 response
When:  /skills/{id}/diff?from=v1.0.0&to=v2.0.0
Then:  頁面顯示「+1 / -0 / ~0」摘要
       顯示 scripts/run.sh 為新增（綠色 + 圖示）
```

**AC-4 — unchanged 檔案不在 entries 列表**
```
Given: from/to 都有 SKILL.md 且 size 相同
When:  API response
Then:  entries 不含 SKILL.md
       body.unchangedCount 有正確計數
```

---

## §4 File Plan

| File | Action |
|------|--------|
| `backend/.../skill/query/FileListDiffResponse.java` | new — DTO + FileDiffEntry inner record |
| `backend/.../skill/query/SkillFileDiffService.java` | new — listDiff() + listEntries() |
| `backend/.../skill/query/SkillQueryController.java` | modify — 加 `GET /skills/{id}/file-list-diff` |
| `frontend/src/hooks/useFileListDiff.ts` | new — useQuery for `/skills/{id}/file-list-diff` |
| `frontend/src/pages/VersionDiffPage.tsx` | modify — 加 FileListDiffPanel |
| `frontend/src/pages/VersionDiffPage.test.tsx` | modify — AC-3 test |

---

## §5 Test Plan

- **AC-1/AC-4 unit（backend）**：`SkillFileDiffService.listEntries()` 單元測試（mock zip bytes）
- **AC-2 unit（backend）**：不存在版本 → IllegalArgumentException
- **AC-3 unit（frontend）**：`VersionDiffPage.test.tsx` mock `/file-list-diff` → panel 渲染
- **Regression**：`./gradlew compileJava` + `npm test`
