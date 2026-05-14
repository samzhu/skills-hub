# S172-T04: My Skills dark segmented lifecycle tabs

## 對應規格
S172：Production UI Responsive Polish

## 這個 task 要做什麼
修正 `/my-skills` 的 lifecycle tabs 白底反白問題。完成後，`全部 / 已發布 / 草稿 / 已停用 / 訂閱` 會是一組深色 segmented control；inactive tab 不再使用 `bg-white`，count 比 label 低一階但仍可讀。

## 使用者情境（BDD）
Given（前提）使用者登入後開啟 `/my-skills`  
When（動作）頁面渲染 tabs：`全部 (0)`、`已發布 (0)`、`草稿 (0)`、`已停用 (0)`、`訂閱 (0)`  
Then（結果）inactive tabs 使用透明或深色 surface，class/computed background 不得是白色  
And（而且）active tab 使用低噪音 selected state，不使用刺眼白/黑反差塊  
And（而且）在 390px 寬度 tab control 會包行或在自身容器內捲動，不造成 body 水平捲動。

## 研究來源
- `docs/grimo/specs/2026-05-14-S172-production-ui-responsive-polish.md` AC-S172-14、AC-S172-15、§4.4 sketch。
- `docs/grimo/ui/DESIGN.md` `bg-2/bg-3/accent-soft/accent-text/line` tokens。
- `frontend/src/pages/MySkillsPage.tsx`：目前 inactive style 是 `bg-white text-foreground`。

## 先做 POC
- POC：not required — 這個 task 只改 local component markup/class，沒有新套件或 API。

## 正式程式怎麼做
- File 名稱：`frontend/src/pages/MySkillsPage.tsx`。
- 入口：`TabPill` 與 tabs wrapper。
- 必要行為：
  - 將 wrapper 改成單一 segmented container，例如 `inline-flex flex-wrap gap-1 rounded-md border border-border bg-card/或 bg-[#0F0F12] p-1`。
  - `TabPill` 建議接收 `label` 與 `count`，讓 count 可以用 muted span 呈現。
  - active tab 使用 `bg-[#171719]` / `accent-soft` / stronger border / `text-foreground`。
  - inactive tab 使用 transparent/dark hover；不得出現 `bg-white`。
  - 加上 `focus-visible` style，鍵盤使用時每個 tab 都看得到焦點。

## 單元測試 / 整合測試
- `MySkillsPage.test.tsx`
  - `@DisplayName("AC-S172-14: lifecycle tabs do not use bg-white inactive style")`
  - `@DisplayName("AC-S172-14: lifecycle tab count renders as muted inline text")`
  - `@DisplayName("AC-S172-15: lifecycle tab buttons remain focusable and change active filter")`

## 會改哪些檔案
- `frontend/src/pages/MySkillsPage.tsx`
- `frontend/src/pages/MySkillsPage.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- MySkillsPage.test.tsx`

## 前置條件
- 無。

## 狀態
pending（待做）
