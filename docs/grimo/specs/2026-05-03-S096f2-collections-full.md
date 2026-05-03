# S096f2: Collections Full Feature (aggregate + install + create)

> Spec: S096f2 | Size: M(13) re-est from M(10-12) | Status: 🚧 in-progress (4 tasks queued — cron tick handoff)
> Date: 2026-05-03

> **Tasks**: T01 backend Collection aggregate (AbstractAggregateRoot + @Version + @MappedCollection skills) + V12 schema (collections + collection_skills + ON DELETE CASCADE) + community @ApplicationModule formal register (skill::domain dep) + 2 events (CollectionCreated/Installed) + SkillRepo extension → T02 CollectionService + Command/Query controllers + 2 exceptions (CollectionNotFound 404 / SkillNotPublishable 400 with invalidSkillIds list) + GlobalExceptionHandler mapping + RequestService.fulfill caller migration → T03 frontend api/skills.ts 加 3 helper + 2 type + useCollections hook (category filter) + useCollection hook (single detail) → T04 CreateCollectionModal (MVP textarea skill picker per §2.6 trim) + InstallButton (loop browser download trigger 50ms 間隔) + CollectionsPage CTA enable + AC-10/11/12 tests。Execution order T01→T02→T03→T04（T02 依賴 T01 schema/aggregate；T03 對齊 T02 DTO shape；T04 全 backend + FE infra ship 後）。

---

## 1. Goal

讓使用者把多個既有 skills 打包成 **Collection**（curated bundle），其他人在 `/collections` 頁可以一鍵 install — 後端記 install event + 回傳 N 個 skill 的 download URL，前端依序觸發 browser 下載。把 ✅ S096f1 的 stub backend (`GET /collections` 回 `[]`) + EmptyState UI 升級為完整功能。

**起源**：S096f1 ship 時明確 defer 完整 aggregate + install + create 至本 spec；前端 CollectionsPage 已有 disabled「建立集合」CTA + EmptyState invite tone，UI shell 完整等填空。對齊 PRD §P7 SBE Scenarios（創建 / 一鍵安裝 / 分類篩選）。

**Visual flow — Install 流程**：

```
User 在 /collections 看到 "DevOps Starter Pack" card
   ↓ 點 [Install]
Frontend POST /api/v1/collections/{id}/install
   ↓
CollectionService.install(id) → aggregate.recordInstall()
                              → collections.install_count +1（同 TX）
                              → registerEvent(CollectionInstalledEvent)
   ↓
Backend 回 200 { downloadUrls: ["/api/v1/skills/sk1/download", ...] }
   ↓
Frontend loop trigger N 個 <a download> click（50ms 間隔避免 browser 阻擋）
   ↓
Browser 各自打 GET /skills/{id}/download
   → skill 既有 endpoint 自動 fire SkillDownloaded event（每個 skill 自然累計 download_count）
   → 用戶下載完 N 個 zip
   ↓
Toast「已下載 N 個技能，請放到 ~/.claude/skills/」
```

**Visual flow — Create 流程**：

```
User 點 /collections 頁的「建立集合」
   ↓ 開 modal
[name input] + [description textarea] + [category text] + [skill picker（multi-select）]
   ↓ 選 ≥1 個 skill + 填必要欄位 + 點 Submit
Frontend POST /api/v1/collections
       body { name, description, category, skillIds: [...] }
   ↓
CollectionCommandController → CollectionService.create()
   ↓
Collection.create() factory 驗證 (name not blank, skillIds 非空, 所有 skillId 存在且 PUBLISHED)
   → registerEvent(CollectionCreatedEvent(id, name, ownerId, skillIds))
   → save → outbox
   ↓
Frontend 樂觀 invalidate ['collections'] + 關 modal + toast「集合已建立」
```

## 2. Approach

走 **ADR-002 canonical pattern**（Spring Data JDBC 充血 + Modulith Outbox）+ **Install 走 Approach C（frontend orchestration + 後端只 record event 與 counter）**。

### 2.1 Install semantic — 三案比較

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| A. Counter only | 最簡 | ❌ 違反 PRD「依序下載」 | |
| B. Backend mega-zip（後端解壓 N 個 zip 重壓成 1 個 super-zip） | 1 次下載 | ❌ Heavy（10 個 skill = 10 解壓 + 重壓 + large file）；timeout 風險；佔 disk | |
| **C. Frontend orchestration** | 對齊 PRD「依序下載 + auto latest version」；reuse 既有 `GET /skills/{id}/download` endpoint（自然累計每 skill download counter + analytics 反映）；後端 install endpoint 純 record event + bump counter | 前端要管 N 個 download trigger（browser 對 rapid-fire downloads 有 throttle，需 50ms 間隔） | ⭐ |

走 **C**。後端 install endpoint 不打包檔案；只 record `CollectionInstalled` event + bump `install_count` + 回傳 N 個 download URL；前端 loop 觸發 browser download。Skill 端既有 `GET /skills/{id}/download` 自動 fire `SkillDownloaded` event — 每個 skill 自然累計自己的 download_count。

### 2.2 6 個產品/UX 決策

| # | 決策 | 採用 | 理由 |
|---|---|---|---|
| 1 | Aggregate pattern | **ADR-002 canonical** (Spring Data JDBC 充血 + Modulith Outbox) | 對齊 S098e2 / Skill |
| 2 | Create 權限 | **任何登入用戶** | PRD 未限制；Feature First |
| 3 | Skills 含哪些 | **建立時直接附 skillIds list (≥ 1)**；MVP 不開 add/remove；edit 動作 defer | PRD §234「選 3 個 skills 加入」就是建立時選；YAGNI |
| 4 | Public/private | **MVP 全 public** | PRD 無 private 訴求；Skill 也預設 public |
| 5 | Category | **Free-text，對齊 Skill.category 同 convention** | 既有 `CollectionSummary.category` 是 String |
| 6 | Name uniqueness | **不強制 unique**（即使同 owner 也允許重名） | PRD 無此 constraint；Skill 也只 (author, name) unique；MVP 簡化 |

### 2.3 community module 正式 register

S096f1 留註「等 f2 + g2 都 ship 後統一補 `@ApplicationModule`」。本 spec 借此 ship 補上：

```java
// community/package-info.java（新增）
@org.springframework.modulith.ApplicationModule(
    displayName = "Community",
    allowedDependencies = {"shared :: events", "shared :: api", "shared :: security", "skill :: domain"}
)
package io.github.samzhu.skillshub.community;
```

`skill :: domain` 是為了 CollectionService.create 驗證 skillIds 存在且 PUBLISHED — 透過 `SkillRepository.findById` / `existsByIdAndStatus`（已存在的 read-only repo）。**不需** `skill :: api`（既有模組無此 sub-namespace；PUBLISHED 驗證走 domain repo 即可）。

`@DomainEvents` outbox 已在 S023 / S024 wire；`CollectionCreatedEvent` / `CollectionInstalledEvent` 自動進 outbox。MVP 無 listener 訂閱（給 future S101b Impact Score / 跨模組 analytics 預留 hook）。

### 2.4 Risk filter — defer

PRD §P7 SBE Scenario 3「過濾掉含 high-risk skill 的集合」需要 JOIN `collection_skills` × `skills.risk_level` 額外邏輯。本 MVP scope **只支援 `?category=` filter**，risk filter defer 為 **S096f3 polish**（unblocked 由本 spec ship 後）。

### 2.5 Behavior validation

| 決策 | Confidence | 證據 |
|------|------------|------|
| ADR-002 canonical pattern | Validated | S024 ship + S098e2 同 pattern |
| `@MappedCollection` 處理 collection → skills 一對多 | Validated | Skill aggregate 已用同 pattern 管 skill_versions |
| `@DomainEvents` outbox auto-publish | Validated | S023 / S024 引用 |
| Modulith `community` allowedDependencies 含 `skill :: domain` | Validated | 既有 audit / analytics 同 pattern |
| Frontend N 個 browser download 50ms 間隔 | **Hypothesis** | Web 標準 browsers 對 rapid downloads 有 throttle，但具體 throttle 表現跨瀏覽器；implementer 試 Chrome / Firefox 確認 |

唯一 Hypothesis 是 frontend download trigger timing — 不阻塞 spec design。Implementer 可調整 interval（50ms / 100ms / queue with await）。**不需 POC**（已知瀏覽器行為，非新框架）。

### 2.6 Trim list

M(13) 一個 cron tick 可能 wall hit；可 defer 的 polish：

- **Risk filter**（已標 defer 至 S096f3）
- **Skill picker UI 漂亮版**（搜尋 + 多選 chip + autocomplete）— MVP 可走粗版「skill UUID list 用換行分隔貼進 textarea」，能 ship；漂亮 picker defer
- **Collection detail page**（`/collections/{id}` 單頁）— MVP 用 modal 顯細節即可；獨立路由 defer
- **Edit / Delete** Collection（owner 自己的）— 完全 defer 至後續 spec

### 2.7 Research Citations

無外部框架研究 — 全部使用既有專案內 pattern。Internal references：

- `docs/grimo/specs/archive/2026-05-02-S096f1-collections-stub.md`（前置 stub spec + frontend 已 ship 部分）
- `docs/grimo/PRD.md` §P7 lines 227-250（SBE Scenarios）
- `docs/grimo/adr/ADR-002-skill-aggregate-state-based.md`（aggregate pattern）
- `backend/.../skill/domain/Skill.java`（@MappedCollection skill_versions 範本）
- `backend/.../community/CollectionController.java`（既有 stub + `CollectionSummary` record 對齊 frontend `SkillCollection` type）
- `frontend/src/pages/CollectionsPage.tsx`（既有 page shell + `CollectionCard` 可 reuse；CTA 從 disabled 改 active 即可）
- `frontend/src/api/skills.ts:99-114`（`SkillCollection` type 已定義）

## 3. SBE Acceptance Criteria

驗證指令：

- Backend：`./gradlew test` + `./gradlew modulithTest`
- Frontend：`cd frontend && npm test`
- Pass：所有 `@DisplayName("AC-N: ...")` / `@Tag("AC-N")` 測試綠 + Modulith boundary verifier 綠

---

**AC-1：建立 collection — happy path**
- Given：alice 已登入；3 個 PUBLISHED skill 存在 (sk1/sk2/sk3)
- When：alice 發 `POST /api/v1/collections` body `{"name":"DevOps Starter","description":"...","category":"DevOps","skillIds":["sk1","sk2","sk3"]}`
- Then：回 201 + body `{"id":"<uuid>"}`；DB `collections` 一筆 + `collection_skills` 三筆 (position 0/1/2)；outbox 寫入 `CollectionCreatedEvent`

**AC-2：skillIds 空 list 拒絕**
- Given：alice 登入
- When：POST body `{"name":"X","skillIds":[]}`
- Then：回 400 + `error: "collection_must_have_skills"`

**AC-3：含不存在 / 非 PUBLISHED skill 拒絕**
- Given：sk1 PUBLISHED；sk2 SUSPENDED；sk-bogus 不存在
- When：POST body `skillIds:["sk1","sk2","sk-bogus"]`
- Then：回 400 + `error: "skill_not_publishable"` + body 含哪些 skillIds invalid

**AC-4：name 長度上限**
- Given：alice 登入
- When：POST body `name` 為 201 字元
- Then：回 400 + `error: "name_too_long"`（cap 200）

**AC-5：列表 endpoint — 預設與 category filter**
- Given：3 collection (DevOps / Frontend / Frontend)
- When：發 `GET /api/v1/collections`
- Then：回 200 + 3 筆，按 createdAt desc
- When：發 `GET /api/v1/collections?category=Frontend`
- Then：回 200 + 2 筆 (Frontend 的)

**AC-6：單筆 endpoint 含 skills detail**
- Given：collection `c1` 含 sk1/sk2
- When：發 `GET /api/v1/collections/c1`
- Then：回 200 + body 含 collection 欄位 + `skills: [{id, name, category, riskLevel, latestVersion}, ...]`（兩個 skill summary）

**AC-7：Install — happy path**
- Given：collection `c1` 含 sk1/sk2/sk3 (全 PUBLISHED)；當前 install_count = 5
- When：alice 發 `POST /api/v1/collections/c1/install`
- Then：回 200 + body `{"downloadUrls": ["/api/v1/skills/sk1/download", "/api/v1/skills/sk2/download", "/api/v1/skills/sk3/download"]}`；DB `collections.install_count` 變 6；outbox 寫入 `CollectionInstalledEvent(c1, alice)`

**AC-8：Install — 不存在 collection 404**
- Given：collection `c-bogus` 不存在
- When：POST `/api/v1/collections/c-bogus/install`
- Then：回 404 + `error: "collection_not_found"`

**AC-9：Modulith 邊界驗證**
- Given：本 spec 加 `community/package-info.java` `@ApplicationModule`
- When：跑 `./gradlew modulithTest`
- Then：所有 ModularityTests PASS（無循環依賴 + community 只用宣告的 allowedDependencies）

**AC-10：Frontend `/collections` — 「建立集合」按鈕啟用**
- Given：CollectionsPage 渲染後
- When：query 「建立集合」按鈕
- Then：button **不再 disabled**；點擊開 create modal

**AC-11：Frontend Create modal — happy path**
- Given：alice 登入；3 個 PUBLISHED skill (sk1/sk2/sk3)
- When：alice 點「建立集合」→ modal 開 → 填 name "DevOps Starter" + description + category "DevOps" + skill picker 選 sk1/sk2/sk3 → 點 Submit
- Then：modal 關閉；CollectionsPage 出現新 card「DevOps Starter」(skillCount=3, installs=0)；toast「集合已建立」

**AC-12：Frontend Install — N 個 download trigger**
- Given：collection card 對應 c1（含 3 個 skill）
- When：user 點 card 上「Install」按鈕
- Then：發 POST install endpoint；接收 downloadUrls；loop trigger 3 個 `<a download>` click（mock 驗證 download attribute 與 click 順序，間隔 ≥ 30ms）；toast「已下載 3 個技能...」

## 4. Interface / API Design

### 4.1 Backend — REST endpoints

```
POST   /api/v1/collections                       # 建立
   body { name: string ≤200, description: string nullable ≤2000,
          category: string ≤100, skillIds: string[] (≥1) }
   201 { id: string }
   400 collection_must_have_skills / skill_not_publishable / name_too_long
   401 unauthenticated（依 LAB mode 配合）

GET    /api/v1/collections                       # 列表
   query ?category=string (optional)
   200 [{ id, name, description, category, skillCount, installs, createdAt }, ...]

GET    /api/v1/collections/{id}                  # 單筆 + skills detail
   200 { id, name, description, category, ownerId, installCount, createdAt,
         skills: [{ id, name, category, riskLevel, latestVersion }, ...] }
   404 collection_not_found

POST   /api/v1/collections/{id}/install          # 安裝（記 event + 回 URLs）
   200 { downloadUrls: ["/api/v1/skills/sk1/download", ...] }
   404 collection_not_found
```

### 4.2 Backend — Schema migration

```sql
-- V<next>__create_collections_tables.sql
CREATE TABLE collections (
    id              VARCHAR(36) PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    owner_id        VARCHAR(255) NOT NULL,
    category        VARCHAR(100) NOT NULL,
    install_count   INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_collections_category ON collections (category);
CREATE INDEX idx_collections_created  ON collections (created_at DESC);
CREATE INDEX idx_collections_owner    ON collections (owner_id);

CREATE TABLE collection_skills (
    collection_id   VARCHAR(36) NOT NULL REFERENCES collections(id) ON DELETE CASCADE,
    skill_id        VARCHAR(36) NOT NULL,  -- soft FK; skill 可能 SUSPENDED 後仍保留歷史
    position        INTEGER NOT NULL,
    PRIMARY KEY (collection_id, skill_id)
);
CREATE INDEX idx_collection_skills_skill ON collection_skills (skill_id);
```

### 4.3 Backend — Aggregate (Spring Data JDBC + @MappedCollection)

```java
@Table("collections")
public class Collection extends AbstractAggregateRoot<Collection> implements Persistable<String> {
    @Id String id;
    String name;
    String description;
    @Column("owner_id") String ownerId;
    String category;
    @Column("install_count") int installCount;
    @Column("created_at") Instant createdAt;

    @MappedCollection(idColumn = "collection_id", keyColumn = "position")
    List<CollectionSkillRef> skills;

    public static Collection create(String name, String description, String category,
                                    String ownerId, List<String> skillIds) {
        validateName(name); validateSkillIds(skillIds);
        var c = new Collection(...);
        c.skills = skillIds.stream().map(CollectionSkillRef::new).toList();
        c.registerEvent(new CollectionCreatedEvent(c.id, c.name, c.ownerId, skillIds));
        return c;
    }

    public void recordInstall(String installerId) {
        this.installCount++;
        registerEvent(new CollectionInstalledEvent(this.id, installerId));
    }

    @Override public boolean isNew() { return this.createdAt == null; }
    // ... validators omitted
}

@Table("collection_skills")
record CollectionSkillRef(@Column("skill_id") String skillId) {}
```

### 4.4 Backend — Domain events

```java
public record CollectionCreatedEvent(String collectionId, String name, String ownerId, List<String> skillIds) {}
public record CollectionInstalledEvent(String collectionId, String installerId) {}
```

放置：`community/events/`（與 controller 同 module；implementer 對齊既有專案結構）。

MVP **無 listener 訂閱**這兩個 event — outbox 會自動寫 `event_publication`，給 future S101b Impact Score / cross-module analytics 預留 hook。

### 4.5 Backend — Service shape

```java
@Service
public class CollectionService {
    private final CollectionRepository repo;
    private final SkillRepository skillRepo;          // skill::domain，read-only
    private final CurrentUserProvider users;          // shared::security

    @Transactional
    public String create(String name, String description, String category, List<String> skillIds) {
        // 1. validate all skillIds exist + PUBLISHED
        var foundIds = skillRepo.findAllPublishedByIdIn(skillIds);
        if (foundIds.size() != skillIds.size()) {
            throw new SkillNotPublishableException(diff(skillIds, foundIds));
        }
        // 2. factory
        var collection = Collection.create(name, description, category, users.current().userId(), skillIds);
        repo.save(collection);  // outbox auto-publish
        return collection.getId();
    }

    @Transactional
    public List<String> install(String collectionId) {
        var collection = repo.findById(collectionId).orElseThrow(() -> new CollectionNotFoundException(collectionId));
        collection.recordInstall(users.current().userId());
        repo.save(collection);
        return collection.getSkills().stream()
            .map(s -> "/api/v1/skills/" + s.skillId() + "/download")
            .toList();
    }

    public List<CollectionSummary> list(String categoryFilter) { ... }
    public CollectionDetail get(String id) { /* JOIN with skill detail */ }
}
```

`SkillRepository.findAllPublishedByIdIn` 若不存在則 implementer 加（或用 existing `findAllById` + filter status）。

### 4.6 Frontend — API + hooks

**修改 `frontend/src/api/skills.ts`**（已含 `SkillCollection` interface + `fetchCollections`，補三個新 fetcher）：

```typescript
// 既有 SkillCollection / fetchCollections 保留

export interface CreateCollectionRequest {
  name: string
  description: string | null
  category: string
  skillIds: string[]
}

export function createCollection(body: CreateCollectionRequest): Promise<{ id: string }> {
  return apiFetch('/collections', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export interface CollectionDetail extends SkillCollection {
  ownerId: string
  installCount: number
  skills: Array<{ id: string; name: string; category: string; riskLevel: string | null; latestVersion: string | null }>
}

export function fetchCollection(id: string): Promise<CollectionDetail> {
  return apiFetch(`/collections/${id}`)
}

export function installCollection(id: string): Promise<{ downloadUrls: string[] }> {
  return apiFetch(`/collections/${id}/install`, { method: 'POST' })
}
```

**新檔 `frontend/src/hooks/useCollections.ts` / `useCollection.ts`**：標準 TanStack Query hooks（對齊 useSkill / useFlags pattern）。

### 4.7 Frontend — CollectionsPage edits

修改 `frontend/src/pages/CollectionsPage.tsx`：

1. 「建立集合」CTA 從 disabled → active；onClick 開 `<CreateCollectionModal>`
2. `CollectionCard` 加「Install」按鈕；onClick 呼 `installCollection(id)` → loop trigger N 個 `<a download>`
3. 新內部 components（implementer 視 testability 可抽 `frontend/src/components/CreateCollectionModal.tsx` / `InstallButton.tsx` 獨立檔，per S112-T03 deviation 啟示）

### 4.8 Frontend — Skill picker

MVP 接受**最簡實作**：modal 內提供「選擇技能」按鈕 → 開 secondary modal 列出 PUBLISHED skills (reuse `useSkillList`) → 多選 checkbox → 確定。

若 wall hit，可進一步 trim 為 textarea 貼 skill UUID list（每行一個）— functional 但醜，留 polish 給後續 spec。

## 5. File Plan

### Backend

| File | Action | Description |
|------|--------|-------------|
| `backend/.../community/package-info.java` | new | `@ApplicationModule(displayName="Community", allowedDependencies={...})` 正式 register |
| `backend/.../community/Collection.java` | new | Aggregate `extends AbstractAggregateRoot` + `@MappedCollection` skills |
| `backend/.../community/CollectionSkillRef.java` | new | Inner record for join row（或合併進 Collection.java） |
| `backend/.../community/CollectionRepository.java` | new | Spring Data JDBC repo + derived queries |
| `backend/.../community/CollectionCommandController.java` | new | POST create / POST install |
| `backend/.../community/CollectionQueryController.java` | new | GET list / GET single（**取代** 既有 `CollectionController.java` stub） |
| `backend/.../community/CollectionController.java` | delete | 由 Command + Query controller 取代（既有 S096f1 stub） |
| `backend/.../community/CollectionService.java` | new | create / install / list / get business orchestration |
| `backend/.../community/events/CollectionCreatedEvent.java` | new | record |
| `backend/.../community/events/CollectionInstalledEvent.java` | new | record |
| `backend/.../skill/domain/SkillRepository.java` | modify (or new derived query) | 加 `findAllPublishedByIdIn(List<String>)` 或 implementer 用 `findAllById` + filter |
| `backend/src/main/resources/db/migration/V<next>__create_collections_tables.sql` | new | 見 §4.2 |
| `backend/src/test/.../community/CollectionServiceTest.java` | new | AC-1/2/3/4/7 (Testcontainers) |
| `backend/src/test/.../community/CollectionCommandControllerTest.java` | new | AC-1/2/3/8 web slice |
| `backend/src/test/.../community/CollectionQueryControllerTest.java` | new | AC-5/6 web slice |
| `backend/src/test/.../ModularityTests.java` | modify | AC-9 — 確認 community module 邊界（既有 ModularityTests 應自動 pick up 新 @ApplicationModule） |

### Frontend

| File | Action | Description |
|------|--------|-------------|
| `frontend/src/api/skills.ts` | modify | 加 `createCollection` / `fetchCollection` / `installCollection` + `CreateCollectionRequest` / `CollectionDetail` types |
| `frontend/src/hooks/useCollections.ts` | new | TanStack Query hook (replace inline query in CollectionsPage) |
| `frontend/src/hooks/useCollection.ts` | new | Single collection hook |
| `frontend/src/pages/CollectionsPage.tsx` | modify | CTA enabled + 加 CreateCollectionModal + Install button on card |
| `frontend/src/pages/CollectionsPage.test.tsx` | new (or modify if exists) | AC-10 / AC-11 / AC-12 tests |
| `frontend/src/components/CreateCollectionModal.tsx` | new (recommended; implementer 可選擇 inline within page per testability) | name + description + category + skill picker (multi-select) + submit |
| `frontend/src/components/SkillMultiPicker.tsx` | new (optional — 可裹 reuse useSkillList) | MVP 可走最簡版 |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/specs/spec-roadmap.md` | modify | M90f2 row：📋 → 📐 in-design + 估點修為 M(13) + 設計摘要 |
| `docs/grimo/specs/spec-roadmap.md` | modify | 加 S096f3 row：📋 planned「Collections risk filter polish」（defer scope） |
| `docs/grimo/glossary.md` | modify | 加 Collection / Curated Bundle 中英對照 |
| `docs/grimo/architecture.md` | modify | community module 加進 module map（與 audit / analytics 同層） |

---

<!-- Sections 6-7 added by /planning-tasks after implementation -->
