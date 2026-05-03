# S117: Frontend SkillVersion type sync `fileCount` (Bug AP fix)

> Spec: S117 | Size: XS(1) | Status: ✅ Shipped (v3.10.3)
> Date: 2026-05-04
> Source: Mode B Round 36 (2026-05-03) finding (LOW) — backlog 候選

---

## 1. Goal

修補 frontend `SkillVersion` interface 缺 `fileCount` field 致 VersionList 顯版本歷史時無「N 個檔案」資訊。Backend `SkillVersion.fileCount` getter 自動序列化 expose（per S098a3-2 ship；v3.8.1）但 frontend type 漏同步。

**起源**：Mode B Round 36 (2026-05-03) finding **Bug AP**。S098a3-2 ship 只把 fileCount expose 在 `/bundle-info` endpoint frontend `BundleInfo` type，漏了同步 `SkillVersion` type — 版本列表 UX 缺檔案計數資訊。

**非目標**（本 spec 不做）：
- 改 backend SkillVersion.fileCount field 設計
- 重構 VersionList 顯示密度（per-row 多 metric 整理）

## 2. Approach

最小 diff：**frontend type 加欄位 + VersionList 顯示**：
1. `frontend/src/types/skill.ts` SkillVersion 加 `fileCount: number`
2. `VersionList.tsx` 顯示「{N} 個檔案」於 fileSize 與 publishedAt 之間
3. **Graceful policy for fileCount=0**：pre-S098a3-2 上傳的歷史 row 為 fallback signal `0`；UI 隱藏該欄避免「0 個檔案」誤導
4. Test fixture (`v` factory) 加 fileCount=3 default + 2 個新 ACs（fileCount>0 顯示 / fileCount=0 隱藏）

### 2.1 Trim list

XS=1 — 無 trim space。

## 3. SBE Acceptance Criteria

驗證指令：`cd frontend && npm test -- --run VersionList`

**AC-S117-1：fileCount > 0 顯示「N 個檔案」**
- Given：SkillVersion fixture with `fileCount: 5`
- When：render `<VersionList versions={[fixture]}/>`
- Then：DOM 含「5 個檔案」文字

**AC-S117-2：fileCount=0 隱藏該欄（避免誤導）**
- Given：SkillVersion fixture with `fileCount: 0`
- When：render `<VersionList versions={[fixture]}/>`
- Then：DOM **不含**任何 `*個檔案` 文字

**AC-S117-3 (regression)：既有 5 ACs（empty / single / multi / latest badge / download link）不 regression**

## 4. File Plan

### Frontend (production)

| File | Action | Description |
|------|--------|-------------|
| `frontend/src/types/skill.ts` | modify | SkillVersion interface 加 `fileCount: number` field + JSDoc 說明 fallback signal `0` |
| `frontend/src/components/VersionList.tsx` | modify | row 內 fileSize 與 publishedAt 之間加 `{v.fileCount > 0 && <span>{v.fileCount} 個檔案</span>}` graceful conditional |

### Frontend (tests)

| File | Action | Description |
|------|--------|-------------|
| `frontend/src/components/VersionList.test.tsx` | modify | (1) `v` factory 加 `fileCount: 3` default；(2) 加 AC-S117-1（顯示）+ AC-S117-2（fileCount=0 graceful hide） |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/CHANGELOG.md` | append | v3.10.3 entry |
| `docs/grimo/specs/spec-roadmap.md` | modify | M112 row → ✅ |
| `docs/grimo/specs/archive/2026-05-04-S117-frontend-skillversion-filecount.md` | new | 本 spec |

## 5. Test Plan

`npm test -- --run VersionList` — 7 ACs（既有 5 + S117-1/-2 新加）。

## 6. Verification

### 6.1 VersionList tests

```
npm test -- --run VersionList
Test Files  1 passed (1)
Tests  7 passed (7)
Duration  803ms
```

### 6.2 Full frontend test suite regression

```
npm test -- --run
Test Files  41 passed (41)
Tests  195 passed (195)
```

195/195 PASS @ 41 files — 0 regression（既有 193 + S117-1/-2 新加 = 195）。

### 6.3 ModularityTests

N/A — 純 frontend type + UI 層改動。

## 7. Result

### Shipped

- `SkillVersion` type 加 `fileCount: number`（含 JSDoc fallback `0` 說明）
- `VersionList` 顯示「N 個檔案」+ graceful hide for fileCount=0
- VersionList tests 7 ACs（5 既驗 + 2 S117 新加）

### Verify metric

- VersionList tests 7/7 PASS @ 803ms
- Full suite：（待 6.2 填入）
- TypeScript：tsconfig.app.json 無 strict 設定但仍須對齊 type；fixture 顯式加 fileCount=3 default 表達意圖

### Trim defer

- 無

### Round 36 backlog 進度

- ✅ S119 (v3.10.2) — list rating projection
- ✅ **S117 (v3.10.3) — frontend SkillVersion fileCount sync**
- 📋 S118 — Collection DTO naming alignment（installs → installCount）

### Lessons / Pattern reuse

- **第 13 次 single-tick XS/S spec ship**（per session lessons learned）
- **Graceful fallback for projection columns 第 N 次採用**（fileCount=0 隱藏；對齊 S098a3-2 既驗 fallback signal pattern + S119 averageRating=0 path）
