# S192-T01: Backend shared user display service

## 對應規格
S192：作者顯示名稱一致性收斂

## 這個 task 要做什麼
新增 `UserDisplay` 與 `UserDisplayService`，讓 backend user-facing DTO 可以用同一條規則把 `u_<id>` 轉成可讀名稱。這個 task 完成後，低階 `DisplayNameResolver` 仍可回傳 `u_<id>` 給技術/debug call site，但 `UserDisplayService` 不會把 raw platform user id 放進 user-facing display name 欄位。

## 使用者情境（BDD）
Given（前提）Alice 的 user row 是 `id="u_f7eb3a"`, `name="Sam Zhu"`, `handle="samzhu"`, `email="sam@example.com"`
When（動作）backend user-facing projection 呼叫 `UserDisplayService.resolve("u_f7eb3a", false)`
Then（結果）回傳 `displayName="Sam Zhu"`, `handle="samzhu"`, `email=null`
And（而且）當 user row 缺失、只有 `userId="u_f7eb3a"` 時，`UserDisplayService` 的 `displayName` 必須是 `null`，不可等於 `u_f7eb3a`

## 研究來源
- `docs/grimo/specs/2026-05-17-S192-author-display-name-completion.md` §2.3：`UserDisplayService` interface 與 raw id 不可進 user-facing DTO 的規則
- `docs/grimo/specs/archive/2026-05-08-S154-author-display-identity.md` §2.5：`DisplayNameResolver` 保留低階 fallback chain
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/UserRepository.java`
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/DisplayNameResolver.java`

## 先做 POC
- POC：not required — 只使用既有 Spring Data JDBC `UserRepository` 與既有 pure helper，沒有新 package、SDK 或 framework SPI。

## 正式程式怎麼做
- Class / file 名稱：`UserDisplay.java`, `UserDisplayService.java`
- 入口：backend services/controllers that need display fields
- 必要行為：
  - `resolve(String userId, boolean exposeEmail)` 讀 `UserRepository.findById(userId)`
  - 有 user row 時用現有 `DisplayNameResolver.resolve(...)` 算 display name，但若結果等於 raw `userId` 則輸出 `null`
  - `exposeEmail=false` 時 email 欄位輸出 `null`
  - `resolveAll(Collection<String>, boolean)` 批次處理並去重，不因重複 id 重複查詢
- Response / DTO 欄位：
  - `userId`: 原始 platform user id
  - `displayName`: user-facing label；不可等於 `u_<id>`
  - `handle`: user handle
  - `email`: 只有 exposeEmail=true 才回傳

## 單元測試 / 整合測試
- `UserDisplayServiceTest`
  - `@DisplayName("AC-S192-11: user-facing display service never returns raw platform user id as displayName")`
  - `@DisplayName("AC-S192-2: user display service resolves human-readable name and optional email")`
  - `@DisplayName("AC-S192-10: missing user row returns no display label so fixtures must seed actor data")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/UserDisplay.java`
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/UserDisplayService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/UserDisplayServiceTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests "*UserDisplayServiceTest"`

## 前置條件
- 無

## 狀態
PASS

## Result
Date: 2026-05-17
Test: `UserDisplayServiceTest` (`backend/src/test/java/io/github/samzhu/skillshub/shared/security/UserDisplayServiceTest.java`)
Files changed:
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/UserDisplay.java` (new)
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/UserDisplayService.java` (new)
- `backend/src/test/java/io/github/samzhu/skillshub/shared/security/UserDisplayServiceTest.java` (new)
Notes: RED = missing `UserDisplayService` compile error；GREEN = `cd backend && ./gradlew test --tests "*UserDisplayServiceTest"` BUILD SUCCESSFUL in 2m 2s.
