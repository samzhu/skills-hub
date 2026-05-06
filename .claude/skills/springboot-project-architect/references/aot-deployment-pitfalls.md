# Spring Boot AOT 部署陷阱

> **適用情境**：Spring Boot 4 + AOT processing（`processAot` / `bootBuildImage` /
> Paketo training run / GraalVM native-image）部署到 Cloud Run / Kubernetes / 任
> 何「容器啟動就要正確」的環境。
>
> AOT mode 的核心特性：**conditions 在 build 階段被 freeze，runtime 不重評**。
> 這個性質讓「runtime 載入新 profile / 新 property 後神奇修好」的經驗失效，下面
> 列出的陷阱大多都是這個性質造成的。
>
> 官方參考：
> - https://docs.spring.io/spring-boot/reference/packaging/aot.html
> - https://docs.spring.io/spring-framework/reference/core/aot.html

---

## §1 AOT profile 與 runtime profile 必須對齊

### 症狀

部署到 Cloud Run，AOT build 用 `gcp,aot` 跑，runtime 啟動 `gcp,aot,lab` —
Spring 起來時：

```
APPLICATION FAILED TO START
Description:
Parameter 0 of method setFilterChains in WebSecurityConfiguration required
a bean of type 'ClientRegistrationRepository' that could not be found.
Action:
Consider defining a bean of type 'ClientRegistrationRepository' in your
configuration.
```

或類似 `UnsatisfiedDependencyException` 抓不到某 bean。

### 根因

Spring Boot AOT processing 跑 `refreshForAotProcessing` 時，會用**當下啟用的
profile** 評估每個 `@Conditional*` 註解，把通過的 bean factory 烤進 AOT-generated
context。

如果 build 階段（`gcp,aot`）載入的 yaml 沒提供條件成立所需的 property，例如
`spring.security.oauth2.client.registration.skillshub.*` 在 lab.yaml 裡，build
看不到 → `OAuth2ClientAutoConfiguration` 的
`@ConditionalOnProperty(prefix="spring.security.oauth2.client.registration")`
評估 false → `ClientRegistrationRepository` bean 沒被烤進去。

Runtime 雖然 lab profile 有掛上、property 也讀到了，但 AOT mode **不會重新評估
conditions**。沒被烤進去就是沒有，runtime 救不回來。

### 修法

**Build 命令必須帶上 runtime 會用到的 behavior profile**：

```bash
# Before
./gradlew bootBuildImage -Pspring.profiles.active=gcp,aot

# After
./gradlew bootBuildImage -Pspring.profiles.active=gcp,aot,lab
```

CI / cloudbuild.yaml 同理。

### Trade-off：image 變得 per-environment

Image 在 build 階段烤進「LAB-specific」context。後續部署 prod 必須**獨立 build**
帶 `gcp,aot,prod`。這是 AOT mode 必然的代價，與「per-environment image」的部署
原則一致（cf. 12-factor「build 與 release 分離」於 AOT mode 失效，build 即 release）。

### 反模式

- **不要**期待「runtime 換 profile 救回 AOT 漏烤的 bean」— 烤進去就是烤進去，沒就是沒。
- **不要**為了「image 不要 per-env」而把所有 profile 全塞 AOT — 不同 profile
  間 property 衝突會比 build 多 image 還難 debug。

---

## §2 自我引用 placeholder 在 AOT 階段炸（runtime 沒事）

### 症狀

```
Caused by: PlaceholderResolutionException: Circular placeholder reference
'skillshub.storage.bucket' in
"${skillshub.storage.bucket:skillshub-packages-lab}" <--
"${skillshub.storage.bucket:skillshub-packages-lab}"
```

對應 yaml：

```yaml
skillshub:
  storage:
    bucket: ${skillshub.storage.bucket:skillshub-packages-lab}   # 自我引用
```

Runtime 沒爆是因為 Cloud Run env var `skillshub.storage.bucket=actual-bucket`
比 yaml 早 resolve，placeholder 拿到 env 值不是自己。

AOT 階段沒 env var → placeholder 嘗試 resolve 自己 → circular。

### 根因

`${same.key:default}` pattern 在 Spring 屬性解析過程中：
1. 遇到 placeholder `${same.key:default}`
2. 試圖在 PropertySources 找 `same.key`
3. 找到的就是當前 yaml 那行 → 又是 `${same.key:default}` → 進入循環

Runtime 之所以 work，是因為 env var 先解析（優先級高於 yaml），placeholder
先被替換成真實值，沒進到循環。

### 修法

**寫死 fallback 值**，移除自我引用：

```yaml
# Before
skillshub:
  storage:
    bucket: ${skillshub.storage.bucket:skillshub-packages-lab}

# After
skillshub:
  storage:
    bucket: skillshub-packages-lab
```

Spring 屬性優先序（env > yaml > Java `@DefaultValue`）已經保證 env var 會蓋過
yaml。`${same.key:default}` 完全沒必要。

### 反模式

- **不要**在 yaml 寫 `${same.key:default}` 想著「給 env var 一個 escape hatch」—
  Spring 本來就會 escape，placeholder self-reference 是純加 AOT 風險。
- **不要**用 `${OTHER_ENV_NAME:fallback}` 的 pattern 期待跨 env 工作 — env var
  名 = property dot key 才是 Spring relaxed binding 的標準模式（cf.
  config-design-principles.md §6）。

---

## §3 OAuth2 Client 等「需要驗證 non-empty」的 property，AOT 階段缺值會 fail

### 症狀

AOT build 階段：

```
Caused by: ConfigurationPropertiesBindException: Could not bind properties
to 'OAuth2ClientProperties' : prefix=spring.security.oauth2.client
...
Caused by: IllegalStateException: Client id must not be empty.
```

或 runtime startup 時抓不到 `ClientRegistrationRepository`（因為 AOT 階段
property 不全 → auto-config skip）。

### 根因

某些 Spring 元件對 property 有「non-empty 強制驗證」：

| 元件 | 強制要求 | 來源 |
|------|---------|------|
| `OAuth2ClientProperties` | 每個 registration 的 `client-id` 非空 | `OAuth2ClientProperties.validate()` |
| `JwtProperties`（自訂） | issuer-uri 結構合法 | bind 階段 |
| `DataSourceProperties` | url / driver 至少一個有值 | `DataSourceAutoConfiguration` |

AOT 階段的 property 來源 = 當下 active profile 的 yaml。如果真實值來自
runtime env var（從 Secret Manager 拉），AOT 看不到 → validation 失敗。

### 修法：AOT-only stub

在 `application-aot.yaml` 提供 stub 值，runtime env var 覆蓋：

```yaml
# application-aot.yaml
# AOT 階段 OAuth2ClientProperties.validate() 強制 client-id 非空。
# 真實值由 Cloud Run env var 從 Secret Manager 注入；此處 stub 只為通過 AOT
# binding，runtime 不會被使用（env var 優先級高於 yaml）。
spring:
  security:
    oauth2:
      client:
        registration:
          skillshub:
            client-id: aot-stub-client-id
            client-secret: aot-stub-client-secret
```

```yaml
# application-lab.yaml — 真正提供 scope / redirect-uri / provider 等結構
spring:
  security:
    oauth2:
      client:
        registration:
          skillshub:
            scope: openid,email,profile
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            client-authentication-method: client_secret_basic
            authorization-grant-type: authorization_code
        provider:
          skillshub:
            issuer-uri: https://accounts.google.com
```

```yaml
# Cloud Run service.yaml — 真實 secret 注入
env:
  - name: spring.security.oauth2.client.registration.skillshub.client-id
    valueFrom:
      secretKeyRef:
        name: app-oauth-client-id
        key: latest
  - name: spring.security.oauth2.client.registration.skillshub.client-secret
    valueFrom:
      secretKeyRef:
        name: app-oauth-client-secret
        key: latest
```

### Why this works

- AOT build：`gcp,aot,lab` profile 都載入。aot.yaml 給 client-id/secret stub
  讓 validation 過 → `ClientRegistrationRepository` bean factory 烤進 context。
- Runtime：env var 優先級 > yaml，覆蓋 stub 為真實 secret。bean 用真值構建。

### 反模式

- **不要**把真實 client-secret 放 yaml — secret leak 風險，且每換 secret 都要
  重 build image。
- **不要**期待「AOT 看不到 property → bean 不烤 → runtime env var 有值就會自動
  烤」— AOT mode 不重評 conditions，bean 沒烤就是沒。

---

## §4 `aot` profile 會 leak 到 runtime

### 觀察

部署到 Cloud Run，`spring.profiles.active=lab,gcp`，啟動 log 顯示：

```
The following 3 profiles are active: "gcp", "aot", "lab"
```

`aot` 不在 deployment 的 active profile 設定裡，但仍出現。

### 原因

Spring Boot AOT mode 在 build 階段生成的 `__ApplicationContextInitializer` 會
呼叫 `addActiveProfile("aot")`，把 aot profile 寫進 runtime context。這是 AOT
runtime 行為的一部分（讓 AOT-specific bean overrides 在 runtime 持續生效）。

### 影響

`application-aot.yaml` 的內容在 runtime 也會被載入。Profile 載入順序：

```
gcp → aot → lab    （deployment 設 lab,gcp，aot 被 AOT runtime inject）
```

aot.yaml 裡的設定如果 runtime 不該套用，必須被**後載入**的 profile 覆蓋。

### 實務 check list

| aot.yaml 寫的 | 後續 profile 必須覆蓋嗎？ | 為什麼 |
|---------------|--------------------------|--------|
| `spring.cloud.gcp.secretmanager.enabled: false` | gcp.yaml 必須 `enabled: true` | runtime 要拉 secret，AOT 不能拉 |
| `spring.autoconfigure.exclude:` Modulith actuator 等 | 不用 | runtime 也不需 Modulith actuator |
| OAuth client-id stub | 不用（env var 覆蓋） | runtime env > yaml |
| AotStubConfig DataSource | 由 `@Profile("aot")` Java config 自動覆蓋 | aot profile leak 但 stub bean 在 runtime 被真 DataSource 蓋掉 |

### 反模式

- **不要**在 `application-aot.yaml` 放「runtime 也想要的設定」— 後續 profile
  載入順序不一定能保證覆蓋；應該放對應的 behavior / infra profile。
- **不要**為了「aot leak 到 runtime」而拒絕用 aot.yaml — 它是 AOT 階段必要的
  設定槽位，只是要意識到 leak 性質。

---

## §5 AOT-friendly 配置設計檢查清單

新加的 yaml / property 進 production build 前，先過這些檢查：

```
- [ ] 沒有 ${same.key:default} 自我引用（§2）
- [ ] OAuth / DataSource / 等需要強制驗證的 property，build 階段是否拿得到值？
       拿不到 → 在 application-aot.yaml 提供 stub（§3）
- [ ] runtime 真實值的注入路徑想清楚：env var? Secret Manager mount? yaml 寫死?
       env var > yaml > Java @DefaultValue 的優先序，能否確保 stub 被覆蓋?
- [ ] cloudbuild AOT step 的 -Pspring.profiles.active 是否含所有 runtime 需要
       的 behavior profile？（§1）
- [ ] aot profile leak：application-aot.yaml 的設定能被後載入 profile 蓋掉嗎？
       不能蓋的（autoconfig 排除等）是否真的 runtime 也想要？（§4）
- [ ] 本機 ./gradlew processAot 會 fail（沒 GCP creds 時）—— 此非 Spring 錯，
       cloudbuild 環境有 metadata server 可正常跑，不要拿 local 結果評斷
- [ ] 第一次 deploy 失敗常 cascade：fix 一個再 build 又冒下一個。一輪 build
       7 分鐘，**先把所有 §3 / §4 stub 補齊**再 trigger，省 build round
```

---

## §6 Diagnostic 快速 path

### 症狀 → 看哪裡

| 症狀 | 第一手診斷 |
|------|----------|
| `APPLICATION FAILED TO START` + `bean of type X could not be found` | §1 — AOT profile 是否含真實 runtime profile？X 的 auto-config 是哪個 `@ConditionalOnProperty`？ |
| `PlaceholderResolutionException: Circular placeholder` | §2 — 看那個 key 在 yaml 是不是 `${same.key:default}` 自我引用 |
| `IllegalStateException: ... must not be empty` 在 build 階段 | §3 — AOT 缺真實值，補 aot.yaml stub |
| `AopConfigException: Advisor sorting failed` 在 AOT 跑時 | 通常 root cause 在「下面被吞」的 dependency 鏈，往 stack 底部找 `Caused by` 直到最深層 — 多半是 §3 類問題 |
| Runtime startup OK 但某 endpoint 404（如 `/publish`） | 跟 AOT 無關，常是 SPA fallback / static resource handler 沒設定，cf. SecurityConfig.permitAll + @Controller "forward:/index.html" pattern |
| Runtime 起得來但 OAuth callback `redirect_uri_mismatch` | `{baseUrl}` URI template 在 reverse proxy 後解析錯（容器看到 http://localhost:8080）。修：`server.forward-headers-strategy: framework` 信任 X-Forwarded-* header，或 service.yaml env 寫死 redirect-uri |

### 看 Cloud Run revision 起不起來的 log

```bash
gcloud run services logs read SERVICE --region=REGION --limit=200 --project=PROJECT
```

抓 `APPLICATION FAILED TO START` 段往下看 `Description:` 跟 stack trace 最深的
`Caused by:`。

### 確認當前 serving image

```bash
gcloud run services describe SERVICE --region=REGION \
  --format='value(spec.template.spec.containers[0].image)'
```

部署後測試一直失敗時，**先確認 revision 真的更新到新 image**（自己改完 yaml
忘記 replace 的情境意外多）。

---

## §7 Cascade build 失敗的避免心得

AOT build 一輪 6–8 分鐘（Paketo + Gradle download dep + JVM AOT 處理）。失敗後
revise → rebuild → 再失敗，一個下午燒 5–6 輪是真實成本。

### 一輪 build 前的審視

1. **所有 §3 stub 是否補齊？** — OAuth、DataSource、其他 ConfigurationProperties
   有 non-null/non-empty 驗證的，全部清點一遍 yaml 是否有提供值。
2. **本機 `./gradlew processAot -Pspring.profiles.active=...` 跑得過嗎？** —
   如果跑得過（且本機有 ADC 或 `secretmanager.enabled=false`），cloudbuild
   大機率也會過。
3. **看「最深 Caused by」一次解 N 個** — Spring stack trace 嵌套很深，最深的
   `Caused by` 才是 root cause。其他 `Caused by` 是上層抓不到 bean 的次生
   錯誤，root cause 修了通常一起消。
4. **比對 `gcp,aot` build 之前 vs 加 `lab` 後的差異** — 加 profile 後 yaml
   property 多了一批，逐個看哪些有 placeholder / 哪些有強制驗證。

### Cloud Build 觀察重點

```
> Task :processAot          ← 看到這個就是 AOT context refresh 階段
2026-...  INFO ... The following N profiles are active: "..."   ← 確認 profile
2026-...  INFO ... Started ... Application                       ← 過了 = AOT OK
Caused by: ...                                                   ← 沒過 = root cause
```

`Task :processAot FAILED` 之後 build 會 abort，不會繼續到 native compile。所以
AOT 失敗 = 看 stack trace 最深 Caused by 即可。

---

## 相關參考

- `config-design-principles.md` — 雙層 profile 設計、預設值四層、placeholder
  skeleton（避開 §2 自我引用陷阱的正解）
- `cloud-gcp-secrets.md` — Secret Manager mount 模式（§3 stub 對應的真實值
  注入路徑）
- `anti-patterns.md` — 跨領域反模式合集
