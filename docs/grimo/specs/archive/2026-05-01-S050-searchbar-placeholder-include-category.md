# S050: SearchBar Placeholder — Include Category

> Spec: S050 | Size: XS(5) | Status: ✅ Done — target ship `v2.27.0`
> Trigger: 2026-05-01 /loop tick 22-24 累積 tech debt — S043 後 keyword search 已擴 category 比對；S044 trim 後 leading/trailing space 不影響；HomePage SearchBar placeholder 仍顯「搜尋技能名稱或描述...」未對齊行為。User 看 placeholder 不會發現可搜分類名（如 DevOps）。

---

## 1. Goal

`SearchBar` placeholder 改為「搜尋名稱、描述或分類...」對齊 S043 SQL `WHERE LOWER(name) LIKE :kw OR LOWER(description) LIKE :kw OR LOWER(category) LIKE :kw`。

---

## 2. Approach

### 2.1 Code diff

```diff
-placeholder="搜尋技能名稱或描述..."
+placeholder="搜尋名稱、描述或分類..."
```

### 2.2 為何選這個措辭

- 「技能名稱」→「名稱」：「技能」二字冗餘（已在「探索 Agent 技能」h1 + 整個搜尋上下文中確立）
- 「描述」保留 — 動詞通用
- 加「分類」對齊 S043 keyword 行為
- 移除「技能」前綴讓三個欄位平行；user 一眼掃完三選項
- 保留 `...` 暗示「不只這些」（語意搜尋仍會用 vector 找相關概念）

### 2.3 為何 NOT 提語意搜尋

`semantic search` 對 user 是黑箱；placeholder 描述「以詞 match 欄位」是主導行為，semantic 是 enhancement。提了 user 反而困惑「我要寫 query 還是關鍵字？」

---

## 3. SBE Acceptance Criteria

### AC-1: HomePage placeholder 顯示新文字

```gherkin
When  user 進入 HomePage
Then  搜尋框 placeholder == "搜尋名稱、描述或分類..."
```

### AC-2: 既有 frontend 測試不破

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
- `frontend/src/components/SearchBar.tsx`：placeholder 文字更新

### 5.2 Test
- 既有 frontend test 不破即可；E2E 由 Chrome 手測（1 個 AC）

### 5.3 Docs
- CHANGELOG `v2.27.0`
- spec-roadmap M46

---

## 6. Task Plan

| # | Task | AC | Status |
|---|---|---|---|
| T01 | placeholder edit + Chrome 驗證 | AC-1~2 | 🔲 |

---

## 7. Implementation Results

> Status: ✅ Done — 2026-05-01 / target ship `v2.27.0`
>
> Verification: vitest 10 / 0 fail；Chrome E2E placeholder 確認新文字。

### 7.1 Verification Results

| 命令 | 結果 |
|------|------|
| `npm test -- --run` | 10 tests / 0 fail ✓ AC-2 |
| Chrome `input[type=search].placeholder` | "搜尋名稱、描述或分類..." ✓ AC-1 |

### 7.2 Files Changed

#### Frontend (1 file)
- `frontend/src/components/SearchBar.tsx`：placeholder 文字更新

### 7.3 AC Results Table

| AC | Status | 證據 |
|----|--------|------|
| AC-1: 顯示新 placeholder | ✅ PASS | Chrome confirm |
| AC-2: 既有測試不破 | ✅ PASS | vitest 10 / 0 fail |
