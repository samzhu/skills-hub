# S067: Version Input HTML5 Pattern Pre-Validation

> Spec: S067 | Size: XS(5) | Status: ✅ Done — target ship `v2.45.0`
> Trigger: 2026-05-01 /loop tick 42 — `PublishPage` 與 `AddVersionForm` 的 version input 缺 HTML5 `pattern` 預驗。User 輸入 `foo` 直接 submit 才從 backend 收 400 才知錯（S056 已 enforce backend 端）；增 client-side pattern 即時 hint。

---

## 1. Goal

PublishPage + SkillDetailPage AddVersionForm 的 version input 加 HTML5 `pattern` 屬性 + `title` hint，對齊 backend `Skill.VERSION_REGEX`（S056）。

---

## 2. 兩個 HTML5 pattern 陷阱

### 2.1 不要寫 `^...$`
HTML5 spec 自動 wrap 為 `^(?:pattern)$`。額外寫 `^...$` 部分 Chrome 版本誤判為 valid。

### 2.2 字元 class 內 `.` 與 `-` 必須 escape
Chrome 對 `[0-9A-Za-z.-]`（未 escape `.`、`-` 在中間/末尾）silent 停用 pattern → user 任何輸入都 valid。實測：
- `[a-z\\.\\-]` ✓ 正常 reject "foo"
- `[a-z.-]` ❌ silent disabled
- `[-a-z.]` ❌ silent disabled
- `[a-z-]` ❌ silent disabled

最終 pattern：`\d+\.\d+\.\d+(-[A-Za-z0-9\.\-]+)?`

---

## 3. SBE Acceptance Criteria

### AC-1: 「foo」rejected by client-side
```gherkin
Given user 在 PublishPage 或 AddVersionForm
When  輸入 version="foo"
Then  HTMLInputElement.validity.patternMismatch === true
```

### AC-2: semver 1.0.0 / 2.0.0-rc.1 accepted
```gherkin
When  輸入 1.0.0 / 2.0.0-rc.1 / 3.0.0-alpha-1
Then  validity.valid === true
```

### AC-3: 既有 frontend test 不破

---

## 7. Implementation Results — ✅ Done

### Verification

| Test | Result |
|------|--------|
| vitest | 10 / 0 fail |
| Chrome `foo` → patternMismatch | true ✓ |
| Chrome `1.0.0` → valid | true ✓ |
| Chrome `2.0.0-rc.1` → valid | true ✓ |
| Chrome `3.0.0-alpha-1` → valid | true ✓ |

### Files Changed (2)
- `frontend/src/pages/PublishPage.tsx`：version input 加 pattern + title
- `frontend/src/pages/SkillDetailPage.tsx`：AddVersionForm version input 同

### Key Findings — HTML5 Pattern 兩陷阱
1. 不要寫 `^...$`（HTML5 自動 wrap，部分 Chrome 版本誤判 valid）
2. 字元 class 內 `.` 與 `-` 必須 escape（`\.\-`），否則 Chrome silent 停用整個 pattern

兩陷阱文檔在 inline 註解，未來改 pattern 不再踩。
