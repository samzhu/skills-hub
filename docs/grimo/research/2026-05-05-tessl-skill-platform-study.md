# Tessl Skill Platform — 研究筆記

> **Date**: 2026-05-05
> **Trigger**: 使用者讀完 Tessl Registry 後要求研究其 UI/UX、Quality 評分、Evaluation results、Security 視覺，作為 Skills Hub 設計參考
> **Sources**:
> - <https://docs.tessl.io/>
> - <https://docs.tessl.io/llms.txt>
> - <https://docs.tessl.io/evaluate/evaluating-skills>
> - <https://docs.tessl.io/evaluate/evaluate-skill-quality-using-scenarios>
> - <https://docs.tessl.io/create/creating-skills>
> - <https://tessl.io/registry/skills/github/a-pavithraa/springboot-skills-marketplace/code-reviewer> (含 SKILL.md / Quality / Evals / Security 四 tab)
> - <https://tessl.io/registry/tessl/skill-optimizer>
>
> **Consumers**:
> - **S135 META Skill Quality Score System (Tessl-aligned)** — Quality 評分（in-design，本 note §3 §6 為主要 input）
> - **S136 Skill Evaluation Scenarios** — Backlog 待討論（task-based eval；本 note §4 §6 為主要 input）
> - **未來 UI rework spec** — hero 三條進度條、SKILL.md 渲染、Last updated/Created sidebar、install 套件管理器下拉、4 tabs（本 note §2 §5 為主要 input）
>
> **取代**: 部分取代 S101 META `§2 Competitive Research：Tessl Skill Optimizer`（粗粒度 4-dim 描述）— 此 note 為更深度版本（8-dim + scenarios 結構 + UI 拆解）

---

## §1 Tessl 平台核心定位

> **One-liner**: Tessl 把 agent skills 視為「需要 SDLC 工具鏈的軟體產品」 — versioning / quality checks / dependency management / continuous validation。

- **Agent context management platform** — 解決 agents 因 framework 演進而失效；根因不在模型，在 context 的 build → evaluate → distribute → optimize 生命週期沒人管
- Registry 規模：3,000+ skills，10,000+ OSS packages docs 索引
- Context Bundles 涵蓋 imports / examples / conventions / common pitfalls，可應用於開源生態與企業私有工作區
- 借用「npm package + Snyk badge + GitHub Insights」混合視覺語言建立信任感

---

## §2 UI/UX 設計拆解

### §2.1 整體版面（從上到下五層）

```
┌─ Header（logo + 全站搜尋 ⌘K + Blog/Docs/Login/Get started） ─┐
├─ Breadcrumb: Registry / Skills / {owner} / {repo} / {skill}  ─┤
├─ Title block（左：H1 + 80字描述）  ┃  Right rail：Install CLI ┤
├─ Hero metrics（左：總分 95 hexagon + 1.23x delta）            ┤
│   └─ 三卡並列：Quality 92% / Impact 100% ↑1.23x / Security ✓ ┤
├─ Tabs（SKILL.md │ Quality │ Evals │ Security）── underline 動效 ┤
└─ 主內容（左 70-75%）+ Right sidebar（25-30%：metadata + CTA） ┘
```

### §2.2 Hero metrics（核心視覺武器）

- **總分 95**：六邊形 hexagon 框（蜂巢 → 「品質徽章」隱喻）+ `↑1.23x` delta tag
- **三條進度條**：
  - Quality：連續綠色填滿條 + 92%
  - Impact：連續條 + 100% 數字 + delta tag
  - Security：**4 段分段條**（每節綠色 = 4 個檢查維度都通過 — 與 Quality/Impact 連續條的視覺隱喻不同）
- **三卡都帶 sub-label**：「Does it follow best practices?」/「Average score across 3 eval scenarios」/「No known issues」 — 抽象指標翻譯成人話
- **第三方權威背書**：右上角 `Security by snyk` by-line

### §2.3 SKILL.md tab 渲染（為何讀起來舒服）

| 設計武器 | 細節 |
|---|---|
| 超大 H1 | 72-80px 級 + 大量留白 |
| 單欄 prose 寬度 | 限制 ~720-760px（最佳閱讀行寬 65-75 字元） |
| 層級對比 | H1 巨大 + H2 中粗 + bullet 細灰 — 三層就夠 |
| 主內容無 sidebar 擠壓 | 右側 metadata 用淡灰、低資訊密度 |
| Dark mode + 純黑底 | `prose-invert` style，markdown 自帶質感 |

### §2.4 Sidebar metadata（每個 tab 都有）

- Repository（GitHub icon + `owner/repo`）
- Commit（縮寫 hash + hover 連到 commit）
- Last updated / Created（相對時間 `about 1 month ago`）
- **Is this your skill?** Claim CTA（白底黑字 primary button）
- Related skills（bundle 內其他 skill）

各 tab 額外加：
- Quality tab → **Table of Contents**（章節錨點）
- Evals tab → Evaluated time / **Agent: Claude Code** / **Model: Claude Sonnet 4.6**
- Security tab → Audited time / **Security analysis: snyk logo**

### §2.5 Install 區塊（top-right rail）

```
Install with Tessl CLI         [npm ▼]
┌───────────────────────────────────┐
│ npx tessl i github:a-pavithraa…  📋│
└───────────────────────────────────┘
What are skills?  ← 教育型 link
```

- 套件管理器下拉（npm / pnpm / yarn / bun）一鍵切換 install 指令
- 複製按鈕永遠在右上角
- 「What are skills?」 onboarding 教育鑲在 install 點

---

## §3 Quality 評分系統（精確版）

### §3.1 官方三大評分類別

> 來源：<https://docs.tessl.io/evaluate/evaluating-skills>
> **Review Score = Weighted average of the three sub-components**

| 類別 | 評分方式 | 評估什麼 |
|---|---|---|
| **Validation** | 結構檢查（rule-based） | 等同 `tessl skill lint` 的格式檢查 |
| **Implementation** | LLM-as-a-judge | SKILL.md **body** 的品質 |
| **Activation** | LLM-as-a-judge | SKILL.md **description** 的品質（agents 是否會在對的時機載入此 skill） |

> **UI 命名差異**：Tessl Registry UI 把 Activation 顯示為「Discovery」 — 對使用者更直白（「能不能被找到」）。CLI 與 docs 仍用 Activation。

### §3.2 三類各自的 sub-dimension

#### Validation（結構檢查 — 通過/失敗計數，例 11/12 Passed）

- `skill_md_line_count`（行數限制）
- `frontmatter_valid` + `name` / `description` / `compatibility` / `allowed-tools` 欄位檢查
- `metadata` / `license`
- `body_present` / `body_examples` / `body_output_format` / `body_steps`

#### Implementation（LLM judge — body 品質，每項 X/3）

1. **Conciseness**（冗餘度）
2. **Actionability**（具體可執行性）
3. **Workflow clarity**（步驟是否清楚）
4. **Progressive disclosure**（是否分層揭露 reference 檔）

#### Activation / Discovery（LLM judge — description 品質，每項 X/3）

1. **Specificity**（描述是否具體）
2. **Completeness**（有沒有同時答 what + when）
3. **Trigger Term Quality**（自然關鍵詞覆蓋）
4. **Distinctiveness / Conflict Risk**（與其他 skill 邊界是否清楚）

### §3.3 Quality tab 表格設計

```
Dimension          Reasoning（敘事段落）              Score
─────────────────────────────────────────────────────
Specificity        Lists multiple concrete actions… 3 / 3
Completeness       Clearly answers both 'what' and… 3 / 3
Trigger Term Q.    Includes strong natural keywords… 3 / 3
```

亮點：
- **每列都有「敘事 reasoning」** — 不只給分，給「為什麼」（LLM-as-judge 副產品）
- 分數 `3 / 3` 用綠色，未滿分用偏暗綠或黃色
- **Discovery 100%** badge 緊跟章節標題 → 一眼掃出強項
- 章節間大留白分隔（Discovery / Implementation / Validation 各為獨立 H2）

### §3.4 對 Skills Hub 的設計 takeaways（→ S135）

| Tessl pattern | Skills Hub 應對 |
|---|---|
| Validation 結構檢查 | 已有 `skill/validation/SkillValidator.java`，擴充對齊 Tessl Validation 項目（line count, body sections） |
| Implementation 4-dim LLM judge | 新建 `score/QualityJudge` Gemini 呼叫；prompt template 寫 4-dim rubric |
| Activation 4-dim LLM judge | 同上，rubric 改成 description-only judge |
| Reasoning 文字呈現 | judge 輸出 reasoning + score → 存 `skill_scores.details` JSONB |
| Weighted average → total | 三類權重待定（建議 Validation 20% / Implementation 40% / Activation 40%） |

---

## §4 Evaluation Scenarios（task-based eval；→ S136 Backlog）

### §4.1 Scenarios 目錄結構

> 來源：<https://docs.tessl.io/evaluate/evaluate-skill-quality-using-scenarios>

```
evals/
├── instructions.json              ← 全局設定
├── scenario-1/
│   ├── task.md                    ← 給 agent 看的任務簡報
│   ├── criteria.json              ← 加權 checklist rubric
│   └── capability.txt             ← 測 skill 哪個 capability
├── scenario-2/ …
└── scenario-3/ …
```

每個 scenario 對應 Evals tab 的一個區塊（例：「Order Management Service — Pre-Merge Code Review」/「ShopCore Product Catalog Service — Architecture Review」/「FinTrack User Management API — Security and Performance Assessment」）。

### §4.2 評估流程（Impact 分數來源）

```
1. tessl eval run 載入 scenarios
2. 對每個 scenario：
   a. Without context: agent 在「沒注入此 skill」下執行 task.md
   b. With context:    agent 在「注入此 skill」下執行 task.md
   c. Judge LLM 對兩次輸出依 criteria.json 評分
3. Impact = with/without 的相對提升（例 1.23x = 提升 23%）
```

### §4.3 Criteria 表格的視覺設計

```
Criteria                       Without context │ With context
File path citations    ⓘ            ✓ 100%    │    ✓ 100%
MockBean annotation flagged  ⓘ      ✓ 100%    │    ✓ 100%
MockitoBean replacement sug. ⓘ      ✓ 100%    │    ✓ 100%
Jackson 2 imports flagged    ⓘ      ✗ 0%      │    ✓ 100%   ← 關鍵差異
TestRestTemplate flagged     ⓘ      ✓ 100%    │    ✓ 100%
```

設計巧思：
- **Without context vs With context 並列** — 直接視覺證明 skill 有用
- 每條 criteria 後綴 ⓘ tooltip — hover 看 judge 如何評分
- 大標題 `100% ↑44%` + Details expand button — 預設摺疊只給總分，點開看細節
- scenario 副標題（`Spring Boot 4 migration review`）— 一行說明場景為何存在

### §4.4 sidebar reproducibility metadata

```
Evaluated:  about 1 month ago
Agent:      Claude Code
Model:      Claude Sonnet 4.6
Commit:     efa5546
```

> 信任的支柱：誰、何時、用什麼 model、commit 哪個版本。沒這些就只是空喊分數。

### §4.5 對 Skills Hub 的設計 takeaways（→ S136）

#### 子路 B1：Sandbox Runtime（與 Tessl 對齊但成本高）

需要：GCP Cloud Run jobs / Vertex AI Agents / 自管 sandbox container；per-eval 成本控管；agent model account（Claude Sonnet / Gemini Pro）。time-to-ship 長。

#### 子路 B2：Static Eval（推薦起手）

把 `criteria.json` 改成「期待 SKILL.md 出現的 keyword / pattern / structure」，靜態比對而非真跑 agent。優點：用既有 LLM judge stack 立刻能做；缺點：只能驗 SKILL.md 內容品質，無法驗 agent 實際表現。

#### Open questions 待 S136 討論

- 子路 B1 vs B2 取捨（與 Skills Hub MVP read-heavy 性質的權衡）
- Agent + Model 選擇（Claude Sonnet 4.6 / Gemini Pro / 開源）
- 每 skill 至少跑幾個 scenarios 才算可信（Tessl 案例為 3）
- Reproducibility — eval 結果如何 cache、何時 invalidate
- 成本上限 — per-skill / per-month eval 預算
- Judge 跟 Quality LLM judge 是否共用 (rubric 不同但 stack 可重用)

---

## §5 Security 視覺策略

### §5.1 「No issues found」hero（minimalism + 第三方背書）

```
┌─────────────────────────────────────┐
│            🛡️ (大綠色盾牌)           │
│      No security issues found       │
│        Scanned about 1 month ago    │
└─────────────────────────────────────┘
```

成功安全感四個設計武器：
1. 居中 + 巨量留白 — 訊息少代表「沒事」
2. 單一綠色強調 — emerald-500 系（飽和但不過亮）
3. 盾牌 icon 大尺寸 — 借用瀏覽器 padlock / Cloudflare 視覺語言
4. Snyk logo 在 sidebar — 第三方公信力背書

### §5.2 Hero 三卡的 Security 條（與 Quality / Impact 不同）

```
Quality:   ████████████████░░  92%        ← 連續進度條
Impact:    ████████████████████  100% ↑1.23x  ← 連續進度條
Security:  ████ ████ ████ ████  Passed   ← 4 段分段條
```

Security 用「分段條」而非連續條 — 巧妙隱喻：
- Quality / Impact 是程度問題（百分比連續）
- Security 是 binary 問題（每段檢查項要嘛通過要嘛沒通過）
- 4 段全綠 = 4 個檢查維度都過 → 比單一進度條更有「逐項把關」的安全感

### §5.3 對 Skills Hub 的設計 takeaways

Skills Hub 的 Security 比 Tessl 重要 100 倍 — 員工會把 skill 注入 internal codebase。建議：
1. 抄這個「綠盾 hero + 分段條」視覺
2. 但**自製檢查項目**：除 dependency CVE，還要查 SKILL.md 中可疑指令（curl|sh、寫 ~/.ssh、外部 webhook 等）
3. 第三方背書可改成「IT Security 部門 Approved」
4. 既有 4-level RiskLevel (NONE/LOW/MEDIUM/HIGH per S096c) 已比 Tessl 二元 Pass/Fail 細
5. OWASP LLM05 dependency scanner (S099e3) 是 Tessl 對應 Snyk 的位置

---

## §6 整體設計藍圖（為 Skills Hub 規劃）

### §6.1 對應到 spec 路線圖

| Tessl 功能 | Skills Hub spec 對應 |
|---|---|
| SKILL.md 渲染頁 | 既有 `frontend/src/pages/SkillDetailPage.tsx`（待 UI rework spec） |
| Validation 評分 | `skill/validation/` module（已存在，→ S135 擴充） |
| Implementation/Activation LLM judge | 新 module `score/`（→ S135） |
| Evaluation results | → **S136 Backlog 待討論** |
| Security tab | 既有 risk_level mapping + S099e3 SBOM scanner |
| Install CLI | 後續 spec（CLI 安裝工具，目前 MVP Out of Scope） |

### §6.2 最關鍵的設計原則

> Tessl 的 UI 不是因為設計師厲害才好看 — 是因為它**把每個指標都對應到一段「為什麼可信」的證據鏈**。

- Quality 95 → 點進去看 LLM judge 的 reasoning
- Impact 100% ↑1.23x → 點進去看 with/without context 對照
- Security Passed → 看 Snyk audit hash + 時間

**沒有空指標**。這是企業內部 skill registry 與一般 markdown wiki 的關鍵差異 — 每個分數都可被追溯到證據。這個 evidence-chain 架構決定，比任何 UI 細節都重要。
