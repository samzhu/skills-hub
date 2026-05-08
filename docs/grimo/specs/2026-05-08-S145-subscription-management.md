# S145: 訂閱管理頁面

> Spec: S145 | Size: S(6) | Status: 📋 planned
> Date: 2026-05-08
> Origin: site audit 2026-05-08 — skill detail 有「訂閱」按鈕可訂閱/退訂，但無訂閱管理入口；使用者無法一覽自己訂閱了哪些 skill；`/subscriptions` 返回 Spring 404

---

## 1. Goal

提供「我的訂閱」管理頁，讓使用者集中查看、管理已訂閱的 skills。

**非目標：**
- 不改訂閱/退訂的技術實作（S125a/b 已完成後端 + toggle API）
- 不做訂閱分類或標籤

---

## 2. Approach

### 2.1 Backend

`GET /api/v1/subscriptions/me` — 回傳當前登入使用者所有訂閱的 skill 摘要列表。

```json
[
  {
    "skillId": "...",
    "skillName": "deep-research",
    "author": "samzhu",
    "latestVersion": "1.0.0",
    "riskLevel": "LOW",
    "subscribedAt": "2026-05-08T..."
  }
]
```

後端已有 `skill_subscriptions` 表（S125a）、`SkillSubscriptionController`（S125b）— 補一個 `GET /me` 查詢即可。

### 2.2 Frontend

**入口：**
1. Nav「我的技能」頁面新增「訂閱」tab（`我的技能 | 訂閱`）
2. 或獨立 `/subscriptions` 頁面（路由已保留但 404）

建議選方案 1（tab），與「我的技能」整合，減少 nav 項目。

**訂閱列表 UI：**
- 每張 card 顯示：skill 名稱、作者、最新版本、風險等級、訂閱日期
- 每張 card 右側「退訂」按鈕（呼叫既有 DELETE /api/v1/subscriptions/{skillId}）
- 空狀態：「尚未訂閱任何技能。前往瀏覽找到有興趣的技能後點「訂閱」。」

---

## 3. Acceptance Criteria

```
Scenario: 查看我的訂閱列表
  Given 使用者已訂閱 2 個 skills
  When 進入「我的技能」→ 點選「訂閱」tab
  Then 顯示 2 張訂閱 skill card
  And 每張顯示 skill 名稱、版本、風險等級、訂閱時間

Scenario: 從管理頁退訂
  Given 使用者在訂閱列表看到「deep-research」
  When 點擊該 card 的「退訂」按鈕
  Then 呼叫 DELETE /api/v1/subscriptions/{skillId} → 204
  And 該 card 從列表移除
  And 顯示 toast「已取消訂閱」

Scenario: 空訂閱狀態
  Given 使用者尚未訂閱任何 skill
  When 進入訂閱 tab
  Then 顯示 empty state「尚未訂閱任何技能」+ 「前往瀏覽」按鈕
```

---

## 4. Files to Change

| 層 | 檔案 | 變動 |
|----|------|------|
| Backend | `SkillSubscriptionController.java` | 加 `GET /api/v1/subscriptions/me` |
| Backend | `SkillSubscriptionService.java` | 加查詢當前使用者訂閱列表 |
| Frontend | `MySkillsPage.tsx` | 加「訂閱」tab + 訂閱列表 UI |
| Frontend | `subscriptionApi.ts` | 加 `getMySubscriptions()` |

---

## 5. Test Plan

- [ ] `GET /api/v1/subscriptions/me` 回傳當前使用者訂閱列表
- [ ] `GET /api/v1/subscriptions/me` 未登入 → 401
- [ ] Frontend：訂閱 tab 顯示正確 skill 列表
- [ ] Frontend：退訂後列表更新 + toast
- [ ] Frontend：空狀態顯示正確
