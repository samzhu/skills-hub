# S162c: ownership 拒絕 409→403 sweep — DELETE/PUT 對 review/collection/skill/flag 等 owner-only 操作

> Spec: S162c | Size: S(6) | Status: 📐 in-design
> Date: 2026-05-09
> Origin: 拆自 S162 META — API consistency 補強；S162 v4.34/v4.35 ship 415/500，AC-8b（ownership 409→403）拆出此 spec

---

## 1. Goal

**一句話：** Bob 嘗試刪 / 改 Alice 的 review / collection / skill / flag 時，後端應回 **403 Forbidden（這操作 user 沒權限）**，不是現在的 **409 Conflict（語意錯）**。

**為什麼重要：**
- HTTP 語意：409 Conflict 表「資源狀態衝突」（如 ETag 不對、duplicate key）；403 Forbidden 才是「user 認證 OK 但無此操作權」（per RFC 9110 §15.5.4）
- 前端拿 409 當「重試可能解」處理（retry、reload state、show conflict UI）— 但 ownership 拒絕重試也沒用，user 會混淆
- 對齊 S162b（401/403 ErrorResponse shape），整套 4xx 語意一致

**非目標：**
- 不改 ACL principal 比對機制（S154 範圍）
- 不改 owner-conditional UI 顯示（前端 button hide / disable 是另議題）

---

## 2. Approach

### 2.1 現況

掃 controller `@DeleteMapping` / `@PutMapping` 對 owner-only resource 操作：

| Endpoint | 現況 ownership 拒絕回什麼 |
|----------|------------------------|
| `DELETE /reviews/{id}` | 待掃 |
| `DELETE /collections/{id}` | 待掃（S164 即將實作）|
| `PUT /skills/{id}` | 待掃（S163 即將實作）|
| `DELETE /skills/{id}` | 待掃（S144 即將實作）|
| `DELETE /flags/{id}` | 待掃 |

掃時若發現某些 endpoint 已 throw `AccessDeniedException` → 已對齊（S162b 已將其轉為 403 ErrorResponse）— skip。
若 throw `IllegalStateException`（"You are not the owner"）+ GlobalExceptionHandler map 到 409 → 本 spec target。
若 throw `OwnershipViolationException`（既有 custom）→ 改 GlobalExceptionHandler map。

### 2.2 設計

統一改用 **`AccessDeniedException`**（Spring Security 標準）拋出 — let SecurityConfig.exceptionHandling 統一處理（S162b ship 後即 403 + ErrorResponse PERMISSION_DENIED）。

**Pattern：**

```java
// 之前
if (!review.author().equals(currentUser.userId())) {
    throw new IllegalStateException("Only author can delete review");  // → 409
}

// 改後
if (!review.author().equals(currentUser.userId())) {
    throw new AccessDeniedException("Only author can delete review");  // → 403
}
```

或更乾淨：用 Spring Security `@PreAuthorize`：

```java
@DeleteMapping("/reviews/{id}")
@PreAuthorize("@reviewSecurity.isOwner(#id, authentication)")
public void delete(@PathVariable String id) { ... }
```

**選 inline `AccessDeniedException`** 為 sweep 統一 pattern（@PreAuthorize 太多 module-specific bean，改動大）。

### 2.3 範圍 — sweep checklist

per Phase 0 task scan + roadmap，預期改：

| Module | Endpoint | 預期改動 |
|--------|----------|---------|
| review | DELETE /reviews/{id} | 已存在 — 掃 sweep |
| review | PUT /reviews/{id} | 若有 — 同 |
| skill | DELETE /skills/{id} | **依賴 S144 ship** — 若 S162c 先 ship，加 placeholder note |
| skill | PUT /skills/{id} | **依賴 S163 ship** — 同 |
| collection | DELETE /collections/{id} | **依賴 S164 ship** — 同 |
| collection | PUT /collections/{id} | 同 |
| flag | DELETE /flags/{id} | 已存在 — 掃 sweep |
| subscription | DELETE /subscriptions/{id} | 已存在（S125b ship）— 掃 sweep |

**Decision point：** S162c 先 ship 還是等 S144/S163/S164 ship 後再 sweep？

我建議 **S162c 先 ship**，覆蓋既有 endpoint（review/flag/subscription）；後續 S144/S163/S164 直接遵循新 pattern（spec §6 互相 cross-ref）。

---

## 3. Acceptance Criteria

```
AC-1: DELETE 別人的 review → 403（不是 409）
  Given Alice 寫了 review；Bob 已登入
  When DELETE /api/v1/reviews/{alice-review-id} as Bob
  Then HTTP 403 + ErrorResponse{ error: "PERMISSION_DENIED", message: "Only author can delete review" }
  And NOT HTTP 409

AC-2: DELETE 自己的 review 仍可（不誤傷）
  Given Alice 寫了 review
  When DELETE /api/v1/reviews/{alice-review-id} as Alice
  Then HTTP 204 + review 從 DB 消失

AC-3: DELETE 別人的 flag → 403
  Given Bob 開了 flag
  When DELETE /api/v1/flags/{bob-flag-id} as Charlie
  Then HTTP 403 + PERMISSION_DENIED

AC-4: DELETE 別人的 subscription → 403
  Given Bob 訂閱了某 skill
  When DELETE /api/v1/subscriptions/{bob-sub-id} as Charlie
  Then HTTP 403 + PERMISSION_DENIED

AC-5: 既有 200/204 path 不破
  Given owner 對自己 resource 操作
  When sweep 後跑既有 test
  Then 全綠（owner-self path 行為不變）

AC-6: 後續 S144/S163/S164 ship 時遵循 pattern
  Given 本 spec ship 後加新 owner-only endpoint
  When code review
  Then reviewer 確認用 AccessDeniedException 不用 IllegalStateException → 409
  Note: 透過 dev-standards 加一條 rule + 互相 cross-ref（無自動化 enforcement，靠 review）
```

**驗證指令：** `cd backend && ./gradlew test`

---

## 4. Files to Change

### Backend production code

per sweep 結果改：

| 檔案 | 變動 |
|------|------|
| `backend/src/main/java/.../review/ReviewCommandService.java`（或 controller）| ownership 檢查改 throw `AccessDeniedException` |
| `backend/src/main/java/.../security/FlagService.java` | 同 |
| `backend/src/main/java/.../skill/SkillSubscriptionService.java`（或對應）| 同 |
| `backend/src/main/java/.../shared/api/GlobalExceptionHandler.java` | 確認 IllegalStateException handler 不被 ownership case 觸發；（若有 OwnershipViolationException custom 改 map）|

### dev-standards

| 檔案 | 變動 |
|------|------|
| `docs/grimo/development-standards.md` | 加一條 「Owner-only endpoint 拒絕拋 `AccessDeniedException` 不拋 `IllegalStateException`」 rule |

### Backend test

| 檔案 | 變動 |
|------|------|
| `backend/src/test/java/.../review/ReviewOwnershipTest.java` | 新增或更新 — verify AC-1, 2 |
| `backend/src/test/java/.../security/FlagOwnershipTest.java` | 同 — AC-3 |
| `backend/src/test/java/.../skill/SkillSubscriptionOwnershipTest.java` | 同 — AC-4 |
| 既有 controller test 含 409 ownership assertion | sweep update assert 403 + ErrorResponse |

---

## 5. Test Plan

### 5.1 自動化

| AC | 驗證方式 |
|----|---------|
| AC-1~4 | 對應 OwnershipTest，4 個 module 各一個負面 case |
| AC-5 | 既有 owner-self test sweep 後跑全綠 |
| AC-6 | manual code review checkpoint，無自動化（dev-standards 文字化）|

### 5.2 手動

無 — 自動化覆蓋足夠。

---

## 6. 風險

| 風險 | 緩解 |
|------|------|
| sweep 漏掉某 endpoint | grep `IllegalStateException` + grep `409` + grep `Only.*owner` 三條 sweep；spec §6 列 final coverage table |
| 既有 client 依賴 409 status code | 不太可能 — 409 拿到也只能 alert user，403 行為等價；release note 提一下 |
| `AccessDeniedException` 在 LAB mode 觸發點不同 | LAB mode user 模擬有 ROLE_admin → 預期不會觸發 ownership reject；確認 LAB user 不誤判為 owner |
| S144/S163/S164 是否 cross-ref 本 spec | 本 spec ship 後，update S144/S163/S164 spec §2 加 reference |
