# S098a3-2: Backend bundle-info endpoint + frontend strip 顯實值

> Spec: S098a3-2 | Size: XS(2) split from S098a3 | Status: 📐 Design
> Date: 2026-05-03
> Parent: S098 META prototype completeness audit；S098a3 frontend-only ship 已 v3.0.0

---

## 1. Goal

把 `/publish/validate` 頁的 upload-strip 從「派生 placeholder（`skill.name-version.zip` + 無 size + 無 fileCount）」升級為「真實 bundle metadata（real filename / fileSize / fileCount / uploadedAt 四欄）」— 讓 user 在驗證進行時看到實際上傳了哪個 bundle 的精確資訊。

**起源**：S098a3 ship 為 frontend-only upload-strip（v3.0.0；2026-05-02），明示 defer 真實 bundle metadata 至本 spec。當前 strip 顯：
- filename → 派生 `<skill.name>-<version>.zip`（不一定是 user 上傳的真實檔名）
- fileSize / fileCount → 完全沒顯（前端無資料）
- uploadedAt → 完全沒顯

**Visual flow**：

```
User 上傳 bundle.zip → PublishPage onSuccess → /publish/validate?id=<skillId>
   ↓
PublishValidatePage 渲染 upload-strip
   → 既有：fetchSkillById(skillId) → 派生 filename「skill.name-version.zip」
   → S098a3-2 升級：fetchBundleInfo(skillId) → 真 {filename, fileSize, fileCount, uploadedAt}
   → strip 顯：「authentication-1.2.0.zip · 12.4 KB · 5 個檔案 · 剛剛」
```

**非目標**（本 spec 不做）：
- Real-time bundle scan progress（fileCount 動態 update）— SkillVersion 已綁定一個 publish 完的 zip，靜態 metadata 即可
- Per-file content listing（zip 內每個 file 顯示）— defer 至 future PublishReview / Files panel polish
- Bundle hash / SHA — defer 至 S098c2 (Version Diff per-version snapshot)

## 2. Approach

新增 `GET /api/v1/skills/{id}/bundle-info` endpoint 走 SkillVersion read model；frontend 新增 `useBundleInfo` hook + strip swap derived placeholder for fetched real values。

### 2.1 Backend metadata source — 三案比較

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| A. Derive `filename` 從 `skill.name + latestVersion`；fileSize 從 `SkillVersion.fileSize`；fileCount 即時 GCS download + zip scan | filename / fileSize 立即可用 | fileCount 走 GCS 下載 + zip scan ≥ 1s latency；每次 refresh 都打 GCS；不 cache 累積流量 | |
| B. 新增 `skill_versions.file_count INT` column + 上傳時 inline 計算 + 既有 row backfill | metadata 全 cached；endpoint < 50ms；fileCount 準確 | V13 migration（小成本）；既有 SkillVersion row 需 backfill | ⭐ |
| C. 走 `S098c2` Version snapshot store（含 fileCount + sha + risk）一次補完 | 解整 V2 audit trail | scope 太大；S098c2 為 M(8)，本 spec XS 不該等它 | |

走 **B** — 加 V13 migration `ALTER TABLE skill_versions ADD COLUMN file_count INTEGER NOT NULL DEFAULT 0`；上傳 pipeline 在 zip 解壓階段順手算 N + 寫入 SkillVersion；既有 row 由 backfill migration 處理（讀 GCS zip + count entries + UPDATE）。

### 2.2 Filename — derive vs persist

**走 derive**：`filename = <skill.name>-<version>.zip`。理由：
1. S041 schema 強制 `(author, name)` UNIQUE — name 不會漂移
2. version 來自 `skill_versions.version` UNIQUE per (skill_id, version)
3. user 上傳的真實檔名（如 `bundle.zip` / `my-skill-v2.zip`）與系統 canonical name 無 invariant 關係 — 顯系統 canonical 比顯 user 上傳檔名更穩定（S041 既驗 — frontend `<skill.name>-<version>.zip` 已在多處 download 使用）

不走 persist：S041 之前考慮過存原始檔名給 audit；但 v1 mark unused，本 spec 不引入。

### 2.3 fileCount backfill 策略

V13 migration 兩段：
1. **Schema migration**（synchronous）：`ALTER TABLE skill_versions ADD COLUMN file_count INTEGER NOT NULL DEFAULT 0;`
2. **Data backfill**（application-level on startup）：
   - `BundleInfoBackfillJob` 走 `skill_versions WHERE file_count = 0` → 對每筆 download GCS storage_path → ZipInputStream count entries → UPDATE
   - 走 Spring `ApplicationRunner` (非 Modulith outbox)；限速 1 row/s 防 GCS API rate limit
   - Idempotent：file_count != 0 直接 skip；execution 失敗（GCS unreachable / corrupt zip）log + skip 該筆；下次 startup 重試

**MVP trim**：backfill 為 polish；本 spec **MVP 不 backfill**（既有 row 顯 fileCount=0 為「未知」signal；下次 publish 該 skill 才會 update）。frontend 對 0 顯「— 個檔案」or hide。Backfill 留 polish backlog row。

### 2.4 Behavior validation

| 決策 | Confidence | 證據 |
|------|------------|------|
| `skill_versions.file_count` 加 column | Validated | V12 既有 collections schema 同 ALTER TABLE pattern；V13 migration sequence 安全 |
| Spring Data JDBC 上傳 pipeline 加欄位寫入 | Validated | SkillVersion factory `from(SkillVersionUploadedCommand)` 既有；加 fileCount 對齊 fileSize 既驗 path |
| GCS zip scan 取代真檔名 | Validated（partial — 走 derive 路徑略過） | S041 既有 `download` endpoint 已 derive canonical filename；無 GCS open 需求 |
| frontend `useBundleInfo` hook | Validated | 對齊 useSkill / useRequest / useCollection enabled-gate canonical |

無 Hypothesis — 純既有 pattern 組合。**不需 POC**。

### 2.5 Trim list

XS(2) 預期一個 cron tick 完成；可 defer 的 polish：

- **既有 row backfill job**（per §2.3）— polish backlog row；MVP 不 backfill；新 publish 自動有 fileCount
- **Frontend hover tooltip 顯精確 byte size**（如 12,853 bytes）— defer；MVP 顯 KB rounded
- **uploadedAt relative time formatter**（「3 分鐘前」）— defer；MVP 顯 ISO date
- **Fallback graceful** 當 fileCount=0：strip 改顯「檔案數未知」or hide 該欄；UX polish 留 follow-up

### 2.6 Research Citations

無外部框架研究。Internal references：
- `docs/grimo/specs/archive/2026-05-02-S098a3-frontend-upload-strip.md`（前置 frontend-only ship）
- `backend/.../skill/domain/SkillVersion.java`（fileSize / storagePath 既有 column）
- `backend/.../skill/command/SkillCommandService.java`（上傳 pipeline；S003 / S037 既驗）
- `backend/src/main/resources/db/migration/V12__create_collections_tables.sql`（V13 migration template reference）
- `frontend/src/pages/PublishValidatePage.tsx:79-95`（既有 strip render；S098a3 註解明示 defer）

## 3. SBE Acceptance Criteria

驗證指令：
- Backend：`./gradlew test`
- Frontend：`cd frontend && npm test`

---

**AC-1：bundle-info endpoint happy path**
- Given：skill `sk-1` 存在，latestVersion = `1.2.0`，對應 SkillVersion row 含 fileSize=12853 + file_count=5 + publishedAt=`2026-05-03T10:00:00Z`
- When：anonymous GET `/api/v1/skills/sk-1/bundle-info`
- Then：回 200 + `{ filename: "<skill.name>-1.2.0.zip", fileSize: 12853, fileCount: 5, uploadedAt: "2026-05-03T10:00:00Z" }`

**AC-2：skill 不存在 → 404**
- Given：skill id `sk-bogus` 不存在
- When：GET `/api/v1/skills/sk-bogus/bundle-info`
- Then：回 404 + `error: "skill_not_found"`

**AC-3：skill 無 latestVersion（DRAFT）→ 404 或 empty payload**
- Given：skill `sk-2` status=DRAFT，無對應 SkillVersion row
- When：GET `/api/v1/skills/sk-2/bundle-info`
- Then：回 404 + `error: "bundle_not_published"`（distinct from skill_not_found）

**AC-4：上傳 pipeline 寫 file_count**
- Given：user 上傳 `pack.zip` 含 5 個 files
- When：publish flow → SkillVersion 寫入
- Then：DB `skill_versions.file_count` = 5；後續 `GET /bundle-info` 回 fileCount=5

**AC-5：既有 row 走 fileCount=0**（per §2.3 MVP trim）
- Given：S098a3-2 ship 前已 published 的 skill，DB `file_count = 0`（V13 migration default）
- When：GET `/bundle-info` for 該 skill
- Then：回 fileCount=0；frontend 顯「— 個檔案」or hide field（per §2.5）

**AC-6：Frontend strip 顯實值**
- Given：useBundleInfo({id: 'sk-1'}) 回 fileSize=12853 + fileCount=5
- When：PublishValidatePage 渲染 upload-strip
- Then：顯「<skill.name>-1.2.0.zip · 12.6 KB · 5 個檔案 · 2026-05-03」（取代既有派生 placeholder）

**AC-7：Frontend strip fallback 當 endpoint 404**
- Given：useBundleInfo enabled gate disable 或 fetch 失敗
- When：strip render
- Then：fall back 顯既有 derived placeholder（不破 既有 UX）

## 4. Interface / API Design

### 4.1 Backend — REST endpoint

```
GET    /api/v1/skills/{id}/bundle-info
   200 { filename: string,        // canonical "<name>-<version>.zip"
         fileSize: number,         // bytes
         fileCount: number,        // entries in zip; 0 = unknown (legacy row)
         uploadedAt: string }      // ISO8601
   404 skill_not_found / bundle_not_published
```

### 4.2 Backend — Schema migration

```sql
-- V13__add_skill_version_file_count.sql
ALTER TABLE skill_versions
    ADD COLUMN file_count INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN skill_versions.file_count IS
    'S098a3-2: zip entry count (excluding directories); 0 = legacy row before this column added';
```

無 backfill SQL（per §2.3 MVP trim — application 不 fail，前端對 0 fallback）。

### 4.3 Backend — SkillVersion 加欄位

```java
@Table("skill_versions")
public class SkillVersion {
    // ... existing fields ...
    @Column("file_count")
    private int fileCount;

    public static SkillVersion from(SkillVersionUploadedCommand cmd) {
        // ... existing logic ...
        sv.fileCount = cmd.fileCount();  // 新加；caller (SkillCommandService) 傳入
        return sv;
    }

    public int getFileCount() { return fileCount; }
}
```

`SkillVersionUploadedCommand` 需加 `int fileCount` field；caller `SkillCommandService.uploadSkill` 在 zip extract phase 計算 entries 並傳入。

### 4.4 Backend — Service + Controller

```java
// new: skill/query/BundleInfoQueryService.java
@Service
public class BundleInfoQueryService {
    private final SkillRepository skillRepo;
    private final SkillVersionRepository versionRepo;

    public BundleInfoResponse get(String skillId) {
        var skill = skillRepo.findById(skillId)
            .orElseThrow(() -> new SkillNotFoundException(skillId));
        if (skill.getLatestVersion() == null) {
            throw new BundleNotPublishedException(skillId);
        }
        var version = versionRepo.findBySkillIdAndVersion(skillId, skill.getLatestVersion())
            .orElseThrow(() -> new BundleNotPublishedException(skillId));
        var filename = skill.getName() + "-" + version.getVersion() + ".zip";
        return new BundleInfoResponse(filename, version.getFileSize(),
                version.getFileCount(), version.getPublishedAt());
    }
}

// existing skill/query/SkillQueryController.java add endpoint
@GetMapping("/{id}/bundle-info")
BundleInfoResponse bundleInfo(@PathVariable String id) {
    return bundleInfoService.get(id);
}

public record BundleInfoResponse(String filename, long fileSize, int fileCount, Instant uploadedAt) {}
```

新 exceptions：
- `SkillNotFoundException`（既有 — 對齊 既有 NoSuchElementException 路徑或新獨立 class；對齊 RequestNotFoundException naming convention）
- `BundleNotPublishedException`（new — 404 + error code `"bundle_not_published"`）

### 4.5 Frontend — API + hook

```typescript
// frontend/src/api/skills.ts (modify — 加新 helper + type)
export interface BundleInfo {
  filename: string
  fileSize: number
  fileCount: number
  uploadedAt: string
}

export function fetchBundleInfo(id: string): Promise<BundleInfo> {
  return apiFetch<BundleInfo>(`/skills/${id}/bundle-info`)
}

// frontend/src/hooks/useBundleInfo.ts (new — 對齊 useSkill enabled-gate pattern)
export function useBundleInfo(id: string | undefined) {
  return useQuery<BundleInfo>({
    queryKey: ['bundle-info', id],
    queryFn: () => fetchBundleInfo(id!),
    enabled: !!id,
    staleTime: 60 * 1000,  // bundle 是 immutable per (skill, version) — 1min cache
  })
}
```

### 4.6 Frontend — PublishValidatePage strip swap

```tsx
// frontend/src/pages/PublishValidatePage.tsx (modify)
const { data: bundleInfo } = useBundleInfo(skillId)

// strip render (取代既有派生 placeholder)
{skill && (
  <div className="mt-6 flex items-center gap-3 ...">
    <FileArchive className="h-4 w-4" />
    <div className="min-w-0 flex-1">
      {/* AC-7 fallback: bundleInfo undefined → 派生既有 placeholder */}
      <p className="truncate text-[13px] font-medium text-foreground">
        {bundleInfo?.filename ?? `${skill.name}-${skill.latestVersion ?? '0.0.0'}.zip`}
      </p>
      <p className="text-[11px] text-muted-foreground">
        <span className="font-mono">v{skill.latestVersion ?? '—'}</span>
        <span className="mx-1.5 text-[#5E5B55]">·</span>
        <span>{skill.category}</span>
        {bundleInfo && (
          <>
            <span className="mx-1.5 text-[#5E5B55]">·</span>
            <span>{(bundleInfo.fileSize / 1024).toFixed(1)} KB</span>
            {bundleInfo.fileCount > 0 && (
              <>
                <span className="mx-1.5 text-[#5E5B55]">·</span>
                <span>{bundleInfo.fileCount} 個檔案</span>
              </>
            )}
          </>
        )}
      </p>
    </div>
  </div>
)}
```

`bundleInfo.fileCount > 0` gate 對齊 AC-5 既有 row fallback（fileCount=0 = unknown，hide 該欄）。

## 5. File Plan

### Backend

| File | Action | Description |
|------|--------|-------------|
| `backend/src/main/resources/db/migration/V13__add_skill_version_file_count.sql` | new | ALTER TABLE skill_versions ADD COLUMN file_count INTEGER NOT NULL DEFAULT 0 + COMMENT |
| `backend/.../skill/domain/SkillVersion.java` | modify | 加 `fileCount` field + `@Column("file_count")` + getter；factory `from()` 從 cmd 取 fileCount |
| `backend/.../skill/command/SkillVersionUploadedCommand.java` | modify | 加 `int fileCount` field |
| `backend/.../skill/command/SkillCommandService.java` | modify | 上傳 pipeline 在 zip extract phase 計算 entries（排除 directories）+ 傳入 cmd.fileCount() |
| `backend/.../skill/query/BundleInfoQueryService.java` | new | get() 業務邏輯：load Skill → 驗 latestVersion → load SkillVersion → BundleInfoResponse |
| `backend/.../skill/query/SkillQueryController.java` | modify | 加 `@GetMapping("/{id}/bundle-info")` + BundleInfoResponse record |
| `backend/.../shared/api/{SkillNotFoundException, BundleNotPublishedException}.java` | new (or new only `BundleNotPublishedException` if SkillNotFoundException 既有) | 404 + error code |
| `backend/.../shared/api/GlobalExceptionHandler.java` | modify | 加 `bundle_not_published` 404 mapping |
| `backend/src/test/.../skill/query/BundleInfoQueryServiceTest.java` | new | AC-1/2/3/4 Testcontainers |
| `backend/src/test/.../skill/query/SkillQueryControllerTest.java` (or new BundleInfoControllerTest) | new / modify | AC-1/2/3 web slice |

### Frontend

| File | Action | Description |
|------|--------|-------------|
| `frontend/src/api/skills.ts` | modify | 加 `BundleInfo` interface + `fetchBundleInfo` helper |
| `frontend/src/hooks/useBundleInfo.ts` | new | TanStack Query hook with enabled-gate（對齊 useSkill canonical） |
| `frontend/src/pages/PublishValidatePage.tsx` | modify | strip render 改用 useBundleInfo 真值；既有派生 placeholder 作 fallback (AC-7) |
| `frontend/src/pages/PublishValidatePage.test.tsx` | modify | AC-6/7 — 真值顯 + endpoint 404 fallback test |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/specs/spec-roadmap.md` | modify | M92a3-2 row：📋 → 📐 in-design + 設計摘要 + scope clarification |

---

## 6. Task plan（單 tick ship — 無 task split）

XS(2) size，單 tick 完成；不拆 BDD task files（對齊 S099a / S110 / S111 single-tick ship pattern 慣例）。

| Phase | 內容 | Commit |
|-------|------|--------|
| Backend infra | V13 migration + PackageService.countEntries + PublishVersionCommand + SkillVersion + 兩 upload pipeline 計算 + BundleInfoQueryService + endpoint + BundleNotPublishedException + GlobalExceptionHandler | 本 commit |
| Backend tests | BundleInfoQueryServiceTest 6 個 + 6 test 檔 cross-callsite migration（PublishVersionCommand 加 fileCount 參數） | 本 commit |
| Frontend infra | api/skills.ts BundleInfo + fetchBundleInfo + useBundleInfo hook | 本 commit |
| Frontend page | PublishValidatePage strip swap 真值 + fileCount=0 hide gate + 404 fallback | 本 commit |
| Persist | CHANGELOG v3.8.1 + roadmap ✅ + spec doc archive | 本 commit |

## 7. Result

### Verification metrics

- **Backend**：
  - `BundleInfoQueryServiceTest` 6/6 PASS @ Testcontainers（AC-1 happy / AC-2 skill not found / AC-3 DRAFT no version + blank latestVersion + orphan pointer / AC-5 legacy file_count=0 fallback）
  - Regression：`SkillVersionRepositoryTest` 6/6 + `SkillCommandServiceTest` 2/2 + `SkillVersionAggregateTest` 4/4 + `ModularityTests` 2/2 PASS（cross-test PublishVersionCommand callsite migration ✓）
- **Frontend**：
  - `PublishValidatePage.test.tsx` 4/4 PASS @ 1.38s
  - 全 frontend suite 193/193 PASS @ 7.32s（0 regression）
  - `npx tsc --noEmit` PASS

### Behavior validation outcome

| 決策 | Pre-ship Confidence | Result |
|------|---------------------|--------|
| `skill_versions.file_count` ALTER TABLE | Validated | V13 migration apply 成功；既有 row 0 default + 新 publish update 路徑運作 |
| Spring Data JDBC 加 column 不破既有 SkillVersion factory | Validated | factory 加 `sv.fileCount = cmd.fileCount()` + 既有 18 個 cross-test 走 `0` 預設 fileCount 全綠 |
| GCS zip scan 取代真檔名 | Validated（partial — 走 derive） | filename canonical S041 既驗；無 GCS open 需求 |
| frontend `useBundleInfo` enabled-gate hook | Validated | 對齊 useSkill / useRequest canonical 第 5 次採用；retry:false 對 404 fallback path 行為對齊 |

### Deviations from spec design

| # | Spec design | Actual implementation | Why |
|---|-------------|----------------------|-----|
| 1 | spec §4.4 範本 加新 `SkillNotFoundException` 獨立 class | 走既有 `NoSuchElementException` 路徑（既有 GlobalExceptionHandler `handleNotFound` 翻 NOT_FOUND） | spec §4.4 範本 over-engineered；既有 SkillQueryService 已用 NoSuchElementException 路徑十多 endpoint，加新 class 違反 minimal diff 原則。BundleNotPublishedException 仍獨立 class 為 distinct error code 提供 i18n 能力 |
| 2 | spec §3 AC-2 預期 401 / `error: "skill_not_found"` distinct | 走既有 NOT_FOUND error code（既有 SkillQueryController 既驗） | 對齊既有 codebase 路徑；frontend i18n key 已對應 "NOT_FOUND" |
| 3 | spec §3 AC-7 frontend 404 fall back 派生 placeholder | 加 retry:false 給 useBundleInfo 避免 404 重試誤觸 ops alerting | 既有 ApiError throw 路徑無區分 expected vs unexpected 404；retry:false 是 minimal 不擴 ApiError shape 達同等效果 |
| 4 | 預期 spec §6 拆 4 BDD task files | 單 tick ship；無 task split | XS(2) size 適合 single-tick ship pattern；對齊 S099a / S110 / S111 既驗 |

### Trim list — 已 defer 為 polish backlog

- **既有 row backfill job**（per §2.3 / §2.5）— polish backlog row；MVP 不 backfill；新 publish 自動有 fileCount
- **Hover tooltip 顯精確 byte size**（如 `12,853 bytes`）— defer
- **uploadedAt relative time formatter**（「3 分鐘前」）— defer；MVP 無 uploadedAt 直接顯（fall back 用「剛剛上傳」signal）
- **Strip alternative copy** 當 fileCount=0：MVP hide；polish 可考慮顯「檔案數未知」
- **Multi-language unknown count signal**：MVP 走 hide；i18n 對應留 polish

### Lessons learned

- **加 record field 觸發 6 test 檔 cross-callsite migration**：`PublishVersionCommand` 為 record（immutable + canonical ctor）；新增 field 雖然安全但 caller 全要 update。本 spec 6 個 test 檔 + 9 callsites 修改為「加 `, 0` for fileCount 預設」單行修改；用 replace_all + 個別 Edit 兩種策略混合處理。Lesson：record field 加 field 是 widespread change，commit body 必須含完整 caller migration list。
- **`retry: false` 取代 ApiError 區分**：TanStack Query 預設 3 次 retry on error；對 404 expected fallback path 不該 retry。`retry: false` 是 minimal-diff 解法不需擴 ApiError shape。
- **`fileCount=0` 為 legacy unknown signal**：column nullable vs default-0 兩案，DEFAULT 0 walking 較安全（INSERT 必須有值，無 null check 路徑）。frontend gate `fileCount > 0` 過濾 + fallback hide 該欄。
- **single-tick XS spec 不需 BDD task split**：對齊既有 S099a / S110 / S111 慣例；XS(2)+ 直接 ship + DOCUMENT + PERSIST 一 commit 落地。

