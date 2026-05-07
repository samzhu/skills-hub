# Skill Detail v2 — 設計師補齊事項

> **對應 prototype**：`Skills Hub Skill Detail v2.html`
> **對應工程說明**：使用者已提供完整的「Skill Detail Page v2 工程實作說明」（配色雙語言 / Skill Score 公式 / Hero metrics / Tabs / Quality 資料結構 / Security 資料結構 / BorderBeam 規範等）
> **對應 spec**：S142（SkillDetailPage v2 完整實作 — 待 spec 草稿）
> **目的**：列出工程在 spec planning 階段盤點 v2 prototype 與生產 / backend 資料 delta 後，**仍需設計師補齊定義**的項目。
> **更新日期**：2026-05-07（含 Files explorer 設計師交付更新）

---

## 狀態總覽

| # | 項目 | 狀態 |
|---|---|---|
| Q1 | Verified pill 語義 | ✅ 已答（待最後確認 D1）|
| Q2 | Star 按鈕意義 | ✅ 已答（= Subscribe 換 icon）|
| Q3 | Files tab 去留 | ✅ **已答 + 設計師 2026-05-07 交付**：保留為第 7 tab + 新 split-pane explorer 設計 — 見 §A |
| Q4 | Skill Score 公式 | ✅ 已答（採工程說明的 `0.6Q + 0.4S`）|
| Q5 | Empty / edge / SUSPENDED 狀態 | ⚠️ 工程暫用 default，設計師可後續 polish — 見 §B |
| Q6 | Quality / Security 低分視覺 | ⚠️ 工程暫用 default，設計師可後續 polish — 見 §B |
| Q7 | CLI dropdown 選項 | ✅ 已答（先放單一靜態 CLI label）|

---

## §1 已答覆事項（記錄給未來查考）

### Q1：Verified pill 語義
- 「掃過 + 上架後」即視為 verified
- 對應規則：`verified = (status === 'PUBLISHED' && riskLevel != null)`
- 含 MEDIUM/HIGH 也算 verified（Risk pill 另獨立顯示風險等級）
- 語義意義：「我們確實檢查過」≠「無風險」

### Q2：Star 按鈕
- = 既有 Subscribe（S125）功能，純換 icon 為 ⭐
- 行為：toggle subscribe / unsubscribe，訂閱者收新版本通知
- 既有鈴鐺 icon 從 PageHeader 拿掉

### Q3：Files tab → 設計師決議保留為 7th tab + split-pane 重做
- 最終 7 tabs：SKILL.md / Quality / Versions / Reviews / Security / Flags / **Files** (badge 顯 file count)
- Files panel 採 split-pane explorer：左 220px file tree + 右 1fr preview pane
- `scripts/` 目錄特殊視覺（amber border + 「security scan」徽章）— 詳 §A

### Q4：Skill Score 公式
- 採工程說明的 `round(qualityScore × 0.6 + securityScore × 0.4)`
- `securityScore`：pass=100 / 1 warn=75 / 2 warn=50 / any fail=25
- 後端計算（不在 frontend 算）— SkillCard list 也要顯示，避免 N+1

### Q7：CLI dropdown
- 目前先放單一靜態 `CLI` label（無下拉行為）
- 「What are skills?」link → `/docs/your-first-skill`（既有 S094d）
- 未來擴充（npm / pnpm / yarn / bun 等）留後續 spec

---

## §A Files Explorer ✅（設計師 2026-05-07 已交付於 v2.html）

### 最終決議

設計師選擇**保留 Files 為第 7 tab**，但用全新 split-pane explorer layout 取代生產 S082 既有的 FilesPanel 列表。理由：tab 結構直觀、不需學新交互、適合深 navigation（樹 → 預覽 → 切換）。

### 工程實作摘要

- **Layout**: `display:grid; grid-template-columns:220px 1fr; height:calc(100vh - 300px); min-height:400px; border:.5px solid var(--line); border-radius:var(--r-lg)`
- **左 file tree** (220px):
  - folder 可展開/收合（`ftToggle()`），檔案項點擊載入預覽（`ftSelect()`）
  - active 項 accent 色 border-left + `rgba(127,119,221,.1)` 背景
  - file size 右靠右顯示
- **右 preview pane** (1fr):
  - header (path + 語言 badge) + scroll body (`<pre>` mono font + line-height 1.75)
  - syntax token classes：`tok-key` `tok-str` `tok-num` `tok-kw`
- **scripts/ 安全焦點**:
  - 目錄：`.ft-scripts-dir` amber `border-left` + 「security scan」徽章
  - 內含檔案：`.ft-in-scripts` 淡 amber 背景
  - 預覽時頂部加 `.ft-security-banner` amber banner
- **Binary fallback**: `.ft-binary` 置中 empty state（icon + 提示文字）

### 後端對齊（無新需求）

- `GET /api/v1/skills/{id}/files` (S074) 回 `[{ path, size, type }]` — 構建 file tree
- `GET /api/v1/skills/{id}/files/{*path}` 回單檔內容（text / binary）
- file-extension → 語言 badge 純 frontend mapping（.md=Markdown / .yml=YAML / .sh=Bash / .py=Python 等）

### 工程注意事項

- 既有 `FilesPanel` (S082) 元件廢棄、改寫為新 `FileExplorerPanel`
- syntax highlighting library 選擇：建議 `shiki` 或 `prism-react-renderer`（如 frontend 既有 stack 沒裝，加進依賴需 +1 task）
- `scripts/` path detection：`path.startsWith('scripts/')`
- File tree default 展開狀態：所有 folder 預設展開（per prototype 行為）

---

## §B 工程暫用 default，設計師可後續 polish

以下工程在 spec §4 寫了 fallback default，設計師有想改可在 polish spec 提案。

### B.1 Empty / edge / SUSPENDED 狀態（prototype 只畫 happy path）

| 情境 | 工程 default |
|---|---|
| 未評分 skill（Quality 404）| SKILL SCORE hexagon 顯「—」+ 下方小字「評分計算中」；Quality card 顯灰色 + 「評分計算中」（reuse S135b QualitySection fallback）；Security card 用 risk_level 直接顯 |
| SUSPENDED skill | hero 三卡正常顯；Download CTA 消失（既有 S028 行為）；Security tab 加紅色 banner |
| DRAFT skill | 同 SUSPENDED 但 banner 文案「未發佈」 |
| 沒 reviews | stat-strip Rating 格顯「—」；Reviews tab empty state（既有 S098e2 處理）|
| 沒 download 資料 | Sparkline 顯「—」；stat-strip Downloads 顯「0」|
| Open flags > 0 | stat 數字顯紅色，不額外提示（連 Flags tab 自然）|
| compatibility 8+ runtimes | flex-wrap 折行，不做「+N more」|
| Version 50+ 筆 | Sidebar 顯 latest 4 + 「查看全部」link 切到 Versions tab |
| 長 description（>200 字）| page-head desc line-clamp-2 + 「展開」 |

### B.2 Quality / Security 低分視覺

| 情境 | 工程 default |
|---|---|
| Quality 整體 < 60 時 hero 漸層 | 仍用 accent 漸層（不能跟風險語義色混用）；數字 label 用 amber/red text |
| Quality axis section < 60% | section header 數字 amber；progress bar 仍 accent 漸層 |
| Security 出現 fail | hero shield 紅色 X icon（per 工程說明）；header 文案「N issues require attention」 |

---

## §C 等使用者最後拍板（2 題）

> 工程目前已經把這兩題用合理 default 寫進 spec 草稿。如果設計師有意見可在 review 階段提，但不擋 spec 進入 task plan 階段。

### D1：Verified pill 範圍
- 工程採：`verified = (status === 'PUBLISHED' && riskLevel != null)`
- 包含 MEDIUM/HIGH risk 也算 verified（Risk pill 另獨立表達風險等級）
- 語義：「我們確實檢查過」≠「無風險」

### D3：Quality 404 fallback
- 工程採：SKILL SCORE hexagon 顯「—」+「評分計算中」字
- 不採 0 分（會誤導為「品質低」，實際是「還沒評」）
- Reuse S135b 既有 null/loading/404 fallback pattern

---

## 接下來流程

1. 使用者最終確認 §C 兩題（或同意 default）
2. 工程 dispatch Phase 2 research（Security 4-quad ruleId prefix grouping、license/compat surfacing 策略、syntax highlighting library 選擇）
3. 工程寫 S142a frontend spec + S142b backend supplement spec（拆兩 spec 並行）
4. S142a 含新 `FileExplorerPanel` task（取代既有 S082 `FilesPanel`）
