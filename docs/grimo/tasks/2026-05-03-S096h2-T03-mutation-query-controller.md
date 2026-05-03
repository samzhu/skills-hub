# S096h2-T03: NotificationService + NotificationQueryService + Controller 取代 stub

## Spec
S096h2 — Notifications Full Projection（spec doc: `docs/grimo/specs/2026-05-03-S096h2-notifications-projection.md`）

## BDD（涵蓋的 AC）

**AC-6: Mark-read endpoint**
- Given：alice 收 1 筆 unread notification (id=n1)
- When：alice POST `/notifications/n1/read`
- Then：回 204；DB read_at = Instant.now() 戳記；`/unread-count` 變 `{count: 0}`
- 非 owner（bob）POST → 403 not_notification_recipient
- 不存在 id → 404 notification_not_found

**AC-7: Mark-all-read endpoint**
- Given：alice 收 5 筆 unread + 2 筆 already read
- When：alice POST `/notifications/read-all`
- Then：回 204；DB 5 筆 unread 全填 read_at；2 筆 read 不動；`/unread-count` 變 0

**AC-8: Delete endpoint**
- Given：alice 收 1 筆 (id=n1)
- When：alice DELETE `/notifications/n1`
- Then：回 204；DB row 消失（硬刪除）；list 不含 n1
- 非 owner DELETE → 403 not_notification_recipient
- 不存在 id → 404

**AC-9: Preferences endpoint — update**
- Given：alice 預設全 enabled (no row in preferences 表 → fallback TRUE)
- When：alice POST `/notifications/preferences` body `{flags: false, reviews: true, requests: true, versions: true}`
- Then：回 200 + body {...preferences}；DB notification_preferences 對應 row UPSERT

**AC-list: GET /notifications cursor pagination**
- Given：alice 收 25 筆 mixed category
- When：alice `GET /notifications?limit=10` → 回 10 筆 + 「Link: rel=next」or hasNext flag；`?cursor=<lastId>&limit=10` → 下 10 筆
- Then：list ORDER BY created_at DESC；最後一頁 hasNext=false

## Implementation outline

### `backend/.../notification/NotificationService.java` (new)

per spec §4.5：markRead / markAllRead / delete / updatePreferences；ownership 由 service 驗（throw NotNotificationRecipientException 給 GlobalExceptionHandler 翻 403）。

### `backend/.../notification/NotificationQueryService.java` (new)

per spec §4.6：list with cursor + category filter；unreadCount 走 partial index 既有 SQL（T01 schema 已建）。

### `backend/.../notification/NotificationController.java` (modify — 取代 stub)

6 endpoints per spec §4.1：
- `GET /api/v1/notifications?category=&cursor=&limit=` → list
- `GET /api/v1/notifications/unread-count` → unread count
- `POST /api/v1/notifications/{id}/read` → 204
- `POST /api/v1/notifications/read-all` → 204
- `DELETE /api/v1/notifications/{id}` → 204
- `GET /api/v1/notifications/preferences` → preferences
- `POST /api/v1/notifications/preferences` body 部分欄位 → 200 updated

CurrentUserProvider 抽 sub。

### `backend/.../shared/api/{NotificationNotFoundException, NotNotificationRecipientException}.java` (new) + `GlobalExceptionHandler` 加 mapping (404 / 403)

### Tests

- `backend/src/test/.../notification/NotificationServiceTest.java` (new — Testcontainers; AC-6/7/8/9 + ownership 路徑)
- `backend/src/test/.../notification/NotificationControllerTest.java` (new — @WebMvcTest slice or @SpringBootTest if DIRECT_DEPENDENCIES 拉 missing；對齊 S096g2-T01 既驗 pattern)

## Target Files

- `backend/.../notification/NotificationService.java` (new)
- `backend/.../notification/NotificationQueryService.java` (new)
- `backend/.../notification/NotificationController.java` (modify — 取代 stub method bodies；保留 @RestController + @RequestMapping)
- `backend/.../shared/api/NotificationNotFoundException.java` (new)
- `backend/.../shared/api/NotNotificationRecipientException.java` (new)
- `backend/.../shared/api/GlobalExceptionHandler.java` (modify — 加 2 mapping)
- `backend/src/test/.../notification/NotificationServiceTest.java` (new — 5 ACs)
- `backend/src/test/.../notification/NotificationControllerTest.java` (new — 6 endpoints smoke)

## Depends On
- T01 + T02（aggregates / repos / listener 都 ship；本 task 對外暴露 mutation API）

## Status
pending
