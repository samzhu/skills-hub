# S159b: Category storage normalize — V19 lowercase migration + CHECK constraint

> Spec: S159b | Size: S(5) | Status: 📐 in-design
> Date: 2026-05-09
> Origin: 拆自 S159 META §2.1 — query API hardening

---

## 1. Goal

**一句話：** 把 `skills.category` 從「大小寫不一致」（"Testing" vs "testing" vs "TESTING"）統一為 lowercase，加 DB CHECK constraint 防止未來 drift，前端用 `capitalize()` 顯示。

**為什麼重要：**
- query `?category=Testing` 找不到 `category="testing"` 的 row（case-sensitive 比對）
- AC list 看到「Testing」「testing」兩個 category，user 困惑
- 既有 V1 schema 沒 CHECK constraint，aggregate write path 也沒 normalize → 任何 caller 帶任意 case 都 INSERT 進 DB

**非目標：**
- 不改 category enum / allowlist 機制（本 spec 維持自由 string）
- 不做 category rename UI（admin 手動 SQL 改即可）

---

## 2. Approach

### 2.1 現況

`Skill.create(CreateSkillCommand cmd)` 直接 `this.category = cmd.category()` — 無 normalize。`SkillQueryService.search()` raw SQL `WHERE category = :category` — case-sensitive 比對。

DB schema：`category VARCHAR(100)` — 無 CHECK constraint。

既存 row（dev DB sample）：可能 mix 大小寫（待 migrate 階段查 distinct）。

### 2.2 設計

**3 層防線：**

| 層 | 動作 |
|----|------|
| **DB** | V19 migration: `UPDATE skills SET category = lower(category)` + 加 `CHECK (category = lower(category))` constraint |
| **Aggregate (write)** | `Skill.create()` / future `Skill.update()` 加 `category = cmd.category().toLowerCase().trim()` invariant |
| **Frontend (display)** | `<Capitalize>` helper 或 CSS `text-transform: capitalize` 在 `SkillCard / Filter chip` 顯「Testing」（不存大寫） |

**API contract：** query param `?category=` 也 lowercase normalize（controller 起手 `category.toLowerCase()`），讓 caller 大小寫無感。

### 2.3 Migration 細節

```sql
-- V19__normalize_skill_category.sql
UPDATE skills SET category = lower(trim(category)) WHERE category IS NOT NULL;
ALTER TABLE skills ADD CONSTRAINT skills_category_lowercase
  CHECK (category IS NULL OR category = lower(category));
```

**風險：** 既存 distinct value 內可能含 leading/trailing spaces 或 special chars — `trim` 也加進來。

### 2.4 Frontend 顯示

```tsx
// utility
const capitalize = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);

// SkillCard / FilterChip
<Badge>{capitalize(skill.category)}</Badge>  // "testing" → "Testing"
```

或更乾淨：CSS `text-transform: capitalize`（純 CSS，無 JS overhead）— 但對 multi-word category（"web-dev"）會大寫每個 word，不適用。所以走 helper 函式。

---

## 3. Acceptance Criteria

```
AC-1: V19 migration 把既存 row category 全 lowercase
  Given 既存 skills 表 row category mix 大小寫（"Testing", "testing", "TESTING"）
  When V19 migrate
  Then 所有 row category 都 lowercase + trim 後值
  And distinct(category) 行數可能變少（合併 case-only 重複）

AC-2: V19 CHECK constraint 防未來 drift
  Given V19 套用後
  When `INSERT INTO skills (..., category) VALUES (..., 'Testing')`（直接 SQL，繞 aggregate）
  Then 拒收（CHECK 違規）

AC-3: Skill.create() write-side normalize
  Given Alice 透過 API publish 帶 category="Testing"
  When SkillCommandService.uploadSkill()
  Then DB skills.category = "testing"

AC-4: Query controller normalize input
  Given GET /api/v1/skills?category=Testing
  When SkillQueryController
  Then 內部 search 查 category="testing" → 回正確 row（不漏 case 不一致的）

AC-5: Frontend SkillCard 顯首字母大寫
  Given skill.category="testing"
  When render SkillCard
  Then 顯「Testing」（capitalize helper）

AC-6: 既有 test suite 不破
  Given V19 migrate + write/read normalize
  When 跑既有 ./gradlew test + npm test
  Then 全綠（test fixture 若 hardcode 大寫 category 須 update）
```

**驗證指令：** `cd backend && ./gradlew test` + `cd frontend && npm test`

---

## 4. Files to Change

### Backend

| 檔案 | 變動 |
|------|------|
| `backend/src/main/resources/db/migration/V19__normalize_skill_category.sql` | **新增** — UPDATE lowercase + CHECK constraint |
| `backend/src/main/java/.../skill/domain/Skill.java` | `create()` invariant 加 category normalize |
| `backend/src/main/java/.../skill/command/SkillCommandService.java` | publish/republish path 確認走 normalize（實際透過 Skill.create 入口）|
| `backend/src/main/java/.../skill/query/SkillQueryController.java` | `@RequestParam("category")` 起手 toLowerCase |
| `backend/src/test/java/.../skill/V19MigrationTest.java` | **新增** — Flyway clean migrate verify |
| 既有 fixture / test 內 hardcode 大寫 category | sweep update |

### Frontend

| 檔案 | 變動 |
|------|------|
| `frontend/src/lib/text.ts`（或既有 utils）| 加 `capitalize()` helper |
| `frontend/src/components/SkillCard.tsx` | category Badge 改套 helper |
| `frontend/src/components/v2/FilterChip.tsx` | 同上 |
| `frontend/src/pages/HomePage.tsx` filter | 顯示文字套 helper |

---

## 5. Test Plan

### 5.1 自動化

| AC | 驗證方式 |
|----|---------|
| AC-1 | `V19MigrationTest` Flyway clean migrate verify distinct(category) 全 lowercase |
| AC-2 | `V19MigrationTest` 嘗試 INSERT 大寫 → expect SQLException |
| AC-3 | `SkillCommandServiceTest` 帶大寫 category → 驗 DB row 是 lowercase |
| AC-4 | `SkillQueryControllerTest` `param("category", "Testing")` → 200 + 正確 row |
| AC-5 | `SkillCard.test.tsx` mock category="testing" → render 含 "Testing" |
| AC-6 | sweep + run all tests |

### 5.2 手動

無 — 自動化覆蓋足夠。

---

## 6. 風險

| 風險 | 緩解 |
|------|------|
| V19 跑時 row 含 unicode special char（'a' vs 'Ａ' fullwidth）| `lower()` 對 unicode 正常處理；POC 可先 SELECT distinct 檢查既存資料 |
| Frontend 既有 hardcode "Testing" 大寫 category | sweep grep 找 — 預期僅 fixture 有 |
| API caller 已習慣大寫 category | 加 controller normalize 後 backwards compatible（大寫 / 小寫 input 都 work）|
