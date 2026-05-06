# S131 — Error Code Naming Convention Alignment

**Status:** ✅ shipped v4.10.0
**Size:** XS(2-3 pt)
**Depends on:** —
**Target version:** v4.10.0

---

## §1 Goal

`GlobalExceptionHandler` 的錯誤碼混用兩種格式：早期加入的 handler 用 `SCREAMING_SNAKE_CASE`（`VALIDATION_ERROR`、`NOT_FOUND`…），後期加入的用 `snake_case`（`invalid_status_transition`、`flag_not_found`…）。

統一全部改為 `SCREAMING_SNAKE_CASE`，與前端 `ERROR_MESSAGE_BUILDER` 的 key pattern 一致，讓 FE i18n 擴充更直觀。

---

## §2 Approach

**只改 `GlobalExceptionHandler.java` 字串常值 + 對應測試 assertion。**

全 13 個 snake_case 碼改為 SCREAMING_SNAKE_CASE：

| 舊碼 | 新碼 |
|------|------|
| `invalid_status_transition` | `INVALID_STATUS_TRANSITION` |
| `flag_not_found` | `FLAG_NOT_FOUND` |
| `request_not_found` | `REQUEST_NOT_FOUND` |
| `not_request_claimer` | `NOT_REQUEST_CLAIMER` |
| `collection_not_found` | `COLLECTION_NOT_FOUND` |
| `skill_not_publishable` | `SKILL_NOT_PUBLISHABLE` |
| `notification_not_found` | `NOTIFICATION_NOT_FOUND` |
| `not_notification_recipient` | `NOT_NOTIFICATION_RECIPIENT` |
| `not_skill_owner` | `NOT_SKILL_OWNER` |
| `owner_already_exists` | `OWNER_ALREADY_EXISTS` |
| `grant_not_found` | `GRANT_NOT_FOUND` |
| `cannot_revoke_own_owner` | `CANNOT_REVOKE_OWN_OWNER` |
| `bundle_not_published` | `BUNDLE_NOT_PUBLISHED` |

**Explicitly out of scope:**
- `invalid_token`（RFC 6750 Bearer error 規範名；`ErrorResponse.code` 與 `WWW-Authenticate` header 共用值，改大寫會讓兩者不一致）
- `ReviewForbiddenException` handler（`ex.getMessage()` 動態傳入，由 caller 控制格式）

**Frontend 影響：零。** `api-error-messages.ts` `ERROR_MESSAGE_BUILDER` 目前沒有這 13 個 key；snake_case 碼走 `err.message` fallback，改 SCREAMING 後仍走同一 fallback，行為不變。

**Trim / Defer:** 無 — XS 規模，一次 commit 全完整。

---

## §3 Acceptance Criteria

**AC-1 — 所有 HTTP 回應的 `error` code 為 SCREAMING_SNAKE_CASE**
```
Given: client 觸發 InvalidStatusTransitionException（flag 狀態機違規）
When:  backend 回應 400
Then:  body.error == "INVALID_STATUS_TRANSITION"（非 "invalid_status_transition"）
```

**AC-2 — 集合 / 通知 / 授權相關 404/403 碼一致大寫**
```
Given: client GET 不存在的 collection id
When:  backend 回應 404
Then:  body.error == "COLLECTION_NOT_FOUND"（非 "collection_not_found"）
```

**AC-3 — invalid_token 維持原大小寫（RFC 守護）**
```
Given: JWT 缺少 sub claim
When:  backend 回應 401
Then:  body.error == "invalid_token"（符合 RFC 6750 Bearer error 命名）
       AND WWW-Authenticate header 含 error="invalid_token"
```

---

## §4 File Plan

| File | Action |
|------|--------|
| `backend/src/main/java/.../shared/api/GlobalExceptionHandler.java` | 13 個 snake_case 碼改 SCREAMING_SNAKE_CASE |
| `backend/src/test/.../notification/NotificationControllerTest.java` | 更新 2 個 jsonPath `$.error` assertion |
| `backend/src/test/.../community/CollectionControllerTest.java` | 更新 3 個 jsonPath `$.error` assertion + @DisplayName（optional） |

---

## §5 Test Plan

- 執行 `./gradlew test --tests "*.NotificationControllerTest" --tests "*.CollectionControllerTest"` 確認改後仍 PASS
- 確認 `GlobalExceptionHandlerTest`（若存在）或相關 integration test 無 regression
- `invalid_token` path 的測試（S115 AC-1）維持原值不改

---

## §6 Verification

| AC | 方法 | 結果 |
|----|------|------|
| AC-1 INVALID_STATUS_TRANSITION | GlobalExceptionHandler 字串替換 | ✅ |
| AC-2 COLLECTION_NOT_FOUND | CollectionControllerTest 8 tests PASS | ✅ |
| AC-3 invalid_token RFC 守護 | 未動 MissingJwtSubException handler | ✅ |

Build: `./gradlew test --tests "*.NotificationControllerTest" --tests "*.CollectionControllerTest"` → BUILD SUCCESSFUL — 18 tests, 0 failures。

---

## §7 Result

- **Ship date:** 2026-05-07
- **Version:** v4.10.0
- **Diff:** GlobalExceptionHandler.java 13 碼大寫化；NotificationControllerTest 2 assertion；CollectionControllerTest 3 assertion + 2 @DisplayName
- **Verify metric:** 18 tests PASS (CollectionControllerTest 8 + NotificationControllerTest 10)
- **Trim:** 無（XS spec 完整 ship）
