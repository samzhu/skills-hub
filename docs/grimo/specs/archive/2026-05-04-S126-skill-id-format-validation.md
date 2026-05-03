# S126: Skill id format validation pre-PreAuthorize (Bug AX fix — LAB UX polish)

> Spec: S126 | Size: XS(2-3) actual scope creep due interceptor order discovery | Status: ✅ Shipped (v3.10.7)
> Date: 2026-05-04
> Source: Mode B Round 39 (2026-05-04 Tick 13) finding LOW (UX confusion)

---

## 1. Goal

修補 Mode B Round 39 Bug AX：invalid / nonexistent skill id（`null`、`undefined`、不存在的 UUID、非 UUID 格式）走 Spring Security `@PreAuthorize` fail-secure 路徑回 401（anon）/ 403（auth）— 對 LAB 員工 typo URL 反饋會混淆。Fix invalid format → 400 VALIDATION_ERROR；valid UUID format 但 nonexistent 仍走 security-first 路徑（per Spring Security 預設）。

**起源**：Mode B Round 39 (2026-05-04 Tick 13) finding **Bug AX**。8 個 `@PreAuthorize`-protected endpoints 受影響：getById / bundleInfo / getVersions / downloadLatest / downloadVersion / subscribe / unsubscribe (+ Tick 14 already shipped S127 part of Round 39 chain)。

**非目標**（本 spec 不做）：
- 改 Spring Security 預設 fail-secure（per LAB UX vs hide-existence trade-off — 後者是安全 feature）
- 改 service signature String → UUID（過度 invasive；controller boundary 轉換即足）

## 2. Approach

走 **option A — `@PathVariable UUID id` + Spring 內建 UUID converter**：invalid format 在 argument resolution 階段就 throw `MethodArgumentTypeMismatchException`，**早於 @PreAuthorize interceptor**，由 GlobalExceptionHandler 翻譯為標準 ErrorResponse + 400。

### 2.1 Approach 比較

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| **A. `@PathVariable UUID id` + 內建 converter** | invalid format 在 arg resolution 階段 throw（早於 @PreAuthorize）；Spring 內建支援；最小 controller diff | 需 service 邊界 `id.toString()` 轉換；method signature 改 type | ⭐ |
| B. `@Validated + @Pattern` on path var (initial attempt) | 最小 type 改動 | **不可行** — Spring Security `@PreAuthorize` interceptor 早於 method validation interceptor；validation never fires；驗證後發現此設計缺陷 | ❌ |
| C. 自訂 `HandlerInterceptor` 做 path 預檢 | 細粒度控制 | 新增 infra；duplicate Spring 內建 UUID 邏輯；違反 minimal diff | |

走 **A**。**Approach B rejected mid-implementation**（per spec §1 scope creep；Tick 過程實作後 E2E verify 顯示 invalid id 仍 401，trace 至 interceptor order，revert 至 UUID type 路徑）。

### 2.2 Interceptor order 重要 lesson

Spring Security 6.x：
- `AuthorizationManagerBeforeMethodInterceptor` (@PreAuthorize) — order 500
- `MethodValidationInterceptor` (@Validated) — order LOWEST_PRECEDENCE

Lower order = 高 precedence = 先 fire。因此 `@PreAuthorize` 早於 `@Pattern` 驗證 → invalid format 走 fail-secure 路徑。

**對策**：把驗證搬至 **argument resolution 階段**（而非 method invocation interceptor 階段）。Spring `@PathVariable UUID id` 的內建 converter 是 arg resolution 級別，發生在所有 method-level interceptor 之前。

### 2.3 Trim list

XS=2-3 scope；從 Approach B 失敗 retry 到 Approach A 過程多了一些 cleanup 成本但仍 single-tick ship。

## 3. SBE Acceptance Criteria

驗證指令：E2E manual via curl

**AC-S126-1：anon GET /skills/null → 400 VALIDATION_ERROR**
- Given：anon，path `/skills/null`
- When：GET
- Then：HTTP 400 + body `{error: "VALIDATION_ERROR", message: "Invalid format for parameter 'id': null", timestamp}`

**AC-S126-2：anon GET /skills/not-a-uuid → 400 VALIDATION_ERROR**
- 同 AC-S126-1，message 含 `not-a-uuid`

**AC-S126-3：anon GET /skills/<valid UUID 不存在> → 401 (security-first 不變)**
- Given：anon，path with valid UUID format but no such skill
- When：GET
- Then：HTTP 401（per @PreAuthorize fail-secure；hide existence）

**AC-S126-4 (regression)：valid PUBLIC UUID → 200**
- Given：anon GET PUBLIC skill by valid id
- When：GET
- Then：HTTP 200 + Skill JSON

**AC-S126-5 (regression)：valid PRIVATE UUID → 401**
- Given：anon GET PRIVATE skill by valid id (no grant)
- When：GET
- Then：HTTP 401（既驗 read-side ACL chain）

**AC-S126-6：8 個 endpoints 均生效**
- /skills/{id}, /skills/{id}/bundle-info, /skills/{id}/versions, /skills/{id}/download, /skills/{id}/versions/{version}/download, /skills/{id}/subscribe (POST + DELETE) — 全部對 invalid id 回 400

**AC-S126-7 (regression)：valid id subscribe → 201**

## 4. File Plan

### Backend (production)

| File | Action | Description |
|------|--------|-------------|
| `backend/.../skill/query/SkillQueryController.java` | modify | (1) 加 `import java.util.UUID`；(2) `@PathVariable String id` → `@PathVariable UUID id` × 5 method (getById / bundleInfo / getVersions / downloadLatest / downloadVersion)；(3) method body 加 `id.toString()` 邊界轉換給 service |
| `backend/.../community/SkillSubscriptionController.java` | modify | 同 SkillQueryController pattern × 2 method (subscribe / unsubscribe) |
| `backend/.../shared/api/GlobalExceptionHandler.java` | modify | (1) 加 `import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException`；(2) 加 `@ExceptionHandler(MethodArgumentTypeMismatchException.class)` 翻譯為 VALIDATION_ERROR + 400 + 含 paramName + rejectedValue 的 message |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/CHANGELOG.md` | append | v3.10.7 entry + interceptor order lesson |
| `docs/grimo/specs/spec-roadmap.md` | modify | M121 row → ✅ |
| `docs/grimo/specs/archive/2026-05-04-S126-skill-id-format-validation.md` | new | 本 spec |

## 5. Test Plan

### 5.1 E2E manual smoke (real backend OAuth=true + curl)

對應 §3 AC-S126-1~7 — 9 case via curl。

### 5.2 Unit test 不額外加

- 對齊 S121/S122/S123/S124/S127 既驗「E2E smoke 取代 unit test」lessons
- 8 個 endpoints 全 cover via E2E；MethodArgumentTypeMismatchException 路徑由 Spring 內建保證

## 6. Verification

### 6.1 E2E manual smoke 全 PASS（9/9）

```bash
# Round 39 fixture + S126 v2 ship 之後

# AC-S126-1 anon GET /skills/null → 400 + {error:"VALIDATION_ERROR", message:"Invalid format for parameter 'id': null", timestamp:...} ✓
# AC-S126-2 anon GET /skills/not-a-uuid → 400 + 同 shape ✓
# AC-S126-3 anon GET /skills/00000000-...-000000000000 (valid UUID, nonexistent) → 401 (security-first 不變) ✓
# AC-S126-4 anon GET /skills/<valid PUBLIC UUID> → 200 ✓
# AC-S126-5 anon GET /skills/<valid PRIVATE UUID> → 401 ✓
# AC-S126-6a /versions/null path → 400 ✓
# AC-S126-6b /subscribe (POST null) → 400 ✓
# AC-S126-6c /download/null path → 400 ✓
# AC-S126-7 valid PUBLIC subscribe → 201 ✓
```

### 6.2 ModularityTests

未額外執行（純 controller annotation + GlobalExceptionHandler annotation 加；module boundary 不變）。

## 7. Result

### Shipped

- 5 個 SkillQueryController endpoints @PathVariable String → UUID + service boundary toString()
- 2 個 SkillSubscriptionController endpoints 同 pattern
- GlobalExceptionHandler 加 `@ExceptionHandler(MethodArgumentTypeMismatchException.class)` → 400 VALIDATION_ERROR
- E2E manual smoke 9/9 case PASS

### Verify metric

- 9 case all PASS（3 invalid-format → 400 standardize + 1 nonexistent-UUID → 401 security-first 不變 + 5 regression）
- Backend devtools restart 2.6s × 2（Approach B 失敗後 revert 至 Approach A）
- Compile：1s

### Trim defer

- 無

### Round 39 backlog 完整收尾

- ✅ S127 (v3.10.6) — NoResourceFoundException 一致性 (Bug AY)
- ✅ **S126 (v3.10.7) — Skill id format validation (Bug AX)**
- **Round 39 backlog 2/2 chain 完整收尾**

### Lessons / Pattern reuse

- **第 17 次 single-tick XS/S spec ship**（per session lessons learned）
- **Interceptor order critical lesson**：Spring Security `@PreAuthorize` 早於 `@Validated` method validation；想在 fail-secure 之前 validate 須走 argument resolution 階段（`@PathVariable UUID` 內建 converter 是乾淨 path）。**未來類似 fix 直接走 type-level converter 不浪費 retry**。
- **Approach B 失敗 → A 修正 retry pattern**：Mode A spec ship 流程中 `VERIFY` 階段揭露 implement 設計缺陷時，spec doc §2.1 加 `Approach B rejected mid-implementation` 紀錄 — 對齊 audit trail 透明化原則
- **GlobalExceptionHandler 第 20 個 handler 加**（既有 19 個）— 持續對齊 ErrorResponse shape 一致性 invariant
