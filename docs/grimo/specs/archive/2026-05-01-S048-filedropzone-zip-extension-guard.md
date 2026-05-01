# S048: FileDropZone — Reject Non-`.zip` Files Client-Side

> Spec: S048 | Size: XS(5) | Status: ✅ Done — target ship `v2.25.0`
> Trigger: 2026-05-01 /loop tick 23 Chrome E2E — `FileDropZone` `accept=".zip"` 只 hint file picker；drag-drop 任何檔案（如 `malicious.txt`）皆被接受、submit 按鈕變可按、user 須等到後端 400 才知錯。先前 S037 已加 size guard，本 spec 補擴展名 guard 對齊。

---

## 1. Goal

`FileDropZone.handleFile` 加擴展名 guard：non-`.zip` 檔案 set inline error、不呼叫 `onFileSelect`。對齊 S037 size guard 模式（同 funnel point；drag + click 兩條 path 共用）。

```diff
 const handleFile = (file: File) => {
+   const acceptedExt = accept.replace(/^\./, '').toLowerCase();  // ".zip" → "zip"
+   if (!file.name.toLowerCase().endsWith('.' + acceptedExt)) {
+     setError(`只接受 .${acceptedExt} 檔，目前是 ${file.name}`);
+     return;
+   }
    if (file.size > maxSizeBytes) { ... }
    ...
 }
```

---

## 2. Approach

### 2.1 Code diff

```diff
 const [sizeError, setSizeError] = useState<string | null>(null)
+ // S048: 擴展名與 size 共用同一條 inline error 訊息槽（user 一次只看到一個錯）
+ // — 命名改 `error` 比 `sizeError` 通用
 const handleFile = (file: File) => {
+   // 從 accept prop 計算允許副檔名（去 leading dot, lowercase）
+   const ext = accept.replace(/^\./, '').toLowerCase();
+   if (!file.name.toLowerCase().endsWith('.' + ext)) {
+     setSizeError(`只接受 .${ext} 檔，目前是 ${file.name}`);
+     return;
+   }
    if (file.size > maxSizeBytes) {
      const limitMb = ...
      setSizeError(`檔案大小 ${fileMb} MB 超過 ${limitMb} MB 限制`)
      return
    }
    setSizeError(null)
    onFileSelect(file)
 }
```

### 2.2 為何沿用 `sizeError` state name

最小改動：state 命名不換（避免 mass rename）；inline 錯誤訊息文字變動是足夠語意（user 看到「只接受 .zip 檔」而非「檔案大小超限」）。`accept` prop 預設 `.zip`，error 文字動態反映副檔名。

### 2.3 為何 NOT 加 MIME type 檢查（`file.type === 'application/zip'`）

`File.type` 由瀏覽器 OS hint 決定 — 同一個 zip 檔在不同 OS 可能回 `application/zip` / `application/x-zip-compressed` / 空字串。擴展名 check 是更穩定的客戶端 first-line guard；後端仍會嚴格驗 zip magic bytes。

### 2.4 為何 NOT 顯 client error code （如 `INVALID_FILE_TYPE`）

inline 文字訊息對 user 已足；不做 i18n Record map（這層發生前都還沒有 backend ApiError）。

---

## 3. SBE Acceptance Criteria

### AC-1: 拖拽 .txt 檔顯示拒絕訊息

```gherkin
When  user 拖拽 `malicious.txt` 至 FileDropZone
Then  顯示 inline error「只接受 .zip 檔，目前是 malicious.txt」
And   `selectedFile` 仍為 null
And   submit 按鈕仍 disabled
```

### AC-2: 拖拽 .zip 檔正常通過

```gherkin
When  user 拖拽 `skill.zip` 至 FileDropZone
Then  不顯 error
And   `selectedFile` 設為該 file
And   submit 按鈕變可按
```

### AC-3: .ZIP（大寫）也接受（case-insensitive）

```gherkin
When  user 選 `Skill.ZIP`
Then  通過 guard，正常選取
```

### AC-4: 超大 zip 仍走原 size guard（既有 S037 不破）

```gherkin
When  user 拖拽 12MB 的 large.zip
Then  顯示「檔案大小 12.0 MB 超過 10 MB 限制」
And   `selectedFile` 不變
```

### AC-5: 既有 frontend 測試不破

```gherkin
When  npm test
Then  既有 vitest 全綠
```

---

## 4. Interface

詳 §2.1。

---

## 5. File Plan

### 5.1 Frontend (1 file)
- `frontend/src/components/FileDropZone.tsx`：`handleFile` 加擴展名 guard

### 5.2 Test
- 既有 frontend test 不破即可；E2E 由 Chrome 手測（4 個 AC）

### 5.3 Docs
- CHANGELOG `v2.25.0`
- spec-roadmap M44

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | extension guard + Chrome E2E | AC-1~5 | 🔲 |

POC: 不需。

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.25.0`
>
> Verification: vitest 10 tests / 0 fail；Chrome E2E 4 個 AC — `.txt` 拒絕 + inline error / `.zip` 通過 / `.ZIP` 大寫接受 / 11MB `.zip` 仍走 size guard。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `npm test -- --run` | 2 files / 10 tests passed |
| Chrome 注入 `malicious.txt` | `hasZipError=true`、`submitDisabled=true`、selectedFile 不變 ✓ AC-1 |
| Chrome 注入 `good.zip` | 無 error、`showsZipName=true` ✓ AC-2 |
| Chrome 注入 `Skill.ZIP`（大寫）| 無 error，正常選取 ✓ AC-3 |
| Chrome 注入 11MB `large.zip` | size error「超過 10 MB 限制」✓ AC-4（既有 S037 不破）|
| 既有 vitest | 10 / 0 fail ✓ AC-5 |

### 7.2 Files Changed

#### Frontend (1 file)
- `frontend/src/components/FileDropZone.tsx`：`handleFile` 在 size guard 前加 extension guard；error 文字動態反映 `accept` prop

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: `.txt` 拒絕 | ✅ PASS | inline「只接受 .zip 檔，目前是 malicious.txt」+ 不選 |
| AC-2: `.zip` 通過 | ✅ PASS | selectedFile 設、submit 啟用 |
| AC-3: `.ZIP` 大寫接受 | ✅ PASS | toLowerCase compare 正確 |
| AC-4: 大檔仍走 size guard | ✅ PASS | size error 正常觸發 |
| AC-5: 既有測試不破 | ✅ PASS | vitest 10 / 0 fail |

### 7.4 Key Findings

**Discovery context**: tick 23 Chrome E2E 走 PublishPage — `accept=".zip"` 在 input 上只 hint OS file picker；drag-drop bypass，user 可拖任何檔（含 `.txt`）。後端會擋 zip magic bytes，但 user 浪費一輪 round-trip 才知。S037 已在同一 funnel point 加 size guard，補擴展名 guard 對齊模式。

**Fix design rationale**:
- 與 S037 size guard 同 funnel point（`handleFile`）— drag + click 路徑共用
- `accept` prop 動態取副檔名（不 hardcode `.zip`）— 支援未來其他 accept 配置
- case-insensitive — `Skill.ZIP` / `skill.zip` 一致
- 不查 `File.type` MIME — OS 差異不可靠（同 zip 在 macOS 是 `application/zip` Linux 可能空字串）
- 後端仍嚴格驗 zip magic bytes（defense-in-depth）

### 7.5 Pending Verification / Tech Debt

- 搜尋框 placeholder「名稱或描述」未對齊 S043 仍待修
- semantic 系統性回 0 根因待查
- analytics「本週新增」算法待驗（25 = total may be plausible 也可能 bug）
- PublishPage submit button disabled 但無 hint「請先選 zip」— 可加 tooltip / aria-disabled，但目前已有 inline placeholder「拖拽 zip 檔到此處」算 self-explanatory
