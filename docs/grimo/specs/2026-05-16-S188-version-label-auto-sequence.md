# S188: 版本標籤可自訂與自動流水號

> 規格：S188 | 大小：S(8) | 狀態：📐 in-design
> 日期：2026-05-16
> 對應：PRD P2 技能發佈流程 / S003 skill upload versioning / S056 version semver validation / S187 Skill SKILL.md 編輯頁
> 執行前置：S003/S004/S024/S056/S163/S176 已 ship；S187 是 ordering-only，S188 可先於 S187 實作，讓 edit page 直接沿用 optional version contract。

---

## 1. 目標

[backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java:369) 目前在 `recordVersionPublished(version)` 強制 `1.0.0` 這種 semver；[frontend/src/pages/PublishPage.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/PublishPage.tsx:193) 與 [SkillDetailPage.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/SkillDetailPage.tsx:263) 也把版本號 input 設為必填 semver。

S188 要改成：

```text
POST /api/v1/skills/upload
  version 有填 -> 使用作者輸入的版本標籤
  version 沒填或空白 -> 建立 skill_versions.version = "1"

PUT /api/v1/skills/{id}/versions
  version 有填 -> 使用作者輸入的版本標籤
  version 沒填或空白 -> 找該 skill 既有純數字版本最大值 + 1

例：
  既有版本：1, 2, 2026.05-hotfix
  不填 version 新增版本 -> 3
```

設計邊界：

| 項目 | S188 決定 |
|---|---|
| Domain term | `version` 欄位語意改名為「版本標籤 Version Label」；DB column / API field 仍叫 `version`，避免大改 schema 與 DTO。 |
| 自動流水號 | 只看同一個 skill 裡符合 `^[1-9][0-9]*$` 的版本標籤；下一號是 `max + 1`；沒有純數字版本時從 `"1"` 開始。 |
| 自訂版本 | 作者可輸入 `1.0.0`、`2026.05-hotfix`、`release-1` 等安全字元標籤；平台只保證同 skill 不重複，不再判斷 semver 大小。 |
| 安全字元 | 版本標籤會進 GCS path `skills/{skillId}/{version}/skill.zip`，所以只允許 ASCII 英數、`.`、`_`、`-`，長度 1-20；拒絕 `/`、`\`、空白、`..`。 |
| 舊資料 | 不做 migration；既有 `1.0.0` 繼續保留。下一次沒填 version 時，若沒有純數字版本，就建立 `"1"`。 |
| S187 關係 | S187 edit page 的版本 input 改成 optional，送出空白時不 append `version`；實際產號由 S188 backend 負責。 |

## 2. 研究與設計

### 2.1 現況掃描

| 來源 | 查到什麼 | 對 S188 的影響 |
|---|---|---|
| [PRD.md P2](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/PRD.md:96) | 目前例子寫首版 `v1.0.0`、更新版本 `v1.1.0` 或作者指定版本號。 | 這是產品行為變更；S188 會同步 PRD，改成「未填自動 1/2/3；有填用作者標籤」。 |
| [S003](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/specs/archive/2026-04-25-S003-skill-upload-versioning.md:32) | MVP 曾決定「版本號由 client 指定；不做 auto-increment semver」。 | S188 是新產品決策：保留 client 可指定，但補上未填自動流水號。 |
| [S056](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/specs/archive/2026-05-01-S056-version-semver-validation.md:15) | 後端強制 semver，當時是為了避免 `foo`、空白、超長字串進 DB 或 GCS path。 | S188 不能單純移除 regex；要用「版本標籤安全字元」取代 semver regex，保留空白/超長/path injection 防線。 |
| [V1 schema](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/resources/db/migration/V1__initial_schema.sql:64) | `skills.latest_version` 與 `skill_versions.version` 都是 `VARCHAR(20)`；`UNIQUE(skill_id, version)` 已存在。 | 不需要 migration；新規則必須維持 20 字元上限，繼續用 DB unique 兜底。 |
| [SkillCommandController.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java:95) | `/upload` 與 `/{id}/versions` 都用 `@RequestParam("version") String version`，缺欄位會被 Spring 擋掉。 | 改成 `@RequestParam(name = "version", required = false) String version`，blank 與 missing 都交給 service resolve。 |
| [SkillCommandService.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java:101) | service 先用 version 寫 aggregate、storage path、SkillVersion row；duplicate check 在 addVersion path。 | 產號必須發生在 `recordVersionPublished` 與 storage path 前；resolved version 是唯一往下傳的字串。 |
| [SkillVersionRepository.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersionRepository.java:40) | 已能列出某 skill 全版本，依 publishedAt 降序。 | 可先用既有 method 算 max numeric；若任務拆分時要更乾淨，可加 `findVersionsBySkillId` explicit query。 |
| [Agent Skills specification](https://agentskills.io/specification) | `SKILL.md` required frontmatter 只有 `name` / `description`；`metadata.version` 是 optional metadata 例子，不是標準必填欄位。 | 平台版本標籤不應強綁 SKILL.md frontmatter version；publish form 的 version 是平台 metadata。 |
| [Semantic Versioning 2.0.0](https://semver.org/) | SemVer 要求軟體先宣告 public API，版本格式是 `X.Y.Z`。 | Skills Hub 目前管理的是 skill package 發佈紀錄；作者可用 semver，但平台不應強制所有內部 skill 都有 public API 版號語意。 |

### 2.2 做法比較

| 做法 | 採用 | 實際行為 | 成本 / 風險 |
|---|---|---|---|
| A. 前端空白時塞 `1.0.0` / `1.1.0` | no | Browser submit 前自動補 semver。 | 只改 UI；API、curl、S187 text edit、seed endpoint 仍會遇到後端 required semver。 |
| B. 後端接受空白，但仍產 `1.0.0`、`1.1.0` | no | 不填時平台自動算 semver。 | 要解析 pre-release / patch bump 規則；使用者要的是舊系統從 `1` 開始流水號。 |
| C. 後端統一 resolve Version Label | yes | API 缺 version 時 service 產 `"1"`、`"2"`；有填時驗安全字元與唯一性後照存。 | 需更新後端 validator、frontend required/pattern、文件與測試；行為最集中。 |

### 2.3 版本標籤規則

```java
public final class VersionLabelPolicy {
    private static final Pattern SAFE_LABEL =
            Pattern.compile("^(?!0$)(?!.*\\.\\.)(?!.*[\\\\/\\s])[A-Za-z0-9._-]{1,20}$");
    private static final Pattern AUTO_LABEL =
            Pattern.compile("^[1-9][0-9]*$");

    public String initialOrRequested(@Nullable String requested) {
        return requested == null || requested.isBlank()
                ? "1"
                : validateRequested(requested);
    }

    public String nextOrRequested(@Nullable String requested, List<String> existingVersions) {
        if (requested != null && !requested.isBlank()) {
            return validateRequested(requested);
        }
        return String.valueOf(maxNumeric(existingVersions) + 1);
    }
}
```

驗證失敗的訊息固定用英文給前端轉譯：

```text
Version must be 1-20 characters and contain only letters, numbers, dot, underscore, or hyphen
```

`"0"` 不屬於自動流水號，但可不可以自訂？S188 決定不接受，因為使用者看到第一個系統版本是 `1`；`0` 會讓版本排序與「從 1 開始」不一致。若未來真的要 `0.1.0`，可填 semver `0.1.0`，它不是純數字 `"0"`。

### 2.4 UI 草圖

`/publish`：

```text
版本號（可留空）
[                    ]  未填時系統會建立版本 1；也可自訂如 1.0.0、2026.05-hotfix
```

`/skills/{id}/edit`（S187）：

```text
版本號（可留空）
[                    ]  未填時系統會建立下一個流水號，例如 3
```

送出行為：

```ts
const trimmed = version.trim()
if (trimmed.length > 0) {
  form.append('version', trimmed)
}
```

## 3. 驗收條件（SBE）

驗證命令：

執行：`cd backend && ./gradlew test --tests '*VersionLabel*' --tests '*SkillUpload*'`
通過條件：S188 後端測試全部綠燈。

執行：`cd frontend && npm test -- --run`
通過條件：PublishPage / SkillEditPage / API FormData 相關測試全部綠燈。

| AC | 優先級 | 驗證方式 | 標題 |
|---|---|---|---|
| AC-S188-1 | 必做 | Backend test | 新 skill 未填 version 時建立 `1` |
| AC-S188-2 | 必做 | Backend test | 既有 skill 未填 version 時建立下一個純數字流水號 |
| AC-S188-3 | 必做 | Backend test | 自訂版本標籤照存且不可重複 |
| AC-S188-4 | 必做 | Backend test | unsafe version label 回 400 且不寫 storage path |
| AC-S188-5 | 必做 | Frontend test | `/publish` 版本欄位可留空且送出時不 append version |
| AC-S188-6 | 必做 | Frontend test | `/skills/{id}/edit` 版本欄位可留空且送出時不 append version |
| AC-S188-7 | 必做 | Backend/Frontend test | UI 與 API 顯示版本 `v1` / `v2` 不假設 semver |
| AC-S188-8 | 建議 | Backend test | 並發空白版本只成功一筆，另一筆得到版本衝突 |

**AC-S188-1: 新 skill 未填 version 時建立 `1`**
- Given（前提）DB 沒有 `skills.name='docker-helper'`
- When（動作）Alice 發 `POST /api/v1/skills/upload`，multipart 有 `file`、`skillName='docker-helper'`、`category='DevOps'`，但沒有 `version`
- Then（結果）HTTP 201
- And（而且）DB 有 `skills.latest_version='1'`
- And（而且）DB 有 `skill_versions.version='1'`
- And（而且）storage path 是 `skills/{skillId}/1/skill.zip`

**AC-S188-2: 既有 skill 未填 version 時建立下一個純數字流水號**
- Given（前提）`skill-docker` 已有 `skill_versions.version` = `1`、`2`、`2026.05-hotfix`
- When（動作）Alice 發 `PUT /api/v1/skills/skill-docker/versions`，multipart 有 `file`，但沒有 `version`
- Then（結果）HTTP 200
- And（而且）DB 新增 `skill_versions.version='3'`
- And（而且）`skills.latest_version='3'`

**AC-S188-3: 自訂版本標籤照存且不可重複**
- Given（前提）`skill-docker` 已有 `skill_versions.version='1'`
- When（動作）Alice 發 `PUT /api/v1/skills/skill-docker/versions`，multipart `version='2026.05-hotfix'`
- Then（結果）HTTP 200，DB 新增 `skill_versions.version='2026.05-hotfix'`
- When（動作）Alice 再用同一個 `version='2026.05-hotfix'` 重送
- Then（結果）HTTP 409
- And（而且）既有 `2026.05-hotfix` row 不被覆寫

**AC-S188-4: unsafe version label 回 400 且不寫 storage path**
- Given（前提）`skill-docker` 已存在
- When（動作）Alice 發 `PUT /api/v1/skills/skill-docker/versions`，multipart `version='../prod'`
- Then（結果）HTTP 400
- And（而且）response message 是 `Version must be 1-20 characters and contain only letters, numbers, dot, underscore, or hyphen`
- And（而且）DB 沒有新增 `skill_versions` row
- And（而且）`storageService.upload(...)` 沒被呼叫

**AC-S188-5: `/publish` 版本欄位可留空且送出時不 append version**
- Given（前提）Alice 開啟 `/publish` 並填完 skill name / category / SKILL.md
- When（動作）Alice 留空「版本號」並點「發布技能」
- Then（結果）`uploadSkill(file, skillName, undefined, category, visibility)` 或等價 FormData 不包含 `version`
- And（而且）畫面不出現 browser required/pattern 錯誤

**AC-S188-6: `/skills/{id}/edit` 版本欄位可留空且送出時不 append version**
- Given（前提）Alice 在 `/skills/skill-docker/edit` 編輯 SKILL.md
- When（動作）Alice 留空「版本號」並點「儲存新版本」
- Then（結果）前端呼叫 `PUT /api/v1/skills/skill-docker/versions`
- And（而且）multipart 不包含 `version`
- And（而且）成功後照 S187 導到 `/publish/validate?id=skill-docker&mode=version`

**AC-S188-7: UI 與 API 顯示版本 `v1` / `v2` 不假設 semver**
- Given（前提）DB 有 `skills.latest_version='2'`，且 `skill_versions.version='1'`、`'2'`
- When（動作）使用者開啟 browse list、detail header、Versions tab、download link
- Then（結果）畫面可顯示 `v2` 與 `v1`
- And（而且）不出現「格式：MAJOR.MINOR.PATCH」或 semver-only hint

**AC-S188-8: 並發空白版本只成功一筆，另一筆得到版本衝突**
- Given（前提）`skill-docker` 已有純數字版本 `1`
- When（動作）兩個 request 同時對 `PUT /api/v1/skills/skill-docker/versions` 送空白 version
- Then（結果）其中一個成功建立 `2`
- And（而且）另一個得到 HTTP 409 或 DB unique conflict 被轉成 VERSION_EXISTS
- And（而且）DB 最後只有一筆 `version='2'`

### 非功能需求檢查

| 分類 | 對應驗收 | 說明 |
|---|---|---|
| Performance | AC-S188-2 | 計算下一號只讀同 skill 的版本；使用既有 `idx_skill_versions_skill_published` 可接受。若實作時版本數很多，可加 explicit `SELECT version` query。 |
| Security | AC-S188-4 | version 進 storage path 前先做安全字元驗證；拒絕 path separator、空白與 `..`。 |
| Reliability | AC-S188-3, AC-S188-8 | 同 skill 版本仍由 `UNIQUE(skill_id, version)` 兜底；並發空白產號不得覆寫既有 row。 |
| Usability | AC-S188-5, AC-S188-6, AC-S188-7 | 使用者可以不懂 semver；留空就用流水號，有需求再自訂。 |
| Maintainability | AC-S188-1, AC-S188-2 | 產號與驗證集中在 `VersionLabelPolicy`，controller/frontend 不自己猜下一號。 |

## 4. 介面與 API 設計

### 4.1 Backend API

`version` 從 required 改 optional：

```java
@PostMapping("/upload")
ResponseEntity<Map<String, String>> uploadSkill(
    @RequestParam("file") MultipartFile file,
    @RequestParam("skillName") String skillName,
    @RequestParam(name = "version", required = false) String version,
    @RequestParam("category") String category,
    ...
)

@PutMapping("/{id}/versions")
ResponseEntity<Void> addVersion(
    @PathVariable String id,
    @RequestParam("file") MultipartFile file,
    @RequestParam(name = "version", required = false) String version
)
```

### 4.2 Backend service

```java
@Service
public class SkillCommandService {
    private final VersionLabelPolicy versionLabelPolicy;

    public String uploadSkill(byte[] uploadedBytes, String skillName,
            @Nullable String requestedVersion, String author, String category, Visibility visibility,
            @Nullable String authorNameSnapshot) {
        var version = versionLabelPolicy.initialOrRequested(requestedVersion);
        ...
        skill.recordVersionPublished(version, authorNameSnapshot);
        var storagePath = "skills/" + skill.getId() + "/" + version + "/skill.zip";
        ...
    }

    public void addVersion(String skillId, byte[] uploadedBytes,
            @Nullable String requestedVersion, @Nullable String authorNameSnapshot) {
        var existing = skillVersionRepo.findBySkillIdOrderByPublishedAtDesc(skillId)
                .stream().map(SkillVersion::getVersion).toList();
        var version = versionLabelPolicy.nextOrRequested(requestedVersion, existing);
        if (skillVersionRepo.existsBySkillIdAndVersion(skillId, version)) {
            throw new VersionExistsException("Version " + version + " already exists");
        }
        ...
    }
}
```

`Skill.recordVersionPublished(version)` 改成只檢查：

```java
VersionLabelPolicy.validate(version);
```

source comment 最多保留：

```java
// S188: version is a safe Version Label, not semver-only.
```

### 4.3 Frontend API

```ts
export async function uploadSkill(
  file: File,
  skillName: string,
  version: string | undefined,
  category: string,
  visibility: Visibility = 'PUBLIC',
): Promise<{ id: string }> {
  const form = new FormData()
  form.append('skillName', skillName)
  form.append('file', file)
  if (version?.trim()) form.append('version', version.trim())
  form.append('category', category)
  form.append('visibility', visibility)
  ...
}

export async function addVersion(skillId: string, file: File, version?: string): Promise<void> {
  const form = new FormData()
  form.append('file', file)
  if (version?.trim()) form.append('version', version.trim())
  ...
}
```

Input 調整：

```tsx
<Input
  id="publish-version"
  value={version}
  onChange={(e) => setVersion(e.target.value)}
  placeholder="留空自動建立 1，也可填 1.0.0"
  maxLength={20}
  title="可留空；自訂時只能用英數、點、底線或連字號，最多 20 字元"
/>
```

## 5. 檔案規劃

| 檔案 | 動作 | 說明 |
|---|---|---|
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java` | modify | `/upload` 與 `/{id}/versions` 的 `version` request param 改 optional。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java` | modify | 在 publish path 一開始 resolve version label；resolved version 往 aggregate / storage path / SkillVersion row 傳遞。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/VersionLabelPolicy.java` | new | 集中處理 blank -> auto sequence、自訂標籤安全驗證、max numeric 計算。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` | modify | 移除 S056 semver regex，改呼叫安全 version label 驗證或保留同等邏輯。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVersionPublishedEvent.java` 等 event docs | modify | JavaDoc 從 SemVer 改成 Version Label。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/command/VersionLabelPolicyTest.java` | new | 覆蓋 AC-S188-1/2/3/4 的純邏輯。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillUploadTest.java` / integration tests | modify | 覆蓋 optional request param、DB row、storage path、duplicate/conflict。 |
| `frontend/src/api/skills.ts` | modify | `uploadSkill` / `addVersion` 的 `version` 改 optional；blank 不 append FormData。 |
| `frontend/src/pages/PublishPage.tsx` | modify | version input 移除 `required` 與 semver pattern；更新 hint。 |
| `frontend/src/pages/SkillDetailPage.tsx` | modify | 若 S187 尚未落地，既有 `AddVersionForm` 同步移除 required/pattern；若 S187 已落地，此 form 已刪除。 |
| `frontend/src/pages/SkillEditPage.tsx` | modify/new | S187 edit page 的 version input optional，blank 交給 backend。 |
| `frontend/src/pages/docs/VersioningPage.tsx` / `FrontmatterPage.tsx` / `UploadValidatePage.tsx` | modify | 使用者文件從 semver-only 改成版本標籤與自動流水號。 |
| `docs/grimo/PRD.md` | modify | P2 SBE 改成首版未填 version 建立 `v1`；更新時未填建立下一個流水號。 |
| `docs/grimo/glossary.md` | modify | `版本` 定義改成 Version Label，不再寫 semver-only。 |
| `docs/grimo/architecture.md` | modify | read model 欄位說明從 latest semver 改成 latest version label。 |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
