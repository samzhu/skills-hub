# S192-T06: Source scan guard and docs update

## 對應規格
S192：作者顯示名稱一致性收斂

## 這個 task 要做什麼
用 repo scan 檢查 user-facing TSX 不再把 `.author` / `.authorId` 直接當顯示文字，並把 S192 的 display-vs-id 規則寫回 glossary / development standards。

## 使用者情境（BDD）
Given（前提）S192 前五個 task 已把 backend DTO 與 frontend UI 改完
When（動作）搜尋 user-facing TSX render sites
Then（結果）看不到 `.author` / `.authorId` 直接出現在一般 visible label 中
And（而且）docs 說明 `author` / `authorId` 是 behavior-bearing id，UI 必須用 display helper 或 backend display fields

## 研究來源
- `docs/grimo/specs/2026-05-17-S192-author-display-name-completion.md` AC-S192-8
- `docs/grimo/glossary.md`
- `docs/grimo/development-standards.md`

## 先做 POC
- POC：not required — inspection task；用 `rg` 命令即可驗證。

## 正式程式怎麼做
- Class / file 名稱：docs and source scan evidence
- 入口：repo inspection
- 必要行為：
  - 更新 `docs/grimo/glossary.md` 的 Platform User ID 說明
  - 更新 `docs/grimo/development-standards.md` frontend/API display rule
  - 在 spec §7 記錄 source scan 命令與例外分類

## 單元測試 / 整合測試
- Inspection:
  - `rg -n "\\.(author|authorId)\\b" frontend/src --glob '*.tsx'`
  - 人工分類：debug/test/API docs/log-only/route-command segment 可留；普通 UI label 不可留

## 會改哪些檔案
- `docs/grimo/glossary.md`
- `docs/grimo/development-standards.md`
- `docs/grimo/specs/2026-05-17-S192-author-display-name-completion.md`

## 驗證方式
執行：`rg -n "\\.(author|authorId)\\b" frontend/src --glob '*.tsx'`

## 前置條件
- S192-T01~T05 PASS

## 狀態
pending（待做）
