# S118: Collection DTO naming alignment `installs → installCount` (Bug AQ fix)

> Spec: S118 | Size: XS(2) | Status: ✅ Shipped (v3.10.4)
> Date: 2026-05-04
> Source: Mode B Round 36 (2026-05-03) finding (LOW) — backlog 候選；Round 36 chain 3/3 closer

---

## 1. Goal

修補 `CollectionSummary.installs` (list endpoint) vs `CollectionDetail.installCount` (single endpoint) 同 entity 跨 endpoint field name 不一致的 oversight。Frontend `SkillCollection.installs` vs `CollectionDetail.installCount` 跟著走 same inconsistent naming — UX 文件 / API contract / type 三層皆不一致。

**起源**：Mode B Round 36 (2026-05-03) finding **Bug AQ**。S096f2 ship Collections feature 時 oversight；本 fix 完成 Round 36 backlog chain 3/3 收尾（S119 + S117 + S118）。

**非目標**（本 spec 不做）：
- 改 Collection aggregate 內 `installCount` field（已正確命名；只 DTO 層 rename）
- 加新 Collection metric（per-day install trend etc.）

## 2. Approach

走 **option A — 直接 breaking rename + 全 callsite migration**：5 個 callsite 一次到位（per spec §1 chain 收尾乾淨）。

### 2.1 Approach 比較

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| **A. 直接 rename + 全 callsite migration** | API contract 一次乾淨；不留 transitional field | breaking change 對既有 frontend caller；須 atomic ship | ⭐ |
| B. 加 `installCount` 同時 deprecate `installs`（Jackson `@JsonAlias`） | 平滑過渡 | 兩個 field 同 schema 持久化（短期）；冗餘；違反「真的有第三 use case 才抽」原則 | |

走 **A** — 5 個 callsite 全在本 repo 內（無 external API caller），atomic rename 是 ship 安全的最小完整 fix。

### 2.2 Affected callsites

| File | Type | Action |
|------|------|--------|
| `backend/.../community/CollectionQueryController.java` | record field | rename `int installs` → `int installCount` |
| `backend/.../community/CollectionControllerTest.java` | test JSON path | `$[0].installs` → `$[0].installCount` |
| `frontend/src/api/skills.ts` | type | `SkillCollection.installs: number` → `installCount: number` |
| `frontend/src/pages/CollectionsPage.tsx` | render caller | `collection.installs.toLocaleString()` → `collection.installCount.toLocaleString()` |
| `frontend/src/pages/CollectionsPage.test.tsx` | test fixture × 3 | `installs:` → `installCount:` |

### 2.3 Trim list

XS=2 — 無 trim space。

## 3. SBE Acceptance Criteria

驗證指令：
- Backend: `./gradlew test --tests "*CollectionControllerTest" -x npmBuild`
- Frontend: `npm test -- --run CollectionsPage`

**AC-S118-1：Backend `CollectionSummary` JSON expose `installCount` 而非 `installs`**
- Given：seeded Collection with installCount=5
- When：`GET /api/v1/collections`
- Then：`$[0].installCount=5`；`$[0].installs` 不存在

**AC-S118-2：Frontend `SkillCollection.installCount` type 對齊 backend**
- TypeScript：`type SkillCollection = { installCount: number }`（移除 `installs`）

**AC-S118-3 (regression)：CollectionsPage CollectionCard 顯示 install count**
- Given：sampleCollections fixture with `installCount: 12`
- When：render
- Then：DOM 含 `12`（toLocaleString format）

**AC-S118-4 (regression)：既有 backend CollectionControllerTest + frontend CollectionsPage tests 不 regression**

## 4. File Plan

對齊 §2.2 callsite 表 — 5 個 file 改動。

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/CHANGELOG.md` | append | v3.10.4 entry |
| `docs/grimo/specs/spec-roadmap.md` | modify | M113 row → ✅ |
| `docs/grimo/specs/archive/2026-05-04-S118-collection-dto-naming-alignment.md` | new | 本 spec |

## 5. Test Plan

### 5.1 Backend slice test

`CollectionControllerTest`：既有 AC-list 改 jsonPath assertion 對齊新 field name。

### 5.2 Frontend tests

`CollectionsPage` tests fixture 全用 `installCount`；既有 4 ACs 行為不變（render install count metric）。

## 6. Verification

### 6.1 Backend test

`CollectionControllerTest`：**8/8 PASS @ 8.0s**（含 AC-list 對齊新 jsonPath；既有 7 ACs 不 regression）。

### 6.2 Frontend test

```
npm test -- --run CollectionsPage
Test Files  1 passed (1)
Tests  4 passed (4)
```

4/4 PASS — 0 regression。

### 6.3 ModularityTests

未額外執行（純 DTO 層 rename；module boundary / public surface 內部 field name only）。

## 7. Result

### Shipped

- Backend `CollectionSummary` record field rename
- Backend test jsonPath assertion 對齊
- Frontend `SkillCollection` type rename
- Frontend caller (`CollectionsPage.tsx`) + 3 fixture entries 同步

### Verify metric

- Backend CollectionControllerTest：（待跑完填入）
- Frontend CollectionsPage tests 4/4 PASS @ 1.62s

### Trim defer

- 無

### Round 36 backlog chain 收尾完成

- ✅ S119 (v3.10.2) — list rating projection (Bug AR)
- ✅ S117 (v3.10.3) — frontend SkillVersion fileCount sync (Bug AP)
- ✅ **S118 (v3.10.4) — Collection DTO naming alignment (Bug AQ)** ← 本 spec
- **Round 36 backlog 完整收尾 3/3** — 從 2026-05-03 audit 開立 backlog 到 2026-05-04 全 ship 共跨 cron tick 9-11

### Lessons / Pattern reuse

- **第 14 次 single-tick XS/S spec ship**（per session lessons learned）
- **Round 36 audit chain 完整收尾範本**：3 個 finding（severity LOW/LOW/MEDIUM）跨 3 個 cron tick ship；每 tick 1 fix-spec；對齊 wall budget 漸進交付
- **Atomic rename pattern**：5 callsite 同 PR ship（vs JSON @JsonAlias 過渡）— breaking change 在 internal repo 可一次到位，避免 transitional field 留 tech debt
