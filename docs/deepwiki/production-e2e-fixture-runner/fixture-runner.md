# Fixture Runner / Service

## Responsibility

Fixture runner 接手 `e2e/tests/_fixtures.ts:36-84` 現在做的三件事：

1. Reset test data.
2. Seed skills.
3. Seed download events.

差異是：runner 不跑在 production app process 裡，不提供 public ingress，不被正式 deploy job 部署。

---

## 推薦形態：`e2e/` TypeScript Runner First

不考慮成本但追求乾淨邊界，第一版建議把 runner 放在 `e2e/fixtures/`，由 Playwright setup project 直接呼叫 TypeScript module，不先做 fixture HTTP service。

```bash
cd e2e
npx playwright test --project "setup fixtures"
npx playwright test --grep @happy-path
```

理由：

| 點 | `e2e/` TypeScript runner | HTTP fixture service |
|---|---|---|
| 攻擊面 | 無 port | 有 private port，需要 network policy |
| Playwright 整合 | setup project 直接 import runner functions | setup project 用 HTTP |
| CI artifact | log + manifest 檔 | log + service log + manifest |
| 適合本 repo | 是，現有 `e2e/` 已是 Node/Playwright workspace | 未來 remote E2E 才需要 |

若未來要遠端跑 E2E，再把同一套 command handler 包成 private service。

---

## Runner Module Boundary

建議把第一版 runner 放在 `e2e/fixtures/`：

```text
e2e/
├── compose.e2e.yaml
└── fixtures/
    ├── setup.fixtures.ts
    ├── teardown.fixtures.ts
    ├── manifest.ts
    ├── production-api-seed.ts
    ├── projection-seed.ts
    └── db-guard.ts
```

這樣不用先把 `backend/` 改成 multi-project Gradle；fixture code 也不可能被 `bootJar` / `bootBuildImage` 打進 production app。

---

## Fixture Manifest

Runner seed 後寫 `e2e/results/fixtures.json`：

```json
{
  "runId": "2026-05-19T12-34-56Z",
  "profile": "paged",
  "skills": [
    {
      "id": "skill-uuid",
      "name": "docker-compose-helper",
      "author": "alice",
      "detailPath": "/skills/skill-uuid"
    }
  ],
  "downloads": [
    {
      "skillId": "skill-uuid",
      "count": 5,
      "daysAgo": 7
    }
  ]
}
```

Playwright tests 不再用 `seedSkill(request, ...)` 回傳 id，而是讀 manifest：

```ts
const fixtures = await readFixtureManifest();
await page.goto(`/skills/${fixtures.skills[0].id}`);
```

---

## Reset Strategy

最佳順序：

| Rank | Strategy | Command 行為 | 何時用 |
|---|---|---|---|
| 1 | Create/drop database per run | `CREATE DATABASE skillshub_e2e_<runId>`，跑 Flyway，測完 drop。 | CI / release gate。 |
| 2 | Create/drop schema per run | `CREATE SCHEMA e2e_<runId>`，JDBC URL `currentSchema=e2e_<runId>`，測完 drop schema cascade。 | PostgreSQL instance 共用但要隔離資料。 |
| 3 | Restore SQL snapshot | restore baseline dump，再 seed delta。 | 大量 fixtures 時加速。 |
| 4 | Truncate allowlist outside app | runner 用 DB admin 清表。 | 過渡期；仍比 app endpoint 好。 |

目前 `backend/compose.yaml:15-16` 用 named volume 保留 dev DB；E2E 不應再依賴該 volume。E2E compose 應使用 anonymous volume 或 per-run container。

---

## Seed Strategy

### Skill Seed

優先走 production API：

```text
Fixture runner
  -> POST /api/v1/skills/upload
     multipart: file, skillName, version, category, visibility
  -> response { id }
```

`frontend/src/api/skills.ts:384-405` 顯示前端正式 upload 也是打 `/api/v1/skills/upload` multipart。後端 `SkillCommandController.java:95-127` 會使用正式 auth context，service `SkillCommandService.java:105-165` 完整處理 publish。

若 CI 使用 LAB/dev-like auth，可讓 runner 帶 cookie/header 或在 test env 使用固定 lab principal。若要測 OAuth，runner 應透過 mock IdP 建 authenticated storage state。

### Download Events Seed

下載事件目前沒有 public seed API，且 `AnalyticsService.java:40-62` 與 `AnalyticsService.java:70-86` 直接讀 projection/counter。Runner 可 direct SQL：

```sql
INSERT INTO download_events (id, skill_id, version, downloaded_at)
VALUES (:id, :skillId, :version, :downloadedAt);

UPDATE skills
   SET download_count = download_count + :delta
 WHERE id = :skillId;
```

這段不能在 production app controller 裡，只能在 fixture runner 裡。

### Quality Score / Security Report Seed

`E2EQualityJudgeConfig.java:12-20` 現在讓 e2e profile 用 deterministic judge。方案 D 下 production app 不載這個 bean；兩個選擇：

1. 直接接受 UI 顯示「評分計算中」狀態，E2E 不 assert deterministic score。
2. Runner direct SQL seed `skill_scores`，讓 UI 顯示固定分數。

如果要測 LLM judge 真實流程，應拆成慢速 nightly / LAB gate，不混入 critical-path E2E。

---

## Runner Safety

| Safety Gate | 實作 |
|---|---|
| DB URL guard | runner 拒絕連線 host/db name 不含 `e2e` / `test` / ephemeral run id。 |
| Credential guard | runner 使用 `skillshub_e2e_owner`，不接受 prod service account secret。 |
| Network guard | fixture service 若存在，只 bind docker internal network，不 expose host public port。 |
| Manifest guard | 每次 seed 必寫 manifest；Playwright tests 只能讀 manifest，不猜 fixture id。 |
| Destructive command guard | `reset` command 必須要求 `--allow-destructive-e2e-reset` 或 CI env marker。 |

OWASP Authorization Cheat Sheet 要求 deny-by-default；這裡的落點是 runner 對未知 DB / unknown env 直接拒絕，不靠使用者小心。
