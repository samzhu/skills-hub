# S030: Conflict-Class Error Mapping（IllegalState + OptimisticLock → 409）

> Spec: S030 | Size: XS(5) | Status: ✅ Done — target ship `v2.7.0`
> Date: 2026-05-01
> Depends: S016 ✅ + S018 ✅ + S027 ✅
> Trigger: 2026-05-01 /loop tick 5 — (1) duplicate ACL grant 回 HTTP 500（aggregate `IllegalStateException` 未對應 handler）；(2) 5 concurrent ACL grants 4/5 → HTTP 500（`OptimisticLockingFailureException` 未對應 handler，`@Version` field 競態）

---

## 1. Goal

把 conflict 類錯誤從 HTTP 500（with stacktrace 暴露）統一映射到 HTTP 409 Conflict + 結構化 ErrorResponse：
1. `IllegalStateException` → 409 + `STATE_CONFLICT` — covers aggregate state machine violations（suspend DRAFT / reactivate non-SUSPENDED / grant duplicate ACL / revoke missing ACL）
2. `OptimisticLockingFailureException` → 409 + `CONCURRENT_MODIFICATION` — concurrent updates on `@Version`d aggregate

延伸已建立的 `VersionExistsException` → 409 pattern（per `SkillCommandController:140`）— 把目前未 cover 的 conflict 類別納入同一 HTTP 409 Conflict 範疇。

---

## 2. Approach

### 2.1 GlobalExceptionHandler 擴充

`shared/api/GlobalExceptionHandler` 加兩個 handler：

```java
@ExceptionHandler(IllegalStateException.class)
ResponseEntity<ErrorResponse> handleStateConflict(IllegalStateException ex) {
    log.atWarn()
            .addKeyValue("errorCode", "STATE_CONFLICT")
            .addKeyValue("message", ex.getMessage())
            .log("State conflict");
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErrorResponse("STATE_CONFLICT", ex.getMessage(), Instant.now()));
}

@ExceptionHandler(OptimisticLockingFailureException.class)
ResponseEntity<ErrorResponse> handleConcurrentModification(OptimisticLockingFailureException ex) {
    log.atWarn()
            .addKeyValue("errorCode", "CONCURRENT_MODIFICATION")
            .log("Optimistic lock conflict");
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ErrorResponse("CONCURRENT_MODIFICATION",
                    "Resource was modified concurrently. Retry the request.",
                    Instant.now()));
}
```

### 2.2 為何選一律 409 Conflict 而非分流

per HTTP RFC 9110 §15.5.10：「The 409 (Conflict) status code indicates that the request could not be completed due to a conflict with the current state of the target resource.」涵蓋：
- 狀態機違規（DRAFT 不能 suspend — 與目前 state 衝突）
- ACL 重複 / 缺失（與目前 acl_entries 衝突）
- 樂觀鎖（與目前 version 衝突）

400 不適合（請求語法 OK）；422 為「semantic but unprocessable」較模糊；409 為 conflict-class 共識選擇。frontend 可依 `code` 細分使用者訊息（STATE_CONFLICT vs CONCURRENT_MODIFICATION 給不同 UI）。

### 2.3 為何 NOT 引入 specific exception types

選 catch IllegalStateException 而非「per-case 新 exception class（AclEntryAlreadyExistsException 等）」：
- aggregate 已用 IllegalStateException + descriptive message — 慣例
- 4 種 state machine 衝突共一 handler 即可（state machine 衝突語義同類）
- 避免 exception class 爆炸（per-error 一個 class 維護成本高）

`VersionExistsException` 保留是因該 exception 已存在且 ErrorResponse code 為 `VERSION_EXISTS`（與 STATE_CONFLICT 細粒度區分）— 不破既有合約。

### 2.4 為何 NOT 加 retry middleware（OptimisticLock case）

OptimisticLock 自動 retry 不在本 spec scope：
- 無腦 retry 可能 mask 真正衝突（同一 client 連續發兩次 grant，第二次該 fail）
- retry middleware 屬 cross-cutting concern，需考慮 idempotency / backoff / max attempts
- 留 future spec（如 S031 client-side retry policy 或 server-side optimistic retry advice）— 先把 5xx → 4xx 的純 HTTP 語義錯誤糾正

---

## 3. SBE Acceptance Criteria

### AC-1: Duplicate ACL grant → 409 STATE_CONFLICT

```gherkin
Given alice 已上傳 skill A（ACL 含 user:alice:read）
When  POST /api/v1/skills/{A}/acl with {type:user, principal:alice, permission:read}
Then  HTTP 409
And   response body code = "STATE_CONFLICT"
And   message 含 "already exists"
```

### AC-2: Revoke unknown ACL → 409 STATE_CONFLICT

```gherkin
Given alice 已上傳 skill A（ACL 不含 user:nobody:read）
When  DELETE /api/v1/skills/{A}/acl?type=user&principal=nobody&permission=read
Then  HTTP 409 STATE_CONFLICT
And   message 含 "not found"
```

### AC-3: Suspend DRAFT skill → 409 STATE_CONFLICT

```gherkin
Given DRAFT skill A（無 version）
When  POST /api/v1/skills/{A}/suspend with {reason:"test"}
Then  HTTP 409 STATE_CONFLICT
And   message 含 "DRAFT" 或 "current state"
```

### AC-4: Reactivate non-SUSPENDED → 409 STATE_CONFLICT

```gherkin
Given PUBLISHED skill A
When  POST /api/v1/skills/{A}/reactivate with {reason:"test"}
Then  HTTP 409 STATE_CONFLICT
```

### AC-5: Concurrent ACL grants (5 different principals) → 全 201（無 OptimisticLock 失敗）or 部分 409 CONCURRENT_MODIFICATION

```gherkin
Given alice 已上傳 skill A
When  並行 POST 5 個不同 principal 的 ACL grant
Then  成功 grant 計數 ≥ 1
And   失敗 grant（如有）回 HTTP 409 CONCURRENT_MODIFICATION（不是 500）
And   response body 含 retry hint message
```

### AC-6: 既有 unit test 不破

```gherkin
Given S030 改動完成
When  ./gradlew test
Then  既有 292 個 test 仍 PASS（IllegalStateException 在 aggregate test 仍被 thrown；只 HTTP 層映射改變）
```

---

## 4. Interface

### 4.1 GlobalExceptionHandler diff

詳 §2.1。imports 新增 `org.springframework.dao.OptimisticLockingFailureException`。

---

## 5. File Plan

### 5.1 Production (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`（add 2 `@ExceptionHandler`）

### 5.2 Test
- 既有 unit tests 不變（aggregate 仍 throw IllegalStateException；只 HTTP 層映射）
- E2E HTTP retest 涵蓋 AC-1~5

### 5.3 Docs
- CHANGELOG `v2.7.0` entry
- spec-roadmap M26

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | GlobalExceptionHandler 加 2 handler + E2E retest 全 6 AC | AC-1~6 | 🔲 |

POC: not required（純 HTTP exception mapping；無新 dep；無 schema 變更）

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.7.0`
>
> Verification: `./gradlew test` BUILD SUCCESSFUL 2m 9s（既有 292 tests 不破）；E2E HTTP 全 6 個 AC 通過：duplicate grant / revoke missing / suspend DRAFT / reactivate PUBLISHED 全 409 STATE_CONFLICT；5 並行 grant 全回 201 或 409（0 個 500）。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `./gradlew test` | BUILD SUCCESSFUL；既有 292 tests 不破（aggregate 仍 throw IllegalStateException；只 HTTP 層映射改變）|
| HTTP duplicate ACL grant | **409 STATE_CONFLICT**（既有 500）✓ AC-1 |
| HTTP revoke unknown ACL | **409 STATE_CONFLICT**（既有 500）✓ AC-2 |
| HTTP suspend DRAFT skill | **409 STATE_CONFLICT** + msg "Cannot suspend skill in DRAFT status" ✓ AC-3 |
| HTTP reactivate PUBLISHED skill | **409 STATE_CONFLICT** + msg "Cannot reactivate skill in PUBLISHED status" ✓ AC-4 |
| 5 並行 ACL grant 不同 principals | 1×201 + 4×409；**0×5xx** ✓ AC-5 |
| 並行 grant 同 principal（race）| 一個 201 + 一個 **409 CONCURRENT_MODIFICATION** with retry hint ✓ AC-5 |

### 7.2 Files Changed

#### Production (1 file)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java`：
  - import `org.springframework.dao.OptimisticLockingFailureException`
  - 加 `@ExceptionHandler(IllegalStateException.class)` → 409 + `STATE_CONFLICT` code + structured log
  - 加 `@ExceptionHandler(OptimisticLockingFailureException.class)` → 409 + `CONCURRENT_MODIFICATION` code + retry hint message

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: Duplicate ACL grant → 409 STATE_CONFLICT | ✅ PASS | E2E HTTP 確認 |
| AC-2: Revoke unknown ACL → 409 STATE_CONFLICT | ✅ PASS | E2E HTTP 確認 |
| AC-3: Suspend DRAFT → 409 STATE_CONFLICT | ✅ PASS | E2E HTTP 確認 |
| AC-4: Reactivate non-SUSPENDED → 409 STATE_CONFLICT | ✅ PASS | E2E HTTP 確認 |
| AC-5: 並行 grants 全 201 或 409（0 個 5xx）| ✅ PASS | 5 並行 1×201+4×409；同 principal race 1×201+1×409 CONCURRENT_MODIFICATION |
| AC-6: 既有 unit test 不破 | ✅ PASS | 292 tests 全綠 |

### 7.4 Key Findings

**Discovery context**: 2026-05-01 /loop tick 5 — (1) duplicate ACL grant 回 HTTP 500 with stacktrace 暴露；(2) 5 個並行 ACL grants 4/5 → HTTP 500（OptimisticLockingFailureException 未對應 handler，`@Version` field 競態）。

**Root cause**:
- aggregate `Skill.grantAcl/revokeAcl` 用 `IllegalStateException` 表達 state machine 衝突；但 `GlobalExceptionHandler` 沒有對應 `@ExceptionHandler` → fallback 到 Spring 預設 500 with stacktrace 暴露
- Spring Data `@Version` 樂觀鎖在並行 update 時拋 `OptimisticLockingFailureException`；同樣未 handle → 500
- 既有 `VersionExistsException` → 409 pattern（per `SkillCommandController:140`）已建立 conflict 類別 = 409 慣例；只是覆蓋率不全

**Fix design rationale**:
- 一律 409 Conflict — per RFC 9110 §15.5.10「conflict with the current state of the target resource」涵蓋 state machine 違規 / ACL 重複 / 樂觀鎖競態
- 一律 catch-all（IllegalStateException）而非引入 4 種 specific exception type — aggregate 已用 IllegalStateException + descriptive message 為 idiom；single handler 即可
- 不在 server 層 auto-retry — 自動 retry 可能 mask 真正衝突（同一 client 連發兩次 grant 該第二次 fail）；屬 future spec scope（client retry policy 或 idempotency key middleware）

### 7.5 Pending Verification / Tech Debt

**OptimisticLock auto-retry**：當前 client 收 409 CONCURRENT_MODIFICATION 後須自行重試。future spec 可考慮 idempotency-key middleware 或 server-side bounded retry，視業務 idempotency 邊界決定。
