# S177: is_public-first Search Visibility

> Spec: S177 | Size: S(9) | Status: ⏳ Plan
> Date: 2026-05-15
> Origin: LAB production bug — two private skills were hidden from keyword browse but still visible through semantic search.
> Related: S017, S026, S114a, S116, S163, S169, S170

---

## 1. 目標

把「公開可見」從 ACL array 拆出來，讓未登入搜尋直接看 `is_public=true`，登入後才額外用 ACL 判斷自己被授權的 private skill。

User 在 LAB 建了兩個應為 private 的 skill：

| Skill ID | 名稱 | production SQL 狀態 | production semantic search |
|---|---|---|---|
| `1b18ce61-7770-4966-a924-a87b9a8877ed` | `translate-subtitle` | `skills.is_public=false`；`skills.acl_entries` 無 `public:*:read` | 匿名可搜到 |
| `bbe2f0c0-1255-4193-841c-376d022296a2` | `transcribe-video` | `skills.is_public=false`；`skills.acl_entries` 無 `public:*:read` | 匿名可搜到 |

目前 bug 的直接原因是 `vector_store.acl_entries` 還殘留 `public:*:read`。但更大的設計問題是：`public:*:read` 同時扮演「公開狀態」與「ACL principal」，讓搜尋、detail permission、grant projection 都要記得把 public 塞進 ACL pattern。S177 改成：

```text
is_public = true      → 任何人可讀
acl_entries           → user/group/company 明確授權可讀/寫/刪
public:*:read         → 移除，不再寫入新資料
```

相依檢查：S017/S114a/S169/S170 都已 shipped/archive。本 spec 會修改既有欄位語意與查詢 SQL，但不依賴未完成 spec 的新型別。

資料相容性決策：LAB 會清掉資料重測，所以 S177 不做舊資料保留/backfill。V26 只要把 schema 改成新語意；public/private 狀態會由重新 publish 與 grant flow 建立。

---

## 2. 研究與設計

### 2.1 查到的事實

| 來源 | 查到什麼 | 對設計的影響 |
|---|---|---|
| `curl '.../api/v1/skills?keyword=影片&page=0&size=20&sort=downloadCount,desc'` | 匿名 keyword browse 回 `{"content":[],"page":{"totalElements":0}}` | `skills` 表的可見性資料是正確的；bug 不是 keyword browse 的 SQL。 |
| `curl '.../api/v1/search/semantic?q=影片'` | 匿名 semantic search 回兩筆 private skill | semantic search 目前依賴 `vector_store.acl_entries`，投影資料一錯就漏。 |
| Production SQL：`skills` row | 兩筆 `is_public=false` 且 `acl_entries` 只有 owner ACL | `is_public` 是 user 想要的可見性判斷實體。 |
| Production SQL：`vector_store` row | 兩筆 `acl_entries` 含 `public:*:read` | `vector_store` 是搜尋索引，應保存可讀範圍，但 public 不應混成 ACL pseudo entry。 |
| `V16__rbac_acl_projection.sql` | `skills.is_public` 是 generated column：`acl_entries @> '["public:*:read"]'::jsonb` | 新設計必須把 generated column 改成普通 boolean；LAB 會清資料重測，不需要保留舊值。 |
| `Skill.create(CreateSkillCommand)` | 現在新增 PUBLIC skill 時會把 `public:*:read` 放進 `acl_entries`；DB generated `is_public` 因此自動變 true | 目前「新增 skill 就有 `is_public`」是成立的，但來源是 ACL pseudo-principal。S177 移除 pseudo-principal 後，`Skill` aggregate 必須直接持久化 `is_public`。 |
| `SkillAclProjectionListener` | rebuild ACL 會從 `skill_grants` 展開到 `skills.acl_entries` 與 `vector_store.acl_entries` | 這裡要改成：只展開 non-public explicit grants；不 seed owner/public grants、不更新 `skills.is_public`。 |
| `SkillQueryService` / `SemanticSearchService` / `JdbcSkillAclReadEvaluator` / `SkillPermissionStrategy` | read patterns 都會補 `public:*:read` | 這些要改成 `is_public OR acl_entries ??| :patterns`。 |
| PostgreSQL 官方 JSON operators docs | `jsonb ?| text[]` 可檢查 JSONB array 是否含任一字串 | ACL 明確授權仍可沿用 `??| :patterns`；public visibility 不必走 JSONB。Source: https://www.postgresql.org/docs/current/functions-json.html |
| Spring Modulith 官方 event docs | `@ApplicationModuleListener` 可用作 async transactional projection listener | grant/created event projection 仍由 listener 維護，但 source 欄位要拆清楚。Source: https://docs.spring.io/spring-modulith/reference/events.html |

### 2.2 Production 證據鏈

Cloud Run 服務：

| 欄位 | 值 |
|---|---|
| Project | `cfh-vibe-lab` |
| Region | `asia-east1` |
| Service | `skillshub` |
| Revision | `skillshub-00024-zc9` |
| Image | `asia-east1-docker.pkg.dev/cfh-vibe-lab/skillshub/skillshub:20260515-013639` |
| Profile | `lab,gcp` |
| Cloud SQL | `cfh-vibe-lab:asia-east1:skillshub-db` |

Request/log 對照：

| 時間 | Request | 結果 |
|---|---|---|
| 2026-05-15T03:49:55Z 附近 | `GET /api/v1/skills?keyword=影片...` | HTTP 200；response empty |
| 2026-05-15T03:49:55Z 附近 | `GET /api/v1/search/semantic?q=影片` | HTTP 200；response 含兩筆 private skill |

SQL 是透過一次性 Cloud Run Job 用 `postgres:18-alpine` + `psql` 查同一個 Cloud SQL instance；只讀查詢，job 查完已刪。Job 使用 runtime service account、VPC/Cloud SQL 設定與 Secret Manager 密碼注入。

### 2.3 架構設計

新的資料語意：

| 欄位 / table | 新角色 | 範例 |
|---|---|---|
| `skills.is_public` | 公開可見 source-of-truth；由 `Skill` aggregate 直接持有並寫入 | `true` 表示 anonymous、所有登入 user 都可讀 |
| `skills.acl_entries` | 明確授權投影 | `["user:u_715d70:read","user:u_715d70:write","user:u_715d70:delete","group:g_eng:read"]` |
| `vector_store.is_public` | 搜尋索引的公開可見性投影；semantic search 用它過濾匿名結果 | `true` |
| `vector_store.acl_entries` | 搜尋索引的明確授權投影；semantic search 用它過濾登入結果 | `["user:u_715d70:read","group:g_eng:read"]` |
| `skill_grants` public VIEWER row | command-side 表達 owner 的公開可見性設定；不是 explicit ACL | `principal_type='public', principal_id='*', role='VIEWER'` |

Keyword/detail 查詢規則：

```text
anonymous browse/search:
  s.status = 'PUBLISHED'
  AND s.is_public = true

authenticated browse/detail:
  s.status = 'PUBLISHED'
  AND (
    s.is_public = true
    OR s.acl_entries ??| ARRAY[
      'user:<id>:read',
      'group:<id>:read',
      'company:<id>:read'
    ]
  )
```

Semantic 查詢規則：

```text
anonymous semantic:
  s.status = 'PUBLISHED'
  AND vs.is_public = true

authenticated semantic:
  s.status = 'PUBLISHED'
  AND (
    vs.is_public = true
    OR vs.acl_entries ??| ARRAY[
      'user:<id>:read',
      'group:<id>:read',
      'company:<id>:read'
    ]
  )
```

資料流：

```text
Publish page / visibility toggle
  PUBLIC / PRIVATE
      |
      v
skill_grants
  OWNER grant: user:u_715d70 OWNER
  public grant: public:* VIEWER  (only when public)
      |
      v
Skill aggregate + projection listeners
      |
      +--> skills.is_public = Skill.publicSkill
      |
      +--> skill_grants public VIEWER row = mirrors Skill public visibility
      |
      +--> skills.acl_entries = explicit non-public permissions
      |
      +--> vector_store.is_public = Skill.publicSkill
      |
      +--> vector_store.acl_entries = same explicit non-public permissions
```

具體資料例子：

| 場景 | `skills.is_public` | `skills.acl_entries` | `skill_grants` |
|---|---:|---|---|
| Alice public skill | `true` | `["user:alice:read","user:alice:write","user:alice:delete"]` | Alice OWNER + public VIEWER |
| Alice private skill | `false` | `["user:alice:read","user:alice:write","user:alice:delete"]` | Alice OWNER |
| Alice private shared to Eng | `false` | `["user:alice:read","user:alice:write","user:alice:delete","group:eng:read"]` | Alice OWNER + Eng VIEWER |

### 2.4 做法比較

| 做法 | 採用 | 理由 |
|---|---:|---|
| A: `is_public` first，ACL 只放 explicit grants | yes | 最符合 user mental model；anonymous 查詢可以用 btree partial index `idx_skills_is_public`；public 不再被當成假 principal。 |
| B: 只修 `vector_store.acl_entries` mirror `skills.acl_entries` | no | 能修這次外漏，但仍保留 `public:*:read` 混在 ACL 裡；每條 read path 都要補 pseudo-principal。 |
| C: semantic search 查 `s.is_public OR s.acl_entries`，但保留 public ACL 寫入 | no | SQL 會安全，但資料模型仍兩套 source，未來 projection mismatch 會再出現。 |
| D: 刪掉 `vector_store.acl_entries` 欄位 | no | 可以做，但不是這次必要條件；先停止依賴它判斷 public，降低 migration 風險。 |

### 2.5 主要設計決策

| 決策 | 選擇 | 理由 |
|---|---|---|
| `is_public` 欄位型態 | generated column → ordinary `BOOLEAN NOT NULL DEFAULT FALSE` | 移除 `public:*:read` 後，generated formula 不能再代表 public。LAB 會清資料，所以 migration 不保留舊值。 |
| `is_public` 寫入權責 | `Skill` aggregate 直接持有 `@Column("is_public") private boolean publicSkill` | 公開/私人是 Skill 自己的可見性狀態；domain event 集中在 Skill aggregate。 |
| public visibility grant 是否保留 | 保留 `skill_grants(public, *, VIEWER)` | Share UI / visibility toggle 已用 grants 顯示公開狀態；作為 `Skill.publicSkill` 的管理面 mirror。 |
| public visibility grant 是否展成 ACL | 不展開 | `acl_entries` 只放 user/group/company explicit permissions。 |
| public visibility grant consistency | `skills.is_public` 與 `skill_grants(public, *, VIEWER)` 同 transaction 寫入 | UI 立即讀 grants 判斷公開狀態；新增與切換都不能等 async listener 補齊。 |
| public visibility grant id | 先產生 12 hex opaque grant id，再交給 `Skill.makePublic(...)` 記進 event | `skill_grants.id` 只是資料庫主鍵，無業務語意、不加 prefix；12 hex 比既有 6 hex user/group id 更適合高筆數 grant；service 層保留 collision retry；前端 `POST /grants` 仍可立即拿到 `{grantId}`。 |
| `SkillCreatedEvent` payload | 不新增 visibility / publicGrantId | 新增流程的強一致資料由 command service 同 TX 寫完；created event 不負責 seed owner/public grants。 |
| `SkillVisibilityChangedEvent` audit | 寫入 `domain_events` audit log | 公開/轉私人會改 `skills.is_public` 與 public VIEWER grant；需要可查「誰在何時改了可見性」。 |
| semantic SQL ACL source | `vector_store` 保存 `is_public` + explicit read ACL scope，查詢時過濾可讀結果 | `vector_store` 是全文/向量索引；仍 JOIN `skills` 確認 `PUBLISHED` 狀態，但可讀範圍由搜尋索引自己的投影欄位判斷。 |
| anonymous pattern | 不建 pattern array，直接 `is_public=true` | 少掉 `public:*:read`，SQL 更短，符合「未登入只看公開」。 |
| authenticated pattern | principal keys 加 `:read`，不補 public | public 由 `is_public=true` 負責；ACL 只判斷明確授權。 |

### 2.6 Task 邊界提示

| Task 候選 | Class / file | 來源 | 正向情境 | 反向情境 | POC |
|---|---|---|---|---|---|
| T01 | schema + aggregate public state | V16 generated column / `Skill.create` | `skills.is_public` 轉 ordinary boolean、`vector_store.is_public` 新增、PUBLIC create 寫 true | schema 仍不可是 generated column；ACL 不含 public pseudo entry | not required |
| T02 | grant mirror + ACL projection | command service + projection | create/toggle 同 TX 寫 owner/public grant mirror；ACL projection 只展 non-public explicit grants | revoke public grant 後 `Skill.publicSkill=false` 且 explicit ACL 保留 | not required |
| T03 | keyword/detail permission SQL | `SkillQueryService`, `JdbcSkillAclReadEvaluator`, `SkillPermissionStrategy`, `DelegatingPermissionEvaluator`, `AclPrincipalExpander` | anonymous 可看 public；logged-in 可看 public + granted private | 未授權 user 看不到 private；public 不給 write/delete | not required |
| T04 | semantic vector visibility | `SemanticSearchService`, `SkillshubPgVectorStore`, `SearchProjection` | anonymous semantic 只搜 `vector_store.is_public=true`；logged-in semantic 搜 public + granted private | `vector_store.acl_entries` 測試 fixture 即使含 public 也不造成外漏 | not required |

### 2.7 Confidence Classification

| 設計決策 | Confidence | 理由 |
|---|---|---|
| anonymous 用 `is_public=true` | Validated | production SQL 已能正確標出兩筆 private `is_public=false`；PostgreSQL btree boolean partial index 已存在。 |
| authenticated 用 `is_public OR acl_entries ??| patterns` | Validated | 現有 JSONB ACL `??|` path 已在 S017/S169 使用；只移除 public pseudo pattern。 |
| `is_public` 要改 ordinary boolean | Validated | V16 generated formula 明確依賴 `public:*:read`；移除 public ACL 必須改欄位定義。 |
| `vector_store.is_public` 作為 semantic public filter | Validated | current code 已有 `vector_store.acl_entries` read filter 與 `JOIN skills` status filter；S177 只把 public 從 ACL 字串拆成 boolean 投影。 |
| V26 migration 一次完成 generated column rewrite + ACL cleanup | Validated | 不保留舊資料時可 drop generated column 後 add ordinary boolean；migration test 只需確認欄位不是 generated column。 |

### 2.8 前後端連動檢查

這次不需要改前端 API request/response shape；需要同步的是前端註解與測試名稱，避免繼續把 public grant 說成 `acl_entries` 裡的 `public:*:read`。

| 前端檔案 | 後端契約 | 檢查結果 |
|---|---|---|
| `frontend/src/pages/PublishPage.tsx` | `SkillCommandService.uploadSkill(... Visibility visibility, ...)` 仍收 `PUBLIC` / `PRIVATE` | UI 已送 `visibility` multipart 欄位，不需要改。後端改成用此欄位直接寫 `skills.is_public`。 |
| `frontend/src/api/skills.ts` | `POST /api/v1/skills` request 仍使用 `visibility` FormData | Type `Visibility = 'PUBLIC' \| 'PRIVATE'` 可保留；註解要改成「後端寫 `is_public`」，不是「由 ACL 推導」。 |
| `frontend/src/components/VisibilityToggleButton.tsx` | `GET/POST/DELETE /api/v1/skills/{id}/grants` 仍管理 public VIEWER grant | 前端仍可用 `skill_grants(public, *, VIEWER)` 判斷按鈕文字；後端把 public grant/revoke 轉成 `Skill` aggregate visibility change，不展開成 ACL entry。 |
| `frontend/src/components/ShareModal.tsx` | grants list 仍包含 public grant | UI 的「已公開瀏覽」判斷可保留；註解要改成 public VIEWER grant，不再寫 `public:*:read`。 |
| `frontend/src/types/skill.ts` / detail page | Skill response 目前沒有 `isPublic` / `visibility` 欄位 | 不新增 response 欄位；detail page 繼續靠 `viewerPermissions.canShare` 顯示分享/公開按鈕。 |
| `e2e/tests/_fixtures.ts` | test API / upload flow 仍接受 `visibility?: 'PUBLIC' \| 'PRIVATE'` | fixture shape 不改；實作後 E2E seed 出來的 DB row 應該是 `is_public` true/false，ACL 不含 public pseudo entry。 |

### 2.9 新增 / 修改 / 刪除路徑檢查

| 使用者動作 | 前端入口 | 後端入口 | S177 要檢查什麼 |
|---|---|---|---|
| 新增 skill 選公開/私人 | `PublishPage` radio → `uploadSkill(..., visibility)` | `POST /api/v1/skills/upload` → `Skill.create(cmd.visibility())` + `grantRepo.save(ownerGrant)` + optional `grantRepo.save(publicGrant)` in same TX | PUBLIC commit 後立刻同時有 `skills.is_public=true`、OWNER grant、public VIEWER grant；PRIVATE commit 後有 `skills.is_public=false`、OWNER grant、無 public grant；兩者 `acl_entries` 都不可含 `public:*:read`。 |
| 修改 description/category | `EditSkillModal` → `updateSkill(id, {description, category})` | `PUT /api/v1/skills/{id}` → `Skill.update(UpdateSkillCommand)` | 這條不改公開狀態；`repo.findById()` 讀出的 `publicSkill` 應原樣保存。並發公開/metadata 修改由 `@Version` 樂觀鎖處理。 |
| 公開分享 | `VisibilityToggleButton` / `ShareModal` → `createGrant({public, *, VIEWER})` | `POST /api/v1/skills/{id}/grants` → `SkillGrantService.grant()` → `Skill.makePublic(...)` + `grantRepo.save(publicGrant)` + `skillRepo.save(skill)` in same TX | API commit 後立刻同時有 `skills.is_public=true` 與 public VIEWER grant；`skills.acl_entries` / `vector_store.acl_entries` 仍不含 `public:*:read`。 |
| 轉為私人 | `VisibilityToggleButton` / `ShareModal` → `revokeGrant(publicGrantId)` | `DELETE /api/v1/skills/{id}/grants/{grantId}` → `SkillGrantService.revoke()` → `Skill.makePrivate(...)` + `grantRepo.deleteById(...)` + `skillRepo.save(skill)` in same TX | API commit 後立刻同時有 `skills.is_public=false` 且 public VIEWER grant 已刪；explicit user/group/company ACL 保留。 |
| 刪除 skill | `MySkillsPage` → `deleteSkill(id)` | `DELETE /api/v1/skills/{id}` → `SkillCommandService.deleteSkill()` | hard delete 會移除 `skills` row；`SearchProjection.onSkillDeleted` 主動刪 vector row。S177 不新增刪除 API，但要保證殘留 vector row 不可靠 `public:*:read` 被搜到。 |
| suspend / reactivate | admin action → existing command API | `POST /suspend` / `POST /reactivate` → `SearchProjection` delete/re-embed | reactivate re-embed 不可重新加 `public:*:read`；semantic SQL 以 `vector_store.is_public OR vector_store.acl_entries` 判斷。 |

---

## 3. 驗收條件（SBE）

驗證命令：

執行：`cd backend && ./gradlew test`

通過條件：所有帶 `AC-S177-*` 的測試都是綠燈，且 Flyway migration test 證明 v26 schema 不是 generated `is_public`。

| AC | 優先級 | 驗證方式 | 標題 |
|---|---|---|---|
| AC-S177-1 | 必做 | Test | migration 把 `is_public` 改成 ordinary boolean |
| AC-S177-1b | 必做 | Test | 新增 skill 時直接寫 `is_public`，不靠 public ACL entry |
| AC-S177-2 | 必做 | Test | anonymous keyword browse 只回 public skill |
| AC-S177-3 | 必做 | Test | logged-in keyword browse 回 public skill + 自己可讀 private skill |
| AC-S177-4 | 必做 | Test | anonymous semantic search 不用 `vector_store.acl_entries` 判斷 public |
| AC-S177-5 | 必做 | Test | logged-in semantic search 回 public skill + granted private skill |
| AC-S177-6 | 必做 | Test | detail/read permission 用 `is_public OR explicit ACL` |
| AC-S177-7 | 必做 | Test | public visibility toggle 同 TX 更新 `is_public` 與 public grant，不寫 `public:*:read` |
| AC-S177-8 | 建議 | Demo | production deploy 後兩筆 private skill 不再被匿名 semantic search 找到 |

**AC-S177-1: migration 把 `is_public` 改成 ordinary boolean**
- Given（前提）v25 schema 的 `skills.is_public` 是 generated column
- When（動作）Flyway 套用 V26
- Then（結果）`information_schema.columns.generation_expression` 對 `skills.is_public` 為 null 或空值

**AC-S177-1b: 新增 skill 時直接寫 `is_public`，不靠 public ACL entry**
- Given（前提）Alice publish 一個 `visibility=PUBLIC` 的 skill
- When（動作）系統建立 `skills` row
- Then（結果）`skills.is_public=true`
- And（而且）`skill_grants` 有 Alice OWNER grant
- And（而且）`skill_grants` 有 public VIEWER grant
- And（而且）`skills.acl_entries` 不含 `public:*:read`
- Given（前提）Alice publish 一個 `visibility=PRIVATE` 的 skill
- When（動作）系統建立 `skills` row
- Then（結果）`skills.is_public=false`
- And（而且）`skill_grants` 有 Alice OWNER grant
- And（而且）`skill_grants` 沒有 public VIEWER grant

**AC-S177-2: anonymous keyword browse 只回 public skill**
- Given（前提）DB 有 public skill A、private skill B、private shared-to-Bob skill C
- When（動作）anonymous 查 `/api/v1/skills?keyword=<common>`
- Then（結果）response 只包含 A
- And（而且）不包含 B 或 C

**AC-S177-3: logged-in keyword browse 回 public skill + 自己可讀 private skill**
- Given（前提）DB 有 public skill A、private skill B、private shared-to-Bob skill C
- When（動作）Bob 查 `/api/v1/skills?keyword=<common>`
- Then（結果）response 包含 A 與 C
- And（而且）不包含 B

**AC-S177-4: anonymous semantic search 不用 `vector_store.acl_entries` 判斷 public**
- Given（前提）private skill B 的 `skills.is_public=false`
- And（而且）測試 fixture 故意讓 `vector_store.acl_entries` 含 `public:*:read`
- When（動作）anonymous 查 `/api/v1/search/semantic?q=<common>`
- Then（結果）response 不包含 B

**AC-S177-5: logged-in semantic search 回 public skill + granted private skill**
- Given（前提）DB 有 public skill A、private shared-to-Bob skill C，兩筆都有 vector row
- When（動作）Bob 查 `/api/v1/search/semantic?q=<common>`
- Then（結果）response 包含 A 與 C
- And（而且）不包含未授權 private skill B

**AC-S177-6: detail/read permission 用 `is_public OR explicit ACL`**
- Given（前提）public skill A 的 `acl_entries` 不含 `public:*:read`
- When（動作）anonymous GET `/api/v1/skills/{A}`
- Then（結果）HTTP 200
- And（而且）random user GET private skill B 得 HTTP 403

**AC-S177-7: public visibility toggle 同 TX 更新 `is_public` 與 public grant，不寫 `public:*:read`**
- Given（前提）Alice owner skill B，且 B 是 private
- When（動作）Alice 新增 public VIEWER grant
- Then（結果）`skills.is_public=true`
- And（而且）`skill_grants` 有 `principal_type='public'`, `principal_id='*'`, `role='VIEWER'`
- And（而且）`skills.acl_entries` 不含 `public:*:read`
- When（動作）Alice revoke public VIEWER grant
- Then（結果）`skills.is_public=false`
- And（而且）`skill_grants` 沒有 public VIEWER grant

**AC-S177-8: production deploy 後兩筆 private skill 不再被匿名 semantic search 找到**
- Given（前提）LAB 部署包含 S177 的新 revision
- When（動作）執行 `curl -sS 'https://skillshub-644359853825.asia-east1.run.app/api/v1/search/semantic?q=%E5%BD%B1%E7%89%87'`
- Then（結果）response 不包含 `1b18ce61-7770-4966-a924-a87b9a8877ed`
- And（而且）response 不包含 `bbe2f0c0-1255-4193-841c-376d022296a2`

### 非功能需求檢查

| 分類 | 對應驗收 | 說明 |
|---|---|---|
| Performance | AC-S177-2, AC-S177-4 | anonymous keyword 查詢走 `skills.status + skills.is_public`；anonymous semantic 查詢走 `skills.status + vector_store.is_public`，避免 JSONB public pattern。 |
| Security | AC-S177-2 到 AC-S177-8 | private skill 不可因 `vector_store` ACL fixture 或 pseudo-principal 混用被匿名看到。 |
| Reliability | AC-S177-1, AC-S177-7 | migration 不做舊資料保留；重測資料由 publish/grant flow 維護同一個 `is_public` source。 |
| Usability | N/A | 不改 UI 控制；「公開分享/轉為私人」按鈕維持既有行為。 |
| Maintainability | AC-S177-1, AC-S177-6 | public visibility 與 explicit ACL 拆欄位，未來讀 SQL 不再到處補 `public:*:read`。 |

### AC well-formedness check

| AC | Singular | Unambiguous | Implementation-free | Verifiable | Bounded |
|---|---|---|---|---|---|
| AC-S177-1 | yes | yes | yes | yes | yes |
| AC-S177-1b | yes | yes | yes | yes | yes |
| AC-S177-2 | yes | yes | yes | yes | yes |
| AC-S177-3 | yes | yes | yes | yes | yes |
| AC-S177-4 | yes | yes | yes | yes | yes |
| AC-S177-5 | yes | yes | yes | yes | yes |
| AC-S177-6 | yes | yes | yes | yes | yes |
| AC-S177-7 | yes | yes | yes | yes | yes |
| AC-S177-8 | yes | yes | yes | yes | yes |

---

## 4. 介面與 API 設計

本 spec 不新增 public HTTP API，不新增前端頁面。

### 4.1 DB migration contract

V26 需要完成三件事：

1. 把 generated `is_public` 改成 ordinary `BOOLEAN NOT NULL DEFAULT FALSE`。
2. 不保留舊 rows 的 public/private 值；LAB 會清資料重測。
3. 新增 `vector_store.is_public BOOLEAN NOT NULL DEFAULT FALSE`，讓 semantic search 不再用 public ACL 字串判斷公開。
4. 從 `skills.acl_entries` 與 `vector_store.acl_entries` 移除 `public:*:read`。

候選 SQL 形狀：

```sql
ALTER TABLE skills ADD COLUMN is_public_v2 BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE skills DROP COLUMN is_public;
ALTER TABLE skills RENAME COLUMN is_public_v2 TO is_public;
CREATE INDEX IF NOT EXISTS idx_skills_is_public ON skills (is_public) WHERE is_public = TRUE;
ALTER TABLE vector_store ADD COLUMN IF NOT EXISTS is_public BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS idx_vector_store_is_public ON vector_store (is_public) WHERE is_public = TRUE;
```

ACL cleanup shape：

```sql
UPDATE skills
   SET acl_entries = acl_entries - 'public:*:read'
 WHERE acl_entries @> '["public:*:read"]'::jsonb;

UPDATE vector_store
   SET acl_entries = acl_entries - 'public:*:read'
 WHERE acl_entries @> '["public:*:read"]'::jsonb;
```

T01 的 migration test 只需確認 `is_public` 不是 generated column，以及 cleanup SQL 不留下 `public:*:read`。

### 4.2 Read SQL contracts

Anonymous list/search：

```sql
WHERE s.status = 'PUBLISHED'
  AND s.is_public = TRUE
```

Authenticated list/search：

```sql
WHERE s.status = 'PUBLISHED'
  AND (
    s.is_public = TRUE
    OR s.acl_entries ??| :readPatterns
  )
```

`readPatterns`：

```java
principalContextService.currentPrincipalKeys()
        .stream()
        .map(key -> key + ":read")
        .toList();
```

不再 append `public:*:read`。

### 4.2a Skill aggregate persistence contract

`Skill` 需要新增持久化欄位：

```java
@Column("is_public")
private boolean publicSkill;
```

`Skill.create(cmd)`：

```java
var visibility = cmd.visibility() == null ? Visibility.PUBLIC : cmd.visibility();
skill.publicSkill = visibility == Visibility.PUBLIC;
skill.aclEntries = ownerExplicitAclOnly(author);
```

這裡的重點是：PUBLIC/PRIVATE 不再透過 `acl_entries` 推導。PUBLIC skill 的 owner ACL 仍是 `user:<author>:read/write/delete`，公開可見性由 `skills.is_public=true` 表達。

公開/私人變更同樣由 `Skill` aggregate method 表達：

```java
public void makePublic(String changedBy, String publicGrantId) {
    if (this.publicSkill) return;
    this.publicSkill = true;
    this.updatedAt = Instant.now();
    registerEvent(new SkillVisibilityChangedEvent(id, true, publicGrantId, changedBy, updatedAt));
}

public void makePrivate(String changedBy) {
    if (!this.publicSkill) return;
    this.publicSkill = false;
    this.updatedAt = Instant.now();
    registerEvent(new SkillVisibilityChangedEvent(id, false, null, changedBy, updatedAt));
}
```

`SkillVisibilityChangedEvent` 是 Skill aggregate domain event；public VIEWER grant 是管理面 mirror，不是 `is_public` 的 source-of-truth。公開時 event 攜帶已產生的 `publicGrantId`，讓 API 可先回 `{grantId}`。`SkillGrantService` 必須在同一個 transaction 內同時保存 `Skill` 與 public VIEWER grant mirror。

`SkillCreatedEvent` 不新增 visibility / publicGrantId。新增 PUBLIC/PRIVATE 的強一致資料由 `SkillCommandService.uploadSkill` 同 TX 寫入；created event listener 不可 seed OWNER/public grant，也不可更新 `skills.is_public`。

### 4.3 Semantic search contract

`SkillshubPgVectorStore` ACL SQL 仍 JOIN `skills` 確認 skill 狀態，但 public / explicit grant 可讀範圍改用 `vector_store` 自己的投影欄位：

```sql
SELECT vs.id, vs.content, vs.metadata, vs.embedding <=> ? AS distance
  FROM vector_store vs
  JOIN skills s ON s.id = vs.skill_id
 WHERE s.status = 'PUBLISHED'
   AND (
        vs.is_public = TRUE
        OR vs.acl_entries ??| ?::text[]
   )
   AND vs.embedding <=> ? < ?
 ORDER BY distance
 LIMIT ?
```

Anonymous caller 可以傳 empty patterns；SQL 仍靠 `vs.is_public=true` 回 public rows。已登入 caller 傳 user/group/company read patterns。`skills` join 只負責排除 DRAFT/SUSPENDED/已刪除的 skill，不負責 public 判斷。

### 4.4 Permission evaluator contract

`read` permission：

```sql
SELECT EXISTS (
  SELECT 1 FROM skills
   WHERE id = :skillId
     AND (
       is_public = TRUE
       OR acl_entries ??| :patterns
     )
)
```

`write/delete/suspend/reactivate` permission：

```sql
SELECT EXISTS (
  SELECT 1 FROM skills
   WHERE id = :skillId
     AND acl_entries ??| :patterns
)
```

`AclPrincipalExpander` 與 `DelegatingPermissionEvaluator` 不再自行補 `public:*:read`。

### 4.5 Projection contract

`SkillAclProjectionListener.rebuildAcl(skillId)`：

```text
grants = skill_grants by skillId
entries = all non-public grants expanded by Role.permissions()

UPDATE skills
   SET acl_entries = :entries
 WHERE id = :skillId

UPDATE vector_store
   SET acl_entries = :entries
 WHERE skill_id = :skillId
```

Visibility changed projection（同 TX 寫 Skill 後，搜尋索引用 listener / projection path 對齊）：

```sql
UPDATE vector_store
   SET is_public = :isPublic
 WHERE skill_id = :skillId
```

Public visibility service transaction：

```text
make public:
  skill.makePublic(actor, publicGrantId)
  grantRepo.save(public VIEWER grant with publicGrantId)
  skillRepo.save(skill)

make private:
  skill.makePrivate(actor)
  grantRepo.delete(public VIEWER grant)
  skillRepo.save(skill)
```

以上三步必須在同一個 `@Transactional` method 內完成；不能交給 async listener 補 public grant mirror。`SkillVisibilityChangedEvent` listener 若存在，只能做 audit/search 等非同步 side effects，不負責補齊強一致資料。

Create skill service transaction：

```text
upload visibility=PUBLIC:
  skill = Skill.create(... publicSkill=true)
  ownerGrant = SkillGrant.create(... OWNER)
  publicGrant = SkillGrant.create(... public/* VIEWER)
  skillRepo.save(skill)
  grantRepo.save(ownerGrant)
  grantRepo.save(publicGrant)

upload visibility=PRIVATE:
  skill = Skill.create(... publicSkill=false)
  ownerGrant = SkillGrant.create(... OWNER)
  skillRepo.save(skill)
  grantRepo.save(ownerGrant)
```

新增流程同樣要強一致；`SkillAclProjectionListener.onSkillCreated` 不再負責 seed OWNER/public grants。

`SearchProjection` create/publish/reactivate re-embed：

```text
read Skill aggregate
write vector_store.is_public = skill.publicSkill
write vector_store.acl_entries = skill.aclEntries
do not infer public visibility from owner
do not add public:*:read
```

---

## 5. 檔案規劃

| 檔案 | 動作 | 說明 |
|---|---|---|
| `backend/src/main/resources/db/migration/V26__is_public_first_search_visibility.sql` | new | 改 `skills.is_public` ordinary boolean、新增 `vector_store.is_public`、清 `public:*:read`；不做舊資料保留。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` | modify | 加 `@Column("is_public") private boolean publicSkill`；PUBLIC create 寫 true，PRIVATE create 寫 false；新增公開/私人變更 method；ACL 不寫 public。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/SkillVisibilityChangedEvent.java` | new | Skill aggregate domain event；公開/私人切換集中在 Skill event path。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Visibility.java` | modify | 文件改成 `is_public` source，不再說 ACL entry。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantIdGenerator.java` | new | 產生無 prefix 的 12 hex opaque grant id；撞 PK 時 retry；public visibility event 先拿 id 再回 API。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java` | modify | upload create 同 TX 保存 Skill、OWNER grant、PUBLIC 時 public VIEWER grant。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantService.java` | modify | public grant/revoke 分支同 TX 呼叫 `Skill.makePublic/makePrivate`、寫/刪 public VIEWER grant mirror、保存 Skill；user/group/company explicit grant 維持現有 service path。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java` | modify | ACL rebuild 不展 public entry；不 seed OWNER/public grants、不負責補 public VIEWER grant mirror。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java` | modify | list/keyword SQL 改 `is_public OR acl_entries`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/JdbcSkillAclReadEvaluator.java` | modify | read permission 改 `is_public OR explicit ACL`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillPermissionStrategy.java` | modify | read permission 改 `is_public OR explicit ACL`，write/delete 維持 explicit ACL。 |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/security/AclPrincipalExpander.java` | modify | read 不再補 `public:*:read`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/security/DelegatingPermissionEvaluator.java` | modify | anonymous read 改走 strategy 的 `is_public` SQL，不再用 public pseudo-principal。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java` | modify | read patterns 不再補 `public:*:read`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SkillshubPgVectorStore.java` | modify | INSERT/UPDATE 支援 `vector_store.is_public`；semantic ACL SQL 改用 `vs.is_public OR vs.acl_entries ??| ?::text[]`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java` | modify | re-embed 寫 `vector_store.is_public = skill.publicSkill`，不寫 public ACL。 |
| `backend/src/test/java/io/github/samzhu/skillshub/db/*` | modify/new | migration AC-S177-1。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillAggregateTest.java` | modify | 新增/修改 aggregate 測試：create 寫 `is_public`；公開/私人 method 發 `SkillVisibilityChangedEvent`；metadata update 不改 public 狀態。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillCommandServiceDeleteTest.java` | modify | 刪除後 browse/detail/vector 不可殘留可查資料。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/query/*` | modify/new | keyword/detail/read permission AC。 |
| `backend/src/test/java/io/github/samzhu/skillshub/search/*` | modify/new | semantic AC。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListenerTest.java` | modify | public grant 新增/撤銷更新 `is_public`，但不寫 public pseudo ACL。 |
| `backend/src/test/java/io/github/samzhu/skillshub/shared/security/*` | modify | 移除 public pseudo-principal expectations。 |
| `backend/src/main/java/io/github/samzhu/skillshub/audit/AuditEventListener.java` | modify | 新增 `SkillVisibilityChangedEvent` audit row。 |
| `backend/src/test/java/io/github/samzhu/skillshub/audit/AuditEventListenerTest.java` | modify | 驗 visibility changed audit idempotency。 |
| `docs/grimo/architecture.md` | modify | Active Skill domain events 7 → 8，新增 `SkillVisibilityChangedEvent`。 |
| `frontend/src/api/skills.ts` | modify | 註解改成 `visibility` 由後端寫入 `skills.is_public`，不再說由 ACL 推導。 |
| `frontend/src/components/VisibilityToggleButton.tsx` | modify | 註解改成 public VIEWER grant；前端行為不改。 |
| `frontend/src/components/VisibilityToggleButton.test.tsx` | modify | 測試名稱/說明移除 `public:*:read` wording；assertion 仍檢查 grants API。 |
| `frontend/src/components/ShareModal.tsx` | modify | 註解改成 public VIEWER grant；前端行為不改。 |
| `docs/grimo/specs/spec-roadmap.md` | modify | S177 row 改成 `📐 in-design` / 新標題。 |

---

## 6. Task Plan

POC: not required — S177 只改既有 Spring Data JDBC aggregate、Flyway migration、NamedParameter/JdbcTemplate SQL 與既有 vector store 客製類；所有設計點都能用現有 RepositorySlice / SpringBootTest / unit tests 直接驗證。

### Execution Order

| Task | Status | AC | 驗證重點 |
|---|---|---|---|
| T01 — schema + aggregate public state | pending | AC-S177-1, AC-S177-1b | V26 改 `skills.is_public`、新增 `vector_store.is_public`；`Skill.create` 直接持有 public/private 狀態 |
| T02 — grant mirror + ACL projection | pending | AC-S177-1b, AC-S177-7 | upload/toggle 同 TX 寫 Skill + OWNER/public grant；projection 不再展 public ACL |
| T03 — keyword/detail permission SQL | pending | AC-S177-2, AC-S177-3, AC-S177-6 | list/detail/read permission 改成 `is_public OR explicit ACL` |
| T04 — semantic vector visibility | pending | AC-S177-4, AC-S177-5, AC-S177-8 | semantic search 用 `vector_store.is_public OR vector_store.acl_entries`；不再 append `public:*:read` |

### AC Coverage

| AC | Covered by |
|---|---|
| AC-S177-1 | T01 |
| AC-S177-1b | T01, T02 |
| AC-S177-2 | T03 |
| AC-S177-3 | T03 |
| AC-S177-4 | T04 |
| AC-S177-5 | T04 |
| AC-S177-6 | T03 |
| AC-S177-7 | T02 |
| AC-S177-8 | T04（local test + deploy follow-up instructions） |

### Planning Notes

- T01 must run first because production code cannot persist `Skill.publicSkill` or `vector_store.is_public` before V26 exists.
- T02 depends on T01 because public grant/revoke must update ordinary `skills.is_public`.
- T03 can start after T01; it does not require vector store changes.
- T04 depends on T01 and T02 so semantic vector rows have both explicit ACL and public projection available.

---

<!-- Section 7 added by /planning-tasks after implementation -->
