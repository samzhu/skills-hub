# Skills Hub — Glossary

| 中文 | English | Code Naming | 說明 |
|------|---------|-------------|------|
| 技能 | Skill | `Skill` | 遵循 agentskills.io 規範的 SKILL.md 資料夾，AI agent 可載入使用的能力單元 |
| 技能市集 | Skills Hub / Registry | `SkillsHub` | 本平台，企業內部的技能集中管理與分發平台 |
| 技能作者 | Skill Author / Publisher | `Publisher` | 建立並發佈技能到平台的使用者角色 |
| 技能消費者 | Skill Consumer | `Consumer` | 瀏覽、搜尋、下載技能的使用者角色 |
| 管理者 | Admin | `Admin` | 管理平台設定、使用者、審核的角色 |
| 風險等級 | Risk Level | `RiskLevel` | 技能的安全風險分級：NONE / LOW / MEDIUM / HIGH（per ADR-future S096c；NONE = 0 findings + no scripts + no allowed-tools） |
| 風險評估 | Risk Assessment | `RiskAssessment` | 對技能內容的自動安全掃描與分級 |
| 硬結構 | Hard Structure | `Organization` | 集團 → 公司 → 部門的固定組織層級樹；S170 起在資料模型上同樣落到 `Group` |
| 軟結構 | Soft Structure | `Workspace` | 戰情室、合作專案等跨組織的彈性團隊空間；S170 起在資料模型上同樣落到 `Group` |
| 集團 | Organization | `Org` | 組織層級最頂層 |
| 公司 | Company | `Company` | 集團下的子公司 |
| 部門 | Department | `Department` | 公司下的部門，可多層巢狀 |
| 群組 | Group | `Group` | S170 起，Company / Department / Team / Other 都是同一種可掛人、可掛子群組的樹狀 Group；分享時由 `PrincipalContextService` 產生 `group:<id>` principal，再展開成 `group:<id>:<permission>` ACL entry |
| 群組 Principal | Group Principal | `group:<id>` | S170 起，`PrincipalContextService` 裡的扁平字串；使用者被放進子群組時會同時得到該群組與所有父群組 principal |
| 協作群組 | Collaboration Group | `Group(kind=TEAM)` | S170 起，跨公司、跨部門的彈性群組不另建獨立表，使用 `Group` 搭配 display-only `TEAM` kind 表示；可 root，也可放在任何群組下 |
| 戰情室 | War Room | `WarRoom` | 臨時性的跨組織協作空間 |
| 合作專案 | Collaborative Project | `Project` | 跨部門/跨公司的專案組合空間 |
| 語意搜尋 | Semantic Search | `SemanticSearch` | 用 AI embedding 做自然語言能力匹配的搜尋 |
| 版本 | Version | `Version` | 技能的版本號（semver 格式） |
| 社群回報 | Community Flag | `Flag` | 使用者對已上架技能提交的問題回報 |
| 使用數據 | Analytics | `Analytics` | 技能的下載次數、使用頻率等統計數據 |
| 領域事件 | Domain Event | `DomainEvent` | 記錄領域中發生的不可變事實（如 SkillCreated, SkillVersionPublished） |
| 事件儲存 | Event Store | `DomainEventRepository` | 持久化 domain events 的 PostgreSQL `domain_events` 表（JSONB payload + per-aggregate `(aggregate_id, sequence)` UNIQUE） |
| 命令 | Command | `*Command` | 表達意圖的請求物件（如 CreateSkillCommand），觸發狀態變更 |
| 投影 | Projection | `*Projection` | 監聽 domain events 並更新 read model 的元件 |
| 讀取模型 | Read Model | `*ReadModel` | 由 projection 建構的查詢優化資料結構 |
| 命令端 | Command Side | `command/` | CQRS 的寫入面，處理命令並產生事件 |
| 查詢端 | Query Side | `query/` | CQRS 的讀取面，從 read model 提供查詢結果 |
| 聚合根 | Aggregate Root | `Skill` | 核心域的一致性邊界，封裝業務規則並產生 domain events。本專案只有 `Skill` 是 Aggregate Root |
| 值物件 | Value Object | `SkillVersion` | Aggregate 內部的不可變物件，無獨立 identity |
| 不變量 | Invariant | — | Aggregate 必須維護的業務規則（如 version 必須遞增、name 唯一） |
| 核心域 | Core Domain | `skill/` | 使用 Aggregate + ES + CQRS 的核心業務模組 |
| 集合 | Collection | `Collection` | 多個 skills 打包成的 curated bundle，支援一鍵安裝（per P7 / S096f） |
| 需求 | Request | `SkillRequest` | 使用者公開發起的「我需要這種 skill」需求；可投票推升優先級、作者可認領（per P8 / S096g） |
| 通知 | Notification | `Notification` | 從 domain events projected 出的 user-facing 動態提醒，支援分類過濾與已讀標記（per P9 / S096h） |
| 訂閱 | Subscription | `SkillSubscription` | 使用者對特定 skill 的關注關係，作為通知過濾依據（per P9） |
| 端對端測試 | E2E Test | `*.spec.ts` (Playwright) | 透過 browser 模擬使用者完整 user journey 的測試（Playwright via `/playwright-expert`，per ADR-007） |
| 測試夾具狀態 | Fixture Profile | `@profile-<name>` tag | E2E 測試的初始 DB 狀態類型：empty / single / paged / full / mixed-visibility / multi-role / boundary（per `playwright-expert/references/fixtures-patterns.md`） |
| 測試資料種子 | Test Data Seed | `TestDataController` | 走 backend `@Profile({"local","dev","e2e"})` controller 透過 `SkillCommandService.create()` 寫入測試資料；**禁繞 aggregate** 直接 INSERT |
| 追蹤檔 | Playwright Trace | `trace.zip` | Playwright 錄製的 time-travel debugger artefact，含 screenshot film strip + DOM snapshot + network；本機 `npx playwright show-trace` 或拖到 trace.playwright.dev |
| 證據檔 | Evidence Contract | `e2e/results/evidence.json` | `playwright-expert` VERIFY mode 產出的跨 skill 契約檔，給 `/verifying-quality` 讀取；schema 含 spec_id / stats / per-test ok / trace_paths |
| 技能分數 | Skill Score | `skillScore` | S142b 複合評分公式：`round(0.6 × qualityTotal + 0.4 × securityScore)`；securityScore 為 null（未掃描）時 skillScore = null；出現在 GET /scores 回應 |
| 安全報告 | Security Report | `SecurityReportResponse` | S142b 4-quad 安全檢查視圖（Shell / Paths / Secrets / Deps）；從 `risk_assessment` JSONB findings 依 analyzer + ruleId 分類為 4 個 quad；每 quad 各有 status（PASS/WARN/FAIL）+ detail；出現在 GET /security-report 回應 |
| 已驗證 | Verified | `verified` | S142b 衍生旗標：`status === 'PUBLISHED' && riskLevel != null`；表示「平台已完成品質 + 安全兩階段審查」≠「無風險」；出現在 GET /skills/{id} 回應 |
| 平台識別碼 | Platform User ID | `userId` (`u_<6hex>`) | S154 起，平台對 user 的 internal PK；解耦 OAuth provider 的 sub；ACL principal / `skills.author` / `skills.owner_id` 都用此 ID（`u_a3f9c1` 格式）；同一個人換 OAuth provider 仍是不同 user_id（per S154 §2.4 Pattern A）|
| 顯示用 slug | Handle | `handle` | S154 起，user 在平台上的可讀短名稱（`alice`）；install command + `/api/v1/skills/{handle}/{name}` URL 用它；user 可改、撞名時自動加 `-2/-3` 後綴 |
| OAuth 識別碼 | OAuth Sub | `sub` | OAuth provider 給的 raw subject identifier（Google: 21 位數字；GitHub: 數字 ID）；S154 起只在 `users.sub` 出現，**不**在業務表（`skills` / `acl_entries`）|
| 顯示名快照 | Author Name Snapshot | `authorNameSnapshot` | S154 起，publish/republish 時 freeze 的作者 display name；user 帳號刪除後 fallback 顯示來源（per S154 §2.6） |
| 顯示名解析鏈 | DisplayName Resolver | `DisplayNameResolver` | S154 起，五層 fallback：name → snapshot → email local-part → handle → user_id（per S154 §2.5）；frontend `lib/displayName.ts` 與 backend static helper 同邏輯 |
| 權限角色 | Permission Role | `Role` | S169 起，使用者分享 skill 時只選角色（OWNER / EDITOR / VIEWER），系統再展開成 `read/write/delete` 權限；UI 不提供 raw operation checkbox |
| 檢視者權限 | Viewer Permissions | `viewerPermissions` | S169 起，Skill detail API 由後端依當前 user 計算可做動作（canEdit/canDelete/canShare 等），frontend 按鈕只讀此欄位，不重做 ACL 判斷 |
