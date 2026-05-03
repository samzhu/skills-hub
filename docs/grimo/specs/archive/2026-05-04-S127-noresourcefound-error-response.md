# S127: NoResourceFoundException ErrorResponse 一致性 (Bug AY fix)

> Spec: S127 | Size: XS(1) | Status: ✅ Shipped (v3.10.6)
> Date: 2026-05-04
> Source: Mode B Round 39 (2026-05-04 Tick 13) finding (LOW) — backlog 候選

---

## 1. Goal

修補 Spring 6+ `NoResourceFoundException` (trailing slash / 不存在 endpoint default 404) 走 `BasicErrorController` 預設 shape (`{timestamp, status, error: "Not Found", message: "No static resource ...", path}`) 而非標準 `ErrorResponse` shape (`{error: "NOT_FOUND", message, timestamp}`)。Frontend i18n `error` code 對 "Not Found" 字串無翻譯 → silent fallthrough。本 fix 統一翻譯對齊既驗 `NoSuchElementException` → NOT_FOUND ErrorResponse 路徑。

**起源**：Mode B Round 39 (2026-05-04 Tick 13) finding **Bug AY**。

**Tomcat-level limitation 排除**（per spec §2.2 trim）：empty path segment（如 `/api/v1/skills//foo`）由 Tomcat 直接拒絕回 400 不進 Spring exception flow — 本 spec 無法 cover 該 case，**屬 known limitation 不 ship 修補**（per CLAUDE.md「真的有第三 use case 才抽」+ frontend 不會生 double-slash URL）。

## 2. Approach

走 **option A — 加 single `@ExceptionHandler(NoResourceFoundException.class)`**：對齊既驗 GlobalExceptionHandler `NoSuchElementException` / `MissingServletRequestParameterException` pattern。

### 2.1 Approach 比較

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| **A. `@ExceptionHandler(NoResourceFoundException.class)` 翻譯** | 對齊既驗 GlobalExceptionHandler pattern；最小 diff；不影響 BasicErrorController 其他 fallback | 不 cover Tomcat-level 400 | ⭐ |
| B. 自訂 `ErrorAttributes` bean override 全 `BasicErrorController` 路徑 | Cover 含 Tomcat 400 之全 fallback | 改動 Spring Boot 預設 error infrastructure；side effect 風險高；違反 minimal diff | |
| C. 改 Tomcat `relaxedQueryChars` config 接受 empty segment | 真 cover 400 case | 安全風險：empty segment 可能 trigger path traversal；Spring 預設拒絕是 security feature | |

走 **A**。

### 2.2 Trim list

- **`/skills//foo` Tomcat-level 400**：known limitation，不 fix（per §1）

## 3. SBE Acceptance Criteria

驗證指令：E2E manual via curl

**AC-S127-1：trailing slash on existing path → 404 standard ErrorResponse**
- Given：valid path with trailing slash (`/api/v1/skills/`)
- When：anon GET
- Then：HTTP 404 + body `{error: "NOT_FOUND", message: "No static resource ...", timestamp}`

**AC-S127-2：trailing slash on nonexistent id → 404 standard**
- Given：`/api/v1/skills/abc/`
- When：anon GET
- Then：HTTP 404 + body 同上

**AC-S127-3：完全不存在 endpoint → 404 standard**
- Given：`/api/v1/totally-nonexistent-endpoint`
- When：anon GET
- Then：HTTP 404 + body 同上

**AC-S127-4 (regression)：既有 NoSuchElementException → NOT_FOUND 不 regression**
- `/skills/null/null`（不存在 author/name）→ HTTP 404 standard shape（既有 GlobalExceptionHandler 路徑）

**AC-S127-5 (known limitation)：empty path segment → Tomcat 400 unchanged**
- `/skills//foo` → HTTP 400 BasicErrorController shape（不在 spec scope）

**AC-S127-6 (regression)：既有 200 endpoints 不 regression**
- `GET /skills?keyword=` → HTTP 200

## 4. File Plan

### Backend (production)

| File | Action | Description |
|------|--------|-------------|
| `backend/.../shared/api/GlobalExceptionHandler.java` | modify | (1) 加 import `org.springframework.web.servlet.resource.NoResourceFoundException`；(2) 加 `@ExceptionHandler(NoResourceFoundException.class)` method 翻譯為 NOT_FOUND ErrorResponse；Javadoc 補 Tomcat-level limitation 說明 |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/CHANGELOG.md` | append | v3.10.6 entry |
| `docs/grimo/specs/spec-roadmap.md` | modify | M122 row → ✅ |
| `docs/grimo/specs/archive/2026-05-04-S127-noresourcefound-error-response.md` | new | 本 spec |

## 5. Test Plan

### 5.1 E2E manual smoke (real backend OAuth=true + curl)

對應 §3 AC-S127-1~6 — 6 case via curl。

### 5.2 Unit test 不額外加

GlobalExceptionHandler 既有 handler 走 implicit MockMvc test pattern；本 spec 純 annotation handler 加 + E2E 已驗 path → ErrorResponse shape；不另加 unit test 對齊「E2E smoke 取代 unit test」既驗 lessons。

## 6. Verification

### 6.1 E2E manual smoke 全 PASS（6/6）

```bash
# Round 39 fixture + S127 ship 之後

# AC-S127-1 anon GET /skills/ → 404 + {error:"NOT_FOUND", message:"No static resource api/v1/skills for request '/api/v1/skills/'.", timestamp:...} ✓
# AC-S127-2 anon GET /skills/abc/ → 404 + 同 shape ✓
# AC-S127-3 anon GET /totally-nonexistent-endpoint → 404 + 同 shape ✓
# AC-S127-4 anon GET /skills/null/null → 404 + {error:"NOT_FOUND", message:"Skill not found: null/null", timestamp:...} (既驗 NoSuchElementException 路徑不 regression) ✓
# AC-S127-5 anon GET /skills//foo → 400 (Tomcat default, known limitation) ✓
# AC-S127-6 anon GET /skills?keyword= → 200 (regression) ✓
```

### 6.2 ModularityTests

未額外執行（純 GlobalExceptionHandler annotation 加 + import；module boundary 不變）。

## 7. Result

### Shipped

- `GlobalExceptionHandler` 加 `@ExceptionHandler(NoResourceFoundException.class)` 翻譯為標準 NOT_FOUND ErrorResponse shape
- E2E manual smoke 6/6 case PASS

### Verify metric

- 6 case all PASS（3 NoResourceFoundException 路徑 + 1 既驗 NOT_FOUND 不 regression + 1 Tomcat known limitation + 1 success regression）
- Backend devtools restart 2.9s

### Trim defer

- **`/skills//foo` Tomcat-level 400**：known limitation per spec §2.2；不 ship 因 frontend 不會生 double-slash URL + Tomcat 預設安全

### Round 39 backlog 進度

- ✅ **S127 (XS=1, v3.10.6) — NoResourceFoundException ErrorResponse 一致性 (Bug AY fix)**
- 📋 S126 (XS=2-3, LOW) — Skill id format validation pre-PreAuthorize (Bug AX) — chain 2/2 待續

### Lessons / Pattern reuse

- **第 16 次 single-tick XS/S spec ship**（per session lessons learned）
- **GlobalExceptionHandler 第 N 次擴展**（既有 18+ handler，本 spec 加第 19 個 NoResourceFoundException）— 對齊「ErrorResponse shape 一致性」session 持續 invariant
- **Tomcat-level vs Spring-level exception 區分**：`/skills//foo` 走 Tomcat 拒絕（safety feature, 不 cover）；`/skills/abc/` 走 Spring NoResourceFoundException（cover）— Mode B finding scope 須區分 layer
