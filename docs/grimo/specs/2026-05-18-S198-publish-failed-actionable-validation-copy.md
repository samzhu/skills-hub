# S198: Publish Failed Actionable Validation Copy

> 規格：S198 | 大小：XS(4) | 狀態：📐 in-design
> 日期：2026-05-18
> 對應：PRD P2「驗證失敗回具體錯誤」、S098b/S098b3-2 publish failed page、S197 validator recommendation split

---

## 1. 目標

`/publish/failed?state=A&msg=驗證失敗：SKILL.md validation failed` 目前頁面頂部顯示：

```text
驗證在第 2 步停止 — 沒有任何資料寫入。
你的 bundle 沒通過 SKILL.md 驗證。請依下方錯誤訊息修正後重新上傳；目前 registry 沒有寫入任何記錄。
```

這兩句只告訴 user「沒有寫入」，沒有說「為什麼失敗、要改哪個檔案、改完會發生什麼」。如果後端 response 有 `findings[]`，但 [uploadSkill()](/Users/samzhu/.codex/worktrees/81d8/skills-hub/frontend/src/api/skills.ts:398) 沒把 `findings` 放進 `ApiError`，`PublishPage` 轉址到 failed page 時 [PublishFailedPage.tsx](/Users/samzhu/.codex/worktrees/81d8/skills-hub/frontend/src/pages/PublishFailedPage.tsx:104) 只能拿到 generic `msg`，最後 user 只看到 `SKILL.md validation failed`。

S198 要讓 failed page 的第一屏直接回答：

1. 哪個檔案錯：`SKILL.md`
2. 哪條規則錯：例如 `SKILL.md 有 589 行，平台目前最多允許 500 行`
3. 下一步怎麼改：例如 `把詳細內容移到 references/，或等 S197 改成只扣品質分`
4. 是否有寫入：沒有寫入 registry

S198 只處理 UX / error payload 傳遞；「500 行應不應該擋 upload」由 S197 處理。

## 2. 研究與設計

### 2.1 目前資料流

```text
後端 400 body:
  {
    error: "VALIDATION_ERROR",
    message: "SKILL.md validation failed",
    findings: [
      { section: "skill_md", severity: "error", title: "skill_md_line_count: SKILL.md has 589 lines (max 500)" }
    ]
  }

frontend uploadSkill()
  -> 只讀 message/error
  -> new ApiError(..., code) 但 findings 遺失

PublishPage.onError()
  -> const findings = err.findings // undefined
  -> navigate(/publish/failed?state=A&msg=驗證失敗：SKILL.md validation failed)

PublishFailedPage
  -> fallback rows = [{ title: "驗證失敗：SKILL.md validation failed" }]
  -> top callout 仍是 generic copy
```

### 2.2 問題盤點

| 問題 | 位置 | 現在 user 看到 | 應改成 |
|------|------|----------------|--------|
| `findings[]` 遺失 | [skills.ts](/Users/samzhu/.codex/worktrees/81d8/skills-hub/frontend/src/api/skills.ts:398) | `SKILL.md validation failed` | 第一筆 finding title 能進 failed page。 |
| top callout generic | [PublishFailedPage.tsx](/Users/samzhu/.codex/worktrees/81d8/skills-hub/frontend/src/pages/PublishFailedPage.tsx:123) | 「沒通過 SKILL.md 驗證」 | 「SKILL.md 有 589 行，目前上限 500 行」這種具體主因。 |
| raw backend code 不好懂 | `skill_md_line_count: ...` | user 看到 rule id + 英文 | UI 轉成繁中：「SKILL.md 太長：589 行，目前上限 500 行。」 |
| 沒有下一步 | same page | 「請依下方錯誤訊息修正」 | 針對已知 rule 顯示具體修法；未知 rule 顯示保守 fallback。 |
| 直訪 failed URL 沒 findings | query string 只有 msg | 只能 generic | 顯示「此頁缺少詳細錯誤，請從上傳頁重送，或打開 Network 查看 `/api/v1/skills/upload` response」。 |

### 2.3 Rule copy mapping

先做 frontend mapping，不改 backend response shape：

| backend finding title startsWith | 主標 | 下一步 |
|----------------------------------|------|--------|
| `skill_md_line_count:` | `SKILL.md 太長：{actual} 行，目前上限 {limit} 行。` | `先把詳細內容移到 references/，SKILL.md 只保留啟動時一定要看的步驟。S197 會把這條改成品質扣分，不再擋上傳。` |
| `Missing required field: name` | `SKILL.md frontmatter 缺少 name。` | `在檔案最上方的 --- 區塊加入 name，例如 name: my-skill。` |
| `Missing required field: description` | `SKILL.md frontmatter 缺少 description。` | `在 --- 區塊加入 description，描述技能做什麼與何時使用。` |
| `No YAML frontmatter found` | `SKILL.md 最上方缺少 YAML frontmatter。` | `檔案開頭要有 ---、name、description、---，後面再寫 Markdown 內容。` |
| `Invalid YAML:` | `SKILL.md frontmatter 的 YAML 格式錯誤。` | `檢查縮排、冒號後空格、引號是否成對。` |
| `body_present:` | `SKILL.md frontmatter 後面沒有任何說明內容。` | `在第二個 --- 後加入此技能的使用步驟。` |
| fallback | 第一筆 finding title 原文 | `請依這筆錯誤修改 SKILL.md 後重新上傳。` |

### 2.4 UI sketch

```text
發佈未通過驗證
請修正 SKILL.md 後重新上傳 zip 套件

┌──────────────────────────────────────────────────────────┐
│ ! SKILL.md 太長：589 行，目前上限 500 行。                │
│                                                          │
│ 目前沒有任何資料寫入 registry。                           │
│ 下一步：把詳細內容移到 references/，SKILL.md 只保留啟動時  │
│ 一定要看的步驟。                                          │
└──────────────────────────────────────────────────────────┘

SKILL.md 驗證失敗                         1 error · 0 warning
✕ SKILL.md 太長：589 行，目前上限 500 行。
  原始訊息：skill_md_line_count: SKILL.md has 589 lines (max 500)

Bundle 結構                               尚未驗證
風險掃描                                  尚未執行
```

## 3. 驗收條件（SBE）

驗證命令：

```bash
cd frontend && npm test -- skills.test PublishFailedPage
```

| AC | 優先級 | 驗證方式 | 標題 |
|----|--------|----------|------|
| AC-S198-1 | 必做 | Test | uploadSkill 保留 response findings |
| AC-S198-2 | 必做 | Test | failed page top callout 顯示第一筆 finding 的繁中主因 |
| AC-S198-3 | 必做 | Test | line-count finding 顯示具體行數與上限 |
| AC-S198-4 | 必做 | Test | known field errors 顯示具體修法 |
| AC-S198-5 | 必做 | Test | 沒有 findings 時顯示 detail-unavailable fallback |
| AC-S198-6 | 建議 | Test | raw backend title 不消失，保留在細節列方便 debug |

**AC-S198-1: uploadSkill 保留 response findings**
- Given（前提）`POST /api/v1/skills/upload` 回 400 且 body 含 `findings[]`
- When（動作）`uploadSkill()` throw `ApiError`
- Then（結果）`ApiError.findings[0].title == "skill_md_line_count: SKILL.md has 589 lines (max 500)"`

**AC-S198-2: failed page top callout 顯示第一筆 finding 的繁中主因**
- Given（前提）router state 帶入第一筆 finding
- When（動作）render `/publish/failed?state=A`
- Then（結果）top callout 主標顯示 `SKILL.md 太長：589 行，目前上限 500 行。`
- And（而且）不再只顯示 `驗證在第 2 步停止 — 沒有任何資料寫入。`

**AC-S198-3: line-count finding 顯示具體行數與上限**
- Given（前提）finding title 是 `skill_md_line_count: SKILL.md has 589 lines (max 500)`
- When（動作）render failed page
- Then（結果）錯誤 row 顯示 `SKILL.md 太長：589 行，目前上限 500 行。`
- And（而且）下一步文字包含 `references/`

**AC-S198-4: known field errors 顯示具體修法**
- Given（前提）finding title 是 `Missing required field: name`
- When（動作）render failed page
- Then（結果）top callout 顯示 `SKILL.md frontmatter 缺少 name。`
- And（而且）下一步文字包含 `name: my-skill`

**AC-S198-5: 沒有 findings 時顯示 detail-unavailable fallback**
- Given（前提）URL 只有 `?state=A&msg=驗證失敗：SKILL.md validation failed`
- When（動作）render failed page
- Then（結果）頁面顯示 `這次失敗頁沒有收到詳細錯誤內容。`
- And（而且）頁面提示 `重新上傳一次` 或 `查看 /api/v1/skills/upload response`

**AC-S198-6: raw backend title 不消失**
- Given（前提）known rule 被轉成繁中
- When（動作）render failed page
- Then（結果）細節列仍可看到 `原始訊息：skill_md_line_count: SKILL.md has 589 lines (max 500)`

## 4. 檔案規劃

| 檔案 | 動作 | 說明 |
|------|------|------|
| `frontend/src/api/skills.ts` | modify | `uploadSkill()` error parsing 對齊 `addVersion()`，保留 `findings`。 |
| `frontend/src/api/skills.test.ts` | modify | 新增 uploadSkill findings propagation test。 |
| `frontend/src/pages/PublishFailedPage.tsx` | modify | 新增 finding copy mapper；top callout 用第一筆 finding 的主因與下一步。 |
| `frontend/src/pages/PublishFailedPage.test.tsx` | modify | 覆蓋 line count、missing name、no findings fallback。 |

## 5. 非目標

- 不改 validator 規則；500 行是否擋 upload 由 S197 處理。
- 不改後端 `ValidationFinding` schema。
- 不新增後端 log；S198 只讓 user 在 UI 看到清楚原因。

