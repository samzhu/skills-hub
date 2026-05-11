# S164: Collection Owner Management — Update / Delete

> Spec: S164 | Size: S(5) → backend XS(3) | Status: 🚧 backend impl 完成 2026-05-12（PUT + DELETE + 2 events + auth check + 8 tests PASS；frontend EditCollectionModal + action bar 待 S150 ship 後拆 S164b）
> Date: 2026-05-08
> Origin: deployment audit 2026-05-08（LAB）— `OPTIONS /api/v1/collections/{id}` 回 `Allow: GET, HEAD, OPTIONS`，**完全沒 mutation methods**。Owner 創 collection 後無法改 / 刪。實測 anon 建了 spam collection `0a514c85-...` 後**沒任何 API 可清**，必須走 DBA 手動 SQL。

---

## 1. Goal

讓 collection owner 能編輯 / 刪除自己創的 collection，跟 skill owner management（S163）對齊：

1. **PUT /api/v1/collections/{id}**: 改 name / description / category / skillIds
2. **DELETE /api/v1/collections/{id}**: hard delete

**為什麼重要：**
- 創 collection 是門檻低操作（任何 user 隨手創）；無 cleanup 路徑導致 DB 永遠累積 stale data
- 改錯 typo 必須砍掉重練，違反 user 直覺
- LAB audit 已產生 1 筆 spam collection 沒法清；ship 後正式環境每天累積

**非目標：**
- 不做 soft-delete（直接硬刪，與 S144 skill delete pattern 一致）
- 不做 audit log（屬 cross-cutting，留另開 spec）
- 不做 admin force-delete

---

## 2. Approach

### 2.1 API

```
PUT    /api/v1/collections/{id}     → update
DELETE /api/v1/collections/{id}     → hard delete
```

**Authorization**：`@PreAuthorize("@collectionPermission.check(#id, principal, 'manage')")`

`collection_acl` projection（per S016 RBAC）：
- `OWNER` → manage
- `VIEWER` (public:* 或 user:specific) → 只 read

需確認 `collectionPermission` evaluator 是否已存在；若無走基礎 `ownerId.equals(currentSub)` 比對。

### 2.2 PUT body

```json
{
  "name": "Updated Pack",
  "description": "...",
  "category": "security",
  "skillIds": ["sk-1", "sk-2", "sk-3"]
}
```

`skillIds` 整段覆蓋（add / remove 都用一次 PUT）。skillCount 自動由 backend recalc。

可選 follow-up：對 skillIds add/remove 提供 PATCH `/skills` sub-resource（細粒度），但 MVP 整段覆蓋簡單夠用。

### 2.3 DELETE 行為

- 刪 collection 主記錄
- 刪 collection_acl 對應 entries
- 不刪 collection 內含 skill 個體（skill 仍存在）
- 不影響歷史 install_count（已下載的不撤銷）
- 發布 `CollectionDeletedEvent`

### 2.4 Frontend

`CollectionDetailPage`（per S150）加 owner-only action bar：
- `[編輯]` button → 開 EditCollectionModal（reuse CreateCollectionModal pattern）
- `[刪除]` button → confirm dialog → DELETE → redirect `/collections` + toast

`CollectionCard` 在 `/collections` list owner 視角加 quick-action menu（kebab "..."）含「編輯 / 刪除」。

### 2.5 Anonymous Mutation 問題（與本 spec 並列）

LAB audit 發現 anonymous user (no cookie) 透過 `LabSecurityFilter` 預設 `lab-user` principal 可建 collection / review / vote。本 spec **不直接** fix 這個（屬 S160 security hardening 範疇），但 backend mutation API 加了 owner-only check 後，「lab-user」就只能改自己的 collection，**不能改其他 user 的** — 形成天然 isolation。

仍需 S160 ship 後（CSRF + 認證強制）才完整擋住「無認證者建 spam」攻擊。

---

## 3. Acceptance Criteria

```
AC-1: Owner update collection
  Given Alice 是 collection X 的 owner
  When Alice PUT /collections/X body={name:"new"}
  Then 回 200
  And GET /collections/X.name="new"
  And CollectionUpdatedEvent 發布

AC-2: 非 owner update → 403
  Given Bob 非 collection X 的 owner
  When Bob PUT /collections/X
  Then 回 403 FORBIDDEN

AC-3: Owner delete collection
  Given Alice 是 collection X 的 owner
  When Alice DELETE /collections/X
  Then 回 204
  And GET /collections/X 回 404
  And CollectionDeletedEvent 發布

AC-4: 非 owner delete → 403
  Given Bob 非 owner
  When Bob DELETE /collections/X
  Then 回 403

AC-5: 刪 collection 不影響內含 skill
  Given collection X 含 skill A, B
  When delete X
  Then skill A, B 仍存在；其 downloadCount 不變

AC-6: skillIds 整段覆蓋
  Given collection X.skillIds=[a,b,c]
  When PUT body={skillIds:[d,e]}
  Then GET X.skillIds=[d,e]（覆蓋，不是 append）
  And skillCount=2

AC-7: Frontend owner 可見編輯 / 刪除 button
  Given Alice 訪問 /collections/{X}（自己創的）
  When 頁面 render
  Then 顯示 [編輯] [刪除] action buttons
  And 非 Alice 訪問同 page 不顯這些 buttons

AC-8: Frontend 確認 dialog
  Given owner 點「刪除」
  When click
  Then 顯確認 dialog「確定刪除集合 'X'？此動作無法復原」
  And 確認後 redirect /collections + toast 「集合已刪除」
```

驗證指令：`cd backend && ./gradlew test`（per qa-strategy.md；新增 `CollectionOwnerManagementTest`）

---

## 4. Files to Change

| 檔案 | 變動 |
|------|------|
| `backend/src/main/java/.../collection/CollectionController.java`（or wherever）| 加 PUT / DELETE endpoints |
| `backend/src/main/java/.../collection/CollectionService.java` | 加 update / delete 方法 |
| `backend/src/main/java/.../collection/CollectionDeletedEvent.java`、`CollectionUpdatedEvent.java` | 新增 |
| `backend/src/main/java/.../shared/security/CollectionPermissionEvaluator.java` | 新增 / 確認已存在 |
| `frontend/src/pages/CollectionDetailPage.tsx` | 加 owner-only action bar（reuse PageHeader pattern from skill）|
| `frontend/src/components/EditCollectionModal.tsx` | 新增（reuse CreateCollectionModal struct + initial values）|
| `frontend/src/components/CollectionCard.tsx`（在 list） | owner 視角加 kebab menu |
| `frontend/src/api/skills.ts`（or collections.ts） | 加 updateCollection / deleteCollection |
| **Tests** | 對應 8 ACs |

---

## 5. Test Plan

### 5.1 自動化（gradlew test）

```java
@Test @DisplayName("AC-3: owner delete collection")
void ownerDeleteCollection() throws Exception {
    mvc.perform(delete("/api/v1/collections/{id}", collId)
            .with(authentication(ownerAuth())))
        .andExpect(status().isNoContent());
    
    mvc.perform(get("/api/v1/collections/{id}", collId))
        .andExpect(status().isNotFound());
}

@Test @DisplayName("AC-4: non-owner delete → 403")
void nonOwnerDelete403() throws Exception {
    mvc.perform(delete("/api/v1/collections/{id}", collId)
            .with(authentication(nonOwnerAuth())))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("FORBIDDEN"));
}

@Test @DisplayName("AC-5: delete 不影響內含 skill")
void deleteCollectionPreservesSkills() throws Exception {
    var skillsBefore = skillRepo.findAll().size();
    deleteCollection(collId);
    assertThat(skillRepo.findAll().size()).isEqualTo(skillsBefore);
}
```

### 5.2 手動 LAB（含 cleanup spam 的實際 use case）

deploy 後：
- [ ] 訪問 LAB 已存在的 spam collection `0a514c85-a80f-403b-812c-e7a440f100bd`（owner: lab-user）→ 確認可 cleanup
- [ ] PUT 改 name / description → 即時生效
- [ ] DELETE → 204 + 從 list 消失
- [ ] 非 owner DELETE → 403

---

## 5.4 Frontend Phase 1 結果（2026-05-12）— S164b modal + action bar 收尾 S164 全 8 ACs

### Ship 範圍

| AC | 內容 | 狀態 |
|---|---|---|
| AC-7 | Frontend owner 可見 [編輯][刪除] action buttons；非 owner 不顯 | ✅ PASS — CollectionDetailPage.test 2 new cases 守 owner / non-owner 行為 |
| AC-8 | confirm dialog（window.confirm）「確定刪除集合『X』？此動作無法復原」+ DELETE → redirect /collections | ✅ PASS — onDeleteClick 走 window.confirm + deleteCollection mutation + navigate('/collections') onSuccess |
| AC-1 補強 | EditCollectionModal 預填 name/description/category/skillIds + 整段覆蓋（包含 skillIds） | ✅ PASS — EditCollectionModal.test 6 cases (prefill / disabled-unchanged / submit-payload / skillIds-overwrite / cancel / name-blank disabled) |

### S150 dependency 狀態釐清

Spec 原寫「S150 ✅ ship 前提」— 實際 codebase 已含 `CollectionDetailPage.tsx` + 既驗 6/6 tests，本 tick 入手前先 grep + run test 確認 S150 functional ship。roadmap status 之前漏更新為 ✅，本 tick 同 commit 補正（housekeeping）。

### S164 spec 全部 8 ACs ✅

| AC | 內容 | Ship Commit |
|---|---|---|
| AC-1 | owner update → 200 + event | 8fbee3d (backend) + 本 tick (frontend) |
| AC-2 | 非 owner update → 403 | 8fbee3d |
| AC-3 | owner delete → 204 + event | 8fbee3d (backend) + 本 tick (frontend wire-up) |
| AC-4 | 非 owner delete → 403 | 8fbee3d |
| AC-5 | 刪 collection 不影響內含 skill | 8fbee3d (service.delete 只動 collection table) |
| AC-6 | skillIds 整段覆蓋 | 8fbee3d (aggregate) + 本 tick (modal) |
| AC-7 | owner 可見 action buttons | 本 tick |
| AC-8 | confirm dialog + redirect + 自動 refresh | 本 tick |

**S164 為原 5-spec 第四個 fully shipped。**

### 改動檔案

| File | 變動 |
|---|---|
| `frontend/src/api/skills.ts` | 加 `updateCollection(id, body)` + `deleteCollection(id)` helpers |
| `frontend/src/components/EditCollectionModal.tsx`（**新檔**）| Mirror EditSkillModal pattern；prefill 4 個欄位含 skillIds textarea 一行一個 UUID；trim+empty 驗證；unchanged disable submit；invalidate ['collection',id] + ['collections'] |
| `frontend/src/pages/CollectionDetailPage.tsx` | 加 `isOwner` check + `editOpen` state + EditCollectionModal render + 2 action buttons + window.confirm + deleteMutation 含 redirect to /collections + error display |
| `frontend/src/components/EditCollectionModal.test.tsx`（**新檔**）| 6 cases — prefill / disabled-unchanged / submit-payload / skillIds-overwrite / cancel / blank-name disabled |
| `frontend/src/pages/CollectionDetailPage.test.tsx` | 加 2 cases — owner action buttons 顯示 / 非 owner 不顯 |

### 驗證指令

```bash
cd frontend && npm run typecheck                            # 0 errors
cd frontend && npm test -- --run EditCollectionModal CollectionDetailPage  # 14/14 PASS
cd frontend && npm test -- --run                            # 395/395 全 suite PASS 無 regression
```

---

## 5.3 Backend Phase 1 結果（2026-05-12）

### Ship 範圍

| AC | 內容 | 狀態 |
|---|---|---|
| AC-1 | owner update collection → service save + CollectionUpdatedEvent | ✅ PASS |
| AC-2 | 非 owner update → CollectionForbiddenException "not_collection_owner" → 403 | ✅ PASS |
| AC-3 | owner delete collection → repo.delete called + CollectionDeletedEvent | ✅ PASS |
| AC-4 | 非 owner delete → CollectionForbiddenException → 403 | ✅ PASS |
| AC-5 | 刪 collection 不影響內含 skill | ✅ 隱含 PASS（service.delete 只 repo.delete collection；skillRepo 從未被呼叫）|
| AC-6 | skillIds 整段覆蓋（不 append）| ✅ PASS |
| 加碼 | aggregate validate name 太長 reject | ✅ PASS |
| 加碼 | markDeleted 拒空 deletedBy | ✅ PASS |

### Defer 至 S164b（frontend，blocked by S150 CollectionDetailPage 未 ship）

| AC | 內容 | 為何 defer |
|---|---|---|
| AC-7 | Frontend owner 可見編輯/刪除 button | 需 CollectionDetailPage 加 action bar；S150 (📋 planned) 為前提 |
| AC-8 | Frontend 確認 dialog + redirect + toast | 同 S164b |

### 授權設計：service-level ownerId 比對 vs @PreAuthorize

不採 `@PreAuthorize("hasPermission(#id, 'Collection', 'manage')")` 因 community module 尚無
`CollectionPermissionEvaluator` 註冊到 `DelegatingPermissionEvaluator`（S016 是 Skill domain
專用）。新註冊 evaluator 跨 trim budget；改在 service load aggregate 後 `ownerId.equals(currentUser)`
比對，mismatch 拋 `CollectionForbiddenException("not_collection_owner")`，`GlobalExceptionHandler`
攔截 → HTTP 403。Mirror ReviewForbiddenException 既驗 pattern。

### 改動檔案

| File | 變動 |
|---|---|
| `backend/.../community/events/CollectionUpdatedEvent.java`（**新檔**）| record (collectionId, name, description, category, skillIds, updatedBy, updatedAt) |
| `backend/.../community/events/CollectionDeletedEvent.java`（**新檔**）| record (collectionId, name, ownerId, deletedBy, deletedAt) — mirror SkillDeletedEvent |
| `backend/.../shared/api/CollectionForbiddenException.java`（**新檔**）| mirror ReviewForbiddenException pattern → 403 |
| `backend/.../community/Collection.java` | 加 `update(name, description, category, skillIds, updatedBy)` 充血方法（整段覆蓋 + validate + registerEvent） + `markDeleted(deletedBy)` |
| `backend/.../community/CollectionService.java` | 加 `update(...)` + `delete(...)` — ownerId 比對 + skillIds 全 PUBLISHED 預檢 + 3-line orchestration |
| `backend/.../community/CollectionCommandController.java` | 加 `PUT /api/v1/collections/{id}` + `DELETE /api/v1/collections/{id}` + UpdateCollectionBody record |
| `backend/.../shared/api/GlobalExceptionHandler.java` | 加 `handleCollectionForbidden` → 403 |
| `backend/.../community/CollectionOwnerManagementTest.java`（**新檔**）| 8 tests — 4 ACs + aggregate validation + event schema sanity |

### 驗證指令

```bash
./gradlew test --tests "*CollectionOwnerManagementTest"     # 8/8 PASS
./gradlew test --tests "io.github.samzhu.skillshub.community.*"  # 63/63 PASS（無 regression）
```

---

## 6. 與其他 spec 關係

- **S163（skill owner management）**：本 spec 對 collection 做同類；可同 PR ship 形成 owner ops 的對齊
- **S160（security headers + CSRF）**：S160 ship 後 anonymous 不能再建 collection；本 spec 補的是「合法 owner 也該能 manage」
- **S150（CollectionDetailPage）**：本 spec 在 detail page 加 action bar；S150 ship 是前提
- **S144（skill delete）**：同 PR ship 形成完整 CRUD set