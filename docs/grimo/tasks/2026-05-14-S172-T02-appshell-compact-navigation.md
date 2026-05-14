# S172-T02: AppShell compact navigation

## 對應規格
S172：Production UI Responsive Polish

## 這個 task 要做什麼
讓 AppShell 在手機與平板不再把 9 個導覽連結硬塞在同一列。完成後，390px 寬度會顯示 brand、auth area、通知入口（登入時）與一個可操作的導覽選單；使用者可以用滑鼠與鍵盤打開完整導覽。

## 使用者情境（BDD）
Given（前提）AppShell 有 9 個 nav links：瀏覽、集合、群組、需求、我的技能、發佈、數據、待審回報、文件  
When（動作）使用者在 390x844 開啟任一頁並按下導覽選單按鈕  
Then（結果）完整導覽清單出現，所有連結可點擊、可鍵盤 focus、連到正確 path  
And（而且）header 內容不重疊、不把 auth area 推出畫面、不造成 body 水平捲動。

## 研究來源
- `docs/grimo/specs/2026-05-14-S172-production-ui-responsive-polish.md` AC-S172-3、AC-S172-4。
- `frontend/src/components/AppShell.tsx`：目前 nav 是單列 `flex flex-1 items-center gap-4`。
- `docs/grimo/ui/DESIGN.md` nav height、dark background、hairline border。
- MDN `flex-wrap`：nowrap 會保留一列並可能 overflow；窄寬度應 wrap 或切 compact UI。

## 先做 POC
- POC：not required — 這個 task 使用既有 React state、React Router `Link`、lucide icon，沒有新增 package。

## 正式程式怎麼做
- File 名稱：`frontend/src/components/AppShell.tsx`。
- 入口：`AppShell` header nav。
- 必要行為：
  - below chosen breakpoint（建議 `<1024px`）隱藏桌面 nav，顯示 icon button（建議 lucide `Menu` / `X`）。
  - menu button 要有 `aria-label`、`aria-expanded`、`aria-controls`。
  - compact menu 內每個 nav link 保留目前 path highlight 行為。
  - 點擊 menu 內 link 後關閉 menu；按 Escape 也關閉 menu。
  - 桌面 `>=1024px` 保留目前橫向 nav。
  - header/main padding 在手機應降到不撐寬，例如 `px-4 sm:px-6`。

## 單元測試 / 整合測試
- `AppShell.test.tsx`
  - `@DisplayName("AC-S172-3: mobile menu exposes all AppShell nav links with aria-expanded state")`
  - `@DisplayName("AC-S172-3: clicking a compact nav link closes the menu")`
  - `@DisplayName("AC-S172-4: desktop nav still renders regular links and highlights current path")`

## 會改哪些檔案
- `frontend/src/components/AppShell.tsx`
- `frontend/src/components/AppShell.test.tsx`

## 驗證方式
執行：`cd frontend && npm test -- AppShell.test.tsx`

## 前置條件
- S172-T01 PASS（避免 Playwright overflow finding 混在同一輪）。

## 狀態
pending（待做）
