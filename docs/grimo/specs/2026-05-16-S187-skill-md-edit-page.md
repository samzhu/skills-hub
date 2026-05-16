# S187: Skill SKILL.md 編輯頁

> 規格：S187 | 大小：M(13) | 狀態：📐 in-design
> 日期：2026-05-16
> 對應：PRD P2 更新已有 skill 版本 / P1 技能詳情頁 / S142a SkillDetailPage v2 / S163 EditSkillModal / S176 explicit publish skill name / S186 embedding source
> 執行前置：S186 必須先 ship；S187 不在 S186 前啟動 task loop。

---

## 1. 目標

把 skill 詳情頁的「編輯」從小 modal 改成完整頁面，讓作者可以用跟 `/publish` 類似的體驗更新既有 skill 的 SKILL.md。

現在 [frontend/src/pages/SkillDetailPage.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/SkillDetailPage.tsx:134) 的編輯按鈕打開 [EditSkillModal.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/components/EditSkillModal.tsx:21)，modal 只提供 `description/category` 小欄位；版本頁籤又在同頁塞 [AddVersionForm](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/SkillDetailPage.tsx:231)。使用者實際看到的是：想改 SKILL.md 內容時，詳情頁編輯區太小；版本頁籤也同時承擔「看紀錄」與「上傳新版本」兩件事。

S187 改成：

```text
Skill detail /skills/{id}
  Edit button -> /skills/{id}/edit

Skill edit page /skills/{id}/edit
  - 上傳檔案：zip 或 SKILL.md
  - 貼上文本：載入 latest SKILL.md 後直接編輯
  - 填新版本號
  - 儲存後呼叫 PUT /api/v1/skills/{id}/versions
  - 成功後回 /skills/{id}

Skill detail Versions tab
  - 只顯示 Version History
  - 不放 upload form
```

設計邊界：

| 項目 | S187 決定 |
|---|---|
| `skills.description` | 是 latest SKILL.md frontmatter `description` 的 Skill Description Snapshot；新版本 publish 後更新，不提供直接編輯表單。 |
| `skills.name` | 是平台顯示名稱；S187 不改。`SKILL.md` frontmatter `name` 保存在 version metadata。 |
| `category` | 暫不放進 SKILL.md edit page；既有 category metadata 編輯若仍需要，另開 metadata spec。 |
| 版本語意 | 編輯 SKILL.md 一律建立新版本；不覆寫既有版本。 |
| S186 關係 | S186 先修 semantic search / vector projection 問題並 ship；S187 才開始實作 edit page 與 `skills.description` snapshot 寫入。兩者不互相 import production type，但執行順序是 S186 → S187。 |

## 2. 研究與設計

### 2.1 現況掃描

| 來源 | 查到什麼 | 對 S187 的影響 |
|---|---|---|
| [PRD.md P2](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/PRD.md:94) | 作者更新已有 skill 時，上傳新版本，舊版本仍可下載，詳情頁顯示完整版本歷史。 | S187 的「編輯」不應直接覆蓋舊內容；要建立新版本。 |
| [PublishPage.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/PublishPage.tsx:30) | `/publish` 已有「上傳檔案 / 貼上文本」雙模式，text mode 會把 textarea 包成 `File([text], 'SKILL.md')`。 | edit page 沿用這個互動模型與 `validateFrontmatter`，不用重造一套。 |
| [SkillDetailPage.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/SkillDetailPage.tsx:190) | Version tab 目前同時 render `VersionsTabV2` 與 `AddVersionForm`。 | S187 要移除 tab 內新增版本表單，只保留紀錄。 |
| [EditSkillModal.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/components/EditSkillModal.tsx:69) | modal 內 description textarea 只有 4 rows，且直接呼叫 `PUT /skills/{id}` 改 `skills.description`。 | 不符合「description 來自 latest SKILL.md」；S187 要停止前端直接改 description。 |
| [skills.ts](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/api/skills.ts:87) | `updateSkill` 目前傳 `description/category`；`addVersion` 已存在並走 multipart `file + version`。 | edit page 優先 reuse `addVersion`；`updateSkill` 要收斂或停用 description。 |
| [SkillCommandService.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java:161) | `addVersion` 會驗證 SKILL.md、存 zip、建立 `SkillVersion`，但沒有把 frontmatter `description` 回寫 `skills.description`。 | S187 要補上 snapshot update，讓 detail/list/search 顯示最新描述。 |
| [SkillCommandController.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java:144) | `PUT /api/v1/skills/{id}/versions` 已要求 write permission。 | edit page 不需要新增權限模型；沿用既有 write permission。 |
| [Skill.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java:387) | `Skill.update(UpdateSkillCommand)` 目前允許 description/category 直接 mutation。 | description 要改成只能由 version publish path 更新；category 是否保留要另行定義。 |
| [Agent Skills specification](https://agentskills.io/specification) | `SKILL.md` 必須有 YAML frontmatter，`name` 與 `description` 是必填；`description` 上限 1024 字元且用來說明技能做什麼與何時使用。 | edit page 的文字模式要沿用 name/description frontmatter 驗證；backend 仍以 `SkillValidator` 作最後判斷。 |
| [React Router useNavigate](https://reactrouter.com/api/hooks/useNavigate) | `useNavigate()` 可在按鈕或成功 callback 後導向指定 path。 | detail edit button 可導向 `/skills/${id}/edit`；submit success 可導回 `/skills/${id}`。 |

### 2.2 做法比較

| 做法 | 採用 | 實際行為 | 成本 / 風險 |
|---|---|---|---|
| A. 放大 `EditSkillModal` | no | 點編輯仍留在詳情頁 modal，textarea 變大。 | 低成本，但仍會讓使用者以為可以直接改 `skills.description`；版本紀錄與上傳流程仍混在一起。 |
| B. 版本頁籤保留新增版本表單，只加 text mode | no | 在版本 tab 內同時看歷史、上傳 zip、貼 SKILL.md。 | 中成本，但 tab 名稱「版本」不再是單純 Version History，頁面密度變高。 |
| C. 新增 `/skills/{id}/edit` 完整頁面 | yes | 詳情頁編輯按鈕導向 edit page；edit page 支援 zip / text；保存建立新版本；版本 tab 只看紀錄。 | 中成本；需新增 route/page/test，並收斂 direct description update。 |

### 2.3 UI 草圖

詳情頁：

```text
/skills/{id}

[返回列表]

[Skill Hero: name / description / badges / actions]
  Actions: [下載] [分享] [編輯] [公開/私人]
                         |
                         v
                 /skills/{id}/edit

Tabs:
  [SKILL.md] [品質] [版本] [評論] [安全性] [旗標] [檔案]

版本 tab:
  +---------------------------------------------+
  | Version History                             |
  | v2.0.0  2026-05-16  12 files  [下載] [diff] |
  | v1.0.0  2026-05-10  10 files  [下載] [diff] |
  +---------------------------------------------+
  // no upload form here
```

編輯頁：

```text
/skills/{id}/edit

[返回技能]

編輯 SKILL.md                         目前版本: 1.0.0
docker-compose-helper                 可見性/分享設定留在詳情頁操作

[上傳檔案] [貼上文本]

上傳檔案 mode:
  +---------------------------------------------+
  | drop zip 或 SKILL.md                         |
  +---------------------------------------------+

貼上文本 mode:
  +------------------------------+--------------+
  | SKILL.md editor              | Preview      |
  | ---                          | H1 / body    |
  | name: docker-helper          |              |
  | description: Compose helper  |              |
  | ---                          |              |
  | ...                          |              |
  +------------------------------+--------------+

版本號 [ 1.1.0          ]

frontmatter check:
  [pass] name
  [pass] description

[取消] [儲存新版本]
```

手機版：

```text
/skills/{id}/edit

[返回技能]
編輯 SKILL.md

[上傳檔案] [貼上文本]

[textarea full width]
[展開預覽]

版本號 [      ]
[儲存新版本]
```

### 2.4 架構設計

前端新增 route：

```tsx
<Route path="/skills/:id/edit" element={<SkillEditPage />} />
```

`SkillDetailPage` 不再維護 `editOpen` state：

```tsx
const navigate = useNavigate()

<PageHeader
  ...
  onEditClick={() => navigate(`/skills/${skill.id}/edit`)}
/>
```

`SkillEditPage` 資料流：

```text
load:
  useSkill(id)             -> 顯示 skill name / current latestVersion
  useSkillFile(id, 'SKILL.md') -> text mode 預填 latest SKILL.md

submit file mode:
  selected File + version -> addVersion(id, file, version)

submit text mode:
  new File([skillMdText], 'SKILL.md', { type: 'text/markdown' })
    -> addVersion(id, syntheticFile, version)

success:
  invalidate ['skills', id], ['skills', id, 'versions'], skill file query
  navigate(`/skills/${id}`)
```

後端版本 publish path 要維護 Skill Description Snapshot：

```java
var description = (String) validation.metadata().get("description");
var skill = skillRepo.findById(skillId).orElseThrow(...);
skill.recordVersionPublished(version, authorNameSnapshot);
skill.refreshDescriptionSnapshot(description, currentUserId);
skillRepo.save(skill);
```

`refreshDescriptionSnapshot` 是 domain method，理由是 `skills.description` 仍是 `Skill` row 的 user-visible read state；但它的來源只能是已通過 `SkillValidator` 的 latest SKILL.md frontmatter。

`PUT /api/v1/skills/{id}` 的 direct description mutation 要收斂：

| API body | S187 後行為 |
|---|---|
| `{ "description": "..." }` | 400，錯誤訊息：`description must be updated by publishing a SKILL.md version` |
| `{ "category": "DevOps" }` | 可先保留 category metadata 更新，因為 S187 不重設 category 產品決策。 |
| `{ "description": "...", "category": "DevOps" }` | 400，不局部套用 category，避免 caller 以為 description 有成功。 |

### 2.5 Task 邊界提示

| Task 候選 | Class / file | 正向情境 | 反向情境 | POC |
|---|---|---|---|---|
| T01 frontend route + detail navigation | `App.tsx`, `SkillDetailPage.tsx`, `SkillDetailPage.test.tsx` | owner 點「編輯」進 `/skills/{id}/edit` | Version tab 不再出現「新增版本」form | not required |
| T02 edit page shell + text mode | `SkillEditPage.tsx`, `SkillEditPage.test.tsx` | text mode 預填 latest SKILL.md，textarea 足夠大並有 frontmatter check | 缺 description 時 submit disabled | not required |
| T03 upload/text submit reuse addVersion | `SkillEditPage.tsx`, `skills.ts` if needed | zip 或 pasted text 都呼叫 `addVersion(id,file,version)` | duplicate version 顯示 409 localize error，不導回 detail | not required |
| T04 backend description snapshot | `Skill.java`, `SkillCommandService.java`, backend tests | 新版本 frontmatter description 寫回 `skills.description` | invalid SKILL.md 不改 description | not required |
| T05 direct description update 收斂 | `UpdateSkillCommand.java`, `SkillCommandController.java`, tests, `EditSkillModal` delete | `PUT /skills/{id}` 傳 description 回 400 | category-only 仍可成功或明確移出 API | not required |
| T06 docs / S186 sync | specs, docs pages if API docs mention metadata update | docs 說明 description 來自 latest SKILL.md | docs 不再說 modal 可直接改 description | not required |

## 3. 驗收條件（SBE）

驗證命令：

執行：`cd frontend && npm test`
通過條件：所有帶 `S187` / `AC-S187-*` 的 frontend 測試都是綠燈。

執行：`cd backend && ./gradlew test`
通過條件：所有帶 `S187` / `AC-S187-*` 的 backend 測試都是綠燈。

| AC | 優先級 | 驗證方式 | 標題 |
|---|---|---|---|
| AC-S187-1 | 必做 | Frontend test | 詳情頁編輯按鈕導向 edit page |
| AC-S187-2 | 必做 | Frontend test | 版本頁籤只顯示 Version History |
| AC-S187-3 | 必做 | Frontend test | edit page 貼上文本 mode 預填 latest SKILL.md |
| AC-S187-4 | 必做 | Frontend test | edit page 上傳檔案 mode 建立新版本 |
| AC-S187-5 | 必做 | Backend test | 新版本 publish 更新 `skills.description` snapshot |
| AC-S187-6 | 必做 | Backend test | direct description update 被拒絕 |
| AC-S187-7 | 必做 | Backend/Frontend test | duplicate version 不覆寫舊版本 |
| AC-S187-8 | 建議 | Frontend test | 編輯頁手機版不重疊且主要操作可見 |

**AC-S187-1: 詳情頁編輯按鈕導向 edit page**
- Given（前提）Alice 對 `skill-docker` 有 write permission，並開啟 `/skills/skill-docker`
- When（動作）Alice 點 PageHeader 的「編輯」
- Then（結果）瀏覽器 URL 變成 `/skills/skill-docker/edit`
- And（而且）頁面不打開 `EditSkillModal`

**AC-S187-2: 版本頁籤只顯示 Version History**
- Given（前提）Alice 開啟 `/skills/skill-docker` 且有 write permission
- When（動作）Alice 點「版本」tab
- Then（結果）畫面顯示 `VersionsTabV2` 的版本紀錄
- And（而且）畫面沒有「新增版本」、file dropzone、版本號 input、上傳按鈕

**AC-S187-3: edit page 貼上文本 mode 預填 latest SKILL.md**
- Given（前提）`GET /api/v1/skills/skill-docker/files/SKILL.md` 回傳 latest SKILL.md 文字
- When（動作）Alice 開啟 `/skills/skill-docker/edit` 並選「貼上文本」
- Then（結果）textarea 內已有 latest SKILL.md
- And（而且）frontmatter check 顯示 `name` 與 `description` 通過
- And（而且）刪掉 `description:` 後「儲存新版本」disabled

**AC-S187-4: edit page 上傳檔案 mode 建立新版本**
- Given（前提）Alice 在 `/skills/skill-docker/edit` 選「上傳檔案」
- When（動作）Alice 選 `skill.zip`、輸入版本 `1.1.0`、點「儲存新版本」
- Then（結果）前端呼叫 `PUT /api/v1/skills/skill-docker/versions`，multipart 內有 `file=skill.zip` 與 `version=1.1.0`
- And（而且）成功後導回 `/skills/skill-docker`

**AC-S187-5: 新版本 publish 更新 `skills.description` snapshot**
- Given（前提）DB 有 `skills.id='skill-docker'` 且 `description='Old desc'`
- When（動作）Alice 上傳新版本，SKILL.md frontmatter 有 `description='Compose deploy helper'`
- Then（結果）`skills.description='Compose deploy helper'`
- And（而且）`skill_versions.frontmatter.description='Compose deploy helper'`
- And（而且）list/detail API 下一次讀取都顯示新 description

**AC-S187-6: direct description update 被拒絕**
- Given（前提）DB 有 `skills.id='skill-docker'` 且 `description='Old desc'`
- When（動作）caller 發 `PUT /api/v1/skills/skill-docker` body `{"description":"Manual desc"}`
- Then（結果）HTTP 400
- And（而且）response error message 是 `description must be updated by publishing a SKILL.md version`
- And（而且）DB 的 `skills.description` 仍是 `Old desc`

**AC-S187-7: duplicate version 不覆寫舊版本**
- Given（前提）`skill-docker` 已有 version `1.1.0`
- When（動作）Alice 在 edit page 再送 version `1.1.0`
- Then（結果）後端回 HTTP 409
- And（而且）前端停留在 edit page 顯示錯誤
- And（而且）`skills.description` 與舊 `skill_versions` row 不變

**AC-S187-8: 編輯頁手機版不重疊且主要操作可見**
- Given（前提）viewport 寬度 390px
- When（動作）Alice 開啟 `/skills/skill-docker/edit`
- Then（結果）mode tabs、textarea、version input、「儲存新版本」按鈕在單欄 layout 中依序顯示
- And（而且）textarea / preview / button text 不互相覆蓋

### 非功能需求檢查

| 分類 | 對應驗收 | 說明 |
|---|---|---|
| Performance | AC-S187-3 | edit page 只在需要編輯文本時讀 latest SKILL.md；detail/list 仍讀 `skills.description` snapshot。 |
| Security | AC-S187-1, AC-S187-4 | 新版本仍走既有 write permission；不新增前端自製授權判斷。 |
| Reliability | AC-S187-5, AC-S187-7 | 新版本成功才更新 snapshot；版本重複不覆寫舊資料。 |
| Usability | AC-S187-2, AC-S187-3, AC-S187-8 | 詳情頁版本 tab 變單純；編輯頁有完整 textarea 與手機版 layout。 |
| Maintainability | AC-S187-6 | description 只有一條寫入來源：通過驗證的 latest SKILL.md frontmatter。 |

## 4. 介面與 API 設計

### 4.1 Frontend route

```tsx
// frontend/src/App.tsx
<Route path="/skills/:id/edit" element={<SkillEditPage />} />
```

### 4.2 Frontend API

沿用既有 `addVersion`：

```ts
export function addVersion(id: string, file: File, version: string): Promise<void>
```

文字模式不新增 JSON endpoint：

```ts
const file = new File([skillMdText], 'SKILL.md', { type: 'text/markdown' })
await addVersion(id, file, version)
```

### 4.3 Backend API

沿用：

```http
PUT /api/v1/skills/{id}/versions
Content-Type: multipart/form-data

file=<zip or SKILL.md>
version=1.1.0
```

調整：

```http
PUT /api/v1/skills/{id}
Content-Type: application/json

{"description":"Manual desc"}
```

回：

```json
{
  "code": "BAD_REQUEST",
  "message": "description must be updated by publishing a SKILL.md version"
}
```

### 4.4 Domain method

```java
public void refreshDescriptionSnapshot(String description, String updatedBy) {
    if (description == null || description.isBlank()) {
        throw new IllegalArgumentException("Skill description must not be blank");
    }
    if (description.length() > DESCRIPTION_MAX) {
        throw new IllegalArgumentException("Skill description exceeds " + DESCRIPTION_MAX + " characters");
    }
    if (description.equals(this.description)) {
        return;
    }
    this.description = description;
    this.updatedAt = Instant.now();
    registerEvent(new SkillUpdatedEvent(id, this.description, this.category, updatedBy, this.updatedAt));
}
```

這個 method 只能由 `SkillCommandService.addVersion(...)` 在 SKILL.md validation 通過後呼叫。`Skill.update(UpdateSkillCommand)` 不再處理 `description`。

## 5. 檔案規劃

| 檔案 | 動作 | 說明 |
|---|---|---|
| `frontend/src/App.tsx` | modify | 新增 `/skills/:id/edit` route。 |
| `frontend/src/pages/SkillDetailPage.tsx` | modify | 移除 `EditSkillModal` state/import；edit button 導向 edit page；Version tab 移除 `AddVersionForm`。 |
| `frontend/src/pages/SkillEditPage.tsx` | new | 完整 SKILL.md edit page；支援 upload/text mode、version input、validation、submit、成功導回 detail。 |
| `frontend/src/pages/SkillEditPage.test.tsx` | new | 覆蓋 AC-S187-1/3/4/7/8 的頁面行為。 |
| `frontend/src/pages/SkillDetailPage.test.tsx` | modify | 驗證 edit navigation 與 version tab read-only。 |
| `frontend/src/components/EditSkillModal.tsx` | delete or retire | 不再用於 skill description；若 category edit 另開 spec，先不要從詳情頁 expose。 |
| `frontend/src/components/EditSkillModal.test.tsx` | delete or rewrite | 移除 direct description edit 測試。 |
| `frontend/src/api/skills.ts` | modify | `updateSkill` 型別移除 description 或加 deprecation；`addVersion` 保持既有 contract。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/UpdateSkillCommand.java` | modify | description 欄位移除或讓 controller 對 description fail-fast。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java` | modify | `PUT /skills/{id}` 收到 description 回 400；category-only path 若保留，文件化。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java` | modify | `addVersion` validation 通過後把 frontmatter description 寫回 `skills.description`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` | modify | 新增 `refreshDescriptionSnapshot`；`update` 不再直接改 description。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/command/*` | modify/new | 覆蓋 AC-S187-5/6/7。 |
| `docs/grimo/specs/spec-roadmap.md` | modify | 新增 S187 active row。 |
| `docs/grimo/architecture.md` / docs pages | inspect/modify if needed | 若 API docs 或 architecture 還說 description 可直接 edit，改成 latest SKILL.md snapshot。 |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
