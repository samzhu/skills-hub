# S192-T03: Review/comment/notification actor display data

## 對應規格
S192：作者顯示名稱一致性收斂

## 這個 task 要做什麼
評論、需求留言、通知目前會把 `authorId` 放到 UI 能直接顯示的位置。這個 task 讓 reviews / request comments API 回傳 display companion fields，並讓新通知 title 存可讀名稱。

## 使用者情境（BDD）
Given（前提）Alice 的 user id 是 `u_f7eb3a` 且 name 是 `Sam Zhu`
When（動作）Alice 留下 skill review、request comment，或觸發 review/comment notification projection
Then（結果）API response / notification title 含 `Sam Zhu`
And（而且）delete ownership 判斷仍比較 `authorId`，不是 display name

## 研究來源
- `docs/grimo/specs/2026-05-17-S192-author-display-name-completion.md` AC-S192-2, AC-S192-5, AC-S192-6, AC-S192-7, AC-S192-9
- `backend/src/main/java/io/github/samzhu/skillshub/review/ReviewController.java`
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestQueryController.java`
- `backend/src/main/java/io/github/samzhu/skillshub/notification/NotificationProjectionListener.java`

## 先做 POC
- POC：not required — 既有 controllers already build DTOs from domain rows；本 task 只用 S192-T01 shared resolver enrich fields。

## 正式程式怎麼做
- Class / file 名稱：`ReviewController.ReviewResponse`, `RequestQueryController.CommentDto`, `NotificationProjectionListener`
- 入口：review list endpoint, request detail endpoint, notification listeners
- 必要行為：
  - `ReviewResponse` 新增 `authorDisplayName`, `authorHandle`
  - `CommentDto` 新增 `authorDisplayName`, `authorHandle`
  - New notification titles resolve actor display name and avoid raw `u_<id>`
  - Delete endpoints and current-user comparisons keep `authorId`

## 單元測試 / 整合測試
- `ReviewControllerTest`
  - `@DisplayName("AC-S192-5: review row returns author display data while delete still uses authorId")`
- `CommentControllerTest` or `RequestQueryControllerTest`
  - `@DisplayName("AC-S192-6: request comment returns author display data while delete still uses authorId")`
- `NotificationProjectionListenerTest`
  - `@DisplayName("AC-S192-7: notification title stores actor display name instead of raw user id")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/review/ReviewController.java`
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestQueryController.java`
- `backend/src/main/java/io/github/samzhu/skillshub/notification/NotificationProjectionListener.java`
- related backend tests

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*ReviewControllerTest" --tests "*Comment*Test" --tests "*NotificationProjectionListenerTest"`

## 前置條件
- S192-T01 PASS

## 狀態
PASS

## Result

Date：2026-05-17

實作結果：
- `ReviewController.ReviewResponse` 保留 `authorId`，新增 `authorDisplayName` 與 `authorHandle`，review delete 仍把 `CurrentUser.userId()` 傳給 `ReviewService.deleteReview(...)`。
- `RequestQueryController.CommentDto` 保留 `authorId`，新增 `authorDisplayName` 與 `authorHandle`，comment delete path 仍由既有 `CommentService` 用 `authorId` 判斷。
- `NotificationProjectionListener` 產生新 review/comment notification title 時，先用 `UserDisplayService` 的 display name / handle；若 actor 是 unresolved `u_<id>`，title 不再直接寫 raw id。

驗證：
- RED：`cd backend && ./gradlew test --tests "*ReviewControllerTest" --tests "*Comment*Test" --tests "*NotificationProjectionListenerTest"` -> failed：`ReviewControllerTest.java:63` missing JSON path `authorDisplayName`；`NotificationProjectionListenerTest.java:160` title still contained raw actor id.
- GREEN：`cd backend && ./gradlew test --tests "*ReviewControllerTest" --tests "*Comment*Test" --tests "*NotificationProjectionListenerTest"` -> BUILD SUCCESSFUL in 2m 17s.
