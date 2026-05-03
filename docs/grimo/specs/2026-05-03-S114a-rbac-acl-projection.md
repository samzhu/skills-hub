# S114a: RBAC ACL with Materialized Projection (Owner + Viewer roles)

> Spec: S114a | Size: M(12) | Status: ⏳ Design
> Date: 2026-05-03

---

## 1. Goal

把現有 row-level ACL 從「直接寫 JSONB」升級為「Role-based grants（write side）+ async ACL projection（read side）」，引入 `Owner` 與 `Viewer` 兩個 role（仿 Google Drive 分享模型），多租戶 visibility（公開 / 公司 / 群組 / 私人）統一走 grants 表達；既有 list / single GET 補上 row-level ACL filter；10M 規模實測驗證的 production-grade index 設計（單 GIN + `is_public` partial）。

**起源**：
- 既有 ACL（S016/S026/S027/S038/S055）採 JSONB 直寫無 role 抽象 — 加 viewer 要呼叫 grantAcl 三次（read / write / delete 各一）
- list / single GET **無 row-level ACL filter**（靠 `*:read` default 全公開掩蓋；future private skill 引入即洩漏 — gap 已在 ACL audit 報告中標記）
- multi-tenant scope 缺 `company` principal type
- per user 「不要塞到 sync flow，async projection 較佳 UX」directive

**非目標**（本 spec 不做）：
- Production scale path（Caffeine cache / PgBouncer / read replica）→ **S114b**
- Owner transfer flow / multi-owner / EDITOR role / COMMENTER role → 後續 sub-spec
- 既有 `POST /api/v1/skills/{id}/acl` (S016 grantAcl) endpoint 移除 → 本 spec deprecate 但保留 backward compat（內部走新 service）

**Visual flow**：

```
建立 skill                    User → POST /api/v1/skills/upload (alice)
                              → Skill.create() owner_id=alice
                              → 自動 INSERT skill_grants (skill_id, type=user, principal=alice, role=OWNER)
                              → ACL projection: ["user:alice:read","user:alice:write","user:alice:delete"]

公開分享                      Owner → 在 SkillDetail 點「設為公開」
                              → POST /api/v1/skills/{id}/grants {type:public, principal:*, role:VIEWER}
                              → 202 Accepted（UI 樂觀顯示「公開中…」）
                              ⤷ async listener
                                  → INSERT skill_grants (..., type=public, principal=*, role=VIEWER)
                                  → recompute acl_entries from all grants
                                  → UPDATE skills SET acl_entries = ['..,public:*:read']
                                  → is_public 自動 TRUE (generated column)

分享給特定 user (bob)          Owner → POST /api/v1/skills/{id}/grants
                                          {type:user, principal:bob, role:VIEWER}
                              → 同上 async flow
                              → ACL projection 含 "user:bob:read"

bob 查列表                    bob 開 /browse → SkillQueryService.search()
                              → AclPrincipalExpander.expand(bob, 'read')
                              → patterns = ["user:bob:read","company:bob's-company:read",
                                            "group:bob's-groups:read","public:*:read"]
                              → SQL: WHERE acl_entries ?| ARRAY[...] ORDER BY ... LIMIT 20
                              → 含分享給 bob 的 skill ✓

撤銷分享                      Owner → DELETE /api/v1/skills/{id}/grants/{grantId}
                              → DELETE skill_grants row
                              ⤷ async listener
                                  → recompute acl_entries
                                  → UPDATE skills SET acl_entries = ...（少一條）
```

## 2. Approach

採用 **CQRS-lite + Materialized View** 模式：

- **Write side**: `skill_grants` 表存 role grants（source of truth）+ `skills.owner_id` 列存單 owner
- **Read side**: `skills.acl_entries` JSONB 為 grants 的 materialized projection，async 維護
- **Query side**: 既有 `?|` GIN + 新加 `is_public` partial index（per 10M POC 實測 8x 加速最高頻 path）

### 2.1 Approach 比較 — Write/Read 分離 vs 單寫 JSONB

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| **A. CQRS-lite: skill_grants (write) + acl_entries (read projection, async)** | 單一 grant API source-of-truth；role 抽象（Owner / Viewer 簡化 UX）；audit trail 在 skill_grants；read 走既有 GIN（無破壞）；write 立即回 202 樂觀 UX | 需 listener 維護 projection；eventual consistency window（typical < 1s） | ⭐ |
| B. 直接寫 JSONB（既有 S016 pattern） | 無 listener；strong consistency | 無 role 抽象（viewer 要 3 grantAcl 呼叫）；無 audit；無 grants list view；扎裂 multi-tenant visibility | |
| C. 純 normalized table + JOIN at query time | 純關聯模型；無冗餘 | list query 每 row 一個 EXISTS 子查；10M scale 太慢（per benchmark） | |

走 **A** — Google Drive / Notion / Dropbox 業界標準 pattern；對齊本專案 ADR-002 + S076 + S098e2 既有 projection 範本。

### 2.2 6 個產品 / UX 決策

| # | 決策 | 採用 | 理由 |
|---|---|---|---|
| 1 | Roles | **MVP: OWNER + VIEWER 兩個** | 對齊 Google Drive default 分享模型；EDITOR / COMMENTER 留 future |
| 2 | Owner 數量 | **單 Owner per skill**（每 skill 必有恰好 1 個 OWNER grant + skills.owner_id 同步） | 對齊雲端硬碟預設；多 owner defer |
| 3 | Owner transfer | **MVP 不支援** | 防誤操作；future 加「轉讓」flow |
| 4 | Visibility model | **uniform via grants**（不開 visibility column） | UI 4-radio (公開/公司/群組/私人) 是抽象；底層全是 grants entry |
| 5 | Projection 同步性 | **async AFTER_COMMIT**；grant API 回 202；UI 樂觀 | per user 異步指示；對齊 S098e2 / S076 既有 pattern |
| 6 | 既有 S016 grantAcl API | **deprecated** 但 backward compat（internally → SkillGrantService） | 1 個 grant API source-of-truth；舊 callers / 既有 test 不破壞 |

### 2.3 Role → ACL entries mapping

```java
public enum Role {
    OWNER,    // read + write + delete (3 entries per principal)
    VIEWER;   // read only (1 entry per principal)

    public List<String> permissions() {
        return switch (this) {
            case OWNER  -> List.of("read", "write", "delete");
            case VIEWER -> List.of("read");
        };
    }
}
```

範例：alice 是 owner、bob 是 viewer、acme 公司可讀、eng-team 群組可讀、設為公開：

```
skill_grants 表：
| skill_id | type    | principal_id | role   |
|----------|---------|--------------|--------|
| sk1      | user    | alice        | OWNER  |
| sk1      | user    | bob          | VIEWER |
| sk1      | company | acme         | VIEWER |
| sk1      | group   | eng-team     | VIEWER |
| sk1      | public  | *            | VIEWER |

skills.acl_entries（projection）：
[
  "user:alice:read", "user:alice:write", "user:alice:delete",
  "user:bob:read",
  "company:acme:read",
  "group:eng-team:read",
  "public:*:read"
]

skills.is_public（generated）: TRUE
```

### 2.4 Async projection idempotency

Listener 用「重算全 ACL array」策略，不靠增量更新：

```java
@ApplicationModuleListener
public void onGrantChanged(SkillGrantedEvent event) {
    // 1. 拿 advisory lock per skill_id（防併發 grant 競爭）
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(:id)::bigint)",
        Map.of("id", event.skillId()));
    // 2. 讀全部 grants
    var grants = grantRepo.findBySkillId(event.skillId());
    // 3. 重算 ACL entries
    var entries = grants.stream()
        .flatMap(g -> g.role().permissions().stream()
            .map(perm -> g.principalType() + ":" + g.principalId() + ":" + perm))
        .toList();
    // 4. UPDATE skills.acl_entries JSONB
    jdbc.update("UPDATE skills SET acl_entries = :acl::jsonb WHERE id = :id",
        Map.of("id", event.skillId(), "acl", toJson(entries)));
}
```

`pg_advisory_xact_lock` 對齊 S024-T05B `audit:` lock pattern；listener 重複觸發 idempotent（讀同 grants → 寫同 ACL）。

### 2.5 Migration backfill

V<next>__backfill_skill_grants_from_acl.sql：

```sql
-- 對每筆既有 skills.acl_entries，parse 推回 (principal, role)：
-- - 若同 principal 有 read+write+delete → OWNER
-- - 若只 read → VIEWER
-- - 其他組合（read+write only / write only / etc）→ 視為 OWNER（向上歸併，最寬鬆）
INSERT INTO skill_grants (id, skill_id, principal_type, principal_id, role, granted_by, granted_at)
SELECT
    gen_random_uuid()::text,
    s.id,
    parsed.type,
    parsed.principal,
    CASE WHEN array_length(parsed.perms, 1) = 3 THEN 'OWNER' ELSE 'VIEWER' END,
    s.author,    -- 既有 author 視為原始 grantor
    s.created_at
FROM skills s, LATERAL (
    SELECT
        split_part(entry, ':', 1) AS type,
        split_part(entry, ':', 2) AS principal,
        array_agg(DISTINCT split_part(entry, ':', 3)) AS perms
    FROM jsonb_array_elements_text(s.acl_entries) entry
    WHERE entry NOT IN ('public:read', '*:read')   -- legacy 2-segment 另處理
    GROUP BY split_part(entry, ':', 1), split_part(entry, ':', 2)
) parsed;

-- legacy 2-segment public 統一改 3-segment
INSERT INTO skill_grants (id, skill_id, principal_type, principal_id, role, granted_by, granted_at)
SELECT gen_random_uuid()::text, s.id, 'public', '*', 'VIEWER', s.author, s.created_at
FROM skills s
WHERE s.acl_entries @> '["public:read"]'::jsonb OR s.acl_entries @> '["*:read"]'::jsonb;

-- skills.owner_id backfill — 從 author 取
ALTER TABLE skills ADD COLUMN owner_id VARCHAR(255);
UPDATE skills SET owner_id = author WHERE owner_id IS NULL;
ALTER TABLE skills ALTER COLUMN owner_id SET NOT NULL;
```

Backfill 完後 trigger projection rebuild 一次，確保 acl_entries 與 grants 一致。

### 2.6 Behavior validation

| 決策 | Confidence | 證據 |
|------|------------|------|
| ACL JSONB GIN `?\|` 查詢 | Validated | 10M POC 實測 0.012-0.13ms |
| `is_public` generated column + partial index | Validated | 10M POC 實測 8x 加速最高頻 |
| `@ApplicationModuleListener` AFTER_COMMIT async | Validated | S098e2 / AuditEventListener 既有 pattern |
| Idempotent 重算 listener | Validated | S024 audit pattern + pg_advisory_xact_lock |
| `company:<id>:read` principal type expansion | Validated | 既有 `CurrentUserProvider` 已抽 `companyId` JWT claim（line 60-61）；`AclPrincipalExpander` 加一段即可 |
| Migration backfill SQL parsing | **Hypothesis** | parser 對 legacy 異常格式（malformed entries）容錯需 implementer 試 |
| Eventual consistency window UX | Validated | per user 直白指示「異步處理 優化使用體驗」；S098e2 同 pattern user 接受 |

唯一 Hypothesis 為 migration parser — implementer 在 task 階段對既有 prod-like data 試一輪即可確認。**不需 POC**（其他全 Validated）。

### 2.7 Trim list

M(12) 一個 cron tick 可能 wall hit；可 defer：

- **Frontend Share modal 漂亮版**（autocomplete user search / 多選 chip / drag-and-drop）— MVP 走最簡 4-radio + email/userId text input
- **Owner transfer flow** — defer 為 future spec
- **Multi-owner support** — defer
- **Grant audit log UI**（who granted what to whom when）— defer，DB 既有 `granted_by` + `granted_at` 紀錄

### 2.8 Research Citations

無外部框架研究。Internal references：

- `docs/grimo/adr/ADR-002-skill-aggregate-state-based.md`（aggregate + outbox + projection pattern）
- `backend/.../skill/domain/Skill.java`（既有 `acl_entries` field + grantAcl method）
- `backend/.../skill/security/AclPrincipalExpander.java`（既有 user/role/group expansion）
- `backend/.../shared/security/CurrentUserProvider.java` line 60-61（既有 `companyId` JWT claim 抽取）
- `backend/.../shared/security/MeController.java`（既有 6-claim shape）
- `backend/.../audit/AuditEventListener.java`（async listener + idempotent + advisory lock 範本）
- `backend/.../skill/SkillRatingProjectionListener.java`（S098e2 同 pattern 範本）
- `backend/src/main/resources/db/migration/V2__add_acl_entries.sql`（既有 ACL schema）
- 10M POC results（本 conversation 上輪）：`is_public` partial index 8x 加速最高頻 path
- Reference repo `samzhu/spring-acl-jsonb` README（`?|` + GIN pattern；註：其 `jsonb_path_ops` index 為已知 bug）
- 業界 RBAC + projection pattern：Google Drive sharing model / Notion permissions / Dropbox shared folders

## 3. SBE Acceptance Criteria

驗證指令：

- Backend：`./gradlew test` + `./gradlew modulithTest`
- Frontend：`cd frontend && npm test`
- Pass：所有 `@DisplayName("AC-N: ...")` / `@Tag("AC-N")` 測試綠

---

**AC-1：建立 skill 自動 grant OWNER**
- Given：alice 登入
- When：alice 上傳 skill `S`
- Then：DB `skills.owner_id = alice`；`skill_grants` 新增一筆 (skill_id=S, type=user, principal=alice, role=OWNER)；async 後 `acl_entries` 含 `["user:alice:read","user:alice:write","user:alice:delete"]`

**AC-2：Grant VIEWER to user — async projection 反映**
- Given：alice owner sk1；bob 從未 grant
- When：alice 發 `POST /api/v1/skills/sk1/grants {type:user, principal:bob, role:VIEWER}`
- Then：回 202 + body `{"grantId":"<uuid>"}`；DB `skill_grants` 新增；**polling ≤ 2s 後** `GET /api/v1/skills/sk1` 回的 `acl_entries` 含 `"user:bob:read"`；bob 在自己的 `/browse` list 看得到 sk1

**AC-3：Grant public sentinel → is_public 自動 TRUE**
- Given：sk1 目前 is_public=FALSE
- When：alice 發 grant `{type:public, principal:*, role:VIEWER}`
- Then：async 後 `skills.is_public = TRUE`；`acl_entries` 含 `"public:*:read"`；**未登入 user 在 `/browse` 看得到 sk1**

**AC-4：Revoke grant → ACL projection 移除**
- Given：bob 已 grant sk1 VIEWER
- When：alice 發 `DELETE /api/v1/skills/sk1/grants/{grantId}`
- Then：回 202；async 後 `skill_grants` 該 row 消失；`acl_entries` 不含 `"user:bob:read"`；bob 在 `/browse` 看不到 sk1

**AC-5：單 Owner 約束**
- Given：sk1 已有 alice 為 OWNER
- When：alice 發 grant `{type:user, principal:carol, role:OWNER}`
- Then：回 409 + `error: "owner_already_exists"`；DB 無新增；`skill_grants` 仍只 1 筆 OWNER

**AC-6：List query 含 row-level ACL filter（最重要）**
- Given：3 個 PUBLISHED skills：(s1=alice 私人) / (s2=alice grant bob viewer) / (s3=public)
- When：bob 開 `GET /api/v1/skills`
- Then：回 [s2, s3]，不含 s1（list 有 row-level ACL filter）
- When：alice 開 `GET /api/v1/skills`
- Then：回 [s1, s2, s3]
- When：未登入 / 第三人 (carol) 開
- Then：回 [s3]

**AC-7：Single GET 含 ACL check**
- Given：s1 為 alice 私人；無 grant 給 bob
- When：bob 發 `GET /api/v1/skills/s1`
- Then：回 404 + `error: "skill_not_accessible"`（隱藏存在性以防 enumeration）
- When：alice 發 `GET /api/v1/skills/s1`
- Then：回 200

**AC-8：Migration backfill 既有 acl_entries → skill_grants**
- Given：DB 既有 5 個 skills，acl_entries 直寫格式（per S016/S026）
- When：跑 V<next> migration
- Then：每 skill 至少 1 筆 OWNER grant（從 `user:X:read+write+delete` 推回）；含 `*:read` 的 skill 多 1 筆 public VIEWER grant；trigger projection rebuild 後 `acl_entries` 與 backfill 結果一致

**AC-9：AclPrincipalExpander 加 company expansion**
- Given：bob token 含 `company_id="acme"`
- When：`AclPrincipalExpander.expand(bob, "read")`
- Then：回的 patterns 含 `"company:acme:read"`（除 user / group / public 既有 expansion）

**AC-10：Async projection idempotency**
- Given：grant event 觸發後完成；listener 重複觸發（模擬 Modulith outbox redelivery）
- When：第二次 listener 跑同 event
- Then：`acl_entries` 結果不變（讀 grants 重算 → 寫同樣 ACL）；無 race / duplicate entries

**AC-11：Frontend Share modal — happy path**
- Given：alice 是 sk1 owner；開 SkillDetail
- When：alice 點 hero「分享」按鈕 → modal 開 → 選 visibility = 「公司」（acme）+ 額外 grant user `bob` VIEWER → Submit
- Then：modal 關閉；發 2 個 POST grant；toast「分享已更新（生效中…）」；polling 2s 後重 fetch `acl_entries` 確認含兩個新 entries

**AC-12：Modulith 邊界驗證**
- Given：本 spec 加 SkillGrantService + SkillAclProjectionListener + 改 AclPrincipalExpander 加 company expansion
- When：跑 `./gradlew modulithTest`
- Then：所有 ModularityTests PASS（無循環依賴；listener 模組依賴乾淨）

## 4. Interface / API Design

### 4.1 Backend — REST endpoints

```
POST   /api/v1/skills/{id}/grants                      # 建立 grant（owner only）
   body { principalType: 'user'|'group'|'company'|'public',
          principalId: string,
          role: 'OWNER'|'VIEWER' }
   202 { grantId: string }                            # async projection
   400 invalid_principal_type / invalid_role
   403 not_skill_owner
   409 owner_already_exists / grant_already_exists
   404 skill_not_found

DELETE /api/v1/skills/{id}/grants/{grantId}            # 撤銷 grant（owner only）
   202
   403 not_skill_owner / cannot_revoke_own_owner
   404 grant_not_found

GET    /api/v1/skills/{id}/grants                      # 列出 grants（owner or with read perm）
   200 [{ id, principalType, principalId, role, grantedBy, grantedAt }, ...]

# 既有 list / single GET 改為含 row-level ACL filter
GET    /api/v1/skills?...                              # AC-6
GET    /api/v1/skills/{id}                             # AC-7

# 既有 S016 endpoints 保留 (deprecated；internal 走 SkillGrantService)
POST   /api/v1/skills/{id}/acl                         # legacy
DELETE /api/v1/skills/{id}/acl                         # legacy
```

### 4.2 Backend — Schema migrations

```sql
-- V<n>__rbac_acl_projection.sql

-- 1. skills.owner_id NOT NULL
ALTER TABLE skills ADD COLUMN owner_id VARCHAR(255);
UPDATE skills SET owner_id = author;
ALTER TABLE skills ALTER COLUMN owner_id SET NOT NULL;
CREATE INDEX idx_skills_owner ON skills (owner_id);

-- 2. is_public generated + partial index
ALTER TABLE skills ADD COLUMN is_public BOOLEAN
  GENERATED ALWAYS AS (acl_entries @> '["public:*:read"]'::jsonb) STORED;
CREATE INDEX idx_skills_is_public ON skills (is_public) WHERE is_public = TRUE;

-- 3. skill_grants 新表
CREATE TABLE skill_grants (
    id              VARCHAR(36) PRIMARY KEY,
    skill_id        VARCHAR(36) NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    principal_type  VARCHAR(20) NOT NULL CHECK (principal_type IN ('user','group','company','public')),
    principal_id    VARCHAR(255) NOT NULL,
    role            VARCHAR(20) NOT NULL CHECK (role IN ('OWNER','VIEWER')),
    granted_by      VARCHAR(255) NOT NULL,
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (skill_id, principal_type, principal_id)
);
CREATE INDEX idx_grants_skill ON skill_grants (skill_id);
CREATE INDEX idx_grants_principal ON skill_grants (principal_type, principal_id);

-- V<n+1>__backfill_skill_grants.sql — 見 §2.5
```

### 4.3 Backend — SkillGrantService

```java
@Service
public class SkillGrantService {
    private final SkillGrantRepository grantRepo;
    private final SkillRepository skillRepo;
    private final CurrentUserProvider users;
    private final ApplicationEventPublisher events;

    @Transactional
    public String grant(String skillId, GrantRequest req) {
        var actor = users.current().userId();
        var skill = skillRepo.findById(skillId).orElseThrow(SkillNotFoundException::new);
        if (!actor.equals(skill.getOwnerId())) throw new NotSkillOwnerException();
        if (req.role() == Role.OWNER && grantRepo.existsBySkillIdAndRole(skillId, "OWNER")) {
            throw new OwnerAlreadyExistsException();
        }
        var grant = SkillGrant.create(skillId, req.principalType(), req.principalId(), req.role(), actor);
        grantRepo.save(grant);
        events.publishEvent(new SkillGrantedEvent(skillId, grant.id()));   // outbox
        return grant.id();
    }

    @Transactional
    public void revoke(String skillId, String grantId) {
        var actor = users.current().userId();
        var skill = skillRepo.findById(skillId).orElseThrow(SkillNotFoundException::new);
        if (!actor.equals(skill.getOwnerId())) throw new NotSkillOwnerException();
        var grant = grantRepo.findById(grantId).orElseThrow(GrantNotFoundException::new);
        if (grant.role() == Role.OWNER && grant.principalId().equals(actor)) {
            throw new CannotRevokeOwnOwnerException();
        }
        grantRepo.deleteById(grantId);
        events.publishEvent(new SkillRevokedEvent(skillId, grantId));
    }

    public List<SkillGrant> list(String skillId) {
        // ACL check via @PreAuthorize on controller
        return grantRepo.findBySkillId(skillId);
    }
}
```

### 4.4 Backend — Projection Listener

```java
@Component
public class SkillAclProjectionListener {
    private final SkillGrantRepository grantRepo;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @ApplicationModuleListener
    public void onGranted(SkillGrantedEvent event)  { rebuildAcl(event.skillId()); }
    @ApplicationModuleListener
    public void onRevoked(SkillRevokedEvent event)  { rebuildAcl(event.skillId()); }

    private void rebuildAcl(String skillId) {
        // 防 race
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext('acl:' || :id)::bigint)",
            Map.of("id", skillId));
        var grants = grantRepo.findBySkillId(skillId);
        var entries = grants.stream()
            .flatMap(g -> Role.valueOf(g.role()).permissions().stream()
                .map(perm -> g.principalType() + ":" + g.principalId() + ":" + perm))
            .toList();
        var json = mapper.writeValueAsString(entries);
        jdbc.update("UPDATE skills SET acl_entries = :acl::jsonb WHERE id = :id",
            Map.of("id", skillId, "acl", json));
    }
}
```

放置位置由 implementer 視 Modulith verifier 結果決定（可能 `skill` 或 `security` 模組；per S112-T01 / S098e2 deviation 啟示）。

### 4.5 Backend — AclPrincipalExpander 加 company

```java
public String[] expand(CurrentUser user, String permission) {
    var patterns = new ArrayList<String>();
    patterns.add("user:" + user.userId() + ":" + permission);
    if (user.companyId() != null && !user.companyId().isBlank()) {
        patterns.add("company:" + user.companyId() + ":" + permission);   // NEW
    }
    user.roles().forEach(r -> patterns.add("role:" + r + ":" + permission));
    user.groups().forEach(g -> patterns.add("group:" + g + ":" + permission));
    if ("read".equals(permission)) {
        patterns.add("public:*:read");                                     // CHANGED from "*:read"
    }
    return patterns.toArray(new String[0]);
}
```

### 4.6 Backend — List query 加 ACL filter

```java
// SkillQueryService.search() — 既有方法加 ACL filter
public Page<SkillReadModel> search(SearchParams params, Pageable page) {
    var patterns = expander.expand(users.current(), "read");
    var sql = """
        SELECT * FROM skills
         WHERE status = 'PUBLISHED'
           AND acl_entries ?| CAST(:patterns AS text[])
           ...其他 keyword/category filter...
         ORDER BY ... LIMIT :limit OFFSET :offset
        """;
    var sqlParams = new MapSqlParameterSource()
        .addValue("patterns", new SqlParameterValue(Types.ARRAY, patterns));
    // ...
}
```

注意：`patterns` bind 為 `SqlParameterValue(Types.ARRAY, ...)` 防 IN-list 展開（S016 既有踩雷紀錄）。

### 4.7 Frontend — Share Modal

```typescript
// frontend/src/api/grants.ts (new)
export interface SkillGrant {
  id: string
  principalType: 'user' | 'group' | 'company' | 'public'
  principalId: string
  role: 'OWNER' | 'VIEWER'
  grantedBy: string
  grantedAt: string
}

export function fetchGrants(skillId: string): Promise<SkillGrant[]> { ... }
export function createGrant(skillId: string, body: CreateGrantRequest): Promise<{grantId: string}> { ... }
export function revokeGrant(skillId: string, grantId: string): Promise<void> { ... }

// frontend/src/components/ShareModal.tsx (new)
// - 4 visibility radio (公開 / 公司 / 群組 / 私人) — 反推自 grants
// - 已 grant 列表 + 「移除」按鈕
// - 「加入分享」: type radio (user/group/company) + principal text input + role select (僅 VIEWER for MVP)
// - Submit 發 N 個 POST/DELETE grants → toast「分享已更新（生效中…）」
```

### 4.8 Frontend — SkillDetailPage hero 加「分享」按鈕

```tsx
// SkillDetailPage hero 加 ShareButton（owner only）
{skill.ownerId === me.sub && (
  <Button onClick={() => setShareOpen(true)}>分享</Button>
)}
<ShareModal open={shareOpen} skillId={skill.id} onClose={() => setShareOpen(false)} />
```

## 5. File Plan

### Backend

| File | Action | Description |
|------|--------|-------------|
| `backend/.../skill/domain/Skill.java` | modify | 加 `ownerId` 欄位 + Skill.create 自動 grant OWNER；既有 grantAcl/revokeAcl deprecate（內部 delegate to SkillGrantService） |
| `backend/.../skill/security/Role.java` | new | enum OWNER/VIEWER + permissions() |
| `backend/.../skill/security/SkillGrant.java` | new | aggregate `extends AbstractAggregateRoot` |
| `backend/.../skill/security/SkillGrantRepository.java` | new | findBySkillId / existsBySkillIdAndRole |
| `backend/.../skill/security/SkillGrantService.java` | new | grant / revoke / list orchestration |
| `backend/.../skill/security/SkillGrantController.java` | new | POST / DELETE / GET grants endpoints |
| `backend/.../skill/security/events/SkillGrantedEvent.java` | new | record |
| `backend/.../skill/security/events/SkillRevokedEvent.java` | new | record |
| `backend/.../skill/security/SkillAclProjectionListener.java` | new | async listener idempotent rebuild |
| `backend/.../skill/security/AclPrincipalExpander.java` | modify | 加 company expansion + `*:read` → `public:*:read` 一致化 |
| `backend/.../skill/query/SkillQueryService.java` | modify | search 加 ACL filter；findById 加 ACL check |
| `backend/.../skill/query/SkillQueryController.java` | modify | findById 404 if no read perm |
| `backend/src/main/resources/db/migration/V<n>__rbac_acl_projection.sql` | new | schema |
| `backend/src/main/resources/db/migration/V<n+1>__backfill_skill_grants.sql` | new | data backfill |
| `backend/src/test/.../skill/security/SkillGrantServiceTest.java` | new | AC-1/2/4/5 + idempotency |
| `backend/src/test/.../skill/security/SkillAclProjectionListenerTest.java` | new | AC-2/3/4/10 (Testcontainers) |
| `backend/src/test/.../skill/security/AclPrincipalExpanderTest.java` | modify (既有) | AC-9 |
| `backend/src/test/.../skill/query/SkillQueryServiceTest.java` | modify | AC-6 / AC-7 row-level filter |
| `backend/src/test/.../skill/security/MigrationBackfillTest.java` | new | AC-8 V<n+1> backfill 行為 |

### Frontend

| File | Action | Description |
|------|--------|-------------|
| `frontend/src/api/grants.ts` | new | SkillGrant type + fetchGrants / createGrant / revokeGrant |
| `frontend/src/hooks/useGrants.ts` | new | TanStack Query hook |
| `frontend/src/components/ShareModal.tsx` | new | 4-radio + grants list + add grant form |
| `frontend/src/pages/SkillDetailPage.tsx` | modify | hero 加「分享」按鈕（owner only） + ShareModal trigger |
| `frontend/src/types/skill.ts` | modify | Skill type 加 `ownerId` field |
| `frontend/src/pages/SkillDetailPage.test.tsx` | modify | AC-11 |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/specs/spec-roadmap.md` | modify | 加 S114a / S114b / S114c 三 row（Phase 5）|
| `docs/grimo/glossary.md` | modify | 加 Role / Grant / Owner / Viewer / Visibility 中英對照 |
| `docs/grimo/adr/ADR-005-rbac-acl-projection.md` | new | 整合既有 6 個 ACL spec + 本次 RBAC + projection 重大架構決策 |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
