# S172-T05: Create collection my-skills picker

## 對應規格
S172：Production UI Responsive Polish

## 這個 task 要做什麼
把 `/collections` 的「建立集合」modal 從 UUID textarea 改成「從我的已發布技能下拉選取 → 按新增 → 已選清單可移除」。完成後，使用者不需要知道 skill UUID，仍會把 selected skill 的 `id` 陣列送給既有 `createCollection` API。

## 使用者情境（BDD）
Given（前提）登入使用者有兩個已發布技能 A、B  
When（動作）使用者開啟「建立集合」，在下拉選 A 後按「新增」  
Then（結果）A 出現在「已選技能」清單，A 從 dropdown 消失或被 disabled  
And（而且）使用者按 A 的移除 icon 後，A 從已選清單移除並可再次選取  
And（而且）按「建立集合」時 `createCollection` 收到 `skillIds: [A.id, B.id]`，畫面沒有可編輯 UUID textarea。

## 研究來源
- `docs/grimo/specs/2026-05-14-S172-production-ui-responsive-polish.md` AC-S172-8 到 AC-S172-13、§4.4 sketch。
- `frontend/src/components/CreateCollectionModal.tsx`：目前 `技能 ID 清單` textarea + `skillIdsText.split(/\s+/)`。
- `frontend/src/pages/MySkillsPage.tsx`：已用 `useMe` + `useSkillList({ author: me?.userId, size: 200 })` 抓我的技能。
- `frontend/src/api/skills.ts`：`createCollection` API 仍接受 `skillIds: string[]`。
- `docs/grimo/ui/DESIGN.md` modal/input/button dark tokens。

## 先做 POC
- POC：not required — 這個 task 重用既有 hook/API，沒有新 package；backend contract 不變。

## 正式程式怎麼做
- File 名稱：`frontend/src/components/CreateCollectionModal.tsx`。
- 入口：`CreateCollectionModal`。
- 必要行為：
  - 刪除 `skillIdsText` 與 UUID textarea。
  - 用 `useMe` 取得 `me.userId`，再用 `useSkillList({ author: me?.userId, size: 200 })` 取得我的技能。
  - dropdown 只列可加入集合的已發布技能；option label 至少包含 name、category、latestVersion（沒有值時用 `—`）。
  - 本地 state 保存 `selectedSkills: Skill[]` 或最小 view model；「新增」把目前選中的 skill append。
  - already-selected skill 不可重複新增。
  - 已選列表每列顯示 name/category/version，移除 button 使用 lucide `Trash2` 或 `X`，並有 `aria-label="移除 {skill.name}"`。
  - `canSubmit` 必須檢查 name、category、`selectedSkills.length > 0`、mutation not pending。
  - `mutationFn` 呼叫 `createCollection({ skillIds: selectedSkills.map((skill) => skill.id) })`。
  - 0 個已發布技能時顯示「集合只能加入已發布技能」空狀態，送出 disabled，提供 `/publish` link/button。
  - Dialog header 加 title、說明、close icon/button；modal 高度超出 viewport 時用內部垂直捲動。

## 單元測試 / 整合測試
- `CreateCollectionModal.test.tsx`（若不存在就新增）
  - `@DisplayName("AC-S172-8: create collection dialog has title description and close button")`
  - `@DisplayName("AC-S172-9: user can add a published my-skill from dropdown")`
  - `@DisplayName("AC-S172-10: no published skills disables submit and links to publish")`
  - `@DisplayName("AC-S172-11: removing selected skill returns it to dropdown")`
  - `@DisplayName("AC-S172-12: submit sends selected skill ids without UUID textarea")`
  - `@DisplayName("AC-S172-13: dialog surface uses viewport-bounded scroll classes")`

## 會改哪些檔案
- `frontend/src/components/CreateCollectionModal.tsx`
- `frontend/src/components/CreateCollectionModal.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- CreateCollectionModal.test.tsx`

## 前置條件
- S172-T03 PASS（EmptyState/link/button behavior 可重用在 no-skill state，但不是硬性程式依賴）。

## 狀態
pending（待做）
