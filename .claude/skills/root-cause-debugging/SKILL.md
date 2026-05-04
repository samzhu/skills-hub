---
name: root-cause-debugging
description: >
  Debug 卡關時的系統化流程：第一性原則找根因，不打症狀補釘。
  六階段流程：症狀分類 → 本機快速重現 → 完整因果鏈從上往下讀 → 並行查既有解 →
  fix 生效驗證 → 挑戰預設假設 → 突破後 minimal-fix bisection 還原嘗試 noise。
  核心精神：症狀 ≠ 根因；fix 沒讓 error 改變 = fix 沒生效；累積嘗試的 noise
  必須在突破時刻清掉只留真正必要的修改。Use when user is stuck in a debug
  loop: same error appears 2+ times despite fix attempts, "為什麼還是一樣",
  "已經改了還是 fail", "build/test/CI 連續失敗", "找不出問題", multi-cycle
  CI failures, error message unchanged after fix, user changed direction
  3+ times on same problem. Also use proactively when assistant has tried
  the same kind of fix 2+ times without progress. Do NOT use for one-shot
  questions, bug-free feature design, or first-time problem investigation.
allowed-tools:
  - Read
  - Glob
  - Grep
  - Bash
  - Edit
  - WebFetch
  - WebSearch
  - Agent
metadata:
  author: samzhu
  version: 1.0.0
  category: workflow-automation
  pattern: debugging-protocol
---

# Root Cause Debugging

## 角色

冷靜、有方法論、第一性原則。每個錯誤先問兩個問題：

1. **真正的觸發點是什麼？**（不是事故現場 — error 訊息底部那行只是症狀）
2. **我的 fix 真的生效了嗎？**（error 一字不變 = fix 沒到達 bug 路徑）

## 黃金法則

> 同 error 連續 2 次 → 立刻轉本機重現 → 完整因果鏈從上往下讀 → 並行派 research 找既有解 → 1 個 minimal-fix → 突破後 bisect 還原 noise

## 六階段流程

### Phase 0：症狀分類（10 秒）

讀錯誤訊息的**第一行**和**最後 `Caused by:`**。問：
- 這是什麼類型的錯誤？（bean creation / network / type / permission / config）
- 涉及哪個 phase？（compile / test / build / deploy / runtime / startup）
- 之前看過類似的嗎？（檢查 references/ 是否已有 case study）

### Phase 1：本機快速重現（2 分鐘 budget）

**遠端循環貴 = 本機循環便宜**：
- CI / cloud build：30 秒~ 數分鐘 / 次
- 本機重現：通常 < 30 秒 / 次

**規則：第 2 次遠端失敗就立刻轉本機**。

```bash
# 先抽出失敗的 task / command 在本機跑
<command-line-from-CI> --rerun-tasks   # 或對應的本機重現指令
```

**判斷分支**：
- 本機重現成功 → Phase 2
- 本機 PASS、遠端 FAIL → 環境差異（credentials / daemon / env var / classpath）；先補環境，**不要繼續改 code**
- 本機跑不到那個 task → 缺 dependency / 工具，補完再 debug

### Phase 2：完整因果鏈從上往下讀

**錯誤訊息底部 = 事故現場，不是根因**。

從第一個 `Caused by:` / 最早的 trigger 往下找。寫下「**X 觸發 Y 拉出 Z**」格式的因果句。**寫不出來 = 沒理解**。

範例（Spring AOT 那次）：
```
1. Advisor sorting failed                      ← 真正根因（什麼觸發 sort）
2. ↓ MethodSecurityAdvisorRegistrar.getOrder
3. ↓ methodSecurityExpressionHandler bean 提早 instantiate
4. ↓ DelegatingPermissionEvaluator
5. ↓ SkillPermissionStrategy（ctor 注 DataSource）
6. ↓ dataSource bean 建構
7. Failed to determine a suitable driver class ← 事故現場
```

寫成因果句：「**`@EnableMethodSecurity` advisor sort 拉出 `methodSecurityExpressionHandler` bean 提早 instantiate，連帶把 ctor 鏈上的 DataSource 拖出來**」。

### Phase 3：並行派 research agent + 找既有解

**第 1 次失敗就派**（不是 fallback）：

```
Agent 任務：找 <upstream repo> 跟此因果鏈相關的 GitHub issue / 文件 / 已知 workaround。
重點：<library version> + <task name> + <bean/api class chain>。
回報：官方 workaround、status (open/blocked/fixed)、PR link。
```

通常 30 秒內找到對應 issue。為什麼 first-thing：你不是第一個踩到的人，**讀別人寫好的解法比 trial-and-error 快 10x**。

### Phase 4：fix 生效驗證

**錯誤訊息 0 變動 = fix 沒生效**（不是 fix 不夠）。

驗證手段（依優先序）：
1. `grep` 改動後的 file，確認改動真的在
2. 跑指令時加 `--info` / `--debug` / `-v`，看 JVM args / env var / system property 真的傳入 spawned process
3. **預期錯誤訊息「應該怎麼變」**：例如加 `autoconfigure.exclude` 後 error 該變「No qualifying bean」而非還是「Failed to determine driver class」；若沒變 → fix 沒到達 bug 路徑

**連續 2 次 fix 後 error 一字不變 → 立刻停手**，改驗 fix 機制（fix 真的有跑嗎？路徑是否觸到 bug？）。

### Phase 5：挑戰預設假設（第一性原則）

「應該會 work」不等於「會 work」。

技術 framework 在不同 phase 行為**不同**：
- Spring Boot：runtime / AOT processing / training run / native compile 各有差異
- Build tool：compile / test / package / publish 各 task 的 classpath 不同
- Container：build-time / start-up / runtime 環境變數可見性不同

**規則：當你發現「我這樣寫應該會 work 但沒 work」時，停下來查官方文件確認該 phase 行為**。常踩的雷：

| 假設 | 實際 |
|---|---|
| 「property X 會被注入到任何地方」 | 某些 phase 跳過 property binding（如 Spring Boot AOT） |
| 「env var 永遠最高優先級」 | 各 phase property source precedence 不同 |
| 「auto-config 看 `enabled=false` 就不跑」 | 看是 conditional 還是 BeanFactoryInitializationAotProcessor |
| 「dependency `developmentOnly` 不會進 production classpath」 | 某些 phase / scope 例外 |

**property/yaml 寫法不行 → 直接走 Java code（`@Bean` / 直接 ctor）**。

### Phase 6：突破後 minimal-fix bisection（**最重要**）

第一個 SUCCESS 那刻 → **不寫收尾文字、不 commit、不更新 spec**，先：

```bash
git stash      # snapshot working state
# 逐項拿掉 attempt（一次一項），每拿一項驗證仍 PASS
# 拿掉就 fail 那項 = 真正必要的 minimal fix；其他全是 noise
git stash pop  # 必要時還原
```

**不做這步的代價**：
- 真因無法驗證 — 也許 SUCCESS 不是靠你以為的 fix A，是 fix B
- 後續 reader 看到一堆「奇妙設定」不知為何
- 未來回歸某層，不知刪哪個會出事
- 累積 noise 變成 permanent debt

**這條原則 CLAUDE.md 已寫**：
> **Clean Experiments**: When debugging, create a restore point before each attempt. Revert failed experiments before trying the next one. When the fix is confirmed, audit the complete changeset — every line must trace to the actual fix, not to leftover experiments.

突破時刻 = 必觸發兩個動作：
1. **Lock the breakthrough**：snapshot 當前狀態（git stash / commit）
2. **Bisect 撤銷**：逐項撤回先前 attempts，每撤一項驗證仍 PASS。直到拿掉就 fail 的那項 = 真正必要的 minimal fix

實作層面跟 git bisect 同形：你有「good」（current SUCCESS）跟「bad」（baseline FAIL），要找出**哪些變更真的造成 good vs bad**。其他全是 noise。

---

## 反模式（看到立刻停）

| 反模式 | 觸發信號 | 立即動作 |
|---|---|---|
| **「config 改改看」式 debug** | 同 error 連續換 4+ 種寫法 | 停。改驗 fix 機制（Phase 4） |
| **連續 fix「應該會 work」但 error 不變** | 連續 2+ fix error 完全相同 | 停。fix 沒到達 bug 路徑（Phase 4） |
| **本機沒重現就一直 push CI** | 第 3+ 次同 CI 失敗 | 停。立刻轉本機（Phase 1） |
| **突破後不 bisect** | 看到綠燈就開始寫 commit / spec | 停。先 git stash + 逐項撤回（Phase 6） |
| **假設 framework 知識通用於所有 phase** | 「我以為這樣會 work」沒驗就寫 | 停。查官方文件確認該 phase 行為（Phase 5） |
| **Stack trace 只讀最後一行** | 看到 error 就 google 那行 | 停。從第一個 `Caused by:` 往下讀因果鏈（Phase 2） |
| **跳過 research 直接動手** | 第 1 次失敗就改 code，沒查 issue | 停。並行派 research agent（Phase 3） |

---

## 跨領域應用

這六階段流程在以下情境都通用：

| 場景 | Phase 1 本機重現對應 | Phase 2 因果鏈讀法 |
|---|---|---|
| CI / Cloud Build 失敗 | 本機跑同 task | Build log stack trace |
| Test failure | 本機跑單個 test | assertion stack trace + setup chain |
| Deploy 失敗 | local docker run | container log + healthcheck output |
| Runtime production bug | 用 prod-similar config 本機 reproduce | log level=DEBUG trace |
| Dependency conflict | local clean build | Gradle/Maven dependency tree |
| Type / lint error | 本機跑 type check | compiler error chain |
| Network / latency 問題 | curl / ping / traceroute | network trace timing |

不論技術棧，都套同樣 6 phase。差別只在「本機重現」用什麼指令、「因果鏈」在什麼 log 裡找。

---

## 參考資料

執行時按需閱讀：

- `references/case-study-spring-aot.md` — Spring Boot 4 + Java 25 + Cloud Build AOT debugging 完整實戰：6 次 CI 失敗 → 本機重現 → 因果鏈追蹤 → research agent 找到 issue #47781 → 5 次 attempt 累積 noise → 突破後 bisect 收斂到 minimal fix。每階段都對應到本 SKILL.md 哪個 phase + 具體做了什麼。
- `references/anti-patterns.md` — 常見 anti-pattern 深入解析：「為何 config 改改看會卡住」、「為何 stack trace 只讀最後一行讀不出根因」、「為何不 bisect 會累積 debt」+ 真實對話片段示例
- `references/checklist.md` — 6 phase 精簡 checklist 形式（卡關時、突破時、收尾時的 trigger-action 對照）

---

## 與其他 skill 的關係

- **`/retro`** — 結案後的回顧分析（產出 trigger-action checklist）；本 skill 是**正在 debug 過程中**的方法論
- **`/simplify`** — 完成後的 code 品質審查；本 skill 是**找問題階段**的工具
- **`/verifying-quality`** — 整 spec ship 前的 QA gate；本 skill 是**個別 bug 級別**的 debug 流程
