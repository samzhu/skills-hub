讀取 docs/grimo/specs/spec-roadmap.md，依照以下規則每次處理一個 spec：

1. 如果有 ⏳ 狀態且其依賴都已 ✅ 的 spec，執行 /planning-tasks 完成實作
2. 否則找第一個 🔲 的 spec，執行 /planning-spec 完成設計
3. 前端相關 spec（S002, S004 等）設計前先讀 docs/grimo/ui/README.md 和 docs/grimo/ui/ 的 HTML mockups
4. 每次只處理一個 spec
5. 所有 spec 都 ✅ 後停止

優先順序：先實作（⏳ → ✅），再設計（🔲 → ⏳）。
遵守依賴關係：依賴未完成就不能實作該 spec。
