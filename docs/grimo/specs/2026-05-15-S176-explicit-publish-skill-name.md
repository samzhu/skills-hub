# S176: Explicit Publish Skill Name（發佈頁手填平台 skill name + 允許重名）

> Spec: S176 | Size: S(7) | Status: 📐 in-design
> Date: 2026-05-15
> Depends: S003 ✅, S004 ✅, S032 ✅（本 spec 修改其 invariant）, S154b ✅, S173 ✅
> Trigger: production upload after S175 deploy — 使用者重新上傳 skill 時，Cloud Run log 出現 `skills.name` unique constraint 409；使用者要求「skill name 不做重複檢查，加一個欄位是 skill name，不是直接拿 zip 檔名稱當 skill name」。

---

## 1. Goal

`frontend/src/pages/PublishPage.tsx` 發佈表單要多一個「技能名稱」欄位；`POST /api/v1/skills/upload` 要收 `skillName` multipart form field，並把它寫進 `skills.name`。同名 skill 可以同時存在，DB 不再用 `skills.name UNIQUE` 擋住第二筆。

現在實體行為是：

- `SkillCommandService.uploadSkill()` 讀 `validation.metadata().get("name")`，把 `SKILL.md` frontmatter `name` 寫成平台 `skills.name`。
- `V1__initial_schema.sql` 建 `skills.name VARCHAR(64) NOT NULL UNIQUE`，正式站同名上傳會被 PostgreSQL 擋掉，Cloud Run log 看到 `Key (name)=(transcribe-video) already exists.`。
- `SkillCommandService.addVersion()` 仍有 S032 guard：新版本 zip 內 `SKILL.md name` 必須等於 `skill.getName()`。

S176 的目標是把「平台上的 skill name」和「包裡的 agentskills.io `SKILL.md name`」拆開：

| 欄位 | 來源 | 寫到哪裡 | 行為 |
|---|---|---|---|
| 平台 skill name | Publish form `skillName` | `skills.name` | 顯示、搜尋、下載檔名 fallback；允許重名；仍用小寫連字號格式 |
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
| [agentskills.io Specification](https://agentskills.io/specification) | `SKILL.md` 必須有 YAML frontmatter；`name` 必填，長度 1-64，只能小寫英數與 hyphen，不能頭尾 hyphen 或連續 hyphen。 | S176 不取消包內 `name` 驗證；只是不再拿它當平台 `skills.name`。 |
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

`Skill.create(CreateSkillCommand)` 已經會驗 `name` 不可空、trim 後符合既有 `NAME_REGEX`，所以 S176 不新增第二套 backend name validator；service 只要把 request `skillName` 傳進 `CreateSkillCommand`，aggregate 會做同一套格式檢查。

`SkillRepository.findByName(String name)` 的 Javadoc 目前寫死「schema 層 `skills.name UNIQUE` 保證最多一筆」。S176 移除 unique 後，這個 method contract 不再成立；如果 production 沒有 caller，就應移除。`findByAuthorAndName(author, name)` 也不再保證唯一，需明確標成 legacy alias，或至少加 deterministic ordering，避免 `LIMIT 1` 隨資料頁面改變回傳不同 row。

### 2.3 Options

| Option | 改哪些實體 | 使用者按下發佈後會看到什麼 | 成本 / 風險 |
|---|---|---|---|
| A. **Reuse `skills.name` as explicit platform name**（推薦） | `PublishPage` 加 `skillName`；upload API 加 multipart field；service 用 `skillName` 建 aggregate；V25 drop `skills_name_key`；S032 version guard 移除。 | 使用者輸入 `transcribe-video`，即使 DB 已有另一筆同名，仍回 201；列表會看到兩筆同名但不同 id/作者/時間。 | 最小 schema 改動；但 `/skills/:author/:name` 不再唯一，需把 ID route 視為 canonical。 |
| B. 新增 `display_name` 欄位，保留 `skills.name` 為包名 | V25 add `display_name`；所有 list/detail/search/download fallback 改讀 display_name；原 `name UNIQUE` 還要另外解除或改語意。 | UI 可顯示新名稱，但很多既有 API JSON 的 `name` 仍會混淆；舊 frontend/test 全面要搬欄位。 | 改動面大，且沒有解掉 user 要「skill name 欄位」對目前 API 的直覺。 |
| C. 新增不可重複 `slug`，`name` 改純 display | DB add `slug UNIQUE` + `name` non-unique；route 從 author/name 轉 author/slug 或 id；publish 要處理 slug auto-generate。 | 可同名，又有 stable public route。 | 超過本輪需求；需要 rename/route migration，適合未來 marketplace URL polish。 |

選 A。理由很直接：目前 `skills.name` 已經是所有 list/detail/search/download UI 顯示的欄位；把它改成「使用者輸入的平台 skill name」能用最少檔案讓正式站不再因同名 409。

### 2.4 Revised Invariants

1. `skills.id` 是唯一身份；`skills.name` 不是唯一身份。
2. `skills.name` 仍是 required、1-64、小寫連字號格式，沿用 `Skill.create()` 的 aggregate validation。
3. `skill_versions.frontmatter.name` 仍要符合 agentskills.io；但它只描述套件 metadata，不再要求等於 `skills.name`。
4. `POST /api/v1/skills/upload` 少 `skillName` 時回 400；不能 fallback 到 `SKILL.md name`，否則正式站仍會把包名當平台名。
5. 同一作者可以發佈兩筆同名 skill；列表/detail by id 正常，`author/name` route 是 legacy alias，不承諾選到哪一筆以外的唯一語意。

### 2.5 Low-Fidelity UI Sketch

```text
發佈新技能

[ 上傳檔案 | 貼上文本 ]

技能名稱
[ transcribe-video                         ]
  小寫英數與 hyphen，最多 64 字元。這是平台列表顯示名稱。

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

Text mode 的 live validation 仍檢查 `SKILL.md` frontmatter `name`。如果使用者輸入平台 `skillName=team-transcribe`，textarea 內 `name: transcribe-video`，兩者可以不同，送出後 DB `skills.name=team-transcribe`，`skill_versions.frontmatter.name=transcribe-video`。

---

## 3. SBE Acceptance Criteria

### AC-1: Publish form sends explicit skillName

```gherkin
Given 使用者在 PublishPage 貼上 SKILL.md，frontmatter name="internal-package-name"
And   技能名稱欄位輸入 "platform-skill"
When  點擊「發佈技能」
Then  前端送出的 multipart FormData 包含 skillName="platform-skill"
And   FormData 不包含 author
And   FormData 仍包含 file/version/category/visibility
```

### AC-2: Backend upload uses request skillName, not SKILL.md name

```gherkin
Given multipart request 有 skillName="platform-skill"
And   zip 內 SKILL.md frontmatter name="internal-package-name"
When  POST /api/v1/skills/upload
Then  HTTP 201
And   skills.name = "platform-skill"
And   skill_versions.frontmatter->>'name' = "internal-package-name"
```

### AC-3: Duplicate platform skill names are allowed

```gherkin
Given DB 已有一筆 skills.name="transcribe-video"
When  同一使用者再次 POST /api/v1/skills/upload with skillName="transcribe-video"
Then  HTTP 201
And   DB 有兩筆 skills.name="transcribe-video"
And   兩筆 id 不同
```

### AC-4: Missing or invalid skillName is rejected before DB write

```gherkin
Given multipart request 少 skillName
When  POST /api/v1/skills/upload
Then  HTTP 400
And   DB 不新增 skills row

Given multipart request skillName="Bad Name!"
When  POST /api/v1/skills/upload
Then  HTTP 400
And   message 說明 name 格式不合法
And   DB 不新增 skills row
```

### AC-5: Add version no longer compares SKILL.md name to platform name

```gherkin
Given skill A 的 platform name 是 "platform-skill"
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

skillName=platform-skill
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
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillRepository.java` | 移除未使用 `findByName`，或改成 `List<Skill> findAllByName`；更新 `findByAuthorAndName` Javadoc，必要時 SQL 加 `ORDER BY created_at DESC, id DESC LIMIT 1` 讓 legacy alias deterministic。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/testsupport/TestDataController.java` | seed path 傳入 explicit platform `req.name()`；合成 SKILL.md 時仍可用同一 name 當包名。 |
| `backend/src/main/resources/db/migration/V25__drop_skill_name_unique.sql` | drop `skills_name_key`。 |
| `frontend/src/api/skills.ts` | `uploadSkill(file, skillName, version, category, visibility)` 並 append `skillName`。 |
| `frontend/src/pages/PublishPage.tsx` | 新增「技能名稱」Input；送出前 required；FormData 使用使用者輸入名稱。 |

### Tests

| File | BDD coverage |
|---|---|
| `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillCommandServiceTest.java` 或新 `SkillUploadExplicitNameTest.java` | AC-2, AC-3, AC-4, AC-5：explicit `skillName`、duplicate names、invalid name、version mismatch allowed。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillPublishForgeryTest.java` | Controller verify 新 service args；仍證明 author 由 server 取，不用 request author。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/testsupport/TestDataControllerTest.java` | Seed endpoint 改 call 新 signature。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillUploadAllowedToolsTest.java` | 移除舊 AC-S032 fail expectation；加新 AC-S176-5 pass expectation 或搬到新 test。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillMigrationTest.java` 或現有 migration test 類 | AC-6：constraint absent + duplicate insert succeeds。 |
| `frontend/src/pages/PublishPage.test.tsx` | AC-1：填技能名稱後 FormData 有 `skillName`，不含 author；缺 skillName 時 button disabled。 |
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
