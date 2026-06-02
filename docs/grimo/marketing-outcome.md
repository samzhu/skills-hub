# Skills Hub 成效摘要

我們用 26 天、約 47.40 億 tokens 的 AI 開發投入，等價成本
$3,362.38，把企業級 AI Agent Skills Registry 從產品概念推進到
1305 點可驗收成果，平均每週交付 351.3 點。

換句話說：約 3,400 美元，交付一套能發佈、搜尋、審核、下載、
管理權限、跑 E2E 驗證，並持續部署修正的 AI Agent Skills Registry。

## 核心數字

| 指標 | 數值 |
| --- | ---: |
| 開發期間 | 2026-04-24 至 2026-05-19 |
| 總天數 | 26 天 |
| Token 投入 | 4,740,430,679 tokens（約 47.40 億） |
| Token 等價成本 | $3,362.38 |
| 可驗收成果 | 1305 story points |
| 每週交付 | 351.3 story points |
| 每點成本 | $2.58 |

## 1305 點怎麼算

1305 點是以 Fibonacci story point deck 加總 2026-05-19 前已完成的
spec，算到 `v4.86.0 / S202`。

目前正式點數只使用：

```text
1, 2, 3, 5, 8, 13, 20
```

`20` 只保留給歷史上的 parent / rollup 工作包，例如 `S014`、
`S147`、`S160`、`S161`、`S163`、`S164`。一般單一 spec 即使很大，
上限也落在 `13`，代表它應該被拆分前不再繼續膨脹。

這份結算不是把舊制括號數字直接相加。Python 腳本會完整讀取
`docs/grimo/specs/spec-roadmap.md` 與 `docs/grimo/specs/archive/`，
每個 SpecID 只算一次，並用以下資料判斷 story points：

- roadmap 的正式 story point 值；舊 rows 的 `XS/S/M/L/XL` 只當 legacy
  標籤讀取，不輸出成 story point 欄位。
- archive spec 的實作證據，例如前端/後端/資料庫、E2E、production、
  Cloud Run、Docker、native image、schema migration、pivot/debug。
- `META`、取消、取代、延後、尚未完成的 spec 不計入。
- 歷史拆段子 spec 仍列在 story point records，但點數歸入 parent 工作包，避免重複計算。

結算資料：

- 腳本：`tools/calculate_story_points.py`
- 每個 spec 的結果：`docs/grimo/specs/story-points-2026-06-02.json`
- source coverage：252 個 archive 檔、4,482,923 bytes。
- marketing 區間：235 個 counted spec，1305 story points。
- 目前全 repo shipped 區間：239 個 counted spec，1347 story points。

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

26 天，47.40 億 tokens，$3.36K，1305 story points 交付。從產品概念到
企業級 AI Agent Skills Registry。
