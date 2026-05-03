# S130: Personal endpoints auth gate (Bug BB fix — anonymous lab-user state leak)

> Spec: S130 | Size: XS(1) | Status: ✅ Shipped (v3.10.9)
> Date: 2026-05-04
> Source: Mode B Round 41 (2026-05-04 Tick 17) finding HIGH (LAB session integrity)

---

## 1. Goal

修補 SecurityFilterChain `/api/v1/me/**` + `/api/v1/notifications/**` 漏 require authenticated — anonymous user 透過 CurrentUserProvider lab-user fallback 讀寫 lab-user shared state（subscriptions / notifications / mark-read）。LAB 封測時多個匿名員工 session **共享** lab-user 身分，違反 personal endpoints 隔離設計。

**起源**：Mode B Round 41 (Tick 17) finding **Bug BB**。E2E probe 顯示 `/me` 401 anon ✓，但 `/me/subscriptions` / `/notifications` / `/notifications/unread-count` / `/notifications/read-all` / `/notifications/preferences` 全 200 anon — inconsistent。CurrentUserProvider line 308 fallback `(lab-user, [admin])` 對 unauthenticated request 回 lab-user，致 anonymous 共享 state。

**非目標**（本 spec 不做）：
- 改 CurrentUserProvider fallback 邏輯（per S115 既驗 graceful degradation；改動風險高，影響 background thread / test 路徑）
- 修補 subscribe POST anonymous → 201 lab-user write（write-side anonymous 仍可建立 lab-user subscription；非 leak 但 architectural smell；留 observation）

## 2. Approach

走 **option A — SecurityFilterChain `requestMatchers` 擴充 path patterns require authenticated**：對齊既驗 `/api/v1/me` + `/api/v1/admin/**` pattern；最小 diff。

### 2.1 Approach 比較

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| **A. SecurityFilterChain pattern 擴充** | 對齊既驗 S011 pattern；single config point；minimal diff | 改 SecurityConfig 全 endpoint scope | ⭐ |
| B. `@PreAuthorize("isAuthenticated()")` per controller method | 細粒度 | 重複 6+ method 違反 DRY；漏改一處致 leak | |
| C. CurrentUserProvider fallback 改 throw on anonymous HTTP request | 全面修補（含 subscribe write 等 anonymous 路徑） | S115 既驗 fallback 路徑廣（background thread / test）；改動風險高；違反「真的有第三 use case 才抽」 | |

走 **A**。

### 2.2 Path pattern coverage

```java
.requestMatchers("/api/v1/me", "/api/v1/me/**").authenticated()
.requestMatchers("/api/v1/notifications", "/api/v1/notifications/**").authenticated()
.requestMatchers("/api/v1/admin/**").authenticated()
```

Coverage：
- `/api/v1/me` — identity (既驗 S011)
- `/api/v1/me/subscriptions` — S125b GET (本 spec fix)
- `/api/v1/notifications` — S096h2 list
- `/api/v1/notifications/unread-count` — bell badge
- `/api/v1/notifications/{id}/read` — mark single read
- `/api/v1/notifications/read-all` — mark all read
- `/api/v1/notifications/{id}` (DELETE) — hard delete
- `/api/v1/notifications/preferences` (GET / POST) — preferences
- `/api/v1/admin/**` — admin (既驗 S011)

### 2.3 Trim list

XS=1 — 無 trim space。

## 3. SBE Acceptance Criteria

驗證指令：E2E manual via curl

**AC-S130-1：anon 6 個 personal endpoints → 401**
- /me / /me/subscriptions / /notifications / /notifications/unread-count / POST /notifications/read-all / /notifications/preferences

**AC-S130-2：A authenticated 4 個 personal endpoints → 200**
- /me / /me/subscriptions / /notifications / /notifications/unread-count

**AC-S130-3 (regression)：3 個 public endpoints anon 仍 200**
- /skills (list) / /skills/{PUBLIC} / /categories

**AC-S130-4 (out-of-scope observation)：anon POST /skills/{PUBLIC}/subscribe 仍 201**
- 不在 personal endpoint 範圍；anonymous write to lab-user shared state — 留 future polish

## 4. File Plan

### Backend (production)

| File | Action | Description |
|------|--------|-------------|
| `backend/.../shared/security/SecurityConfig.java` | modify | (1) `filterChain` OAuth branch 擴充 `requestMatchers`：`/api/v1/me, /api/v1/me/**` + `/api/v1/notifications, /api/v1/notifications/**`；(2) Javadoc 補 S130 fix 說明 |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/CHANGELOG.md` | append | v3.10.9 entry |
| `docs/grimo/specs/spec-roadmap.md` | modify | 加 M125 row (S130) ✅ |
| `docs/grimo/specs/archive/2026-05-04-S130-personal-endpoints-auth-gate.md` | new | 本 spec |
| `.claude/progress/loop-e2e-test-coverage.md` | modify | Round 41 進度 |

## 5. Test Plan

### 5.1 E2E manual smoke (real backend OAuth=true + curl)

對應 §3 AC-S130-1~4 — 14 case via curl。

### 5.2 Unit test 不額外加

- 對齊「E2E smoke 取代 unit test」既驗 lessons (per S121-S128 chain pattern)
- SecurityFilterChain pattern 行為由 Spring Security 內建保證

## 6. Verification

### 6.1 E2E manual smoke 全 PASS（14/14）

```bash
# Round 41 fixture + S130 ship 之後

# AC-S130-1 anon personal endpoints (6 ACs)
# /me=401 / /me/subscriptions=401 / /notifications=401 / /notifications/unread-count=401 /
# /notifications/read-all=401 / /notifications/preferences=401 ✓

# AC-S130-2 A authenticated (4 ACs)
# /me=200 / /me/subscriptions=200 / /notifications=200 / /notifications/unread-count=200 ✓

# AC-S130-3 anon public endpoints regression (3 ACs)
# /skills=200 / /skills/PUBLIC_ID=200 / /categories=200 ✓

# AC-S130-4 (observation) anon /skills/PUBLIC/subscribe=201 (out-of-scope)
```

### 6.2 ModularityTests

未額外執行（純 SecurityConfig pattern 改動；module boundary 不變）。

## 7. Result

### Shipped

- `SecurityConfig.filterChain` OAuth branch 擴充 require-authenticated path patterns
- E2E manual smoke 14/14 case PASS

### Verify metric

- 14 case all PASS（6 anon-personal=401 + 4 auth-personal=200 + 3 public-regression=200 + 1 out-of-scope observation）
- Backend devtools restart 2.6s
- Compile 1s

### Trim defer / known limitation

- **Anonymous POST /skills/{id}/subscribe still 201** — write to lab-user shared subscription；非 read-side leak（/me/subscriptions 已 401）；anonymous 寫入後其他 anonymous user 看不到，僅 lab-user 累積；架構 smell 但 LAB 影響小。Future polish 候選（per spec §1 排除 + §3 AC-S130-4 observation）。
- **CurrentUserProvider fallback 邏輯不改** — S115 既驗 fallback 路徑廣（background thread / test）；本 spec 用 SecurityFilterChain pattern 隔離 anonymous HTTP request 而非 patch fallback core。

### LAB 部署 impact — session integrity unblock

- LAB 員工匿名 session 不再共享 lab-user 個人 state（subscriptions / notifications）
- /me / /me/** / /notifications/** 全 require auth；anonymous 看 PUBLIC skill 列表仍可
- 既知 architectural smell（subscribe write side）已 document 為 observation；非 LAB-blocker

### Lessons / Pattern reuse

- **第 19 次 single-tick XS/S spec ship**（per session lessons learned）
- **SecurityFilterChain pattern 擴充 canonical pattern**：path matcher require-auth；對齊 S011 既驗 `/api/v1/me` + `/api/v1/admin/**`
- **Mode B finding 同 tick ship pattern 重複採用**：LAB-blocker XS fix 走完整 implement→VERIFY→ship pipeline (per S128 既驗 R40 同 tick ship)
- **`/api/v1/me/**` 涵蓋 me/subscriptions** — Spring Security ant pattern `/me` 不包含 `/me/...`；須顯式加 `/**` 子路徑
