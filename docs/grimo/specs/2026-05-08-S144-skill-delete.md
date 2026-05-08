# S144: Skill 刪除功能

> Spec: S144 | Size: S(7) | Status: 📋 planned
> Date: 2026-05-08
> Origin: site audit 2026-05-08 — OWNER role 已有 "delete" 權限宣告，但無 DELETE API endpoint 也無前端入口

---

## 1. Goal

讓 skill OWNER 能從 UI 刪除自己發布的 skill（包含所有版本與檔案）。

**非目標：**
- 不做 soft-delete（直接硬刪）
- 不做版本選擇性刪除（整個 skill 含所有版本一起刪）
- 不做 Admin 批量刪除

---

## 2. Approach

### 2.1 Backend

新增 `DELETE /api/v1/skills/{id}` endpoint：

- 權限：`@PreAuthorize("@skillPermission.check(#id, principal, 'delete')")`（OWNER 才能刪）
- 刪除順序（同一 transaction）：
  1. `domain_events` 相關記錄
  2. `download_events` 相關記錄
  3. `vector_store` embeddings（by skill_id）
  4. `skill_versions` 所有版本
  5. `skill_acl` 所有 ACL
  6. `flags` 相關
  7. `skills` 主記錄
- GCS zip 檔案：async 刪除（事件驅動，非同步，不阻塞 API response）
- 回傳：`204 No Content`

發布 `SkillDeletedEvent` 讓各 projection listener 清除衍生資料。

### 2.2 Frontend

**MySkillsPage**：每個 skill card 右上角加「⋮」選單，選項：
- 編輯（已有 → 跳轉 publish 更新版本）
- **刪除**（新增）→ 跳出確認對話框

**確認對話框內容：**
```
確定要刪除「{name}」嗎？
此操作無法復原，所有版本與下載記錄都將一併刪除。

[取消]  [確定刪除]
```

刪除成功後從列表移除，顯示 toast「技能已刪除」。

---

## 3. Acceptance Criteria

```
Scenario: OWNER 刪除自己的 skill
  Given 作者在「我的技能」頁面，有一個已發布的 skill
  When 點選 skill 的「⋮」選單 → 點「刪除」→ 確認對話框點「確定刪除」
  Then API DELETE /api/v1/skills/{id} 回 204
  And 該 skill 從「我的技能」列表消失
  And 顯示 toast「技能已刪除」
  And 從 /browse 列表也消失

Scenario: 非 OWNER 無法刪除
  Given 使用者不是該 skill 的 OWNER
  When 呼叫 DELETE /api/v1/skills/{id}
  Then API 回 403 FORBIDDEN

Scenario: 刪除不存在的 skill
  Given skill 已不存在
  When 呼叫 DELETE /api/v1/skills/{id}
  Then API 回 404 NOT_FOUND
```

---

## 4. Files to Change

| 層 | 檔案 | 變動 |
|----|------|------|
| Backend | `SkillCommandController.java` | 加 `@DeleteMapping("/{id}")` |
| Backend | `SkillCommandService.java` | 加 `deleteSkill(id, principal)` |
| Backend | `Skill.java` (domain) | 加 `SkillDeletedEvent` publish |
| Backend | `SkillDeletedEvent.java` | 新增 domain event |
| Backend | Projection listeners | 訂閱 `SkillDeletedEvent` 清除衍生資料 |
| Frontend | `MySkillsPage.tsx` | 加刪除選單 + 確認對話框 |
| Frontend | `skillApi.ts` | 加 `deleteSkill(id)` |

---

## 5. Test Plan

- [ ] `DELETE /api/v1/skills/{id}` OWNER → 204
- [ ] `DELETE /api/v1/skills/{id}` 非 OWNER → 403
- [ ] `DELETE /api/v1/skills/{id}` 不存在 → 404
- [ ] Frontend：確認對話框顯示正確 skill 名稱
- [ ] Frontend：刪除後列表移除 + toast 顯示
