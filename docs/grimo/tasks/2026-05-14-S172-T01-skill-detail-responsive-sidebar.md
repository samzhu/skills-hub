# S172-T01: Skill detail responsive grid + sidebar

## 對應規格
S172：Production UI Responsive Polish

## 這個 task 要做什麼
修正 `/skills/{id}` 在手機與平板寬度會出現 body 水平捲動的問題。完成後，使用者在 390px、768px、900px 寬度看技能詳情時，主內容與安裝/統計 sidebar 會上下堆疊，整頁只需要垂直捲動。

## 使用者情境（BDD）
Given（前提）`deep-research` 或任一已發布技能存在，且使用者開啟 `/skills/{id}`  
When（動作）viewport 是 390x844、768x900 或 900x700  
Then（結果）`document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1`  
And（而且）安裝指令、下載 CTA、30 天下載趨勢與 sidebar metadata 都在畫面內可讀  
And（而且）寬度 `< 1024px` 時 sidebar 使用上邊線與垂直間距，不使用桌面版左邊線。

## 研究來源
- `docs/grimo/specs/2026-05-14-S172-production-ui-responsive-polish.md` AC-S172-1、AC-S172-2、§4.4 sketch。
- `docs/grimo/ui/DESIGN.md` dark surface / border token。
- `frontend/src/pages/SkillDetailPage.tsx`：目前固定 `gridTemplateColumns: '1fr 232px'`。
- `frontend/src/components/v2/Sidebar.tsx`：目前 sidebar 視覺是桌面左邊線。
- WCAG 2.2 Reflow：一般頁面內容不可要求雙向捲動。

## 先做 POC
- POC：not required — 這個 task 只改既有 React/Tailwind 版面 class，沒有新套件、SDK 或未驗證 API。

## 正式程式怎麼做
- File 名稱：`frontend/src/pages/SkillDetailPage.tsx`、`frontend/src/components/v2/Sidebar.tsx`。
- 入口：`SkillDetailPage` render body grid；`Sidebar` render install/stats metadata。
- 必要行為：
  - 將固定 inline grid 改成 responsive class 或 breakpoint-aware style：`<1024px` 單欄，`>=1024px` 才是 `minmax(0,1fr) 232px`。
  - 主欄需使用 `min-w-0`，避免 tab content、code block 或 markdown 內容撐破 grid。
  - `Sidebar` 在 stacked mode 使用 top border / top padding；桌面才使用 left border / left padding。
  - 不用 global `overflow-x-hidden` 遮掉問題。
  - 若 TabsList 在窄寬度太長，只能讓 tab list 自己水平捲動，不能讓 body 水平捲動。

## 單元測試 / 整合測試
- `SkillDetailPage.test.tsx`
  - `@DisplayName("AC-S172-1: detail body uses responsive grid without fixed 232px sidebar on mobile")`
  - `@DisplayName("AC-S172-2: sidebar switches from left divider to stacked divider below lg")`

## 會改哪些檔案
- `frontend/src/pages/SkillDetailPage.tsx`
- `frontend/src/components/v2/Sidebar.tsx`
- `frontend/src/pages/SkillDetailPage.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- SkillDetailPage.test.tsx`

## 前置條件
- 無。

## 狀態
pending（待做）
