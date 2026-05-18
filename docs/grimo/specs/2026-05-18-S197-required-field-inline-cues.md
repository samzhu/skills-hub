# S197: 必填欄位即時提示 UX

> 規格：S197 | 大小：S(8) | 狀態：✅ QA PASS (ready to ship)
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

POC：not required — S197 不新增套件、SDK、framework SPI、schema migration 或 browser-only API；只調整既有 React form state、`FileDropZone` props、RTL tests 與 UI DESIGN 文件。Phase 0 pre-flight 已對照 PRD P2、S176/S188/S195 shipped findings、目前 `PublishPage` / `SkillEditPage` / `FileDropZone` 實作；未發現需要回到 `$planning-spec` 的設計矛盾。

E2E：not required for planning — S197 改的是表單必填提示、component props、RTL 可驗證的 DOM text / attributes 與 docs sync；沒有新增 route、test seed endpoint、後端 contract、credential injection 或真瀏覽器-only API。若實作時改成依賴 native submit focus 或瀏覽器 constraint validation bubble，Phase 4 需重新評估 Browser/Playwright 證據。

| 順序 | Task | 主要檔案 | 覆蓋 AC | 驗證方式 | 前置 |
|---:|---|---|---|---|---|
| 1 | S197-T01 Publish 必填欄位 inline 提示 | `PublishPage.tsx`, `PublishPage.test.tsx` | AC-S197-1, AC-S197-2, AC-S197-4, AC-S197-5, AC-S197-7 | `cd frontend && npm test -- PublishPage` | 無 |
| 2 | S197-T02 FileDropZone required error contract | `FileDropZone.tsx`, `FileDropZone.test.tsx` | AC-S197-3, AC-S197-7 | `cd frontend && npm test -- FileDropZone` | 無 |
| 3 | S197-T03 SkillEdit 分類與 upload mode 缺欄原因 | `SkillEditPage.tsx`, `SkillEditPage.test.tsx` | AC-S197-6, AC-S197-7 | `cd frontend && npm test -- SkillEditPage` | T02 |
| 4 | S197-T04 DESIGN 必填表單規則同步 | `docs/grimo/ui/DESIGN.md` | AC-S197-8 | `rg -n "必填\|aria-describedby\|required" docs/grimo/ui/DESIGN.md` | T01, T02, T03 |
| 5 | S197-T05 Publish file mode required error page contract | `PublishPage.tsx`, `PublishPage.test.tsx` | AC-S197-3, AC-S197-7 | `cd frontend && npm test -- PublishPage` | T02, QA §7.5 |

### AC Coverage

| AC | Task | 可驗證輸出 |
|---|---|---|
| AC-S197-1 | T01 | `/publish` label 顯示 required mark；`版本號` 不顯示 required mark。 |
| AC-S197-2 | T01 | 分類空白時畫面顯示 `請填寫分類`，且沒有 `POST /api/v1/skills/upload`。 |
| AC-S197-3 | T02 | `FileDropZone` 顯示 `請選擇 zip 或 SKILL.md`，副檔名錯誤仍顯示既有訊息。 |
| AC-S197-3 QA fix | T05 | `/publish` file mode 也把 `請選擇 zip 或 SKILL.md` 傳給 `FileDropZone`，且 page-level test 驗 `publish-file-error`。 |
| AC-S197-4 | T01 | text mode 空白顯示 `請貼上 SKILL.md 內容`；frontmatter 錯誤仍顯示 `缺必填欄位：description`。 |
| AC-S197-5 | T01 | `版本號` optional，空白 FormData 不含 `version`。 |
| AC-S197-6 | T03 | edit page 分類清空顯示 `分類不可空白`；upload mode 未選檔顯示 `請選擇 zip 或 SKILL.md`。 |
| AC-S197-7 | T01, T02, T03 | required mark、`aria-invalid`、`aria-describedby` 都可由 RTL assertion 或 source inspection 驗證。 |
| AC-S197-8 | T04 | `docs/grimo/ui/DESIGN.md` 記錄 Publish/Edit required mark + inline error rule。 |

## 7. Results

狀態：QA PASS 2026-05-19。S197-T05 已補上 `/publish` file mode page-level test，`./scripts/verify-all.sh` 本 tick 全綠；下一步是 `$shipping-release S197`。

### 7.1 Task Results

| Task | Result | 實際輸出 |
|---|---|---|
| S197-T01 Publish 必填欄位 inline 提示 | PASS | `PublishPage.test.tsx` 覆蓋 required mark、分類缺欄 inline error、text mode 空白、frontmatter 錯誤、版本號 optional、`aria-describedby`。 |
| S197-T02 FileDropZone required error contract | PASS | `FileDropZone.test.tsx` 覆蓋 caller required error、副檔名錯誤優先、`aria-invalid` / `aria-describedby`。 |
| S197-T03 SkillEdit 分類與 upload mode 缺欄原因 | PASS | `SkillEditPage.test.tsx` 覆蓋分類清空顯示 `分類不可空白`、upload mode 未選檔顯示 `請選擇 zip 或 SKILL.md`、a11y attributes。 |
| S197-T04 DESIGN 必填表單規則同步 | PASS | `docs/grimo/ui/DESIGN.md` 已記錄 Publish/Edit required mark、inline error、`aria-invalid` / `aria-describedby`、required color 與 category/risk palette 分離。 |
| S197-T05 Publish file mode required error page contract | PASS | `PublishPage.test.tsx` 覆蓋 `/publish` file mode 未選檔顯示 `請選擇 zip 或 SKILL.md`、`publish-file-error` a11y contract、以及 `.txt` 副檔名錯誤優先。 |

### 7.2 Verification

```bash
cd frontend && npm test -- PublishPage SkillEditPage FileDropZone
```

PASS — 3 files / 48 tests.

```bash
cd frontend && npm test -- PublishPage
```

PASS — 1 file / 23 tests（含 S197-T05 QA fix）。

```bash
cd frontend && npm run verify
```

PASS — `eslint . --max-warnings 0` 與 `tsc -b` 都通過。

E2E：not required。S197 沒有新增 route、後端 API、credential injection、test seed endpoint 或真瀏覽器-only 行為；這次行為都能由 RTL DOM text / attributes 與 TypeScript/ESLint 驗證。

### 7.3 AC Results

| AC | Result | 證據 |
|---|---|---|
| AC-S197-1 | PASS | `PublishPage.test.tsx` 驗 `/publish` 必填 label 顯示 required mark，`版本號` 不顯示 required mark。 |
| AC-S197-2 | PASS | `PublishPage.test.tsx` 驗分類空白顯示 `請填寫分類`，且不呼叫 upload API。 |
| AC-S197-3 | PASS | `FileDropZone.test.tsx` 驗 required error 與副檔名錯誤優先順序；`PublishPage.test.tsx` 驗 `/publish` file mode 也會顯示 `請選擇 zip 或 SKILL.md`。 |
| AC-S197-4 | PASS | `PublishPage.test.tsx` 驗 text mode 空白顯示 `請貼上 SKILL.md 內容`，frontmatter 缺 `description` 仍顯示原錯誤。 |
| AC-S197-5 | PASS | `PublishPage.test.tsx` 驗 `版本號` optional，空白時 FormData 不含 `version`。 |
| AC-S197-6 | PASS | `SkillEditPage.test.tsx` 驗 edit page 分類與 upload mode 缺欄原因。 |
| AC-S197-7 | PASS | `PublishPage.test.tsx`、`FileDropZone.test.tsx`、`SkillEditPage.test.tsx` 驗 `aria-invalid` / `aria-describedby` 與 required mark accessible text。 |
| AC-S197-8 | PASS | `docs/grimo/ui/DESIGN.md` 已同步 required field rule，並保留 required color 與 category/risk palette 分離。 |

### 7.4 Next Step

下一步應執行 `$shipping-release S197`，歸檔 spec、清除 S197 task file、更新 changelog / roadmap 並建立 release tag。

### 7.5 Independent QA Review（2026-05-19）

Verdict：REJECT-FIX。自動驗證全綠，但 AC-S197-3 的 `/publish` 實際頁面路徑沒有被滿足。

| Layer | Result | Detail |
|---|---|---|
| Automated tests | PASS | `cd frontend && npm test -- PublishPage SkillEditPage FileDropZone && npm run verify` → 3 files / 46 tests PASS，ESLint + TypeScript PASS。 |
| Full verify | PASS | `./scripts/verify-all.sh` → V01=PASS V02=INFO V03=PASS V04=PASS V05=PASS V06=PASS V07=PASS V08a=PASS V08b=PASS；`Verdict: ✅ all CRITICAL passed; exit=0`。 |
| Manual verification | N/A | S197 沒有人工操作才可驗的外部流程；缺口是可由 component/page test 補上的 DOM 行為。 |
| Testability gate | BLOCKED | AC-S197-3 目前只有 `FileDropZone` component test，沒有 `/publish` page-level test 證明 file mode 未選檔時使用者會看到 inline 缺檔案提示。 |

#### Findings

| Severity | AC | Finding | Evidence | Required fix |
|---|---|---|---|---|
| CRITICAL | AC-S197-3 | `/publish` file mode 未選檔時，實際 `PublishPage` 沒有把 required error 傳給 `FileDropZone`。使用者填完技能名稱與分類但未選檔，只看到 `發佈技能` disabled 與 required mark，沒有看到 `請選擇 zip 或 SKILL.md`。 | `frontend/src/pages/PublishPage.tsx:194` 只有 `<FileDropZone inputId="publish-file" onFileSelect={setFile} selectedFile={file} />`；`frontend/src/components/FileDropZone.test.tsx:87` 只證明 caller 傳入 `error` 時元件會顯示，但 `PublishPage` 沒有傳。 | 新增 QA fix task：`PublishPage` 在 file mode、submit attempt 或足以讓 disabled button 有欄位原因時傳入 `error="請選擇 zip 或 SKILL.md"`、`describedBy`、`errorId`，並在 `PublishPage.test.tsx` 加 AC-S197-3 page-level assertion。 |

Resolution：S197-T05 已完成並通過獨立 QA re-review；上述 CRITICAL finding 關閉。

### 7.6 Independent QA Re-Review（2026-05-19）

Verdict：PASS。S197-T05 補上 `/publish` page-level AC-S197-3 test 後，所有 S197 AC 都有可執行證據，完整 verify gate 全綠。

| Layer | Result | Detail |
|---|---|---|
| Automated tests | PASS | `cd frontend && npm test -- PublishPage SkillEditPage FileDropZone` → 3 files / 48 tests PASS；`cd frontend && npm run verify` → ESLint + TypeScript PASS。 |
| Full verify | PASS | `./scripts/verify-all.sh` → V01=PASS V02=INFO（LINE coverage 87.2%，covered=4866 / total=5580）V03=PASS V04=PASS V05=PASS V06=PASS V07=PASS V08a=PASS V08b=PASS；`Verdict: ✅ all CRITICAL passed; exit=0`。 |
| Manual verification | N/A | S197 只改既有 React form DOM text / attributes，沒有新增 route、後端 API、外部帳號、credential injection、test seed endpoint 或真瀏覽器-only 行為。 |
| Testability gate | CLEAR | AC-S197-1~8 都有 RTL test 或 docs/source inspection 證據；AC-S197-3 同時有 `FileDropZone` component test 與 `/publish` page-level test。 |

#### AC Evidence Check

| AC | QA status | Evidence |
|---|---|---|
| AC-S197-1 | VERIFIED | `PublishPage.test.tsx` 驗 required mark 與 `版本號` optional。 |
| AC-S197-2 | VERIFIED | `PublishPage.test.tsx` 驗分類空白顯示 `請填寫分類` 且不送 upload。 |
| AC-S197-3 | VERIFIED | `PublishPage.test.tsx` 驗 `/publish` file mode 顯示 `請選擇 zip 或 SKILL.md` 與 `publish-file-error`；`FileDropZone.test.tsx` 驗副檔名錯誤優先。 |
| AC-S197-4 | VERIFIED | `PublishPage.test.tsx` 驗 text mode 空白與 frontmatter 錯誤分開顯示。 |
| AC-S197-5 | VERIFIED | `PublishPage.test.tsx` 驗空白 `version` 不 append 到 FormData。 |
| AC-S197-6 | VERIFIED | `SkillEditPage.test.tsx` 驗分類與 upload mode 缺欄原因。 |
| AC-S197-7 | VERIFIED | `PublishPage.test.tsx`、`FileDropZone.test.tsx`、`SkillEditPage.test.tsx` 驗 `aria-invalid` / `aria-describedby`。 |
| AC-S197-8 | VERIFIED | `docs/grimo/ui/DESIGN.md` 記錄 required mark、inline error、a11y attribute 規則。 |

#### Code Quality Check

- `PublishPage.tsx` 只串接既有 `FileDropZone` props，沒有新增 package、後端 API 或 FormData 欄位。
- `PublishPage.test.tsx` 補兩個 page-level 情境：未選檔顯示 required error、`.txt` 副檔名錯誤優先。
- `rg -n "TODO|FIXME" frontend/src/pages/PublishPage.tsx frontend/src/pages/PublishPage.test.tsx frontend/src/components/FileDropZone.tsx frontend/src/components/FileDropZone.test.tsx frontend/src/pages/SkillEditPage.tsx frontend/src/pages/SkillEditPage.test.tsx` → no matches。
