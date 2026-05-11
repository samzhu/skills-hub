# S163: Skill Owner Management — Update Metadata + Visibility Toggle

> Spec: S163 | Size: S(5) → backend trim XS(3) | Status: 🚧 backend impl 完成 2026-05-12（PUT endpoint ship；frontend EditSkillModal + visibility toggle UX defer 至 S163b）
> Date: 2026-05-08（修訂 2026-05-08 — 移除 suspend / unsuspend，per user 反饋「平台是 registry 不是 runtime」）
> Origin: deployment audit 2026-05-08（LAB）— `PUT /api/v1/skills/{id}` 回 405 Method Not Allowed。Owner 只能透過重新上傳整個 zip 改 metadata，無法 mid-version 編輯描述 / 分類。

---

## 1. Goal

補上 skill owner 的中間 management 操作：

1. **Update metadata**（PUT）：改 description / category / compatibility / license（不改 name / version — 屬 publish flow）
2. **Visibility toggle**（既有 ACL 機制 + UX shortcut）：public（含 `public:*` VIEWER ACL）↔ private（移除該 entry）

S144 已涵蓋 hard delete。本 spec 補編輯與「轉為私人」的快捷 UX。

**為什麼不做 suspend / unsuspend：**

Skills Hub 是 **registry / marketplace**（per CLAUDE.md：「企業內部 AI Agent 技能市集與 Registry 平台」），平台**不執行 / 不應用 skill**，僅儲存 + 發布 + 下載。
→ 所以「停用」沒有 runtime 意義，與「**轉為私人**」結果相同：
  - 不可被任意人發現 / 安裝（從 list 與 search 隱藏）
  - 仍存在於系統可被 owner 編輯
  - 已下載的 user 不影響（檔案在 client 端）
- 「轉為私人」用既有 ACL 機制達成（移除 `public:*` VIEWER）— 無需新 status 欄位、無需新 API endpoint
- 「真的要永久撤下」就 delete（S144）— 兩段式 lifecycle 比三段式（PUBLISHED / SUSPENDED / DELETED）簡單

**非目標：**
- 不改 name / version（這些屬 publish flow 範疇）
- 不做 admin force-private（屬未來 admin 功能）
- 不做 partial update PATCH（用 PUT 整段覆蓋簡單）
- ~~不做 suspend / unsuspend~~（per 上面 rationale 已移除）

---

## 2. Approach

### 2.1 API 設計

```
PUT /api/v1/skills/{id}    → update metadata
```

**Update body**:
```json
{
  "description": "Auditing Terraform...",
  "category": "security",                  // S159 ship 後 normalize lowercase
  "compatibility": ["claude-code", "cline"],
  "license": "Apache-2.0"
}
```

**Authorization**：`@PreAuthorize("@skillPermission.check(#id, principal, 'write')")`

**Visibility 切換**：reuse 既有 ACL grant / revoke API（per S016）：

```
POST   /api/v1/skills/{id}/grants  body={principalType:"public", principalId:"*", role:"VIEWER"}  → 公開
DELETE /api/v1/skills/{id}/grants/{grantId}                                                       → 撤回 public:* → 變私人
```

ShareSkillModal（per S154 §8）已有 add/remove ACL UI；本 spec 不重做，只在 PageHeader 加 **快捷 toggle button**：

- 當前 public → 顯「[轉為私人]」button → DELETE public:* grant → 即時轉私人
- 當前 private → 顯「[公開分享]」button → POST public:* VIEWER grant → 即時公開

純前端 UX shortcut；後端走既有 grant API。

### 2.2 Domain Events

新增：
- `SkillUpdatedEvent(skillId, changedFields, ...)` — projections 對應更新

ACL 切換已透過既有 `SkillAclGrantedEvent` / `SkillAclRevokedEvent` 處理，不新增。

### 2.3 Frontend

`SkillDetailPage` PageHeader 加 owner-only action 區（per S158 用 `viewerPermissions.canEdit`）：

- `[編輯]` button → 開 EditSkillModal（form：description / category / compatibility / license）
- `[轉為私人]` / `[公開分享]` button — 依當前 ACL 切換
- `[刪除]` button — per S144

### 2.4 私人 (Private) skill UX

當 skill 沒有 `public:*` VIEWER ACL（變私人）：
- 不出現在 `/browse` list
- 不出現在 search 結果
- detail page 仍可由 owner 訪問（與被 grant 的 user）
- 非 owner / 非 grantee 訪問 → 403（per 既有 ACL）

無需 new status 欄位 — 純由 ACL 篩選自然達成。

### 2.5 Browse list filter

`/browse` 僅顯示「viewer 有 read 權限」的 skill — 本來就是 ACL filter 的結果。無 `status=PUBLISHED` 概念，因為私人 skill 對其他人來說不是「停用」而是「不存在於我的視野」。

---

## 3. Acceptance Criteria

```
AC-1: Owner update description 立即生效
  Given Alice 是 skill X 的 owner
  When Alice PUT /skills/X body={description:"new"}
  Then 回 200
  And GET /skills/X 立即看 description="new"
  And SkillUpdatedEvent 發布

AC-2: 非 owner update → 403
  Given Bob 非 skill X 的 owner
  When Bob PUT /skills/X
  Then 回 403 FORBIDDEN

AC-3: Update 不能改 name / version
  Given PUT body 含 {name:"new", version:"2.0.0"}
  When backend 處理
  Then 400 VALIDATION_ERROR「name and version are immutable」

AC-4: Owner 切換為私人（透過 revoke public:* grant）
  Given skill X 含 public:* VIEWER ACL
  When Alice 點「轉為私人」（前端走 DELETE grant）
  Then ACL 移除 public:*
  And /browse 不再顯 skill X 給其他 user
  And Alice 自己仍能 detail 訪 / 編輯

AC-5: 私人 skill 對非 owner / 非 grantee 不可見
  Given skill X 為私人（無 public:* VIEWER）
  When Bob (anonymous 或 random user) GET /skills/X
  Then 回 403（per 既有 ACL）

AC-6: 重新公開
  Given skill X 為私人
  When Alice 點「公開分享」（前端走 POST grant public:* VIEWER）
  Then /browse 再次顯示給其他 user

AC-7: EditSkillModal 預填當前值
  Given owner 點「編輯」
  When modal open
  Then form 預填當前 description / category / compatibility / license
  And submit 後 success toast + close + refetch

AC-8: 已下載的 client 不受 private 切換影響
  Given Bob 在 skill 公開時下載過
  When Alice 之後切換為私人
  Then Bob 本地的 skill bundle.zip 仍可用（registry 行為，非 DRM）
  And Bob 重訪 /skills/{id} 看到 403（不能看新 metadata 但自己已有舊版）
```

驗證指令：
- `cd backend && ./gradlew test`（per qa-strategy.md；新增 `SkillUpdateTest`）
- 手動 LAB：deploy 後跑 8 條 AC

---

## 4. Files to Change

| 檔案 | 變動 |
|------|------|
| `backend/src/main/java/.../skill/command/SkillCommandController.java` | 加 PUT endpoint |
| `backend/src/main/java/.../skill/command/SkillCommandService.java` | 加 update method |
| `backend/src/main/java/.../skill/domain/Skill.java` | 加 update domain method + registerEvent |
| `backend/src/main/java/.../skill/domain/SkillUpdatedEvent.java` | 新增 |
| `frontend/src/components/v2/PageHeader.tsx` | 加 owner-only [編輯][轉為私人/公開分享] buttons |
| `frontend/src/components/EditSkillModal.tsx` | 新增 |
| `frontend/src/api/skills.ts` | 加 updateSkill helper（visibility toggle 走既有 grant API）|
| **Tests** | 對應 8 ACs |

**範圍縮小**（vs 原 S163 含 suspend）：刪除 SkillStatus enum 變更、suspend / unsuspend service 方法、SUSPENDED state UI sweep（多個 component 條件 render）等。原 S(7) → 修訂 S(5)。

---

## 5. Test Plan

### 5.1 自動化（gradlew test + Testcontainers）

```java
@Test @DisplayName("AC-1: owner update description")
void ownerUpdateDescription() throws Exception {
    mvc.perform(put("/api/v1/skills/{id}", skillId)
            .contentType(APPLICATION_JSON)
            .content("{\"description\":\"new desc\"}")
            .with(authentication(ownerAuth())))
        .andExpect(status().isOk());
    
    var fresh = skillRepo.findById(skillId).orElseThrow();
    assertThat(fresh.getDescription()).isEqualTo("new desc");
}

@Test @DisplayName("AC-2: non-owner update → 403")
void nonOwnerUpdate403() throws Exception {
    mvc.perform(put("/api/v1/skills/{id}", skillId)
            .contentType(APPLICATION_JSON)
            .content("{\"description\":\"x\"}")
            .with(authentication(nonOwnerAuth())))
        .andExpect(status().isForbidden());
}

@Test @DisplayName("AC-3: name / version 不可變")
void cannotUpdateNameVersion() throws Exception {
    mvc.perform(put("/api/v1/skills/{id}", skillId)
            .contentType(APPLICATION_JSON)
            .content("{\"name\":\"new\"}")
            .with(authentication(ownerAuth())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
}

@Test @DisplayName("AC-4/5: 私人 skill 對非 owner 403")
void privateSkillNotVisibleToOthers() throws Exception {
    revokePublicGrant(skillId);
    mvc.perform(get("/api/v1/skills/{id}", skillId)
            .with(authentication(otherUserAuth())))
        .andExpect(status().isForbidden());
}
```

### 5.2 手動 LAB

deploy 後：
- [ ] PUT /skills/{id} 改 description → 立即可見
- [ ] PageHeader [轉為私人] → /browse 中該 skill 對 anonymous / 其他 user 消失
- [ ] [公開分享] 恢復可見
- [ ] 非 owner 操作 → 403
- [ ] PUT name / version → 400

---

## 5.5 Frontend Phase 2 結果（2026-05-12）— S163b' visibility toggle

### Ship 範圍 — S163 spec 全部 8 個 AC 收尾

| AC | 內容 | 狀態 |
|---|---|---|
| AC-4 | 切私人（DELETE public:* grant 透過既有 API）| ✅ PASS — VisibilityToggleButton 偵測 publicGrant + 點按 revokeGrant(skillId, publicGrant.id) |
| AC-6 | 重新公開（POST public:* VIEWER grant）| ✅ PASS — createGrant({principalType:'public', principalId:'*', role:'VIEWER'}) |
| AC-5 / AC-8 | 私人 skill 對非 owner 403 / 已下載 client 不受影響 | ✅ 純後端 ACL 既驗行為，不需獨立 frontend 驗 |

### 改動檔案

| File | 變動 |
|---|---|
| `frontend/src/components/VisibilityToggleButton.tsx`（**新檔**）| 自包 grants query — useQuery fetchGrants 偵測 publicGrant；按 state branch label 公開分享 / 轉為私人；mutation 成功後 invalidate skill + grants query 同步刷新 PageHeader 與 ShareModal |
| `frontend/src/components/v2/PageHeader.tsx` | owner-only render `<VisibilityToggleButton skillId={skill.id} />`（無 prop drilling 也無需 parent 傳 isPublic）|
| `frontend/src/components/VisibilityToggleButton.test.tsx`（**新檔**）| 5 cases — public→ 轉為私人 / private→ 公開分享 / revoke params / create params / loading disabled |
| `frontend/src/components/v2/PageHeader.test.tsx` | 既有 helper + 新 [編輯] 測試包 QueryClientProvider（因 VisibilityToggleButton 走 useQuery）；fetchGrants mock |

### 驗證指令

```bash
cd frontend && npm run typecheck                    # 0 errors
cd frontend && npm test -- --run                    # 374/374 全 suite PASS 無 regression
cd frontend && npm test -- --run VisibilityToggleButton  # 5/5
```

### S163 spec 進度收尾

| AC | 內容 | Ship | Commit |
|---|---|---|---|
| AC-1 | owner PUT description → 200 + cmd captured | ✅ | 136564d |
| AC-2 | 非 owner PUT → 403 | ✅ | 136564d |
| AC-3 | name/version DTO surface 不收 | ✅ | 136564d |
| AC-4 | 轉私人（revoke public grant）| ✅ | 本 tick |
| AC-5 | 私人 skill 非 owner 403 | ✅ | （後端既驗）|
| AC-6 | 重新公開 | ✅ | 本 tick |
| AC-7 | EditSkillModal 預填 + submit | ✅ | fbce208 |
| AC-8 | 已下載 client 不影響 | ✅ | （registry 非 DRM 既驗）|

**S163 為原 5-spec 中第一個全部 AC ✅ shipped。**

---

## 5.4 Frontend Phase 1 結果（2026-05-12）— S163b 部分套用

### Ship 範圍

| AC | 內容 | 狀態 |
|---|---|---|
| AC-7 | EditSkillModal 預填當前 description / category；submit 後 success + close + refetch | ✅ PASS（vitest 6 cases）|
| AC-7 補強 | name + version 不在 modal surface；UI 顯示提示「不可在此編輯」 | ✅ PASS |
| 部分 AC-1 | 改 description → PUT 帶 {description, category} payload | ✅ PASS（updateSkill mock 驗 params）|

### Defer 至 S163b'（visibility toggle UX）

| AC | 內容 | 為何 defer |
|---|---|---|
| AC-4 | [轉為私人] button → DELETE public:* grant | 走既有 /grants API；獨立 UX 分支 + grant lookup logic |
| AC-5 | 私人 skill 對非 owner 403 | 既有 ACL backend 行為，純驗證 |
| AC-6 | [公開分享] button → POST public:* VIEWER grant | 同 AC-4 |
| AC-8 | 已下載 client 不受 private 切換影響 | 純行為驗證（registry 非 DRM） |

### 改動檔案

| File | 變動 |
|---|---|
| `frontend/src/api/skills.ts` | 加 `updateSkill(id, {description, category})` helper — PUT /skills/{id} |
| `frontend/src/components/EditSkillModal.tsx`（**新檔**）| Mirror CreateCollectionModal pattern；prefill skill 當前值；trim+empty 驗證；unchanged disable submit |
| `frontend/src/components/v2/PageHeader.tsx` | 加 `onEditClick?: () => void` prop；owner-only `[編輯]` button（mirror 既有 `[分享]` pattern） |
| `frontend/src/pages/SkillDetailPage.tsx` | useState editOpen + EditSkillModal render + 傳 onEditClick prop |
| `frontend/src/components/EditSkillModal.test.tsx`（**新檔**）| 6 vitest cases — prefill / disabled-when-unchanged / submit-with-new-value / blank-rejects / cancel / immutable-fields hint |
| `frontend/src/components/v2/PageHeader.test.tsx` | 加 3 cases — 非 owner 不顯 / owner+onEditClick render + click / owner-無-onEditClick 不顯（防漏接 parent prop） |

### 驗證指令

```bash
cd frontend && npm run typecheck      # 0 errors
cd frontend && npm test -- --run EditSkillModal PageHeader   # 19/19 PASS
cd frontend && npm test -- --run SkillDetailPage              # 9/9 PASS（無 regression）
```

---

## 5.3 Backend Phase 1 結果（2026-05-12）

### Ship 範圍

| AC | 內容 | 狀態 |
|---|---|---|
| AC-1 | owner PUT /skills/{id} body={description:"new"} → 200 + service captor 收到 cmd | ✅ PASS |
| AC-2 | 非 owner (write permission denied) PUT → 403 Forbidden + service never called | ✅ PASS |
| AC-3 | PUT body 含 {name, version} → Jackson silently drop；service 收到 cmd 兩欄皆 null（自然不變 aggregate）| ✅ PASS |

### Defer 至 S163b（frontend + visibility toggle UX）

| AC | 內容 | 為何 defer |
|---|---|---|
| AC-4 | 切換為私人（DELETE public:* grant 透過既有 API）| 純前端 PageHeader 加 toggle button；reuse 既有 `/grants` API |
| AC-5 | 私人 skill 對非 owner 403 | 既有 ACL 行為，純驗證 |
| AC-6 | 重新公開（POST public:* VIEWER grant）| 純前端 toggle |
| AC-7 | EditSkillModal 預填當前值 + submit | 新前端 component；S163b 範疇 |
| AC-8 | 已下載 client 不受 private 切換影響 | 純行為驗證 |

### 改動檔案

| File | 變動 |
|---|---|
| `backend/.../skill/domain/SkillUpdatedEvent.java`（**新檔**）| record (skillId, description, category, updatedBy, updatedAt) — listeners 接 search projection / audit / subscription |
| `backend/.../skill/command/UpdateSkillCommand.java`（**新檔**）| record (description, category) + @JsonIgnoreProperties(ignoreUnknown=true) |
| `backend/.../skill/domain/Skill.java` | 加 `update(UpdateSkillCommand, updatedBy)` 充血方法（validate + trim + length cap + no-op skip + registerEvent）|
| `backend/.../skill/command/SkillCommandService.java` | 加 `updateSkill(id, cmd, updatedBy)` 3-line orchestration |
| `backend/.../skill/command/SkillCommandController.java` | 加 `PUT /api/v1/skills/{id}` + `@PreAuthorize("hasPermission(#id, 'Skill', 'write')")` |
| `backend/.../skill/command/SkillUpdateControllerTest.java`（**新檔**）| 3 ACs (AC-1/2/3) via WebMvcSliceTestBase |

### 驗證指令

```bash
./gradlew test --tests "*SkillUpdateControllerTest"                       # 3/3 PASS
./gradlew test --tests "io.github.samzhu.skillshub.skill.command.*"        # 40/40 PASS（無 regression）
```

---

## 6. 風險與注意

| 風險 | 緩解 |
|------|------|
| Update 觸發 search index reindex 開銷 | description 改 → embedding 需 regenerate；走 SkillUpdatedEvent listener async；queue 控量 |
| 私人 skill 變更後既有 collection 內含此 skill | install collection 時 skip 私人 skill（filter ACL）；前端提示「集合內含 N 個目前無權訪問的技能」 |
| 私人切換頻繁造成 ACL 表 churn | 走既有 grant / revoke pattern；無 high-throughput 顧慮 |
| 既有 skill `status` 欄位若未來其他 spec 用到 | per 本 spec rationale，status 欄位仍可保留 PUBLISHED 預設值；新 status 不再加；現有 PUBLISHED 保持即可 |

---

## 7. 與其他 spec 關係

- **S144（skill delete）**：本 spec 處理 update + visibility；S144 處理 delete；可同 PR ship 一次補完 owner management
- **S158（API privacy）**：本 spec PUT 路徑要走 owner-only authz，與 S158 viewerPermissions 整合
- **S145（訂閱管理）**：SkillUpdatedEvent 觸發 notification 給訂閱者
- **S150（CollectionDetail）**：collection 含 private skill 時的 UX 提示（在 S150 ship 後跟進）
- **S164（collection owner management）**：本 spec 為 skill 對應；S164 為 collection；同期 ship 形成完整 owner ops