# Debug 階段檢查表

精簡版 trigger-action 對照。**printable 一頁**。

---

## 卡關時：六 phase 順序檢查

每階段問自己：**做了沒**？沒做就回頭做。

```
[ ] Phase 0 — 症狀分類 + project memory consult（30 秒）
    □ 讀錯誤訊息第一行 + 最後 Caused by
    □ 分類：bean / network / type / permission / config / behavior 不對 / 其他
    □ 哪個 phase：compile / test / build / deploy / runtime / startup / 線上
    □ references/ 有對應 case study 嗎
    □ Search-Self-First：grep keyword 在 own config/yaml/build script
    □ 同檔 comment / docstring — 「為什麼這樣設」
    □ CLAUDE.md / handover note / git log <file> | head — project memory

[ ] Phase 1 — 本機快速重現（規則：第 2 次遠端失敗就跑）
    □ 抽出失敗的 task / command
    □ 本機跑得起來嗎
    □ 本機 PASS 但遠端 FAIL → 環境差異（先補環境，停改 code）

[ ] Phase 2 — 完整因果鏈從上往下讀
    □ 從第一個 Caused by 往下讀，不是只看最後一行
    □ 寫出「X 觸發 Y 拉出 Z」格式因果句
    □ 寫不出來 = 沒理解，回去再讀

[ ] Phase 3 — 並行派 research agent（規則：第 1 次失敗就派）
    □ Agent 任務：找 GitHub issue / 文件 / 已知 workaround
    □ 重點：library version + task name + class chain
    □ 30 秒內回報

[ ] Phase 4 — fix 生效驗證
    □ grep 改動的檔，確認改動真的在
    □ 加 --info / --debug / -v 看 args / env 真的傳入
    □ 預期 error 應該怎麼變
    □ 連續 2 次同 error → 停手，驗 fix 機制（不是換寫法）

[ ] Phase 5 — 挑戰預設假設
    □ 「應該會 work」沒 work → 質疑該 phase framework 行為
    □ Property 不行 → 改走 Java code（@Bean / 直接 instantiate）

[ ] Phase 6 — 突破後 minimal-fix bisection（規則：看到綠燈立刻做）
    □ git stash snapshot 當前 working state
    □ 逐項拿掉 attempt → 跑驗證 → 仍 PASS = 那項是 noise
    □ 收斂到 minimal set，每項都必要才能 commit
```

---

## 突破時刻 trigger

第一個 SUCCESS / GREEN BUILD / TEST PASS → **馬上做這串**：

```
1. 不要寫收尾文字
2. 不要 commit
3. 不要更新 spec / docs
4. git stash 鎖定 working state
5. 逐項撤回 attempts
   - 撤一項 → 跑驗證
   - 仍 PASS → 那項是 noise，繼續撤下一項
   - FAIL → 還原那項，繼續撤其他
6. 直到剩 minimal set，每項都必要
7. 此刻才開始寫 commit / docs / spec
```

---

## 卡關信號 → 立即動作

| 信號 | 立即動作 |
|---|---|
| 同 error 連續 2 次（任何 phase） | Phase 1 本機重現 / Phase 4 驗 fix 機制 |
| 連續 4+ 次 config 換寫法、error 不變 | 停。Phase 4 驗 fix 真的生效 + Phase 5 質疑 framework 行為 |
| 第 3+ 次同 CI 失敗 | 強制 Phase 1 本機重現，不要再 push |
| 「應該會 work 但沒 work」 | Phase 5：查官方文件確認該 phase 行為 |
| 突破後寫 commit message | 停。Phase 6 bisect 先 |
| Stack trace 只看到最後一行 | Phase 2：從第一個 Caused by 往下讀 |
| 沒查 GitHub issue 直接動手改 | Phase 3：派 research agent |
| 直覺型 fix「我以為這樣就好了」連續多次 | 停。第一性原則：是什麼觸發了這條 path |
| 想去研究 framework / upstream issue | 先 `grep keyword own/config` 排除自己（Search-Self-First）|
| 同 session 第 2 次同類「形狀」的 bug | 寫 rule，第 3+ 次套（Pattern-Recognition-on-2nd）|
| User 重啟同指令 ≥ 2 次但你以為 done | 直接問 user「your done = which scope?」(Read-User-Intent) |
| Bisect 時間貴（≥ 5 min × 多 candidate）| 按 confidence 分組：high-confidence reasoning，low-confidence 必 bisect |
| 即將說「X 自動觸發 / Y 預設 / framework 偵測」沒查源碼 ⭐ v1.2 | 停。WebFetch source / docs 確認；不確定派 Agent research（Ground in Official Docs）|
| 即將自創 config / property name | 停。grep 上下游有無同義標準名 → 直接複用（Ground in Official Docs）|
| User 質疑「真的是這樣嗎」/「做一下功課」/「參考一下官網」 | 立刻派 research，這是強烈的 grounding 缺失信號 |
| Fix + bisect 完直接 commit，沒 grep 同主題舊 file | grep 該 feature keyword 找 dead config / 失效 class（Phase 6 延伸 Dead-Config-Audit）|

---

## 收尾時：commit 前最後檢查

ship / commit 前確認：

```
□ Phase 6 bisect 做了
□ 真正必要的 minimal fix 已驗證（拿掉就 fail）
□ 累積 attempt 的 noise 全撤
□ commit message 能解釋每一行為什麼必要
□ 因果句寫得出來：「fix X 解決 root cause Y，因為 Z」
□ 找到的 GitHub issue / 官方 doc URL 列在 commit / spec 裡
□ 同類問題下次再遇到能 5 分鐘解，不用 2 小時
```

---

## Cycle time 預算（任何 debug session）

如果發現你超過這些 budget，**停手，回 Phase 0 重啟**：

| 項目 | Budget |
|---|---|
| Phase 0 症狀分類 | 10 秒 |
| Phase 1 本機重現 | 2 分鐘（找出指令 + 跑通） |
| Phase 2 寫出因果句 | 5 分鐘（包含完整讀 stack trace） |
| Phase 3 research agent 回報 | 30 秒～ 2 分鐘 |
| Phase 4 驗 fix 生效 | 1 分鐘 |
| Phase 5 質疑假設 + 找對的方向 | 5 分鐘 |
| Phase 6 bisect | 10-30 分鐘（看 attempt 累積量） |
| **單 bug session 總計** | **30-60 分鐘** |

超過 1 小時 = 卡住了，回頭重新跑 Phase 0-3，或請別人 fresh eye。

---

## 自我提問模板

每 5 分鐘問自己一次：

1. 我目前在第幾 phase？
2. 上一個 fix 真的有生效嗎？怎麼驗的？
3. error 訊息有變嗎？沒變代表 fix 沒到 bug 路徑
4. 我是基於什麼假設改這個的？這個假設有官方文件背書嗎？
5. 如果現在突然 SUCCESS，我能說清楚是哪個 fix 解的嗎？

第 5 題答不出來 = 你已經累積 noise，突破後一定要 bisect。
