# S163: Skill Owner Management — Update / Suspend / Unsuspend

> Spec: S163 | Size: S(7) | Status: 📐 in-design
> Date: 2026-05-08
> Origin: deployment audit 2026-05-08（LAB）— `PUT /api/v1/skills/{id}` 回 405 Method Not Allowed；no suspend / unsuspend endpoint。Owner 只能透過重新上傳整個 zip 改 metadata，無法 mid-version 編輯描述、分類、停用 skill。

---

## 1. Goal

補完 skill owner 的基本 management 操作：

1. **Update**: 改 description / category / compatibility / license（但不改 name / version — 這些屬於 publish 範疇）
2. **Suspend**: owner 主動標 skill 為 `SUSPENDED`（list 與 detail 仍可訪但有醒目標示「已停用」）
3. **Unsuspend**: owner 把 `SUSPENDED` 改回 `PUBLISHED`

S144 已涵蓋 delete；本 spec 補編輯 / 停用 / 恢復。

**為什麼重要：**
- Skill owner 發現 description typo 不能改 → 必須走完整 republish 流程，違背「small diff small effort」直覺
- 發現 skill 有 bug 但無時間修 → 應能先 suspend 不再讓 user 安裝；不該被迫等修好才停下載量

**非目標：**
- 不改 name / version（這些是 publish flow 範疇）
- 不做 admin force-suspend（屬未來 admin 功能）
- 不做 partial update PATCH（用 PUT 整段覆蓋簡單）

---

## 2. Approach

### 2.1 API 設計

```
PUT  /api/v1/skills/{id}          → update metadata
POST /api/v1/skills/{id}/suspend  → status PUBLISHED → SUSPENDED
POST /api/v1/skills/{id}/unsuspend → status SUSPENDED → PUBLISHED
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

`SUSPENDED` 狀態語意（per S033 lifecycle audit）：
- list 仍可見（不 hide）
- detail 仍可訪（顯醒目「已停用」標籤 + 說明）
- 不能 install（API reject）
- semantic search index 保留（已建好，避免 reindex 成本）— 但搜尋結果加「已停用」flag 提示

### 2.2 Domain Events

新增 events：
- `SkillUpdatedEvent(skillId, changedFields, ...)` — projections 對應更新
- `SkillSuspendedEvent(skillId, reason?, ...)` — 通知訂閱者（per S145）
- `SkillUnsuspendedEvent(skillId, ...)` — 通知

### 2.3 Frontend

`SkillDetailPage` PageHeader 加 owner-only action 區（per S158 用 viewerPermissions.canEdit）：
- `[編輯]` button → 開 EditSkillModal（form：description / category / compatibility / license）
- `[停用]` / `[恢復]` button — 依 status 切換

Modal 直接覆蓋整段 metadata；前端 form 預填當前值。

### 2.4 SUSPENDED 狀態 UI

| 位置 | 顯示 |
|------|------|
| SkillCard | 加「已停用」灰底 badge；下載按鈕 disabled |
| SkillDetail Hero | 顯醒目 banner「此技能已被作者停用，可能不再維護」+ 不顯下載 CTA |
| /browse list | 預設 hide SUSPENDED（add filter toggle「顯示已停用」） |
| Search 結果 | 顯但帶「已停用」flag |
| Install API | reject 401 + ErrorCode `SKILL_SUSPENDED` |

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

AC-3: Owner suspend skill
  Given skill X status=PUBLISHED, owner Alice
  When Alice POST /skills/X/suspend
  Then 回 200
  And GET /skills/X.status="SUSPENDED"
  And SkillSuspendedEvent 發布

AC-4: SUSPENDED skill install reject
  Given skill X status=SUSPENDED
  When 任意 user GET /skills/X/download
  Then 回 409 STATE_CONFLICT (or 410 GONE) + ErrorCode SKILL_SUSPENDED
  And 不增 downloadCount

AC-5: SUSPENDED skill 在 /browse 預設 hide
  Given /browse?status=PUBLISHED (預設)
  When 頁面 render
  Then SUSPENDED 的 skill 不出現

AC-6: SUSPENDED skill 可顯 detail page
  Given 直接訪問 /skills/{suspended-id}
  When 頁面 render
  Then 顯 detail content 但加醒目「已停用」banner
  And 不顯下載 CTA

AC-7: Unsuspend 恢復
  Given skill X status=SUSPENDED
  When owner POST /skills/X/unsuspend
  Then status="PUBLISHED" + SkillUnsuspendedEvent

AC-8: Update 不能改 name / version
  Given PUT body 含 {name:"new", version:"2.0.0"}
  When backend 處理
  Then 400 VALIDATION_ERROR「name and version are immutable」
  Note: 這兩欄位需走 publish flow

AC-9: EditSkillModal 預填當前值
  Given owner 點「編輯」
  When modal open
  Then form 預填當前 description / category / compatibility / license
  And submit 後 success toast + close + refetch
```

驗證指令：
- `cd backend && ./gradlew test`（per qa-strategy.md；新增 `SkillOwnerManagementTest`）
- 手動 LAB：deploy 後跑 9 條 AC

---

## 4. Files to Change

| 檔案 | 變動 |
|------|------|
| `backend/src/main/java/.../skill/command/SkillCommandController.java` | 加 PUT / suspend / unsuspend endpoints |
| `backend/src/main/java/.../skill/command/SkillCommandService.java` | 加 update / suspend / unsuspend 方法 |
| `backend/src/main/java/.../skill/domain/Skill.java` | 加 update / suspend / unsuspend domain methods + registerEvent |
| `backend/src/main/java/.../skill/domain/SkillUpdatedEvent.java`、`SkillSuspendedEvent.java`、`SkillUnsuspendedEvent.java` | 新增 |
| `backend/src/main/java/.../skill/query/SkillQueryService.java` | /browse 預設 filter status=PUBLISHED |
| `frontend/src/components/v2/PageHeader.tsx` | 加 owner-only [編輯][停用][恢復] buttons |
| `frontend/src/components/EditSkillModal.tsx` | 新增 |
| `frontend/src/components/SkillCard.tsx` | SUSPENDED 顯灰 badge + disabled CTA |
| `frontend/src/api/skills.ts` | 加 updateSkill / suspendSkill / unsuspendSkill helpers |
| **Tests** | 對應 9 ACs |

---

## 5. Test Plan

### 5.1 自動化（gradlew test + Testcontainers）

```java
@Test @DisplayName("AC-1: owner update description")
void ownerUpdateDescription() throws Exception {
    var resp = mvc.perform(put("/api/v1/skills/{id}", skillId)
            .contentType(APPLICATION_JSON)
            .content("{\"description\":\"new desc\"}")
            .with(authentication(ownerAuth())))
        .andExpect(status().isOk());
    
    var fresh = skillRepo.findById(skillId).orElseThrow();
    assertThat(fresh.getDescription()).isEqualTo("new desc");
}

@Test @DisplayName("AC-3: suspend changes status")
void suspendChangesStatus() throws Exception {
    mvc.perform(post("/api/v1/skills/{id}/suspend", skillId)
            .with(authentication(ownerAuth())))
        .andExpect(status().isOk());
    
    assertThat(skillRepo.findById(skillId).get().getStatus())
        .isEqualTo(SkillStatus.SUSPENDED);
}

@Test @DisplayName("AC-4: suspended skill 不能 download")
void suspendedSkillRejectDownload() throws Exception {
    suspendSkill(skillId);
    mvc.perform(get("/api/v1/skills/{id}/download", skillId))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("SKILL_SUSPENDED"));
}
```

### 5.2 手動 LAB

deploy 後：
- [ ] PUT /skills/{id} 改 description → 立即可見
- [ ] POST /suspend → /browse 不再見此 skill
- [ ] 訪問 detail 仍可，顯「已停用」banner
- [ ] download API → 409 + SKILL_SUSPENDED
- [ ] /unsuspend 恢復後，再次出現在 /browse
- [ ] 非 owner 操作 → 403

---

## 6. 風險與注意

| 風險 | 緩解 |
|------|------|
| SUSPENDED 後 download_events 行為 | 不增 count；API reject 在 controller 層；listener 不觸發 |
| Embedding 保留 vs 清理 | 保留（避免 unsuspend 後 reindex 成本）；search 結果加 status flag 顯示 |
| 既有 install collection 含 SUSPENDED skill | install API filter SUSPENDED 跳過；前端提示「集合內含 N 個已停用技能，已自動排除」 |
| Update name / version 觸發 Pandora's box | API 層 reject；確保只 publish flow 動 |
| 前端 SUSPENDED state 顯示需多 sweep | 影響：SkillCard / Hero / Search results / MySkills tab，逐一補 |

---

## 7. 與其他 spec 關係

- **S144（skill delete）**：本 spec 處理 update + suspend；S144 處理 delete；可同 PR ship 一次補完 owner management
- **S158（API privacy）**：本 spec PUT/suspend 路徑要走 owner-only authz，與 S158 viewerPermissions 整合
- **S145（訂閱管理）**：SkillSuspendedEvent 觸發 notification 給訂閱者
- **S150（CollectionDetail）**：collection 含 SUSPENDED skill 時的 UX 提示（在 S150 ship 後跟進）