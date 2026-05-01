# S061: Download Filename Includes Skill Name

> Spec: S061 | Size: XS(5) | Status: ✅ Done — target ship `v2.38.0`
> Trigger: 2026-05-01 /loop tick 34 — `GET /api/v1/skills/{id}/download` Content-Disposition `filename=skill.zip` 與 skill name 無關；user 下載多個 skills 都同檔名 `skill.zip` 混淆。`/versions/{ver}/download` 為 `filename=skill-{ver}.zip` 也無 skill 標識。

---

## 1. Goal

`SkillQueryController` 的兩個 download endpoint 之 Content-Disposition filename 改用 `{skillName}-{version}.zip` — `downloadLatest` 用 `latestVersion`，`downloadVersion` 用 path version。確保下載多個 skill 時檔名可區分。

```diff
- "attachment; filename=skill.zip"
+ "attachment; filename=" + skill.getName() + "-" + version + ".zip"
```

---

## 2. Approach

### 2.1 Code diff

```java
// downloadLatest
@GetMapping("/skills/{id}/download")
ResponseEntity<byte[]> downloadLatest(@PathVariable String id) {
    var skill = queryService.findById(id);
    var bytes = queryService.downloadLatest(id);
    return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=" + skill.getName() + "-" + skill.getLatestVersion() + ".zip")
            .body(bytes);
}

// downloadVersion
@GetMapping("/skills/{id}/versions/{version}/download")
ResponseEntity<byte[]> downloadVersion(@PathVariable String id, @PathVariable String version) {
    var skill = queryService.findById(id);
    var bytes = queryService.downloadVersion(id, version);
    return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=" + skill.getName() + "-" + version + ".zip")
            .body(bytes);
}
```

### 2.2 為何 NOT 改 service 回傳 包裝 record

範圍守住 controller-only 改動：service signature 不變。`findById` 既有方法可直接取 skill metadata。額外一次 DB 查詢成本可忽略（既有 `downloadLatest` 內部已 findById 一次）。

### 2.3 Skill name 為 a-z0-9- regex（per S041）— filename 安全

NAME_REGEX 已限為 `[a-z0-9-]{1,64}` — 可直接 inline 進 filename 不需 escape。`skill.getName()` 不會含路徑分隔符 / 空白 / quote 等問題字元。

### 2.4 為何 NOT URL-encode

filename 是 RFC 6266 `Content-Disposition` 的純值；只有特殊字元（如空格、UTF-8）才需要 `filename*` 形式。skill name 為 ASCII 安全字元集，不需 encode。

---

## 3. SBE Acceptance Criteria

### AC-1: downloadLatest 使用 skill name

```gherkin
Given skill name="tick26-final-case1" 且 latestVersion="1.0.0"
When  GET /api/v1/skills/{id}/download
Then  HTTP 200
And   Content-Disposition: attachment; filename=tick26-final-case1-1.0.0.zip
```

### AC-2: downloadVersion 使用 skill name + version

```gherkin
When  GET /api/v1/skills/{id}/versions/2.0.0-rc.1/download
Then  Content-Disposition: attachment; filename={skillName}-2.0.0-rc.1.zip
```

### AC-3: 既有 download 流程不破

```gherkin
When  download 任何 skill
Then  HTTP 200 + bytes 正確 + downloadCount 增 1
```

### AC-4: 既有 test 不破

```gherkin
When  ./gradlew test
Then  286 tests / 0 fail
```

---

## 4. Interface

詳 §2.1。

---

## 5. File Plan

### 5.1 Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryController.java`：兩個 download endpoint 的 Content-Disposition filename 動態組

### 5.2 Test
- 既有 unit test 不破即可；E2E 由 curl 驗 filename header

### 5.3 Docs
- CHANGELOG `v2.38.0`
- spec-roadmap M57

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | filename + curl retest | AC-1~4 | 🔲 |

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.38.0`
>
> Verification: 286 / 0 fail；E2E：filename `tick34-cn-4257-1.0.0.zip` 含 skill name + version。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | 286 / 0 fail ✓ AC-4 |
| HEAD `/skills/{id}/download` | `Content-Disposition: attachment; filename=tick34-cn-4257-1.0.0.zip` ✓ AC-1 |
| HEAD `/skills/{id}/versions/{ver}/download` | 同 filename pattern ✓ AC-2 |
| GET download | HTTP 200 / 227 bytes（正常下載）✓ AC-3 |

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryController.java`：兩個 download endpoint 動態組 filename = `{skillName}-{version}.zip`

### 7.3 AC Results Table

| AC | Status |
|----|--------|
| AC-1: downloadLatest filename 含 name+version | ✅ |
| AC-2: downloadVersion filename 含 name+version | ✅ |
| AC-3: 既有下載流程不破 | ✅ |
| AC-4: backend test 不破 | ✅ |

### 7.4 Key Findings

**Discovery context**: tick 34 — `curl -I /skills/{id}/download` 返回 `filename=skill.zip`（與 skill name 無關）；user 下載多個 skill 全部變 `skill.zip` 衝撞混淆。`/versions/{ver}/download` 也只有版本 filename 缺 skill name 標識。

**Fix scope choice**:
- 不改 service signature；controller 內 `queryService.findById(id)` 即可取 metadata
- skill name 已限 `[a-z0-9-]{1,64}`（S041）— filename 安全字元，不需 URL-encode
- 額外一次 DB findById 查詢成本可忽略（既有 downloadAndRecord 內部已 findById 一次做 SUSPENDED guard）

### 7.5 Pending Verification / Tech Debt

- 暫無新 tech debt
