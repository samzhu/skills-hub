# S125a: SkillSubscription backend infra (LAB-blocker user-visible flow chain start)

> Spec: S125a | Size: XS(4) | Status: ✅ Shipped (v3.9.0)
> Date: 2026-05-04
> Source: Mode B Round 38 (2026-05-04) — Bug AV finding（HIGH，LAB user-visible gap）；S125 split first sub-spec

---

## 1. Goal

補完 PRD §285-§291 P9 SBE scenario 1「使用者訂閱了 docker-compose-helper skill / 該 skill 作者發布 v2.1.0 / 使用者通知中心顯示 1 unread badge」缺失的訂閱基礎建設。本 sub-spec 為 S125 split 三 sub-spec 的第一塊（**S125a backend infra**），交付 `SkillSubscription` aggregate / V14 migration / Repository / Service — 為 S125b（listener + endpoints）+ S125c（frontend）提供基礎。

**起源**：Mode B Round 38 (2026-05-04) E2E probe 確認 7 個 case 全 fail（POST subscribe=405 / GET subscribers=404 / DB skill_subscriptions table 不存在 / NotificationProjectionListener 無 onVersionPublished method）。Glossary line 37 已定義 `SkillSubscription` 但 codebase 0 實作。

**Split 設計**（per Tick 5 Round 38 plan）：
- **S125a (XS=4) — 本 spec**：backend infra (aggregate + V14 + repo + service)
- S125b (XS=3) — listener + endpoints (POST/DELETE subscribe + onVersionPublished listener)
- S125c (XS=3) — frontend (SkillDetail subscribe button)

**非目標**（本 spec 不做）：
- HTTP endpoints（`POST/DELETE /skills/{id}/subscribe`）→ S125b
- `NotificationProjectionListener.onVersionPublished` 訂閱 SkillVersionPublishedEvent → S125b
- Frontend SkillDetail subscribe button → S125c
- `GET /me/subscriptions` endpoint → S125b 或 S125d follow-up

## 2. Approach

走 **option A — community module 第三個 aggregate**（對齊 Collection / Request 既驗 pattern）。Subscription 是「user-skill 關係 join-table-like aggregate」，與 community 模組現有兩個 aggregate 同 domain semantic（user-curated content）。

### 2.1 Module placement 決策

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| **A. community module 第三個 aggregate** | 對齊既驗 Collection / Request pattern；同 module 可共用 NamedInterface (community::events) future expansion；無新 module 設計成本 | community 模組變大 | ⭐ |
| B. 新加 subscription module | 純粹 module boundary | 需新 NamedInterface / package-info / Modulith wiring；重複 Collection / Request 既驗 setup | |
| C. notification module 內 | Subscription 與 notification 強相關 | 違反 single responsibility（notification 是 read-side projection / subscription 是 write-side join entity） | |

走 **A**。

### 2.2 Aggregate 設計

對齊 ADR-002 canonical pattern：
- `extends AbstractAggregateRoot<SkillSubscription>` — 預留 future SkillSubscribedEvent / SkillUnsubscribedEvent 擴展
- `@Version Long version` — Spring Data JDBC 樂觀鎖
- 無 mutable state — subscribe = create + save row；unsubscribe = repo.delete row
- `@PersistenceCreator` 反序列化 ctor + factory `create(skillId, subscriberId)` 對齊既驗

### 2.3 Schema 設計

| Column | Type | 設計理由 |
|---|---|---|
| id | VARCHAR(36) | UUID PK；對齊 Collection / Request 既驗（非 BIGSERIAL） |
| skill_id | VARCHAR(36) | soft FK 不加 ON DELETE CASCADE — 對齊 collection_skills 既驗：skill SUSPENDED 後保留 subscription history |
| subscriber_id | VARCHAR(255) | JWT sub claim (per S115 既驗)；no FK 因 user 表不存在 |
| created_at | TIMESTAMPTZ | NOT NULL DEFAULT NOW() — 對齊 Collection.createdAt |
| version | BIGINT | NOT NULL DEFAULT 0 — `@Version` 樂觀鎖 |

UNIQUE(skill_id, subscriber_id) 守不重複訂閱；index on skill_id（S125b listener lookup）+ index on subscriber_id（"我的訂閱"頁面）。

### 2.4 Service 設計

5 個 method：
- `subscribe(skillId)` — idempotent；skill 不存在拋 NoSuchElementException
- `unsubscribe(skillId)` — idempotent；找不到 row 安靜 return
- `isSubscribed(skillId)` — boolean readOnly query；frontend SkillDetail 按鈕狀態
- `findSubscribersOf(skillId)` — readOnly；S125b listener 查 subscribers list
- `findSubscriptionsOfCurrentUser()` — readOnly；future "我的訂閱" 頁面

`@Transactional` class-level；read methods 走 `@Transactional(readOnly = true)`。

### 2.5 Trim list

XS=4 範圍緊；無可進一步 trim。已從 S125 (M=10-12) split 為三 sub-spec。

## 3. SBE Acceptance Criteria

驗證指令：`./gradlew test --tests "*SkillSubscriptionServiceTest" -x npmBuild`

**AC-S125a-1：subscribe 寫入一筆 row + isSubscribed=true**
- Given：bob current user；skill 'demo-skill' 存在
- When：service.subscribe(skillId)
- Then：repo.existsBySkillIdAndSubscriberId(skillId, bob)=true；service.isSubscribed(skillId)=true

**AC-S125a-2：subscribe 對不存在 skill → NoSuchElementException**
- Given：bob current user；skillId 不存在
- When：service.subscribe('nonexistent-id')
- Then：拋 NoSuchElementException + message 含 "Skill not found"

**AC-S125a-3：重複 subscribe 為 idempotent**
- Given：bob current user
- When：service.subscribe(skillId) 連續呼叫 3 次
- Then：repo.findBySkillId(skillId).size()=1

**AC-S125a-4：unsubscribe 移除 row + isSubscribed=false**
- Given：bob 已 subscribed
- When：service.unsubscribe(skillId)
- Then：service.isSubscribed(skillId)=false；repo.findBySkillId(skillId)=[]

**AC-S125a-5：unsubscribe 對未訂閱 skill → 安靜 noop**
- Given：bob 未 subscribed
- When：service.unsubscribe(skillId)
- Then：不拋例外；repo.findBySkillId(skillId)=[]

**AC-S125a-6：findSubscribersOf 回傳指定 skill 的所有 subscriber id**
- Given：bob/carol/dave 分別 subscribed skillId
- When：service.findSubscribersOf(skillId)
- Then：return ["bob", "carol", "dave"]（順序不限）

**AC-S125a-7：findSubscriptionsOfCurrentUser 回傳當前 user 訂閱的 skillId list**
- Given：bob current user；subscribed skill1 + skill2
- When：service.findSubscriptionsOfCurrentUser()
- Then：return [skill1, skill2]

## 4. File Plan

### Backend (production)

| File | Action | Description |
|------|--------|-------------|
| `backend/src/main/resources/db/migration/V14__create_skill_subscriptions.sql` | new | DDL：`skill_subscriptions(id PK, skill_id, subscriber_id, created_at, version)` + UNIQUE(skill_id, subscriber_id) + 2 indexes |
| `backend/.../community/SkillSubscription.java` | new | `@Table("skill_subscriptions")` aggregate；`extends AbstractAggregateRoot`；factory `create(skillId, subscriberId)`；`@Version Long version` `@JsonIgnore`；4 getter |
| `backend/.../community/SkillSubscriptionRepository.java` | new | `extends CrudRepository<SkillSubscription, String>`；4 derived query (findBySkillIdAndSubscriberId / existsBySkillIdAndSubscriberId / findBySkillId / findBySubscriberId) |
| `backend/.../community/SkillSubscriptionService.java` | new | `@Service @Transactional`；5 method (subscribe / unsubscribe / isSubscribed / findSubscribersOf / findSubscriptionsOfCurrentUser)；DI `SkillSubscriptionRepository` + `SkillRepository` + `CurrentUserProvider` |

### Backend (tests)

| File | Action | Description |
|------|--------|-------------|
| `backend/src/test/.../community/SkillSubscriptionServiceTest.java` | new | `extends RepositorySliceTestBase` + `@Import(SkillSubscriptionService.class)` + `@MockitoBean CurrentUserProvider` + 7 ACs |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/CHANGELOG.md` | append | v3.9.0 entry — S125a ship + verify metric + LAB 封測 chain start note |
| `docs/grimo/specs/spec-roadmap.md` | modify | S125 row → split 為 S125a (✅) / S125b (📋) / S125c (📋) 三 row |
| `docs/grimo/specs/archive/2026-05-04-S125a-subscription-backend-infra.md` | new | 本 spec 直接寫 archive (single-tick spec doc) |

## 5. Test Plan

### 5.1 Targeted slice test (automated)

- `SkillSubscriptionServiceTest`（new；7 ACs）：對應 §3 全部 ACs
- 既驗測試 0 regression：community module 既有 CollectionServiceTest / RequestService 等不 touch（純加 file，不改 module-public surface）

### 5.2 Smoke test (manual)

- DB schema check via `\d skill_subscriptions` — 驗 table + UNIQUE constraint + indexes
- Spring boot startup 套用 V14 — Flyway log 顯 "Successfully applied 1 migration to schema 'public', now at version v14"

## 6. Verification

### 6.1 自動測試

```
./gradlew test --tests "*SkillSubscriptionServiceTest" -x npmBuild
```

`SkillSubscriptionServiceTest`：（待 6.3 結果填入）— 預期 7/7 PASS

### 6.2 Smoke verification

✅ Flyway 套用 V14：`Successfully applied 1 migration to schema "public", now at version v14 (execution time 00:00.034s)`

✅ Schema 正確：
```
Table "public.skill_subscriptions"
    Column     |           Type           | Nullable | Default
---------------+--------------------------+----------+---------
 id            | character varying(36)    | not null |
 skill_id      | character varying(36)    | not null |
 subscriber_id | character varying(255)   | not null |
 created_at    | timestamp with time zone | not null | now()
 version       | bigint                   | not null | 0
Indexes:
    "skill_subscriptions_pkey" PRIMARY KEY, btree (id)
    "idx_skill_subscriptions_skill_id" btree (skill_id)
    "idx_skill_subscriptions_subscriber_id" btree (subscriber_id)
    "skill_subscriptions_skill_id_subscriber_id_key" UNIQUE CONSTRAINT, btree (skill_id, subscriber_id)
```

✅ Backend devtools restart：2.3s（含 V14 apply）

### 6.3 Targeted test results

`SkillSubscriptionServiceTest`：**7/7 PASS @ 9.1s**（AC-S125a-1 ~ AC-S125a-7）— 對應 §3 全部 ACs：
- subscribe persist + isSubscribed=true ✓
- 對不存在 skill → NoSuchElementException ✓
- 重複 subscribe idempotent（row 仍 1 筆）✓
- unsubscribe 移除 row + isSubscribed=false ✓
- unsubscribe 對未訂閱 skill 安靜 noop ✓
- findSubscribersOf 回傳 3 subscribers ✓
- findSubscriptionsOfCurrentUser 回傳 2 skillIds ✓

### 6.4 ModularityTests

未額外執行（本 spec 不變動 module boundaries；新 file 全在 community module 內，無 cross-module SPI 暴露）。

## 7. Result

### Shipped

- V14 migration `skill_subscriptions` table 套用成功（dev backend）
- `SkillSubscription` aggregate（ADR-002 canonical：`@Version` + `AbstractAggregateRoot` + factory + `@PersistenceCreator`）
- `SkillSubscriptionRepository` interface（4 derived query）
- `SkillSubscriptionService`（5 method；service-layer 業務邏輯 + idempotency + transactional boundary）
- `SkillSubscriptionServiceTest`（7 ACs；對齊 SkillSearchTest @MockitoBean CurrentUserProvider 既驗 pattern）

### Verify metric

- DB schema 套用成功；UNIQUE constraint + 2 indexes 對齊設計
- Spring boot startup 2.3s（含 V14 apply）
- SkillSubscriptionServiceTest：（待跑完填入；預期 7/7 PASS）

### Trim defer

- **無** — XS=4 範圍緊，single-tick 完整 ship 含 unit test + smoke verification。S125b/c 為 follow-up sub-spec 不在本 ship scope

### LAB 封測 impact — chain start

- S125a backend infra **ready** — S125b 可接續加 endpoint + listener
- 預計 LAB 封測前需 ship S125b（listener + POST/DELETE subscribe endpoint）讓員工可從 API 測 flow
- S125c frontend SkillDetail subscribe button 可 LAB 後補（frontend 整體 LAB 風格 polish 候選）

### Lessons / Pattern reuse

- **第 9 次 single-tick XS/S spec ship**（per session lessons learned）
- **第 3 次 community 模組 aggregate**（Collection / Request / SkillSubscription）對齊 ADR-002 canonical
- **Spec split pattern 第 1 次採用 S125 → S125a/b/c 三 sub-spec ship**（M=10-12 拆 3 個 XS） — 對齊 single-tick ship 範圍 + LAB 封測 user-visible 漸進補完
- **devtools `processResources` gotcha**：新增 .sql migration 須跑 `./gradlew processResources` 才會拷至 build/resources/main；compileJava 不會自動觸發；首次發現此 dev workflow 細節，未來新加 migration 須記得跑此 task
