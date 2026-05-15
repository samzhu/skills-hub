# S177: is_public-first Search Visibility

> Spec: S177 | Size: S(9) | Status: ✅ QA PASS
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
| `SkillAclProjectionListener` | rebuild ACL 會從 `skill_grants` 展開到 `skills.acl_entries` 與 `vector_store.acl_entries` | S177 保守同步：不新增另一個 projection class；`SkillGrantService` 在 grant/revoke 同 TX 重建 `skills.acl_entries`，現有 `SkillAclProjectionListener` 在 AFTER_COMMIT 重新投影 `skills.acl_entries` 與 `vector_store`，其中 `vector_store.acl_entries` 只寫 read scope，public 用 `vector_store.is_public`。 |
| `SkillQueryService` / `SemanticSearchService` / `JdbcSkillAclReadEvaluator` / `SkillPermissionStrategy` | read patterns 都會補 `public:*:read` | 這些要改成 `is_public OR acl_entries ??| :patterns`。 |
| PostgreSQL 官方 JSON operators docs | `jsonb ?| text[]` 可檢查 JSONB array 是否含任一字串 | ACL 明確授權仍可沿用 `??| :patterns`；public visibility 不必走 JSONB。Source: https://www.postgresql.org/docs/current/functions-json.html |
| Spring Modulith 官方 event docs | `@ApplicationModuleListener` 可用作 async transactional projection listener | `vector_store` search read scope 可走 async listener；`skills.is_public`、`skill_grants` mirror、`skills.acl_entries` 不可交給 listener 補。Source: https://docs.spring.io/spring-modulith/reference/events.html |

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
Skill aggregate + same-TX read scope + async search projection
      |
      +--> skills.is_public = Skill.publicSkill
      |
      +--> skill_grants public VIEWER row = mirrors Skill public visibility
      |
      +--> skills.acl_entries = explicit non-public permissions
      |
      +--> vector_store.is_public = Skill.publicSkill
      |
      +--> vector_store.acl_entries = explicit non-public read permissions only
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
| `is_public` 寫入權責 | `Skill` aggregate 直接持有 `@Column("is_public") private boolean publicSkill` | 公開/私人是 Skill 自己的可見性狀態；同 TX 由 application service 呼叫 Skill method 更新。對外通知不另發 visibility event，而是讓 public `SkillGrantedEvent` / `SkillRevokedEvent` 表達 public grant row 的新增/撤銷。 |
| public visibility grant 是否保留 | 保留 `skill_grants(public, *, VIEWER)` | Share UI / visibility toggle 已用 grants 顯示公開狀態；作為 `Skill.publicSkill` 的管理面 mirror。 |
| public visibility grant 是否展成 ACL | 不展開 | `acl_entries` 只放 user/group/company explicit permissions。 |
| public visibility grant consistency | `skills.is_public` 與 `skill_grants(public, *, VIEWER)` 同 transaction 寫入，並發 Grant event | UI 立即讀 grants 判斷公開狀態；新增與切換都不能等 async listener 補齊。建立時的 public VIEWER grant 與建立後公開切換都由 `SkillGrantedEvent` 記錄。 |
| `skills.acl_entries` consistency | 同 transaction 重建 explicit ACL entries | grant/revoke commit 後，browse/detail/read permission 立刻反映新授權；這是強一致維護資料間的關係。 |
| vector search read-scope consistency | `vector_store.is_public` / `vector_store.acl_entries` 是搜尋索引投影，可接受最終一致 | browse/detail 權限看 `skills`；semantic search 走索引 projection，公開/ACL grant 變更允許短暫延遲。 |
| public visibility grant id | 先產生 12 hex opaque grant id，再建立 public VIEWER grant | `skill_grants.id` 只是資料庫主鍵，無業務語意、不加 prefix；12 hex 比既有 6 hex user/group id 更適合高筆數 grant；service 層保留 collision retry；前端 `POST /grants` 仍可立即拿到 `{grantId}`。 |
| `SkillCreatedEvent` payload | 不帶 grants；只記 skill 本身建立事實 | 建立 private skill 時同 TX 產生兩筆 event：`SkillCreatedEvent` + OWNER `SkillGrantedEvent`。建立 public skill 時因多一筆 public VIEWER grant，所以同 TX 產生三筆 event。`SkillCreatedEvent` 是 skill row 的 source-of-truth；`SkillGrantedEvent` 是 grant row 的 source-of-truth，兩者都兼 audit。 |
| grant/revoke event source | Grant domain model 發 `SkillGrantedEvent` / `SkillRevokedEvent` | Skill 與 Grant 是兩個領域模型；Grant 管授權對象與角色。建立時的 OWNER/public grant 與建立後的角色變動都由 Grant event 記錄；grant/revoke 同 TX 更新 `skills.acl_entries`，並發 event 讓 search projection async 更新 `vector_store.acl_entries`。 |
| `SkillGrantedEvent` / `SkillRevokedEvent` audit | 寫入 `domain_events` audit log，`aggregate_id = skillId` | 所有 domain event 都是 audit log 來源；Grant event 也要留下誰對誰授權/撤銷哪個角色。`grantId` 留在 payload，讓同一個 skill 的 visibility/grant/version 事件可用同一條 timeline 查。 |
| Grant event payload | `SkillGrantedEvent` / `SkillRevokedEvent` 都帶完整授權事實 | Listener 不回查 `skill_grants`，也不要求 event 先替訂閱方展開 read/write/delete。Granted payload 帶 skillId、grantId、isPublic、principalType、principalId、role、grantedBy、grantedAt；Revoked payload 另帶 revokedBy、revokedAt，並保留 revoke 前 grant snapshot。 |
| Grant audit `event_type` | `SkillGranted` / `SkillRevoked` | 依新 event class 名稱去掉 `Event`；不為舊 event/舊資料做相容設計。 |
| `SkillGrantedEvent` / `SkillRevokedEvent` projection | 現有 `SkillAclProjectionListener` 重建 `skills.acl_entries` 與 `vector_store` read scope | public VIEWER grant 也會發 event；listener 回查 `skills.is_public` 寫入 `vector_store.is_public`，但不把 public grant 展成 `public:*:read`。user/group/company role 會展開到 `skills.acl_entries` 的完整 permission；寫到 `vector_store.acl_entries` 時只保留 `:read`。 |
| Grant event delivery | Spring Modulith transactional outbox | Skill event 與 Grant event 機制一致；同 TX 只寫 `skill_grants` / `skills.acl_entries` 等強一致資料，audit/search projection 都 AFTER_COMMIT 消費 outbox event。 |
| Grant aggregate root | 保留 `SkillGrant` as Spring Data JDBC entity；event 由 `SkillGrantService` publish | S177 實作未改成 Grant aggregate root，避免把 package move / event payload 擴張和 visibility bug fix 綁在同一輪。 |
| Grant package name | 保留 `io.github.samzhu.skillshub.skill.security` | repo 既有 controller/service/repository/test 都在 `skill.security`；S177 只修 public/search visibility，不做 package rename 造成的大量 import churn。 |
| Public domain event packages | Skill events in `skill.domain`；Grant events in `skill.security.events` | 實作保留既有 event package；`SkillGrantedEvent(skillId, grantId)` / `SkillRevokedEvent(skillId, grantId)` 由 listener 回查 grant/skill 狀態重建 projection。 |
| Grant API path | 維持 `/api/v1/skills/{id}/grants` | HTTP path 表達「某個 skill 底下的授權設定」；後端 package 保留 `skill.security` 不影響前端 API contract。 |
| Permission evaluator package | `SkillPermissionStrategy` 等 read/write/delete 判斷留在 `skill.security` | Grant domain 管授權對象與角色；permission evaluator 管「目前 user 對 skill 可否做某動作」，會讀 `skills.is_public` 與 `skills.acl_entries`。 |
| Grant persistence newness | 保留 `SkillGrant.isNew() == true` 的 insert-only 現況 | S177 revoke 實作仍是 `grantRepo.deleteById(...)` 後 service publish `SkillRevokedEvent`；不需要 UPDATE revoked snapshot 才能修 visibility bug。 |
| Grant revoke storage | hard delete `skill_grants` row after service updates Skill state | `skill_grants` 是目前授權現況表；S177 不新增 soft-delete 欄位。 |
| Grant revoke event sequence | `grantRepo.deleteById(grantId)` → `rebuildSkillAclEntries(skillId)` → `events.publishEvent(new SkillRevokedEvent(skillId, grantId))` | 實作由 application service publish event；listener 收到事件後回查目前 grants 重建 projection。 |
| `SkillAclProjectionListener` | 保留 listener，調整投影語意 | `SkillGrantService` 同 TX 重建 `skills.acl_entries`；listener AFTER_COMMIT 再以現有 grants 重建 `skills.acl_entries` 與 `vector_store.acl_entries`，並把 `skills.is_public` 投影到 `vector_store.is_public`。舊 listener 不再把 public grant 展成 ACL，也不再把 write/delete 寫進 vector row。 |
| semantic SQL ACL source | `vector_store` 保存 `is_public` + explicit read ACL scope，查詢時過濾可讀結果 | `vector_store` 是全文/向量索引；仍 JOIN `skills` 確認 `PUBLISHED` 狀態，但可讀範圍由搜尋索引自己的投影欄位判斷。 |
| anonymous pattern | 不建 pattern array，直接 `is_public=true` | 少掉 `public:*:read`，SQL 更短，符合「未登入只看公開」。 |
| authenticated pattern | principal keys 加 `:read`，不補 public | public 由 `is_public=true` 負責；ACL 只判斷明確授權。 |

### 2.6 Task 邊界提示

| Task 候選 | Class / file | 來源 | 正向情境 | 反向情境 | POC |
|---|---|---|---|---|---|
| T01 | schema + aggregate public state | V16 generated column / `Skill.create` | `skills.is_public` 轉 ordinary boolean、`vector_store.is_public` 新增、PUBLIC create 寫 true | schema 仍不可是 generated column；ACL 不含 public pseudo entry | not required |
| T02 | grant mirror + strong ACL scope | command service + same-TX ACL rebuild | create/toggle/grant/revoke 同 TX 寫 grant mirror 與 `skills.acl_entries`；ACL 只展 non-public explicit grants | revoke public grant 後 `Skill.publicSkill=false` 且 explicit ACL 保留 | not required |
| T03 | keyword/detail permission SQL | `SkillQueryService`, `JdbcSkillAclReadEvaluator`, `SkillPermissionStrategy`, `DelegatingPermissionEvaluator`, `AclPrincipalExpander` | anonymous 可看 public；logged-in 可看 public + granted private | 未授權 user 看不到 private；public 不給 write/delete | not required |
| T04 | semantic vector read scope | `SemanticSearchService`, `SkillshubPgVectorStore`, `SearchProjection`, `SkillAclProjectionListener` | anonymous semantic 只搜 `vector_store.is_public=true`；logged-in semantic 搜 public + granted private；vector read scope 最終一致 | `vector_store.acl_entries` 即使被誤塞 public pseudo entry 也不造成外漏 | not required |

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
| 新增 skill 選公開/私人 | `PublishPage` radio → `uploadSkill(..., visibility)` | `POST /api/v1/skills/upload` → `Skill.create(cmd.visibility())` + `grantRepo.save(ownerGrant)` + optional `grantRepo.save(publicGrant)` in same TX | PUBLIC commit 後立刻同時有 `skills.is_public=true`、OWNER grant、public VIEWER grant、owner ACL；PRIVATE commit 後有 `skills.is_public=false`、OWNER grant、無 public grant、owner ACL；兩者 `acl_entries` 都不可含 `public:*:read`。 |
| 修改 description/category | `EditSkillModal` → `updateSkill(id, {description, category})` | `PUT /api/v1/skills/{id}` → `Skill.update(UpdateSkillCommand)` | 這條不改公開狀態；`repo.findById()` 讀出的 `publicSkill` 應原樣保存。並發公開/metadata 修改由 `@Version` 樂觀鎖處理。 |
| 公開分享 | `VisibilityToggleButton` / `ShareModal` → `createGrant({public, *, VIEWER})` | `POST /api/v1/skills/{id}/grants` → `SkillGrantService.grant()` → `Skill.makePublic(...)` + `grantRepo.save(publicGrant)` + `skillRepo.save(skill)` in same TX | API commit 後立刻同時有 `skills.is_public=true` 與 public VIEWER grant；`skills.acl_entries` / `vector_store.acl_entries` 仍不含 `public:*:read`。 |
| 轉為私人 | `VisibilityToggleButton` / `ShareModal` → `revokeGrant(publicGrantId)` | `DELETE /api/v1/skills/{id}/grants/{grantId}` → `SkillGrantService.revoke()` → `Skill.makePrivate(...)` + `grant.revoke(actor)` + `grantRepo.save(revokedGrant)` + `grantRepo.delete(revokedGrant)` + `skillRepo.save(skill)` in same TX | API commit 後立刻同時有 `skills.is_public=false` 且 public VIEWER grant 已刪；explicit user/group/company ACL 保留；revoked event 先進 outbox 再刪 row。 |
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
| AC-S177-1b | 必做 | Test | 新增 skill 時直接寫 `is_public`，同 TX 發 SkillCreated + SkillGranted events |
| AC-S177-2 | 必做 | Test | anonymous keyword browse 只回 public skill |
| AC-S177-3 | 必做 | Test | logged-in keyword browse 回 public skill + 自己可讀 private skill |
| AC-S177-4 | 必做 | Test | anonymous semantic search 用 `vector_store.is_public` 判斷 public |
| AC-S177-5 | 必做 | Test | logged-in semantic search 回 public skill + granted private skill |
| AC-S177-5b | 必做 | Test | `vector_store.acl_entries` 只保存 read scope |
| AC-S177-6 | 必做 | Test | detail/read permission 用 `is_public OR explicit ACL` |
| AC-S177-7 | 必做 | Test | public visibility toggle 同 TX 更新 `is_public` 與 public grant，不寫 `public:*:read` |
| AC-S177-7b | 必做 | Test | explicit grant/revoke 同 TX 更新 `skills.acl_entries` |
| AC-S177-7c | 必做 | Test | Grant events 寫入 `domain_events` audit log |
| AC-S177-8 | 建議 | Demo | production deploy 後兩筆 private skill 不再被匿名 semantic search 找到 |

**AC-S177-1: migration 把 `is_public` 改成 ordinary boolean**
- Given（前提）v25 schema 的 `skills.is_public` 是 generated column
- When（動作）Flyway 套用 V26
- Then（結果）`information_schema.columns.generation_expression` 對 `skills.is_public` 為 null 或空值

**AC-S177-1b: 新增 skill 時直接寫 `is_public`，同 TX 發 SkillCreated + SkillGranted events**
- Given（前提）Alice publish 一個 `visibility=PUBLIC` 的 skill
- When（動作）系統建立 `skills` row
- Then（結果）`skills.is_public=true`
- And（而且）`skill_grants` 有 Alice OWNER grant
- And（而且）`skill_grants` 有 public VIEWER grant
- And（而且）`skills.acl_entries` 不含 `public:*:read`
- And（而且）`domain_events` 有一筆 `event_type='SkillCreated'`
- And（而且）`domain_events` 有 Alice OWNER 的 `event_type='SkillGranted'`
- And（而且）`domain_events` 有 public VIEWER 的 `event_type='SkillGranted'`
- Given（前提）Alice publish 一個 `visibility=PRIVATE` 的 skill
- When（動作）系統建立 `skills` row
- Then（結果）`skills.is_public=false`
- And（而且）`skill_grants` 有 Alice OWNER grant
- And（而且）`skill_grants` 沒有 public VIEWER grant
- And（而且）`domain_events` 有 Alice OWNER 的 `event_type='SkillGranted'`

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

**AC-S177-4: anonymous semantic search 用 `vector_store.is_public` 判斷 public**
- Given（前提）private skill B 的 `vector_store.is_public=false`
- And（而且）測試 fixture 故意讓 `vector_store.acl_entries` 含 `public:*:read`
- When（動作）anonymous 查 `/api/v1/search/semantic?q=<common>`
- Then（結果）response 不包含 B

**AC-S177-5: logged-in semantic search 回 public skill + granted private skill**
- Given（前提）DB 有 public skill A 且 `vector_store.is_public=true`
- And（而且）private shared-to-Bob skill C 的 `vector_store.acl_entries` 含 Bob read pattern
- When（動作）Bob 查 `/api/v1/search/semantic?q=<common>`
- Then（結果）response 包含 A 與 C
- And（而且）不包含未授權 private skill B

**AC-S177-5b: `vector_store.acl_entries` 只保存 read scope**
- Given（前提）Alice 授權 Bob EDITOR
- When（動作）`SkillAclProjectionListener` 處理 `SkillGrantedEvent`
- Then（結果）`skills.acl_entries` 含 `user:bob:read`、`user:bob:write`
- And（而且）`vector_store.acl_entries` 只含 `user:bob:read`
- And（而且）不含 `user:bob:write` 或 `user:bob:delete`
- Given（前提）Alice 新增 public VIEWER grant
- When（動作）`SkillAclProjectionListener` 處理 `SkillGrantedEvent`
- Then（結果）`vector_store.acl_entries` 不含 `public:*:read`

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
- And（而且）同一個 transaction 發出 `SkillGrantedEvent`，payload 含 `isPublic=true`, `principalType='public'`, `principalId='*'`, `role='VIEWER'`
- And（而且）`skills.acl_entries` 不含 `public:*:read`
- When（動作）Alice revoke public VIEWER grant
- Then（結果）`skills.is_public=false`
- And（而且）`skill_grants` 沒有 public VIEWER grant
- And（而且）同一個 transaction 發出 `SkillRevokedEvent`，payload 含 `isPublic=false` 與 revoke 前的 public VIEWER grant snapshot

**AC-S177-7b: explicit grant/revoke 同 TX 更新 `skills.acl_entries`**
- Given（前提）private skill A 只有 Alice OWNER grant
- When（動作）Alice 授權 Bob VIEWER
- Then（結果）同一個 API commit 後，`skill_grants` 有 Bob VIEWER row
- And（而且）`skills.acl_entries` 立刻含 `user:bob:read`
- And（而且）Bob 立刻 GET `/api/v1/skills/{A}` 得 HTTP 200
- When（動作）Alice revoke Bob grant
- Then（結果）同一個 API commit 後，`skills.acl_entries` 立刻不含 `user:bob:read`

**AC-S177-7c: Grant events 寫入 `domain_events` audit log**
- Given（前提）Alice 對 skill A 新增 Bob VIEWER grant
- When（動作）`SkillGrantedEvent` 發出
- Then（結果）`domain_events` 有一筆 `event_type='SkillGranted'`
- And（而且）`aggregate_id = skillId`
- And（而且）payload 含完整 grant snapshot：skillId、grantId、principalType、principalId、role、grantedBy、grantedAt
- Given（前提）Alice revoke Bob grant
- When（動作）`SkillRevokedEvent` 發出
- Then（結果）`domain_events` 有一筆 `event_type='SkillRevoked'`
- And（而且）`aggregate_id = skillId`
- And（而且）payload 含 revoke 前 grant snapshot 與 revokedBy、revokedAt

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
| Reliability | AC-S177-1, AC-S177-7, AC-S177-7b | migration 不做舊資料保留；`skills` 與 grants 強一致；`vector_store` read scope 作為搜尋索引可最終一致。 |
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
| AC-S177-5b | yes | yes | yes | yes | yes |
| AC-S177-6 | yes | yes | yes | yes | yes |
| AC-S177-7 | yes | yes | yes | yes | yes |
| AC-S177-7b | yes | yes | yes | yes | yes |
| AC-S177-7c | yes | yes | yes | yes | yes |
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
public void makePublic(String changedBy) {
    if (this.publicSkill) return;
    this.publicSkill = true;
    this.updatedAt = Instant.now();
}

public void makePrivate(String changedBy) {
    if (!this.publicSkill) return;
    this.publicSkill = false;
    this.updatedAt = Instant.now();
}
```

public VIEWER grant 是管理面 mirror，不是 `is_public` 的 source-of-truth；但對外通知統一用 Grant event。公開時 service 先產生 `publicGrantId`，同一個 transaction 內呼叫 `Skill.makePublic(...)`、保存 public VIEWER grant，並由 `SkillGrantedEvent(isPublic=true, principalType=public, role=VIEWER)` 讓訂閱方自行決定是否更新 `vector_store.is_public`。

`SkillCreatedEvent` 只記 skill 本身已建立，不帶 grant snapshot。新增 PUBLIC/PRIVATE 的強一致資料由 `SkillCommandService.uploadSkill` 同 TX 寫入；created event listener 不可 seed OWNER/public grant，也不可更新 `skills.is_public`。建立 skill 時的 OWNER grant 與 PUBLIC 時的 public VIEWER grant 也要由 `SkillGrant` aggregate 發 `SkillGrantedEvent`，讓 grant row 的 event 成為 grant 的 single source of truth。

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

### 4.5 Strong ACL scope + search projection contract

Grant application service 在同一個 transaction 內重建 `skills.acl_entries`；搜尋索引 read scope 由現有 `SkillAclProjectionListener` 在 AFTER_COMMIT 重建：

```text
grants = skill_grants by skillId
fullEntries = all non-public grants expanded by Role.permissions()

skillAclEntryWriter.replaceEntries(skillId, fullEntries)
```

Async vector read-scope projection：

```text
projectedReadEntries = all non-public grants that include READ expanded to principal:read only

UPDATE vector_store
   SET acl_entries = :projectedReadEntries
 WHERE skill_id = :skillId
```

Visibility changed projection（async event listener 更新搜尋索引；最終一致）：

```sql
UPDATE vector_store
   SET is_public = :isPublic
 WHERE skill_id = :skillId
```

Public visibility service transaction（application service 負責同 TX 更新 Skill state 與 mirror row；對外 event 由 `SkillGrantService` publish）：

```text
make public:
  skill.makePublic(actor)
  publicGrant = SkillGrant.createPublicViewer(publicGrantId, skillId, actor)
  grantRepo.save(publicGrant)
  skillRepo.save(skill)
  rebuildSkillAclEntries(skillId)
  events.publishEvent(new SkillGrantedEvent(skillId, publicGrantId))

make private:
  skill.makePrivate(actor)
  grant = grantRepo.findPublicGrant(skillId)
  grantRepo.deleteById(grant.id)
  skillRepo.save(skill)
  rebuildSkillAclEntries(skillId)
  events.publishEvent(new SkillRevokedEvent(skillId, grant.id))
```

Event count：

```text
make public:
  SkillGranted(isPublic=true, public VIEWER)

make private:
  SkillRevoked(isPublic=false, public VIEWER)
```

Explicit grant service transaction：

```text
grant user/group/company:
  grant = SkillGrant.create(grantId, skillId, principal, role, actor)
  grantRepo.save(grant)
  entries = aclEntriesFrom(skillGrantRepository.findBySkillId(skillId), excludingPublic=true)
  update skills set acl_entries = entries
  events.publishEvent(new SkillGrantedEvent(skillId, grant.id))

revoke user/group/company:
  grant = grantRepo.findById(grantId)
  grantRepo.deleteById(grantId)
  entries = aclEntriesFrom(skillGrantRepository.findBySkillId(skillId), excludingPublic=true)
  update skills set acl_entries = entries
  events.publishEvent(new SkillRevokedEvent(skillId, grantId))
```

以上步驟必須在同一個 `@Transactional` method 內完成；不能交給 async listener 補 public grant mirror、explicit grant mirror、或 `skills.acl_entries`。S177 實作保留 service publish 的 `SkillGrantedEvent(skillId, grantId)` / `SkillRevokedEvent(skillId, grantId)`；listener 回查目前 `skill_grants` 與 `skills.is_public` 後更新 `vector_store.is_public` / read-only `vector_store.acl_entries`。搜尋索引允許最終一致。

Create skill service transaction：

```text
upload visibility=PUBLIC:
  skill = Skill.create(... publicSkill=true)
  ownerGrant = SkillGrant.create(... OWNER)
  publicGrant = SkillGrant.create(... public/* VIEWER)
  skillRepo.save(skill)
  grantRepo.save(ownerGrant)
  grantRepo.save(publicGrant)
  events.publishEvent(new SkillGrantedEvent(skillId, ownerGrant.id))
  events.publishEvent(new SkillGrantedEvent(skillId, publicGrant.id))

upload visibility=PRIVATE:
  skill = Skill.create(... publicSkill=false)
  ownerGrant = SkillGrant.create(... OWNER)
  skillRepo.save(skill)
  grantRepo.save(ownerGrant)
  events.publishEvent(new SkillGrantedEvent(skillId, ownerGrant.id))
```

新增流程同樣要強一致；create service 直接初始化 `Skill.publicSkill` 與 owner ACL entries，並同 TX 寫 OWNER/public grant mirror。`SkillCreatedEvent` 記錄 skill 已建立，`SkillGrantedEvent` 記錄 OWNER/public grant 已建立；兩種 event 都在同一個 transaction 內 publish。`SkillAclProjectionListener.onSkillCreated` 只負責重建 projection，不負責 seed OWNER/public grants。

Event count：

```text
visibility=PRIVATE:
  SkillCreated
  SkillGranted(owner)

visibility=PUBLIC:
  SkillCreated
  SkillGranted(owner)
  SkillGranted(public VIEWER)
```

`SearchProjection` create/publish/reactivate re-embed：

```text
read Skill aggregate
write vector_store.is_public = skill.publicSkill
write vector_store.acl_entries = read-only subset of skill.aclEntries
do not infer public visibility from owner
do not add public:*:read
```

---

## 5. 檔案規劃

| 檔案 | 動作 | 說明 |
|---|---|---|
| `backend/src/main/resources/db/migration/V26__is_public_first_search_visibility.sql` | new | 改 `skills.is_public` ordinary boolean、新增 `vector_store.is_public`、清 `public:*:read`；不做舊資料保留。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` | modify | 加 `@Column("is_public") private boolean publicSkill`；PUBLIC create 寫 true，PRIVATE create 寫 false；`SkillCreatedEvent` 只記 skill 本身建立；新增公開/私人變更 method；ACL 不寫 public。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrant.java` | modify | 保留既有 package/entity；新增流程仍由 service 建 grant row，public grant 不展開成 ACL。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantRepository.java` | modify | 保留既有 package；仍對應 `skill_grants` table。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/Role.java` | modify | 保留既有 package；Role 仍負責 OWNER/EDITOR/VIEWER 到 read/write/delete 的展開。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/events/SkillGrantedEvent.java` | modify | 保留既有 package；event payload 維持 `skillId`、`grantId`，listener 回查 grants/skill 狀態重建 projection。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/events/SkillRevokedEvent.java` | modify | 保留既有 package；event payload 維持 `skillId`、`grantId`，撤銷後 listener 以目前 grants 狀態重建 projection。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Visibility.java` | modify | 文件改成 `is_public` source，不再說 ACL entry。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantIdGenerator.java` | new | 產生無 prefix 的 12 hex opaque grant id；public grant 建立前先拿 id，再讓 API 回 `{grantId}`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/command/SkillCommandService.java` | modify | upload create 同 TX 保存 Skill、OWNER grant、PUBLIC 時 public VIEWER grant；Skill row 直接帶 owner ACL entries。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantController.java` | modify | 保留既有 package；API path 維持 `/api/v1/skills/{id}/grants`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillGrantService.java` | modify | Grant application service：public grant/revoke 同 TX 呼叫 `Skill.makePublic/makePrivate`、保存/刪除 public VIEWER grant、保存 Skill；user/group/company explicit grant/revoke 同 TX 保存/刪除 grant row、重建 `skills.acl_entries`；service publish Grant event。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java` | modify | 保留 listener；AFTER_COMMIT 回查 grants 與 `skills.is_public`，重建 `skills.acl_entries`、`vector_store.is_public`、read-only `vector_store.acl_entries`。public grant 不展成 ACL；vector row 不寫 `write/delete`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/SkillQueryService.java` | modify | list/keyword SQL 改 `is_public OR acl_entries`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/query/JdbcSkillAclReadEvaluator.java` | modify | read permission 改 `is_public OR explicit ACL`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillPermissionStrategy.java` | modify | read permission 改 `is_public OR explicit ACL`，write/delete 維持 explicit ACL。 |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/security/AclPrincipalExpander.java` | modify | read 不再補 `public:*:read`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/shared/security/DelegatingPermissionEvaluator.java` | modify | anonymous read 改走 strategy 的 `is_public` SQL，不再用 public pseudo-principal。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SemanticSearchService.java` | modify | read patterns 不再補 `public:*:read`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SkillshubPgVectorStore.java` | modify | INSERT/UPDATE 支援 `vector_store.is_public`；semantic ACL SQL 改用 `vs.is_public OR vs.acl_entries ??| ?::text[]`。 |
| `backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java` | modify | re-embed 寫 `vector_store.is_public = skill.publicSkill` 與 `skill.aclEntries` 的 read-only subset，不寫 public ACL。 |
| `backend/src/test/java/io/github/samzhu/skillshub/db/*` | modify/new | migration AC-S177-1。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillAggregateTest.java` | modify | 新增/修改 aggregate 測試：create 寫 `is_public`；公開/私人 method 更新 `publicSkill`；metadata update 不改 public 狀態。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/command/SkillCommandServiceDeleteTest.java` | modify | 刪除後 browse/detail/vector 不可殘留可查資料。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/query/*` | modify/new | keyword/detail/read permission AC。 |
| `backend/src/test/java/io/github/samzhu/skillshub/search/*` | modify/new | semantic AC。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/security/SkillGrantServiceVisibilityTest.java` | modify | grant/revoke commit 後 `skills.is_public`、public grant mirror、`skills.acl_entries` 都符合新語意。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListenerTest.java` | modify | Grant events 後 `vector_store.is_public` / read-only `vector_store.acl_entries` 更新；不寫 public pseudo entry 或 write/delete。 |
| `backend/src/test/java/io/github/samzhu/skillshub/shared/security/*` | modify | 移除 public pseudo-principal expectations。 |
| `backend/src/main/java/io/github/samzhu/skillshub/audit/AuditEventListener.java` | modify | 新增 `SkillGrantedEvent`、`SkillRevokedEvent` audit row；Grant event 使用 `aggregate_id = skillId`。 |
| `backend/src/test/java/io/github/samzhu/skillshub/audit/AuditEventListenerTest.java` | modify | 驗 created、visibility、grant、revoke audit idempotency。 |
| `docs/grimo/architecture.md` | modify | 文件同步實際 package：Grant 仍在 `skill.security`；`SkillAclProjectionListener` 依 Grant event 更新 vector read scope。 |
| `frontend/src/api/skills.ts` | modify | 註解改成 `visibility` 由後端寫入 `skills.is_public`，不再說由 ACL 推導。 |
| `frontend/src/components/VisibilityToggleButton.tsx` | modify | 註解改成 public VIEWER grant；前端行為不改。 |
| `frontend/src/components/VisibilityToggleButton.test.tsx` | modify | 測試名稱/說明移除 `public:*:read` wording；assertion 仍檢查 grants API。 |
| `frontend/src/components/ShareModal.tsx` | modify | 註解改成 public VIEWER grant；前端行為不改。 |
| `docs/grimo/specs/spec-roadmap.md` | modify | S177 row 維持 `⏳ Dev (QA bug fix)`，直到 `verify-all.sh` 與 QA pass。 |

---

## 6. Task Plan

POC: not required — S177 只改既有 Spring Data JDBC aggregate、Flyway migration、NamedParameter/JdbcTemplate SQL 與既有 vector store 客製類；所有設計點都能用現有 RepositorySlice / SpringBootTest / unit tests 直接驗證。

### Execution Order

| Task | Status | AC | 驗證重點 |
|---|---|---|---|
| T01 — schema + aggregate public state | PASS | AC-S177-1, AC-S177-1b | V26 改 `skills.is_public`、新增 `vector_store.is_public`；`Skill.create` 直接持有 public/private 狀態 |
| T02 — grant mirror + strong ACL scope | PASS | AC-S177-1b, AC-S177-7, AC-S177-7b, AC-S177-7c | upload/toggle/grant/revoke 同 TX 寫 Skill + grant mirror + `skills.acl_entries`；public grant/revoke 寫 `SkillVisibilityChanged` audit log；search projection 不再展 public ACL |
| T03 — keyword/detail permission SQL | PASS | AC-S177-2, AC-S177-3, AC-S177-6 | list/detail/read permission 改成 `is_public OR explicit ACL` |
| T04 — semantic vector visibility | PASS | AC-S177-4, AC-S177-5, AC-S177-5b, AC-S177-8 | semantic search 用 `vector_store.is_public OR vector_store.acl_entries`；`vector_store.acl_entries` 只含 read scope，不再 append `public:*:read` |

### AC Coverage

| AC | Covered by |
|---|---|
| AC-S177-1 | T01 |
| AC-S177-1b | T01, T02 |
| AC-S177-2 | T03 |
| AC-S177-3 | T03 |
| AC-S177-4 | T04 |
| AC-S177-5 | T04 |
| AC-S177-5b | T04 |
| AC-S177-6 | T03 |
| AC-S177-7 | T02 |
| AC-S177-7b | T02 |
| AC-S177-7c | T02 |
| AC-S177-8 | T04（local test + deploy follow-up instructions） |

### Planning Notes

- T01 must run first because production code cannot persist `Skill.publicSkill` or `vector_store.is_public` before V26 exists.
- T02 depends on T01 because public grant/revoke must update ordinary `skills.is_public`.
- T03 can start after T01; it does not require vector store changes.
- T04 depends on T01 and T02 so semantic vector rows have both explicit ACL and public projection available.

---

## 7. Implementation Results

| Task | Status | 驗證 |
|---|---|---|
| T01 — schema + aggregate public state | PASS | `cd backend && ./gradlew test --tests "*IsPublicFirstMigrationTest" --tests "*SkillAggregateTest"` → `BUILD SUCCESSFUL in 2m 4s` |
| T02 — grant mirror + strong ACL scope | PASS | `cd backend && ./gradlew test --tests "*SkillCommandServiceUploadVisibilityTest" --tests "*SkillGrantServiceVisibilityTest" --tests "*AuditEventListenerTest"` → `BUILD SUCCESSFUL in 2m 20s` |
| T03 — keyword/detail permission SQL | PASS | `cd backend && ./gradlew test --tests "*SkillQueryServiceVisibilityTest" --tests "*JdbcSkillAclReadEvaluatorTest" --tests "*DelegatingPermissionEvaluatorTest"` → `BUILD SUCCESSFUL in 2m 6s` |
| T04 — semantic vector visibility | PASS | `cd backend && ./gradlew test --tests "*SkillshubPgVectorStoreVisibilityTest" --tests "*SemanticSearchServiceVisibilityTest" --tests "*SearchProjectionVisibilityTest"` → `BUILD SUCCESSFUL in 2m 10s` |
| S177 integrated local verification | PASS | `cd backend && ./gradlew test --tests "*IsPublicFirstMigrationTest" --tests "*SkillAggregateTest" --tests "*SkillCommandServiceUploadVisibilityTest" --tests "*SkillGrantServiceVisibilityTest" --tests "*AuditEventListenerTest" --tests "*SkillQueryServiceVisibilityTest" --tests "*JdbcSkillAclReadEvaluatorTest" --tests "*DelegatingPermissionEvaluatorTest" --tests "*SkillshubPgVectorStoreVisibilityTest" --tests "*SemanticSearchServiceVisibilityTest" --tests "*SearchProjectionVisibilityTest"` → `BUILD SUCCESSFUL in 2m 41s` |

### T04 Notes

- `vector_store.is_public` 現在由 `SearchProjection` 寫入；semantic SQL 讀取條件是 `vs.is_public = TRUE OR vs.acl_entries ??| ?::text[]`。
- `SemanticSearchService.readPatterns(...)` 不再 append `public:*:read`；anonymous semantic search 會送空 read pattern `"{}"`，只靠 `vector_store.is_public=true` 看到公開 skill。
- `vector_store.acl_entries` 現在只保存 explicit read scope；即使 fixture 或舊資料誤塞 `public:*:read`，private vector row 也不會因此被 anonymous 搜到。
- AC-S177-8 的 Cloud Run production curl evidence 需等 release/deploy 後補。

### Final Size Re-score (per estimation-scale.md)

| Dimension | Initial | Actual | Rationale |
|---|---:|---:|---|
| Tech risk | 2 | 2 | 使用既有 Spring Data JDBC / Modulith / pgvector patterns，但 public visibility 從 ACL 拆欄位後牽動多條 read path。 |
| Uncertainty | 1 | 2 | Round 1 QA 發現 spec file plan 與實作 package/listener 不一致，T08 需補文件同步。 |
| Dependencies | 2 | 3 | 依賴 S017/S169/S170 既有 ACL/search contract，final gate 還包含 Docker native image build。 |
| Scope | 2 | 3 | 實際改到 schema、aggregate、grant service、query SQL、semantic vector store、audit、fixtures 與多個測試檔，超過 9 個 production/test touchpoints。 |
| Testing | 2 | 3 | 除 targeted JUnit 外，必須跑 Testcontainers、Playwright happy-path、AOT、bootBuildImage，且 Round 2 清掉 28 個 backend failures。 |
| Reversibility | 1 | 2 | V26 改 persisted schema 語意，rollback 需要 migration/data projection 一起回復。 |
| **Total** | **9 / S** | **15 / L** | Bucket shift S→L；主因是 public visibility source-of-truth 變更同時影響 persisted schema、ACL projection、semantic search、fixture 與 native image gate。 |

## 8. QA Review — 2026-05-15

Verdict: REJECT-FIX.

`./scripts/verify-all.sh` 跑完後回 `exit=1`。`verify-all.log` 的 summary 是：

```text
V01=FAIL V02=SKIP V03=FAIL V04=PASS V05=PASS V06=PASS V07=PASS V08a=PASS V08b=FAIL
PASS=5, FAIL=3, SKIP=1
```

User 清空 Testcontainers 後重跑：

```text
cd backend && ./gradlew clean test jacocoTestReport
986 tests completed, 28 failed, 7 skipped
BUILD FAILED in 4m 50s
```

### QA Layer Result

| Layer | Result | Detail |
|---|---|---|
| Automated tests | FAIL | Backend V01 仍有 28 個 test failed；清空 Testcontainers 後仍重現。Frontend V04/V05/V06 PASS。 |
| Coverage / integration | FAIL | V03 因 backend tests fail 無法通過；V07 Playwright `@happy-path` PASS；V08a `processAot` PASS；V08b `bootBuildImage` 因 Docker container removal 409 失敗。 |
| Manual verification | READY | AC-S177-8 已寫 production curl follow-up；release/deploy 後補 evidence。 |
| Testability gate | BLOCKED | AC-S177-5b 的現有 T04 測試沒有檢查 `vector_store.acl_entries` 不含 `write/delete`。 |

### Findings

1. CRITICAL — `backend/src/main/java/io/github/samzhu/skillshub/skill/security/SkillAclProjectionListener.java:89` 到 `:108` 會把 non-public grant 的所有 role permission 寫進 `vector_store.acl_entries`。OWNER/EDITOR grant 會產生 `write/delete`，但 AC-S177-5b 要的是 `vector_store.acl_entries` 只放 `:read`。目前 `backend/src/test/java/io/github/samzhu/skillshub/search/SearchProjectionVisibilityTest.java:52` 只檢查有 `user:u_alice0:read`、沒有 `public:*:read`，沒有檢查不含 `write/delete`。
2. CRITICAL — `cd backend && ./gradlew clean test jacocoTestReport` 仍失敗。測試輸出明確列出 `SearchConfigRegressionTest`, `SearchProjectionTest`, `SemanticSearchIntegrationTest`, `SemanticSearchRealFixtureIT`, `AclPrincipalExpanderTest`, `SkillSearchTest`, `MigrationBackfillTest`, `SkillAclProjectionListenerTest` 等仍在期待舊 `public:*:read` 或舊 projection 行為。
3. CRITICAL — `SkillCommandServiceDeleteTest`, `SkillCommandServiceTest`, `SkillVersionQueryTest` 的 Spring context 啟動失敗；HTML report 顯示 `NoSuchBeanDefinitionException: SkillGrantIdGenerator`。S177 讓 `SkillCommandService` 需要新 bean，但這些 test slice 沒有匯入它。
4. IMPORTANT — spec §5 寫 `SkillGrant*` 會搬到 `skill.grant`、新增 `SearchReadScopeProjection`、移除/替換 `SkillAclProjectionListener`；實作仍在 `skill.security`，也沒有 `SearchReadScopeProjection`。若這是刻意縮 scope，spec 要改成實際設計；若不是，implementation 還沒完成。
5. IMPORTANT — `./gradlew bootBuildImage` 在 V08b 失敗點是 Docker API 409：`removal of container ... is already in progress`。這看起來是本機 Docker/OrbStack 清理中的狀態，不是 S177 code path；但 verify-all 的 container image gate 仍未拿到 PASS evidence。

### Required Fix Before Ship

1. 先修 AC-S177-5b：`vector_store.acl_entries` 寫入路徑只能保留 `:read`，並新增/修正測試讓 OWNER/EDITOR grant 不會把 `write/delete` 寫入 vector row。
2. 更新所有仍期待 `public:*:read` 的 backend tests 與 fixtures：公開 skill 要設 `skills.is_public=true` / `vector_store.is_public=true`，private fixture 不應靠 public pseudo ACL。
3. 修正 `SkillGrantIdGenerator` 在 command/query test slice 的 bean 匯入或 test config。
4. 同步 spec §4/§5/§7：把實際保留的 package/listener 設計寫清楚。（T08 已處理）
5. 修完後重跑 `./scripts/verify-all.sh`；V08b 若仍是 Docker 409，需在 Docker/OrbStack container 清理完成後單跑 `cd backend && ./gradlew bootBuildImage` 補證據。

### Round 2 Task Plan

| Task | Status | QA finding | 驗證重點 |
|---|---|---|---|
| T05 — vector read-scope projection | PASS | Finding 1 / AC-S177-5b | `skills.acl_entries` 保留完整 explicit ACL；`vector_store.acl_entries` 只含 `:read`，不含 `write/delete/public:*:read` |
| T06 — update public ACL tests and fixtures | PASS | Finding 2 | 舊 backend tests/fixtures 改用 `skills.is_public` / `vector_store.is_public`；active public fixtures 不再期待 `public:*:read` |
| T07 — fix test slice beans | PASS | Finding 3 | `SkillCommandService*` / `SkillVersionQueryTest` Spring context 不再缺 `SkillGrantIdGenerator` |
| T08 — sync spec design with implementation | PASS | Finding 4 | S177 spec / roadmap / architecture 不再把未實作 package/class 寫成完成狀態 |

Round 2 task files:

- `docs/grimo/tasks/2026-05-15-S177-T05-vector-read-scope-projection.md`
- `docs/grimo/tasks/2026-05-15-S177-T06-update-public-acl-fixtures.md`
- `docs/grimo/tasks/2026-05-15-S177-T07-fix-test-slice-beans.md`
- `docs/grimo/tasks/2026-05-15-S177-T08-sync-spec-design.md`

### Final QA Review — 2026-05-15

Verdict: PASS.

`./scripts/verify-all.sh` 跑完後回 `exit=0`：

```text
V01=PASS V02=INFO V03=PASS V04=PASS V05=PASS V06=PASS V07=PASS V08a=PASS V08b=PASS
PASS=8, FAIL=0, SKIP=0
```

Layer result:

| Layer | Result | Detail |
|---|---|---|
| Automated tests | PASS | V01 `./gradlew clean test jacocoTestReport` PASS；user 清空 Testcontainers 後的 28 個 backend failures 已清掉。 |
| Coverage / integration | PASS | V02 line coverage 86.1%；V03 coverage gate PASS；V07 Playwright `@happy-path` PASS；V08a `processAot` PASS；V08b `bootBuildImage` PASS。 |
| Manual verification | READY | AC-S177-8 production curl evidence 等 release/deploy 後補。 |
| Testability gate | PASS | AC-S177-5b 已補 `vector_store.acl_entries` 不含 `write/delete/public:*:read` 的測試；docs 已同步實際 package/listener。 |

Residual note:

- AC-S177-8 是 production deploy 後驗證；本輪 local/container gate 全綠，production evidence 在 release/deploy 後補。
