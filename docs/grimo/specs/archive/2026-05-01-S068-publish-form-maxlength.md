# S068: PublishPage Form maxLength Constraint

> Spec: S068 | Size: XS(5) | Status: ✅ Done — target ship `v2.46.0`
> Trigger: 2026-05-01 /loop tick 43 — `PublishPage` 的 category / author 欄位無 client-side 字數限制；user 可輸入超過 DB column varchar 上限的字串，backend 攔截為 400 CONSTRAINT_VIOLATION（S057），但 round-trip 後才知。HTML5 `maxLength` 屬性 prevent typing beyond limit，更佳 UX。

---

## 1. Goal

PublishPage 的 category / author input 加 HTML5 `maxLength`，對齊 DB column varchar 上限：
- `category`: maxLength={50}（對齊 `skills.category varchar(50)`）
- `author`: maxLength={255}（對齊 `skills.author varchar(255)`）

---

## 7. Implementation Results — ✅ Done

### Verification
- `npm test` — 10 / 0 fail
- Chrome `input.maxLength` 驗：category=50 / author=255 ✓

### Files Changed (1)
- `frontend/src/pages/PublishPage.tsx`：category + author input 加 maxLength

### Pattern Note
maxLength + pattern 互補：
- `pattern`：格式驗證（如 semver）
- `maxLength`：硬性字數上限對齊 DB schema
