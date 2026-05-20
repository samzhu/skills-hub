# S205: Download Filename UTF-8 Content-Disposition

> 規格：S205 | 大小：XS(8) | 狀態：📋 planned
> 日期：2026-05-20
> 對應：PRD P4 一鍵安裝（Web 下載） / S061 / S176 / S188

---

## 1. 目標

`GET /api/v1/skills/c80ca4cc-9ceb-4586-85bc-c0187d49fab3/download` 現在有回 200 zip bytes，但 response 沒有 `Content-Disposition`，Chrome 只能把檔案存成 `download`。正式站資料裡這筆 skill 的 `skills.name` 是 `OAuth 專家`、`latestVersion` 是 `1`；Cloud Run log 在同一條 download request 看到 `java.nio.charset.UnmappableCharacterException`，發生點是 Tomcat 把 header value 轉成 bytes 時碰到中文。

本 spec 要修的是兩條下載 API 的檔名 header：

- `GET /api/v1/skills/{id}/download`
- `GET /api/v1/skills/{id}/versions/{version}/download`

修完後，下載 `OAuth 專家` 最新版時 response header 會包含：

```http
Content-Disposition: attachment; filename*=UTF-8''OAuth%20%E5%B0%88%E5%AE%B6-1.zip
```

使用者在 Chrome 下載時會拿到可辨識的 zip 檔名，不再退回瀏覽器預設的 `download`。

相依狀態：

| Spec | 狀態 | 對 S205 的影響 |
|---|---|---|
| S061 Download Filename Includes Skill Name | ✅ shipped | 當時把 header 改成 `{skillName}-{version}.zip`，但假設 `skills.name` 一定是 ASCII 安全字元。 |
| S176 Explicit Publish Skill Name | ✅ shipped | 把 `skills.name` 改成人類顯示名稱，允許空白、中文與重名；S061 的 ASCII 前提失效。 |
| S188 Version Label Auto Sequence | ✅ shipped | version 可能是 `1`、`2026.05` 或其他自訂標籤；S205 必須沿用目前 version label。 |
| S204 OAuth Login Error Page | 📋 planned | 只改 OAuth login failure UI，不碰 download endpoint；ordering-only，不阻擋本 spec。 |

非目標：

- 不改 zip bytes、GCS storage path 或 `StorageService.download(...)`。
- 不改 download counter、analytics projection 或 `SkillDownloadedEvent`。
- 不把 `skills.name` 改回 agentskills.io package name regex。
- 不做前端下載按鈕重設計；直接 API / curl 也必須拿到正確 header。

## 2. 研究與設計

### 2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
|------|----------|--------------|
| [RFC 6266 §4.3/§5/§6](https://www.rfc-editor.org/rfc/rfc6266) | `Content-Disposition` 用來告訴瀏覽器下載時的本機檔名；非 ISO-8859-1 字元可用 `filename*=`，範例是 `filename*=UTF-8''%e2%82%ac%20rates`。 | 中文檔名不能裸放進 `filename=`；要產生 `filename*=`。 |
| [RFC 5987 §3.2.1](https://www.ietf.org/rfc/rfc5987) | extended parameter value 會把字元先轉成指定 charset 的 bytes，再做 percent-encoding；producer 必須支援 `UTF-8` 或 `ISO-8859-1`。 | `OAuth 專家` 應變成 UTF-8 bytes 後的 `%E5...`，空白應是 `%20`。 |
| [Spring Framework `ContentDisposition.Builder.filename(String, Charset)`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/ContentDisposition.Builder.html) | Spring 官方 API 說這個 overload 會依 RFC 5987 encode filename，且支援 `US-ASCII`、`UTF-8`、`ISO-8859-1`。 | 用 `ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8)`，不要手寫 encode。 |
| [Spring Framework `ContentDisposition`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/ContentDisposition.html) | `toString()` 回傳 RFC 6266 header value；`getFilename()` 可從 `filename*` 依 RFC 5987 decode。 | Controller 可以把 `ContentDisposition.toString()` 直接放進 `Content-Disposition` header。 |
| [Tomcat `MessageBytes.toBytes()` source](https://nightlies.apache.org/tomcat/tomcat-10.1.x/coverage/org.apache.tomcat.util.buf/MessageBytes.java.html) | Tomcat 會把 header string 轉成 bytes；預設 fast path 遇到 code point > 255 會丟 `IllegalArgumentException`。 | `filename=OAuth 專家-1.zip` 會在 servlet container 層失敗；header value 必須保持 ASCII-safe。 |
| [Tomcat Character Encoding docs](https://cwiki.apache.org/confluence/display/TOMCAT/Character%2BEncoding) | HTTP headers 一律是 US-ASCII；超出範圍的字元需要 encode。 | 修正方向是 encode header parameter，不是改 response body charset。 |
| [S061 archived spec](archive/2026-05-01-S061-download-filename-includes-skill-name.md) | §2.4 說只有空白 / UTF-8 等特殊字元才需要 `filename*`，並依 S041 假設 `skill.getName()` 是 `[a-z0-9-]`。 | S205 明確取代這個前提：`skills.name` 已不是 ASCII-only。 |
| [S176 archived spec](archive/2026-05-15-S176-explicit-publish-skill-name.md) | §1/§2.4 定義 `skills.name` 是平台顯示名稱，允許空白、大小寫、中文與一般標點。 | download filename 必須接受人類顯示名稱。 |

### 2.2 現況

`backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryController.java` 目前直接拼 header：

```java
"attachment; filename=" + skill.getName() + "-" + version + ".zip"
```

當 `skill.getName()` 是 `OAuth 專家` 時，runtime 實際 header string 會變成：

```http
Content-Disposition: attachment; filename=OAuth 專家-1.zip
```

這個字串有空白與中文。Tomcat 在送 response header 時會把它轉成 bytes，中文不在 header 可直接表示的範圍內，所以 Cloud Run log 出現 `UnmappableCharacterException`；瀏覽器最後拿不到 `Content-Disposition`，就用 URL path 的最後段或預設名，結果變成 `download`。

### 2.3 做法比較

| 做法 | 採用 | 理由 |
|----------|--------|-----------|
| A. 用 Spring `ContentDisposition.attachment().filename(filename, UTF_8)` | yes | Spring 會照 RFC 5987 產 `filename*=`；我們傳的是正常 Unicode 字串，header 由框架 encode，最少手寫規則。 |
| B. 自己把 filename 做 percent-encoding 後拼 `filename*=` | no | 容易漏掉 attr-char、quote、大小寫 hex、空白 `%20` 等細節；也容易把已 encoded 的 `%` 再 encode 成 `%25`。 |
| C. 把下載檔名強制改成 ASCII slug，例如 `oauth-expert-1.zip` | no | 可以避開 Tomcat，但丟失 S176 的人類顯示名稱；使用者看不到自己在平台輸入的 `OAuth 專家`。 |
| D. 只在前端 `<a download="...">` 補檔名 | no | 不能修直接 API、curl、版本下載 URL；後端 header 仍可能在 Tomcat 層失敗。 |

選 A。程式只需要建立系統檔名字串，再交給 Spring 產 RFC 6266 header：

```java
ContentDisposition.attachment()
        .filename(filename, StandardCharsets.UTF_8)
        .build()
        .toString()
```

### 2.4 編碼規則

這裡要先 encode，但不是把整段 header 手工 URL encode。

正確流程是：

1. Java 裡保留正常字串：`OAuth 專家-1.zip`。
2. 呼叫 Spring `filename(filename, StandardCharsets.UTF_8)`。
3. Spring 產生 ASCII-safe header parameter：`filename*=UTF-8''OAuth%20%E5%B0%88%E5%AE%B6-1.zip`。
4. Tomcat 送出的 header 全是 ASCII 字元，Chrome 再依 `filename*` 解回 `OAuth 專家-1.zip`。

如果我們先把 `OAuth 專家-1.zip` 自己變成 `%E5...` 再丟給 Spring，Spring 可能會把 `%` 當一般字元處理，反而得到 `%25E5...` 這種雙重編碼。

### 2.5 Task 邊界提示

| Task 候選 | Class / file | 來源 | 正向情境 | 反向情境 | POC |
|-----------|--------------|------|----------|----------|-----|
| T01 | `SkillQueryController` + `SkillQueryControllerApiContractTest` | RFC 6266 / RFC 5987 / Spring `ContentDisposition` | `OAuth 專家` 下載 header 含 `filename*=UTF-8''OAuth%20...zip` | header 不含裸中文，路徑分隔符不保留為 `/` 或 `\` | not required — Spring API 行為可由 slice test 鎖定 |
| T02 | production curl + Cloud Run log check | 正式站 `c80ca4cc-9ceb-4586-85bc-c0187d49fab3` | deploy 後 `curl -D -` 可看到 `content-disposition`，同 revision log 沒有 `UnmappableCharacterException` | 若仍無 header，要回查 Tomcat / proxy log | not required |

## 3. 驗收條件（SBE）

驗證命令：

執行：`./scripts/verify-pr.sh`
通過條件：所有帶 `AC-S205-*` 的測試都是綠燈，且既有 backend/frontend 檢查不因本 spec 失敗。

Ship 前正式 gate：

執行：`./scripts/verify-release.sh`
通過條件：V01-V09 都 PASS；deploy 後補 AC-S205-5 的 curl/log evidence。

| AC | 優先級 | 驗證方式 | 標題 |
|----|----------|--------|-------|
| AC-S205-1 | 必做 | Test | 最新版下載用 UTF-8 `filename*` |
| AC-S205-2 | 必做 | Test | 指定版本下載用 UTF-8 `filename*` |
| AC-S205-3 | 必做 | Test | ASCII 顯示名稱仍有可讀檔名 |
| AC-S205-4 | 必做 | Test | 顯示名稱裡的路徑分隔符不進檔名 |
| AC-S205-5 | 必做 | Demo | 正式站 `OAuth 專家` 下載不再落成 `download` |

**AC-S205-1: 最新版下載用 UTF-8 `filename*`**
- Given（前提）skill id 是合法 UUID，`skills.name="OAuth 專家"`，`latestVersion="1"`，且使用者有 read permission
- When（動作）呼叫 `GET /api/v1/skills/{id}/download`
- Then（結果）HTTP 200，response body 是 zip bytes
- And（而且）`Content-Disposition` 含 `filename*=UTF-8''OAuth%20%E5%B0%88%E5%AE%B6-1.zip`
- And（而且）`Content-Disposition` 不含裸字串 `OAuth 專家`

**AC-S205-2: 指定版本下載用 UTF-8 `filename*`**
- Given（前提）skill id 是合法 UUID，`skills.name="OAuth 專家"`，指定版本是 `2026.05`
- When（動作）呼叫 `GET /api/v1/skills/{id}/versions/2026.05/download`
- Then（結果）HTTP 200
- And（而且）`Content-Disposition` 含 `filename*=UTF-8''OAuth%20%E5%B0%88%E5%AE%B6-2026.05.zip`
- And（而且）`Content-Disposition` 不含裸字串 `OAuth 專家`

**AC-S205-3: ASCII 顯示名稱仍有可讀檔名**
- Given（前提）`skills.name="docker-helper"`，`latestVersion="1.0.0"`
- When（動作）呼叫 `GET /api/v1/skills/{id}/download`
- Then（結果）HTTP 200
- And（而且）`Content-Disposition` 仍含 `docker-helper-1.0.0.zip`

**AC-S205-4: 顯示名稱裡的路徑分隔符不進檔名**
- Given（前提）`skills.name="Team/OAuth\Expert"`，`latestVersion="1"`
- When（動作）呼叫 `GET /api/v1/skills/{id}/download`
- Then（結果）HTTP 200
- And（而且）`Content-Disposition` 含 `filename*=UTF-8''Team-OAuth-Expert-1.zip`
- And（而且）建議檔名不保留 `/` 或 `\` 當路徑分隔符

**AC-S205-5: 正式站 `OAuth 專家` 下載不再落成 `download`**
- Given（前提）新 revision 部署到 Cloud Run
- When（動作）執行：

```bash
curl -sS -D - -o /tmp/oauth-expert.zip \
  https://skillshub-644359853825.asia-east1.run.app/api/v1/skills/c80ca4cc-9ceb-4586-85bc-c0187d49fab3/download
```

- Then（結果）response header 有 `content-disposition`，且包含 `filename*=UTF-8''OAuth%20%E5%B0%88%E5%AE%B6-1.zip`
- And（而且）同 revision Cloud Run log 不再出現 `UnmappableCharacterException` 或 `MessageBytes.toBytes` 的 header encoding error

### 非功能需求檢查

| 分類 | 對應驗收 | 說明 |
|---|---|---|
| Performance | AC-S205-1~4 | 只多一次字串組裝與 framework header builder，不多打 DB/GCS。 |
| Security | AC-S205-4 | user-controlled display name 不可把 `/` 或 `\` 帶進建議檔名。 |
| Reliability | AC-S205-1, AC-S205-2, AC-S205-5 | header 變 ASCII-safe，Tomcat 不會因中文檔名丟掉 header。 |
| Usability | AC-S205-1, AC-S205-2, AC-S205-5 | 使用者下載後看到 `OAuth 專家-1.zip`，不再看到 `download`。 |
| Maintainability | AC-S205-1~4 | 使用 Spring 官方 `ContentDisposition`，避免專案內自製 RFC 5987 encoder。 |

## 4. 介面與 API 設計

HTTP API path 不變，response body 不變，只改 `Content-Disposition` header 建構方式。

Production helper：

```java
private static String contentDisposition(Skill skill, String version)
```

輸入：

- `skill.getName()`：平台顯示名稱，來源是 `skills.name`，可能含中文與空白。
- `version`：`skill.latestVersion` 或 path variable `{version}`。

輸出：

- RFC 6266 `Content-Disposition` header value。
- 非 ASCII 檔名透過 RFC 5987 `filename*=` 表示。

Filename part helper：

```java
private static String safeFilenamePart(String value)
```

規則：

- `trim()` 前後空白。
- `/` 與 `\` 改成 `-`。
- 空白結果 fallback 成 `skill`。
- 其他非 ASCII 字元保留給 Spring `ContentDisposition` 做 UTF-8 percent-encoding。

資料流：

```mermaid
sequenceDiagram
    participant Browser
    participant Controller as SkillQueryController
    participant Service as SkillQueryService
    participant Spring as ContentDisposition
    participant Tomcat

    Browser->>Controller: GET /api/v1/skills/{id}/download
    Controller->>Service: findById(id)
    Controller->>Service: downloadLatest(id)
    Controller->>Spring: filename("OAuth 專家-1.zip", UTF_8)
    Spring-->>Controller: attachment; filename*=UTF-8''OAuth%20...
    Controller-->>Tomcat: ResponseEntity bytes + ASCII-safe header
    Tomcat-->>Browser: 200 zip + Content-Disposition
```

## 5. 檔案規劃

| 檔案 | 動作 | 說明 |
|------|--------|-------------|
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryController.java` | modify | 兩個 download endpoint 改用 `contentDisposition(...)`；新增 `safeFilenamePart(...)`。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/query/SkillQueryControllerApiContractTest.java` | modify | 加 `AC-S205-1` ~ `AC-S205-4` slice tests，鎖定中文、指定版本、ASCII、路徑分隔符。 |
| `docs/grimo/specs/spec-roadmap.md` | modify | 加 S205 Active row 與 v4.89.0 milestone row。 |
| `docs/grimo/specs/2026-05-20-S205-download-filename-utf8-content-disposition.md` | new | 本設計文件；sections 6-7 由 `$planning-tasks S205` 後續補。 |

---

## 6. Task Plan

POC: not required — S205 不新增 dependency；官方 Spring Framework `ContentDisposition.Builder.filename(String, Charset)` 已確認會依 RFC 5987 encode `filename*`，本專案目前 HEAD 也已經有 `SkillQueryController.contentDisposition(...)` 與 `SkillQueryControllerApiContractTest` 的 AC-S205-1~4 測試。此 task plan 的作用是補齊 spec/task 追蹤，讓後續 consolidation/QA/ship 有完整證據鏈。

### 6.1 Pre-Flight Findings

- PRD 對齊：本 spec 修 P4 一鍵下載的檔名可用性，不改 zip bytes、權限、analytics 或 storage path。
- 現有 code 狀態：`SkillQueryController` 已用 `ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8)` 產 header，並用 `safeFilenamePart(...)` 把 `/` 與 `\` 改為 `-`。
- 現有 test 狀態：`SkillQueryControllerApiContractTest` 已有 `@Tag("AC-S205-1")` ~ `@Tag("AC-S205-4")`，覆蓋中文最新版、中文指定版本、ASCII name、路徑分隔符。
- E2E 評估：AC-S205-1~4 是 HTTP header contract，可由 backend slice test 覆蓋；AC-S205-5 是正式站 deploy 後 evidence，不需要 Playwright browser test。

### 6.2 Task Index

| Task | File | AC | Status | Notes |
|---|---|---|---|---|
| T01 | `docs/grimo/tasks/2026-05-20-S205-T01-download-content-disposition.md` | AC-S205-1, AC-S205-2, AC-S205-3, AC-S205-4 | PASS | HEAD 已有 implementation/tests；`./gradlew clean test --tests io.github.samzhu.skillshub.skill.query.SkillQueryControllerApiContractTest` PASS。 |
| T02 | `docs/grimo/tasks/2026-05-20-S205-T02-production-download-evidence.md` | AC-S205-5 | pending | ship/deploy 後用正式站 curl 和 Cloud Run log 補 evidence。 |

### 6.3 Execution Order

1. T01 先確認 local API header contract。若測試已綠，不重寫既有 implementation。
2. T02 在新 revision 部署後補正式站 evidence；若尚未 deploy，先在 spec §7 Pending verification 記錄待補命令。

### 6.4 Verification Commands

- T01: `cd backend && ./gradlew clean test --tests io.github.samzhu.skillshub.skill.query.SkillQueryControllerApiContractTest`
- T02: `curl -sS -D - -o /tmp/oauth-expert.zip https://skillshub-644359853825.asia-east1.run.app/api/v1/skills/c80ca4cc-9ceb-4586-85bc-c0187d49fab3/download`

<!-- Section 7 added after implementation / verification -->
