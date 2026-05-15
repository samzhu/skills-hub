# S176: Explicit Publish Skill Name（發佈頁手填平台顯示名稱 + 允許重名）

> Spec: S176 | Size: S(8) → M(14) | Status: ✅ Done — Verify-All PASS after T07 display-name correction
> Date: 2026-05-15
> Depends: S003 ✅, S004 ✅, S032 ✅（本 spec 修改其 invariant）, S154b ✅, S173 ✅
> Trigger: production upload after S175 deploy — 使用者重新上傳 skill 時，Cloud Run log 出現 `skills.name` unique constraint 409；使用者要求「skill name 不做重複檢查，加一個欄位是 skill name，不是直接拿 zip 檔名稱當 skill name」。

---

## 1. Goal

`frontend/src/pages/PublishPage.tsx` 發佈表單要多一個「技能名稱」欄位；`POST /api/v1/skills/upload` 要收 `skillName` multipart form field，並把它寫進 `skills.name`。這個 `skillName` 是平台列表、詳情頁、搜尋結果給人看的顯示名稱，可以和 `SKILL.md` frontmatter `name` 不同；同名 skill 可以同時存在，DB 不再用 `skills.name UNIQUE` 擋住第二筆。

現在實體行為是：

- `SkillCommandService.uploadSkill()` 讀 `validation.metadata().get("name")`，把 `SKILL.md` frontmatter `name` 寫成平台 `skills.name`。
- `V1__initial_schema.sql` 建 `skills.name VARCHAR(64) NOT NULL UNIQUE`，正式站同名上傳會被 PostgreSQL 擋掉，Cloud Run log 看到 `Key (name)=(transcribe-video) already exists.`。
- `SkillCommandService.addVersion()` 仍有 S032 guard：新版本 zip 內 `SKILL.md name` 必須等於 `skill.getName()`。

S176 的目標是把「平台上的 skill name」和「包裡的 agentskills.io `SKILL.md name`」拆開：

| 欄位 | 來源 | 寫到哪裡 | 行為 |
|---|---|---|---|
| 平台顯示名稱 | Publish form `skillName` | `skills.name` | 顯示、搜尋、下載檔名 fallback；允許重名；只要求 trim 後非空、長度 ≤ 64、不可含 control characters |
| 套件 metadata name | `SKILL.md` frontmatter `name` | `skill_versions.frontmatter->>'name'` | 符合 agentskills.io；用於匯出/下載後的 skill 本身；不再決定平台 row name |

Ordering note: S174/S175 仍在 Active table，但本 spec 不 import 它們的新型別；S175 native scan hotfix 已完成部署驗證，S174 是 detail 404 UX，都是 ordering-only，不阻擋 S176 設計。

### Out of Scope

- 不做 rename existing skill。
- 不新增 `slug` / `handle` / display name 雙欄位。
- 不保證 `/skills/:author/:name` 在重名後仍是 canonical unique route；ID route 才是 canonical identity。`author/name` route 會在本 spec 中降級成 legacy alias 行為。
- 不改 agentskills.io validator：`SKILL.md` 仍必須有合法 `name` + `description`。

---

## 2. Research + Approach

### 2.1 Sources

| Source | Finding | S176 usage |
|---|---|---|
| [agentskills.io Specification](https://agentskills.io/specification) | `SKILL.md` 必須有 YAML frontmatter；`name` 必填，長度 1-64，只能小寫英數與 hyphen，不能頭尾 hyphen 或連續 hyphen，且 must match parent directory name。 | S176 不取消包內 `name` 驗證；只是不再拿它當平台 `skills.name`。平台 `skillName` 不是 agentskills.io metadata，不套用這個 regex。 |
| [Spring Framework Multipart docs](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/multipart-forms.html) | `multipart/form-data` 解析後，普通欄位和檔案都可用 `@RequestParam` 存取，例如 `@RequestParam("name") String name` 搭配 `@RequestParam("file") MultipartFile file`。 | `SkillCommandController.uploadSkill()` 用 `@RequestParam("skillName") String skillName` 是框架原生路徑，不需 JSON part 或 custom parser。 |
| [PostgreSQL ALTER TABLE docs](https://www.postgresql.org/docs/current/sql-altertable.html) | `ALTER TABLE ... DROP CONSTRAINT [ IF EXISTS ] constraint_name` 會移除指定 constraint；官方說明 drop constraint 也會移除 constraint 底下的 index。 | 新 migration 用 `ALTER TABLE skills DROP CONSTRAINT IF EXISTS skills_name_key;` 解除 inline `UNIQUE` 產生的 constraint。 |
| `docs/grimo/specs/archive/2026-05-01-S032-version-name-consistency.md` | S032 當時假設 `POST /upload` 一定從 `SKILL.md name` 建 aggregate，因此 `PUT /versions` 才要求 zip name 等於 aggregate name。 | S176 反轉這個前提；S032 guard 必須移除或改語意，否則 explicit platform name 會和版本上傳互相打架。 |

### 2.2 Existing Stack Audit

`SkillCommandController.uploadSkill()` 現在 multipart 只收：

```java
@RequestParam("file") MultipartFile file
@RequestParam("version") String version
@RequestParam("category") String category
@RequestParam(name = "visibility", required = false, defaultValue = "PUBLIC") Visibility visibility
```

`frontend/src/api/skills.ts` 也只送 `file/version/category/visibility`。所以 UI 沒有地方讓使用者輸入平台 skill name。

`Skill.create(CreateSkillCommand)` 現在會驗 `name` 不可空、trim 後符合既有 `NAME_REGEX`，而 `PublishPage.tsx` 也有 `pattern="[a-z0-9-]{1,64}"`。這兩個檢查造成使用者輸入「團隊轉錄工具」或「Team Transcribe」時被擋，錯誤訊息會出現 `Skill name must match ^[a-z0-9-]{1,64}$`。這和 S176 的新語意衝突：平台 `skillName` 是人類顯示名稱，不是 `SKILL.md` package name。

`SkillRepository.findByName(String name)` 的 Javadoc 目前寫死「schema 層 `skills.name UNIQUE` 保證最多一筆」。S176 移除 unique 後，這個 method contract 不再成立；如果 production 沒有 caller，就應移除。`findByAuthorAndName(author, name)` 也不再保證唯一，需明確標成 legacy alias，或至少加 deterministic ordering，避免 `LIMIT 1` 隨資料頁面改變回傳不同 row。

### 2.3 Options

| Option | 改哪些實體 | 使用者按下發佈後會看到什麼 | 成本 / 風險 |
|---|---|---|---|
| A. **Reuse `skills.name` as human display name**（推薦） | `PublishPage` 加 `skillName`；upload API 加 multipart field；service 用 `skillName` 建 aggregate；V25 drop `skills_name_key`；Skill aggregate 改成 display-name validation；S032 version guard 移除。 | 使用者輸入 `Team Transcribe` 或 `團隊轉錄工具`，即使 DB 已有另一筆同名，仍回 201；列表會看到兩筆同名但不同 id/作者/時間。 | 最小 schema 改動；但 `/skills/:author/:name` 不再適合當 canonical route，需把 ID route 視為 canonical。 |
| B. 新增 `display_name` 欄位，保留 `skills.name` 為包名 | V25 add `display_name`；所有 list/detail/search/download fallback 改讀 display_name；原 `name UNIQUE` 還要另外解除或改語意。 | UI 可顯示任意名稱，但很多既有 API JSON 的 `name` 仍會混淆；舊 frontend/test 全面要搬欄位。 | 改動面大，且沒有解掉 user 要「skill name 欄位」對目前 API 的直覺。 |
| C. 新增不可重複 `slug`，`name` 改純 display | DB add `slug UNIQUE` + `name` non-unique；route 從 author/name 轉 author/slug 或 id；publish 要處理 slug auto-generate。 | 可同名，又有 stable public route。 | 超過本輪需求；需要 rename/route migration，適合未來 marketplace URL polish。 |

選 A。理由很直接：目前 `skills.name` 已經是所有 list/detail/search/download UI 顯示的欄位；把它改成「使用者輸入的平台顯示名稱」能用最少檔案讓正式站不再因同名 409，也不再把 agentskills.io package name 的 regex 套到人類顯示文字上。

### 2.4 Revised Invariants

1. `skills.id` 是唯一身份；`skills.name` 不是唯一身份。
2. `skills.name` 是人類顯示名稱：trim 後 required、長度 1-64、不可含 control characters；允許空白、大小寫、中文與一般標點。
3. `skill_versions.frontmatter.name` 仍要符合 agentskills.io；但它只描述套件 metadata，不再要求等於 `skills.name`。
4. `POST /api/v1/skills/upload` 少 `skillName` 時回 400；不能 fallback 到 `SKILL.md name`，否則正式站仍會把包名當平台名。
5. 同一作者可以發佈兩筆同名 skill；列表/detail by id 正常，`author/name` route 是 legacy alias，不承諾選到哪一筆以外的唯一語意。

### 2.5 Low-Fidelity UI Sketch

```text
發佈新技能

[ 上傳檔案 | 貼上文本 ]

技能名稱
[ Team Transcribe                          ]
  平台列表顯示名稱，可和 SKILL.md 裡的 name 不同，最多 64 字元。

上傳 Skill 套件 / SKILL.md 內容
[ drop zone 或 textarea                    ]

版本號                    分類
[ 1.0.0       ]           [ DevOps        ]

作者
[ Sam Zhu   @samzhu ]      <- read-only，維持 S154b

可見性
(o) 公開
( ) 私人

[ 發佈技能 ]
```

Text mode 的 live validation 仍檢查 `SKILL.md` frontmatter `name`。如果使用者輸入平台 `skillName=Team Transcribe`，textarea 內 `name: transcribe-video`，兩者可以不同，送出後 DB `skills.name=Team Transcribe`，`skill_versions.frontmatter.name=transcribe-video`。

---

## 3. SBE Acceptance Criteria

### AC-1: Publish form sends explicit skillName

```gherkin
Given 使用者在 PublishPage 貼上 SKILL.md，frontmatter name="internal-package-name"
And   技能名稱欄位輸入 "Team Transcribe"
When  點擊「發佈技能」
Then  前端送出的 multipart FormData 包含 skillName="Team Transcribe"
And   FormData 不包含 author
And   FormData 仍包含 file/version/category/visibility
```

### AC-2: Backend upload uses request skillName, not SKILL.md name

```gherkin
Given multipart request 有 skillName="Team Transcribe"
And   zip 內 SKILL.md frontmatter name="internal-package-name"
When  POST /api/v1/skills/upload
Then  HTTP 201
And   skills.name = "Team Transcribe"
And   skill_versions.frontmatter->>'name' = "internal-package-name"
```

### AC-3: Duplicate platform skill names are allowed

```gherkin
Given DB 已有一筆 skills.name="Team Transcribe"
When  同一使用者再次 POST /api/v1/skills/upload with skillName="Team Transcribe"
Then  HTTP 201
And   DB 有兩筆 skills.name="Team Transcribe"
And   兩筆 id 不同
```

### AC-4: Missing or invalid skillName is rejected before DB write

```gherkin
Given multipart request 少 skillName
When  POST /api/v1/skills/upload
Then  HTTP 400
And   DB 不新增 skills row

Given multipart request skillName="   "
When  POST /api/v1/skills/upload
Then  HTTP 400
And   message 說明 name must not be blank
And   DB 不新增 skills row

Given multipart request skillName 含換行字元
When  POST /api/v1/skills/upload
Then  HTTP 400
And   message 說明 name must not contain control characters
And   DB 不新增 skills row
```

### AC-5: Add version no longer compares SKILL.md name to platform name

```gherkin
Given skill A 的 platform display name 是 "Team Transcribe"
When  owner PUT /api/v1/skills/{A}/versions with SKILL.md frontmatter name="internal-package-v2"
Then  HTTP 200
And   skill latestVersion 更新成新版本
And   新版 skill_versions.frontmatter->>'name' = "internal-package-v2"
```

### AC-6: DB migration removes name uniqueness

```gherkin
Given 新資料庫跑完 Flyway migration
When  查 pg_constraint
Then  skills 表不存在 skills_name_key unique constraint

Given 新資料庫跑完 Flyway migration
When  直接 INSERT 兩筆不同 id、相同 name 的 skills row
Then  第二筆 INSERT 成功
```

### AC-7: Legacy author/name route is deterministic but not canonical

```gherkin
Given DB 有兩筆同 author + same name 的 skills
When  GET /api/v1/skills/{author}/{name}
Then  API 不丟 500
And   回傳其中一筆 deterministic row
And   文件或 source comment 標示 ID route 才是 canonical identity
```

---

## 4. Interface Design

### 4.1 HTTP Contract

```http
POST /api/v1/skills/upload
Content-Type: multipart/form-data

skillName=Team Transcribe
version=1.0.0
category=DevOps
visibility=PUBLIC
file=<zip or SKILL.md>
```

Controller signature:

```java
@PostMapping("/upload")
ResponseEntity<Map<String, String>> uploadSkill(
    @RequestParam("skillName") String skillName,
    @RequestParam("file") MultipartFile file,
    @RequestParam("version") String version,
    @RequestParam("category") String category,
    @RequestParam(name = "visibility", required = false, defaultValue = "PUBLIC") Visibility visibility
) throws IOException
```

Service canonical signature:

```java
@Transactional
public String uploadSkill(
    byte[] uploadedBytes,
    String skillName,
    String version,
    String author,
    String category,
    Visibility visibility,
    @Nullable String authorNameSnapshot
) throws IOException
```

Implementation rule:

```java
var packageName = (String) validation.metadata().get("name");
var description = (String) validation.metadata().get("description");

var skill = Skill.create(new CreateSkillCommand(
    skillName, description, author, category, visibility, authorNameSnapshot));

var publishCmd = new PublishVersionCommand(
    skill.getId(), version, storagePath, zipBytes.length, fileCount, validation.metadata());
```

`packageName` 可以保留在 log 裡幫 debug，但不可再傳進 `CreateSkillCommand.name()`。

### 4.2 Frontend API

```ts
export async function uploadSkill(
  file: File,
  skillName: string,
  version: string,
  category: string,
  visibility: Visibility = 'PUBLIC',
): Promise<{ id: string }>
```

`PublishPage` state:

```ts
const [skillName, setSkillName] = useState('')
```

Submit disabled:

```ts
const submitDisabled =
  mutation.isPending ||
  skillName.trim().length === 0 ||
  (mode === 'file' ? !file : skillMdText.trim().length === 0 || fmValidation.errors.length > 0)
```

UI rule: remove the HTML `pattern="[a-z0-9-]{1,64}"` from the platform `skillName` input. Keep `maxLength={64}` and a short hint that this is the platform display name, not the `SKILL.md` package name.

### 4.3 Migration

```sql
-- V25__drop_skill_name_unique.sql
ALTER TABLE skills DROP CONSTRAINT IF EXISTS skills_name_key;
```

No data backfill needed：既有 rows 保持原 name；只解除 future insert constraint。

### 4.4 S032 Replacement

移除 `SkillCommandService.addVersion()` 中這段邏輯：

```java
var zipName = (String) validation.metadata().get("name");
if (zipName != null && !zipName.equals(skill.getName())) {
    throw new IllegalArgumentException(...);
}
```

移除或改寫 `SkillUploadAllowedToolsTest` 裡 `AC-S032` mismatch should fail 的 test。新的 test 應證明 mismatch 可以成功，因為平台 name 和 package name 已分離。

---

## 5. File Plan

### Production

| File | Change |
|---|---|
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java` | `POST /upload` 增加 `@RequestParam("skillName")`，log 加 `skillName`，呼叫新 service signature。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java` | 新 canonical upload signature；用 `skillName` 建 `CreateSkillCommand`；保留 `SKILL.md` metadata 給 `SkillVersion`；移除 S032 addVersion name mismatch guard。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/CreateSkillCommand.java` | Javadoc 改成 platform display name；不再說符合 agentskills.io regex。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` | `Skill.create()` 對 `cmd.name()` 改成 display-name validation：required、trim、長度 ≤ 64、拒收 control characters；不再使用 agentskills.io `NAME_REGEX`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillCreatedEvent.java` | Javadoc 改成平台顯示名稱；事件 payload 欄位名稱暫不改，避免破壞既有 subscriber。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillRepository.java` | 移除未使用 `findByName`，或改成 `List<Skill> findAllByName`；更新 `findByAuthorAndName` Javadoc，必要時 SQL 加 `ORDER BY created_at DESC, id DESC LIMIT 1` 讓 legacy alias deterministic。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/TestDataController.java` | seed path 傳入 explicit platform `req.name()`；合成 SKILL.md 時仍可用同一 name 當包名。 |
| `backend/src/main/resources/db/migration/V25__drop_skill_name_unique.sql` | drop `skills_name_key`。 |
| `frontend/src/api/skills.ts` | `uploadSkill(file, skillName, version, category, visibility)` 並 append `skillName`。 |
| `frontend/src/pages/PublishPage.tsx` | 新增「技能名稱」Input；送出前 required；FormData 使用使用者輸入名稱；移除 kebab-case `pattern`，文案改成「平台顯示名稱，可和 SKILL.md 裡的 name 不同」。 |

### Tests

| File | BDD coverage |
|---|---|
| `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillCommandServiceTest.java` 或新 `SkillUploadExplicitNameTest.java` | AC-2, AC-3, AC-4, AC-5：explicit display `skillName`、duplicate names、blank/control-character rejection、version mismatch allowed。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillPublishForgeryTest.java` | Controller verify 新 service args；仍證明 author 由 server 取，不用 request author。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/testsupport/TestDataControllerTest.java` | Seed endpoint 改 call 新 signature。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillUploadAllowedToolsTest.java` | 移除舊 AC-S032 fail expectation；加新 AC-S176-5 pass expectation 或搬到新 test。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillMigrationTest.java` 或現有 migration test 類 | AC-6：constraint absent + duplicate insert succeeds。 |
| `frontend/src/pages/PublishPage.test.tsx` | AC-1：填「Team Transcribe」後 FormData 有 `skillName`，不含 author；缺 skillName 時 button disabled；input 不再有 kebab-case pattern。 |
| `frontend/src/api/skills.test.ts`（若存在） | `uploadSkill` append 欄位順序/內容。 |
| `backend/src/test/java/io/github/samzhu/skillshub/e2e/SkillsHubAuthE2ETest.java` | helper upload multipart 加 `skillName`。 |

### Verification Commands

```bash
cd backend && ./gradlew test --tests "*SkillUploadExplicitNameTest" --tests "*SkillPublishForgeryTest" --tests "*TestDataControllerTest" --tests "*SkillsHubAuthE2ETest"
cd frontend && npm test -- PublishPage.test.tsx
SKIP_NATIVE=1 ./scripts/verify-all.sh
```

Production retest after deploy:

1. Chrome 開 `https://skillshub-644359853825.asia-east1.run.app/publish`。
2. 用同一個 `skillName=transcribe-video` 上傳兩次。
3. 第二次應回成功並跳 `/publish/validate?id=<new-id>`；Cloud Run log 不應再有 `skills_name_key` / `duplicate key value violates unique constraint`。
4. 詳情頁 by id 可以開兩筆不同 id；列表搜尋 `transcribe-video` 可以看到兩筆。

---

## 6. Task Plan

POC: not required — S176 只使用現有 Spring MVC multipart `@RequestParam`、現有 Spring Data JDBC aggregate validation、現有 Flyway/PostgreSQL migration pattern、現有 React form/FormData path；沒有新 package、SDK、framework SPI 或未知外部 API。Phase 0 pre-flight 已重新檢查 PRD P2 發佈流程、S032 舊 invariant、S154b author forge fix、V1 schema 與目前 upload code；未發現需要回到 `/planning-spec` 的設計矛盾。

E2E seam decision: required — AC-1 是使用者在 browser 看到並送出的發佈表單；AC-2/3 是 HTTP multipart → controller → service → DB migration 的 real assembly path。T05 會補 `@S176` Playwright flow，用 `/publish` 頁面實際送出 duplicate platform name，避免只用 unit test 證明單一元件。

| Task | Scope | AC | Depends | Verification |
|---|---|---|---|---|
| T01 | Drop `skills.name` unique constraint and make legacy author/name lookup deterministic | AC-6, AC-7 | — | `cd backend && ./gradlew test --tests "*SkillNameUniquenessMigrationTest" --tests "*SkillRepositoryDuplicateNameTest"` |
| T02 | Backend upload API/service uses explicit `skillName` and allows duplicate platform names | AC-2, AC-3, AC-4 | T01 | `cd backend && ./gradlew test --tests "*SkillUploadExplicitNameTest" --tests "*SkillPublishForgeryTest" --tests "*TestDataControllerTest"` |
| T03 | Version publishing keeps `SKILL.md name` as package metadata and no longer compares it to platform name | AC-5 | T02 | `cd backend && ./gradlew test --tests "*SkillUploadAllowedToolsTest" --tests "*SkillUploadExplicitNameTest"` |
| T04 | PublishPage and frontend upload API send `skillName` from a required UI field | AC-1 | T02 | `cd frontend && npm test -- PublishPage.test.tsx` |
| T05 | Browser E2E proves duplicate platform names can be published through the assembled app | AC-1, AC-2, AC-3 | T01-T04 | `cd e2e && npx playwright test --grep @S176` |
| T06 | Update existing happy-path publish E2E to fill the required platform `skillName` field | V07 gate | T05 / Phase 4 finding | `cd e2e && npx playwright test --grep @happy-path` |
| T07 | Correct platform `skillName` from agentskills.io package-name regex to human display-name validation | AC-1, AC-2, AC-4 | T01-T06 | `cd backend && ./gradlew test --tests "*SkillUploadExplicitNameTest" --tests "*SkillPublishForgeryTest"` + `cd frontend && npm test -- PublishPage.test.tsx` |

After all tasks pass, Phase 4 runs `SKIP_NATIVE=1 ./scripts/verify-all.sh`, records §7 results, and leaves production deploy/log retest as the post-release verification step unless the user asks to deploy in the same turn.

---

## 7. Implementation Results

### Revision Needed Before Shipping — T07（2026-05-15）

使用者指出平台 `skillName` 可以任意輸入，因為它跟 skill package 的 `SKILL.md` 檔案內容沒有影響，是給人看的名稱。這修正了 T01-T06 的一個設計錯誤：T04 目前在 `frontend/src/pages/PublishPage.tsx` 對「技能名稱」保留 `pattern="[a-z0-9-]{1,64}"`，T02 目前在 `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` 仍回 `Skill name must match ^[a-z0-9-]{1,64}$`。

T07 要做的實體修改：

- `Skill.create()`：`skills.name` 改驗 display name：trim 後非空、長度 ≤ 64、不可含 control characters；允許 `Team Transcribe`、`團隊轉錄工具`。
- `SkillValidator`：不改。`SKILL.md` frontmatter `name` 仍照 agentskills.io regex 和 parent-folder 規則。
- `PublishPage`：移除 `pattern` / `title` 的 kebab-case 限制，欄位提示改成平台顯示名稱。
- Tests：把 `Bad Name!` 應失敗改成應成功；新增 blank/control-character 應 400；frontend FormData 用 `Team Transcribe`。

T01-T06 的 Verify-All PASS 是「同名可重複 + platform name 與 package name 分離」的證據；T07 完成前不可 shipping，因為目前使用者在發佈頁輸入空白或中文顯示名稱仍會被錯誤擋下。

### T07 — PASS（2026-05-15）

使用者確認平台 `skillName` 是給人看的顯示名稱，不應套用 agentskills.io `SKILL.md name` 的 package-name regex。這次修正後：

- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`：`Skill.create()` 會接受 `Team Transcribe`、`團隊轉錄工具` 這類平台顯示名稱；只拒收 null、trim 後空字串、超過 64 字元、control characters。
- `frontend/src/pages/PublishPage.tsx`：發佈頁「技能名稱」欄位移除 `pattern="[a-z0-9-]{1,64}"` 和 kebab-case title；欄位提示改成「平台列表顯示名稱，可和 SKILL.md 裡的 name 不同，最多 64 字元。」
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillUploadExplicitNameTest.java`：新增/上傳測試改用 `Team Transcribe` 與 `團隊轉錄工具`，並保留 blank/control-character 應失敗。
- `frontend/src/pages/PublishPage.test.tsx`：FormData 測試改送 `skillName="Team Transcribe"`，並確認 input 不再有 package-name `pattern`。
- `e2e/tests/S176-explicit-publish-skill-name.spec.ts`：S176 browser publish flow 改用 `Team Transcribe` 當平台名稱。

Verification:

```bash
cd frontend && npm test -- PublishPage.test.tsx EditSkillModal.test.tsx MySkillsPage.test.tsx PageHeader.test.tsx
```

Result: 4 files passed / 46 tests passed.

```bash
cd backend && ./gradlew test --tests "*SkillUploadExplicitNameTest" --tests "*SkillPublishForgeryTest" --tests "*SkillUpdateControllerTest" --tests "*SkillCommandControllerSecurityTest" --tests "*SkillCommandServiceDeleteTest"
```

Result: `BUILD SUCCESSFUL in 2m 48s`.

Coverage note: 現成 E2E 只有 S176 新增發佈流程；修改/刪除這次由 `EditSkillModal.test.tsx`、`MySkillsPage.test.tsx`、`SkillUpdateControllerTest`、`SkillCommandControllerSecurityTest`、`SkillCommandServiceDeleteTest` 確認。T07 後已重跑 `SKIP_NATIVE=1 ./scripts/verify-all.sh` 並 PASS。

Phase 4 first retry after T07:

```bash
SKIP_NATIVE=1 ./scripts/verify-all.sh
```

Red result: V01 failed in `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillAggregateTest.java` because two old S041 assertions still expected platform `skills.name` to follow agentskills.io package-name regex:

- `BadName` expected `IllegalArgumentException`, but S176-T07 correctly accepts uppercase display names.
- Empty name expected message containing `must match`, but S176-T07 correctly returns `Skill name must not be blank`.

Fix: update `SkillAggregateTest` so uppercase display name succeeds and empty display name expects `must not be blank`.

Verification:

```bash
cd backend && ./gradlew test --tests "*SkillAggregateTest"
```

Result: `BUILD SUCCESSFUL in 2m 22s`.

### T01 — PASS（2026-05-15）

Commit `46baf24 fix: allow duplicate skill names`：

- `V25__drop_skill_name_unique.sql` 移除 `skills_name_key` unique constraint。
- `SkillRepository.findByAuthorAndName(...)` 改成 deterministic latest-row legacy alias。
- `SkillNameUniquenessMigrationTest` 和 `SkillRepositoryDuplicateNameTest` 通過。

### T02 — PASS（2026-05-15）

Commit `24ee27a fix: require explicit upload skill name`：

- `POST /api/v1/skills/upload` 新增 required multipart `skillName`。
- `SkillCommandService.uploadSkill(...)` canonical signature 改成 `byte[], skillName, version, author, category, visibility, authorNameSnapshot`。
- `skills.name` 由 request `skillName` 寫入；`skill_versions.frontmatter->>'name'` 保留 zip 內 `SKILL.md name`。
- `TestDataController` seed path 傳 `req.name()` 當平台 skill name。
- `SkillPublishForgeryTest` 驗證新 signature 下 author 仍來自 `CurrentUserProvider`，request `author` 仍被忽略，且缺少/非法 `skillName` 回 HTTP 400。

Verification:

```bash
cd backend && ./gradlew test --tests "*SkillUploadExplicitNameTest" --tests "*SkillPublishForgeryTest" --tests "*TestDataControllerTest"
```

Result: `BUILD SUCCESSFUL in 2m 32s`

### T03 — PASS（2026-05-15）

Commit message `fix: allow package name changes on version publish`：

- `SkillCommandService.addVersion(...)` 不再比較 `SKILL.md name` 與平台 `skills.name`。
- `SkillUploadAllowedToolsTest` 改為 `AC-S176-5`：新版本 package name 可不同於平台 name，`latestVersion` 更新成 `1.1.0`，`skill_versions.frontmatter.name` 保存 package metadata。
- 同一測試確認同一 skill 同 version 第二次上傳仍丟 `VersionExistsException`，版本唯一檢查未被移除。
- `SearchProjection` 更新過時 S032 註解，明確寫出 S176 後 package name 可不同於平台 name。

Verification:

```bash
cd backend && ./gradlew test --tests "*SkillUploadAllowedToolsTest" --tests "*SkillUploadExplicitNameTest"
```

Red result: `SkillUploadAllowedToolsTest > AC-S176-5... FAILED`，`addVersion(...)` 在舊 name mismatch guard 丟 `IllegalArgumentException`。

Green result: `BUILD SUCCESSFUL in 2m 31s`

### T04 — PASS（2026-05-15）

Commit message `feat: require publish page skill name`：

- `PublishPage` 新增 required「技能名稱」欄位，並在欄位下方顯示「小寫英數與 hyphen，最多 64 字元。這是平台列表顯示名稱。」
- `submitDisabled` 新增 `skillName.trim().length === 0`，缺技能名稱時不能送出。
- `PublishPage` 呼叫 `uploadSkill(submitFile, skillName.trim(), version, category, visibility)`。
- `frontend/src/api/skills.ts uploadSkill(...)` 新增 `skillName` 參數，multipart `FormData` 會 append `skillName`，仍不送 `author`。
- `PublishPage.test.tsx` 新增 `AC-S176-1`：送出 text-mode SKILL.md 時 FormData 含 `skillName="platform-skill"`、不含 `author`，且缺技能名稱時「發佈技能」按鈕 disabled。

Verification:

```bash
cd frontend && /Users/samzhu/.nvm/versions/node/v20.19.3/bin/npm test -- PublishPage.test.tsx
```

Red result: 2 failed / 9 passed；失敗點為找不到 `技能名稱` label，以及缺技能名稱時 submit button 仍未 disabled。

Green result: 1 file passed / 11 tests passed.

### T05 — PASS（2026-05-15）

Commit message `test: add S176 duplicate publish e2e`：

- 新增 `e2e/tests/S176-explicit-publish-skill-name.spec.ts`。
- Playwright 透過 `/publish` text mode 連續送出兩筆相同平台 `skillName="transcribe-video"` 的 skill。
- 第一筆 SKILL.md frontmatter `name="internal-package-one"`，第二筆 `name="internal-package-two"`；兩次都成功跳到 `/publish/validate?id=...` 並完成 `/publish/review?id=...`。
- 測試確認兩次 publish id 不同，且 `GET /api/v1/skills?keyword=transcribe-video&page=0&size=20` 查回兩筆同名平台 skill，覆蓋 AC-1/2/3 的 browser → HTTP multipart → service → DB 組裝路徑。

Verification:

```bash
cd e2e && /Users/samzhu/.nvm/versions/node/v20.19.3/bin/npx playwright test --grep @S176
```

Red result: webServer 成功啟動後 `Error: No tests found`。

Green result: `1 passed (22.1s)`。

### Phase 4 Finding — V07 failed before S176-T06（2026-05-15）

`SKIP_NATIVE=1 ./scripts/verify-all.sh` first run:

- V01 PASS
- V02 INFO：LINE coverage = 85.8%（covered=4620 / total=5382）
- V03 PASS
- V04 PASS
- V05 PASS
- V06 PASS
- V07 FAIL
- V08a PASS
- V08b SKIP（`SKIP_NATIVE=1`）

V07 failure detail:

- Failed test：`e2e/tests/S140-critical-path-publish.spec.ts` `@happy-path`
- Actual：`發佈技能` button stayed disabled for 30s.
- DOM snapshot：`textbox "技能名稱"` existed but was empty.
- Root cause：S176-T04 correctly made platform `skillName` required, but the older S140 happy-path publish test still filled only SKILL.md text, category, and version.

### T06 — PASS（2026-05-15）

Commit message `test: fill skill name in happy-path publish e2e`：

- `e2e/tests/S140-critical-path-publish.spec.ts` now fills `技能名稱 = "ac3-publish-helper"` before clicking `發佈技能`。
- This keeps the existing PRD P2 publish browser flow aligned with S176's required platform skill name field.

Verification:

```bash
cd e2e && /Users/samzhu/.nvm/versions/node/v20.19.3/bin/npx playwright test --grep @happy-path
```

Result: `9 passed (27.9s)`.

### Phase 4 Retry before T07 — PASS（2026-05-15）

```bash
SKIP_NATIVE=1 ./scripts/verify-all.sh
```

Result:

- V01 PASS
- V02 INFO：LINE coverage = 85.9%（covered=4622 / total=5382）
- V03 PASS
- V04 PASS
- V05 PASS
- V06 PASS
- V07 PASS
- V08a PASS
- V08b SKIP（`SKIP_NATIVE=1` dev opt-out）

Verdict: all CRITICAL passed; exit=0.

### Phase 4 Retry after T07 — PASS（2026-05-15）

```bash
SKIP_NATIVE=1 ./scripts/verify-all.sh
```

Result:

- V01 PASS
- V02 INFO：LINE coverage = 86.0%（covered=4693 / total=5456）
- V03 PASS
- V04 PASS
- V05 PASS
- V06 PASS
- V07 PASS
- V08a PASS
- V08b SKIP（`SKIP_NATIVE=1` dev opt-out）

Verdict: all CRITICAL passed; exit=0.

S176 is ready for independent QA review. Production deploy/log retest remains the post-release verification step because this tick only ran local deterministic verification.

### QA Review（Independent QA — 2026-05-15）

Verdict: PASS

Checked documents:

- `docs/grimo/specs/2026-05-15-S176-explicit-publish-skill-name.md` full spec.
- `docs/grimo/development-standards.md` testing / naming / Spring Data JDBC aggregate rules.
- `docs/grimo/qa-strategy.md` verification registry and AC-to-test rule.

Checked implementation and tests:

- Backend production: `Skill.java`, `CreateSkillCommand.java`, `SkillCreatedEvent.java`, `SkillRepository.java`, `SkillCommandService.java`, `SkillCommandController.java`, `V25__drop_skill_name_unique.sql`.
- Backend tests: `SkillUploadExplicitNameTest`, `SkillPublishForgeryTest`, `SkillAggregateTest`, `SkillUploadAllowedToolsTest`, `SkillNameUniquenessMigrationTest`, `SkillRepositoryDuplicateNameTest`.
- Frontend: `PublishPage.tsx`, `PublishPage.test.tsx`, `frontend/src/api/skills.ts`.
- E2E: `e2e/tests/S176-explicit-publish-skill-name.spec.ts`.

Verification evidence:

```bash
grep -E "V0(1|3|4|5|6|7|8a|8b)|Summary|Verdict|LINE coverage" verify-all.log
```

Latest `verify-all.log` shows: V01 PASS, V03 PASS, V04 PASS, V05 PASS, V06 PASS, V07 PASS, V08a PASS, V08b SKIP because `SKIP_NATIVE=1`; LINE coverage = 86.0% (covered=4693 / total=5456); verdict says all CRITICAL passed and exit=0.

```bash
cd backend && ./gradlew test --tests "*SkillUploadExplicitNameTest" --tests "*SkillUploadAllowedToolsTest" --tests "*SkillPublishForgeryTest" --tests "*SkillNameUniquenessMigrationTest" --tests "*SkillRepositoryDuplicateNameTest" --tests "*SkillAggregateTest"
```

Result: `BUILD SUCCESSFUL in 2m 59s`. Note: this Gradle invocation also ran `jacocoTestReport` and the wider `test` task path before finishing green.

```bash
cd frontend && npm test -- PublishPage.test.tsx
```

Result: 1 file passed / 12 tests passed.

```bash
cd e2e && npx playwright test --grep @S176
```

Result: 1 passed. Backend logs during this run showed two `uploadSkill entered` / `技能上傳完成，已發佈首版` flows and `SearchProjection onSkillCreated ... name=Team Transcribe` for both duplicate publishes.

AC coverage:

| AC | Status | Evidence |
|---|---|---|
| AC-1 Publish form sends explicit `skillName` | VERIFIED | `PublishPage.test.tsx` checks FormData has `skillName="Team Transcribe"`, has no `author`, and still has file/version/category/visibility; E2E also fills browser field. |
| AC-2 Backend upload uses request `skillName`, not `SKILL.md name` | VERIFIED | `SkillUploadExplicitNameTest` asserts `skills.name` equals request display name while `skill_versions.frontmatter.name` keeps package name; E2E verifies assembled browser → HTTP → DB path. |
| AC-3 Duplicate platform skill names are allowed | VERIFIED | `SkillUploadExplicitNameTest` inserts two same-name skills; S176 Playwright publishes two `Team Transcribe` rows and verifies different ids from list API. |
| AC-4 Missing or invalid `skillName` is rejected before DB write | VERIFIED | `SkillUploadExplicitNameTest` covers null/blank/control-character with unchanged row count; `SkillPublishForgeryTest` covers missing multipart `skillName` 400 and no service call; `SkillAggregateTest` covers display-name uppercase acceptance and blank rejection. |
| AC-5 Add version no longer compares package name to platform name | VERIFIED | `SkillUploadAllowedToolsTest` publishes v1 with one package name, adds v1.1.0 with different package name, checks latestVersion and frontmatter. |
| AC-6 DB migration removes name uniqueness | VERIFIED | `V25__drop_skill_name_unique.sql`; `SkillNameUniquenessMigrationTest` checks `skills_name_key` absent and duplicate INSERT succeeds. |
| AC-7 Legacy author/name route is deterministic but not canonical | VERIFIED | `SkillRepository.findByAuthorAndName` orders by `created_at DESC, id DESC LIMIT 1`; `SkillRepositoryDuplicateNameTest` verifies newest duplicate is returned case-insensitively; repository Javadoc says ID route is canonical. |

Findings:

- IMPORTANT — `frontend/src/api/skills.ts` documented `fetchSkillByAuthorAndName` as "`/skills/:author/:name` 為 v2 canonical；`/skills/:id` 仍為永久 alias." S176 reverses this: ID route is canonical and author/name is a legacy alias. Resolved after QA by updating the comment to say author/name is a legacy alias and ID is canonical.
- MINOR — `SkillCommandController.uploadSkill` Javadoc said `skillName` is "平台顯示/路由用 skill name". Resolved after QA by updating the Javadoc to say `skillName` is the platform display name and canonical identity remains skill id.

Residual risk:

- Production deploy/log retest was not run in this QA pass. Local deterministic verification and S176 E2E passed; production evidence still needs the post-release check listed in §5.

### Final Size Re-score (per estimation-scale.md)

| Dimension | Initial | Actual | Rationale |
|---|---:|---:|---|
| Tech risk | 1 | 1 | Implementation used existing Spring MVC multipart, Spring Data JDBC aggregate validation, Flyway migration, React FormData, and Playwright patterns; no new SDK or framework SPI. |
| Uncertainty | 1 | 2 | User clarified mid-loop that platform `skillName` is arbitrary human display text, not agentskills.io package name; T07 corrected backend/frontend validation. |
| Dependencies | 2 | 3 | Work depended on S003/S004 publish flow, S032 version-name invariant, S154b author identity, S173 upload flow, and S177 ordering safety. |
| Scope | 2 | 3 | Actual change crossed backend command/domain/query docs, DB uniqueness behavior, frontend publish form/API comments, browser E2E, PRD/ADR sync, and roadmap/changelog/archive work. |
| Testing | 2 | 3 | Release required backend Testcontainers, frontend Vitest/coverage/typecheck, S176 browser E2E, happy-path E2E, and AOT smoke through `verify-all.sh`. |
| Reversibility | 1 | 2 | V25 removes `skills.name` uniqueness and upload API now requires explicit `skillName`; rollback would need coordinated frontend/backend/schema behavior. |
| **Total** | **9 / S** | **14 / M** | Bucket shifted S→M; root cause was the T07 display-name semantic correction plus route/documentation sync after duplicate names became valid. |
