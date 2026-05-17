# S187: Skill SKILL.md 編輯頁

> 規格：S187 | 大小：M(13) | 狀態：⏳ Plan
> 日期：2026-05-16
> 對應：PRD P2 更新已有 skill 版本 / P1 技能詳情頁 / S142a SkillDetailPage v2 / S163 EditSkillModal / S176 explicit publish skill name / S186 embedding source / S188 version label optional input
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
  - 預設貼上文本 mode：載入 latest SKILL.md 後直接編輯
  - 版本號可留空或自訂；留空時由 S188 後端建立下一個流水號
  - 儲存後呼叫 PUT /api/v1/skills/{id}/versions
  - 成功後進入驗證中頁（沿用 /publish/validate?id={id}，但文案支援新版本驗證）

Skill detail Versions tab
  - 只顯示 Version History
  - 不放 upload form
```

設計邊界：

| 項目 | S187 決定 |
|---|---|
| `skills.description` | 是 latest SKILL.md frontmatter `description` 的 Skill Description Snapshot；新版本 publish 後更新，不提供直接編輯表單。 |
| `skills.name` | 是平台顯示名稱；S187 不改。`SKILL.md` frontmatter `name` 保存在 version metadata。 |
| `category` | 可在 `/skills/{id}/edit` 編輯；它是平台分類，不是 SKILL.md frontmatter canonical source。儲存分類走既有 `PUT /api/v1/skills/{id}` category-only body。 |
| 版本語意 | 編輯 SKILL.md 一律建立新版本；不覆寫既有版本。 |
| version input | 由 S188 決定：可留空或自訂；留空時 backend 產 `1` / `2` / `3`。S187 不在前端猜下一版號。 |
| S186 關係 | S186 先修 semantic search / vector projection 問題並 ship；S187 才開始實作 edit page 與 `skills.description` snapshot 寫入。兩者不互相 import production type，但執行順序是 S186 → S187。 |

## 2. 研究與設計

### 2.1 現況掃描

| 來源 | 查到什麼 | 對 S187 的影響 |
|---|---|---|
| [PRD.md P2](/Users/samzhu/workspace/github-samzhu/skills-hub/docs/grimo/PRD.md:94) | 作者更新已有 skill 時，上傳新版本，舊版本仍可下載，詳情頁顯示完整版本歷史。 | S187 的「編輯」不應直接覆蓋舊內容；要建立新版本。 |
| [PublishPage.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/PublishPage.tsx:30) | `/publish` 已有「上傳檔案 / 貼上文本」雙模式，text mode 會把 textarea 包成 `File([text], 'SKILL.md')`；同頁填 version/category；成功後導到 `/publish/validate?id={id}`。 | edit page 沿用這個互動模型與 `validateFrontmatter`，但預設 text mode 並預填 latest SKILL.md；拿掉平台名稱與 visibility，保留 category 編輯；新增版本成功後也進驗證中頁。 |
| [PublishValidatePage.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/PublishValidatePage.tsx:1) | 建立新 skill 後顯示「發佈流程 / 驗證進行中」，輪詢 skill riskLevel 完成後導到 review。 | S187 要讓它支援新版本驗證文案，避免 edit page 成功後直接回 detail 看到尚未更新完成的掃描狀態。 |
| [SkillDetailPage.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/SkillDetailPage.tsx:190) | Version tab 目前同時 render `VersionsTabV2` 與 `AddVersionForm`。 | S187 要移除 tab 內新增版本表單，只保留紀錄。 |
| [EditSkillModal.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/components/EditSkillModal.tsx:69) | modal 內 description textarea 只有 4 rows，且直接呼叫 `PUT /skills/{id}` 改 `skills.description`；同 modal 也改 category。 | 不符合「description 來自 latest SKILL.md」；S187 要停止前端直接改 description，但 category 編輯移到 edit page 保留。 |
| [skills.ts](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/api/skills.ts:87) | `updateSkill` 目前傳 `description/category`；`addVersion` 已存在並走 multipart `file + version`。 | edit page 優先 reuse `addVersion`；`updateSkill` 改成 category-only 或在 type 上不再接受 description。 |
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
| C. 新增 `/skills/{id}/edit` 完整頁面 | yes | 詳情頁編輯按鈕導向 edit page；edit page 預設 text mode 並載入 latest SKILL.md，也支援 zip upload；保存建立新版本；同頁可改 category；版本 tab 只看紀錄。 | 中成本；需新增 route/page/test，並收斂 direct description update。 |

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

[貼上文本] [上傳檔案]

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

版本號（可留空） [ 1.1.0          ]

技能設定
分類   [ DevOps         ] [儲存分類]

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

版本號（可留空） [      ]
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
  default mode = text
  useSkillFile(id, 'SKILL.md') -> textarea 預填 latest SKILL.md

fallback:
  latest SKILL.md 讀取失敗 -> 顯示錯誤，仍可切換 upload mode 上傳 zip / SKILL.md

submit file mode:
  selected File + optional version -> addVersion(id, file, version?)

submit text mode:
  new File([skillMdText], 'SKILL.md', { type: 'text/markdown' })
    -> addVersion(id, syntheticFile, version?)

submit category:
  category input -> updateSkill(id, { category })

version success:
  invalidate ['skills', id], ['skills', id, 'versions'], skill file query
  navigate(`/publish/validate?id=${id}&mode=version`)

validate success:
  /publish/validate?id={id}&mode=version -> scan done -> navigate(`/skills/${id}`)
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
| `{ "category": "DevOps" }` | 成功更新 category；edit page 的「儲存分類」使用此 body。 |
| `{ "description": "...", "category": "DevOps" }` | 400，不局部套用 category，避免 caller 以為 description 有成功。 |

### 2.5 Task 邊界提示

| Task 候選 | Class / file | 正向情境 | 反向情境 | POC |
|---|---|---|---|---|
| T01 frontend route + detail navigation | `App.tsx`, `SkillDetailPage.tsx`, `SkillDetailPage.test.tsx` | owner 點「編輯」進 `/skills/{id}/edit` | Version tab 不再出現「新增版本」form | not required |
| T02 edit page shell + text mode | `SkillEditPage.tsx`, `SkillEditPage.test.tsx` | text mode 預填 latest SKILL.md，textarea 足夠大並有 frontmatter check | 缺 description 時 submit disabled | not required |
| T03 upload/text submit reuse addVersion | `SkillEditPage.tsx`, `skills.ts` if needed | zip 或 pasted text 都呼叫 `addVersion(id,file,version?)`；blank version 不 append FormData（S188） | duplicate version 顯示 409 localize error，不導回 detail | not required |
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
| AC-S187-4 | 必做 | Frontend test | edit page 上傳檔案 mode 建立新版本後進驗證中 |
| AC-S187-5 | 必做 | Backend test | 新版本 publish 更新 `skills.description` snapshot |
| AC-S187-6 | 必做 | Backend test | direct description update 被拒絕 |
| AC-S187-7 | 必做 | Backend/Frontend test | duplicate version 不覆寫舊版本 |
| AC-S187-8 | 建議 | Frontend test | 編輯頁手機版不重疊且主要操作可見 |
| AC-S187-9 | 必做 | Frontend/Backend test | edit page 可更新 category |
| AC-S187-10 | 必做 | Frontend test | version 驗證完成後導回 detail |

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
- When（動作）Alice 開啟 `/skills/skill-docker/edit`
- Then（結果）預設選中「貼上文本」mode
- And（而且）textarea 內已有 latest SKILL.md
- And（而且）frontmatter check 顯示 `name` 與 `description` 通過
- And（而且）刪掉 `description:` 後「儲存新版本」disabled

**AC-S187-4: edit page 上傳檔案 mode 建立新版本**
- Given（前提）Alice 在 `/skills/skill-docker/edit` 選「上傳檔案」
- When（動作）Alice 選 `skill.zip`、輸入版本 `1.1.0`、點「儲存新版本」
- Then（結果）前端呼叫 `PUT /api/v1/skills/skill-docker/versions`，multipart 內有 `file=skill.zip` 與 `version=1.1.0`
- And（而且）成功後導到 `/publish/validate?id=skill-docker&mode=version`
- And（而且）驗證中頁文案顯示「新版本驗證中」或等價文字，不顯示「發佈新技能」語意

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

**AC-S187-9: edit page 可更新 category**
- Given（前提）Alice 對 `skill-docker` 有 write permission，並開啟 `/skills/skill-docker/edit`
- When（動作）Alice 把分類改成 `DevOps` 並點「儲存分類」
- Then（結果）前端呼叫 `PUT /api/v1/skills/skill-docker`，body 是 `{"category":"DevOps"}`
- And（而且）request body 不含 `description`
- And（而且）成功後 detail/list 下一次讀取顯示新 category

**AC-S187-10: version 驗證完成後導回 detail**
- Given（前提）Alice 從 edit page 成功新增版本後位於 `/publish/validate?id=skill-docker&mode=version`
- When（動作）輪詢 `GET /api/v1/skills/skill-docker` 回傳 riskLevel 不為 null
- Then（結果）瀏覽器導向 `/skills/skill-docker`
- And（而且）建立新 skill 的 `/publish/validate?id=new-skill` 仍維持原本導向 `/publish/review?id=new-skill`

### 非功能需求檢查

| 分類 | 對應驗收 | 說明 |
|---|---|---|
| Performance | AC-S187-3 | edit page 只在需要編輯文本時讀 latest SKILL.md；detail/list 仍讀 `skills.description` snapshot。 |
| Security | AC-S187-1, AC-S187-4 | 新版本仍走既有 write permission；不新增前端自製授權判斷。 |
| Reliability | AC-S187-5, AC-S187-7, AC-S187-10 | 新版本成功才更新 snapshot；版本重複不覆寫舊資料；新增版本後先等待驗證完成再回 detail。 |
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
export function addVersion(id: string, file: File, version?: string): Promise<void>
```

文字模式不新增 JSON endpoint：

```ts
const file = new File([skillMdText], 'SKILL.md', { type: 'text/markdown' })
await addVersion(id, file, version)
```

`version` 若為空字串，`addVersion` 依 S188 不 append `version` FormData 欄位，由 backend 建立下一個流水號。

成功導向：

```ts
navigate(`/publish/validate?id=${id}&mode=version`)
```

`PublishValidatePage` 讀 `mode=version` 時使用「新版本驗證中」文案；scan 完成後導回 `/skills/${id}`。沒有 `mode=version` 時維持建立新 skill flow，scan 完成後導到 `/publish/review?id=${id}`。

### 4.3 Backend API

沿用：

```http
PUT /api/v1/skills/{id}/versions
Content-Type: multipart/form-data

file=<zip or SKILL.md>
version=1.1.0  # optional；缺省時由 S188 建立下一個流水號
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
| `frontend/src/pages/SkillEditPage.tsx` | new | 完整 SKILL.md edit page；支援 upload/text mode、version input、category input、validation、submit、成功導到 version 驗證中頁。 |
| `frontend/src/pages/SkillEditPage.test.tsx` | new | 覆蓋 AC-S187-1/3/4/7/8/9 的頁面行為。 |
| `frontend/src/pages/PublishValidatePage.tsx` | modify | 支援 `mode=version` 文案與完成後導回 detail；預設 create flow 不變。 |
| `frontend/src/pages/PublishValidatePage.test.tsx` | modify | 覆蓋 AC-S187-10。 |
| `frontend/src/pages/SkillDetailPage.test.tsx` | modify | 驗證 edit navigation 與 version tab read-only。 |
| `frontend/src/components/EditSkillModal.tsx` | delete or retire | 不再用於 skill description；category edit 移到 `/skills/{id}/edit`。 |
| `frontend/src/components/EditSkillModal.test.tsx` | delete or rewrite | 移除 direct description edit 測試。 |
| `frontend/src/api/skills.ts` | modify | `updateSkill` 型別改成 category-only；`addVersion` 保持既有 contract。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/UpdateSkillCommand.java` | modify | description 欄位移除或讓 controller 對 description fail-fast。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandController.java` | modify | `PUT /skills/{id}` 收到 description 回 400；category-only path 若保留，文件化。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java` | modify | `addVersion` validation 通過後把 frontmatter description 寫回 `skills.description`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` | modify | 新增 `refreshDescriptionSnapshot`；`update` 不再直接改 description。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/command/*` | modify/new | 覆蓋 AC-S187-5/6/7。 |
| `docs/grimo/specs/spec-roadmap.md` | modify | 新增 S187 active row。 |
| `docs/grimo/architecture.md` / docs pages | inspect/modify if needed | 若 API docs 或 architecture 還說 description 可直接 edit，改成 latest SKILL.md snapshot。 |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
## 6. Task Plan

POC：not required — S187 只重組既有 frontend route/page flow、沿用 `addVersion(...)`、`useSkillFile(...)`、existing Skill aggregate / version publish path；沒有新套件、未知 SDK、DB schema 或 framework SPI。Release gate 先修已完成：S186/S188/S179 都已 shipped，`docs/grimo/tasks/` 目前沒有舊 task 殘留。

E2E 評估：S187 是 browser/UI flow，但主要行為可由 Vitest + backend tests 先紅綠；最後 T06 必須明確評估是否需要 `@S187` Playwright evidence 來驗 mobile layout 與 version validation assembly。若 T06 判定不新增 Playwright，必須在 §7 寫出實際不跑原因。

| 順序 | Task file | AC | 狀態 | 驗證 |
|---:|---|---|---|---|
| 1 | `docs/grimo/tasks/2026-05-17-S187-T01-detail-route-version-history.md` | AC-S187-1, AC-S187-2 | PASS（2026-05-17） | `cd frontend && npm test -- SkillDetailPage` |
| 2 | `docs/grimo/tasks/2026-05-17-S187-T02-edit-page-text-mode.md` | AC-S187-3 | pending | `cd frontend && npm test -- SkillEditPage` |
| 3 | `docs/grimo/tasks/2026-05-17-S187-T03-edit-page-submit-and-validate-flow.md` | AC-S187-4, AC-S187-7, AC-S187-10 | pending | `cd frontend && npm test -- SkillEditPage PublishValidatePage` |
| 4 | `docs/grimo/tasks/2026-05-17-S187-T04-backend-description-snapshot.md` | AC-S187-5, AC-S187-7 | pending | `cd backend && ./gradlew test --tests "*SkillUpload*" --tests "*SkillCommand*"` |
| 5 | `docs/grimo/tasks/2026-05-17-S187-T05-category-only-update-and-description-rejection.md` | AC-S187-6, AC-S187-9 | pending | `cd frontend && npm test -- SkillEditPage && cd ../backend && ./gradlew test --tests "*SkillUpdateControllerTest"` |
| 6 | `docs/grimo/tasks/2026-05-17-S187-T06-browser-mobile-doc-sync.md` | AC-S187-8, AC-S187-10, docs sync | pending | `cd frontend && npm test -- SkillEditPage PublishValidatePage`; if required `cd e2e && npx playwright test --grep @S187` |
