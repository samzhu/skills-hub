# S128: CORS configuration (Bug AZ fix — LAB cross-origin deploy unblock)

> Spec: S128 | Size: XS(2-3) | Status: ✅ Shipped (v3.10.8)
> Date: 2026-05-04
> Source: Mode B Round 40 (2026-05-04 Tick 16) finding HIGH (LAB cross-origin deploy blocker)

---

## 1. Goal

修補 backend 完全沒設 CORS — LAB / production frontend 與 backend 不同 origin 部署時 browser 直接拒絕請求。已知 tech debt 自 tick 63 R20.4，本 spec 在 LAB 部署前補完。

**起源**：Mode B Round 40 (2026-05-04 Tick 16) finding **Bug AZ**。OPTIONS 請求帶 `Origin` header 沒回任何 `Access-Control-*` headers；source code grep `addCorsMappings` / `@CrossOrigin` / `CorsConfiguration` 全空。

**非目標**（本 spec 不做）：
- Server compression（per Round 40 Bug BA — 留 polish backlog）
- ETag / cache header tuning（per Round 40 observation — defer）
- CORS preflight cache (`maxAge`) — defer 至 production 部署觀察 OPTIONS 流量後再調

## 2. Approach

走 **option A — `CorsConfigurationSource` bean + `http.cors(...)` + `SkillshubProperties.Security.Cors`**：對齊既驗 Spring Security 7 標準 pattern。

### 2.1 Approach 比較

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| **A. `CorsConfigurationSource` bean + `http.cors(...)` 在 SecurityConfig** | 對齊 Spring Security 7 canonical；allowlist 由 SkillshubProperties 配置；env var 注入；single bean point | 1 個新 bean + 1 record | ⭐ |
| B. `@CrossOrigin` per controller method | 細粒度控制 | 重複 30+ controller method；單一 origin 改動須改多處；違反 DRY | |
| C. `WebMvcConfigurer.addCorsMappings()` global | 簡單 | 與 Spring Security 整合需 `http.cors(Customizer.withDefaults())`；本質與 A 等價但 less explicit | |

走 **A**。

### 2.2 Configuration design

`SkillshubProperties.Security.Cors`：
- `allowedOrigins: List<String>` — env var `SKILLSHUB_SECURITY_CORS_ALLOWED_ORIGINS` 注入逗號分隔 list
- `allowCredentials: boolean` — 預設 `true` 支援 OAuth bearer token

Default values:
- `allowedOrigins = ["http://localhost:5173", "http://localhost:8080"]` — dev vite + backend self
- `allowCredentials = true`

Production / LAB 部署透過 env var 顯式覆蓋為 LAB host。

### 2.3 CORS 策略

- `allowedMethods = [GET, POST, PUT, PATCH, DELETE, OPTIONS]` — 對齊既有 RestController method set
- `allowedHeaders = ["*"]` — allow all request headers including Authorization
- Path pattern = `/api/**` — 只 cover API endpoints；static resource / actuator 不啟用 CORS

### 2.4 Trim list

- **Compression / ETag** → 留 polish backlog（per spec §1）

## 3. SBE Acceptance Criteria

驗證指令：E2E manual via curl

**AC-S128-1：OPTIONS preflight from allowed origin → 200 + 完整 CORS headers**
- Given：`OPTIONS /api/v1/skills` with `Origin: http://localhost:5173`
- Then：HTTP 200 + `Access-Control-Allow-Origin: http://localhost:5173` + `Access-Control-Allow-Methods` + `Access-Control-Allow-Headers` + `Access-Control-Allow-Credentials: true` + `Vary: Origin`

**AC-S128-2：GET from allowed origin → echo Access-Control-Allow-Origin**
- Given：`GET /api/v1/skills?keyword=` with `Origin: http://localhost:5173`
- Then：HTTP 200 + `Access-Control-Allow-Origin: http://localhost:5173`

**AC-S128-3：OPTIONS from disallowed origin → 403**
- Given：`OPTIONS /api/v1/skills` with `Origin: https://evil.example.com`
- Then：HTTP 403（CORS rejection）

**AC-S128-4：Download endpoint CORS works**
- Given：`OPTIONS /api/v1/skills/{id}/download` with allowed origin
- Then：HTTP 200 + Allow-Origin echoed

**AC-S128-5 (regression)：GET without Origin → no CORS echo（only Vary）**
- Given：anon `GET /api/v1/skills?keyword=` no Origin
- Then：no `Access-Control-Allow-Origin` (Vary 仍存在 indicates CORS-aware response)

**AC-S128-6 (regression)：GET valid PUBLIC by id 仍 200**

## 4. File Plan

### Backend (production)

| File | Action | Description |
|------|--------|-------------|
| `backend/.../SkillshubProperties.java` | modify | 加 `Security.cors()` field + 新 `Cors` record（allowedOrigins + allowCredentials） |
| `backend/.../shared/security/SecurityConfig.java` | modify | (1) imports CorsConfiguration + UrlBasedCorsConfigurationSource + List；(2) `filterChain` 加 `http.cors(cors -> cors.configurationSource(...))`；(3) 加 `@Bean CorsConfigurationSource` 從 SkillshubProperties.Security.Cors 配置 |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/CHANGELOG.md` | append | v3.10.8 entry |
| `docs/grimo/specs/spec-roadmap.md` | modify | 加 M123 row (S128) ✅ |
| `docs/grimo/specs/archive/2026-05-04-S128-cors-configuration.md` | new | 本 spec |
| `.claude/progress/loop-e2e-test-coverage.md` | modify | Round 40 進度紀錄（cut + finding + fix-spec link） |

## 5. Test Plan

### 5.1 E2E manual smoke (real backend OAuth=true + curl)

對應 §3 AC-S128-1~6 — 6 case via curl。

### 5.2 Unit test 不額外加

- 對齊「E2E smoke 取代 unit test」既驗 lessons (per S121-S127 chain pattern)
- CORS 行為由 Spring Security 內建保證；單元測試不會比 E2E 更可靠

## 6. Verification

### 6.1 E2E manual smoke 全 PASS（6/6）

```bash
# AC-S128-1: OPTIONS allowed origin → 200 + 5 CORS headers ✓
# AC-S128-2: GET allowed origin → Access-Control-Allow-Origin + Vary ✓
# AC-S128-3: OPTIONS disallowed origin → 403 ✓
# AC-S128-4: OPTIONS download endpoint → 200 + CORS echo ✓
# AC-S128-5: GET no Origin → no Access-Control echo (Vary only) ✓
# AC-S128-6: GET PUBLIC by id → 200 (regression) ✓
```

### 6.2 ModularityTests

未額外執行（純 SecurityConfig + Properties 改動；module boundary 不變）。

## 7. Result

### Shipped

- `SkillshubProperties.Security.Cors` record 加 `allowedOrigins` + `allowCredentials` 配置
- `SecurityConfig` 加 `CorsConfigurationSource` bean + `http.cors(...)` 啟用
- E2E manual smoke 6/6 case PASS

### Verify metric

- 6 case all PASS（4 主流場景 + 1 regression no-Origin + 1 regression PUBLIC by id）
- Backend devtools restart 2.8s
- Compile 1s

### Trim defer

- **Server compression** (Round 40 Bug BA) — 留 polish backlog；非 LAB-blocker
- **ETag / Cache header tuning** — 留 polish backlog；Spring Security default `no-cache, no-store` 對 dynamic API 是 OK

### LAB 部署 impact — UNBLOCK cross-origin deploy

- LAB / production frontend 與 backend 不同 origin 部署時 CORS preflight 與實際請求都正常
- 既知 tech debt 自 tick 63 R20.4 已補
- env var `SKILLSHUB_SECURITY_CORS_ALLOWED_ORIGINS=https://lab-ui.example.com,https://prod-ui.example.com` 即可配置

### Lessons / Pattern reuse

- **第 18 次 single-tick XS/S spec ship**（per session lessons learned）
- **CORS canonical pattern 確立**：`CorsConfigurationSource` bean + `http.cors(...)` + properties-driven allowlist + env var 覆蓋 — 將來新 service deploy 可直接 reuse
- **SkillshubProperties.Security 第 3 個 sub-record**（OAuth + Lab + Cors）— 對齊既驗 properties hierarchy 設計
