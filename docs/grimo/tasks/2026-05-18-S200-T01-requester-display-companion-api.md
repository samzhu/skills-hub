# S200-T01: Request API 回 requester display companion

## 對應規格
S200：Request Requester Display Identity

## 這個 task 要做什麼
`GET /api/v1/requests` 和 `GET /api/v1/requests/{id}` 要在保留 `requesterId` 的同時，多回 `requesterDisplayName` 與 `requesterHandle`。`canDelete` 仍然用 `current.userId == requesterId` 判斷，display 欄位只做畫面文字，不參與權限。

## 使用者情境（BDD）
Given（前提）`users` 表有 `id="u_aa1111", name="Alice Chen", handle="alice"`，Alice 建立一筆 request
When（動作）`GET /api/v1/requests/{id}` 回應
Then（結果）JSON 含 `requesterId="u_aa1111"`
And（而且）JSON 含 `requesterDisplayName="Alice Chen"`
And（而且）JSON 含 `requesterHandle="alice"`

Given（前提）`users` 表有 `id="u_bb2222", name="Bob Lin", handle="bob"`，Bob 建立一筆 request
When（動作）`GET /api/v1/requests` 回應
Then（結果）該 row 含 `requesterId="u_bb2222"`
And（而且）該 row 含 `requesterDisplayName="Bob Lin"`
And（而且）該 row 含 `requesterHandle="bob"`

Given（前提）current user 是 `u_aa1111`，request 的 `requesterId` 也是 `u_aa1111`
When（動作）backend 計算 detail response
Then（結果）`canDelete=true`
And（而且）修改 `requesterDisplayName` 不會影響 `canDelete`

## 研究來源
- `docs/grimo/specs/2026-05-18-S200-request-requester-display-identity.md`
- `CONTEXT.md`：Platform User ID 不可當 user-facing label
- `docs/grimo/specs/archive/2026-05-17-S192-author-display-name-completion.md`
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestQueryController.java`
- `backend/src/main/java/io/github/samzhu/skillshub/shared/security/UserDisplayService.java`
- `backend/src/test/java/io/github/samzhu/skillshub/community/RequestDetailQueryTest.java`

## 先做 POC
- POC：not required — 沿用 S192 已驗證的 `UserDisplayService.resolveAll(...)` display companion pattern，不新增 schema、library 或 framework API。

## 正式程式怎麼做
- Class / file 名稱：`RequestQueryController.java`
- 入口：`list(...)`、`getOne(...)`、`RequestResponse`、`RequestDetailResponse`
- 必要行為：
  - `RequestResponse` 加 nullable `requesterDisplayName` / `requesterHandle`。
  - `RequestDetailResponse` 加 nullable `requesterDisplayName` / `requesterHandle`。
  - list endpoint 用 `userDisplayService.resolveAll(requesterIds, false)` batch 查 requester display，不要每筆 request 單查。
  - detail endpoint resolve requester id；comments 仍沿用既有 author display resolution。
  - `canDelete` 判斷保持 `users.current().userId().equals(request.getRequesterId())`。
  - 若 `UserDisplayService` 找不到 user，display 欄位回 `null`，不要填 raw id。

## 單元測試 / 整合測試
- `RequestDetailQueryTest`
  - `@DisplayName("AC-S200-1: request detail API 回 requester display companion")`
  - `@DisplayName("AC-S200-2: request list API 回 requester display companion")`
  - `@DisplayName("AC-S200-4: canDelete 仍用 requesterId 不用 display name")`

## 會改哪些檔案
- `backend/src/main/java/io/github/samzhu/skillshub/community/RequestQueryController.java`
- `backend/src/test/java/io/github/samzhu/skillshub/community/RequestDetailQueryTest.java`

## 驗證方式
執行：`cd backend && ./gradlew test --tests io.github.samzhu.skillshub.community.RequestDetailQueryTest`

## 前置條件
- 無

## 狀態
pending（待做）
