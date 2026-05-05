# Case Study：Spring Native Runtime 實戰 — 通用原則萃取

S133 session：16 個 build cycle 把 Spring Boot 4 + Java 25 應用編譯成可執行的 native image。
本 case 重點不是 Spring 細節，是過程中**反覆驗證的 5 個通用原則**。

---

## 場景一句話

「`./gradlew bootBuildImage` 過了但 native runtime 起不來」連環踩 5 個雷：
Modulith ArchUnit ClassNotFound → @Profile bake 鎖死 → AOT @CP binding 失效 →
Flyway disable leak → SpringDoc 404。每個雷的「**形狀**」都是同一個：
**「build-time 的 disable / config 會被 freeze 進 native runtime，runtime 改不回來」**。

---

## 原則 1：查詢「自己」優先於懷疑「外面」（Search-Self-First）

### 反模式

看到某 framework feature 不 work → 立刻假設 framework 不相容 / native compat 問題 →
深入研究 framework AOT 機制 / Spring Boot 4 變動 / GitHub issue。

### 為什麼會卡

外部解釋永遠複雜（涉及多 framework / phase / version）；你自己的 config 簡單但沒人查。
**簡單解釋優先於複雜解釋**（Occam's razor），但人習慣外歸因。

### 實戰

SpringDoc `/v3/api-docs` 404：
- 我假設「SpringDoc 3.0.2 + Spring Boot 4 + native compat 不全」
- 深入研究 SpringDoc 的 `aot.factories` / `BeanRegistrationAotProcessor` 缺失 / `@Configuration` vs `@AutoConfiguration` 寫法
- 結論寫了「upstream 問題」
- User 一句話：「先移除吧」 → 我才回去 `grep springdoc` →
  發現我們自己 `application.yaml` line 120：`springdoc.api-docs.enabled: false`

**直接 root cause** 在 own config，跟 framework 無關。

### 通用 rule

第 1 步永遠先 `grep` keyword 在自己的 config / yaml / build script / java code。
2 秒內排除「自己關掉」的可能再去懷疑外面。

```bash
# 任何「X feature 不 work」第一個動作
grep -rn "<feature-keyword>" src/main/resources/ build.gradle.kts CLAUDE.md
```

---

## 原則 2：同 session 第 2 次同 pattern 必 generalize（Pattern-Recognition-on-2nd）

### 反模式

同 session 內已踩過「同一形狀」的 bug N 次，第 N+1 次仍從零 debug。

### 為什麼會卡

每次都當作「新 bug」用 first-principle 重來，**不寫 rule 就不會自動套用**。
人對 pattern 的識別需要「先寫下來」這個動作來固化，光靠記憶會漏。

### 實戰

「AOT-time disable / property false → ConditionalOn 評估 false → bean 不 baked → runtime 改不回」
這個 pattern 在這 session 出現 **4 次**：

| # | 觸發 | 時間花費 |
|---|---|---|
| 1 | Modulith actuator/observability autoconfig disable → ArchUnit fail | 3 個 build cycle |
| 2 | Flyway `enabled=false` baked → relation does not exist | 1 個 build cycle |
| 3 | JdbcDialect (上 session) | (handover 已記錄) |
| 4 | SpringDoc `api-docs.enabled=false` baked → 404 | 30 min 研究 framework 才發現 |

**第 2 次踩到時就該寫成 explicit rule**：

> 「Native build 的 AOT 階段必須 enable runtime 想要的所有 bean / autoconfig；
> 任何 yaml `enabled=false` 或 ConditionalOn 評估 false 都會 baked into native context，
> runtime env var 救不回。要 native 啟用某 feature → AOT 階段就要啟用。」

寫下來 → 第 3、4 次同 pattern 5 秒識別。沒寫 → 第 4 次照樣繞 30 分鐘。

### 通用 rule

```
觸發：在同個 debug session 觀察到「形狀相似的 bug」第 2 次出現
動作：
  1. 停手不要直接修
  2. 寫一句話 rule：「凡是 X 觸發 Y → Z 行為」
  3. 第 3+ 次同 pattern → 直接套 rule，不重 debug
  4. 結案時把 rule 加進 references/ 或 CLAUDE.md
```

---

## 原則 3：Phase 0 包含 project memory consultation（Read-Project-First）

### 反模式

跳過 project 自身的 lesson 直接 debug — 不讀 handover note / 不讀同檔 comment / 不查 git log。

### 為什麼會卡

你或前人很可能已經踩過、解過、留下 comment 寫「為什麼這樣設」。
**不讀 = 把 learning tax 重新付一次**。

### 實戰

`application-aot.yaml` 開頭 comment 上 session 已寫：

> 「Spring Boot 4 AOT processing 不跑 @ConfigurationProperties binding，profile yaml override 不生效」

我試了 systemProperty / args / yaml 三種 binding-based fix（共 3 個 build cycle、
9 分鐘）都失敗 → 最後**回去看 comment** → 才想起「對齁，根本不是 binding 路徑」→
切走 Java code with System.getenv 直接過。

如果 Phase 0 先讀 comment，省 9 分鐘 + 3 次同 error 的判斷力消耗。

### 通用 rule

Phase 0 加入「**project memory consultation**」(< 30 秒)：

```
[ ] grep keyword 在 own config (Search-Self-First)
[ ] Read 同檔 comment / docstring / class Javadoc — 「為什麼這樣設」
[ ] Read CLAUDE.md / project README 看 architecture decisions
[ ] Read .claude/handovers/archive/ 最新 1-2 個 handover note —
    上次 session 結束時的 known issues / lessons
[ ] git log -p <file> | head -100 看最近一次該檔的 commit message + diff
```

這串花 < 30 秒，省幾倍時間。

---

## 原則 4：Literal vs Intent — 識別 user 真正要的「done」（Read-User-Intent）

### 反模式

User 給字面指令 → 字面解 → 自以為完成 → user 重啟同指令 → 你還以為「他在等 commit 確認」。

### 為什麼會卡

字面語意 ≠ user intent。需要從 user 的**重複行為**推 intent。

### 實戰

User 的字面指令：
> 「在本地端嘗試解決 Springboot 無法編譯成 Native Image 的問題 ... 我要用 Spring Native + ./gradlew bootBuildImage 來編譯成 Native Image」

**字面解**：`bootBuildImage` 編譯成功就完成。

**Intent**（從 4 次重啟同 /loop 推出來）：
1. 編譯成功（literal）
2. **能本機跑起來**（隱含）
3. **DB / Modulith / Flyway / 主要功能 work**（隱含）
4. **沒明顯 4xx/5xx**（隱含）

每次 user 重啟，我才往下解一層。如果第 1 次重啟就主動問「你要 compile 完，還是要 native runtime 完整 work？」可以省掉 3 輪猜測。

### 通用 rule

```
觸發：user 重啟「相同指令」≥ 2 次 OR 對你的「結束報告」沒 acknowledge 就再開同 task
動作：
  1. 不要繼續猜
  2. 直接問：「你的 done 標準是什麼？以下哪些 must-have？
     A) <字面 minimum>
     B) <你猜的中間層>
     C) <最大 scope>」
  3. 一次性 align scope，避免增量猜
```

---

## 原則 5：Bisect cost-vs-benefit threshold（Bisect-Judgement）

### 反模式

A: 永遠 bisect（即使每個 cycle 5 分鐘 × 6 個 candidate noise = 30 分鐘）
B: 永遠不 bisect（推理 talk yourself into 留 noise）

兩端都不對。

### 為什麼會卡

Phase 6 「突破後 bisect」是強 rule，但實務上 bisect 也有 cost。
盲目跟 rule 沒考慮 cost-benefit 在 long-cycle 場景燒太多時間。

### 實戰

本 session 突破時刻有 5 個 candidate noise 要驗證：

| Candidate | 處理方式 | 用時 |
|---|---|---|
| `BP_JVM_AOTCACHE_ENABLED=false` | **Reasoning**：Paketo native-image chain 不跑 JVM AOT cache buildpack（doc 明確），高信心 argued noise；revert 但不獨立 build 驗證 | 0 min |
| `application-aot.yaml` 加 modulith excludes | **Reasoning**：profile=aot,local 下 list 屬性 last-wins（Spring docs 明確），application-local.yaml override 它，是 dead code；revert | 0 min |
| `@Lazy DataSource` x2 | **Bisect**：理論上 stub DataSource 滿足 chain 不需 lazy，但 stack overflow / circular dep 場景可能反向；獨立 build 驗證 | +3 min |
| ProcessAot args 加 local | **Reasoning skip**：明顯必要（FileSystemStorageService 沒 local 不會 baked），不需驗 | 0 min |
| stub URL placeholder | **Bisect**：4 次失敗 attempt 留下的 dead config，全 revert 一次 verify | 0 min（已含在 @Lazy bisect 裡） |

總成本：3 min build × 1 次 = 3 min（vs 全 bisect 5 candidate × 3 min = 15 min）

### 通用 rule

突破後 audit 每個 candidate，依 cost-confidence matrix：

|  | High confidence noise（doc / spec 明確 support） | Low confidence |
|---|---|---|
| **Bisect cycle 便宜** (≤ 1 min) | Always bisect | Always bisect |
| **Bisect cycle 貴** (≥ 5 min) | Reasoning 可接受 — 寫進 commit message 解釋 | **必 bisect** — claim 不夠強 |

「Bisect cycle 貴 + High confidence」是可以 reasoning 帶過的唯一格子。其他三格 bisect。

實作 sketch：

```bash
# Phase 6：build cycle 貴，先按 confidence 分組
git stash --keep-index    # 鎖 working state

# Group A：high-confidence noise — 一起 revert，one bisect 驗
git revert --no-commit <range-of-noise-A>
./build && ./test         # 一次驗多項

# Group B：low-confidence — 一個一個 revert verify
git revert --no-commit <noise-B-1>
./build && ./test
# ...
```

---

## 反模式互相疊加（本 session 觀察）

5 條原則沒守 → 災難疊加：

```
Read-Project-First 沒做（→ 不知道 @CP binding 限制）
    +
Search-Self-First 沒做（→ 不知 yaml 自己關 SpringDoc）
    +
Pattern-Recognition-on-2nd 沒做（→ 4 次同 pattern 各自從零 debug）
    +
Read-User-Intent 沒做（→ 每次以為 done 結果不是）
    +
Bisect-Judgement 沒做（→ 全 bisect 燒 cycle / 全不 bisect 留 noise）

= 16 個 build cycle、~2 小時繞彎
```

5 條都做 → cycle 收斂到 <8 個 build。

---

## 與其他 case study 的關係

- `case-study-spring-aot.md` — 強調 Phase 1（本機重現）/ Phase 2（stack trace） / Phase 6（bisect）
  的執行細節
- 本 case study — 強調 Phase 0（project memory consult）+ session-level meta skills（pattern
  recognition / intent reading）+ Phase 6 的判斷層（不是執行層）

兩者互補。技術 framework 不同（Spring Boot vs 任何技術棧），但通用原則重疊。
