# Skills Hub — Product Requirements Document

## Problem Statement

AI Agent 技能（Skills）生態正在快速成長，agentskills.io 的 SKILL.md 標準已被 30+ 產品採用（Claude Code、Cursor、GitHub Copilot、OpenAI Codex、Gemini CLI 等），但目前**沒有一個集中式的 Agent Skills Registry**。企業內部面臨以下痛點：

1. **發現困難** — 團隊成員不知道哪些好用的 skills 已經存在，重複造輪子
2. **品質不可控** — 從 GitHub 引入的 skills 缺乏安全審核，含 scripts 的技能可能帶來安全風險
3. **無法管理** — 沒有統一的平台追蹤技能的版本、使用狀況、健康度
4. **協作不足** — 跨部門、跨公司的技能分享缺乏機制

## Solution

**Skills Hub** 是一個企業內部的 AI Agent 技能市集與 Registry 平台。基於 agentskills.io 的 SKILL.md 開放標準，提供技能的發佈、發現、安全評估、安裝與使用分析的完整生命週期管理。

## Core Principles

| 原則 | 理由 |
|------|------|
| **標準優先** | 遵循 agentskills.io SKILL.md 規範，確保技能可跨 30+ 工具使用，不鎖定特定 agent |
| **安全分級** | 區分純 markdown 技能（低風險）與含 scripts 技能（高風險），自動風險評估保障企業安全 |
| **來源不限** | 內部自研與外部引入一視同仁，統一經過品質管控流程 |
| **漸進式功能** | MVP 專注核心價值（發現 + 發佈 + 安全 + 安裝），認證/權限/組織管理後續整合 |
| **AI 驅動探索** | 用語意搜尋取代純關鍵字，讓使用者用自然語言描述需求找到技能 |

## Target Users

| 角色 | 需求 |
|------|------|
| **個人開發者** | 快速找到好用的 skills，一鍵下載安裝到 Claude Code / Cursor 等工具 |
| **團隊 / 企業管理者** | 策管審核 approved skills，確保安全合規，掌握使用狀況 |
| **技能創作者** | 發佈推廣自己寫的 skills，獲得使用數據回饋 |

## Skill Format

遵循 [agentskills.io specification](https://agentskills.io/specification)：

```
my-skill/
  SKILL.md          # 必要 — YAML frontmatter + markdown 指令
  scripts/           # 可選 — 可執行腳本
  references/        # 可選 — 參考文件，按需載入
  assets/            # 可選 — 模板、資料檔案
```

SKILL.md frontmatter 必要欄位：
- `name`：技能名稱（小寫連字號，最長 64 字元）
- `description`：技能描述（最長 1024 字元）

可選欄位：`license`、`version`、`author`、`compatibility`、`metadata`、`allowed-tools`

---

## Critical Path（MVP 優先序）

以下為 demo-able 的核心能力，按優先序排列：

### P1 — 技能瀏覽與搜尋 ✅ (S001, S002)

使用者能在 Web 介面上瀏覽、搜尋、篩選已上架的技能。

**SBE Acceptance Criteria：**

```
Scenario: 用關鍵字搜尋技能
  Given 平台上有 50 個已上架的 skills
  When 使用者在搜尋框輸入 "docker"
  Then 回傳所有 name 或 description 含 "docker" 的 skills
  And 結果按相關度排序
  And 每筆顯示 name、description、author、version、風險等級、下載次數

Scenario: 按分類篩選技能
  Given 平台上有多個分類（例如：DevOps、Testing、Documentation）
  When 使用者選擇 "DevOps" 分類
  Then 只顯示該分類下的 skills

Scenario: 技能詳情頁
  Given 使用者點擊某個 skill
  Then 顯示完整的 SKILL.md 內容（rendered markdown）
  And 顯示版本歷史、風險評估結果、下載統計、社群評分
```

### P2 — 技能發佈流程 ✅ (S003, S004)

技能作者能上傳 skill 資料夾，經過驗證後上架。

**SBE Acceptance Criteria：**

```
Scenario: 上傳合法的純 markdown skill
  Given 作者準備了一個 skill 資料夾，僅含 SKILL.md（無 scripts/）
  When 作者透過 Web 上傳該資料夾（zip/tar）
  Then 平台驗證 SKILL.md 格式符合 agentskills.io 規範
  And 自動標記為「低風險」
  And skill 進入「已上架」狀態
  And 建立版本號（未輸入時自動為 v1；作者也可自訂版本號）

Scenario: 上傳含 scripts 的 skill
  Given 作者準備了含 scripts/ 目錄的 skill 資料夾
  When 作者上傳該資料夾
  Then 平台驗證 SKILL.md 格式
  And 自動執行風險評估掃描
  And skill 標記為對應風險等級（中/高）
  And skill 進入「待審核」狀態

Scenario: 上傳不合規的 skill
  Given 作者上傳的資料夾缺少 SKILL.md 或 frontmatter 格式錯誤
  When 平台驗證失敗
  Then 回傳具體的錯誤訊息（哪個欄位、什麼問題）
  And skill 不會上架

Scenario: 更新已有 skill 的版本
  Given 作者已有一個 v1 的 skill 在平台上
  When 作者上傳新版本
  Then 平台建立下一個流水號版本（如 v2），或使用作者指定的版本號
  And 舊版本仍可下載
  And 詳情頁顯示完整版本歷史
```

### P3 — 自動風險評估 ✅ (S005)

發佈時自動掃描技能內容，評估安全風險等級。

**SBE Acceptance Criteria：**

```
Scenario: 純 markdown skill 的風險評估
  Given 上傳的 skill 僅含 SKILL.md（無 scripts/、無外部引用）
  When 風險評估引擎掃描
  Then 標記為「低風險」
  And 記錄評估結果（掃描項目 + 通過/未通過）

Scenario: package 內含危險指令
  Given skill zip 內任一文字檔含有 `rm -rf`、`curl | bash`、或存取敏感路徑的指令
  When 風險評估引擎掃描
  Then 標記為「高風險」
  And 列出 issue code、具體危險項目、所在檔案/行號與修法建議
  And skill 進入「待審核」狀態

Scenario: 含外部依賴的 package
  Given skill zip 內任一文字檔下載或引用外部 URL
  When 風險評估引擎掃描
  Then 列出可疑下載網址或無法驗證的外部依賴 issue
  And 根據 issue severity 計算風險等級
```

### P4 — 一鍵安裝（Web 下載） ✅ (S006)

使用者能從 Web 介面直接下載技能的打包檔案。

**SBE Acceptance Criteria：**

```
Scenario: 下載最新版本
  Given 使用者在某 skill 的詳情頁
  When 點擊「下載」按鈕
  Then 下載該 skill 最新版本的 zip 檔
  And zip 解壓後就是完整的 skill 資料夾結構

Scenario: 下載指定版本
  Given 使用者在版本歷史中選擇 v1.0.0
  When 點擊該版本的下載按鈕
  Then 下載 v1.0.0 的 zip 檔

Scenario: 下載頁面提供安裝指引
  Given 使用者下載了 skill zip 檔
  Then 頁面顯示安裝說明：
    "解壓後將資料夾放到 ~/.claude/skills/（系統級）
     或 <project>/.claude/skills/（專案級）"
```

### P5 — 語意搜尋 / 任務導向探索

使用者用自然語言描述需求，AI 推薦最適合的技能。

**SBE Acceptance Criteria：**

```
Scenario: 自然語言搜尋
  Given 平台上有 "docker-compose-helper" 和 "k8s-deployment" 等 skills
  When 使用者輸入「我想把應用部署到容器環境」
  Then 回傳語意相關的 skills（docker-compose-helper、k8s-deployment）
  And 結果按語意相關度排序
  And 不要求使用者知道確切的 skill 名稱

Scenario: 任務導向推薦
  Given 使用者輸入「幫我寫單元測試」
  When 語意搜尋引擎處理
  Then 推薦測試相關的 skills（例如 junit-generator、test-coverage-analyzer）
  And 每筆結果附上匹配理由（為什麼這個 skill 適合你的需求）

Scenario: 無相關結果
  Given 使用者輸入的需求在平台上沒有匹配的 skill
  When 語意搜尋引擎處理
  Then 顯示「未找到匹配的技能」
  And 建議使用者調整描述或瀏覽分類目錄
```

### P6 — 使用數據分析 ✅ (S008)

追蹤技能的下載、使用狀況，提供數據儀表板。

**SBE Acceptance Criteria：**

```
Scenario: 技能下載統計
  Given 某 skill 被下載了 150 次
  When 查看該 skill 的詳情頁
  Then 顯示總下載次數 150
  And 顯示近 7 天 / 30 天的下載趨勢圖

Scenario: 平台總覽儀表板
  Given 管理者進入數據分析頁面
  When 查看總覽
  Then 顯示：總 skill 數、總下載次數、本週新增 skills、熱門排行 Top 10
  And 數據先以匿名方式追蹤（無帳號關聯）

Scenario: 技能作者查看自己的數據
  Given 某作者發佈了 3 個 skills
  When 作者進入「我的技能」頁面
  Then 顯示每個 skill 的下載次數、趨勢、評分
  And 帳號整合後可關聯到作者身份
```

---

### P7 — 技能集合（Collections）📋 (S096f, planned)

讓使用者把多個相關技能打包成「集合」一鍵安裝，類似 npm `bundle` 或 GitHub Curated List。

**SBE Acceptance Criteria：**

```
Scenario: 創建集合
  Given 使用者在「我的技能」或瀏覽頁
  When 點選「建立集合」並選 3 個 skills 加入
  Then 集合創建成功
  And 顯示集合名稱、描述、3 個成員 skills（含版本）

Scenario: 一鍵安裝集合
  Given 使用者在 /collections 頁看到 "DevOps Starter Pack" 集合
  When 點選「Install」
  Then 系統依序下載集合內所有 skills
  And 每個 skill 自動套用其當前 latest version

Scenario: 集合分類與篩選
  Given 平台已有 12 個集合
  When 使用者用 category=DevOps + risk=low 篩選
  Then 顯示符合條件的集合（過濾掉含 high-risk skill 的集合）
```

---

### P8 — 技能需求看板（Request Board）📋 (S096g, planned)

讓使用者公開發起「我需要這種 skill」的需求，社群投票決定優先級，作者可認領後實作。

**SBE Acceptance Criteria：**

```
Scenario: 發起需求
  Given 使用者在搜尋「kubernetes auto-scaling」找不到合適 skill
  When 點選「請求這個 skill」並寫下需求 title + description
  Then 需求進入 /requests 看板，狀態 OPEN
  And 預設投票數 1（發起人自動算 1 票）

Scenario: 投票推升優先級
  Given 「k8s autoscaler skill」需求已開啟 7 天
  When 5 名同事 upvote
  Then 票數變 6
  And 該需求在「依票數排序」list 中升至前段

Scenario: 認領與實作
  Given 「k8s autoscaler skill」累計 12 票
  When `platform-team` 作者點選「Claim」
  Then 該需求狀態變 IN-PROGRESS
  And 作者上傳對應 skill 後，需求狀態變 FULFILLED
  And 系統自動 link 該 skill 到原始需求
```

---

### P9 — 通知中心（Notifications）📋 (S096h, planned)

把 domain events（per architecture.md）作為 user-facing notification source，給作者 / 訂閱者 / admin 觀察自己關心的 skill 動態。

**SBE Acceptance Criteria：**

```
Scenario: 新版本發布通知
  Given 使用者訂閱了 docker-compose-helper skill
  When 該 skill 作者發布 v2.1.0
  Then 使用者通知中心顯示 1 unread badge
  And 通知列表顯示「docker-compose-helper v2.1.0 已發布」+ 時間 + 跳轉連結

Scenario: 通知分類過濾
  Given 使用者通知中心有 12 條未讀
  When 切換 tab=flags
  Then 只顯示與 SkillFlagged 相關通知（如「你發布的 skill 收到 1 個 flag」）

Scenario: 全部已讀 + 偏好設定
  Given 使用者打開 /notifications
  When 點選「全部標為已讀」
  Then 所有通知變 read，bell badge 歸 0
  And 在偏好頁可關閉特定類型的通知（如 "version updates only"）
```

---

## Backlog（依重要性排序）

| 優先級 | 功能 | 說明 |
|--------|------|------|
| B1 | 權限控制 | Admin / Publisher / Consumer 三層角色，資料模型先設計，功能後續啟用 |
| B2 | OAuth 認證整合 | 接企業 OAuth Server，與權限控制一起啟用 |
| B3 | 社群回報與評分 | 使用者 flag 問題 skill、星級評分、文字評論 |
| B4 | 一鍵安裝（深度連結） | 網頁 Install 按鈕透過 URL scheme 觸發本地安裝 |
| B5 | 一鍵安裝（CLI） | `skills-hub install org/my-skill` 命令列安裝 |
| B6 | 人工審核流程 | 高風險 skill 指定審核者 approve/reject |
| B7 | 組織層級管理 | 集團 → 公司 → 部門樹狀結構 |
| B8 | 軟結構 | 戰情室、合作專案等跨組織彈性團隊空間 |
| B9 | MCP Server 支援 | 擴展支援 MCP Server 類型的技能 |
| B10 | Prompt Templates | 擴展支援 prompt 模板庫 |

---

## MVP Scope

### In Scope

- **前端**：React 19 SPA（Vite 8 + TypeScript 6 + Tailwind CSS 4 + shadcn/ui + Beam），打包至 Spring Boot static resources
- **後端**：Spring Boot 4.0.6 + Spring Modulith（Java 25, Gradle 9.4.1）
- **API 文件**：SpringDoc OpenAPI 3.0.2（Swagger UI）
- **資料儲存**：PostgreSQL 16 + pgvector（Spring Data JDBC + 自訂 `SkillshubPgVectorStore`）+ GCS 存 skill 打包檔
- **語意搜尋**（非最優先）：Spring AI + Gemini embedding + 自訂 `SkillshubPgVectorStore`（HNSW + cosine），P1-P4 完成後再實作
- **自動風險評估**：靜態分析引擎（掃描 scripts/ 中的危險模式）
- **可觀測性**：OpenTelemetry + Grafana LGTM（模板已含）
- **安全基底**：Spring Security OAuth2 Resource Server（模板已含，MVP 先 mock）
- **部署**：Docker container → GCP Cloud Run（單一 container）
- **SKILL.md 格式驗證**：符合 agentskills.io 規範

### 上線狀態（Status）

- ✅ MVP v1.0.0（2026-04-27 ship）— 14 specs / 147 story points；初始上線使用 Firestore Enterprise（已被 Phase 1 取代）
- ✅ Phase 1 v1.1.0（2026-04-27 ship）— S014 PostgreSQL 資料層遷移 + 自訂 `SkillshubPgVectorStore` + Firestore 全清；詳 [ADR-001](./adr/ADR-001-postgresql-migration.md) + [`S014 archived spec`](./specs/archive/2026-04-27-S014-postgresql-migration.md)
- ✅ Phase 2.5 — Project Infra（S019/S020/S021/S022 已 ship）
- ✅ Phase 2 — Row-Level ACL × 充血 Aggregate（S016/S017/S018 已 ship）
- ✅ Phase 3a v1.5.0（2026-04-29 ship）— S023 Spring Modulith Outbox Foundation（transactional outbox + 9 listener async migration + ShedLock retry + Micrometer metrics）；詳 [ADR-002](./adr/ADR-002-skill-aggregate-state-based.md) + [`S023 archived spec`](./specs/archive/2026-04-29-S023-modulith-outbox-foundation.md)
- 🔲 Phase 3b — S024 Skill state-based aggregate migration（M19，target v2.0.0）

### Out of Scope (MVP)

- 使用者認證與權限控制（資料模型先設計，功能不啟用）
- 組織層級管理（集團/公司/部門）
- 軟結構（戰情室/合作專案）
- CLI 安裝工具
- 深度連結安裝
- 人工審核流程
- MCP Server 支援
- Prompt Templates
- 收益分潤機制
- 桌面應用程式

---

## Architecture Overview

**Pattern:** Event Sourcing + CQRS（核心領域）+ Event-driven projections（輔助功能）

```
┌──────────────────────────────────────────────────────────────┐
│              GCP Cloud Run (single container)                 │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  Spring Boot 4.0.6 + Spring Modulith + ES/CQRS        │  │
│  │                                                        │  │
│  │  React 19 SPA → backend/src/main/resources/static/     │  │
│  │                                                        │  │
│  │  Command Side (ES):        Query Side (Projections):   │  │
│  │  ┌─────────┐ ┌─────────┐  ┌─────────┐ ┌───────────┐  │  │
│  │  │ skill   │ │security │  │ search  │ │ analytics │  │  │
│  │  │ command │ │(risk    │  │(keyword │ │(stats,    │  │  │
│  │  │         │ │ assess) │  │+semanti)│ │ trends)   │  │  │
│  │  └────┬────┘ └────┬────┘  └─────────┘ └───────────┘  │  │
│  │       │    events │                                    │  │
│  │       └─────┬─────┘                                    │  │
│  │             ▼                                          │  │
│  │  ┌─────────────────┐     ┌──────────────────────────┐ │  │
│  │  │  domain_events  │────▶│  Projection Listeners    │ │  │
│  │  │  (event store)  │     │  (@ApplicationModule     │ │  │
│  │  └─────────────────┘     │   Listener)              │ │  │
│  │                          └──────────────────────────┘ │  │
│  └────────────────────┬───────────────────────────────────┘  │
└───────────────────────┼──────────────────────────────────────┘
                        │
         ┌──────────────┼──────────────┐
         │              │              │
   ┌─────▼──────┐ ┌─────▼─────┐ ┌─────▼─────┐
   │ PostgreSQL  │ │   GCS     │ │ Vertex AI │
   │ 16 +        │ │ (skill    │ │ (Gemini   │
   │ pgvector    │ │  packages)│ │  via      │
   │ (Cloud SQL  │ │ - zip/tar │ │  Spring   │
   │  Auth Proxy │ └───────────┘ │  AI)      │
   │  sidecar)   │               └───────────┘
   │             │
   │ Event Store:│
   │ -domain_   │
   │  events    │
   │  (JSONB)   │
   │ Read Models:│
   │ -skills    │
   │ -skill_    │
   │  versions  │
   │ -flags     │
   │ -download_ │
   │  events    │
   │ Vector:    │
   │ -vector_   │
   │  store     │
   │  (HNSW)    │
   └─────────────┘
```

---

## Organization Model（設計先行，MVP 不啟用）

### 硬結構（固定層級）
```
集團 (Org)
  └── 公司 (Company)
       └── 部門 (Department)  ← 樹狀，可多層巢狀
            └── 部門 ...
```

### 軟結構（彈性組合）
- 戰情室、合作專案等臨時性團隊
- 成員可跨部門、跨公司自由組合
- 有生命週期（建立 → 活躍 → 歸檔）

### 權限模型（MVP 設計，不啟用）
| 角色 | 可瀏覽 | 可安裝 | 可發佈 | 可審核 | 可管理成員 |
|------|--------|--------|--------|--------|-----------|
| Consumer | O | O | X | X | X |
| Publisher | O | O | O | X | X |
| Admin | O | O | O | O | O |

---

## Security Model

### 風險分級

| 等級 | 條件 | 處理方式 |
|------|------|----------|
| **低風險** | package 內無危險 issue；或只有低嚴重度 finding | 自動通過，直接上架 |
| **中風險** | package 內有中嚴重度 issue，例如有限外部依賴或可疑資料流 | 自動上架，標記風險等級 |
| **高風險** | package 內任一文字檔含高嚴重度 issue（prompt injection、惡意程式碼組合、系統服務修改、寫死 secret、敏感資料外送等） | 標記高風險，進入待審核（MVP 先標記，人工審核流程在 Backlog） |

### 自動掃描項目
- zip 內所有 UTF-8 文字檔掃描（不只 `SKILL.md` 或 `scripts/`）
- issue-code finding（E004/E005/E006/W007/W008/W009/W011/W012/W013/W014/W017/W018/W019/W020）
- 危險 shell 指令檢測（rm -rf、chmod 777、curl\|bash 等）
- 敏感路徑存取（/etc/、~/.ssh/、~/.aws/ 等）
- 可疑下載網址與無法驗證的外部依賴
- 敏感資料模式（API key、token、password patterns）
- 檔案大小與數量異常檢測

### 社群回報（MVP 含）
- 使用者可對任何已上架 skill 提交問題回報（flag）
- 回報類型：安全疑慮 / 品質問題 / 描述不符 / 其他
- 累積一定 flag 數量自動觸發複查

---

## Decision Log

| # | 決策 | 選擇 | 理由 | 排除的替代方案 |
|---|------|------|------|---------------|
| D1 | 產品定位 | 企業內部技能市集 / Registry | 公司內部需求，不需和公開市場競爭 | 公開市集（已有 SkillsMP、Glama） |
| D2 | 技能格式 | SKILL.md（agentskills.io 標準） | 30+ 工具採用的開放標準，跨工具可攜性最強 | 自定義格式（鎖定風險）、MCP Server（互補不競爭，後續擴展） |
| D3 | 儲存架構 | Object Storage (GCS) + DB (PostgreSQL 16 + pgvector) | 多租戶權限控制容易、搜尋統計方便、運維可控（Phase 1 從 Firestore 遷移；見 ADR-001） | Git-backed（多租戶管理成本高）、Git + Registry API（兩套系統一致性問題） |
| D4 | 認證模型 | OAuth Server 整合（Backlog） | 只管 OAuth 協議，不管上游是 SSO 還是帳密，解耦乾淨 | 自建帳號系統（多餘）、SSO 直接整合（綁定特定 IdP） |
| D5 | 安全模型 | 分級制度 + 自動風險評估 + 社群回報 | 低風險自動通過減少摩擦，高風險自動標記保障安全，社群回報補漏 | 純自動掃描（會漏判）、純人工審核（太慢） |
| D6 | 前端框架 | React 19 SPA，打包後放入 Spring Boot static resources，單一 container 部署 | 生態最大、元件庫豐富、UI 互動豐富度高 | htmx（模板預設，互動豐富度有限）、Vue、Angular |
| D7 | 後端框架 | Spring Boot 4.0.6 + Gradle 9.4.1 (Kotlin DSL) + Java 25 | 專案模板已配置、最新穩定版、GraalVM Native 支援 | Spring Boot 3.5.x（舊版）、Maven（模板用 Gradle） |
| D8 | 資料庫 | PostgreSQL 16 + pgvector（Spring Data JDBC + 自訂 `SkillshubPgVectorStore`；GCP 部署採 Cloud SQL Auth Proxy sidecar） | Row-level ACL（JSONB + GIN）+ 向量 + 一般查詢統一 SQL；Phase 2 ACL × 向量整合需要無上限 array filter（ADR-001 §3.1：Firestore `array-contains-any` 30 元素硬上限） | Firestore Enterprise（ACL 表達力天花板）、純 MongoDB Atlas（多一層供應商） |
| D9 | 語意搜尋 | Spring AI（core artifact）+ Gemini embedding + 自訂 `SkillshubPgVectorStore`（HNSW 索引 + cosine distance） | 與 D8 統一；ACL × vector 一條 SQL 同時 GIN filter + HNSW 排序（ADR-001 §3.2） | Firestore `findNearest()`、Vertex AI Vector Search（$65-100/月）、Cloud SQL pgvector starter（4-欄 INSERT 不支援 `owner` 自訂欄位） |
| D10 | 部署方式 | GCP Cloud Run (Container) | 免管 K8s、auto-scale、跟 GCP 服務整合好、MVP 最輕量 | GKE（太重）、App Engine（彈性不足） |
| D11 | 安裝方式 MVP | Web 下載（zip） | 最簡單、無需額外工具、所有使用者都能用 | CLI（需額外開發安裝）、深度連結（需本地 app 配合） |
| D12 | 權限控制 MVP | 資料模型先設計，功能不啟用 | 先讓核心流程跑起來，認證整合後再啟用權限，避免 MVP 過重 | 直接做完整權限（開發成本高、拖慢 MVP） |
| D13 | 組織模型 | 硬結構（樹狀）+ 軟結構（彈性團隊） | 涵蓋企業正式組織和跨組織協作兩種真實場景 | 純扁平（無法表達企業層級）、純樹狀（無法表達跨組織協作） |
| D14 | DB 存取方式 | 統一 Spring Data JDBC（CRUD + event store JSONB payload）+ 自訂 `SkillshubPgVectorStore extends AbstractObservationVectorStore`（向量 6-欄 atomic INSERT；含 `owner` / `skill_id` 自訂欄位） | 單一連線池 / 單一 transaction 模型；不再混 wire protocol；對齊 Spring AI Manual Configuration 原則（S014 archived §2.1 決策 #2/#12） | 混用 driver、官方 PgVectorStore starter（`owner` 欄位需 add + UPDATE 兩步驟、中間視窗 `owner=NULL` observable） |
| D15 | 架構風格 | Spring Modulith（模組化單體） | 模板已配置、模組邊界清晰、可獨立測試、未來可拆分微服務 | 微服務（MVP 太重）、傳統分層（模組邊界模糊） |
| D16 | AI 框架 | Spring AI 2.0.0-M4 | 模板已配置、跟 Spring Boot 生態整合、支援 Gemini/Vertex AI | 直接呼叫 Gemini API（無 Spring 整合）、LangChain4j（生態較小） |
| D17 | 可觀測性 | OpenTelemetry + Grafana LGTM | 模板已配置、業界標準、traces/metrics/logs 統一 | 自建 logging（不完整）、Datadog（付費） |
| D18 | UI 元件庫 | shadcn/ui + Beam (border-beam) | shadcn/ui 跟 Tailwind + React 整合最好、Beam 提供動畫邊框效果 | Ant Design（較重）、MUI（Material 風格衝突） |
| D19 | API 文件 | SpringDoc OpenAPI 3.0.2 | 模板已配置、自動產生 API 文件、Swagger UI | 手動寫文件（維護成本高） |
| D20 | 後端架構 | Event Sourcing + CQRS（核心領域）| Skill 生命週期天然適合事件驅動、完整審計軌跡、讀寫分離 | 傳統 CRUD（無事件歷史）、Full ES 全領域（MVP 太重） |
| D21 | ES 實作方式 | Spring Modulith Events + 自建 Event Store | 輕量、跟模板整合好、不多引入框架 | Axon Framework（重量級、學習曲線高）、Emmett（生態小） |
| D22 | Event Store 位置 | 同 PostgreSQL 的 `domain_events` 表（JSONB payload + per-aggregate `(aggregate_id, sequence)` UNIQUE） | 與 read model 同 DB / 同 transaction；query 簡單；無額外基礎設施 | 獨立 DB（多一套系統）、per-aggregate table（管理複雜） |
| D23 | ES MVP 範圍 | 僅儲存事件 + 更新 projection | 最小可行、後續可擴展 replay/snapshot | Full ES（replay、snapshot、upcasting 放 Backlog） |
| D24 | 專案目錄 | `backend/`（原 `skillshub/`）+ `frontend/` | 前後端分離目錄、語意清晰 | 單一目錄（前後端混在一起） |
| D25 | URL schema | `/skills/:id` canonical identity + `/skills/:author/:name` legacy deterministic alias（ADR-003 amended by S176） | S176 允許同一作者多筆同名 skill，ID 才能唯一識別；author/name alias 保留既有 caller 並回 deterministic latest row | 只留 author/name（重名不可唯一定位）、hard redirect（增 round-trip + cli tool 可能不 follow） |
| D26 | UI 主題 | dark theme（`#08080A` bg + `#EEECEA` ink；per Engineering Handoff §7） | 對齊 v2 prototype 16 mockup；engineer-tier 工具的視覺慣例 | warm-white 維持（既有 v1）、dual-theme switcher（維護 2× cost） |
| D27 | Risk tier 階數 | 4-tier (NONE/LOW/MEDIUM/HIGH) per ADR-future / S096c | 對齊 Cisco Skill Scanner + CVSS None band；分清「0-finding pure docs」vs「low-severity finding」 | 3-tier（既有，把 0-finding 與 low-finding 混為 LOW，user 看不到差異） |

> **Phase 1 PostgreSQL migration（2026-04-27 v1.1.0）**：D3/D8/D9/D14/D22 已重寫；遷移決策軌跡見 [`adr/ADR-001-postgresql-migration.md`](./adr/ADR-001-postgresql-migration.md) + [`specs/archive/2026-04-27-S014-postgresql-migration.md`](./specs/archive/2026-04-27-S014-postgresql-migration.md)。其他 D-entry（D1/D2/D4-D7/D10-D13/D15-D21/D23-D24）不受 Phase 1 影響。

---

## Glossary

See [glossary.md](./glossary.md)
