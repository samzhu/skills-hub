# S084 — UI Rework META spec（DESIGN.md → frontend 全套用）

> **Status**: in-design (META spec — splits into S085-S089 sub-specs)
> **Estimate**: M / 12 pts (rollup)；single ship 為 XL → mandatory split
> **User-driven**: 「參考 DESIGN.md 設計語言優化畫面...原始設計師畫的 ./docs/prototype 蠻完整的」+「border-beam 原生效果不錯，你用就沒那麼好看，研究一下」+ jakubantalik playground 截圖
> **Foundation already shipped**: S081 (tokens) + S082 (Files tab) + S083 (BorderBeam first-pass)

---

## §1 Goal

把 DESIGN.md 的設計語言（warm off-white surface + purple accent + 完整 4-tier semantic + 6 category tints + Inter 字體 + 4-5s 慢轉 beam）**全面套用**到 frontend 4 個 implemented page，視覺對齊 `docs/prototype/*.html` 12 個設計稿中的 4 個對應頁。

**範圍**：
- ✅ HomePage（對 `skills_hub_homepage_mockup.html`）
- ✅ PublishPage（對 `skill_publish_upload_flow.html` + `skill_publish_failure_and_high_risk_states.html`）
- ✅ SkillDetailPage（對 `skill_detail_page_docker_compose_helper.html`）— 已有 Files tab (S082)
- ✅ AnalyticsPage（對 `platform_analytics_dashboard_admin_view.html`）
- ✅ BorderBeam 深度 tuning（對 jakubantalik playground 觀感）

**範圍排除**（user 已明示）：onboarding wizard / docs page / admin queue / landing page / empty state / my-skills dashboard / semantic search standalone page — 8 個 prototype 對應頁尚未 implement，不在本批次。

**一句話**：套上 DESIGN.md，每頁長得像 `./docs/prototype` 設計稿，beam 像 jakubantalik playground 一樣有質感。

---

## §2 Approach

### §2.1 Decomposition rationale (XL → split)

6 維 estimate：tech_risk=2 / uncertainty=3 / dependencies=1 / scope=3 / testing=2 / reversibility=1 → 12 (M-)，但 **scope=3 導致 single-ship 不可行**（≥4 page rework + 1 component deep-tuning）。Per planning-spec skill: "XL = mandatory split"。本 spec 為 META，拆 5 個 sub-specs 排隊執行。

### §2.2 Key research finding — BorderBeam light theme **物理不可避免**

Sub-agent inspect `frontend/node_modules/border-beam/dist/index.es.js` source 發現：

| ThemeColors | dark | light |
|---|---|---|
| strokeOpacity | 0.48 | 0.33 |
| innerOpacity | 0.70 | 0.46 |
| bloomOpacity | 0.80 | 0.54 |
| innerShadow | rgba(255,255,255,0.27) | **rgba(0,0,0,0.14)** |
| saturation | 1.20 | 0.96 |

**根本問題**：Light theme 用 `rgba(0,0,0,x)` 黑色透明 inner-shadow。在 #FFFFFF 白背景上，黑色透明只能產生陰影、無法產生 glow（光感）。Dark theme 在深背景用白色 rgba(255,255,255,x) 才有「光」的視覺。物理對比度不對稱。

**Prototype 的解法**：`skills_hub_homepage_mockup.html` `.sh-search-wrap` **不用 border-beam npm package**，直接 hand-roll conic-gradient：
```css
.sh-search-wrap {
  border-radius: var(--border-radius-lg);
  padding: 1px;
  background: var(--color-border-tertiary);  /* #E0DDD3 — 比 surface 暗一階 */
  overflow: hidden;
}
.sh-search-wrap::before {
  inset: -50%;
  background: conic-gradient(from 0deg, transparent 0deg 300deg, #7F77DD 330deg, #378ADD 345deg, transparent 360deg);
  animation: sh-spin 4s linear infinite;
}
```

關鍵差別：
1. 1px padding 露出 conic-gradient ring（DESIGN.md `card-featured` pattern）
2. 背景 `#E0DDD3`（比 #FFFFFF 暗一階）讓 beam 有對比空間
3. 只有 60° 弧（330-360°）可見，產生「掃描光」感
4. 直接用 hex 色值 `#7F77DD` (accent) → `#378ADD` (info) 漸變，不繞 ThemeColors

### §2.3 Approach comparison（BorderBeam tuning，S089 用）

| 路徑 | Pros | Cons | 推薦 |
|------|------|------|------|
| **A: 覆蓋 brightness/saturation** | 保留 npm package；單一檔案改動；5 分鐘 ship | 黑色透明陰影物理限制無解；只能勉強提升「亮度」但對比度天花板低；本質是「在錯誤的 theme 上補 tone」 | |
| **B: Hand-roll per prototype** | 1:1 對齊 DESIGN.md `card-featured` pattern；明確、可控；移除一個 dep（border-beam）；prototype 已驗證 | 自己維護 conic-gradient CSS；失去 npm package 的 size variants（sm/md/line）但本案不需要 | ⭐ Recommended |
| **C: theme="dark" + 暗化容器** | 利用 npm package 的 dark theme（最炫）；不改 DESIGN.md surface | 把 SearchBar 變成「白色搜尋框配深色背景」與 DESIGN.md warm off-white 矛盾；視覺奇怪 | |

**推薦 B**：DESIGN.md 是 source of truth；prototype 已驗證 hand-roll 在 light surface 上效果好；放棄 npm dep 換取 1:1 對齊。

### §2.4 Approach for per-page reworks (S085-S088)

四個頁的 rework 共用模式：
1. 讀對應 prototype HTML，提取 layout + classes + 動畫
2. 對齊 DESIGN.md spec：色彩用 token / 半徑用 scale / spacing 4px-base / 字體 Inter
3. 保留現有 React Query hooks + business logic；只重組 JSX + Tailwind classes
4. icon-tile / pill / callout / stepper 等 DESIGN.md component 第一次出現的 sub-spec 同步抽 reusable component（後續 sub-specs 共用）
5. 視覺 smoke：Chrome 截圖比 prototype HTML，pixel-perfect 不要求但 layout/色彩/字體要對

---

## §3 Acceptance Criteria (rollup; sub-specs 各自 expand)

| AC | 對應 sub-spec | Verify Command |
|----|---------------|----------------|
| **AC-1**：HomePage（瀏覽 + 搜尋）視覺對齊 `skills_hub_homepage_mockup.html` | S085 | `npm test && npm run build` + Chrome 截圖比對 |
| **AC-2**：PublishPage 上傳流程 + 失敗 / HIGH 風險 state 對齊 `skill_publish_*.html` | S086 | 同上 |
| **AC-3**：SkillDetailPage 對齊 `skill_detail_page_docker_compose_helper.html`，Files tab (S082) 視覺整合 | S087 | 同上 |
| **AC-4**：AnalyticsPage 對齊 `platform_analytics_dashboard_admin_view.html` | S088 | 同上 |
| **AC-5**：SearchBar BorderBeam 達 jakubantalik playground 質感（user 主觀認可） | S089 | Chrome 截圖 + user signoff |
| **AC-6**：既有 11 frontend tests / 0 fail；無 backend 改動 | (all sub-specs) | `cd frontend && npm test` |
| **AC-7**：DESIGN.md tokens 100% 套用（沒有 hard-coded hex 在 component code 中） | (all sub-specs) | `grep -rE "#[0-9a-fA-F]{3,6}" frontend/src/{components,pages}` 應 ≈ 0 hits（CSS modules 例外） |

**Verification**：`cd frontend && npm test && npm run build`（per qa-strategy QA pipeline）

---

## §4 Sub-spec roadmap

| Spec | Topic | Size | Order | Notes |
|------|-------|------|-------|-------|
| **S085** | HomePage rework + 共用 components 抽取（IconTile / Pill / Callout） | S(8) | 1st | 入口頁；定義 reusable patterns |
| **S086** | PublishPage rework | XS(5) | 2nd | upload flow + failure / HIGH risk state（後者直接套既有 S082 risk path） |
| **S087** | SkillDetailPage rework | S(7) | 3rd | 含 Files tab (S082) 重新設計成 prototype style |
| **S088** | AnalyticsPage rework | XS(5) | 4th | metric cards + sparkline + activity feed |
| **S089** | BorderBeam hand-roll per prototype（drop border-beam dep） | XS(3) | 5th | 可隨 S085 一起 ship（HomePage SearchBar 用） |

**執行順序理由**：S085 先 — HomePage 是入口，且建立的 reusable components（IconTile/Pill/Callout）後續 sub-specs 共用。S089 與 S085 同 batch ship 因為 SearchBar 在 HomePage。S086-S088 平行可，視 user 優先序。

**累計**：5 sub-specs / 28 pts（M+），跨 5 個 cron tick 預計可完成。

---

## §5 File Plan

本 META spec **不直接改 production code**。只產出文件：
- `docs/grimo/specs/2026-05-01-S084-ui-rework-meta.md`（本檔）
- 5 個 sub-spec stub 由 `/planning-spec [S085|S086|...]` 產出（M+ 規範各自設計）
- `docs/grimo/specs/spec-roadmap.md` 更新：加 5 個 backlog rows

每個 sub-spec 自己定 file plan（component / page / test 改動）；本 META 只 list 共用 components 候選：

| Component | 第一次出現 sub-spec | 用於 |
|-----------|---------------------|------|
| `IconTile` (sm/md/lg/xl, 6 category tint) | S085 | 所有 page 的 skill row icon |
| `Pill` (low/med/high risk + pending/published/verified/draft) | S085 | risk badge + status badge |
| `Callout` (info/warn/danger × soft fill) | S085 | suspended banner / 各頁 inline notices |
| `Stepper` (done/active/future + line color) | S086 | publish flow（如有 multi-step）/ onboarding 預留 |
| `ActivityFeedRow` (6px 色點 + 內容 + 相對時間) | S088 | analytics dashboard / 後續 audit log |
| `BeamFrame`（hand-roll conic-gradient 替代 BorderBeam） | S085+S089 | SearchBar / 後續 primary CTA |

---

## §6 Verification command

`cd frontend && npm test && npm run build`（既有 vitest + vite build pipeline）。各 sub-spec ship 時加 Chrome 視覺 smoke 對 prototype。

---

## §7 Result（待 sub-specs 全 ship 後填）

回填時記錄：
- 每個 sub-spec ship 的 commit hash + tick 編號
- 視覺對齊 confidence（user signoff snapshot）
- 累計 frontend 改動行數 + 新 component 數
- 任何發現的 prototype 不可達邊界與 trade-off

---

## §8 Doc Sync

- [ ] PRD scope 不變（UI 改造不涉產品邊界）
- [ ] spec-roadmap.md 加 5 個 backlog rows（S085-S089）
- [ ] architecture.md frontend 部分加 hand-roll BeamFrame 設計決策（S089 ship 時）
- [ ] development-standards.md 加「component DESIGN.md token 引用準則」（S085 ship 時，第一次抽 reusable component）

---

## Handoff

User，本 META spec 寫完。確認方向 OK 後即可：
- `/planning-tasks S085` 啟動 HomePage rework（1st sub-spec）
- 或 user 想先動哪一頁也可 jump（如先做 S087 SkillDetailPage 整合 S082 Files tab）

每個 sub-spec 仍會走完整 `/planning-spec [Sxxx]` 流程：自己 grill / 對應 prototype 細部 research / pixel-level interface 設計。本 META 只在 rollup level 做 split + 共識方向。
