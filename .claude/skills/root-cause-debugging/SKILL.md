---
name: root-cause-debugging
description: >
  Debug 卡關時的系統化流程：第一性原則找根因，不打症狀補釘。核心精神：
  症狀 ≠ 根因；fix 沒讓 error 改變 = fix 沒生效；累積嘗試的 noise 必須在
  突破時刻清掉只留真正必要的修改；解釋現象 / 命名 / 假設都要 ground 在
  官方文件，直覺自洽 ≠ 對。Use when user is stuck in a debug loop:
  same error appears 2+ times despite fix attempts, "為什麼還是一樣",
  "已經改了還是 fail", "build/test/CI 連續失敗", "找不出問題", multi-cycle
  CI failures, error message unchanged after fix, user changed direction
  3+ times on same problem. Also use proactively when assistant has tried
  the same kind of fix 2+ times without progress, OR when assistant is
  about to claim "X is automatically triggered / Y is default behavior"
  without source citation. Do NOT use for one-shot questions, bug-free
  feature design, or first-time problem investigation.
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
  version: 1.2.0
  category: workflow-automation
  pattern: debugging-protocol
---

# Root Cause Debugging

## 角色 + 北極星

冷靜、有方法論、第一性原則。每個錯誤先問三個問題：

1. **真正的觸發點是什麼？**（不是事故現場 — error 訊息底部那行只是症狀）
2. **我的 fix 真的生效了嗎？**（error 一字不變 = fix 沒到達 bug 路徑）
3. **我的解釋有官方文件背書嗎？**（直覺自洽 ≠ 對；hand-wave 解釋會誤導 fix 方向）

> **黃金法則**：同 error 連續 2 次 → 立刻轉本機重現 → 完整因果鏈從上往下讀 → 並行派 research 找既有解 → 1 個 minimal-fix → 突破後 bisect 還原 noise。

---

## 跨階段原則：Ground in Official Docs

整個流程的 anchor。任何時刻你即將做以下三件事之一 → **停下查官方文件**，不要靠記憶 / 直覺：

| 場景 | Trigger | Action |
|---|---|---|
| **解釋現象** | 即將說「X 自動觸發 / Y 是預設行為 / framework 偵測 Z / Paketo 看到 A 就 B」 | WebFetch 該 framework / library 的 source / docs / issue 確認機制；不確定就派 Agent research |
| **命名 config / property** | 即將自創 key name（`aotProfiles` / `appMode` / 等） | 先 grep 上下游是否已有同義標準名（`spring.profiles.active` / 既有 yaml key），有 → 直接複用，跨層命名一致零認知負擔 |
| **設計 fix 方向** | 基於 framework 行為假設（「property X 在 phase Y 會生效」「env var 永遠最高優先級」） | WebFetch 該 framework 該 phase 的官方 doc 確認；找不到 → 派 Agent 找對應 GitHub issue |

**為什麼 anchor 在這裡**：debug 過程的每一步都建立在「我相信 framework 這樣 work」上。一個錯誤假設會 cascading 到所有後續步驟。先 ground 比 trial-and-error 便宜 10x。

**Trigger 信號（看到立刻停 + 查文件）**：
- 「應該會 work / 預設就有 / 自動觸發 / 框架會偵測」這類「魔法」措辭從你嘴裡冒出來
- User 質疑「真的是這樣嗎？」/「要做一下功課」/「參考一下官網」
- 你無法給「這個說法的來源是？」5 字內的明確答案（issue#XXX / docs URL / source line）

---

## 工作流（六階段）

### Phase 0：症狀分類 + grep 自己 + project memory（30 秒）

**做這三件事，順序固定**：

1. **讀 error 第一行 + 最後 `Caused by:`** — 分類：bean / network / type / permission / config / behavior / other；涉及哪個 phase（compile / test / build / deploy / runtime / startup / 線上）
2. **Search-Self-First**：`grep -rn "<feature-keyword>" src/main/resources/ build.gradle.kts CLAUDE.md` — 90% 的「framework feature 不 work」其實是自己 config 把 feature 關掉了
3. **Project memory consult**：
   - 同檔 / 同 module 的 comment / docstring — 「為什麼這樣設」
   - `CLAUDE.md` / project README — architecture decisions
   - `docs/grimo/handovers/archive/` 最新 1-2 個 — 上次 session known issues
   - `git log -p <file> | head -100` — 最近一次該檔的 commit message + diff

**為什麼花 30 秒**：90% 場景能直接終結 debug。沒做 = 把學費付給 framework rabbit hole。

詳細運用 → `references/case-study-spring-native-runtime.md` 原則 1+3

### Phase 1：本機快速重現（2 分鐘 budget）

**遠端循環貴 = 本機循環便宜**：CI / cloud build 30 秒~ 數分鐘 / 次；本機通常 < 30 秒 / 次。

**規則：第 2 次遠端失敗就立刻轉本機**。

```bash
# 抽出失敗的 task / command 在本機跑
<command-line-from-CI> --rerun-tasks
```

**判斷分支**：
- 本機重現成功 → Phase 2
- 本機 PASS、遠端 FAIL → 環境差異（credentials / daemon / env var / classpath）；先補環境，**不要繼續改 code**
- 本機跑不到那個 task → 缺 dependency / 工具，補完再 debug

### Phase 2：完整因果鏈從上往下讀

**錯誤訊息底部 = 事故現場，不是根因**。

從第一個 `Caused by:` / 最早的 trigger 往下找。寫下「**X 觸發 Y 拉出 Z**」格式的因果句。**寫不出來 = 沒理解，回去再讀**。

範例（Spring AOT）：
```
1. Advisor sorting failed                      ← 真正根因
2. ↓ MethodSecurityAdvisorRegistrar.getOrder
3. ↓ methodSecurityExpressionHandler bean 提早 instantiate
4. ↓ DelegatingPermissionEvaluator
5. ↓ SkillPermissionStrategy（ctor 注 DataSource）
6. ↓ dataSource bean 建構
7. Failed to determine a suitable driver class ← 事故現場
```

因果句：「`@EnableMethodSecurity` advisor sort 拉出 `methodSecurityExpressionHandler` bean 提早 instantiate，連帶把 ctor 鏈上的 DataSource 拖出來」。

### Phase 3：並行派 research agent（第 1 次失敗就派）

**不是 fallback — 是 first-thing**：

```
Agent 任務：找 <upstream repo> 跟此因果鏈相關的 GitHub issue / 文件 / 已知 workaround。
重點：<library version> + <task name> + <bean/api class chain>。
回報：官方 workaround、status (open/blocked/fixed)、PR link。
```

通常 30 秒內找到對應 issue。**讀別人寫好的解法比 trial-and-error 快 10x**。

並行派 = 不阻塞你 Phase 1（本機重現）。

### Phase 4：fix 生效驗證

**錯誤訊息 0 變動 = fix 沒生效**（不是 fix 不夠）。

驗證手段（依優先序）：
1. `grep` 改動後的 file，確認改動真的在
2. 跑指令時加 `--info` / `--debug` / `-v`，看 JVM args / env var / system property 真的傳入 spawned process
3. **預期錯誤訊息「應該怎麼變」**：例如加 `autoconfigure.exclude` 後 error 該變「No qualifying bean」而非還是「Failed to determine driver class」；若沒變 → fix 沒到達 bug 路徑

**連續 2 次 fix 後 error 一字不變 → 立刻停手**，改驗 fix 機制（fix 真的有跑嗎？路徑是否觸到 bug？）。

### Phase 5：挑戰預設假設 + Pattern-Recognition

「應該會 work」不等於「會 work」。

**5a. 框架 phase 行為差異**：技術 framework 在不同 phase 行為**不同** — Spring Boot runtime / AOT processing / training run / native compile 各有差異。常踩的雷：

| 假設 | 實際 |
|---|---|
| 「property X 會被注入到任何地方」 | 某些 phase 跳過 property binding（如 Spring Boot AOT） |
| 「env var 永遠最高優先級」 | 各 phase property source precedence 不同 |
| 「auto-config 看 `enabled=false` 就不跑」 | 看是 conditional 還是 BeanFactoryInitializationAotProcessor |
| 「dependency `developmentOnly` 不會進 production classpath」 | 某些 phase / scope 例外 |

**規則：發現「我這樣寫應該會 work 但沒 work」→ 停下來查官方文件確認該 phase 行為**（套上面的 Ground in Official Docs 原則）。

**property/yaml 寫法不行 → 直接走 Java code（`@Bean` / 直接 ctor）**。

**5b. Session 內 pattern recognition**：「形狀相似的 bug」第 2 次出現 → 停下寫一句 rule，第 3+ 次直接套：

```
觸發：同 session 內第 2 次踩同類 pattern（不一定同 error 訊息，但「形狀」相似）
動作：
  1. 停手不要直接修第 2 個 bug
  2. 寫一句話 rule：「凡是 X 觸發 Y → Z 行為」
  3. 第 3+ 次同 pattern 出現 → 直接套 rule，5 秒識別不繞
  4. 結案時把 rule 加進 references/ 或 CLAUDE.md，下 session 也省
```

**為什麼必要**：人對 pattern 的識別需要「先寫下來」這個動作來固化，光靠記憶會漏。詳 `references/case-study-spring-native-runtime.md` 原則 2。

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
- 後續 reader 看到「奇妙設定」不知為何
- 未來回歸某層，不知刪哪個會出事
- 累積 noise 變成 permanent debt

**這條原則 CLAUDE.md 已寫**：
> **Clean Experiments**: When debugging, create a restore point before each attempt. Revert failed experiments before trying the next one. When the fix is confirmed, audit the complete changeset — every line must trace to the actual fix, not to leftover experiments.

突破時刻 = 必觸發兩個動作：
1. **Lock the breakthrough**：snapshot 當前狀態（git stash / commit）
2. **Bisect 撤銷**：逐項撤回先前 attempts，每撤一項驗證仍 PASS。直到拿掉就 fail 的那項 = 真正必要的 minimal fix

**延伸：Dead-Config-Audit**：fix + bisect 完，再加一步 audit 同主題歷史 config — `grep -r "<feature-keyword>" src/main/resources/ src/main/java/`，檢查是否有舊 attempt 累積但已失效的 dead config / class（list-property 被覆蓋、profile 對應的 yaml 已 wipe 但 class 還在 etc.）。Phase 6 處理「本次 attempt」noise；這步處理「歷史 attempt」noise。

---

## 反模式 trigger-action（看到立刻停）

| 反模式 | 觸發信號 | 立即動作 |
|---|---|---|
| **「config 改改看」式 debug** | 同 error 連續換 4+ 種寫法 | 停。改驗 fix 機制（Phase 4） |
| **連續 fix「應該會 work」但 error 不變** | 連續 2+ fix error 完全相同 | 停。fix 沒到達 bug 路徑（Phase 4） |
| **本機沒重現就一直 push CI** | 第 3+ 次同 CI 失敗 | 停。立刻轉本機（Phase 1） |
| **突破後不 bisect** | 看到綠燈就開始寫 commit / spec | 停。先 git stash + 逐項撤回（Phase 6） |
| **假設 framework 知識通用於所有 phase** | 「我以為這樣會 work」沒驗就寫 | 停。查官方文件確認該 phase 行為（Phase 5a） |
| **Stack trace 只讀最後一行** | 看到 error 就 google 那行 | 停。從第一個 `Caused by:` 往下讀因果鏈（Phase 2） |
| **跳過 research 直接動手** | 第 1 次失敗就改 code，沒查 issue | 停。並行派 research agent（Phase 3） |
| **懷疑外面前不 grep 自己** | 任何「X feature 不 work」直接研究 framework | 停。先 `grep keyword own/config/*` 排除「自己關掉」（Phase 0 Search-Self-First）|
| **不讀 project memory 直接 debug** | 同檔 comment / handover note / git log 沒看就動手 | 停。30 秒 consult project memory（Phase 0）|
| **Session 內同 pattern 第 3 次仍從零 debug** | 「形狀相似的 bug」第 2 次出現沒寫 rule | 停。寫一句 rule，第 3+ 次直接套（Phase 5b）|
| **User 重啟同指令但你以為 done** | 同一指令 user 開 ≥ 2 次 | 停猜。問 user「your done = which scope: A/B/C」 |
| **Hand-wave 解釋現象** ⭐ v1.2 | 即將說「X 自動觸發 / Y 是預設 / framework 偵測 Z」沒查源碼 | 停。WebFetch source / docs 確認機制；不確定派 Agent research（跨階段原則 Ground in Official Docs）|
| **自創 config / property name** ⭐ v1.2 | 即將寫新的 `xxxProfiles` / `appMode` 等自創 key | 停。先 grep 上下游有無同義標準名 → 直接複用（跨階段原則 Ground in Official Docs）|
| **Fix 完不 audit 歷史 config** ⭐ v1.2 | bisect 完直接 commit，沒 grep 同主題舊 file | 停。`grep -r "<feature-keyword>" src/` 找 dead config（Phase 6 延伸 Dead-Config-Audit）|

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

- `references/case-study-spring-aot.md` — Spring Boot 4 + Java 25 + Cloud Build AOT debugging 完整實戰（強調 Phase 1/2/6 執行細節）
- `references/case-study-spring-native-runtime.md` — Spring Native Runtime 16 個 build cycle 實戰（強調 Phase 0 / Phase 5b / 5 個通用原則 + 反模式疊加災難分析）
- `references/anti-patterns.md` — 8 個反模式深入解析：觸發信號 → 為什麼會卡 → 怎麼跳脫 + 真實對話片段
- `references/checklist.md` — 6 phase 精簡 checklist（卡關時 / 突破時 / 收尾時 trigger-action 對照）

---

## 與其他 skill 的關係

- **`/retro`** — 結案後的回顧分析（產出 trigger-action checklist）；本 skill 是**正在 debug 過程中**的方法論
- **`/simplify`** — 完成後的 code 品質審查；本 skill 是**找問題階段**的工具
- **`/verifying-quality`** — 整 spec ship 前的 QA gate；本 skill 是**個別 bug 級別**的 debug 流程
