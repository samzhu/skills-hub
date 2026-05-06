# S114c — ACL Infra: Connection Pool 調校 + 讀取副本設計

**Status:** 📋 planned
**Size:** S(4)（trim from M(8)；read replica + PgBouncer defer to prod-scale）
**Depends on:** S114b ✅（Caffeine cache — ACL DB 熱點路徑已緩解）
**Target version:** v4.15.0

---

## §1 Goal

S114b 的 Caffeine JVM cache 已大幅降低 ACL SQL 查詢頻次（TTL 300s，cache hit → no DB）。本 spec 補完 HikariCP 連線池調校的細節，讓 lab/prod 環境的連線設定有明確依據，並記錄 read replica + PgBouncer 的評估結論（為何 MVP 不需要）。

**核心問題：** Cloud SQL `db-f1-micro` 連線上限 ≈ 25。目前 HikariCP `maximum-pool-size=3` per Cloud Run instance，但 `minimum-idle` 未設（預設等於 maximum-pool-size，開機即借滿 3 條）。在 Cloud Run scale-to-zero / cold-start 場景，未調 `minimum-idle` 會在 0 → 1 instance 時立刻搶 3 條連線，多 instance 同時 scale-up 可能超出 Cloud SQL 連線上限。

**不是（defer）：** 架設 Cloud SQL read replica（另一 Cloud SQL instance + cost）；PgBouncer sidecar（Cloud Run 多容器配置複雜度高；HikariCP 已是 JVM 層 pool）。

---

## §2 Approach

### 2.1 現狀評估

| 項目 | 現況 | 問題 |
|------|------|------|
| HikariCP `maximum-pool-size` | dev=10 / lab,prod=3 | ✅ 已針對 Cloud SQL micro 調小 |
| HikariCP `minimum-idle` | 未設（= maximum） | Cloud Run scale-up 時全借 → 多 instance 同時 cold-start 可能超 Cloud SQL 限制 |
| HikariCP `connection-timeout` | 30 000 ms | ✅ 合理 |
| ACL 讀取 | Caffeine 300s TTL | ✅ S114b 已緩解 DB 讀壓 |
| `initialization-fail-timeout` | 未設（預設 1s） | GCP 啟動時 Cloud SQL Proxy 需幾秒暖機 — 預設可能導致 false-fail |

### 2.2 HikariCP minimum-idle 調校

在 `application-lab.yaml` 和 `application-prod.yaml` 加：

```yaml
spring:
  datasource:
    hikari:
      minimum-idle: 1           # scale-to-zero 後 cold-start 只借 1 條，避免多 instance 同時 cold-start 搶光連線
      initialization-fail-timeout: 60000   # Cloud SQL Auth Proxy 暖機最多 60s；避免假死
```

`application-dev.yaml` 保持 `minimum-idle` 不設（= 10；本機 Docker Compose 無限制問題，保持開發快速）。

### 2.3 Read Replica + PgBouncer 評估（defer 理由）

| 方案 | 評估 | 結論 |
|------|------|------|
| Cloud SQL read replica | 需額外付費 Cloud SQL instance + Cloud SQL Auth Proxy 新增 port / sidecar；Spring `AbstractRoutingDataSource` read/write 路由需測試 | ⏸ MVP 不需要。S114b cache hit rate > 90% 時 ACL 讀壓已可忽略。候補 production-scale spec |
| PgBouncer | Cloud Run sidecar 配置複雜；HikariCP 已在 JVM 層做 pool；PgBouncer 主要解決 PostgreSQL thread-per-connection 限制（對 Cloud SQL managed 無額外益處）| ⏸ MVP 不需要。Cloud SQL 已有 server-side connection tracking；再加 PgBouncer 為 over-engineering |

**結論**：MVP 階段 read replica + PgBouncer 不帶來可量化收益；S114b cache + minimum-idle=1 足夠應對 Cloud Run scale 場景。待 QPS 達 100+ req/s 再評估 read replica。

### 2.4 Cloud SQL 連線上限文件

**`db-f1-micro`**：max_connections ≈ 25（Cloud SQL 依 RAM 算）。  
三個 Cloud Run instances × 3 connections = 9 → 安全邊界 ✅。  
若 scale 到 8 instances 以上（8 × 3 = 24 ≥ 25）可能超限 → Cloud Run `--max-instances` 建議設 `7` 或升級 `db-g1-small`。

### 2.5 Trim / Defer

- **本 spec：** `minimum-idle=1` + `initialization-fail-timeout=60000`（lab + prod profile）
- **Defer：** read replica architecture（候補 S114d）；PgBouncer（不再規劃；over-engineering for MVP）

---

## §3 Acceptance Criteria

**AC-1 — minimum-idle 設為 1**
```
Given: application-lab.yaml / application-prod.yaml
When:  讀取 Hikari 設定
Then:  spring.datasource.hikari.minimum-idle = 1
```

**AC-2 — initialization-fail-timeout 設為 60000**
```
Given: application-lab.yaml / application-prod.yaml
When:  讀取 Hikari 設定
Then:  spring.datasource.hikari.initialization-fail-timeout = 60000
```

**AC-3 — 啟動不 fail（regression）**
```
Given: ./gradlew bootRun（local profile，Docker Compose DB）
When:  啟動
Then:  Spring Context 啟動成功；no DataSource initialization exception
```

---

## §4 File Plan

| File | Action |
|------|--------|
| `backend/config/application-lab.yaml` | modify — 加 `minimum-idle: 1` + `initialization-fail-timeout: 60000` |
| `backend/config/application-prod.yaml` | modify — 同上 |
| spec doc（本檔）| archive after ship |

---

## §5 Test Plan

- **AC-1/AC-2（config check）**：grep yaml 確認值存在
- **AC-3（smoke）**：`./gradlew bootRun` 啟動成功（local profile）；`./gradlew compileJava` 無錯
- **Regression**：`npm test`（frontend 不受影響）
