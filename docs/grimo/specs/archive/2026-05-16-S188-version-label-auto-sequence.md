# S188: 版本標籤可自訂與自動流水號

> 規格：S188 | 大小：M(14) | 狀態：✅ Shipped v4.68.0
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

## 6. Task Plan

規劃日期：2026-05-17

本 spec 拆成四個 task，順序固定從後端 policy 到 API contract，再到前端表單，最後清掉顯示與文件中的 semver-only 假設。每個 task 都可單獨驗證；T01/T02 先讓 API 行為成立，T03 再讓 UI 不送空白 version，T04 做全域文案與完整驗證。

| Task | 狀態 | 對應 AC | 驗證重點 |
|---|---|---|---|
| [S188-T01 Backend VersionLabelPolicy](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/tasks/2026-05-17-S188-T01-backend-version-label-policy.md) | PASS | AC-S188-1, AC-S188-2, AC-S188-3, AC-S188-4 | `VersionLabelPolicyTest` 驗空白首版、下一號、自訂標籤與 unsafe label。 |
| [S188-T02 Backend Optional Version API](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/tasks/2026-05-17-S188-T02-backend-api-optional-version.md) | PASS | AC-S188-1, AC-S188-2, AC-S188-3, AC-S188-4 | `/upload` 與 `/{id}/versions` 不送 version 時能寫 DB / storage；duplicate 與 unsafe label 有正確錯誤。 |
| [S188-T03 Frontend Optional Version Forms](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/tasks/2026-05-17-S188-T03-frontend-optional-version.md) | PASS | AC-S188-5, AC-S188-6 | `/publish` 與新增版本表單 blank version 不 append `version`，也不被 required/pattern 擋住。 |
| [S188-T04 Version Label Display, Docs, and Full Verify](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/tasks/2026-05-17-S188-T04-display-docs-and-full-verify.md) | PASS | AC-S188-7 + full spec verify | UI / docs 不再用 semver-only 文案；跑 backend/frontend S188 相關驗證並整理 §7。 |

### 6.1 POC Decision

POC：not required。

原因：S188 不引入新 framework 或外部 SDK；核心未知數只有字串規則與既有 service ordering。`backend/src/main/resources/db/migration/V1__initial_schema.sql` 已確認 `version` 欄位長度 20，`SkillVersionRepository` 已有列出同 skill 版本與檢查 duplicate 的 method，前端也只是 FormData append 條件調整。風險可用 T01/T02 的單元與 service/controller 測試直接鎖住，不需要先做 throwaway POC。

### 6.2 Execution Notes

- T01 先只新增 policy + test，不碰 controller/service，避免一次改太大。
- T02 才改 API optional contract 與 `Skill.recordVersionPublished` semver 檢查。
- T03 若 S187 尚未建立 `SkillEditPage.tsx`，只改當前 `SkillDetailPage.tsx` 的 AddVersionForm；S187 實作時再沿用同一個 optional contract。
- T04 文件搜尋要避開 archived specs 的歷史敘述，只更新現行產品文件與 UI。

## 7. Implementation Results

### S188-T01 Backend VersionLabelPolicy — PASS（2026-05-17）

Files:
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/VersionLabelPolicy.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/VersionLabelPolicyTest.java`

Verification:
- RED：`cd backend && ./gradlew test --tests '*VersionLabelPolicyTest'` failed at `compileTestJava` because `VersionLabelPolicy` did not exist.
- GREEN：`cd backend && ./gradlew test --tests '*VersionLabelPolicyTest'` passed; Gradle printed `BUILD SUCCESSFUL in 2m 5s`.

Result:
- `initialOrRequested(null)` and blank string return `"1"`.
- `nextOrRequested(null, ["1", "2", "2026.05-hotfix"])` returns `"3"`.
- Custom labels such as `2026.05-hotfix`, `release-1`, and `0.1.0` are preserved after trimming.
- Unsafe labels such as `../prod`, labels with whitespace, pure numeric `"0"`, and labels longer than 20 characters throw `IllegalArgumentException` with the fixed API-facing English message.

Next:
- S188-T02 wires this policy into `SkillCommandController`, `SkillCommandService`, and `Skill.recordVersionPublished`.

### S188-T02 Backend Optional Version API — PASS（2026-05-17）

Files:
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/VersionLabelPolicy.java`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillUploadExplicitNameTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillCommandControllerSecurityTest.java`
- `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillPublishForgeryTest.java`

Verification:
- RED：`cd backend && ./gradlew test --tests '*SkillUploadExplicitNameTest' --tests '*SkillCommandControllerSecurityTest' --tests '*SkillPublishForgeryTest'` failed 6 AC-S188 tests because controller still required `version` and aggregate still rejected non-semver labels.
- GREEN：same command passed; Gradle printed `BUILD SUCCESSFUL in 2m 45s`.

Result:
- `POST /api/v1/skills/upload` accepts missing `version`; service resolves it to `"1"` and stores `skills/{skillId}/1/skill.zip`.
- `PUT /api/v1/skills/{id}/versions` accepts missing `version`; service resolves it to max existing numeric version + 1 while ignoring custom labels such as `2026.05-hotfix`.
- Custom labels such as `0.1.0` and `2026.05-hotfix` store unchanged; duplicate labels still throw `VersionExistsException` and HTTP 409 via existing controller handler.
- Unsafe labels such as `../prod` fail before a new `skill_versions` row is inserted.

Next:
- S188-T03 updates frontend forms so blank version is not appended to `FormData`.

### S188-T03 Frontend Optional Version Forms — PASS（2026-05-17）

Files:
- `frontend/src/api/skills.ts`
- `frontend/src/api/skills.test.ts`
- `frontend/src/pages/PublishPage.tsx`
- `frontend/src/pages/PublishPage.test.tsx`
- `frontend/src/pages/SkillDetailPage.tsx`
- `frontend/src/pages/SkillDetailPage.test.tsx`

Verification:
- RED：`cd frontend && npm test -- skills.test.ts PublishPage.test.tsx SkillDetailPage.test.tsx` failed because blank `version` was still appended to `FormData`, `/publish` still prefilled `1.0.0`, and the add-version form was still disabled when version was blank.
- GREEN：same command passed; Vitest printed `Test Files 3 passed` and `Tests 28 passed`.

Result:
- `/publish` now starts with a blank version field, has no browser `required` / semver `pattern`, and tells the user blank input lets the system generate a version.
- `uploadSkill(...)` omits `version` when the caller passes blank or whitespace; nonblank labels are trimmed before append.
- The detail page add-version form allows a selected file with blank version, and `addVersion(...)` omits `version` in that case.

Next:
- S188-T04 removes remaining semver-only display/doc assumptions and runs full S188 verification.

### S188-T04 Version Label Display, Docs, and Full Verify — PASS（2026-05-17）

Files:
- `docs/grimo/PRD.md`
- `docs/grimo/tasks/2026-05-17-S188-T04-display-docs-and-full-verify.md`
- `frontend/src/types/skill.ts`
- `frontend/src/pages/PublishPage.tsx`
- `frontend/src/pages/docs/VersioningPage.tsx`
- `frontend/src/pages/docs/FrontmatterPage.tsx`
- `frontend/src/pages/docs/UploadValidatePage.tsx`
- `frontend/src/pages/docs/RestApiPage.tsx`
- `frontend/src/pages/docs/EventPayloadPage.tsx`
- `frontend/src/pages/docs/YourFirstSkillPage.tsx`
- `frontend/src/components/SkillCard.test.tsx`
- `frontend/src/components/VersionList.test.tsx`
- `frontend/src/components/v2/PageHeader.test.tsx`
- `frontend/src/components/v2/tabs/VersionsTabV2.test.tsx`
- `frontend/src/pages/VersionDiffPage.test.tsx`
- `frontend/src/components/CreateCollectionModal.test.tsx`
- `frontend/src/pages/PublishValidatePage.test.tsx`
- `frontend/src/pages/PublishPage.test.tsx`

Verification:
- RED：`rg -n "semver|SemVer|MAJOR\\.MINOR\\.PATCH|1\\.0\\.0|1\\.1\\.0|格式.*版本|必填.*版本|version: 1\\.0\\.0|v1\\.0\\.0|v1\\.1\\.0" frontend/src docs/grimo/PRD.md docs/grimo/glossary.md docs/grimo/architecture.md docs/grimo/specs/2026-05-16-S188-version-label-auto-sequence.md docs/grimo/tasks/2026-05-17-S188-T04-display-docs-and-full-verify.md` found current docs and display tests still assuming semver examples or `1.0.0`.
- GREEN：`cd frontend && npm test -- SkillCard.test.tsx PageHeader.test.tsx VersionsTabV2.test.tsx VersionList.test.tsx VersionDiffPage.test.tsx CreateCollectionModal.test.tsx PublishValidatePage.test.tsx PublishPage.test.tsx` passed; Vitest printed `Test Files 8 passed` and `Tests 63 passed`.
- GREEN：`cd frontend && npm run typecheck` passed.
- GREEN：`cd backend && ./gradlew test --tests '*VersionLabel*' --tests '*SkillCommand*' --tests '*SkillUpload*'` passed; Gradle printed `BUILD SUCCESSFUL in 2m 25s`.
- GREEN：`cd frontend && npm test -- --run` passed; Vitest printed `Test Files 79 passed` and `Tests 450 passed`.
- GREEN：`rg -n "semver|SemVer|MAJOR\\.MINOR\\.PATCH|格式.*版本|必填.*版本|version: 1\\.0\\.0|v1\\.0\\.0|v1\\.1\\.0" frontend/src docs/grimo/PRD.md docs/grimo/glossary.md docs/grimo/architecture.md | rg -v "MVP v1\\.0\\.0|Phase 1 v1\\.1\\.0|PostgreSQL migration"` printed no matches.

Result:
- Current user-facing docs describe platform `version` as a Version Label, not a required SemVer value.
- UI display tests now cover numeric labels (`1`, `2`, `3`) and one custom label (`2026.05-hotfix`), so browse cards, detail header, Versions tab, diff page, collection modal, and download filename display do not assume semver parsing.
- S188 implementation tasks are complete.

### S188 Shipping Gate — PASS（2026-05-17）

Files:
- `e2e/tests/S140-critical-path-publish.spec.ts`

Verification:
- RED：`./scripts/verify-all.sh` failed at V07 because `S140-critical-path-publish.spec.ts` still expected `v1.0.0` after publish; the S188 runtime correctly created and displayed platform version label `v1`.
- GREEN：`cd e2e && npx playwright test --grep @happy-path` passed; Playwright printed `9 passed`.
- GREEN：`./scripts/verify-all.sh` passed after the E2E expectation update; script printed `V01=PASS V02=INFO V03=PASS V04=PASS V05=PASS V06=PASS V07=PASS V08a=PASS V08b=PASS` and `Verdict: ✅ all CRITICAL passed; exit=0`.

Result:
- The critical-path publish E2E now asserts the shipped S188 behavior: blank platform version input creates visible `v1`.

### Production Deploy / Recheck — PASS（2026-05-17）

Commands / evidence:
- `gcloud builds submit --config=cloudbuild.yaml --project=cfh-vibe-lab --substitutions=_REGION=asia-east1,_TAG=20260517-041711` created Cloud Build `f1f7da62-318a-419c-9266-8f387bf4eaa0`.
- Cloud Build pushed `asia-east1-docker.pkg.dev/cfh-vibe-lab/skillshub/skillshub:20260517-041711` with digest `sha256:8b3a5f39f5506228655c8c07c3f714972564657b7ee07b49beaa2a81f3cd6716`.
- `gcloud run services replace temp/service.rendered.yaml --region=asia-east1 --project=cfh-vibe-lab --quiet` created revision `skillshub-00038-252`; Cloud Run reports it Ready and serving 100% traffic.
- `curl -fsS https://skillshub-644359853825.asia-east1.run.app/actuator/health/readiness` returned `{"status":"UP"}`.
- `curl -fsS -o /tmp/skillshub-home.html -w '%{http_code} %{content_type} %{size_download}\n' https://skillshub-644359853825.asia-east1.run.app/` returned `200 text/html;charset=UTF-8 458`.
- `curl -fsS 'https://skillshub-644359853825.asia-east1.run.app/api/v1/skills?page=0&size=3'` returned HTTP 200 with an empty page shape: `content=[]`, `totalElements=0`.
- `gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="skillshub" AND resource.labels.revision_name="skillshub-00038-252" AND severity>=ERROR' --freshness=20m` returned no rows.

Limit:
- This Codex tick had no callable Chrome automation tool, so the production recheck did not claim authenticated UI clicks. The recorded evidence is HTTP/API + Cloud Run logs.

### Final Size Re-score (per estimation-scale.md)

| Dimension | Initial | Actual | Rationale |
|---|---:|---:|---|
| Tech risk | 1 | 2 | The core API was simple, but the old semver assumption also lived in backend validation, frontend forms, docs, display tests, and S140 E2E. |
| Uncertainty | 1 | 1 | Requirements stayed concrete: blank version creates `1` / next numeric label, custom safe labels remain allowed. |
| Dependencies | 1 | 3 | Final release depended on shipped upload/versioning behavior plus the S140 Playwright critical-path gate and Docker-backed local environment. |
| Scope | 2 | 3 | Implementation touched backend command/domain code, frontend API/forms/docs/display tests, PRD/glossary/architecture notes, and one critical-path E2E. |
| Testing | 2 | 3 | Verification required backend tests, frontend tests/typecheck, Playwright happy-path, processAot, and bootBuildImage via `verify-all.sh`. |
| Reversibility | 1 | 2 | The behavior changes a published API contract and existing platform version semantics, but no schema migration or data rewrite was needed. |
| **Total** | **8 / S** | **14 / M** | Bucket shift S→M; root cause: semver assumptions were wider than the initial backend/frontend form scope. |
