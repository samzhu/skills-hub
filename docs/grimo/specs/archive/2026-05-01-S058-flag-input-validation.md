# S058: Flag Input Validation

> Spec: S058 | Size: XS(5) | Status: ✅ Done — target ship `v2.35.0`
> Trigger: 2026-05-01 /loop tick 31 — `POST /api/v1/skills/{id}/flags` with body 缺 `type`/`description` 觸 NullPointerException → 500 + 「No message available」。根因：`FlagService` 用 `Map.of("type", null, ...)`，Java `Map.of` 不接受 null values → NPE。type 為 DB NOT NULL（varchar(20)）；description 可 nullable 但仍應 trim。

---

## 1. Goal

`FlagService.createFlag` 預驗 `type` + `description`：
- `type` 非 null + trim 非 blank + 長度 ≤ 20（對齊 DB column varchar(20)）
- `description` trim（允許 null/empty — DB 允許）

違反 → IllegalArgumentException → 400 VALIDATION_ERROR。

---

## 2. Approach

### 2.1 Code diff

```java
// FlagService.java
@Transactional
public String createFlag(String skillId, String type, String description) {
    if (type == null || type.isBlank()) {
        throw new IllegalArgumentException("Flag type must not be blank");
    }
    var trimmedType = type.trim();
    if (trimmedType.length() > 20) {
        throw new IllegalArgumentException(
                "Flag type exceeds 20 characters (got: " + trimmedType.length() + ")");
    }
    var trimmedDescription = description == null ? null : description.trim();
    // ... 後續用 trimmedType / trimmedDescription
}
```

`Map.of("type", trimmedType, "description", trimmedDescription)` 仍會對 null description 拋 NPE — 改 `Map<String,Object>` 構築允許 nullable values（如 HashMap），或 description 改 default empty string 入 payload 不入 NULL row column。

對齊 DB schema（description nullable）— 若 user 沒傳，row description=NULL（不入 payload 字段 OR 入空字串）。我會選「null description → 不入 payload；DB row description 為 NULL」最 idiomatic。

### 2.2 Java Map.of vs HashMap

`Map.of` 不接受 null values（拋 NPE）—我們需要：
- type 必填（驗證 → 不會 null）
- description 可 null

solution: 用 `HashMap` allow null OR conditional 構築。我選後者（更明確意圖）：

```java
var payload = new java.util.HashMap<String, Object>();
payload.put("flagId", flagId);
payload.put("type", trimmedType);
if (trimmedDescription != null) {
    payload.put("description", trimmedDescription);
}
```

### 2.3 為何 NOT 在 controller `@Valid` 註解

對齊 S041/S054/S055/S056 既有風格 — service / aggregate 層守 invariant；不依賴 controller annotation。

---

## 3. SBE Acceptance Criteria

### AC-1: 缺 type → 400 VALIDATION_ERROR

```gherkin
When  POST /skills/{id}/flags body {"description":"foo"}（缺 type）
Then  HTTP 400 + 「Flag type must not be blank」
```

### AC-2: type 空字串 → 400

```gherkin
When  POST flags body {"type":"","description":"foo"}
Then  HTTP 400
```

### AC-3: 超長 type (>20) → 400

```gherkin
When  POST flags body {"type":(30 chars),"description":"foo"}
Then  HTTP 400 + 「Flag type exceeds 20 characters」
```

### AC-4: 合法 type + description → 201

```gherkin
When  POST flags body {"type":"MALICIOUS","description":"contains rm -rf /"}
Then  HTTP 201 + {id: <uuid>}
And   GET flags 含這筆
```

### AC-5: type 合法 + description=null → 201

```gherkin
When  POST flags body {"type":"OTHER"}（無 description）
Then  HTTP 201 + {id: <uuid>}
And   GET flags 含這筆，description=null
```

### AC-6: 既有 test 不破

```gherkin
When  ./gradlew test
Then  286 tests / 0 fail
```

---

## 4. Interface

詳 §2.1 / §2.2。

---

## 5. File Plan

### 5.1 Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/security/FlagService.java`：加 type/description 預驗 + 改 HashMap 構築 payload

### 5.2 Test
- 既有 test 不破；E2E curl 5 個 case

### 5.3 Docs
- CHANGELOG `v2.35.0`
- spec-roadmap M54

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | validate + payload fix + curl | AC-1~6 | 🔲 |

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.35.0`
>
> Verification: backend 286 / 0 fail；E2E 6 ACs 全綠；NPE 從 500 → 400 VALIDATION_ERROR。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | 286 / 0 fail ✓ AC-6 |
| 缺 type | 400 「Flag type must not be blank」✓ AC-1 |
| type="" | 400 ✓ AC-2 |
| 30-char type | 400 「exceeds 20 characters (got: 30)」✓ AC-3 |
| MALICIOUS + description | 201 ✓ AC-4 |
| OTHER (no description) | 201 ✓ AC-5（payload 不含 null description；GET flags 顯 description=None）|

### 7.2 Files Changed

#### Backend (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/security/FlagService.java`：
  - `createFlag` 加 type / description 預驗（trim、blank、length）
  - 改 `Map.of` → `HashMap` 構築 payload（避 null value NPE）
  - downstream 用 trimmed values

### 7.3 AC Results Table

| AC | Status |
|----|--------|
| AC-1: 缺 type → 400 | ✅ |
| AC-2: type="" → 400 | ✅ |
| AC-3: 超長 → 400 | ✅ |
| AC-4: 合法 → 201 | ✅ |
| AC-5: null description → 201 | ✅ |
| AC-6: 既有 test 不破 | ✅ |

### 7.4 Key Findings

**Discovery context**: tick 31 endpoint sweep — 透過 `/actuator/mappings` 發現未測過的 `/api/v1/skills/{skillId}/flags` 端點。POST with `{"description":"foo"}`（缺 type）→ 500 + 「No message available」。根因：`FlagService.createFlag` 用 `Map.<String,Object>of("type", null, ...)`；Java `Map.of` 不接受 null values → NPE → 500。

**Fix scope**:
- 預驗 type（DB NOT NULL varchar(20)）+ blank reject + length cap
- description trim（DB nullable text 允許 null/empty）
- payload 改 `HashMap` 構築允許 conditional add description
- downstream FlagReadModel + SkillFlaggedEvent 用 trimmed values（一致性）

### 7.5 Pending Verification / Tech Debt

- semantic 系統性回 0 根因 → 自行解決（vector_store row 累積後 ≥ similarity threshold）— 從 tech debt 清單移除
- DB 既有畸形 entries（version "foo"/"" + ACL null:user:bob:read）需 future migration 清理
- 405 (DELETE skills) 用 S045 normalized response 已正常
