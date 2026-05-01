---
topic: "Skills Hub /loop E2E test session — 51 ticks shipped S043-S070"
session_type: "development"
status: "in_progress"
date: "2026-05-01"
---

# Handover: Skills Hub /loop E2E test session — 51 ticks shipped S043-S070

## Layer 1 — Portable Summary

> 此 session 已 ship 28 個 spec（S043 ~ S070），跑完 51 個 /loop tick。System 達到全綠 baseline，新 bug 須從 user telemetry / 更深層 angle 找。

### Completed

- **70 specs in archive** (`docs/grimo/specs/archive/`)，最新 S070 v2.48.0
- **Backend**: 286 tests / 0 fail；7 modulith modules / 0 boundary violations
- **Frontend**: 10 tests / 0 fail；vitest coverage 維持
- **verify-all.sh**: V01-V06 全 PASS，line coverage 82.3%
- **Data integrity**: 100% consistent
  - download_count vs download_events 全 match
  - domain_events sequence per-aggregate 連續無 gaps
  - 無 orphan vector_store / skill_versions / domain_events / download_events
  - outbox `event_publication` pending=0
- **Storage hygiene**: storage 38 dirs / 46 zips = DB PUBLISHED+SUSPENDED skills / DB versions
- **i18n coverage**: 12/12 backend ErrorResponse codes 全有 frontend 翻譯
- **Defense-in-depth**: 5 層 backend default-error 防漏網（S045/S049/S051/S052/S057）
- **Real Chrome E2E**：tick 40 完整 happy path（手刻 ZIP via DataView → drop → form → submit → 201 → detail → download round-trip）
- **Persistent test log**: `.claude/progress/loop-e2e-test-coverage.md`（每個 tick 更新）

### Decisions

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| `retry: (count, err) => 4xx 不 retry` (S065 hotfix) | React Query v5.100.1 + Vite dev 共生 bug：retry on 4xx 觸發 fetchStatus='paused' hang | 改 SkillDetailPage logic 處理 paused — 反而 hang loading；networkMode='always' 設定生效但仍 paused（疑 framework bug） |
| Flyway V7 cleanup pre-S033 vector orphans (S070) | 一次性 historical drainage；prod 部署同樣自動清理 | startup @EventListener — 邏輯重；DELETE FROM SQL 在不同部署環境須手動 |
| Listener defense + aggregate validation 配對 (S069) | post-fix historical bad data drain + prevent NEW bad state | 只修 aggregate — 既有 stuck events 永久卡；只修 listener — 後續 race condition 仍可寫入 bad data |
| HTML5 pattern 不寫 `^...$` + char class 內 `.`/`-` 必須 escape (S067) | Chrome silent disable malformed pattern — 須實測 | 用 `pattern="^...$"` 看似 work 實際 silent fail；用 `[a-z.-]` 不 escape 也 silent fail |

### Next Steps

1. **若有新 cron tick fire**: 系統已達 baseline；新 bug 須從 user-reported / production telemetry / 更刁鑽 angle 找。可考慮：
   - **Frontend test coverage 補強**（目前 10 tests / 2 files；多 component 未測）— 屬 improvement 非 bug fix，可單獨 spec
   - **Concurrency stress test** — 多 user 同名上傳 / race conditions
   - **Error boundary** — React component 拋 exception 時 fallback UI
   - **ACL UI** — 目前無前端管理 page（admin endpoint 待設計）
2. **若 Chrome / backend 重啟**: 先 `cd backend && SPRING_PROFILES_ACTIVE=local,dev ./gradlew bootRun -x processAot &` + `cd frontend && npm run dev &`
3. **若需 cron 重設**: tick 51 cron `388844f0` 仍在；CronList 確認；新 session 須重設

### Lessons Learned

- **Map.of 不接受 null values** — Java 14+ enhancement；多處陷阱（S058 FlagService / S069 AuditEventListener）。Pattern：`?? ""` for nullable string fields OR `HashMap` conditional add
- **HTML5 pattern 兩陷阱**（S067 inline 註解）：(1) 不要寫 `^...$` (HTML5 自動 wrap)；(2) char class 內 `.` 與 `-` 必須 escape `\.\-`，否則 Chrome silent 停用整個 pattern → 任何輸入 valid
- **React Query v5 + Vite dev** retry on 4xx 會 paused hang — 解：retry conditional skip 4xx (S065 hotfix)
- **Spring Boot 4 property rename**: `server.error.*` → `spring.web.error.*`（S045）— yaml parse 不 warn 寫錯就 silent 失效
- **Modulith outbox 失敗 events 永久 stuck** if listener consistently fails；defense + aggregate validation 配對才能 closure
- **Storage cleanup gap**: dev DB resets 留 orphan files；prod 因無 DELETE skill endpoint 不會發生；Flyway 不適合清 filesystem
- **DOM event for tab switching with Radix UI**: 純 `.click()` 不 work — 需 PointerEvent + MouseEvent dispatch
- **HMR ApiError instanceof 失效**: Vite 模組重載產多 class instance；解：name-based `ApiError.is()` static type guard
- **JSON POST 預設 DRAFT** (architecture.md)：upload 透過 multipart `/skills/upload` 才會 PUBLISHED；測試時容易混淆
- 構建 minimal valid ZIP via DataView (tick 40 verified)：STORED method (no compression) + 30-byte LFH + 46-byte CDH + 22-byte EOCD = ~200 bytes minimum

### Session Summary

Session 從 tick 1 開始 long-running /loop testing，user 給的 prompt 是「進行完整的 E2E 測試」。Tick 1-43 在 previous session（context 已 compress）找到 28 個 bug 並 ship S043-S068。本 session 從 tick 44 (post-compaction) 接手，繼續 ship S069-S070 + 大量 health verification。系統現在 100% 健康：data integrity / outbox drained / vector orphans cleaned / modulith boundaries clean。在 tick 50 達 milestone，tick 51 跑 final verify-all 全綠後寫此 handover preempt context exhaustion。下一個 session takeover 後若 user 觸發 /loop，須優先評估是否真有新 bug 可找 — codebase 已達 ideal state。

---

## Layer 2 — Environment Details

| Property | Value |
|----------|-------|
| Branch | `main` |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Test Status | backend 286 / 0 fail; frontend 10 / 0 fail; verify-all.sh V01-V06 全 PASS |

### Uncommitted Changes

```
M .claude/loop.md
```

（loop.md 是 skill 觸發時產生的 transient 檔；可忽略 / git checkout 還原）

### Recent Commits

```
9a79e81 docs: tick 50 milestone + storage hygiene cleanup
d87088e docs: tick 48-49 progress (data integrity + modulith health verified)
fb5a426 docs: update tick 47 progress + Bug AE logged
b600962 feat(db): ship S070 — Flyway V7 Cleanup Pre-S033 Vector Orphans (M66 完成 v2.48.0)
abc6e0f docs: update tick 46 progress + Bug AD logged
```

### Key Files

- `.claude/progress/loop-e2e-test-coverage.md` — **READ FIRST**：累積 51 ticks 的 test coverage / bug ledger A-AE / known tech debt / next-tick 建議
- `docs/grimo/CHANGELOG.md` — 最新 v2.48.0 (S070)；上推可看 S043-S070 完整時間線
- `docs/grimo/specs/spec-roadmap.md` — Phase 4 已到 M66，總 story points ~512
- `docs/grimo/specs/archive/2026-05-01-S0{43..70}-*.md` — 28 個 ship 完整 spec（design + AC + 7-section result）
- `backend/src/main/resources/db/migration/V7__cleanup_pre_s033_suspended_vectors.sql` — 最新 migration（idempotent）
- `frontend/src/api/client.ts` — `ApiError.is()` static type guard pattern
- `frontend/src/main.tsx` — QueryClient 配置（retry 4xx skip + networkMode='always'）
- `backend/src/main/java/io/github/samzhu/skillshub/audit/AuditEventListener.java` — null-defense pattern
- `backend/src/main/java/io/github/samzhu/skillshub/shared/api/GlobalExceptionHandler.java` — 5 layers default-error
- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` — aggregate validation (NAME_REGEX/VERSION_REGEX/ACL_TYPES/ACL_PERMISSIONS)

### Restart Commands

```bash
# Backend (with secrets + LAB mode)
cd backend && SPRING_PROFILES_ACTIVE=local,dev ./gradlew bootRun -x processAot > /tmp/backend.log 2>&1 &
until curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; do sleep 3; done

# Frontend
cd frontend && npm run dev > /tmp/frontend.log 2>&1 &

# Verify all
bash scripts/verify-all.sh
```

### Cron State

- Cron `388844f0` (every 10 min) 仍在 — recurring /loop fire
- Tools loaded this session: ToolSearch, CronCreate/List/Delete, Skill, Bash, Edit/Write/Read, mcp__claude-in-chrome__*
