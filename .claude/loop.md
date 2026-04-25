讀取 docs/grimo/specs/spec-roadmap.md，依照以下規則每次處理一個 spec：

1. 找到第一個未完成（非 ✅）的 spec，依其狀態決定動作：
   - 🔲 未設計 → 執行 /planning-spec 完成設計（→ ⏳ Design）
   - ⏳ Design（已設計，依賴都 ✅）→ 執行 /planning-tasks 完成實作
   - 實作通過驗證後 → 執行 /shipping-release 出貨（→ ✅）
2. 前端相關 spec 設計前先讀 docs/grimo/ui/README.md
3. 每次只處理一個 spec，完成後進入下一輪
4. 所有 spec 都 ✅ 後停止
5. 遵守依賴關係：依賴未完成就跳過，找下一個可處理的 spec

Skill 流程（每個 spec 的完整生命週期）：

```
/planning-spec [/deep-research] → /planning-tasks ⟺ /implementing-task (loop) → /verifying-quality → /shipping-release
```

各 skill 職責：
- /deep-research — 深入研究外部專案或技術，產出架構、協議、資料流設計文件（可選，搭配 /planning-spec 使用）
- /planning-spec — 研究 API、設計方案、寫 spec §1-5（Goal, Approach, AC, Interface, File Plan）。可帶 /deep-research 參數先做深入研究再設計
- /planning-tasks — 拆 BDD task、逐一呼叫 /implementing-task、最終驗證
- /implementing-task — TDD 實作單一 task（Red → Green → Refactor）
- /verifying-quality — 獨立 QA subagent，三層驗證（自動 + 整合 + 手動）
- /shipping-release — commit、文件同步、歸檔 spec、更新 CHANGELOG、打 tag
