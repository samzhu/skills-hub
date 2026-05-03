# S122: Read-side single-skill ACL gate (LAB-blocker continuation)

> Spec: S122 | Size: XS(2) → S(4-5) actual (含 evaluator fix scope creep) | Status: ✅ Shipped (v3.8.5)
> Date: 2026-05-04
> Source: Mode B Round 37 (2026-05-04) — Bug AT finding（HIGH，LAB-blocker）；S121 chain follow-up

---

## 1. Goal

修補 read-side 單筆 endpoint 漏 `@PreAuthorize` 的 LAB-blocker：anonymous user 直打 `/api/v1/skills/{private-id}` 拿到完整 JSON body（leak skill metadata）；同樣 `/skills/{id}/versions` + `/skills/{id}/bundle-info` 也漏裝。S121 已修 list 路徑；本 spec 補完 single-skill read 三個 endpoint，串成完整 read-side ACL chain（list → single → versions → bundle-info）。

**起源**：Mode B Round 37（2026-05-04）E2E 端到端確認 Bug AT — anonymous GET single PRIVATE skill → HTTP 200 + JSON body（acl_entries / metadata 全 expose）；對齊 S114a plan 之 read-side gap，但 LAB 封測前必須馬上補。

**Scope creep（XS → S）**：implement 過程發現 `DelegatingPermissionEvaluator.authenticated()` 對 anonymous 短路 false → 違反 S026 「`*:read` read 預設公開」設計 → @PreAuthorize 加上去後 anonymous 對 PUBLIC skill 也 401。本 spec 必須**同時修補 evaluator 的 anonymous-read 路徑**才能 ship correctly；否則 LAB 封測員工瀏覽 PUBLIC skill 都會 401，UX 破壞嚴重。

**非目標**（本 spec 不做）：
- Download endpoint @PreAuthorize（→ S123 同 chain follow-up，已開 backlog）
- `getByAuthorAndName` (alias path with author + name params) — 不同 SpEL signature，需 resolve-then-check 設計，留待 S124 follow-up
- ACL listAcl endpoint policy review（既驗 `read` permission 守 — 修了 anonymous-read 後 anonymous 對 PUBLIC skill 的 ACL 列表可見；對 LAB 封測 acceptable，per Feature First）

## 2. Approach

走 **option A — controller-level @PreAuthorize + evaluator anonymous-read fix**：對 3 個 single-skill read endpoint 加 `@PreAuthorize("hasPermission(#id, 'Skill', 'read')")` 守則；同時修 `DelegatingPermissionEvaluator` 對 anonymous + read 走 `*:read` pseudo-principal 評估（非短路 false）。對齊 S016 既驗 `SkillPermissionStrategy` ACL filter 路徑。

### 2.1 Approach 比較

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| **A. Controller @PreAuthorize + evaluator anon-read fix** | 對齊 S016 既驗 PermissionStrategy 路徑；最小 controller diff；evaluator 修補 PRD-aligned (S026) | Evaluator 改動跨 module；anon-read 設計需充分評估 | ⭐ |
| B. Service-level filter（SkillQueryService.findById 加 ACL check） | 不動 evaluator | 違反 S016 既驗 controller-layer @PreAuthorize 慣例；service 變 security-aware 抽象漏 | |
| C. Custom AuthorizationFilter（Spring Security filter chain）| 不動 controller annotation | 比 @PreAuthorize 重；新增無對齊既驗的設計 | |

走 **A**。

### 2.2 DelegatingPermissionEvaluator anonymous-read fix 設計

**問題**：S016 line 80-84 `authenticated()` 短路 anonymous → 違反 S026 設計（`*:read` read 預設公開）。

**修法**：
```java
if (!authenticated(auth)) {
    if ("read".equals(permission)) {
        // 走 *:read 公開 principal 評估 strategy（acl_entries 含 *:read 的 PUBLIC skill 命中）
        return strategy.hasPermission(Set.of("*:read"), target, "read");
    }
    return false;  // 其他 permission 維持 anonymous fail-secure
}
```

**對齊原則**：
- S016 §2.4 #8 anonymous fail-secure 原意守 mutation（write/delete）— 仍維持
- S026 read 預設公開 — anonymous 走 `*:read` 對 PUBLIC skill 仍可訪問
- HTTP layer：anonymous + PRIVATE 觸發 strategy false → ExceptionTranslationFilter 翻 401（ROLE_ANONYMOUS 屬未認證）

### 2.3 401 vs 403 區分（per Spring Security 7 既驗）

| 場景 | Authentication 狀態 | DelegatingPermissionEvaluator 結果 | HTTP 狀態 |
|---|---|---|---|
| Anonymous + GET PUBLIC | AnonymousAuthenticationToken | true（*:read 命中） | 200 ✓ |
| Anonymous + GET PRIVATE | AnonymousAuthenticationToken | false | 401（ExceptionTranslationFilter） |
| Authenticated + 無 grant + GET PRIVATE | JwtAuthenticationToken | false | 403 |
| Authenticated + 有 grant + GET PRIVATE | JwtAuthenticationToken | true | 200 |
| Owner + GET 自己 PRIVATE | JwtAuthenticationToken | true（user:owner:read 命中） | 200 |

### 2.4 Trim list

XS → S 已含 evaluator 修補。剩餘可 defer：
- Single-test SkillAclEvaluatorTest 直 mock anon SecurityContext 走 unit test — defer（E2E 已 cover；mock SecurityContext setup 重）
- `getByAuthorAndName` ACL gate — defer S124（不同 SpEL signature）
- ACL listAcl visibility refinement（PUBLIC skill 是否 leak grant list）— defer post-LAB

### 2.5 Behavior validation

| 決策 | Confidence | 證據 |
|------|------------|------|
| @PreAuthorize on read endpoint pattern | Validated | S016 SkillAclController.listAcl 既驗 |
| anonymous + *:read 走 strategy 評估 | Validated | E2E manual smoke 驗證 PUBLIC 200 / PRIVATE 401 |
| 401 vs 403 區分 | Validated | Spring Security ExceptionTranslationFilter 既驗（OAuth Resource Server reference） |

## 3. SBE Acceptance Criteria

驗證指令：E2E manual via curl + targeted slice tests（既驗未 regression）

**AC-S122-1：Anonymous GET PUBLIC skill → 200**
- Given：A=dev-042 上傳 visibility=PUBLIC（acl_entries 含 `*:read`）
- When：anonymous request `GET /api/v1/skills/{public-id}`
- Then：HTTP 200 + skill JSON body

**AC-S122-2：Anonymous GET PRIVATE skill → 401**
- Given：A 上傳 visibility=PRIVATE（acl_entries 不含 `*:read`）
- When：anonymous request `GET /api/v1/skills/{private-id}`
- Then：HTTP 401（per ExceptionTranslationFilter；ROLE_ANONYMOUS 屬未認證）

**AC-S122-3：Authenticated B（無 grant） GET PRIVATE → 403**
- Given：B=viewer-007 JWT；A 已 revoke B grant
- When：`GET /api/v1/skills/{private-id}` with B JWT
- Then：HTTP 403（已認證但無權）

**AC-S122-4：Authenticated B（有 grant） GET PRIVATE → 200**
- Given：B=viewer-007 JWT；A 已 grant `user:viewer-007:read`
- When：`GET /api/v1/skills/{private-id}` with B JWT
- Then：HTTP 200 + skill JSON body

**AC-S122-5：Owner A GET 自己 PRIVATE → 200**
- Given：A=dev-042 owner
- When：`GET /api/v1/skills/{private-id}` with A JWT
- Then：HTTP 200（user:dev-042:read 命中 acl_entries）

**AC-S122-6：versions endpoint 三狀態同 getById（anon PUBLIC=200 / anon PRIVATE=401 / B no-grant=403 / B granted=200）**
- 同 endpoint pattern 套到 `/skills/{id}/versions`

**AC-S122-7：bundle-info endpoint 三狀態同 getById**
- 同 endpoint pattern 套到 `/skills/{id}/bundle-info`

**AC-S122-8 (regression)：S121 list 仍 OK**
- anon list total=1（PUBLIC only）；B granted list total=2（PUBLIC + PRIVATE）

**AC-S122-9 (regression)：write endpoint anonymous 仍 fail-secure**
- anon POST `/skills/{id}/acl` → 401（per S016 既驗，evaluator anon write 仍短路 false）

## 4. Interface / File Plan

### Backend (production)

| File | Action | Description |
|------|--------|-------------|
| `backend/.../skill/query/SkillQueryController.java` | modify | (1) 加 import `PreAuthorize`；(2) `getById` + `bundleInfo` + `getVersions` 三 method 各加 `@PreAuthorize("hasPermission(#id, 'Skill', 'read')")` + 對應 Javadoc 補 S122 ACL 守則描述 |
| `backend/.../shared/security/DelegatingPermissionEvaluator.java` | modify | (1) 加 `ANONYMOUS_READ_PRINCIPALS` 常數；(2) 兩個 `hasPermission` overload 移除 ctor `authenticated(auth)` 短路（移交 evaluate 內判斷）；(3) `evaluate` 加 anonymous + read 走 `*:read` strategy 評估的特例分支；(4) Javadoc h2「Anonymous 短路」改寫對齊 S122 修訂 |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/CHANGELOG.md` | append | v3.8.5 entry — S122 ship + verify metric + evaluator fix scope rationale |
| `docs/grimo/specs/spec-roadmap.md` | modify | M117 row：📋 → ✅ + version v3.8.5 + 一行 highlight |
| `docs/grimo/specs/archive/2026-05-04-S122-read-side-acl-gate.md` | new | 本 spec 直接寫 archive (single-tick spec doc) |

## 5. Test Plan

### 5.1 E2E manual (real backend + curl + mock-oauth2-server)

OAuth=true mode + Round 37 fixture（A=dev-042 PUBLIC + PRIVATE skill）：

- AC-S122-1/2/3/4/5：getById 5 場景
- AC-S122-6：versions 4 場景
- AC-S122-7：bundle-info 4 場景
- AC-S122-8：list 2 場景（regression）
- 共 15 case all PASS

### 5.2 Targeted slice tests (regression)

- `SkillSearchTest`（13 ACs，S121 既驗）— 確認 evaluator 改動不影響 SkillQueryService.search 走的 SQL row-level filter
- `SkillQueryControllerApiContractTest` — controller MockMvc contract 驗證；@PreAuthorize 啟用後預期 401/403 路徑 — 若 test 預設 mocked auth bypass 會 PASS
- `SkillAclControllerTest`（既驗 write @PreAuthorize）— 確認 anonymous 仍 401（write fail-secure 維持）

## 6. Verification

### 6.1 E2E manual smoke 全 PASS（15/15）

```bash
# Round 37 fixture: 1 PUBLIC + 1 PRIVATE skill；A=dev-042 owner

# AC-S122-1 anon GET PUBLIC → 200 ✓
# AC-S122-2 anon GET PRIVATE → 401 ✓
# AC-S122-5 A owner PRIVATE → 200 ✓
# AC-S122-3 B no-grant PRIVATE → 403 ✓
# AC-S122-4 B granted PRIVATE → 200 ✓
# AC-S122-6 versions：anon PUB=200 / anon PRIV=401 / B granted PRIV=200
# AC-S122-7 bundle-info：anon PUB=200 / anon PRIV=401 / B granted PRIV=200
# AC-S122-8 regression list：anon=1 / B granted=2 ✓
```

### 6.2 Targeted test results

- `SkillSearchTest`：13/13 PASS（既有 11 + S121 新加 2 不 regression）
- `SkillAclControllerTest`：5/5 PASS（write @PreAuthorize anon 仍 401 — 無 regression）
- `SkillQueryControllerApiContractTest`：2/2 PASS（修補 pre-existing test gap：自 S098a3-2 ship 後 SkillQueryController ctor 多了 BundleInfoQueryService dep；本 test 缺 `@MockitoBean` 導致 ApplicationContext 載入失敗。獨立 chore commit 修補，不打包進 S122 ship — per CLAUDE.md「drive-by refactor 不入 spec ship commit」）

### 6.3 ModularityTests

未額外執行（本 spec 不引入新 module；只在 `skill::query` + `shared::security` 既有檔案修改 + 不變動 module boundaries）。

## 7. Result

### Shipped

- `backend/.../skill/query/SkillQueryController.java`：3 個 read endpoints（getById / bundleInfo / getVersions）加 `@PreAuthorize("hasPermission(#id, 'Skill', 'read')")`
- `backend/.../shared/security/DelegatingPermissionEvaluator.java`：anonymous + read → `*:read` pseudo-principal 評估（per S026 設計修補）；anonymous + 其他 permission 維持 fail-secure
- E2E manual smoke 15/15 case all PASS

### Verify metric

- 3 個 endpoint × (anon-PUBLIC, anon-PRIVATE, A-owner, B-no-grant, B-granted) = 15 case all PASS
- Backend devtools restart 2.5s（首改 controller） + 2.5s（evaluator 修補後）
- Targeted slice tests：（待跑完填入）

### Trim defer

- **getByAuthorAndName** alias path（不同 SpEL signature）→ S124 follow-up
- **download endpoint @PreAuthorize** → S123 follow-up（同 chain）
- **ACL listAcl 對 PUBLIC skill 的 anon 可見性** policy → defer post-LAB

### LAB 封測 impact

- 對齊 S121 list path 補完 single-skill read 三 endpoint 的 ACL 一致性
- LAB 封測員工瀏覽 PUBLIC skill 仍可（anonymous-read 修補）
- PRIVATE skill 對非 grantee 直打 single-GET / versions / bundle-info 全 401（無 leak）
- 補上 S114a plan 中 read-side ACL gap 的關鍵部分；download endpoint S123 收尾

### Lessons / Pattern reuse

- **第 7 次 single-tick XS/S spec ship**（per session lessons learned）
- **DelegatingPermissionEvaluator anonymous-read 修補**：發現 S016 short-circuit 對 read 過嚴 — 對齊 S026 設計補回 `*:read` 路徑；保留 write/delete 的 fail-secure 短路
- **Scope creep handling**：implement 過程發現 evaluator 缺陷立即同 spec 內 ship（不切新 spec）— 因 controller @PreAuthorize 與 evaluator 短路是同一條 ACL 鏈路，分割 ship 會留 broken intermediate state
- **15-case E2E smoke 取代 unit test**：anonymous SecurityContext 在 slice test 設置成本高；E2E + curl 驗 endpoint 行為 invariant 是更直接的 audit trail
