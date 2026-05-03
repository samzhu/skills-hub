# S124: getByAuthorAndName ACL gate (read-side ACL chain final closer)

> Spec: S124 | Size: XS(2) | Status: ✅ Shipped (v3.10.5)
> Date: 2026-05-04
> Source: S122/S123 chain follow-up — read-side ACL chain 唯一漏 endpoint

---

## 1. Goal

補完 read-side ACL chain 最後一個 endpoint：`GET /api/v1/skills/{author}/{name}` (canonical alias path per S096c / ADR-003)。其餘 6 個 read endpoints 已在 S121/S122/S123 chain 加上 ACL 守則，唯本 alias path 因不同 SpEL signature（兩 path variable 而非單一 id）延後到 LAB 部署相關 chain 之外。

**起源**：S122 / S123 ship 後 backlog 顯示 S124 為「同 chain 最後一個 endpoint」候選。**非 LAB-blocking**（dev/frontend 主要走 `/{id}` path），但 read-side ACL 一致性需收尾以避免「alias path 比 canonical path 寬鬆」的反向漏洞。

**非目標**（本 spec 不做）：
- 自訂 `PermissionStrategy` `byAuthorAndName(author, name, permission)` overload（不需 — `@PostAuthorize` resolve-then-check 即足）
- 改 SkillQueryService.findByAuthorAndName 業務邏輯

## 2. Approach

走 **option A — `@PostAuthorize("hasPermission(returnObject.id, 'Skill', 'read')")`**：resolve-then-check pattern。先 `findByAuthorAndName` 拿 Skill aggregate，再對 `returnObject.id` (SpEL path access) 走 hasPermission；不存在拋 `NoSuchElementException` → 404 in handler；無權拋 AccessDenied → 401（anon）/ 403（authenticated）。

### 2.1 Approach 比較

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| **A. `@PostAuthorize("hasPermission(returnObject.id, 'Skill', 'read')")`** | Reuse 既驗 `SkillPermissionStrategy` SQL；零新 infra；對齊 S122/S123 既驗 ACL chain | 多 1 次 DB read（resolve）；非權限失敗時 leak load cost | ⭐ |
| B. 自訂 `PermissionStrategy` `byAuthorAndName(author, name, permission)` overload + `@PreAuthorize("...")` SpEL bean call | 單一 SQL（COUNT EXISTS 直接走 author+name 條件） | 新 SQL + 新 strategy method + 可能未來其他 alias 路徑也要對應 overload；違反「真的有第三 use case 才抽」 | |
| C. Service-level resolve + custom AspectJ before-advice 攔截 | 細粒度控制 | 與 Spring Security canonical pattern 衝突；維護成本高 | |

走 **A**。

### 2.2 Resolve-then-check 安全性分析

關鍵 invariant：**findByAuthorAndName 結果不會 leak 給無權 user**。
- Spring Security `@PostAuthorize` 在 method return 後攔截
- 失敗時拋 `AccessDeniedException`，由 `ExceptionTranslationFilter` 翻 401/403
- 4xx response body 為標準 ErrorResponse shape（per GlobalExceptionHandler），**不含 returnObject 內容**
- DB load cost 是 acceptable trade-off（per spec §1 alias path UX 優於 canonical-only）

### 2.3 Trim list

XS=2 — 無 trim space。

## 3. SBE Acceptance Criteria

驗證指令：E2E manual via curl

**AC-S124-1：anon GET PUBLIC by author/name → 200**
- Given：A 上傳 visibility=PUBLIC（acl_entries 含 `*:read`）
- When：anon `GET /api/v1/skills/dev-042/e2e-public-skill`
- Then：HTTP 200 + Skill JSON

**AC-S124-2：anon GET PRIVATE by author/name → 401**
- Given：A 上傳 visibility=PRIVATE（acl_entries 不含 `*:read`）
- When：anon `GET /api/v1/skills/dev-042/e2e-private-skill`
- Then：HTTP 401（per AccessDeniedException + ExceptionTranslationFilter）

**AC-S124-3：anon GET nonexistent → 404**
- Given：author + name 組合不存在
- When：anon `GET /api/v1/skills/dev-042/nonexistent-skill`
- Then：HTTP 404（per NoSuchElementException 在 PostAuthorize 之前）

**AC-S124-4：A owner GET own PRIVATE → 200**
- Given：A=dev-042 owner
- When：A JWT `GET /api/v1/skills/dev-042/e2e-private-skill`
- Then：HTTP 200（user:dev-042:read 命中）

**AC-S124-5：B no-grant GET PRIVATE → 403**
- Given：B JWT；A 已 revoke B grant
- When：B `GET /api/v1/skills/dev-042/e2e-private-skill`
- Then：HTTP 403

**AC-S124-6：B granted GET PRIVATE → 200**
- Given：B JWT；A grant `user:viewer-007:read`
- When：B `GET /api/v1/skills/dev-042/e2e-private-skill`
- Then：HTTP 200

**AC-S124-7 (regression)：id-based + list endpoint 仍 OK**

## 4. File Plan

### Backend (production)

| File | Action | Description |
|------|--------|-------------|
| `backend/.../skill/query/SkillQueryController.java` | modify | (1) 加 import `PostAuthorize`；(2) `getByAuthorAndName` 加 `@PostAuthorize("hasPermission(returnObject.id, 'Skill', 'read')")`；(3) Javadoc 補 S124 ACL 守則描述 + resolve-then-check 設計理由 |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/CHANGELOG.md` | append | v3.10.5 entry |
| `docs/grimo/specs/spec-roadmap.md` | modify | M119 row → ✅ |
| `docs/grimo/specs/archive/2026-05-04-S124-author-name-acl-gate.md` | new | 本 spec |

## 5. Test Plan

### 5.1 E2E manual smoke (real backend OAuth=true + curl)

對應 §3 AC-S124-1~7 — 走既有 Round 38 fixture（A=dev-042 PUBLIC + PRIVATE skill；B grant cycle）：8 個 case 涵蓋 anon/auth + PUBLIC/PRIVATE + owner/grant/revoke。

### 5.2 Unit test 不額外加

- 既驗 `SkillSearchTest` 不 cover 此 alias path
- `@WebMvcTest` slice 加 test 需 mock PermissionEvaluator（per S122 既驗 stub pattern）— 但本 spec 純 annotation 改動 + E2E 已驗 PostAuthorize 行為；不另加 unit test 對齊 S121/S122/S123 既驗「E2E smoke 取代 unit test」lessons

## 6. Verification

### 6.1 E2E manual smoke 全 PASS（8/8）

```bash
# Round 38 fixture + S121/S122/S123 ship 之後

# AC-S124-1 anon GET dev-042/e2e-public-skill → 200 ✓
# AC-S124-2 anon GET dev-042/e2e-private-skill → 401 ✓
# AC-S124-3 anon GET dev-042/nonexistent-skill → 404 ✓
# AC-S124-4 A owner GET PRIVATE → 200 ✓
# AC-S124-5 B no-grant GET PRIVATE → 403 ✓
# AC-S124-6 B granted GET PRIVATE → 200 ✓
# AC-S124-7 regression id-based GET=200 / list total=1 ✓
```

### 6.2 ModularityTests

未額外執行（純 controller annotation 改動；module boundary 不變）。

## 7. Result

### Shipped

- `SkillQueryController.getByAuthorAndName` 加 `@PostAuthorize("hasPermission(returnObject.id, 'Skill', 'read')")`
- E2E manual smoke 8/8 case all PASS

### Verify metric

- 8 case all PASS（5 主流場景 + 1 NotFound + 2 regression）
- Backend devtools restart 2.9s

### Read-side ACL chain 完整收尾（7 endpoints）

- ✅ S121 (v3.8.4) — list (`GET /skills`)
- ✅ S122 (v3.8.5) — single GET / versions / bundle-info (`GET /skills/{id}` + `/skills/{id}/versions` + `/skills/{id}/bundle-info`)
- ✅ S123 (v3.8.6) — download / downloadVersion (`GET /skills/{id}/download` + `/skills/{id}/versions/{version}/download`)
- ✅ **S124 (v3.10.5) — author/name canonical alias** (`GET /skills/{author}/{name}`)
- **總計 7 個 read endpoint 一致 ACL 守則** — visibility (PUBLIC/PRIVATE) 真實 enforced 跨整個 read-side surface

### Trim defer

- 無

### LAB 封測 impact

- LAB 員工瀏覽 skills 時，無論走 `/{id}` 或 `/{author}/{name}` canonical alias path 行為一致（visibility / ACL 守則對稱）
- 非 LAB-blocking（dev/frontend 主要走 `/{id}`）但補完一致性後 LAB 安全 invariant 完整

### Lessons / Pattern reuse

- **第 15 次 single-tick XS/S spec ship**（per session lessons learned）
- **第 1 次 `@PostAuthorize("hasPermission(returnObject.id, ...)")` resolve-then-check pattern**：對 alias path / multi-pathvar endpoint 補 ACL 不需自訂 strategy method，reuse 既驗 SkillPermissionStrategy SQL
- **Read-side ACL chain 完整收尾**：S121 + S122 + S123 + S124 共 4 個 spec 跨 9 個 read endpoint 一致 ACL 守則 — LAB 封測 read-side 安全 invariant 全 ship
- **「真的有第三 use case 才抽」原則應用**（per spec §2.1 Approach B 拒絕）：alias path 是 alias，不需另立 strategy overload；reuse + minimal diff 是正解
