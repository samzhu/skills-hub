# Skills Hub — Glossary

| 中文 | English | Code Naming | 說明 |
|------|---------|-------------|------|
| 技能 | Skill | `Skill` | 遵循 agentskills.io 規範的 SKILL.md 資料夾，AI agent 可載入使用的能力單元 |
| 技能市集 | Skills Hub / Registry | `SkillsHub` | 本平台，企業內部的技能集中管理與分發平台 |
| 技能作者 | Skill Author / Publisher | `Publisher` | 建立並發佈技能到平台的使用者角色 |
| 技能消費者 | Skill Consumer | `Consumer` | 瀏覽、搜尋、下載技能的使用者角色 |
| 管理者 | Admin | `Admin` | 管理平台設定、使用者、審核的角色 |
| 風險等級 | Risk Level | `RiskLevel` | 技能的安全風險分級：LOW / MEDIUM / HIGH |
| 風險評估 | Risk Assessment | `RiskAssessment` | 對技能內容的自動安全掃描與分級 |
| 硬結構 | Hard Structure | `Organization` | 集團 → 公司 → 部門的固定組織層級樹 |
| 軟結構 | Soft Structure | `Workspace` | 戰情室、合作專案等跨組織的彈性團隊空間 |
| 集團 | Organization | `Org` | 組織層級最頂層 |
| 公司 | Company | `Company` | 集團下的子公司 |
| 部門 | Department | `Department` | 公司下的部門，可多層巢狀 |
| 戰情室 | War Room | `WarRoom` | 臨時性的跨組織協作空間 |
| 合作專案 | Collaborative Project | `Project` | 跨部門/跨公司的專案組合空間 |
| 語意搜尋 | Semantic Search | `SemanticSearch` | 用 AI embedding 做自然語言能力匹配的搜尋 |
| 版本 | Version | `Version` | 技能的版本號（semver 格式） |
| 社群回報 | Community Flag | `Flag` | 使用者對已上架技能提交的問題回報 |
| 使用數據 | Analytics | `Analytics` | 技能的下載次數、使用頻率等統計數據 |
| 領域事件 | Domain Event | `DomainEvent` | 記錄領域中發生的不可變事實（如 SkillCreated, SkillVersionPublished） |
| 事件儲存 | Event Store | `DomainEventRepository` | 持久化 domain events 的 Firestore collection（`domain_events`） |
| 命令 | Command | `*Command` | 表達意圖的請求物件（如 CreateSkillCommand），觸發狀態變更 |
| 投影 | Projection | `*Projection` | 監聽 domain events 並更新 read model 的元件 |
| 讀取模型 | Read Model | `*ReadModel` | 由 projection 建構的查詢優化資料結構 |
| 命令端 | Command Side | `command/` | CQRS 的寫入面，處理命令並產生事件 |
| 查詢端 | Query Side | `query/` | CQRS 的讀取面，從 read model 提供查詢結果 |
| 聚合根 | Aggregate Root | `Skill` | 核心域的一致性邊界，封裝業務規則並產生 domain events。本專案只有 `Skill` 是 Aggregate Root |
| 值物件 | Value Object | `SkillVersion` | Aggregate 內部的不可變物件，無獨立 identity |
| 不變量 | Invariant | — | Aggregate 必須維護的業務規則（如 version 必須遞增、name 唯一） |
| 核心域 | Core Domain | `skill/` | 使用 Aggregate + ES + CQRS 的核心業務模組 |
