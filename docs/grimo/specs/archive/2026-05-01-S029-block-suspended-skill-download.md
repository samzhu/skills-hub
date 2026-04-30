# S029: Block Suspended Skill Download（403 SKILL_SUSPENDED）

> Spec: S029 | Size: XS(5) | Status: ✅ Done — target ship `v2.6.0`
> Date: 2026-05-01
> Depends: S018 ✅（SUSPENDED state machine）+ S028 ✅
> Trigger: 2026-05-01 /loop tick 4 — `GET /api/v1/skills/{id}/download` 對 SUSPENDED skill 仍回 HTTP 200 + zip bytes，違反 `SkillStatus.SUSPENDED` Javadoc 設計意圖「因安全風險或違規而下架，不可下載」

---

## 1. Goal

被 suspend 的 skill（status=SUSPENDED）下載 endpoint 回 HTTP 403 SKILL_SUSPENDED 取代既有 HTTP 200 + zip。對 PUBLISHED / DRAFT skill 行為不變（PUBLISHED 200 + zip；DRAFT 因無 version 已 throw NoSuchElementException → 404）。

---

## 2. Approach

### 2.1 Exception type

新增 `SkillSuspendedException extends RuntimeException`（位置 `shared.api`，與 `VersionExistsException` 同層 — cross-aggregate exception）。

```java
public class SkillSuspendedException extends RuntimeException {
    private final String skillId;
    public SkillSuspendedException(String skillId) {
        super("Skill is suspended and cannot be downloaded: " + skillId);
        this.skillId = skillId;
    }
    public String getSkillId() { return skillId; }
}
```

### 2.2 Guard in download chokepoint

`SkillQueryService.downloadAndRecord` 為兩條 download path 的共用 helper（`downloadLatest` + `downloadVersion`）。在 `skillRepo.findById` 取得 aggregate 後立即檢查 status：

```diff
 private byte[] downloadAndRecord(String skillId, SkillVersion version) {
+    var skill = skillRepo.findById(skillId)
+            .orElseThrow(() -> new NoSuchElementException("Skill not found: " + skillId));
+    if (skill.getStatus() == SkillStatus.SUSPENDED) {
+        throw new SkillSuspendedException(skillId);
+    }
     var zipBytes = storageService.download(version.getStoragePath());
-    var skill = skillRepo.findById(skillId)
-            .orElseThrow(() -> new NoSuchElementException("Skill not found: " + skillId));
     skill.recordDownload();
     ...
 }
```

順序調整：先 `findById` 再檢查 status，狀態 fail-fast 不浪費 storage download bandwidth；既有 download + recordDownload 流程不變。

### 2.3 ExceptionHandler

`GlobalExceptionHandler` 加：

```java
@ExceptionHandler(SkillSuspendedException.class)
ResponseEntity<ErrorResponse> handleSuspended(SkillSuspendedException ex) {
    log.atWarn()
            .addKeyValue("errorCode", "SKILL_SUSPENDED")
            .addKeyValue("skillId", ex.getSkillId())
            .log("Download blocked: skill suspended");
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(new ErrorResponse("SKILL_SUSPENDED", ex.getMessage(), Instant.now()));
}
```

選 403 而非 410：
- 403 Forbidden — server understood request but refuses authorization；suspended 是政策/moderation 阻擋
- 410 Gone — 永久不存在；不適合可被 reactivate 的 SUSPENDED 狀態
- ErrorResponse code `SKILL_SUSPENDED` 讓 frontend 區分 generic 403（auth fail）vs 政策阻擋

### 2.4 為何 NOT 在 controller layer 檢查

放 service layer 而非 controller：
- service 層為 single chokepoint — 兩條 download endpoint 共用 `downloadAndRecord`
- 避免 controller 層重複判斷 + DRY
- `@PreAuthorize` 與 status guard 是不同 concerns（前者授權、後者狀態）— 分層清晰

---

## 3. SBE Acceptance Criteria

### AC-1: SUSPENDED skill download → 403 SKILL_SUSPENDED

```gherkin
Given alice 已上傳 skill A 並 suspend
When  GET /api/v1/skills/{A}/download
Then  HTTP 403
And   response body code = "SKILL_SUSPENDED"
And   message 含 skillId
```

### AC-2: SUSPENDED skill specific version download → 403

```gherkin
Given alice 已上傳 skill A v1.0.0 並 suspend
When  GET /api/v1/skills/{A}/versions/1.0.0/download
Then  HTTP 403 SKILL_SUSPENDED
```

### AC-3: PUBLISHED skill download 仍 200（regression check）

```gherkin
Given alice 已上傳 skill B 為 PUBLISHED
When  GET /api/v1/skills/{B}/download
Then  HTTP 200
And   response body 為 zip bytes
And   downloadCount 增 1
```

### AC-4: Reactivated skill download 恢復 200

```gherkin
Given alice 已上傳 skill C → suspend → reactivate
When  GET /api/v1/skills/{C}/download
Then  HTTP 200（status 已從 SUSPENDED 回到 PUBLISHED；guard 通過）
```

---

## 4. Interface

### 4.1 New: `shared/api/SkillSuspendedException.java`

詳 §2.1。

### 4.2 Modified: `skill/query/SkillQueryService.downloadAndRecord`

詳 §2.2 — guard 移到 method 起始。

### 4.3 Modified: `shared/api/GlobalExceptionHandler`

詳 §2.3 — 加 `@ExceptionHandler(SkillSuspendedException.class)` → 403 + SKILL_SUSPENDED code。

---

## 5. File Plan

### 5.1 Production (3 files)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/SkillSuspendedException.java`（new）
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java`（modify `downloadAndRecord`）
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`（add `@ExceptionHandler`）

### 5.2 Test (1 unit test)
- 既有 `SkillSuspendReactivateTest`（@DataJdbcTest slice）— 加 1 case：suspend 後 `downloadAndRecord` throw `SkillSuspendedException`；reactivate 後恢復

### 5.3 Docs
- CHANGELOG `v2.6.0` entry
- spec-roadmap M25

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | new exception + guard + handler + 1 unit test + E2E HTTP retest | AC-1~4 | 🔲 |

POC: not required（純已知 state machine 加 guard；無新 dep；單一 chokepoint）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.6.0`
>
> Verification: `./gradlew test` BUILD SUCCESSFUL 2m 9s（292 tests / 0 fail / 0 disabled）；E2E HTTP 全 4 個 AC 通過（SUSPENDED 兩 endpoint 都 403 SKILL_SUSPENDED；reactivate 後恢復 200）。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | BUILD SUCCESSFUL 2m 9s；292 tests / 0 fail / 0 errors（含新加 `suspendedSkillBlocksDownload` test）|
| HTTP `GET /skills/{A}/download` 對 PUBLISHED skill | 200 ✓ baseline |
| HTTP `POST /skills/{A}/suspend` | 200 ✓ |
| HTTP `GET /skills/{A}/download` 對 SUSPENDED skill | **403 + `{"error":"SKILL_SUSPENDED",...}`** ✓ AC-1 |
| HTTP `GET /skills/{A}/versions/1.0.0/download` 對 SUSPENDED skill | **403 SKILL_SUSPENDED** ✓ AC-2 |
| HTTP `POST /skills/{A}/reactivate` 後 `GET /download` | 200 ✓ AC-4 |

### 7.2 Files Changed

#### Production (3 files)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/SkillSuspendedException.java`（new — 含 `getSkillId()` getter；class Javadoc 說明 403 而非 410 的選擇理由）
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java`（modify `downloadAndRecord`：將 `findById` 上移至 method 起始 + 加 SUSPENDED guard；fail-fast 早於 storage download）
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`（add `@ExceptionHandler(SkillSuspendedException.class)` → 403 + SKILL_SUSPENDED code + skillId structured log）

#### Test (1 file)
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillSuspendReactivateTest.java`（add `suspendedSkillBlocksDownload` test：suspend 後 `downloadLatest` + `downloadVersion` 均 throw `SkillSuspendedException`；含 fail-fast 註解）

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: SUSPENDED skill download → 403 SKILL_SUSPENDED | ✅ PASS | E2E HTTP 確認 |
| AC-2: SUSPENDED specific version download → 403 | ✅ PASS | E2E HTTP 確認 |
| AC-3: PUBLISHED skill download 仍 200（regression）| ✅ PASS | baseline 200 OK |
| AC-4: Reactivated skill download 恢復 200 | ✅ PASS | E2E HTTP 確認 |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 4 — PUT /api/v1/skills/{id}/download 對已 suspend 的 skill 仍回 200 + zip bytes。違反 `SkillStatus.SUSPENDED` Javadoc 設計意圖「因安全風險或違規而下架，不可下載」。

**Fix design rationale**:
- guard 放 service layer 而非 controller — service 層為 single chokepoint，兩條 download endpoint 共用 `downloadAndRecord`，DRY 原則
- guard 順序：先 `findById` 再檢查 status，fail-fast 早於 storage download；節省無謂 bandwidth
- Exception Handler 用 403 而非 410：SUSPENDED 可被 admin reactivate（非永久），與 410 Gone「permanent removal」語意不符；`SKILL_SUSPENDED` error code 區分 generic 403 auth fail
- 安全性：S029 為 row-level access policy；S027 admin bypass 對 ACL 的 `@PreAuthorize` 短路不影響 status guard（在 service layer，不依賴 SecurityContext）— admin 也不能下載 SUSPENDED skill（除非先 reactivate），符合 audit trail 要求

### 7.5 Pending Verification / Tech Debt

無新增 tech debt。S028 §7.5 的「DRAFT/SUSPENDED 在公開 list 仍可見」議題範圍不變（S029 只處理 download 路徑）。
