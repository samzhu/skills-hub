# Skills Hub 成效摘要

我們用 26 天、約 47.40 億 tokens 的 AI 開發投入，等價成本
$3,362.38，把企業級 AI Agent Skills Registry 從產品概念推進到
2208 點專案成果，平均每週交付 594.5 點。

換句話說：約 3,400 美元，交付一套能發佈、搜尋、審核、下載、
管理權限、跑 E2E 驗證，並持續部署修正的 AI Agent Skills Registry。

## 核心數字

| 指標 | 數值 |
| --- | ---: |
| 開發期間 | 2026-04-24 至 2026-05-19 |
| 總天數 | 26 天 |
| Token 投入 | 4,740,430,679 tokens（約 47.40 億） |
| Token 等價成本 | $3,362.38 |
| 專案成果 | 2208 story points |
| 每週交付 | 594.5 story points |
| 每點成本 | $1.52 |

## 2208 點怎麼算

2208 點是以 MVP complexity-only Fibonacci story point deck 先為 repo
所有 spec records 分配 story points，再排除沒有執行的 records 後得到的
成效結算點數。

每個 SpecID 都仍會分配 story points，方便盤點完整工作量；但成效摘要只把
已執行的 records 算進成果。`cancelled`、`deferred`、`other` 不進成效結算；
`shipped`、`superseded`、`META`、rolled-up child，以及 roadmap 有列但沒有
archive spec 檔、但狀態可判定已執行的 records 仍會計入。

目前正式點數只使用：

```text
1, 2, 3, 5, 8, 13, 20
```

`20` 只保留給 parent / rollup 工作包，例如 `S014`、`S147`、`S160`、
`S161`、`S163`、`S164`。一般單一 spec 即使很大，上限也落在 `13`，
代表它應該被拆分前不再繼續膨脹。

Python 腳本會完整讀取 `docs/grimo/specs/spec-roadmap.md` 與
`docs/grimo/specs/archive/`，每個 SpecID 只算一次，並用以下 complexity
dimensions 判斷 story points：

- implementation surface
- state and contract
- integration surface
- verification effort

MVP 口徑只看實作複雜度，不因 failure cost、business risk、reversibility
risk、研究主題不確定、或成熟工具名稱本身加點。Spring、React、Playwright、
Cloud Run、Docker、Testcontainers 等固定技術棧只在真的新增 setup、
implementation、fixture 或 verification work 時才計入。

結算資料：

- 腳本：`tools/calculate_story_points.py`
- 每個 spec 的結果：`docs/grimo/specs/story-points-2026-06-02.json`
- source coverage：252 個 spec 檔、4,482,923 bytes、79,657 lines。
- repo 所有 spec records：286 個 records，2291 assigned story points。
- 不進成效結算：13 個 records，83 story points。
- 成效結算 records：273 個 records，2208 story points。
- repo shipped records：239 個 records，2012 story points。
- excluded / non-shipped records：47 個 records，279 story points。
- marketing date range 內所有 records：266 個 records，2210 story points。
- marketing date range 內成效結算 records：257 個 records，2136 story points。
- marketing date range 內 shipped records：235 個 records，1965 story points。

實際例子：

- `S079` 是 1 點：只修暫停技能時的錯誤訊息。
- `S074` 是 5 點：新增 Skill 檔案瀏覽 API。
- `S189` 是 8 點：驗證 `/browse` 搜尋入口與 Playwright request contract。
- `S188` 是 13 點：版本標籤可留空、自動流水號、前後端與驗證一起完成。
- `S014` 是 20 點：PostgreSQL 資料層遷移，且吸收了 `S015` 範圍。

## Token / Cost 口徑

Token 與 cost 由本機 `npx ccusage@latest` 重新查詢，版本是
`ccusage 20.0.6`。因為期間內有 Claude 其他專案資料，最終採用：

```text
all agents daily - all Claude daily + Claude skills-hub project daily
```

這個公式是逐日套用後再加總，不是把三份總表直接相減。
`ccusage daily --json` 的日期欄位是 `period`，`ccusage claude daily --json`
的日期欄位是 `date`，Python 計算時兩者都會讀取。
`Total tokens` 使用 daily `totalTokens` 欄位逐日加總，不用四個 token
分類欄位回推。

使用的命令：

```bash
npx ccusage@latest --json --since 2026-04-24 --until 2026-05-19 --timezone Asia/Taipei
npx ccusage@latest claude daily --json --since 20260424 --until 20260519 --timezone Asia/Taipei
npx ccusage@latest claude daily --json --since 20260424 --until 20260519 --timezone Asia/Taipei --project=-Users-samzhu-workspace-github-samzhu-skills-hub
```

Python 計算後：

| 欄位 | 數值 |
| --- | ---: |
| Input tokens | 118,415,608 |
| Output tokens | 15,712,934 |
| Cache create tokens | 35,399,159 |
| Cache read tokens | 4,570,901,843 |
| Total tokens | 4,740,430,679 |
| Total cost | $3,362.38 |

## 一句話版本

26 天，47.40 億 tokens，$3.36K，2208 story points 交付。從產品概念到
企業級 AI Agent Skills Registry。
