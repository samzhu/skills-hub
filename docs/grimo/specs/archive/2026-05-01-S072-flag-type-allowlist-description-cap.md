# S072: Flag Type Allowlist + Description Length Cap

> Spec: S072 | Size: XS(3) | Status: ✅ Done — target ship `v2.50.0`
> Trigger: 2026-05-01 long E2E test session — Round 10 flag flow 探查 `POST /api/v1/skills/{id}/flags` 發現：
> 1. `type="bogus"` 任意字串接受成功 → DB `flag.type` 髒，admin review 不可能依 type 分類
> 2. `description="xxx" * 5000` 接受成功 → DB row 5KB+ 進 storage，UI flag list 排版破壞、儲存成本
>
> S058 (M51) 修了 `Map.of` null 接受 nullable description，但沒做 type 白名單與 description 長度上限。本 spec 補齊 — pair with S055（Skill aggregate ACL type validation）的 defense pattern。

---

## 1. Goal

`FlagService.createFlag` 補兩道閘：
1. `type` 必須屬白名單 `{malicious, spam, inappropriate, copyright, security, other}`
2. `description`（trim 後）≤ 500 字元

違反 → `IllegalArgumentException` → `GlobalExceptionHandler` → HTTP 400 `VALIDATION_ERROR`

---

## 2. Approach

`FlagService` 加兩個 constants：

```java
private static final Set<String> ALLOWED_TYPES = Set.of(
    "malicious", "spam", "inappropriate", "copyright", "security", "other");
private static final int DESCRIPTION_MAX = 500;
```

`createFlag` 在既有 `type ≤ 20 chars` 後加：
- `!ALLOWED_TYPES.contains(trimmedType)` → throw
- `trimmedDescription != null && length > 500` → throw

---

## 3. Acceptance Criteria

- AC-1: `POST /flags {"type":"bogus"}` → 400 VALIDATION_ERROR + 訊息列出白名單
- AC-2: `POST /flags {"type":"security","description":"xxx" * 5000}` → 400
- AC-3: `POST /flags {"type":"security","description":"yyy" * 500}` (boundary) → 201
- AC-4: `POST /flags {"type":"malicious"}` 等 6 個白名單 type → 201
- AC-5: `POST /flags {"type":"security","description":""}` (empty)→ 201（description nullable）
- AC-6: 既有 SkillFlagged event store / read-model write / publish 行為不變

---

## 4. Risks / Rejected Alternatives

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| 6 個 fixed type 白名單 | 對齊一般社群審核分類；管理上 enum 比 free-form 好 reporting | free-form text — admin review 困難；過於嚴格 ENUM (`SECURITY` 大寫) — 增加 client 負擔 |
| description 上限 500 | flag 描述本質是「指控」，500 字足夠；超過大概率 attacker 灌水 | 1024（aligned with skill description）— 過鬆；100 — 真實 abuse report 可能不夠 |
| validation 在 `FlagService`（service 層） | 與 S055 ACL aggregate validation 同層；service 層 fail fast 比 jdbc constraint 快 | DB CHECK constraint — error message 不友善；前端 only — bypass 風險 |

---

## 7. Implementation Results — ✅ Done

### Verification
- `./gradlew test` 288 / 0 fail（286 → 288，FlagControllerTest 加 2 個 reject test）
- 真實 backend curl 驗證所有 6 個 AC PASS：
  - bogus → 400 with allowlist message ✓
  - 5000 chars → 400 ✓
  - 500 boundary → 201 ✓
  - malicious/spam → 201 ✓
  - empty description → 201 ✓

### Files Changed (2)
- `backend/src/main/java/io/github/samzhu/skillshub/security/FlagService.java`：
  - 加 `ALLOWED_TYPES` Set + `DESCRIPTION_MAX = 500`
  - `createFlag` 在 type length check 後加 `!ALLOWED_TYPES.contains` + description length 檢查
- `backend/src/test/java/io/github/samzhu/skillshub/security/FlagControllerTest.java`：
  - 加 `rejectInvalidType` + `rejectLongDescription` 測 controller catch IllegalArgumentException → 400

### Bug Origin
- M51 (S058) 修 `Map.of` null pattern 時沒做 type 白名單 — 那次 trigger 是 `Map.of` NPE，不是 type 問題
- S055 (M51 同期) 修 `Skill.grantAcl` ACL type validation — 但 Flag 是獨立 aggregate，沒一起做
- 沒人從攻擊者視角測 flag endpoint — Round 10 的 `type="bogus"` 是第一次 negative case test

### Pattern Consolidation
Aggregate validation 三例：
- S055 `Skill.grantAcl` — ACL tuple `(type, principal, permission)` 各自白名單
- S057 `Skill` — name/description/category/author/version 格式驗證
- S072 `FlagService` — flag `type` 白名單 + `description` length cap（**本 spec**）

未來新增 service 接 user input：
- 任何「字串選一個語意分類」欄位都要白名單（不要相信 free text）
- 任何「描述/原因/註解」欄位都要 length cap（500 通常夠）
- service 層 throw IllegalArgumentException，由 GlobalExceptionHandler 轉 400 — 已標準化
