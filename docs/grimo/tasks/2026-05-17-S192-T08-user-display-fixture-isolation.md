# S192-T08: UserDisplayService test fixture isolation

## 對應規格
S192：作者顯示名稱一致性收斂

## 這個 task 要做什麼
Phase 4 `./gradlew test` 發現 `UserDisplayServiceTest` 使用的 `u_bbbbbb` 和既有 `UserRepositoryTest` 撞同一個 `users.id`。Repository slice tests 共用 PostgreSQL container 且不靠 rollback 清資料，所以 S192 新增 fixture 必須避開既有固定 id。

## 使用者情境（BDD）
Given（前提）全 backend test suite 依序執行 `UserDisplayServiceTest` 與 `UserRepositoryTest`
When（動作）兩者都 seed `users` row
Then（結果）S192 新增測試資料不會和既有 `u_bbbbbb` / `sub-google-2` 撞唯一鍵
And（而且）`resolveAll(...)` 仍驗證重複輸入 id 只回一筆 display data

## 研究來源
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/UserDisplayServiceTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/UserRepositoryTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/shared/persistence/RepositorySliceTestBase.java`
- Phase 4 failure: `./gradlew test` failed with `DuplicateKeyException` at `UserRepositoryTest.java:43`

## 先做 POC
- POC：not required — deterministic fixture collision.

## 正式程式怎麼做
- 將 `UserDisplayServiceTest.resolveAllDeduplicatesIds()` 的 fixed user id 從 `u_bbbbbb` 改成 S192 專用、不和既有 repository test 重複的 id。

## 單元測試 / 整合測試
- `cd backend && ./gradlew test --tests "*UserDisplayServiceTest" --tests "*UserRepositoryTest"`

## 會改哪些檔案
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/UserDisplayServiceTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*UserDisplayServiceTest" --tests "*UserRepositoryTest"`

## 前置條件
- S192-T01~T07 PASS

## 狀態
PASS

## Result
- RED：`cd backend && ./gradlew test` 先在 `UserRepositoryTest.java:43` 失敗，錯誤是 `DuplicateKeyException`；原因是 `UserDisplayServiceTest.resolveAllDeduplicatesIds()` 和既有 repository test 都 seed `users.id='u_bbbbbb'`。
- GREEN：`UserDisplayServiceTest.resolveAllDeduplicatesIds()` 改用 S192 專用 `u_192bbb` / `bob-s192` 後，`cd backend && ./gradlew test --tests "*UserDisplayServiceTest" --tests "*UserRepositoryTest"` PASS（BUILD SUCCESSFUL）。
