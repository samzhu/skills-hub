# S158: API Response Privacy Hardening — 過度曝露的 internal authorization 資料

> Spec: S158 | Size: S(5) | Status: 🚧 in-progress（list endpoint 完工 2026-05-08；detail endpoint owner-conditional 拆 S158b 跟進）
> Date: 2026-05-08
> Origin: deployment audit 2026-05-08（LAB）— `GET /api/v1/skills` response 每一個 skill 都帶 `aclEntries: ["user:<sub>:read","user:<sub>:write","user:<sub>:delete","public:*:read"]` 給未登入 / 任意登入 user。對外 API 洩漏 RBAC 結構、誰能編輯 / 刪除、平台 internal authorization model。

---

## 1. Goal

把對外 API 的 response 縮減到「對該 viewer 必要的資料」：

1. **`aclEntries`**：list endpoint **完全移除**；detail endpoint 僅 owner / admin 看見
2. **`ownerId`**：與 `author` 同值（per S154 ship 後 owner 是 sub，author 是 display handle），對 viewer 無意義 — 一律移除
3. **掃 sweep** 其他可能曝露的內部欄位

**為什麼重要：**
- 即使 LAB「lab 可以全開」是針對 actuator，**業務 API 仍應守 least-privilege response 原則**：給 viewer 看他需要看的，不多
- ACL entry 結構（`role:principal:permission` triple）一旦穩定下來、對外暴露 → 改 ACL design 變成 breaking change
- 攻擊者可從 list response 推測 RBAC model（principal 命名、權限種類）規劃 attack

**非目標：**
- 不改 ACL 邏輯本身（內部仍用 `acl_entries` 表 + `DelegatingPermissionEvaluator`）
- 不改授權判斷流程（只動 read-side projection）

---

## 2. Approach

### 2.1 Sample Response（現況）

`GET /api/v1/skills?size=1` 第一筆 content：

```json
{
  "aclEntries": [
    "user:111161306011023995106:read",
    "user:111161306011023995106:write",
    "user:111161306011023995106:delete",
    "public:*:read"
  ],
  "author": "111161306011023995106",
  "averageRating": 0.0,
  "category": "Security",
  "compatibility": [],
  "createdAt": "2026-05-08T03:47:04.114586Z",
  "description": "...",
  "downloadCount": 1,
  "id": "8a907059-...",
  "latestVersion": "1.0.0",
  "license": "Apache-2.0",
  "name": "auditing-terraform-...",
  "openFlagCount": 0,
  "ownerId": "111161306011023995106",
  "reviewCount": 0,
  "riskLevel": "NONE",
  "status": "PUBLISHED",
  "updatedAt": "...",
  "verified": false,
  "versionCount": 1
}
```

### 2.2 應暴露 vs 應隱藏分類

| 欄位 | 對 viewer 有意義？| 動作 |
|------|------------------|------|
| `id`, `name`, `description`, `category`, `riskLevel`, `status`, `latestVersion`, `verified`, `license`, `compatibility`, `createdAt`, `updatedAt` | ✅ 公開資訊 | 保留 |
| `author`, `averageRating`, `reviewCount`, `downloadCount`, `versionCount`, `openFlagCount` | ✅ public stats | 保留 |
| **`aclEntries`** | ❌ internal authorization detail | **移除（list）；detail owner-only** |
| **`ownerId`** | ❌ 與 author 同值 | **移除** |

`aclEntries` detail 端的 owner-only return 邏輯：
- 走 `DelegatingPermissionEvaluator` 檢查 `auth has 'manage' permission on skillId`
- yes → return aclEntries（owner / admin 自管授權需要看）
- no → 移除欄位（甚至 return 不含 key，讓 frontend 知道沒權限看）

### 2.3 為什麼 list 端 aclEntries 不該存在？

1. **List query 的目的是 browse**：feedviewer 想看「有哪些 skill 可用」，不需要看「誰能 write 它」
2. **效能**：N 個 skill × M 個 ACL entry → list payload 隨 ACL 規模線性放大
3. **快取友好**：list response 完全不依 viewer identity → 可同 cache key 共享給匿名 / 任意登入 user；含 aclEntries 就要 per-viewer cache，效能砍半
4. **Authorization-by-knowledge 反模式**：把 ACL 結構 cleartext 給 attacker，等於告訴他「你的攻擊面長這樣」

### 2.4 Frontend 影響

`frontend/src/types/skill.ts` 與 `Skill` interface 預期含 `aclEntries: string[]`、`ownerId: string`。Sweep usage：

```bash
grep -rn 'aclEntries\|ownerId' frontend/src/
```

預期使用：
- `SkillDetailPage` 判斷 `isOwner = skill.ownerId === me.sub` → 改用 detail endpoint 的 owner-scope `permissions: { canEdit, canDelete }` boolean 或新欄位 `viewerIsOwner: boolean`
- 沒其他直接 consume aclEntries 的地方（per S016 ACL 引入時 frontend 設計就是 per-viewer permissions）

設計：detail endpoint 改回：

```jsonc
{
  // 公開欄位...
  "viewerPermissions": {            // ← 新欄位（owner / admin 才有完整；其他 user 看 viewerIsOwner=false 與權限 false）
    "isOwner": true,
    "canEdit": true,
    "canDelete": true
  },
  "aclEntries": [...]               // ← owner / admin 才出現此 key
}
```

非 owner viewer：`viewerPermissions.isOwner=false, canEdit=false, canDelete=false`，不含 aclEntries key。

### 2.5 Backend 改動定位

```
SkillReadModel（projection）
  + viewerIsOwner / viewerCanEdit / viewerCanDelete 計算（per call）
  + aclEntries 條件包含

SkillQueryService.findById()
  → 注入 CurrentUserProvider 取 sub
  → 計算 viewerPermissions
  → 條件填 aclEntries

SkillQueryService.findAll()
  → list 永遠不 fill aclEntries（不管誰問）
  → ownerId 不 fill
```

DTO 設計：分 `SkillSummary`（list 用）vs `SkillDetail`（detail 用）— 既有可能已分；確認後加欄位。

---

## 3. Acceptance Criteria

```
AC-1: List response 不含 aclEntries / ownerId
  Given 任意 viewer 打 GET /api/v1/skills?size=10
  When response render
  Then content[].aclEntries 欄位完全不存在（不是 [] 也不是 null）
  And content[].ownerId 欄位不存在
  Note: 用 strict equality `'aclEntries' in content[0]` === false 驗證

AC-2: Detail 非 owner 不含 aclEntries
  Given Bob 不是 skill X 的 owner
  When Bob 打 GET /api/v1/skills/{X}
  Then response 不含 aclEntries key
  And response 含 viewerPermissions: {isOwner:false, canEdit:false, canDelete:false}

AC-3: Detail owner 看得到 aclEntries
  Given Alice 是 skill Y 的 owner
  When Alice 打 GET /api/v1/skills/{Y}（含 Authorization header）
  Then response 含 aclEntries 完整 list
  And response.viewerPermissions = {isOwner:true, canEdit:true, canDelete:true}

AC-4: Frontend isOwner 判斷不破
  Given SkillDetailPage 渲染
  When skill.viewerPermissions.isOwner = true
  Then 顯示 owner-only 操作（如「編輯」、「刪除」、「停用」）
  And 渲染與既有「ownerId === me.sub」邏輯一致

AC-5: Anonymous viewer 也走縮減 schema
  Given 未登入 user 打 GET /api/v1/skills（含 public skills）
  When response render
  Then 同 AC-1 — 不含 aclEntries / ownerId

AC-6: 檢測 regression — schema test
  Given test：對 list 與 detail 各取 1 sample
  When 序列化為 JSON
  Then list payload 不含 'aclEntries' / 'ownerId' string；
       detail payload 對 non-owner 不含 'aclEntries' string
```

驗證指令：
- `cd backend && ./gradlew test`（per qa-strategy.md；新增 `SkillResponseSchemaTest` 走 MockMvc）
- 手動：deploy 後 `curl /api/v1/skills` grep 'aclEntries|ownerId' → 應為空

---

## 4. Files to Change

| 檔案 | 變動 |
|------|------|
| `backend/src/main/java/.../skill/query/SkillReadModel.java`（or DTO 處） | 拆 `SkillSummary`（list 用）vs `SkillDetail`（detail 用）；list 移除 aclEntries / ownerId；detail 加 viewerPermissions + aclEntries 條件填 |
| `backend/src/main/java/.../skill/query/SkillQueryController.java` | list 端 return type 改 `Page<SkillSummary>`；detail 端 return `SkillDetail`；detail 注入 CurrentUserProvider |
| `backend/src/main/java/.../skill/query/SkillQueryService.java` | findById 加 viewer permission 計算；findAll 純資料 |
| `frontend/src/types/skill.ts` | 移除 `aclEntries`、`ownerId`；加 `viewerPermissions?: { isOwner: boolean; canEdit: boolean; canDelete: boolean }` |
| `frontend/src/pages/SkillDetailPage.tsx` | `isOwner = skill.viewerPermissions?.isOwner` 取代 `ownerId === me.sub` |
| `frontend/src/components/v2/PageHeader.tsx` | 同步 |
| `frontend/src/api/skills.ts` | 拆 `Skill`（detail）vs `SkillSummary`（list）型別；fetchSkills return SkillSummary[]，fetchSkillById return Skill |
| **Tests** | `SkillResponseSchemaTest`（backend）+ `SkillDetailPage.test.tsx`（frontend） |

---

## 5. Test Plan

### 5.1 自動化

```java
// SkillResponseSchemaTest.java
@WebMvcTest
class SkillResponseSchemaTest {

    @Test @DisplayName("AC-1: list response 無 aclEntries / ownerId")
    void listResponseFiltersInternalFields() throws Exception {
        var json = mvc.perform(get("/api/v1/skills?size=1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("aclEntries");
        assertThat(json).doesNotContain("ownerId");
    }

    @Test @DisplayName("AC-2: detail non-owner 無 aclEntries")
    void detailNonOwnerNoAclEntries() throws Exception {
        // mock CurrentUserProvider 為非 owner
        var json = mvc.perform(get("/api/v1/skills/{id}", existingSkillId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("aclEntries");
        assertThat(json).contains("\"isOwner\":false");
    }

    @Test @DisplayName("AC-3: detail owner 有 aclEntries")
    void detailOwnerHasAclEntries() throws Exception {
        // mock CurrentUserProvider 為 owner
        var json = mvc.perform(get("/api/v1/skills/{id}", ownedSkillId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json).contains("aclEntries");
        assertThat(json).contains("\"isOwner\":true");
    }
}
```

### 5.2 手動 LAB

```bash
# AC-1
curl -s 'https://.../api/v1/skills?size=2' | jq '.content[0] | keys' | grep -E 'aclEntries|ownerId'
# 預期：grep 無 match（exit code 1）

# AC-2 (非 owner viewer)
curl -s 'https://.../api/v1/skills/<id-not-yours>' | jq '.aclEntries // "missing"'
# 預期：missing

# AC-3 (owner viewer，需登入)
curl -s -H "Authorization: Bearer <jwt>" 'https://.../api/v1/skills/<your-id>' | jq '.aclEntries | length'
# 預期：≥ 1
```

---

## 6. Verification（partial — list endpoint 完工）

| 項目 | 結果 |
|------|------|
| `./gradlew test --tests "...SkillJsonViewTest" -x processTestAot` | ✅ 3/3 PASS（list view 排除 aclEntries/ownerId / detail view 包含 / no-view 包含） |
| Skill domain 加 `Views.List` + `Views.Detail` interface | ✅ aclEntries + ownerId 標 `@JsonView(Detail)` |
| `SkillQueryController.search()` `@JsonView(List)` | ✅ list endpoint 序列化走 List view |
| Detail endpoint `findById` / `findByAuthorAndName` | ⏳ 不動 — 走 default view（含 aclEntries/ownerId）；owner-conditional 邏輯留 S158b |
| Frontend 影響 | ✅ 零 — production 程式無 list-page 消費 ownerId/aclEntries；TS type 已 optional |

---

## 7. Result（list endpoint partial）

**Shipped 2026-05-08** — backend-only，3 file changes，3/3 unit test PASS。

### 7.1 程式變動

- `backend/.../skill/domain/Skill.java`
  - 新增 `public static final class Views { interface List {} interface Detail extends List {} }`
  - `aclEntries` 欄位加 `@JsonView(Views.Detail.class)`
  - `ownerId` 欄位加 `@JsonView(Views.Detail.class)`
- `backend/.../skill/query/SkillQueryController.java`
  - `search()` 方法加 `@JsonView(Skill.Views.List.class)` — 觸發 list view 序列化
- `backend/src/test/.../SkillJsonViewTest.java`（新增）
  - 3 個 case：list 排除 / detail 包含 / no-view 預設行為

### 7.2 拆出 follow-up（→ S158b）

Detail endpoint 條件 owner-only 邏輯（spec §2.4 提到的 `viewerPermissions: { isOwner, canEdit, canDelete }` + 條件 aclEntries 露出）需要：
- 注入 `CurrentUserProvider` 至 `SkillQueryService.findById`
- 計算 viewer permissions（per-call）
- 條件 fill 或 strip aclEntries（per viewer identity）
- DTO 拆 `SkillSummary` vs `SkillDetail` 或用 dynamic Jackson filter

surface 偏 M（多 layer 變動 + frontend `viewerPermissions` 型別 + SkillDetailPage `isOwner` 改邏輯）— 拆 S158b backlog row。

### 7.3 `/skills/{id}/grants` endpoint（spec §8）

仍未 fix — 暴露完整 grant list（含 grantedBy / principalId / role / grantId）給任何 viewer。同樣留 S158b 跟進（authz check + frontend `canManageGrants` flag）。

---

## 8. 設計筆記

| 風險 | 緩解 |
|------|------|
| 遺漏其他 internal 欄位（如 audit_log）| 本 spec 範圍 sweep 一次 SkillReadModel；其他 entity（Collection、Request、Flag、Review）的 read model 留 follow-up audit |
| 既有 frontend 依 ownerId 邏輯沒抓乾淨 → bug | grep 全 frontend 並 sweep；test 覆蓋每個 ownership-conditional render path |
| Cache invalidation 改變（detail per-viewer）| list 仍可 share cache（無 viewer-specific 欄位）；detail 改 per-viewer cache（既有可能已是這 pattern） |
| API consumer（CLI / external）破壞性 change | 本平台 CLI / SDK 尚未 publish；若已有第三方依賴此欄位，加 deprecation 期；目前 MVP 階段直接改可接受 |

---

## 7. 後續 follow-up

- **S159（potential）**: 同類 audit 套用至 Collection / Request / Flag / Review read model，確認沒遺漏 aclEntries / ownerId 暴露
- **S160（potential）**: 加上 OpenAPI schema annotation 與 contract test，避免未來 reintroduce internal field

## 8. /skills/{id}/grants endpoint — 同類議題並入

LAB audit 發現第 2 個 ACL 暴露點：`GET /api/v1/skills/{id}/grants` 回 完整 grant list：
```json
[{
  "grantedAt": "2026-05-08T03:47:04.220488Z",
  "grantedBy": "111161306011023995106",
  "id": "a94997a5-cd20-4e71-88e2-65711b25a3b2",
  "principalId": "111161306011023995106",
  "principalType": "user",
  "role": "OWNER",
  "skillId": "..."
}, ...]
```

含 grant ID（內部 PK）、grantedBy（誰授權）、principalId / role 等完整 RBAC metadata。

**修法（並入本 spec）：**

- `/grants` endpoint enforce owner / admin only access；非 owner 走 403 FORBIDDEN
- 補 `viewerPermissions.canManageGrants` boolean 給前端判斷是否顯示「管理分享」入口
- 既有 `/skills/{id}/grants` 為 ShareSkillModal 用；前端只在 owner viewer 顯示此 modal，本 spec 後 backend 也 enforce

加 §3 補 AC：

```
AC-7: 非 owner 不能 GET grants
  Given Bob 非 skill X 的 owner
  When Bob GET /api/v1/skills/{X}/grants
  Then 回 403 FORBIDDEN
  And response = ErrorResponse 平台格式 (per S162)

AC-8: Owner GET grants 仍正常
  Given Alice 是 skill Y 的 owner
  When Alice GET /api/v1/skills/{Y}/grants
  Then 回 200 + 完整 grant list
```
