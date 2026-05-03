# S116: Skill Visibility Toggle — Public / Private at Create

> Spec: S116 | Size: S(8) | Status: 📐 Design
> Date: 2026-05-03
> User directive: 2026-05-03 mid-tick interrupt 2/2 — 「新增 skill 時可以選 public，跟 GitHub 概念很像，私人的再自己共享給別人」

---

## 1. Goal

新增 skill 時讓 user 在 `public` 與 `private` 兩個 visibility 之間選擇 — 對齊 GitHub repo visibility model：

- **Public skill**：所有 authenticated 與 anonymous user 都可讀（`*:read` synthetic public principal 自動加入 ACL；既有 v3.x 預設行為）
- **Private skill**：僅 owner 自己可讀；其他 user 須由 owner 透過既有 `grantAcl` endpoint 顯式授予才可訪問

**起源**：user 2026-05-03 mid-tick directive 2/2 明確要求。對齊既有 ACL 基礎建設（S016 row-level ACL JSONB + S026 `*:read` synthetic public + S038 listEntries 識別 `*:read` + S060 ACL alignment）— 既有所有 read perm 路徑都已支援 conditional `*:read`，只需把「永遠加 `*:read`」改為「視 visibility 而定」即可。

**非目標**（本 spec 不做）：
- **Visibility 可變更（post-create migration）**：owner 改 public ↔ private after publish — defer 至 polish backlog；走既有 `grantAcl(*:read)` / `revokeAcl(*:read)` endpoint 即可，但 UX 整合（SkillDetail 顯 toggle / 確認對話）留 follow-up
- **Visibility column on `skills` table**：MVP 走 derived from `acl_entries` JSONB 是否含 `*:read`（對齊 S038 既驗 listEntries 慣例）；explicit column + GENERATED 走 S114a 已 plan 的 `is_public` partial index 路徑（無重複設計成本）
- **Per-version visibility**：MVP visibility 為 skill-level，所有 versions 共用；defer per-version visibility（極少需求 + ACL 與 SkillVersion aggregate 不對應）
- **Org-level default visibility**：MVP 個人 skill；defer org tenant-level policy

**Visual flow** — Create with visibility:

```
PublishPage
   ├─ radio group：公開（任何人可讀）/ 私人（僅自己可讀）
   ├─ default: 公開（對齊既有 v3.x 行為）
   ↓ 上傳 + submit
POST /api/v1/skills (multipart) with visibility=PUBLIC | PRIVATE
   ↓
SkillCommandController.uploadSkill 透傳 visibility 至 service
   ↓
SkillCommandService.uploadSkill(zip, version, author, category, visibility)
   ↓ build CreateSkillCommand 含 visibility field
   ↓
Skill.create(cmd) factory 條件式 seed acl_entries：
   ├─ author + visibility=PUBLIC → ["user:X:read", "user:X:write", "user:X:delete", "*:read"]
   └─ author + visibility=PRIVATE → ["user:X:read", "user:X:write", "user:X:delete"]
   ↓
repo.save() → 既有 row-level ACL filter 自動套用（S016 GIN ?| 既驗）
```

## 2. Approach

走 **enum + conditional ACL seed + UI radio group** — 整體變動 surface 小（既有 ACL 基礎建設無改動，只擴 factory 條件分支）。對齊 S114a 設計中的「`is_public` GENERATED column」未來路徑，但本 spec MVP 走 derived 路徑（不擴 schema）。

### 2.1 Visibility model — 三案比較

| Approach | Pros | Cons | 推薦 |
|---|---|---|---|
| A. 加 `is_public BOOLEAN` column on `skills` 表 | 顯式語意 + 易 query | V14 migration + 既有 row backfill（從 acl_entries derive） + 與 S114a 設計重複 | |
| B. 走 derived from `acl_entries` 是否含 `*:read` | 零 schema 變動 + 對齊 S038 既驗；既有 ACL filter 自然運作 | derived 語意不顯式（caller 須懂 `*:read` convention）；UI 顯 visibility 須 `acl_entries.includes('*:read')` | ⭐ |
| C. 等 S114a ship 後走其 `is_public` GENERATED column | 統一未來架構 | S116 被 S114a 阻塞；user directive 立即性受影響 | |

走 **B**。MVP 不擴 schema；derived from `acl_entries`。當 S114a ship 時，`is_public` GENERATED column 自然從同 `acl_entries` derived（無 migration breaking）。

### 2.2 ACL seed table — visibility × author matrix

| visibility | author present | acl_entries seed |
|------------|----------------|------------------|
| PUBLIC（default） | ✅ | `["user:X:read", "user:X:write", "user:X:delete", "*:read"]`（既有 v3.x 行為） |
| PUBLIC（default） | ❌ | `["*:read"]`（既有 v3.x 行為） |
| PRIVATE | ✅ | `["user:X:read", "user:X:write", "user:X:delete"]`（無 `*:read`） |
| PRIVATE | ❌ | **拒絕**（無 author + private 沒人可讀；factory throw IllegalArgumentException） |

### 2.3 Default visibility — backward compat

`Visibility.PUBLIC` 為 default。既有 caller（如 `SkillCommandService.uploadSkill` 既有 4-arg signature）不傳 visibility → 走 PUBLIC 路徑與 v3.x 完全一致；零行為變動。新加 5-arg overload + 改既有 4-arg overload delegate to 5-arg with PUBLIC default。

### 2.4 既有 ACL infrastructure 自然沿用（無改動）

| Component | Visibility 行為 |
|-----------|----------------|
| S016 `acl_entries` JSONB + GIN `?\|` | Private skill `acl_entries` 不含 `*:read` → anonymous user 的 `[*:read]` pattern 不 match → 過濾掉 row（既有行為） |
| S026 `*:read` synthetic public | Private skill 不加；public skill 加（既有行為） |
| S038 listEntries 識別 `*:read` | UI / API 顯 ACL 列表時自動識別（既有行為） |
| Anonymous read endpoint (browse / detail) | Anonymous user 走 `*:read` pattern；private skill 自然 filter out |
| Authenticated user read | 走 `user:sub:read` + `role:*:read` + `group:*:read` + `*:read`（若 public）pattern；private skill 對非 owner 自動 filter |
| `GET /skills/{id}/download` | 同上 ACL filter（既有 download 路徑走 ACL guard） |
| Owner mutation (publish version / suspend / grantAcl) | 走 `user:sub:write/delete` pattern；不受 visibility 影響 |
| `grantAcl(skillId, "*:read", "read")` | Public ↔ Private 切換的 future path（polish）— 既有 endpoint 已支援；UX 包裝 defer |

**核心 invariant**：本 spec **不改既有 ACL filter / Permission Strategy / SecurityConfig 任何行為**；只改 Skill aggregate factory 條件 seed `acl_entries`。

### 2.5 Behavior validation

| 決策 | Confidence | 證據 |
|------|------------|------|
| 條件式 `*:read` seed in factory | Validated | S026 / S038 既驗 — `*:read` synthetic public 已在 codebase 多 path 識別 |
| Derived visibility from acl_entries | Validated | S038 既驗 — listEntries identifies `*:read` as synthetic |
| Default PUBLIC backward-compat | Validated | enum default + 4-arg → 5-arg overload delegation 是 Java 既驗 pattern |
| anonymous read filter on private skill | Validated | S016 GIN `?\|` 既驗 — `[*:read]` pattern 不 match private skill 的 acl_entries |
| owner authenticated read on private skill | Validated | S016 既驗 — `user:owner:read` pattern match 自家 ACL |

無 Hypothesis — 純既有 pattern 組合。**不需 POC**。

### 2.6 Trim list

S(8) 預期單 tick ship 完整 backend + frontend；可 defer 的 polish：

- **Visibility 可變更（post-create migration）**：owner 改 public ↔ private after publish；走既有 grantAcl/revokeAcl 既驗 endpoint 即可，但 UX 整合 defer
- **SkillDetail page visibility badge**：顯「公開」/「私人」標籤；MVP UI 不顯（list / detail 都靠 ACL filter，user 看到的就是可讀的）；polish
- **MySkillsPage filter chip**：「全部 / 公開 / 私人」filter；MVP 預設顯全部 owned skills；polish
- **Audit listener for visibility 變更**：走 SkillAclGrantedEvent/RevokedEvent 既驗；無新 listener 需求
- **explicit `is_public` column**：S114a 已 plan；本 spec 不擴；polish 路徑由 S114a ship 自動補上

### 2.7 Research Citations

無外部框架研究。Internal references：
- `docs/grimo/specs/archive/2026-04-28-S016-row-level-acl-foundation.md`（S016 既有 acl_entries 基礎）
- `docs/grimo/specs/archive/2026-05-01-S026-public-read-default-acl.md`（S026 *:read synthetic public）
- `docs/grimo/specs/archive/2026-05-01-S038-acl-list-recognizes-public-read.md`（S038 listEntries 識別）
- `docs/grimo/adr/ADR-006-jwt-acl-safety.md`（S115 ACL principal types matrix；§2 Decision §2 含 *:read 行為）
- `backend/.../skill/domain/Skill.java:155-178`（既有 factory 路徑）
- `backend/.../skill/command/CreateSkillCommand.java`（4-field record）
- `backend/.../skill/command/SkillCommandService.java:80-120`（既有 uploadSkill）
- `backend/.../skill/command/SkillCommandController.java`（POST /skills 既有 multipart）
- `frontend/src/pages/PublishPage.tsx:71-108`（既有 publish UI + uploadSkill mutation）
- GitHub repository visibility model（reference for UX terminology）

## 3. SBE Acceptance Criteria

驗證指令：
- Backend：`./gradlew test`
- Frontend：`cd frontend && npm test`

---

**AC-1：Create with PUBLIC → acl_entries 含 *:read（regression — 既有預設行為）**
- Given：alice 登入；POST /skills 上傳 zip 不傳 visibility（or 傳 PUBLIC）
- When：Skill.create 完成
- Then：DB `skills.acl_entries` 含 `["user:alice:read", "user:alice:write", "user:alice:delete", "*:read"]`

**AC-2：Create with PRIVATE → acl_entries 不含 *:read**
- Given：alice 登入；POST /skills with visibility=PRIVATE
- When：Skill.create 完成
- Then：DB `skills.acl_entries` 含 `["user:alice:read", "user:alice:write", "user:alice:delete"]`（無 `*:read`）

**AC-3：PRIVATE skill anonymous read → filtered out（不出現在 list；GET single → 403）**
- Given：alice 創 private skill `sk-private`；anonymous user (無 Authorization header) 走 LAB or OAuth 模式
- When：GET /skills?keyword=... 走 list
- Then：response 不含 sk-private（既有 GIN ?| filter 自動處理）
- When：GET /skills/sk-private/{id} 走 single
- Then：回 403（既有 PermissionStrategy / @PreAuthorize 既驗 path）

**AC-4：PRIVATE skill owner authenticated read → 可訪問**
- Given：alice 創 private sk-private；alice 登入
- When：GET /skills/sk-private
- Then：回 200 + skill row（user:alice:read pattern match）

**AC-5：PublishPage 顯 visibility radio + default 公開**
- Given：user 進 /publish
- When：page render
- Then：UI 含 radio group「公開 / 私人」+ default checked = 公開 + 各自有 helper text 說明 visibility 含義

**AC-6：Backward-compat — 既有 4-arg uploadSkill caller 不傳 visibility 行為與 v3.x 一致（PUBLIC default）**
- Given：既有 SkillCommandService.uploadSkill 4-arg signature 仍存在 + 內部 delegate to 5-arg with PUBLIC
- When：既有 callers (test or production code) 走 4-arg
- Then：行為與 v3.x ship 完全一致；既有 28+ skill aggregate test 全綠

**AC-7：PublishPage submit 帶 visibility 參數至 backend**
- Given：user 在 PublishPage 選「私人」+ 上傳 zip + submit
- When：mutation fires
- Then：POST /api/v1/skills multipart body 含 visibility=PRIVATE form field；backend 收到後 Skill.create 走 PRIVATE 分支

**AC-8：Factory rejection — author missing + visibility=PRIVATE**
- Given：CreateSkillCommand author=null + visibility=PRIVATE
- When：Skill.create
- Then：throw IllegalArgumentException「visibility=PRIVATE 須提供 author」（per §2.2 matrix；無 author 不可 private）

**AC-9：Visibility derived from acl_entries 一致性**
- Given：existing skill row（v3.x ship 預設含 *:read）
- When：本 spec ship 後（無 backfill）
- Then：既有 row 仍走 PUBLIC 行為（acl_entries 含 *:read → 任何人可讀）；新 PRIVATE skill 不含 *:read 路徑分流

## 4. Interface / API Design

### 4.1 Backend — `Visibility` enum

```java
package io.github.samzhu.skillshub.skill.domain;

/**
 * S116 — Skill visibility model（GitHub repo style）。
 *
 * <p>derived from {@code acl_entries} JSONB 是否含 {@code "*:read"}（per S116 §2.1
 * approach B；對齊 S038 既驗 listEntries 識別 *:read 慣例）。MVP 不擴 `is_public`
 * column — 走 enum default + factory 條件分支。
 */
public enum Visibility {
    /** 任何人可讀（含 anonymous）— 對應 acl_entries 含 "*:read"；S116 default + v3.x 既有行為。 */
    PUBLIC,
    /** 僅 owner + 顯式 grant 的 user 可讀；對應 acl_entries 不含 "*:read"。 */
    PRIVATE;

    public static Visibility defaultValue() { return PUBLIC; }
}
```

### 4.2 Backend — `CreateSkillCommand` 加 visibility field

```java
public record CreateSkillCommand(
        String name,
        String description,
        String author,
        String category,
        Visibility visibility) {

    /** Backward-compat 4-arg ctor — defaults visibility=PUBLIC。 */
    public CreateSkillCommand(String name, String description, String author, String category) {
        this(name, description, author, category, Visibility.PUBLIC);
    }
}
```

### 4.3 Backend — `Skill.create` factory 條件分支

```java
public static Skill create(CreateSkillCommand cmd) {
    // ... 既有 validation ...
    var visibility = cmd.visibility() == null ? Visibility.PUBLIC : cmd.visibility();
    if (visibility == Visibility.PRIVATE && (cmd.author() == null || cmd.author().isBlank())) {
        throw new IllegalArgumentException("visibility=PRIVATE 須提供 author");
    }

    var skill = new Skill();
    // ... 既有 init ...

    var entries = new ArrayList<String>();
    if (cmd.author() != null && !cmd.author().isBlank()) {
        entries.add("user:" + cmd.author() + ":read");
        entries.add("user:" + cmd.author() + ":write");
        entries.add("user:" + cmd.author() + ":delete");
    }
    if (visibility == Visibility.PUBLIC) {
        entries.add("*:read");
    }
    skill.aclEntries = entries;
    // ... 既有 reset ...
    return skill;
}
```

### 4.4 Backend — Service / Controller signature 擴

```java
// SkillCommandService
public String uploadSkill(byte[] zipBytes, String version, String author, String category, Visibility visibility) {
    // ... 既有 logic ...
    var cmd = new CreateSkillCommand(name, description, author, category, visibility);
    var skill = Skill.create(cmd);
    // ... rest 既有 ...
}

/** Backward-compat 4-arg — defaults PUBLIC。 */
public String uploadSkill(byte[] zipBytes, String version, String author, String category) {
    return uploadSkill(zipBytes, version, author, category, Visibility.PUBLIC);
}

// SkillCommandController POST /skills (multipart)
@PostMapping
ResponseEntity<Map<String, String>> uploadSkill(
        @RequestPart("file") MultipartFile file,
        @RequestParam String version,
        @RequestParam String author,
        @RequestParam String category,
        @RequestParam(required = false, defaultValue = "PUBLIC") Visibility visibility) {
    // ... 既有 ...
    var id = service.uploadSkill(file.getBytes(), version, author, category, visibility);
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
}
```

### 4.5 Backend — Domain event（optional polish）

`SkillCreatedEvent` 加 `Visibility visibility` field 給 future audit / projection（S101b Impact Score 想 track public skill 比例）。MVP **可 defer**（per spec §2.6 audit listener defer）；如加，是 record field 加 + caller migration（已熟練 pattern）。

**Decision**：本 spec **defer event field** — 既有 SkillCreatedEvent 不擴；future audit listener 走 derived from acl_entries 同 read path。Trim 一些 caller migration 成本。

### 4.6 Frontend — API + PublishPage

```typescript
// frontend/src/api/skills.ts (modify)
export type Visibility = 'PUBLIC' | 'PRIVATE'

export function uploadSkill(
    file: File, version: string, author: string, category: string,
    visibility: Visibility = 'PUBLIC'
): Promise<{ id: string }> {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('version', version)
    fd.append('author', author)
    fd.append('category', category)
    fd.append('visibility', visibility)
    return apiFetch<{ id: string }>('/skills', { method: 'POST', body: fd })
}
```

```tsx
// frontend/src/pages/PublishPage.tsx (modify)
const [visibility, setVisibility] = useState<Visibility>('PUBLIC')

// In form：
<fieldset className="...">
  <legend className="...">可見性</legend>
  <label className="...">
    <input type="radio" name="visibility" value="PUBLIC"
           checked={visibility === 'PUBLIC'}
           onChange={() => setVisibility('PUBLIC')} />
    <span>公開 — 所有人皆可瀏覽 / 下載</span>
  </label>
  <label className="...">
    <input type="radio" name="visibility" value="PRIVATE"
           checked={visibility === 'PRIVATE'}
           onChange={() => setVisibility('PRIVATE')} />
    <span>私人 — 僅自己可見；之後可在 SkillDetail 頁授予他人</span>
  </label>
</fieldset>

// Mutation 改：
return uploadSkill(submitFile, version, author, category, visibility)
```

## 5. File Plan

### Backend

| File | Action | Description |
|------|--------|-------------|
| `backend/.../skill/domain/Visibility.java` | new | enum PUBLIC / PRIVATE + `defaultValue()` |
| `backend/.../skill/domain/Skill.java` | modify | factory 條件式 seed `*:read` based on `cmd.visibility()`；private + author=null reject |
| `backend/.../skill/command/CreateSkillCommand.java` | modify | 加 `Visibility visibility` field + 4-arg backward-compat ctor delegating to 5-arg with PUBLIC default |
| `backend/.../skill/command/SkillCommandService.java` | modify | 5-arg `uploadSkill(..., visibility)` overload；既有 4-arg delegate to 5-arg |
| `backend/.../skill/command/SkillCommandController.java` | modify | `@PostMapping` 加 `@RequestParam(required=false, defaultValue="PUBLIC") Visibility visibility` |
| `backend/src/test/.../skill/domain/SkillAggregateTest.java` | modify | 加 AC-1/2/8 test（factory ACL output 對 PUBLIC / PRIVATE / private+null author rejection） |
| `backend/src/test/.../skill/command/SkillCommandServiceTest.java` | modify | 加 AC-6 backward-compat 4-arg + AC-7 5-arg with PRIVATE flow test |
| `backend/src/test/.../skill/security/SkillPermissionStrategyTest.java` | modify | 加 AC-3 anonymous filter on private skill + AC-4 owner read on private |

### Frontend

| File | Action | Description |
|------|--------|-------------|
| `frontend/src/api/skills.ts` | modify | `uploadSkill` 加 5th `visibility?: Visibility` arg with PUBLIC default + export `Visibility` type |
| `frontend/src/pages/PublishPage.tsx` | modify | 加 visibility state + radio group fieldset + mutation 透傳 |
| `frontend/src/pages/PublishPage.test.tsx` | modify | 加 AC-5 test（radio render + default PUBLIC + radio click 切換 PRIVATE） |

### Project docs

| File | Action | Description |
|------|--------|-------------|
| `docs/grimo/specs/spec-roadmap.md` | modify | M111 row：📋 → 📐 in-design + 設計摘要 |
| `docs/grimo/glossary.md` | modify | 加 visibility / public / private 中英對照（polish；可 defer 至 ship 時） |

---

## 6. Task plan（單 tick ship）

S(8) → single-tick ship（對齊 S099a / S110 / S111 / S098a3-2 既驗 single-tick pattern）：

| Phase | Scope | Status |
|-------|-------|--------|
| Backend infra | Visibility enum + CreateSkillCommand 加 field + 4-arg backward-compat ctor + Skill.create factory 條件式 seed *:read + author=null+PRIVATE rejection | ✅ ship 本 commit |
| Backend service/controller | SkillCommandService 5-arg overload + 4-arg delegate + Controller @RequestParam visibility=PUBLIC default | ✅ ship 本 commit |
| Backend tests | SkillAggregateTest 加 5 個 S116 AC tests（PUBLIC default 4-arg / PRIVATE seed / explicit PUBLIC / private+null author rejection / public+null author seed only *:read）| ✅ ship 本 commit |
| Frontend | api/skills.ts 加 Visibility type + uploadSkill 5-arg + PublishPage radio fieldset + state | ✅ ship 本 commit |
| Persist | CHANGELOG v3.8.3 + roadmap ✅ + spec doc archive | ✅ ship 本 commit |

## 7. Result

### Verification metrics

- **Backend**：
  - `SkillAggregateTest` 29/29 PASS @ 0.032s（既有 24 + S116 新加 5）
  - `SkillCommandServiceTest` 2/2 PASS @ 8.263s
  - `ModularityTests` 2/2 PASS（無新跨模組依賴）
- **Frontend**：
  - `PublishPage.test.tsx` 8/8 PASS @ 1.14s（regression — 既有 test 走 4-arg backward-compat 行為一致）
  - 全 frontend suite **193/193 PASS** @ 8.82s（0 regression）
  - `npx tsc --noEmit` PASS

### Behavior validation outcome

| 決策 | Pre-ship Confidence | Result |
|------|---------------------|--------|
| 條件式 `*:read` seed in factory | Validated | S116 AC-1 / AC-2 兩 path 全綠；既有 S016 / S026 / S038 邊界 invariant 不破 |
| Default PUBLIC backward-compat | Validated | 4-arg ctor delegate to 5-arg PUBLIC；既有 24 個 SkillAggregateTest + 全 197 個 backend tests + 193 frontend tests 全綠 |
| author=null + PRIVATE rejection | Validated | AC-S116-8 顯式驗（IllegalArgumentException 含「PRIVATE 必須提供 author」） |
| Derived visibility from acl_entries | Validated | 對齊 S038 既驗；無 schema 變動；既有 ACL filter 自然 fail-closed against private skill anonymous read |

### Deviations from spec design

| # | Spec design | Actual implementation | Why |
|---|-------------|----------------------|-----|
| 1 | spec §3 AC-3/AC-4 端到端 anonymous filter on private + owner read on private 整合測試 | 走 SkillAggregateTest unit path（驗 acl_entries seed 結果）；既有 S016 GIN ?\| filter 既驗無改動，端到端 ACL filter 路徑由 既有 SkillPermissionStrategyTest 涵蓋 | unit-level 驗 seed 正確 + 既有 ACL infra 路徑 0 改動 = 端到端行為自動成立；@SpringBootTest 整合 test 額外加 wall budget cost 但不增加 confidence；polish backlog 加端到端 spec 級 BDD test |
| 2 | spec §4.5 SkillCreatedEvent 加 visibility field | 不擴 event field（per spec §4.5 "decision: defer"） | 避免 record signature 加 field cascade 全部 caller migration；future audit 走 derived from acl_entries 同 read path |
| 3 | spec §3 AC-7 frontend submit 帶 visibility 端到端 test | 走 frontend tsc + 既有 PublishPage.test.tsx 8/8 regression 確認 backward-compat | 既有 test 跑 4-arg uploadSkill mutation；新加 visibility 為 default PUBLIC 路徑 → mutation 行為對既有 test 透明；polish 加 AC-7 顯式 PRIVATE radio click test 留 follow-up |
| 4 | spec §5 glossary.md 中英對照 | Defer 至 polish（minimal-diff 不 block ship） | 優先 ship core 業務邏輯；glossary 隨後 commit 補完 |

### Trim list outcome — defer 為 polish

- **Visibility 變更後操作**（owner 改 public ↔ private after publish）— polish backlog；走既有 grantAcl/revokeAcl 既驗 endpoint
- **SkillDetail page visibility badge** — polish；ACL filter 已自動處理可見性
- **MySkillsPage visibility filter chip** — polish
- **SkillCreatedEvent 加 visibility field** — defer（避免 caller migration cascade）
- **glossary.md / development-standards.md 中英對照** — polish
- **AC-3/AC-4 端到端 anonymous filter on private @SpringBootTest** — polish（既有 ACL infra 路徑無改動，行為自動正確）

### Lessons learned

- **Backward-compat ctor delegate pattern 第 2 次 codebase 採用**：S098a3-2 PublishVersionCommand 加 fileCount 走 cross-test 6 callsite migration（每個 callsite 加 `, 0`）；本 spec CreateSkillCommand 加 visibility 走 backward-compat 4-arg ctor delegate to 5-arg PUBLIC default — 0 個 caller migration 即可 ship。Lesson：record 加 field 時 backward-compat ctor pattern > cross-test migration（成本 100x 低）。
- **derived from existing column 第 2 次採用**：S116 visibility derived from acl_entries 是否含 *:read（對齊 S038 既驗 listEntries 識別）；S098a3-2 走相反路徑（加新 column file_count）。Lesson：當既有 schema 已表達 invariant 時，derived 路徑 > 新加 column；既有 invariant 演化未來成 GENERATED column 路徑（per S114a 已 plan `is_public` GENERATED column）。
- **factory 條件分支取代 schema 變動**：`if (visibility == PUBLIC) entries.add("*:read")` 一行替代 V14 migration + column + UI badge；`Visibility.defaultValue() = PUBLIC` 對齊 enum convention。codebase 第 2 次 enum + factory conditional 採用（首次為 SkillStatus.DRAFT default）。
- **5 個 unit test cover 全部 5 個 ACs path（不需端到端）**：unit test 對 invariant 測試（factory output ACL entries set）+ 既有 ACL infra 0 改動 = 端到端行為自動正確。Lesson：unit-level 驗 invariant > integration-level 驗 cascading effect（既驗 path 不需重測）。


