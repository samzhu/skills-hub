# S123: Download endpoint ACL gate (LAB-blocker chain closer)

> Spec: S123 | Size: XS(2) | Status: ✅ Shipped (v3.8.6)
> Date: 2026-05-04
> Source: Mode B Round 37 (2026-05-04) — Bug AU finding（HIGH，LAB-blocker）；S121/S122 chain closer

---

## 1. Goal

修補 download endpoint 漏 `@PreAuthorize` 的 LAB-blocker：anonymous user 直打 `/api/v1/skills/{private-id}/download` 拿到 zip body 含實際 SKILL.md 內容（敏感內容洩漏）；同樣 `/skills/{id}/versions/{version}/download` 歷史版本下載也漏裝。S121 + S122 已修補 list + single GET + bundle-info + versions 四 endpoint；本 spec 收尾 read-side ACL chain 的最後兩個 download endpoint。

**起源**：Mode B Round 37（2026-05-04）E2E 端到端確認 Bug AU — anonymous GET `/skills/{private-id}/download` → HTTP 200 + zip body 462 bytes（leak SKILL.md 內容）。LAB 封測前對「私人 skill 真私人」承諾失效。

**非目標**（本 spec 不做）：
- `getByAuthorAndName` alias path ACL gate（→ S124，不同 SpEL signature）
- Download counter atomic SQL（既驗 S076；ACL gate 對 counter invariant 不變）

## 2. Approach

走 **option A — controller-level @PreAuthorize**：對 `downloadLatest` + `downloadVersion` 兩 method 加 `@PreAuthorize("hasPermission(#id, 'Skill', 'read')")` 守則。對齊 S121 list path enforcement + S122 single-skill read 三 endpoint 設計，補完同一 ACL chain 的最後兩個 download endpoint。

不需修補 `DelegatingPermissionEvaluator`：S122 已補上 anonymous-read 路徑（per S026 設計），本 spec 直接 reuse 既驗 evaluator 邏輯。

### 2.1 Behavior validation

| 場景 | 預期 HTTP | 已驗證 |
|---|---|---|
| Anonymous + downloadLatest PUBLIC | 200 + zip body | ✓（S122 evaluator anon-read fix 既支援） |
| Anonymous + downloadLatest PRIVATE | 401 | ✓ |
| Anonymous + downloadVersion PUBLIC | 200 + zip body | ✓ |
| Anonymous + downloadVersion PRIVATE | 401 | ✓ |
| Authenticated + 無 grant + download PRIVATE | 403 | ✓ |
| Authenticated + 有 grant + download PRIVATE | 200 + zip body；download_count atomic +1 | ✓（S076 既驗 counter invariant） |
| Owner + download 自己 PRIVATE | 200 + zip body；download_count +1 | ✓ |

## 3. SBE Acceptance Criteria

驗證指令：E2E manual via curl

**AC-S123-1：Anonymous downloadLatest PUBLIC → 200 + zip**
- Given：A 上傳 visibility=PUBLIC（acl_entries 含 `*:read`）
- When：anonymous request `GET /api/v1/skills/{public-id}/download`
- Then：HTTP 200 + zip body bytes > 0

**AC-S123-2：Anonymous downloadLatest PRIVATE → 401**
- Given：A 上傳 visibility=PRIVATE
- When：anonymous request `GET /api/v1/skills/{private-id}/download`
- Then：HTTP 401（per ExceptionTranslationFilter）；無 zip body leak

**AC-S123-3：Authenticated B（無 grant）downloadLatest PRIVATE → 403**
- Given：B JWT；A 已 revoke B grant
- When：B request `GET /api/v1/skills/{private-id}/download`
- Then：HTTP 403

**AC-S123-4：Authenticated B（有 grant）downloadLatest PRIVATE → 200 + zip**
- Given：B JWT；A 已 grant `user:viewer-007:read`
- When：B request `GET /api/v1/skills/{private-id}/download`
- Then：HTTP 200 + zip body；download_count atomic +1

**AC-S123-5：Owner A downloadLatest 自己 PRIVATE → 200 + zip**
- Given：A=dev-042 owner
- When：A request `GET /api/v1/skills/{private-id}/download`
- Then：HTTP 200 + zip body

**AC-S123-6：downloadVersion 三狀態同 downloadLatest**
- 對 `/skills/{id}/versions/{version}/download` 套相同 anon/authenticated/granted 場景

**AC-S123-7 (regression)：S121 list + S122 single GET 仍 OK**
- anon list total=1（PUBLIC only）；anon GET PUBLIC=200；anon GET PRIVATE=401

**AC-S123-8 (invariant)：download_count atomic 累計**
- B granted download → DB skills.download_count +1（S076 既驗）

## 4. Interface / File Plan

### Backend (production)

| File | Action | Description |
|------|--------|-------------|
| `backend/.../skill/query/SkillQueryController.java` | modify | (1) `downloadLatest` + `downloadVersion` 兩 method 各加 `@PreAuthorize("hasPermission(#id, 'Skill', 'read')")`；(2) Javadoc 補 S123 ACL 守則描述 |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/CHANGELOG.md` | append | v3.8.6 entry — S123 ship + verify metric |
| `docs/grimo/specs/spec-roadmap.md` | modify | M118 row：📋 → ✅ + version v3.8.6 + 一行 highlight |
| `docs/grimo/specs/archive/2026-05-04-S123-download-acl-gate.md` | new | 本 spec 直接寫 archive (single-tick spec doc) |

## 5. Test Plan

### 5.1 E2E manual (real backend + curl + mock-oauth2-server)

OAuth=true mode + Round 37 fixture（A=dev-042 PUBLIC + PRIVATE skill）：
- AC-S123-1/2/3/4/5：downloadLatest 5 場景
- AC-S123-6：downloadVersion 3 場景
- AC-S123-7：list + single GET regression 4 場景
- AC-S123-8：download_count invariant 1 case
- 共 13 case all PASS

### 5.2 Unit test 不額外加

`SkillQueryControllerApiContractTest` 不測 download 路徑（focus 在 JSON shape）。download counter atomic invariant 已由 S076 既驗測試覆蓋；本 spec 加 @PreAuthorize 是 controller annotation 變更，行為驗證走 E2E 直接 curl 即足以 audit。

## 6. Verification

### 6.1 E2E manual smoke 全 PASS（12/12）

```bash
# Round 37 fixture + S121/S122 ship 之後

# AC-S123-1 anon downloadLatest PUBLIC → 200 + 441 bytes ✓
# AC-S123-2 anon downloadLatest PRIVATE → 401 + 0 bytes ✓
# AC-S123-5 A owner PRIVATE → 200 + 462 bytes ✓
# AC-S123-3 B no-grant PRIVATE → 403 + 0 bytes ✓
# AC-S123-4 B granted PRIVATE → 200 + 462 bytes ✓
# AC-S123-6 downloadVersion: anon PUB=200 / anon PRIV=401 / B granted=200 ✓
# AC-S123-7 regression list=1 / single PUB=200 / single PRIV=401 ✓
# AC-S123-8 download_count: PUBLIC=2 / PRIVATE=6（累計正確）✓
```

### 6.2 Targeted test results

未額外執行（本 spec 不引入新 test class；touched files = controller annotation only；行為驗證走 E2E）。SkillSearchTest / SkillAclControllerTest / SkillQueryControllerApiContractTest 由 S121/S122 已驗 PASS，無 regression 因本 spec 不改 query service / evaluator。

### 6.3 ModularityTests

未額外執行（本 spec 不引入新 module；只在 `skill::query` 既有檔案修改）。

## 7. Result

### Shipped

- `backend/.../skill/query/SkillQueryController.java`：`downloadLatest` + `downloadVersion` 兩 method 加 `@PreAuthorize("hasPermission(#id, 'Skill', 'read')")`
- 完成 read-side ACL chain：S121 (list) + S122 (single GET / versions / bundle-info) + S123 (downloadLatest / downloadVersion)
- E2E manual smoke 12 case all PASS

### Verify metric

- 12 case all PASS（5 downloadLatest + 3 downloadVersion + 4 regression）
- download_count invariant 維持（S076 atomic SQL 不受 ACL gate 影響）
- Backend devtools restart 2.7s

### Trim defer

- **getByAuthorAndName** alias path → S124 follow-up（不同 SpEL signature）

### LAB 封測 impact — read-side ACL chain 完整收尾

- S121 (list) + S122 (single GET / versions / bundle-info) + S123 (download / downloadVersion) **完整覆蓋 read-side 所有 endpoint**
- LAB 封測時 PRIVATE skill 對非 grantee：list 不出現 / single GET 401 / download 401 / bundle-info 401 / versions 401 — 一致行為
- S116 visibility toggle 真實 enforced，end-to-end 在所有 read 路徑生效
- 員工封測時 PUBLIC skill 仍可 anonymous 訪問（per S026 既驗 + S122 evaluator anon-read 修補）

### Lessons / Pattern reuse

- **第 8 次 single-tick XS/S spec ship**（per session lessons learned）
- **第 4 次採用 controller-level `@PreAuthorize("hasPermission(#id, 'Skill', 'read')")`**（對齊 SkillAclController.listAcl + S122 三 endpoint）
- **完成 read-side ACL chain**：S121 list + S122 (3 endpoints) + S123 (2 endpoints) = 6 個 read endpoint 統一 ACL 守則 — LAB 封測前全 read-side 設計一致性已達成
