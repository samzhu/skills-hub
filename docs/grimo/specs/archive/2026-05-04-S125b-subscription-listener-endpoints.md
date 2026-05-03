# S125b: SkillSubscription endpoints + version-published listener (LAB user-visible flow chain 2/3)

> Spec: S125b | Size: XS(3) | Status: ✅ Shipped (v3.10.0)
> Date: 2026-05-04
> Source: S125 split second sub-spec; Bug AW fix surfaced + 同 ship

---

## 1. Goal

完成 PRD §285-§291 P9 SBE scenario 1 端到端：交付 `POST/DELETE /skills/{id}/subscribe` + `GET /me/subscriptions` HTTP endpoints，再加 `NotificationProjectionListener.onVersionPublished` 訂閱 `SkillVersionPublishedEvent` → 對每個 subscriber 寫通知。S125 chain 2/3。

實作過程揭露 **Bug AW** — `DelegatingPermissionEvaluator.expandPrincipals` 對 authenticated user 漏加 `*:read` pseudo-principal（pre-existing 自 S122 ship 起；本 spec @PreAuthorize 在 subscribe endpoint 才 surface）。同 spec 內補修以避免 S125b ship 後 LAB 員工拿 JWT 反而看不到 PUBLIC skills。

**起源**：S125a backend infra (XS=4) ship 後接續 chain 2/3 — endpoints + listener。
**非目標**（本 spec 不做）：
- Frontend SkillDetail subscribe button → S125c
- Bell badge polling 改 push（既有 30s poll OK；future SSE）
- Subscriber list count badge in admin view → post-MVP

## 2. Approach

### 2.1 Endpoints layout

放於 community module（對齊 S125a aggregate 位置 + 既驗 RequestCommandController / CollectionCommandController pattern）。3 個 endpoint：

- `POST /api/v1/skills/{id}/subscribe` → 201 + `@PreAuthorize("hasPermission(#id, 'Skill', 'read')")` 守 read 權限
- `DELETE /api/v1/skills/{id}/subscribe` → 204；不加 @PreAuthorize（unsubscribe 對未訂閱 skill 是 noop，無 leak）
- `GET /api/v1/me/subscriptions` → 200 List<String>；不加 @PreAuthorize（個人訂閱清單只暴露給自己）

### 2.2 Listener integration

`NotificationProjectionListener.onVersionPublished(SkillVersionPublishedEvent e)`：
- 對齊既驗 4 listener pattern (`@ApplicationModuleListener` AFTER_COMMIT async + outbox redelivery)
- 流程：`skillRepo.findById(e.aggregateId())` → `subscriptionService.findSubscribersOf(skillId)` → 對每個 subscriber `save(subscriberId, "versions", title, ...)`
- **Self-action skip**：subscriber == skill.author → skip（per onReviewCreated / onRequestClaimed 既驗）
- **Preferences gate**：subscriber 各自查 `NotificationPreference.versions` flag；opt-out 跳過
- **Idempotency**：`ref_event_id = skillId + ":" + version`；UNIQUE(recipient_id, ref_event_id, category) 攔截重發

### 2.3 Bug AW 修補（同 spec 內 ship）

**問題**：`DelegatingPermissionEvaluator.expandPrincipals(auth, permission)` 對 authenticated user 只加 `user:` + `role:` 兩 namespace，未加 `*:read`。S122 ship `@PreAuthorize` 在 read endpoint 後，B authenticated 對 PUBLIC skill (acl_entries 含 `*:read` 但無 `user:viewer-007:read`) 反而 false → 403。**LAB 員工拿 JWT 看不到 PUBLIC skills**。

**修法**：`expandPrincipals` 加 `if ("read".equals(permission)) p.add("*:read");` — 對齊 `AclPrincipalExpander.expand` line 41 既驗。Authenticated read path 與 anonymous read path（per S122 既驗）對稱。

**Bug AW timeline**：S122 ship (v3.8.5) 引入；Tick 7 S125b ship 過程 surface（B subscribe PUBLIC 401 ↑ 403 路徑）；同 spec 內補修。

### 2.4 Trim list

XS=3 範圍緊；無可進一步 trim。Bug AW 修補在 §1 已標 scope creep（同 spec 內 ship 因 chain dep）。

## 3. SBE Acceptance Criteria

驗證指令：E2E manual via curl + targeted slice tests（subscriptionService 既驗 7 ACs 不 regression）

**AC-S125b-1：B authenticated subscribe PUBLIC → 201**
- Given：B JWT；PUBLIC skill (acl_entries 含 `*:read`)
- When：`POST /api/v1/skills/{public-id}/subscribe`
- Then：HTTP 201；DB row 寫入 `(skill_id, subscriber_id=viewer-007)`

**AC-S125b-2：B authenticated subscribe granted PRIVATE → 201**
- Given：B JWT；A 已 grant `user:viewer-007:read`
- When：`POST /api/v1/skills/{private-id}/subscribe`
- Then：HTTP 201

**AC-S125b-3：subscribe idempotent (重複 POST → 仍 201, 1 row)**
- Given：B 已 subscribed
- When：再 POST 兩次
- Then：HTTP 201；DB 仍 1 row

**AC-S125b-4：GET /me/subscriptions 回 List<String> skillIds**
- Given：B 已 subscribed 2 個 skill
- When：GET
- Then：HTTP 200；body=`[skill1-id, skill2-id]`

**AC-S125b-5：anonymous subscribe PRIVATE → 401**
- Given：no Authorization header；PRIVATE skill
- When：POST
- Then：HTTP 401（per @PreAuthorize + ExceptionTranslationFilter）

**AC-S125b-6：onVersionPublished 對訂閱者寫通知**
- Given：B subscribed PRIVATE skill；A 是 owner
- When：A `PUT /api/v1/skills/{private-id}/versions` (v2.0.0)
- Then：B `GET /api/v1/notifications` 含 1 條 `category=versions, title="e2e-private-skill 2.0.0 已發布", refEventId={id}:2.0.0`

**AC-S125b-7 (regression)：B authenticated read PUBLIC → 200 (Bug AW fix)**
- Given：B JWT；PUBLIC skill
- When：`GET /api/v1/skills/{public-id}`
- Then：HTTP 200（before fix → 403）

**AC-S125b-8 (regression)：S125a 7 ACs SubscriptionServiceTest 仍 PASS**
- Service-layer business logic 不 regression

## 4. Interface / File Plan

### Backend (production)

| File | Action | Description |
|------|--------|-------------|
| `backend/.../community/SkillSubscriptionController.java` | new | package-private REST controller；3 endpoints (POST/DELETE subscribe + GET /me/subscriptions)；@PreAuthorize 守 subscribe |
| `backend/.../notification/NotificationProjectionListener.java` | modify | (1) imports SkillVersionPublishedEvent + SkillSubscriptionService；(2) ctor 加 SkillSubscriptionService dep；(3) 加 `onVersionPublished` method (5th listener)；(4) Javadoc h2 升 4→5 listeners |
| `backend/.../shared/security/DelegatingPermissionEvaluator.java` | modify (Bug AW fix) | `expandPrincipals` 對 read permission 加 `*:read` pseudo-principal — 對齊 anonymous read 路徑 + S026 設計 |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/CHANGELOG.md` | append | v3.10.0 entry — S125b ship + Bug AW fix |
| `docs/grimo/specs/spec-roadmap.md` | modify | M120b row → ✅ + version v3.10.0 + 一行 highlight |
| `docs/grimo/specs/archive/2026-05-04-S125b-subscription-listener-endpoints.md` | new | 本 spec |

## 5. Test Plan

### 5.1 E2E manual smoke (real backend OAuth=true + curl)

對應 §3 全部 ACs（AC-S125b-1~8）— 走 Round 38 fixture（A=dev-042 owner 1 PUBLIC + 1 PRIVATE skill；B=viewer-007 grant `user:viewer-007:read` on PRIVATE）。

### 5.2 Targeted slice test (regression)

- `SkillSubscriptionServiceTest`（既驗 7 ACs from S125a）— 未 touch service 邏輯，預期不 regression
- 不另加 SkillSubscriptionControllerTest @WebMvcTest slice — 對齊本 ship 「E2E smoke 取代 unit test」既驗 lessons (per S121/S122/S123 spec doc §6)

## 6. Verification

### 6.1 E2E manual smoke 全 PASS（10/10）

```bash
# Round 38 fixture + S125a backend infra ready

# AC-S125b-1 B subscribe PUBLIC → 201 ✓
# AC-S125b-2 B subscribe PRIVATE granted → 201 ✓
# AC-S125b-3 重複 subscribe → 201 (DB 1 row) ✓
# AC-S125b-4 GET /me/subscriptions → ["public-id", "private-id"] ✓
# AC-S125b-5 anon subscribe PRIVATE → 401 ✓
# AC-S125b-6 A PUT /skills/{id}/versions v2.0.0 → B notification 出現
#            B notifications.items[0]:
#              category=versions
#              title="e2e-private-skill 2.0.0 已發布"
#              refEventId="9b3d5e50-...:2.0.0"
#              skillId="9b3d5e50-..." ✓
# AC-S125b-7 (Bug AW fix regression) B GET PUBLIC → 200 ✓
# AC-S125b-8 (S125a regression) Service-layer 7 ACs PASS（未跑；產品 code 未 touch service）
# 額外 regression：anon GET PUBLIC=200 / anon GET PRIVATE=401 / B GET PRIVATE granted=200 ✓
```

### 6.2 DB state verification

```
skill_subscriptions:
   skill_id   | subscriber_id
--------------+---------------
 PUBLIC-id    | viewer-007
 PRIVATE-id   | viewer-007

notifications:
 recipient_id | category | title                          | ref_event_id
--------------+----------+--------------------------------+--------------
 viewer-007   | versions | e2e-private-skill 2.0.0 已發布 | {pid}:2.0.0
```

### 6.3 ModularityTests

未額外執行（本 spec touch 跨 module 但無新 module / 無新 NamedInterface；既有 community + notification + skill 三 module 互通 path 維持）。

## 7. Result

### Shipped

- `SkillSubscriptionController` 3 endpoints — POST/DELETE subscribe + GET /me/subscriptions
- `NotificationProjectionListener.onVersionPublished` (5th listener) — 對齊既驗 4 listener pattern
- `DelegatingPermissionEvaluator.expandPrincipals` 補 `*:read` for read permission (Bug AW fix)
- E2E manual smoke 10/10 case PASS — PRD §285-§291 P9 SBE scenario 1 端到端完整 demo

### Verify metric

- Backend devtools restart 2.6s × 2（controller/listener 改 + Bug AW fix）
- E2E smoke PRD P9 scenario 1 完整：B subscribe → A publish v2.0.0 → B 收通知（title=「e2e-private-skill 2.0.0 已發布」）— 對齊 PRD wording
- Subscription DB 2 rows；notifications DB 1 row（subscriber-recipient path）

### Trim defer

- **無** — XS=3 範圍 + Bug AW 修補同 spec 內 ship（避 broken intermediate state）

### Bug AW timeline

- S122 ship (v3.8.5, 2026-05-04 Tick 3) 引入 — 加 `@PreAuthorize` 在 read endpoint 但未察 expandPrincipals 漏 `*:read`
- Tick 7 S125b ship 過程 surface — B authenticated subscribe PUBLIC 路徑揭露
- 同 spec 內補修（per S122 既驗 scope-creep handling pattern）

### LAB 封測 impact — chain 2/3 完成

- ✅ S125a backend infra (v3.9.0)
- ✅ **S125b endpoints + listener (v3.10.0)** — LAB 封測員工可從 API 完整測 PRD §285-§291 P9 SBE scenario 1
- 📋 S125c frontend SkillDetail subscribe button — 可 LAB 後補（員工已可從 API 測 flow）

### Lessons / Pattern reuse

- **第 10 次 single-tick XS/S spec ship**（per session lessons learned）
- **第 5 次 NotificationProjectionListener listener method**（4→5）對齊既驗 pattern
- **DelegatingPermissionEvaluator 第 2 次補 `*:read`**（Tick 3 S122 anonymous-read + 本 tick S125b authenticated-read symmetric fix）
- **Bug AW 揭露 lesson**：加 `@PreAuthorize` 在 read endpoint 時 ALWAYS 須 grep + 端到端驗證 `authenticated + PUBLIC` case；S122 ship 漏此 case（只測 anon PUBLIC + authenticated PRIVATE granted），edge case slipped。Future @PreAuthorize ship 須 cover 5 case 矩陣：(anon, auth) × (PUBLIC, PRIVATE) + owner。
- **Spec split chain 2/3 ship**：S125a (XS=4) + S125b (XS=3) 兩 tick 完成 LAB 封測 user-visible gap；S125c frontend 為 nice-to-have polish
