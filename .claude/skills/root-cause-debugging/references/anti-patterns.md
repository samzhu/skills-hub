# Debugging 反模式深入解析

每個反模式：**觸發信號 → 為什麼會卡 → 怎麼跳脫**。

---

## 1. 「Config 改改看」式 debug

### 觸發信號

同一個 error 訊息出現 4+ 次，每次你換不同的 config 寫法（property、yaml、env var、CLI args），但 error 訊息**完全沒變**。

### 為什麼會卡

你以為「這次寫法不對，下次換另一種」。但實際上 fix 從來沒到達 bug 路徑 — error 一字不變就是證據。你在原地打轉，**輸入空間是有限的**：4 種寫法都試完了，第 5 種會比較好嗎？大概率不會。

### 怎麼跳脫

**第 2 次同 error 就停手**，從「換 fix 寫法」切換到「**驗 fix 機制**」：

1. `grep` 改動的檔，確認改動真的在
2. 加 `--info / --debug / -v` 看 spawned process 的 args / env / config 真的有那項
3. 預期 error **應該怎麼變**：例如加 exclude 後該變「No qualifying bean」而非還是同訊息

如果驗證顯示 fix **真的有傳進去但沒被讀到** → 不是寫法問題，是 framework 在這 phase 不讀你想的那個 source（轉 Phase 5）。

### 真實對話片段

> Assistant: 把 `spring.datasource.url` 加到 application-aot.yaml...
> [build fail，相同 error]
> Assistant: 改用 system property `-D` 看看...
> [build fail，相同 error]
> Assistant: 試試 environment 環境變數...
> [build fail，相同 error]
> Assistant: 用 CLI args `--key=value` 最高優先級...
> [build fail，相同 error]
> 
> ❌ 4 次嘗試 = 在原地打轉
> ✅ 第 2 次同 error → grep config 確認改動在 → `--info` 看 JVM args 有沒有那項 →
>    確認 fix 真的有傳但沒被讀 → 切換思路：寫 Java config

---

## 2. Stack trace 只讀最後一行

### 觸發信號

看到 error 直接 google 最後 line 的訊息，貼到 LLM 問「這個錯誤怎麼解」。

### 為什麼會卡

Java / Spring stack trace **底部 = 事故現場**（哪行 throw），**頂部 = 觸發鏈起點**（什麼導致建構這個 bean / 進入這條 path）。

只讀底部會 google 出「Failed to determine driver class」的解 100 個帖子，每個都說「設 spring.datasource.url」。但你的問題不是 URL 沒設，是**為什麼 DataSource bean 在這 phase 提早被建構**。

### 怎麼跳脫

從**第一個** `Caused by:` 往下讀，每行都看 class name + method name：
- 是哪個 framework 的 class 在做事？
- 為什麼要做這件事？（trigger）
- 為什麼觸發到 bean creation？

寫成「**X 觸發 Y 拉出 Z**」格式因果句。寫不出來代表沒理解，再讀。

### 真實對話片段

> Assistant: 看到 "Failed to determine a suitable driver class"，加 spring.datasource.url
> [fail，error 一樣]
> Assistant: 那加 driver-class-name...
> [fail，error 一樣]
>
> ❌ 只看底部 = google 出 wrong direction
> ✅ 看頂部「Advisor sorting failed → MethodSecurityAdvisorRegistrar.getOrder」=
>    @EnableMethodSecurity advisor sort 觸發整條 bean chain →
>    根因是「為什麼 bean chain 會在 advisor sort 階段被觸發」

---

## 3. 本機沒重現就一直 push CI

### 觸發信號

第 3+ 次同 CI 失敗，每次循環 ≥ 1 分鐘，期間什麼都做不了只能等。

### 為什麼會卡

CI 循環貴（網路 + 排程 + 等別人佔資源），本機循環便宜（直接跑）。但 CI 你不能 step debug、不能加 `println`、不能即時觀察。**循環貴 + 工具少 = 最差的 debug 環境**。

繼續 push 的心理是「也許這次會過」/「總會試到對的」/「本機環境不一樣很麻煩」。但 cost-benefit 已經炸了。

### 怎麼跳脫

第 2 次 CI 失敗 → **強制本機重現**：
1. 抽出 CI 跑的指令（`docker ...` / `./gradlew ...` / 對應 task name）
2. 本機跑，加任何能 reduce cycle time 的 flag
3. 如果本機 PASS、CI fail → 環境差異（不是 code 問題，補環境變數 / credentials）

**99% 的「only fails on CI」其實本機能重現** — 通常用對 profile / env / classpath 就行。

### 真實對話片段

> Assistant: 改了 → push → CI fail
> Assistant: 改了 → push → CI fail（同 error）
> Assistant: 改了 → push → CI fail（同 error）
> Assistant: 改了 → push → CI fail（同 error）
>
> ❌ 4 次 CI = 6 分鐘 + 3 個 retry budget 燒掉
> ✅ 第 2 次 CI fail → `SPRING_PROFILES_ACTIVE=aot ./gradlew processAot` 本機重現
>    7 秒 / 次循環，13x 快

---

## 4. 突破後不 bisect

### 觸發信號

看到綠燈 → 心情飛揚 → 開始寫 commit message / spec 收尾文字 / PR description。

### 為什麼會卡

突破時刻你的 working state 累積了 N 個 attempt：
- 改的 yaml 有 5 段 stub 設定
- build script 有 belt+suspenders 的多種寫法
- 新加的 Java config 解了一半問題
- 各種 env var override

**你不知道哪些是真正必要的，哪些是 noise**。一起 commit → permanent debt：
- 後續 reader 看到「奇妙設定」不知為何而設
- 未來改別的踩雷時，這些 noise 互相干擾
- 真因永遠寫不清楚（你都不確定）

### 怎麼跳脫

**第一個 SUCCESS = 立即 git stash + bisect**：

```bash
git stash      # snapshot working state
# 逐項拿掉一個 attempt → 跑驗證
# 若仍 PASS → 那項是 noise，繼續拿下一項
# 若 fail → 還原那項，繼續拿其他
# 直到拿到一個 minimal set，每項都必要
git stash pop  # 必要時還原
```

跟 git bisect 同形：在 noisy patch 集合中找出**真正造成 good 的 minimal subset**。

### 真實對話片段

> Build SUCCESSFUL ✓
> Assistant: 太好了！我來寫 spec 收尾...
> [user 中斷] 等等，先把之前嘗試亂改的恢復，只做問題修正的修改
> Assistant: [然後撤掉 10+ 項 noise，5 項 minimal fix 浮出水面]
>
> ❌ User 不開口 = noise 進 commit history 變 permanent debt
> ✅ 突破時刻 trigger：snapshot → 逐項撤回 → 每項驗證 → 留 minimal set

---

## 5. 假設 framework 知識通用於所有 phase

### 觸發信號

「我以為這樣會 work 但沒 work」連續 2+ 次。你寫了你 Spring runtime 上熟悉的東西（property override、env var、profile yaml），結果在 AOT processing / training run / native compile 階段失靈。

### 為什麼會卡

Framework 在不同 lifecycle phase 行為**不同**，但 documentation 和 tutorial 通常只描述 runtime 行為。你把 runtime 知識套到 build-time 階段 → 預設假設失敗。

具體例：
- Spring Boot：`@ConfigurationProperties` binding 在 AOT processing 階段對 eager bean 不生效
- Build tool：`processAotClasspath` 跟 `runtimeClasspath` 跟 `testClasspath` 不一樣
- Container：`ARG` 跟 `ENV` 在 build-time / start-up / runtime 可見性不同
- Test runner：fixture 在 `@BeforeAll` / `@BeforeEach` / setup 階段順序不同

### 怎麼跳脫

當你看到「我這樣寫應該 work 但沒 work」 → 停下來查官方文件確認該 phase 行為。具體手段：

1. WebSearch / WebFetch 該 framework 該 phase 的官方 doc
2. 派 research agent 找對應 GitHub issue
3. 寫 minimal repro 隔離 phase 行為（不要在 production code 上 debug）

如果官方 doc 沒寫清楚（常見），通常 GitHub issue 有人問過。這時候 **issue search > docs**。

### 真實對話片段

> Assistant: CLI args `--spring.datasource.url=...` 是最高優先級 property source
> [verify with --info 顯示真的傳進 JVM]
> Assistant: 但 error 還是 URL empty...
> Assistant: 多 try 幾種寫法應該其中一個會 work
>
> ❌ 假設 runtime precedence rule 適用 AOT phase = 永遠繞不過
> ✅ 第 2 次「應該 work 但沒 work」 → 派 agent 查 Spring AOT processing 階段
>    對 @ConfigurationProperties binding 的行為 → 找到 issue #47781 →
>    切換到 Java config 方案

---

## 6. 跳過 research 直接動手

### 觸發信號

看到 error → 立刻動手改 code，沒先查 GitHub issue / 官方 docs。

### 為什麼會卡

你不是第一個踩到這個 bug 的人。Open-source library 有一個 publicly-known issue tracker，**有人已經幫你寫好 workaround**。

不查就動手 = trial-and-error；查了就動手 = 直接套 known good solution。**速度差 10x**。

### 怎麼跳脫

**第 1 次失敗 = 並行派 research agent**（不是 fallback、不是「先試試看再說」）：

```
Agent 任務：找 <upstream repo> 跟此 stack trace 相關的 issue / doc / 已知 workaround。
重點：<library version> + <task name> + <bean class chain>。
回報：官方 workaround、status (open / blocked / fixed)、PR link。
```

通常 30 秒內回報。並行進行 = 不阻塞你 Phase 1（本機重現）。

### 真實對話片段

> Assistant: error 看起來是 DataSource binding 問題，加 stub URL...
> [4 次 attempt 後] Assistant: 那派個 research agent 看看吧
> [agent 30 秒後] 找到 issue #47781，官方 workaround 是 @Bean JdbcDialect override
>
> ❌ 做了 4 次嘗試後才派 agent = 浪費 6 分鐘 CI + 30 分鐘配置改改看
> ✅ 第 1 次失敗就派 = 30 秒拿到 known good solution，直接套

---

## 7. Hand-wave 解釋現象（v1.2 新增）

### 觸發信號

即將從你嘴裡冒出「**X 自動觸發 / Y 是預設行為 / framework 偵測 Z 就 W**」這類「魔法」措辭，但你**無法 5 字內給出來源**（issue#XXX / docs URL / source line）。

### 為什麼會卡

debug 過程的每一步都建立在「我相信 framework 這樣 work」上。一個錯誤的 explanation **不是中性的觀察**，是**會 cascading 到所有後續步驟**的假設：

- 假設「BP_NATIVE_IMAGE 是 redundant，Paketo 自動觸發 native」 → 推論「拿掉 env var 也行」 → 設計 fix 方向 → 但實際真因是 builder order group 結構，跟「自動偵測」無關
- 你以為自洽 = 對，user 一句「**真的是這樣嗎？**」/「**要做一下功課**」直接 expose

直覺自洽 ≠ 對。Hand-wave explanation 是 ground 缺失的 anti-pattern，**比 wrong fix 更危險** — wrong fix 你會看到 error 不變立刻發現；wrong explanation 會誤導你選錯整套 fix 方向。

### 怎麼跳脫

任何時刻你即將說「自動 / 預設 / 偵測 / 就會」這類詞 → **停下查源**：

1. WebFetch / WebSearch 該 framework 該 phase 的官方 doc
2. 派 Agent research 找對應 source / issue
3. 確認後再講；找不到 confidence 來源就明說「我不確定，需要查」

如果 user 已經在質疑（「真的是這樣嗎」/「做一下功課」/「參考一下官網」）→ **這就是 trigger 信號**，立刻派 research。

### 真實對話片段

> Assistant: BP_NATIVE_IMAGE 是 redundant，因為 paketo-buildpacks/spring-boot 5.36.2 偵測 jar 裡的 META-INF/native-image/ metadata，自動觸發 native build...
> User: BP_NATIVE_IMAGE=true 本來就預設有的吧, 要做一下功課
> Assistant: [派 research agent] → 30 秒後修正：真因是 builder-noble-java-tiny 第一個 order group `java-native-image` required（CNB lifecycle 機制），跟「自動偵測」是兩回事
>
> ❌ Hand-wave explanation 順但錯 = cascading 到下游 fix 方向錯
> ✅ 任何「自動 / 預設 / 偵測」措辭 → 先 research grounding 再講

---

## 8. 自創 config / property name（v1.2 新增）

### 觸發信號

即將寫新的 Gradle property / config key / env var name（`aotProfiles` / `appMode` / `buildEnv` 等），**沒先 grep 上下游有無同義標準名**。

### 為什麼會卡

每一層自創一個名字 = 每加一層認知負擔：
- yaml: `spring.profiles.active`
- env var: `SPRING_PROFILES_ACTIVE`
- args: `--spring.profiles.active=...`
- Gradle property: `aotProfiles` ← **斷裂點**

User / 接手者讀 build.gradle.kts 看到 `aotProfiles`，要在腦中轉換「喔這個對應 spring.profiles.active」。3 層轉 1 層 noise，5 層轉 4 層 noise — 不是線性疊加，是 cognitive friction × 每次 context switch。

### 怎麼跳脫

**新 config key / property name = 先 grep 上下游**：

```bash
# 上下游已有同義標準名嗎？
grep -rn "spring.profiles.active\|SPRING_PROFILES_ACTIVE" .
```

有 → **直接複用**。Gradle property 也支援 dotted name (`-Pspring.profiles.active=aot,local`)，跨層命名一致零認知負擔。

只有「上下游真的都沒有對應名 + 你這層發明的概念是新的」才自創。99% 場景是上游已存在標準名。

### 真實對話片段

> Assistant: Gradle property 我用 `-PaotProfiles=aot,local` 控制 ProcessAot 的 active profile
> User: aotProfiles 應該更通用點 或是跟原本一樣 參考一下 spring 官網
> Assistant: [grep + 查 Spring docs] → 改用 `-Pspring.profiles.active=aot,local`，跟 yaml / env var / args 全跨層同名
>
> ❌ 自創 `aotProfiles` = 多一層認知負擔，未來接手者要記「這對應 spring.profiles.active」
> ✅ 直接複用 `spring.profiles.active` = 跨 yaml / env var / args / Gradle property 全同名，零負擔

---

## 反模式互相疊加 = 災難

這 8 個反模式不是獨立的，而是**會互相強化**：

```
Stack trace 只讀最後一行（→ 抓錯方向）
    +
跳過 research（→ 不知道有 known workaround）
    +
本機沒重現（→ 循環貴）
    +
Config 改改看（→ 換寫法不換思路）
    +
假設 framework 知識通用（→ 永遠繞不過 phase 差異）
    +
Hand-wave 解釋現象（→ 錯 explanation cascading 到 fix 方向）
    +
自創 config name（→ 跨層斷裂後人重看要轉換）
    +
突破後不 bisect（→ noise 進 commit）

= 2 小時繞彎 + 累積 permanent debt
```

每個單一反模式的代價可能 30 分鐘，但**疊加效應是非線性的**。

打破任一個都能顯著縮短 cycle：
- 第 1 次失敗派 agent → Phase 5 假設質疑用得上
- 完整讀 stack trace → 不會抓錯方向
- 本機重現 → 循環便宜，可以多試幾次
- 第 2 次同 error 停手 → 不會 noise 累積
- 解釋現象前先 ground → 不會 wrong explanation 連帶 wrong fix 方向
- 命名前先 grep 上下游 → 不會跨層斷裂
- 突破後 bisect → noise 不進 commit
