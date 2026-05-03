# S121: List endpoint row-level ACL filter (LAB-blocker)

> Spec: S121 | Size: S(4-5) → shipped single-tick | Status: ✅ Shipped (v3.8.4)
> Date: 2026-05-04
> Source: Mode B Round 37 (2026-05-04) — Bug AS finding（CRITICAL，LAB-blocker）

---

## 1. Goal

修補 `SkillQueryService.search()` 完全沒套 row-level ACL filter 的 critical gap — list endpoint SQL 只有 `WHERE status='PUBLISHED'`，導致 anonymous user / 非 grantee user 仍看得到 PRIVATE skill。S116 visibility toggle (PUBLIC/PRIVATE) 仰賴 acl_entries 含 `*:read` 與否來定義可見性；list 路徑不套此 filter，visibility 設定**完全失效**。

**LAB 封測前必補**：員工封測時 PRIVATE skill 對非 grantee 仍 visible 不符 product 對「私人」的承諾；本 spec 補完 S116 ship 後該有的 end-to-end enforcement。

**起源**：Mode B Round 37（2026-05-04）E2E 端到端驗證 — A 上傳 PUBLIC + PRIVATE skill 後，anonymous list `total=2`（含 PRIVATE，但 acl_entries 無 `*:read`）；S016 ship 的 `SkillPermissionStrategy` 只實作 `@PreAuthorize` 路徑，list endpoint SQL 從未套用相同 filter。

**非目標**（本 spec 不做）：
- Single GET / download endpoint ACL（→ S122 / S123，已開 backlog）
- CurrentUserProvider anonymous fallback 升級（fallback 走 `(lab-user, [admin])` 是另一個議題；本 spec 透過 expand("read") 自動加 `*:read` 確保 PUBLIC 路徑不受影響）
- Admin role bypass — 對齊 S016 既有設計（admin bypass 集中在 `DelegatingPermissionEvaluator` `@PreAuthorize` 路徑，CQRS read 路徑不另立例外）

## 2. Approach

走 **option A — SQL clause inline**：直接在 `SkillQueryService.search()` SQL 加 `AND acl_entries ??| :aclPatterns` clause，patterns 由 `AclPrincipalExpander.expand(currentUserProvider.current(), "read")` 產生（與 `SemanticSearchService` / `SkillshubPgVectorStore` S017 既驗 pattern 一致）。對齊 `SkillPermissionStrategy` (S016) `??|` SQL escape + `SqlParameterValue(Types.ARRAY)` bind 模式。

### 2.1 Approach 比較

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| **A. Inline SQL clause + AclPrincipalExpander reuse** | 對齊 S016 既驗 pattern；不引入新抽象；最小 diff | search() ctor 多 2 deps（CurrentUserProvider + AclPrincipalExpander） | ⭐ |
| B. 包成 helper class `SkillSearchAclEnforcer` | 邏輯集中 | 1 個 caller（search()）抽 helper 是 premature；違反「真的有第三個 use case 才抽」 | |
| C. 改用 SQL view + RLS (PostgreSQL Row-Level Security) | DB-level enforcement；query path 都自然套 | 大改動；需 DB role 切換；S016 既驗 pattern 是 application-level；與既有架構不對齊 | |

走 **A**。

### 2.2 Admin bypass 設計決策

**不在 `SkillQueryService` 加 admin 特殊化路徑** — 對齊 S016 既驗：admin bypass 集中於 `DelegatingPermissionEvaluator.hasAdminRole()` 用於 `@PreAuthorize` mutation 路徑；CQRS read 路徑（list / single GET）走 ACL 自然 expand pattern。Admin 若需看 PRIVATE skill 須走 grant `role:admin:read`。此設計：
- 邏輯集中：admin bypass 只一個地方
- Read 路徑無例外：每個 user 看到的都是 acl_entries 過濾後結果，無 hidden 例外
- 未來 admin 真有 PRIVATE skill 跨組織瀏覽需求 → 走 explicit 加 `role:admin:read` 至 `*` 預設（factory 改動，可審計）

### 2.3 Anonymous 路徑

OAuth=true mode anonymous HTTP request → `SecurityContextHolder` 通常含 `AnonymousAuthenticationToken` → `CurrentUserProvider.current()` 走 fallback `(lab-user, [admin], [])`（per S115 既驗 graceful degradation）。本 spec 不改 fallback；而是利用 `AclPrincipalExpander.expand("read")` 自動加 `*:read` 至 patterns（per S026 既驗）→ anonymous user 與 admin fallback 自然只看 PUBLIC skill（acl_entries 含 `*:read`）。

PRIVATE skill `acl_entries=[user:owner:read, user:owner:write, user:owner:delete]` → fallback patterns `[user:lab-user:read, role:admin:read, *:read]` → 0 命中 → list 不顯示 ✓

### 2.4 Author-mode 與 ACL 互動

- `?author=A`（A 自己）→ skip status filter（S094a 既驗）→ ACL 仍套 → A 看自己 skill 的 acl_entries 含 `user:A:read` → 自然 match ✓
- `?author=B`（A 看 B 的）→ skip status filter → ACL 套 → A 對 B 的 PRIVATE skill 無 grant → 不顯示 ✓
- 結論：author-mode 與 ACL 完全正交，**ACL 永遠套**（無例外）

### 2.5 SQL escape `??|` 細節

對齊 S016 §2.4 #2 + SkillPermissionStrategy line 32-34 既驗：
- pgJDBC PgPreparedStatement 在 NamedParameterJdbcTemplate 之下會 parse `?` 為 placeholder
- `??` escape：pgJDBC 解碼 `??` → `?`，最終送 PostgreSQL 是 `?|` JSONB operator
- `SqlParameterValue(Types.ARRAY, String[])`：避免 `String[]` 被 NamedParameterJdbcTemplate 自動展為 IN-list（破壞 `?|` 單一 ARRAY 語意，S016 §2.4 #3）

## 3. SBE Acceptance Criteria

驗證指令：`./gradlew test --tests "io.github.samzhu.skillshub.skill.query.SkillSearchTest" -x npmBuild`

**AC-S121-1：非 owner non-admin user 看不到 PRIVATE skill**
- Given：3 個 PUBLIC skill（acl_entries 含 `*:read`）+ 1 個 PRIVATE skill（acl_entries 只含 `user:alice:read/write/delete`，無 `*:read`，alice 為 owner）
- When：user=bob (role=viewer) list（patterns expand `[user:bob:read, role:viewer:read, *:read]`）
- Then：page.content 含 3 個 PUBLIC；不含 PRIVATE skill

**AC-S121-2：被 grant 的 user 看得到該 PRIVATE skill**
- Given：3 個 PUBLIC + 1 個 PRIVATE skill `acl_entries=[user:alice:read, user:alice:write, user:bob:read]`（alice owner，已 grant bob:read）
- When：user=bob list（patterns expand 含 `user:bob:read`）
- Then：page.content 含該 PRIVATE skill

**AC-S121-3 (E2E manual)：anonymous list 看到 PUBLIC skill 但不見 PRIVATE**
- Given：A=dev-042 上傳 PUBLIC + PRIVATE skill；anonymous request（無 Authorization header）
- When：`GET /api/v1/skills?keyword=`
- Then：response `page.totalElements=1`；只 PUBLIC skill 出現

**AC-S121-4 (E2E manual)：B authenticated 沒 grant 看得到 PUBLIC 但不見 PRIVATE**
- Given：B=viewer-007 JWT；A 已上傳 PUBLIC + PRIVATE 但**未** grant B
- When：`GET /api/v1/skills` with B's JWT
- Then：response `page.totalElements=1`；只 PUBLIC

**AC-S121-5 (E2E manual)：A grant `user:viewer-007:read` on PRIVATE → B list 看到 PUBLIC + PRIVATE**
- Given：A grant 後
- When：B list
- Then：response `page.totalElements=2`；PUBLIC + PRIVATE 都出現

**AC-S121-6 (E2E manual)：A revoke grant → B list 只見 PUBLIC**
- Given：B 之前看得到（granted），A `DELETE /skills/{id}/acl?...` revoke
- When：B list
- Then：response `page.totalElements=1`；只 PUBLIC

## 4. Interface / File Plan

### Backend (production)

| File | Action | Description |
|------|--------|-------------|
| `backend/.../skill/query/SkillQueryService.java` | modify | (1) 加 imports `Types`, `SqlParameterValue`, `CurrentUserProvider`, `AclPrincipalExpander`；(2) 加 2 fields + 對應 ctor params；(3) `search()` 新增：expand patterns + 加 `acl_entries ??| :aclPatterns` clause 至 sql + countSql + 用 `SqlParameterValue(Types.ARRAY, ...)` bind |

### Backend (tests)

| File | Action | Description |
|------|--------|-------------|
| `backend/src/test/.../skill/query/SkillSearchTest.java` | modify | (1) imports Mockito + MockitoBean + CurrentUserProvider + AclPrincipalExpander + CurrentUser；(2) 加 `@MockitoBean CurrentUserProvider` + `@MockitoBean AclPrincipalExpander` 兩 mocks；(3) `@BeforeEach` 加 default stub（admin user + patterns 含 `*:read`）；(4) 既有 6 fixture acl_entries `List.of()` → `List.of("*:read")`（PUBLIC 語意）；(5) 加 2 個 S121 ACs（owner 不可見/granted 可見） |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/CHANGELOG.md` | append | v3.8.4 entry — S121 ship + verify metric |
| `docs/grimo/specs/spec-roadmap.md` | modify | S121 row：📋 → ✅ + version v3.8.4 + 一行 highlight |
| `docs/grimo/specs/archive/2026-05-04-S121-list-acl-filter.md` | new | 本 spec 從 active 移到 archive (single-tick spec doc 直接寫 archive) |

## 5. Test Plan

### 5.1 Targeted slice test (automated)

- `SkillSearchTest`（既有 11 + 新加 2 = **13 ACs**）
  - 6 既有 fixture acl_entries 改 `List.of("*:read")`（PUBLIC 語意 — 既有 ACs 行為不變，皆驗 keyword/category/author/sort logic）
  - 新加 AC-S121-1（PRIVATE 對 non-grantee 不可見）
  - 新加 AC-S121-2（PRIVATE 對 granted user 可見）

### 5.2 E2E manual (real backend + curl)

LAB-bound regression baseline — 走 mock-oauth2-server (port 9000) + Spring app OAuth=true mode：

- AC-S121-3/4/5/6 由 Round 37 既驗 fixture（A=dev-042 PUBLIC + PRIVATE 對應 acl_entries seed）走 curl 直打 backend：
  - anonymous list → 1 (only PUBLIC)
  - B list (no grant) → 1 (only PUBLIC)
  - A grant `user:viewer-007:read` on PRIVATE
  - B list (after grant) → 2 (PUBLIC + PRIVATE)
  - A revoke
  - B list → 1 (only PUBLIC)

## 6. Verification

### 6.1 自動測試

```
./gradlew test --tests "io.github.samzhu.skillshub.skill.query.SkillSearchTest" -x npmBuild
BUILD SUCCESSFUL in 2m 1s
13 tests / 0 failures / 0 errors
```

`SkillSearchTest`：13/13 PASS @ 9.6s test execution。新加 AC-S121-1（私人 skill 對非 grantee 不可見） + AC-S121-2（granted 後可見）兩個 PASS；既有 11 個 ACs 行為不變（fixture acl_entries 同步加 `*:read` 維持 PUBLIC 語意 — 通過 ACL filter）。

### 6.2 E2E manual smoke (Round 37 fixture)

```bash
# Mode B Round 37 fixture: 2 skills (1 PUBLIC + 1 PRIVATE) + B granted user:viewer-007:read

# AC-S121-3 anonymous list
$ curl http://localhost:8080/api/v1/skills?keyword=
total=1 — e2e-public-skill (含 *:read) ✓

# AC-S121-4 B no grant
$ curl ... -H "Authorization: Bearer $TOKEN_B" (revoke 後)
total=1 — e2e-public-skill ✓

# AC-S121-5 B granted
$ curl -X POST .../acl  # grant user:viewer-007:read
$ curl ... -H "Authorization: Bearer $TOKEN_B"
total=2 — e2e-public-skill + e2e-private-skill ✓

# AC-S121-6 owner A
$ curl ... -H "Authorization: Bearer $TOKEN_A"
total=2 — A 看自己 owner 的 PUBLIC + PRIVATE ✓
```

S121 完整 enforce — S116 visibility toggle 在 list endpoint **正確生效**。

### 6.3 ModularityTests

未額外執行（本 spec 不引入新 module；只在 `skill::query` 既有檔案修改 + import 已 wired 的 `shared::security` beans）。

## 7. Result

### Shipped

- `backend/.../skill/query/SkillQueryService.java`：search() 注入 `CurrentUserProvider` + `AclPrincipalExpander`，加 `acl_entries ??| :aclPatterns` SQL clause（對齊 S016 既驗 `??` escape + `SqlParameterValue(Types.ARRAY)` ARRAY bind 模式）
- `SkillSearchTest.java`：13/13 PASS（既有 11 + 新加 AC-S121-1 / AC-S121-2）；既有 fixture acl_entries 升 `List.of("*:read")` 表達 PUBLIC 語意
- E2E manual smoke 6 個案例（anonymous / B no-grant / B granted / owner A / revoke / restore）全 PASS

### Verify metric

- Test：13 tests PASS @ 9.6s（既有 11 不 regression + 新加 2 PASS）
- Build：`compileJava` 8s + `test` 2m 1s（含 Testcontainers PG boot）
- Backend devtools restart 2.9s（dev runtime smoke）
- E2E manual：6 case all PASS（anonymous list 從 total=2 → total=1 確認 critical bug 修復）

### Trim defer (per §2.6 trim list)

- **無** — 本 spec scope 緊湊，single-tick S(4-5) 完整 ship 含 unit test + E2E manual smoke。Admin bypass、CurrentUserProvider anonymous fallback 升級已於 §1 / §2.2 排除為 scope 外。

### 對 LAB 封測 impact

- LAB 封測前必補的 critical bug 已修；S116 visibility toggle 在 list endpoint 真實生效
- 員工封測時 PRIVATE skill 對非 grantee 不再 visible — 對齊 product 對「私人」的承諾
- 對齊 S122/S123（single GET + download endpoint @PreAuthorize）— 三 endpoint ACL 一致性走完整 chain

### Lessons / Pattern reuse

- **第 3 次採用 AclPrincipalExpander.expand pattern**（S016 / S017 / S121）— 「expand 對 read 自動加 *:read」是 anonymous + admin fallback 自然走 PUBLIC 路徑的關鍵
- **第 3 次採用 `??|` + `SqlParameterValue(Types.ARRAY)` 雙 hack**（S016 / S017 / S121）— `?` placeholder 衝突的 codebase canonical 解
- **第 6 次 single-tick XS/S spec ship**（per session lessons learned）— S=4-5 含 1 production change + 1 test class update + 6-case E2E smoke + 完整 §1-§7 spec doc 一個 commit 落地
- **MockitoBean stub 既驗 admin patterns**：slice test 不啟動 SecurityContext 也能驗 ACL filter SQL behavior（mock CurrentUserProvider + AclPrincipalExpander 組合即 cover 所有 user role / pattern 場景）
