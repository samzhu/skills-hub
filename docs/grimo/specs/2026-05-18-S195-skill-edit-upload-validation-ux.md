# S195: Skill Edit Upload Validation UX

> 規格：S195 | 大小：S(9) | 狀態：📐 in-design
> 日期：2026-05-18
> 對應：PRD P2「驗證失敗回具體錯誤」、S187 edit page、S098b3-2 structured findings

---

## 1. 目標

讓 Skill 編輯頁的「上傳檔案」模式和 `/publish` 一樣可以拖拽 `.zip` 或 `.md`，並在後端回 400 時把第一個可修正的 validation finding 顯示成主要錯誤訊息。

現在編輯頁 [SkillEditPage.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/SkillEditPage.tsx:319) 自己畫一個普通 file input；`/publish` 則用 [FileDropZone.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/components/FileDropZone.tsx:114) 顯示「拖拽 zip 或 md 檔到此處」。同時，`PUT /versions` 的 [addVersion](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/api/skills.ts:421) 讀到了 400 response body，但只把 `message/error` 放進 `ApiError`，把 `findings` 丟掉；所以使用者只看到 `SKILL.md validation failed`，看不到真正要改的欄位。

這個 spec 只改編輯新版本 UX，不改後端 validator 規則。S194 會處理 `metadata.tags` 是否允許上傳。

## 2. 研究與設計

### 2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
|------|----------|--------------|
| [FileDropZone.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/components/FileDropZone.tsx:32) | 已支援 drag/drop、click select、`.zip,.md` 副檔名檢查、10MB inline error。 | 編輯頁應重用，不再自建 file input。 |
| [PublishPage.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/PublishPage.tsx:155) | `/publish` 已用 `FileDropZone onFileSelect={setFile}`。 | S195 要對齊同一 UX。 |
| [api/client.ts](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/api/client.ts:110) | `apiFetch` 失敗時會把 `findings` 傳入 `ApiError`。 | `addVersion` 應補同樣行為。 |
| [PublishFailedPage.tsx](/Users/samzhu/workspace/github-samzhu/skills-hub/frontend/src/pages/PublishFailedPage.tsx:104) | 已有 structured findings row 呈現 pattern。 | 編輯頁可做 inline findings list，不必導到 failed page。 |
| [GlobalExceptionHandler.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java:65) | 後端 400 body 已含 `ValidationErrorResponse(..., findings)`。 | 這是 frontend propagation bug，不需新增 backend API。 |
| [ValidationFinding.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/shared/api/ValidationFinding.java:12) | finding 欄位是 `section`, `severity`, `title`, `hint`。 | 前端不能讀 `source/message/location`；主要錯誤訊息要來自第一筆 error finding 的 `title`。 |
| [SkillCommandService.java](/Users/samzhu/workspace/github-samzhu/skills-hub/backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java:342) | 目前 `buildFindings()` 產生的 `section` 都是 `skill_md`。 | S195 若不改後端，UI 範例與測試 fixture 要用 `skill_md`，不能假設會得到 `metadata` section。 |

### 2.2 架構設計

資料流：

```text
使用者切到「上傳檔案」
  -> 看到 FileDropZone：「拖拽 zip 或 md 檔到此處」
  -> drop/click 選 handover.zip
  -> 儲存新版本
  -> addVersion() 呼叫 PUT /api/v1/skills/{id}/versions
  -> 400 VALIDATION_ERROR + findings[]
  -> ApiError.findings 保留
  -> SkillEditPage 用第一筆 error finding.title 當主要錯誤訊息
  -> generic "SKILL.md validation failed" 只放在次要說明或除錯文字
```

低保真 UI sketch：

```text
編輯 SKILL.md                                           [取消] [儲存分類] [儲存新版本]
brainstorm-ideas-existing  latest 1

┌────────────────────────────────────────────────────────────────────────────┐
│ [貼上文本] [上傳檔案]                                                      │
│                                                                            │
│ 分類  [ PM                         ]                                       │
│ 版本號 [ 2                          ]                                       │
│                                                                            │
│ Skill 套件                                                                  │
│ ┌────────────────────────────────────────────────────────────────────────┐ │
│ │                         ↑                                              │ │
│ │              拖拽 zip 或 md 檔到此處                                    │ │
│ │                    或點擊選取檔案                                      │ │
│ └────────────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────────┘

若後端回 validation findings：

┌────────────────────────────────────────────────────────────────────────────┐
│ ! metadata: key 'owner' nested object is not supported                     │
│ 儲存新版本失敗：SKILL.md validation failed                                  │
│                                                                            │
│ - error · skill_md · metadata: key 'owner' nested object is not supported   │
└────────────────────────────────────────────────────────────────────────────┘
```

這不是最終像素稿，不新增設計系統；只是確認控制、字串、錯誤區塊與資料流。

### 2.3 做法比較

| 做法 | 採用 | 理由 |
|------|------|------|
| A: 編輯頁維持原 file input，只修 findings | no | 不符合 user 對 `/publish` drag/drop 一致性的要求。 |
| B: 編輯頁重用 `FileDropZone`，inline 顯示 findings | yes | 最小改動；共用既有副檔名/大小檢查；不改 route flow。 |
| C: 400 時導到 `/publish/failed` | no | 會離開 edit page，user 修改版本號/分類/選檔狀態容易丟失；新版本編輯應 inline 修正。 |

### 2.4 Task 邊界提示

| Task 候選 | Class / file | 來源 | 正向情境 | 反向情境 | POC |
|-----------|--------------|------|----------|----------|-----|
| T01 | `frontend/src/components/FileDropZone.tsx` | existing component | 支援傳入 `inputId` 或 aria label，方便 edit test 用 label 找 input | `/publish` 既有 tests 不變 | not required |
| T02 | `frontend/src/api/skills.ts` | `apiFetch` pattern | `addVersion` 400 時 `ApiError.findings` 有值 | non-JSON body fallback 仍顯 status | not required |
| T03 | `frontend/src/pages/SkillEditPage.tsx` | S187 edit page | upload mode 顯示 dropzone 與 selected filename；400 時主標顯示第一筆 error finding title | text mode 不顯示 dropzone；沒有 findings 時退回既有 localized error | not required |
| T04 | `SkillEditPage.test.tsx` | S187 tests | 400 findings inline render | duplicate version 409 仍顯版本已存在 | not required |

## 3. 驗收條件（SBE）

驗證命令：

執行：`./scripts/verify-all.sh`
通過條件：所有帶 `S195` AC id 的測試都是綠燈。

| AC | 優先級 | 驗證方式 | 標題 |
|----|--------|----------|------|
| AC-S195-1 | 必做 | Test | 編輯頁 upload mode 顯示 drag/drop 區 |
| AC-S195-2 | 必做 | Test | drop/click 選 zip 後送到 PUT /versions |
| AC-S195-3 | 必做 | Test | 第一筆 error finding 顯示成主要錯誤 |
| AC-S195-4 | 必做 | Test | addVersion 保留 response findings |
| AC-S195-5 | 建議 | Test | invalid extension 不送 PUT |
| AC-S195-6 | 建議 | Demo | mobile 寬度下 dropzone 不溢出 |

**AC-S195-1: 編輯頁 upload mode 顯示 drag/drop 區**
- Given（前提）使用者在 `/skills/4d3021ee-7c5c-4920-a312-1912141f1c45/edit`
- When（動作）點擊「上傳檔案」
- Then（結果）頁面顯示「拖拽 zip 或 md 檔到此處」
- And（而且）頁面顯示「或點擊選取檔案」

**AC-S195-2: drop/click 選 zip 後送到 PUT /versions**
- Given（前提）使用者位於 upload mode
- And（而且）已選取 `handover.zip`
- When（動作）點擊「儲存新版本」
- Then（結果）frontend 發出 `PUT /api/v1/skills/{id}/versions`
- And（而且）FormData 內 `file.name == "handover.zip"`

**AC-S195-3: 第一筆 error finding 顯示成主要錯誤**
- Given（前提）後端回 400 body：
  ```json
  {
    "error": "VALIDATION_ERROR",
    "message": "SKILL.md validation failed",
    "findings": [
      {
        "section": "skill_md",
        "severity": "error",
        "title": "metadata: key 'owner' nested object is not supported",
        "hint": null
      }
    ]
  }
  ```
- When（動作）`addVersionMutation` 進入 error state
- Then（結果）edit page 的錯誤區塊主標顯示 `metadata: key 'owner' nested object is not supported`
- And（而且）同一個錯誤區塊的次要文字顯示 `儲存新版本失敗：SKILL.md validation failed`
- And（而且）同一個錯誤區塊列出 `error · skill_md · metadata: key 'owner' nested object is not supported`
- And（而且）畫面不只顯示 `SKILL.md validation failed` 這種 generic 錯誤

**AC-S195-4: addVersion 保留 response findings**
- Given（前提）`PUT /versions` 回 400 且 body 含 `findings[]`
- When（動作）`addVersion()` throw `ApiError`
- Then（結果）`ApiError.findings` 等於 response body 的 findings
- And（而且）`localizeApiError()` 仍回傳 `驗證失敗：SKILL.md validation failed`，但 edit page 的主要錯誤訊息優先使用第一筆 error finding 的 `title`

**AC-S195-5: invalid extension 不送 PUT**
- Given（前提）使用者在 dropzone 選取 `handover.txt`
- When（動作）檔案選取事件觸發
- Then（結果）頁面顯示「只接受 .zip / .md」
- And（而且）未發出 `PUT /api/v1/skills/{id}/versions`

**AC-S195-6: mobile 寬度下 dropzone 不溢出**
- Given（前提）viewport width 是 390px
- When（動作）進入 edit page upload mode
- Then（結果）「拖拽 zip 或 md 檔到此處」文字在卡片內可見
- And（而且）主要 action 按鈕仍可見

### 非功能需求檢查

| 分類 | 對應驗收 | 說明 |
|------|----------|------|
| Performance | — | N/A — 只改前端單頁互動，不新增查詢或背景工作。 |
| Security | AC-S195-5 | drag/drop 不信任 input accept；副檔名仍由 `FileDropZone` JS guard 擋第一層，後端 magic-byte 繼續是最終防線。 |
| Reliability | AC-S195-4 | response findings 不遺失，避免 user 只看到 generic error 後重試同一錯誤。 |
| Usability | AC-S195-1, AC-S195-3, AC-S195-6 | 上傳控制對齊 `/publish`，錯誤主標直接指出要改的欄位，mobile 不溢出。 |
| Maintainability | AC-S195-1 | 重用 `FileDropZone`，避免 `/publish` 和 edit page 兩套上傳 UI 分歧。 |

## 4. 介面與 API 設計

`FileDropZone` props 增加可選 id：

```ts
interface FileDropZoneProps {
  onFileSelect: (file: File) => void
  selectedFile: File | null
  accept?: string
  maxSizeBytes?: number
  inputId?: string
}
```

`addVersion()` error parsing 對齊 `apiFetch`：

```ts
const b = body as { message?: string; error?: string; findings?: ValidationFinding[] }
throw new ApiError(res.status, b.message ?? `Version upload failed: ${res.status}`, b.error, b.findings)
```

`SkillEditPage` 新增 inline findings renderer：

```ts
function primaryValidationMessage(error: unknown): string | null {
  if (!(error instanceof ApiError)) return null
  const findings = error.findings ?? []
  return findings.find((finding) => finding.severity === 'error')?.title
    ?? findings[0]?.title
    ?? null
}

function ValidationFindingsList({ findings }: { findings?: ValidationFinding[] }) {
  if (!findings?.length) return null
  return <ul>{findings.map(...section/severity/title/hint...)}</ul>
}
```

## 5. 檔案規劃

| 檔案 | 動作 | 說明 |
|------|------|------|
| `frontend/src/components/FileDropZone.tsx` | modify | 增加 optional `inputId`；不改 `/publish` 預設行為。 |
| `frontend/src/components/FileDropZone.test.tsx` | modify | 驗 `inputId` 與既有 prompt/guard 不回歸。 |
| `frontend/src/api/skills.ts` | modify | `addVersion` 保留 `findings`。 |
| `frontend/src/api/skills.test.ts` | modify | 新增 400 findings propagation test。 |
| `frontend/src/pages/SkillEditPage.tsx` | modify | upload mode 改用 `FileDropZone`；mutation error 顯 findings list。 |
| `frontend/src/pages/SkillEditPage.test.tsx` | modify | 新增 S195 AC tests；調整既有 upload test 找 input 的方式。 |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
