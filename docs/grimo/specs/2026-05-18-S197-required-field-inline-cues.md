# S197: 必填欄位即時提示 UX

> 規格：S197 | 大小：S(8) | 狀態：📐 in-design
> 日期：2026-05-18
> 對應：PRD §P2 / S004 / S176 / S187 / S195

---

## 1. 目標

`/publish` 目前分類欄位空白時，使用者按「發佈技能」才看到瀏覽器原生泡泡「請填寫此欄位」。這張 spec 要把「必填」提前顯示在欄位旁邊，並在使用者碰過欄位後用 inline text 告訴他缺什麼。

這次優先處理會擋住技能發佈或改版的欄位：

```
/publish
  - 技能名稱
  - Skill 套件 / SKILL.md 內容
  - 分類

/skills/:id/edit
  - 分類
  - 上傳檔案模式的 Skill 套件
  - 貼上文本模式的 SKILL.md 內容
```

### Scope

| In | Out |
|---|---|
| 必填 label 加紅色 `*` / 小紅點 + `sr-only` 必填文字 | 不重做整套 form library |
| 欄位被 focus/blur 或 submit attempt 後顯示 inline 提示 | 不用 toast 取代欄位下方錯誤 |
| `PublishPage` submit disabled 條件納入 `category.trim().length === 0` | 不改後端 upload API contract |
| `FileDropZone` 支援外部傳入必填錯誤訊息 | 不解析 zip 內容做前端 SKILL.md 深度驗證 |
| `SkillEditPage` 分類清空時顯示原因 | 不改版本號留白自動產生行為 |
| 補前端測試，驗證提示文字與 FormData 不變 | 不新增 Playwright E2E，除非實作時發現 browser-only 行為 |

### Dependency

| Dependency | 類型 | 判斷 |
|---|---|---|
| S176 | shipped | `skills.name` 是平台顯示名稱，不套 SKILL.md package-name regex；本 spec 只標示它必填與最多 64 字元。 |
| S188 | shipped | 版本號可留白，不能被誤標成必填。 |
| S195 | shipped | `SkillEditPage` upload mode 已重用 `FileDropZone`；本 spec 可在同一元件加 required/error 顯示。 |

## 2. 研究與設計

### 2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
|------|----------|--------------|
| `docs/grimo/PRD.md` §P2 | 發佈流程要驗證 SKILL.md，失敗要回具體欄位與問題。 | UI 不應等送出後才讓使用者知道必填欄位沒填。 |
| `frontend/src/pages/PublishPage.tsx:108` | `submitDisabled` 檢查 `skillName` 與 file/text，沒有檢查 `category`。 | 分類空白時仍可能觸發 native required 泡泡；要把分類納入 disabled 與 inline 提示。 |
| `frontend/src/pages/PublishPage.tsx:140` | 技能名稱 label 只有文字，input 有 `required`。 | 視覺上看不出必填；要在 label 補 required mark。 |
| `frontend/src/pages/PublishPage.tsx:154` | 檔案 mode 只有 `FileDropZone`，沒有 required label / error prop。 | `FileDropZone` 要能顯示「請選擇 zip 或 SKILL.md」。 |
| `frontend/src/pages/PublishPage.tsx:193` | 文本 mode 有 frontmatter live validation，但空白時不顯示缺內容。 | 空白與 frontmatter 錯誤要分開：空白提示「請貼上 SKILL.md 內容」，有內容才顯示 frontmatter checks。 |
| `frontend/src/pages/SkillEditPage.tsx:101` | 分類清空時 `儲存分類` disabled，但欄位旁沒有原因。 | 顯示「分類不可空白」避免使用者只看到 disabled button。 |
| [MDN `required` attribute](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Attributes/required) | required 欄位空值會變成 `valueMissing` 並 match `:invalid`。 | 可以保留 native `required` 作語意與 fallback，但主要 UX 用自家 inline 提示。 |
| [W3C WAI Form Instructions](https://www.w3.org/WAI/tutorials/forms/instructions/) | 必填與格式限制可以放在 label 文字內，額外說明可用 `aria-describedby` 關聯到欄位。 | 紅點 / `*` 要搭配 `sr-only` 或 label 文字；錯誤文字要用 `aria-describedby` 接到 input。 |
| [W3C WAI Validating Input](https://www.w3.org/WAI/tutorials/forms/validation/) | 範例把 required 寫在 label，並同時使用 `required` / `aria-required`。 | 必填提示不能只靠顏色；需要讓輔助工具也讀得到。 |

### 2.2 使用者看到的規則

| 狀態 | 視覺行為 |
|---|---|
| 欄位必填，尚未互動 | label 顯示紅色 `*` 或小紅點；欄位下方維持原 help text。 |
| 欄位被 blur 後仍空白 | 欄位 border 變 error 色，help text 下方顯示「請填寫分類」之類的短句。 |
| 使用者按主要送出按鈕但還有缺欄位 | 不送 request；所有缺欄位顯示 inline 提示，focus 第一個缺欄位。 |
| 欄位補上合法值 | error text 消失，help text 保留。 |

### 2.3 Required mark 設計

紅色 `*` / 小紅點是可接受的常見表示，但不能只有紅色符號。採用：

```tsx
<span aria-hidden="true" className="ml-1 text-red-400">*</span>
<span className="sr-only">必填</span>
```

若欄位下方有錯誤文字，input/textarea/dropzone wrapper 要接：

```tsx
aria-invalid={Boolean(error)}
aria-describedby="publish-category-help publish-category-error"
```

### 2.4 做法比較

| 做法 | 採用 | 理由 |
|---|---|---|
| A: 保留 `required`，加自家 required mark + inline error | yes | native validation 留作 fallback；使用者主要看我們自己的繁中提示。 |
| B: 拿掉 `required`，全部用 React state 控制 | no | 會少掉瀏覽器與輔助工具的原生語意，除非整套 form validation 重做。 |
| C: 只在 button tooltip 說明不能送 | no | 使用者仍不知道要改哪個欄位；欄位下方提示比較直接。 |
| D: 建一個輕量 `RequiredMark` / `FieldMessage` helper | yes | 避免每個 label 重複 `aria-hidden` + `sr-only`；但不引入新 package。 |

### 2.5 Task 邊界提示

| Task 候選 | File | 正向情境 | 反向情境 | POC |
|---|---|---|---|---|
| T01 | `frontend/src/pages/PublishPage.tsx` | 技能名稱 / 分類 / file/text mode 顯示必填 mark 與缺欄提示 | 版本號仍顯示可留白，不出現必填 mark | not required |
| T02 | `frontend/src/components/FileDropZone.tsx` | 未選檔且 caller 傳入 error 時，dropzone 下方顯示錯誤並可被 `aria-describedby` 指到 | 副檔名 / 大小錯誤仍顯示既有訊息，不被外部 required error 蓋掉 | not required |
| T03 | `frontend/src/pages/SkillEditPage.tsx` | 編輯分類清空後看到「分類不可空白」；upload mode 未選檔時看到「請選擇 zip 或 SKILL.md」 | 讀取 SKILL.md 中不誤顯缺內容錯誤 | not required |
| T04 | `frontend/src/pages/PublishPage.test.tsx`, `SkillEditPage.test.tsx`, `FileDropZone.test.tsx` | RTL 驗提示文字、disabled 條件、`aria-invalid` / `aria-describedby` | 測試不依賴 CSS color 或瀏覽器原生 bubble | not required |
| T05 | `docs/grimo/ui/DESIGN.md` | Publish/Edit form rule 補上「必填 mark + inline message」 | 不改 category palette / risk palette 規則 | not required |

### 2.6 Planning-Spec Readiness Check

| Check | Result |
|---|---|
| Release Completeness Gate | PASS — S196 已 shipped v4.79.0，`docs/grimo/specs/` 根目錄沒有其他 in-flight spec。 |
| Existing implementation scan | PASS — 問題集中在 `PublishPage`、`FileDropZone`、`SkillEditPage`，不需要後端 schema/API 變更。 |
| Research citation | PASS — MDN 與 W3C WAI 官方文件確認 required semantics、label instruction、`aria-describedby` / required label pattern。 |
| POC need | not required — React state + existing component props + RTL tests；沒有新 package、SDK、framework SPI 或 browser-only API 假設。 |

Design verdict：sections 1-5 are concrete enough for `$planning-tasks S197`; no POC needed.

## 3. 驗收條件（SBE）

驗證命令：

執行：`cd frontend && npm test && npm run verify`
通過條件：所有 S197 相關測試綠燈，且 TypeScript / ESLint 通過。

| AC | 優先級 | 驗證方式 | 標題 |
|----|---|---|---|
| AC-S197-1 | 必做 | Test | Publish 必填欄位一開始就有 required mark |
| AC-S197-2 | 必做 | Test | Publish 分類空白不再靠 native bubble 才提醒 |
| AC-S197-3 | 必做 | Test | File mode 未選檔有 inline required error |
| AC-S197-4 | 必做 | Test | Text mode 空白與 frontmatter 錯誤分開顯示 |
| AC-S197-5 | 必做 | Test | 版本號不被標成必填 |
| AC-S197-6 | 必做 | Test | SkillEdit 分類 / upload mode 顯示缺欄原因 |
| AC-S197-7 | 必做 | Inspection | a11y attributes 接到 help/error text |
| AC-S197-8 | 必做 | Inspection | DESIGN 同步必填提示規則 |

**AC-S197-1: Publish 必填欄位一開始就有 required mark**
- Given 使用者打開 `/publish`
- When 頁面載入完成
- Then `技能名稱`、`上傳 Skill 套件` 或 `SKILL.md 內容`、`分類` label 旁顯示必填 mark
- And `版本號` label 旁沒有必填 mark

**AC-S197-2: Publish 分類空白不再靠 native bubble 才提醒**
- Given 使用者已填技能名稱與 SKILL.md 內容，但分類空白
- When 使用者按 `發佈技能` 或分類欄位 blur
- Then 畫面在分類欄位下方顯示 `請填寫分類`
- And frontend 不送 `POST /api/v1/skills/upload`

**AC-S197-3: File mode 未選檔有 inline required error**
- Given 使用者停在 `/publish` 的 `上傳檔案` mode
- When 使用者填好技能名稱與分類，但未選檔
- Then `發佈技能` disabled 或 submit attempt 後顯示 `請選擇 zip 或 SKILL.md`
- And `FileDropZone` 仍可顯示既有副檔名錯誤，例如 `只接受 .zip / .md 檔`

**AC-S197-4: Text mode 空白與 frontmatter 錯誤分開顯示**
- Given 使用者切到 `貼上文本` mode
- When SKILL.md textarea 空白
- Then 顯示 `請貼上 SKILL.md 內容`
- When 使用者輸入缺 `description` 的 frontmatter
- Then 顯示既有 `缺必填欄位：description`

**AC-S197-5: 版本號不被標成必填**
- Given 使用者打開 `/publish`
- When 查看 `版本號` 欄位
- Then 欄位仍可留白
- And FormData 仍不 append 空白 `version`

**AC-S197-6: SkillEdit 分類 / upload mode 顯示缺欄原因**
- Given owner 打開 `/skills/:id/edit`
- When 清空分類欄位
- Then `儲存分類` disabled，欄位下方顯示 `分類不可空白`
- When 切到 upload mode 且未選檔
- Then `儲存新版本` disabled，dropzone 區域顯示 `請選擇 zip 或 SKILL.md`

**AC-S197-7: a11y attributes 接到 help/error text**
- Given 任一必填欄位有 inline error
- Then input/textarea/dropzone wrapper 有 `aria-invalid="true"`
- And `aria-describedby` 包含 error text id
- And required mark 有 `aria-hidden="true"`，旁邊有 `sr-only` 的 `必填`

**AC-S197-8: DESIGN 同步必填提示規則**
- Given 開發者閱讀 `docs/grimo/ui/DESIGN.md`
- Then Publish/Edit form rule 有記錄 required mark + inline error 的規則
- And 沒有把 required mark 顏色混進 category / risk palette 規則

## 4. 實作筆記

- 優先用小 helper，不新增第三方套件。
- 錯誤文字用繁體中文，API error 仍維持既有英文後端訊息 + frontend localize。
- Source code 註解只留 S197 + 簡短 override 提示；完整 rationale 留本 spec。
- Button disabled 不是唯一提示；欄位旁要說明為什麼不能送。
- 不要用瀏覽器原生泡泡當主要 UX，因為它只在 submit attempt 後出現，且文字由 browser locale 決定。

## 5. QA / Ship 計畫

本 spec 是 frontend UX polish，預期 verification：

```bash
cd frontend && npm test -- PublishPage SkillEditPage FileDropZone
cd frontend && npm run verify
```

Browser E2E 暫不列為必做：這次不新增 route、不改 backend、不依賴真瀏覽器才能觸發的 API。若實作過程改到 native form submit、focus 第一個錯誤欄位，Phase 4 再補 Browser/Playwright 截圖或互動檢查。

## 6. Task Plan

待 `$planning-tasks S197` 產生。

## 7. Results

待實作後填寫。
