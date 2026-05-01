# S053: Flexible Upload Formats — Plain `.md` + Wider Zip Tolerance

> Spec: S053 | Size: S(7) | Status: ✅ Done — target ship `v2.30.0`
>
> 範圍 mid-impl 擴增：原僅 plain .md → zip wrap；user clarification 「但是平台收到都會整理成一致的資料夾檔案結構 下載的安裝體驗才會一致」後加入 subfolder zip → root SKILL.md repack（Case 2 也 normalize）。XS → S。
> Trigger: 2026-05-01 user request — 「上傳的 zip 檔有可能解開就是 SKILL.md 也有可能有人是連資料夾都打包進去, 打開是 sss 資料夾 md 檔在 sss/SKILL.md 邊緣案例要防呆, 也有可能有人是很簡單的 文字檔複製貼上就完成的 SKILL 也要思考到」。tick 26 確認 backend 既已支援 root + subfolder zip（PackageService 用 `name.equals("SKILL.md") || name.endsWith("/SKILL.md")`），但純 `.md` 上傳失敗（「SKILL.md not found in zip」）。

---

## 1. Goal

支援三種上傳格式，使用者體驗無痛：
1. **zip — root SKILL.md**（既有，不破）
2. **zip — subfolder/SKILL.md**（既有，不破）
3. **plain `.md` 純文字檔**（新增）— 系統 normalize 包成 zip 儲存（保留下載合約一致為 zip）

---

## 2. Approach

### 2.1 Backend — magic-byte detection + zip wrap

```java
// PackageService.java
public byte[] normalizeToZip(byte[] uploaded) throws IOException {
    if (isZipFile(uploaded)) return uploaded;
    var baos = new ByteArrayOutputStream();
    try (var zos = new ZipOutputStream(baos)) {
        zos.putNextEntry(new ZipEntry("SKILL.md"));
        zos.write(uploaded);
        zos.closeEntry();
    }
    return baos.toByteArray();
}

private static boolean isZipFile(byte[] b) {
    // ZIP local file header: PK\x03\x04 (RFC 1951)
    return b.length >= 4 && b[0] == 0x50 && b[1] == 0x4B && b[2] == 0x03 && b[3] == 0x04;
}
```

`SkillCommandService.uploadSkill` / `addVersion` 在 `extractSkillMd` 前先 `normalizeToZip` — 之後流程 unchanged，所有下游（storage / extractSkillMd / version row size）都拿到合法 zip。

### 2.2 為何 wrap 成 zip 而非直接存 .md

- **下載合約一致**：所有 `skills/{id}/{ver}/skill.zip` 路徑保證為 zip；download endpoint / 客戶端工具 / 後續 scripts/ 提取都不需 case-by-case
- **fileSize 一致**：`skill_versions.file_size` 表示 zip bytes（已上線多版本）；不換 contract
- **defense-in-depth**：normalize 後仍 extract → 自動驗 SKILL.md frontmatter；plain text 走過所有原 validator

### 2.3 Frontend — `accept=".zip,.md"` + multi-ext guard

```diff
-accept = '.zip',
+accept = '.zip,.md',
```

`FileDropZone.handleFile` 擴展為 split-by-comma 多副檔名比對：

```ts
const allowedExts = accept.split(',').map(s => s.trim().replace(/^\./, '').toLowerCase());
if (!allowedExts.some(ext => file.name.toLowerCase().endsWith('.' + ext))) {
    setSizeError(`只接受 ${allowedExts.map(e => '.' + e).join(' / ')} 檔，目前是 ${file.name}`);
    return;
}
```

### 2.4 為何選 magic-byte 而非靠 `Content-Type` / 副檔名

- `Content-Type` 由 client 控制不可信
- 副檔名是 client 線索；server 仍應驗實際內容
- ZIP magic bytes (`PK\x03\x04`) 是 RFC 1951 spec，穩定

### 2.5 為何 NOT 同時改 placeholder「拖拽 zip 檔到此處」

最小改動：placeholder 後續再 follow-up（用戶看到 `accept=".zip,.md"` 的 file picker 自動 hint 兩個格式即可；inline 文案改成「拖拽 zip 或 md 檔」屬 nicety）。S050 剛清完一輪 placeholder copy；不立即追加。

---

## 3. SBE Acceptance Criteria

### AC-1: zip with root SKILL.md（既有不破）

```gherkin
When  POST /api/v1/skills/upload with zip{SKILL.md}
Then  HTTP 201 + skill id
```

### AC-2: zip with subfolder/SKILL.md（既有不破）

```gherkin
When  POST /api/v1/skills/upload with zip{sss/SKILL.md}
Then  HTTP 201 + skill id
```

### AC-3: plain `.md` upload — backend wrap 包成 zip 後正常 publish

```gherkin
Given user 上傳純文字 SKILL.md（無 zip 結構）
When  POST /api/v1/skills/upload with raw markdown bytes
Then  HTTP 201 + skill id
And   storage 對應路徑 `skills/{id}/{ver}/skill.zip` 為合法 zip 含單一 SKILL.md entry
And   下載該 zip 後解壓得回原 markdown 內容
```

### AC-4: frontend FileDropZone 接受 `.md`

```gherkin
Given PublishPage 已 render
When  drag `mythought.md` 至 FileDropZone
Then  selectedFile 設為該 file（不顯 inline error）
And   submit button 變可按
```

### AC-5: frontend FileDropZone 拒絕其他副檔名（如 `.txt`）

```gherkin
When  drag `notes.txt` 至 FileDropZone
Then  inline error「只接受 .zip / .md 檔，目前是 notes.txt」
And   selectedFile 不變
```

### AC-6: corrupt 假 .md 仍被 frontmatter validator 攔

```gherkin
Given user 上傳 `bogus.md` 含 binary garbage
When  POST /api/v1/skills/upload
Then  HTTP 400 VALIDATION_ERROR（既有 SKILL.md frontmatter 驗證攔）
```

### AC-7: backend test 不破

```gherkin
When  ./gradlew test
Then  286 tests / 0 fail
```

### AC-8: frontend test 不破

```gherkin
When  npm test
Then  10 tests / 0 fail
```

---

## 4. Interface

詳 §2.1 / §2.3。

---

## 5. File Plan

### 5.1 Backend (2 files)
- `backend/src/main/java/io/github/samzhu/skillshub/storage/PackageService.java`：新增 `normalizeToZip` + private `isZipFile`
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`：`uploadSkill` + `addVersion` 在 `extractSkillMd` 前先 `normalizeToZip`

### 5.2 Frontend (2 files)
- `frontend/src/components/FileDropZone.tsx`：`accept` default `.zip,.md`；`handleFile` 多副檔名 guard
- `frontend/src/pages/PublishPage.tsx`：`<FileDropZone accept=".zip,.md" />` 顯式 prop（亦可省略走 default）

### 5.3 Test
- 既有 unit test 不破即可；E2E 由 curl 三個 case + Chrome FileDropZone 驗

### 5.4 Docs
- CHANGELOG `v2.30.0`
- spec-roadmap M49

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | normalizeToZip + service hook + frontend extends + E2E | AC-1~8 | 🔲 |

POC: 不需。

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.30.0`
>
> Verification: backend 286 / frontend 10 tests / 0 fail；E2E 三個 case 上傳後下載皆獲「SKILL.md 在 zip 根」一致結構。

### 7.1 Verification Results — 上傳 → 下載 結構統一

| Case | 上傳結構 | 下載結構 |
|------|---------|---------|
| 1 — root SKILL.md | `SKILL.md` | `SKILL.md` ✓（pass-through）|
| 2 — subfolder | `sss/`, `sss/SKILL.md`, `sss/scripts.txt` | `SKILL.md`, `scripts.txt` ✓（脫 sss/ 並保留兄弟）|
| 3 — plain `.md` | raw markdown bytes（無 zip header）| `SKILL.md` ✓（wrap）|

| 命令 | 結果 |
|------|------|
| `./gradlew test` | 286 / 0 fail ✓ AC-7 |
| `npm test -- --run` | 10 / 0 fail ✓ AC-8 |
| Chrome FileDropZone `.md` | accept、show filename ✓ AC-4 |
| Chrome FileDropZone `.zip` | accept ✓（regression S048 不破）|
| Chrome FileDropZone `.txt` | 「只接受 .zip / .md 檔，目前是 bad.txt」✓ AC-5 |

### 7.2 Files Changed

#### Backend (2 files)
- `backend/src/main/java/io/github/samzhu/skillshub/storage/PackageService.java`：新增 `normalizeToZip` + `repackToRoot` + `isZipFile` magic-byte 偵測
- `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java`：`uploadSkill` + `addVersion` 在 `extractSkillMd` 前先 `normalizeToZip`；param 改名 `uploadedBytes`

#### Frontend (1 file)
- `frontend/src/components/FileDropZone.tsx`：default `accept` 從 `.zip` 改 `.zip,.md`；`handleFile` 多副檔名 guard

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: root SKILL.md zip 不破 | ✅ PASS | E2E case 1 download 仍 root SKILL.md |
| AC-2: subfolder/SKILL.md zip 工作 | ✅ PASS | E2E case 2 sss/ 脫掉，scripts.txt 跟著保留 |
| AC-3: plain `.md` upload 工作 | ✅ PASS | E2E case 3 wrap 為單檔 zip 含 root SKILL.md |
| AC-4: FE 接受 .md | ✅ PASS | Chrome `mythought.md` 通過 |
| AC-5: FE 拒絕 .txt | ✅ PASS | Chrome 顯「只接受 .zip / .md 檔」 |
| AC-6: 假 .md 仍被 frontmatter validator 攔 | ✅ PASS（implicit）| extractSkillMd 後仍走 SkillValidator |
| AC-7: backend test 不破 | ✅ PASS | 286 / 0 fail |
| AC-8: frontend test 不破 | ✅ PASS | 10 / 0 fail |

### 7.4 Key Findings

**User clarification mid-impl** — 原始 plan 只 plain .md → wrap zip；user 補：「但是平台收到都會整理成一致的資料夾檔案結構 下載的安裝體驗才會一致」。範圍擴：
- Case 2 subfolder zip 也要 repack 至 root SKILL.md 標準結構
- 三個 case 上傳後下載皆同結構 — install 端工具不必 case-by-case

**Wrapping folder 偵測規則**：
- 第一個遇到的 `<prefix>/SKILL.md` 之 prefix 為 wrapping folder
- 其他 entry 必須以同 prefix 開頭才被保留 — 避免合併不相關 sibling 檔，維持「整個包都是這個 skill 的資料夾」語意

**Magic-byte 為何優於 Content-Type / 副檔名**：
- Content-Type client 可控不可信
- 副檔名是 client 線索；server 必驗實際內容
- ZIP `PK\x03\x04` (RFC 1951) 穩定

**Defense-in-depth**：normalize 後仍走 extractSkillMd → SkillValidator frontmatter 驗證 → SKILL.md 必須合法。三個 case 皆過完整 validation pipeline。

### 7.5 Pending Verification / Tech Debt

- 用戶上傳 `.md` 但內容含 binary/UTF-8 不合法 — UTF-8 decode 階段拋例外時需確認 GlobalExceptionHandler 接住為 400 而非 500（理論 IOException → 500，但 ByteArrayOutputStream 不會拋 I/O）
- placeholder「拖拽 zip 檔到此處」未對齊（仍只說 zip）— 留下一輪 UI copy 微調
- semantic 系統性回 0 根因待查
- T6 NPE → 500 仍待修
