# S054: Aggregate Null-Param 400 + Placeholder Polish

> Spec: S054 | Size: XS(5) | Status: ✅ Done — target ship `v2.31.0`
> Trigger: 2026-05-01 /loop tick 26 — `POST /api/v1/skills` body `{}`（缺 name）→ HTTP 500 + 訊息「name is required」（NPE）。Aggregate factory 用 `Objects.requireNonNull` 守 null 是 NPE → 落 Spring 預設 500（status code 錯，user 看了像 server 出錯而非自己 input 不對）。順道：tick 26 S053 後 FileDropZone 接受 .zip + .md，但 placeholder 仍只說「拖拽 zip 檔到此處」。

---

## 1. Goal

兩個微修：
1. **Backend**：5 個 `Objects.requireNonNull(x, msg)` 改 `if (x == null) throw new IllegalArgumentException(msg)` — 走 GlobalExceptionHandler 既有 VALIDATION_ERROR → 400。
2. **Frontend**：FileDropZone placeholder 「拖拽 zip 檔到此處」→「拖拽 zip 或 md 檔到此處」對齊 S053 雙格式接受。

---

## 2. Approach

### 2.1 Backend diff

```diff
-Objects.requireNonNull(cmd.name(), "name is required");
-Objects.requireNonNull(cmd.description(), "description is required");
+if (cmd.name() == null) throw new IllegalArgumentException("name is required");
+if (cmd.description() == null) throw new IllegalArgumentException("description is required");
```

5 處：
- `Skill.java` line 89-90（name / description）
- `SkillVersion.java` line 106-108（skillId / version / storagePath）

### 2.2 Frontend diff

```diff
-<p className="font-medium">拖拽 zip 檔到此處</p>
+<p className="font-medium">拖拽 zip 或 md 檔到此處</p>
```

### 2.3 為何 NOT 用 `@NotNull` annotation 在 record 欄位

對齊既有風格（S041 / S042 用 explicit `if + throw IAE`）；不引入 Bean Validation 層。Aggregate factory 守 invariant 為 source of truth，不依賴 controller / DTO 註解。

### 2.4 為何 NOT 在 GlobalExceptionHandler 加 NPE → 400 handler

NPE 太廣 — 真 programmer bug（如 list.get(i) 索引錯）也會 NPE，誤映射為 user 400 會掩蓋真錯。aggregate factory 既然知道是 user input 場景，自己拋對的 exception type 才正確。

---

## 3. SBE Acceptance Criteria

### AC-1: 缺 name 的 JSON POST 回 400 VALIDATION_ERROR

```gherkin
When  POST /api/v1/skills body {}
Then  HTTP 400
And   response body == {error: "VALIDATION_ERROR", message: "name is required", timestamp}
```

### AC-2: 缺 description 也 400

```gherkin
When  POST /api/v1/skills body {"name":"valid","author":"x","category":"y"}
Then  HTTP 400 + VALIDATION_ERROR
```

### AC-3: 完整 body 仍 201

```gherkin
When  POST /api/v1/skills with all required fields
Then  HTTP 201
```

### AC-4: FileDropZone placeholder 顯新文字

```gherkin
When  user 進入 PublishPage
Then  drop zone 顯示「拖拽 zip 或 md 檔到此處」
```

### AC-5: backend 286 tests 不破

### AC-6: frontend 10 tests 不破

---

## 4. Interface

詳 §2.1 / §2.2。

---

## 5. File Plan

### 5.1 Backend (2 files)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`：name + description null check 改 IAE
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersion.java`：skillId / version / storagePath null check 改 IAE

### 5.2 Frontend (1 file)
- `frontend/src/components/FileDropZone.tsx`：placeholder 文字更新

### 5.3 Docs
- CHANGELOG `v2.31.0`
- spec-roadmap M50

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | NPE→IAE + placeholder + curl + Chrome | AC-1~6 | 🔲 |

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.31.0`
>
> Verification: backend 286 / frontend 10 tests / 0 fail（含對齊一個既有 unit test 從 NPE assertion 改 IAE）；E2E：缺 name/description 從 500 NPE → 400 VALIDATION_ERROR；FileDropZone placeholder 顯新文字。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | 286 / 0 fail ✓ AC-5 |
| `npm test` | 10 / 0 fail ✓ AC-6 |
| HTTP `POST /skills` body `{}` | 400 + `{error: "VALIDATION_ERROR", message: "name is required"}` ✓ AC-1 |
| HTTP `POST /skills` 缺 description | 400 + `{error: "VALIDATION_ERROR", message: "description is required"}` ✓ AC-2 |
| HTTP `POST /skills` 完整 body | 201 ✓ AC-3 |
| Chrome `/publish` placeholder | 「拖拽 zip 或 md 檔到此處」✓ AC-4 |

### 7.2 Files Changed

#### Backend (3 files)
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`：name + description null check 從 NPE → IAE；移除 unused `java.util.Objects` import
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersion.java`：skillId/version/storagePath null check 從 NPE → IAE；移除 unused import
- `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillAggregateTest.java`：對齊 unit test 斷言 IAE

#### Frontend (1 file)
- `frontend/src/components/FileDropZone.tsx`：placeholder「拖拽 zip 檔到此處」→「拖拽 zip 或 md 檔到此處」

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: 缺 name → 400 VALIDATION_ERROR | ✅ PASS | curl confirm |
| AC-2: 缺 description → 400 | ✅ PASS | curl confirm |
| AC-3: 合法 body 201 | ✅ PASS | curl confirm |
| AC-4: placeholder 對齊 | ✅ PASS | Chrome confirm |
| AC-5: backend test 不破 | ✅ PASS | 286 / 0 fail |
| AC-6: frontend test 不破 | ✅ PASS | 10 / 0 fail |

### 7.4 Key Findings

**Discovery context**: tick 26 API probe — `POST /api/v1/skills` body `{}` → HTTP 500 + 「name is required」（NPE）。Aggregate factory 用 `Objects.requireNonNull` 守 user input 屬語意錯（NPE 暗示 programmer bug，落 Spring 預設 500）。應該 IAE → 400 走既有 GlobalExceptionHandler 路徑。

**Fix scope choice**:
- 不在 GlobalExceptionHandler 加 NPE → 400 handler — NPE 太廣，真 programmer bug 也 NPE，誤映射掩蓋真錯
- 修 source（aggregate factory 自己拋對的 exception type）為正解
- 順手清 unused `Objects` import 保持檔案整潔
- 對齊 既有 unit test 斷言（test 反映原意「null name 拒絕」即可，exception type 細節可變）

### 7.5 Pending Verification / Tech Debt

- semantic 系統性回 0 根因待查
- analytics「本週新增」算法待驗
- ACL endpoints `/api/v1/skills/{id}/acl` 路徑驗證下輪做（tick 26 誤打 /acl-entries）
