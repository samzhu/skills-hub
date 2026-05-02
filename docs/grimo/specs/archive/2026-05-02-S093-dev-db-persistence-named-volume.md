# S093 — Dev DB persistence: compose named volume + start-only lifecycle

> **Status**: shipped
> **Type**: dev infra polish (close S092 ship-period observation + handover Layer 1 step 4 follow-up)
> **Estimate**: XS / 2 pts

## §1 Problem

Dev DB（pgvector container）每次 backend graceful restart 都被 down + 重 up，**資料消失**：

1. `backend/compose.yaml` 沒 named volume → pgvector image 預設 `VOLUME /var/lib/postgresql/data` 自動建 anonymous volume；每次 `docker compose up` 都建新 anonymous volume，舊的變 dangling
2. `application-local.yaml` `spring.docker.compose.lifecycle-management: start-and-stop` → bootRun graceful stop 觸發 `docker compose down`，container 連同 mount 被 unwind

User 觀察「容器應該還是有保存的吧」反映的期待：dev 環境 DB 應跨 session 持久，方便：
- 累積測試 corpus（103+ skills 是 round-by-round upload 而成；reset 等於從頭來）
- 驗證跨 session 行為（如 outbox drain、scheduled jobs 預期累積、long-tail data quality audit）
- 避免 fresh DB 加重 onboard cognitive load

S092 ship 期間實際發現上 session abnormal-exit（Java OOM/kill）跳過 spring shutdown hook 才意外保住 16 hours 的 container — 這是脆弱 happy accident，不是 architecture guarantee。

## §2 Approach

兩處 minimum diff：

**(1) `backend/compose.yaml`**：pgvector service 加 named volume mount + 顯式 top-level volume 宣告
```yaml
services:
  pgvector:
    volumes:
      - 'pgvector-data:/var/lib/postgresql/data'   # 取代 anonymous default
volumes:
  pgvector-data:                                   # 顯式 named；docker 自動 manage 路徑
```

Project prefix 後實際名稱為 `backend_pgvector-data`。`docker compose down`（不加 `-v`）只移除 container，volume 仍保留；下次 `up` 接回。

**(2) `backend/src/main/resources/application-local.yaml`**：lifecycle 改為 `start-only`
```yaml
spring:
  docker:
    compose:
      lifecycle-management: start-only
```

`start-only` = bootRun start 拉 compose；bootRun stop **不** down container。配合 named volume 讓 dev DB 跨 session 完整持久。

捨棄方案：
- (A) `lifecycle-management: none` + 手動 `docker compose up`：違反 dev one-command 體驗
- (B) Named volume only + 維持 `start-and-stop`：每次 stop 容器仍消失（雖 volume 保留，但 container 重建慢）；start-only 跳過此 churn
- (C) `tmpfs` mount：data 在 RAM，重啟丟；違反持久要求
- (D) Bind mount 到本地路徑：容易 leak host file system / 增 backup pollution；named volume 由 docker manage 更乾淨

## §3 Acceptance Criteria

| AC | Case | Expected |
|----|------|----------|
| AC-1 | `docker compose -f backend/compose.yaml config` | 解析正確，volumes section 含 `pgvector-data` 帶 project prefix `backend_pgvector-data` |
| AC-2 | gradle test (testcontainer-based) | 不受影響，299+/0 fail（testcontainer 用獨立 ephemeral PG，不走 compose.yaml） |
| AC-3 | bootRun start | spring-docker-compose 拉 pgvector + mock-oauth2；mount named volume；PG init scripts 跑（fresh DB on first apply）|
| AC-4 | bootRun graceful stop（Ctrl+C） | spring shutdown hook 在 `start-only` 下**不** down compose；`docker ps` 顯示 pgvector 仍 running |
| AC-5 | bootRun re-start | spring-docker-compose 偵測既有 running container，skip up；DB data 完整保留（前 session upload skill 仍可 GET） |
| AC-6 | 手動 `docker compose down`（不加 -v） | container 消失但 named volume 保留；再 `docker compose up` 接回完整資料 |
| AC-7 | 手動 `docker compose down -v` | volume 也清；下次 up = fresh DB（reset 路徑明確） |

## §4 Implementation

兩 file 變更：

```diff
--- backend/compose.yaml
+++ backend/compose.yaml
@@ services.pgvector
+    volumes:
+      - 'pgvector-data:/var/lib/postgresql/data'

@@ end of file
+volumes:
+  pgvector-data:
```

```diff
--- backend/src/main/resources/application-local.yaml
+++ backend/src/main/resources/application-local.yaml
@@ spring.docker.compose
-      lifecycle-management: start-and-stop
+      lifecycle-management: start-only
```

註解寫 why：why named volume vs anonymous（避免 churn）/ why start-only（dev DB 跨 session 持久）/ 手動清理 hint（`docker compose down -v`）。

## §5 Test plan

- `docker compose config` validate yaml 解析
- gradle test (testcontainers) 跑全 backend 299+ tests — 應不受影響（testcontainers 不用 compose.yaml 路徑）
- **Cross-session smoke** 留 user 下次 restart 觸發（不在本 ship 內主動 restart 以免 lose 既有 103-skills corpus）：
  1. user 觸發 `Ctrl+C` 或 `kill <pid>` graceful stop（舊 yaml 在 JVM 仍 start-and-stop → compose down 一次 + lose 既有 anonymous data；首次 transition 必然 fresh）
  2. `./gradlew bootRun -x processAot` 啟動 → 新 yaml load → start-only + named volume → fresh DB（首次）
  3. 之後 stop+start 循環 → DB 持久（103 skills + 累積 vector_store + outbox 全活）

## §6 Verification

- yaml lint: `docker compose config` 解析含 `volumes.pgvector-data:` + project prefix `backend_pgvector-data` ✓
- gradle test: 299+/0 fail（待執行；本 spec 純 dev infra 配置，不動 production code）
- Backend 當前狀態（舊 yaml）保持 :8080 healthy + 103 skills DB intact — 不主動 restart trigger transition

## §7 Result

- yaml lint ✓ (`backend_pgvector-data` named volume confirmed in `docker compose config` output)
- **gradle test 299/299 PASS / 0 fail** ✓（`./gradlew test` BUILD SUCCESSFUL in 2m 32s；testcontainer-based 不受 compose.yaml runtime 配置影響）
- 當前 backend session 不 break（持續 :8080 / 103 skills DB intact）
- **Transition cost**：首次 user-triggered restart 後一次 fresh DB；自此 onwards data 持久
- **手動清理路徑**: `docker compose -f backend/compose.yaml down -v` 一次清掉 container + volume

ship as **v2.68.0** (M87)。
