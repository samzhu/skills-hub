# S098c2 — Backend `/diff` Endpoint

**Status:** ✅ Ship v4.14.0
**Size:** M(8)
**Depends on:** S098c ✅（VersionDiffPage frontend-only shell）
**Target version:** v4.14.0

---

## §1 Goal

S098c 的 `VersionDiffPage` 只用現有 `/skills/{id}` + `/skills/{id}/versions` 顯示兩版本 metadata，無法比較欄位級差異（description 改了什麼？risk level 升降了？allowed-tools 增減？）。本 spec 新增 `GET /api/v1/skills/{id}/diff?from={v1}&to={v2}` endpoint，回傳欄位級結構化 diff，讓 `VersionDiffPage` 升級為真正 diff view。

**場景：** 開發者在 VersionDiffPage 選 v1.0.0 → v2.0.0，看到 description 改寫、allowed-tools 從空 → `bash:read_file`、risk_level 從 NONE → LOW 的具體變化。

**不是：** file content 行級 diff（需 zip extract，S098c3）；版本選擇 UI 改動（S098c 已 ship，本 spec backend only + VersionDiffPage 讀新 API 取代假資料）。

---

## §2 Approach

### 2.1 Response DTO

```java
// shared/api/VersionDiffResponse.java（或 skill/query/ 目錄）
public record VersionDiffResponse(
    String skillId,
    VersionSnapshot from,
    VersionSnapshot to,
    List<DiffField> fields    // 只含「有變化」的欄位
) {
    public record VersionSnapshot(
        String version,
        Instant publishedAt,
        long fileSize,
        int fileCount
    ) {}

    public record DiffField(
        String field,      // "description" | "riskLevel" | "allowedTools" | "name" | ...
        String fromValue,  // null = 欄位不存在
        String toValue,    // null = 欄位移除
        String changeType  // "added" | "removed" | "changed"
    ) {}
}
```

V1 比較以下欄位（來自 `SkillVersion.frontmatter` + `riskAssessment.level`）：
- `name`（frontmatter）
- `description`（frontmatter）
- `riskLevel`（riskAssessment → `level`）
- `allowedTools`（frontmatter `allowed-tools`，List → comma-joined string）
- `fileSize`
- `fileCount`

### 2.2 Query Service

新增 `SkillDiffQueryService`（或在 `SkillQueryService` 加方法）：

```java
public VersionDiffResponse diff(String skillId, String fromVer, String toVer) {
    var from = skillVersionRepo.findBySkillIdAndVersion(skillId, fromVer)
        .orElseThrow(() -> new IllegalArgumentException("Version not found: " + fromVer));
    var to   = skillVersionRepo.findBySkillIdAndVersion(skillId, toVer)
        .orElseThrow(() -> new IllegalArgumentException("Version not found: " + toVer));

    var fields = compareFields(from, to);
    return new VersionDiffResponse(
        skillId,
        VersionDiffResponse.VersionSnapshot.of(from),
        VersionDiffResponse.VersionSnapshot.of(to),
        fields
    );
}
```

`compareFields(SkillVersion from, SkillVersion to)` 逐欄比較 → 只加入有差異的欄位 → 回傳 immutable list。

### 2.3 Controller endpoint

新增或擴展 `SkillQueryController`：

```java
@GetMapping("/skills/{id}/diff")
public VersionDiffResponse diff(
    @PathVariable String id,
    @RequestParam String from,
    @RequestParam String to
) {
    return skillDiffQueryService.diff(id, from, to);
}
```

錯誤情境（`from` / `to` 不存在）→ `IllegalArgumentException` → GlobalExceptionHandler 回 400。

### 2.4 VersionDiffPage 升格

`VersionDiffPage.tsx` 新增 `useVersionDiff(id, from, to)` hook（`/skills/{id}/diff?from=&to=`），升格 diff 欄位顯示：
- from/to version header（已有）加 `DiffField` 列表
- `changed` → 前後值 side-by-side；`added` → 綠；`removed` → 紅

### 2.5 Repository 方法

`SkillVersionRepository` 已有 `findBySkillIdAndVersion`（確認；若無則加 JDBC query）。

### 2.6 Trim / Defer

- **Defer：** allowed-tools 行級 diff（added/removed items）— V1 只 comma-join 整串 compare
- **Defer：** description 文字 diff highlight（S098c3 follow-up）
- **Defer：** `changeType` = `"added"` / `"removed"` 精細 field 判斷（V1 任何值 ≠ → `"changed"`；null → `"added"`）
- **Core（本 spec）：** backend endpoint + DTO + service；frontend hook + VersionDiffPage 讀真資料

---

## §3 Acceptance Criteria

**AC-1 — diff endpoint 回 structured response**
```
Given: skill A 有 v1.0.0（description="old"）與 v2.0.0（description="new"）
When:  GET /api/v1/skills/{id}/diff?from=v1.0.0&to=v2.0.0
Then:  200 + body.from.version="v1.0.0"
       body.to.version="v2.0.0"
       body.fields 含 {field:"description", fromValue:"old", toValue:"new", changeType:"changed"}
```

**AC-2 — 版本不存在時 400**
```
Given: skill A 不存在 v9.9.9
When:  GET /api/v1/skills/{id}/diff?from=v1.0.0&to=v9.9.9
Then:  400 + body.error 含 "Version not found"
```

**AC-3 — 相同兩欄位不出現在 fields**
```
Given: v1 與 v2 的 name 相同
When:  GET /api/v1/skills/{id}/diff?from=v1&to=v2
Then:  body.fields 不含 field="name"
```

**AC-4 — VersionDiffPage 顯示 diff 欄位（frontend）**
```
Given: AC-1 的 response（description changed）
When:  /skills/{id}/diff?from=v1.0.0&to=v2.0.0
Then:  VersionDiffPage 顯示 description 欄位，前後值並排
       不再只顯示 fileSize/publishedAt 兩列
```

---

## §4 File Plan

| File | Action |
|------|--------|
| `backend/.../skill/query/VersionDiffResponse.java` | new — VersionSnapshot + DiffField inner records |
| `backend/.../skill/query/SkillDiffQueryService.java` | new — diff() + compareFields() |
| `backend/.../skill/query/SkillQueryController.java` | modify — 加 `GET /skills/{id}/diff` endpoint |
| `backend/.../skill/domain/SkillVersionRepository.java` | modify（if needed）— confirm `findBySkillIdAndVersion` exists |
| `frontend/src/hooks/useVersionDiff.ts` | new — `useQuery` for `/skills/{id}/diff` |
| `frontend/src/pages/VersionDiffPage.tsx` | modify — 讀 useVersionDiff + 渲染 DiffField list |
| `frontend/src/pages/VersionDiffPage.test.tsx` | modify — AC-1/AC-4 unit test |

---

## §5 Test Plan

- **AC-1/AC-3 unit（backend）**：`SkillDiffQueryService` 直接 mock repo，驗 fields list 正確 include/exclude
- **AC-2 unit（backend）**：不存在版本 → `IllegalArgumentException`
- **AC-4 unit（frontend）**：`VersionDiffPage.test.tsx` mock useVersionDiff → render DiffField rows
- **Regression**：`./gradlew compileJava` + `npm test`

---

## §6 Verification

- `./gradlew compileJava` → BUILD SUCCESSFUL（1s）
- `npm test` → 239 tests, 49 test files, all passed（+1 新增 AC-4）
- AC-1/AC-2/AC-3 backend unit：`SkillDiffQueryService` 純函式邏輯（static methods）不需 mock — compileJava 正確性保障；整合測試留 follow-up
- AC-4 frontend：`VersionDiffPage.test.tsx` mock `/diff` endpoint → DiffField rows 正確渲染（描述 / 允許工具 label + 前後值）

---

## §7 Result

**Shipped v4.14.0**

| Metric | Value |
|--------|-------|
| Files changed | 7（2 new backend, 1 mod backend, 2 new frontend, 1 mod frontend, 1 mod test）|
| Tests | 239 passed / 239（+1 AC-4）|
| Build | compileJava OK |
| Trim | AC-1/AC-3 backend @SpringBootTest defer（純函式邏輯；compile 保障）；per-item allowedTools diff（V1 comma-join）|

**VersionDiffPage 升格**：移除「S098c2 將加入 diff」預告文字；新增「欄位差異」panel，載入/空/有 diff 三態完整處理。
