# S062: SkillVersion JSON Hide Internals (storagePath / new)

> Spec: S062 | Size: XS(5) | Status: ✅ Done — target ship `v2.39.0`
> Trigger: 2026-05-01 /loop tick 35 — `GET /api/v1/skills/{id}/versions` 回應暴露 `storagePath`（內部儲存路徑 `skills/{uuid}/{ver}/skill.zip`）+ `new`（Spring Data JDBC `Persistable.isNew()` artifact）。前端 type 雖宣告 `storagePath` 但 grep 確認從未實際使用。屬資訊洩漏（暴露 storage backend 路徑結構）+ 髒 API surface。

---

## 1. Goal

`SkillVersion` 的 `getStoragePath()` 與 `isNew()` 加 `@JsonIgnore` — 這兩個欄位為內部用途（`storagePath` 用於後端 download；`isNew` 用於 Spring Data JDBC INSERT/UPDATE 區分），不應出現於 API JSON。

---

## 2. Approach

### 2.1 Backend diff

```java
// SkillVersion.java
@JsonIgnore
@Override
public boolean isNew() { return isNew; }

@JsonIgnore
public String getStoragePath() { return storagePath; }
```

### 2.2 Frontend type 對齊

```diff
-  /** 套件在 GCS 中的完整物件路徑（前端僅用於組合下載 URL） */
-  storagePath: string
```

註解寫「前端僅用於組合下載 URL」但全 codebase grep 確認從未引用 — 註解過時。下載 URL 由 `/skills/{id}/download` 與 `/skills/{id}/versions/{ver}/download` 兩 endpoint 提供，frontend 不需 storagePath。

### 2.3 為何 NOT 用獨立 DTO

範圍守 XS：domain model + 2 個 `@JsonIgnore` 註解最小改動。獨立 DTO（`SkillVersionView` record）更乾淨但要改 service signature + 多檔案；future spec 候選。

### 2.4 為何 isNew 必須隱藏

`isNew` 為 Spring Data JDBC `Persistable` interface 必填 — 不能改 `private`。但 JSON output 不該暴露 — `@JsonIgnore` 即 Jackson 序列化排除。

---

## 3. SBE Acceptance Criteria

### AC-1: GET versions 不再暴露 storagePath

```gherkin
When  GET /api/v1/skills/{id}/versions
Then  response JSON 每筆 version 不含 storagePath field
```

### AC-2: GET versions 不再暴露 new

```gherkin
When  GET /api/v1/skills/{id}/versions
Then  response JSON 每筆 version 不含 new field
```

### AC-3: 既有 frontend 使用欄位仍存在

```gherkin
When  GET /api/v1/skills/{id}/versions
Then  仍含 id / skillId / version / fileSize / frontmatter / riskAssessment / publishedAt / allowedTools
```

### AC-4: download endpoint 仍正常（storagePath 內部使用不破）

```gherkin
When  GET /api/v1/skills/{id}/download
Then  HTTP 200 + 正確 zip bytes
```

### AC-5: backend 286 / frontend 10 tests 不破

---

## 4. Interface

詳 §2.1 / §2.2。

---

## 5. File Plan

### 5.1 Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersion.java`：`isNew()` + `getStoragePath()` 加 `@JsonIgnore` + import

### 5.2 Frontend (1 file)
- `frontend/src/types/skill.ts`：移除 `storagePath` 欄位

### 5.3 Test
- 既有 test 不破即可；E2E curl 驗 JSON keys

### 5.4 Docs
- CHANGELOG `v2.39.0`
- spec-roadmap M58

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | @JsonIgnore + frontend type + curl retest | AC-1~5 | 🔲 |

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.39.0`
>
> Verification: backend 286 / frontend 10 / 0 fail；E2E：JSON keys 不再含 storagePath / new；download 仍正常。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | 286 / 0 fail ✓ AC-5（backend） |
| `npm test` | 10 / 0 fail ✓ AC-5（frontend） |
| GET versions JSON keys | `[allowedTools, fileSize, frontmatter, id, publishedAt, riskAssessment, skillId, version]` — `storagePath` / `new` 已不在 ✓ AC-1/2/3 |
| GET download | HTTP 200 / 227 bytes ✓ AC-4（storagePath 內部仍可用）|

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersion.java`：`isNew()` + `getStoragePath()` 加 `@JsonIgnore` 註解

#### Frontend (1 file)
- `frontend/src/types/skill.ts`：移除 `storagePath` 欄位（grep 確認從未實際使用）

### 7.3 AC Results Table

| AC | Status |
|----|--------|
| AC-1: storagePath 隱藏 | ✅ |
| AC-2: new 隱藏 | ✅ |
| AC-3: 既有 frontend 用欄位仍存 | ✅ |
| AC-4: download endpoint 仍正常 | ✅ |
| AC-5: 既有 test 不破 | ✅ |

### 7.4 Key Findings

**Discovery context**: tick 35 — `GET /api/v1/skills/{id}/versions` JSON 包含：
- `storagePath: "skills/{uuid}/{ver}/skill.zip"` — 內部儲存路徑暴露 storage backend 結構（GCS / 本地 FS）
- `new: false` — Spring Data JDBC `Persistable.isNew()` 持久化 artifact，無業務語意

Frontend type 雖宣告 `storagePath` 但 grep 全 codebase 無實際引用 — 註解寫「前端僅用於組合下載 URL」過時，實際下載走 `/skills/{id}/download` 與 `/skills/{id}/versions/{ver}/download` endpoint。

**Fix scope choice**:
- 採 `@JsonIgnore` 1-2 line annotation；不引入獨立 DTO（範圍守 XS）
- 兩個欄位 backend 內部仍可用（download 邏輯透過 `getStoragePath()` 取 GCS 路徑下載）
- frontend type 移除 `storagePath` 欄位 + 過時註解

### 7.5 Pending Verification / Tech Debt

- 暫無新 tech debt
