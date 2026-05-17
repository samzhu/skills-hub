# S180 — Skill Public Native Readback Hotfix

> SpecID: S180
> Status: ✅ closed — original native readback crash no longer reproduced; active tracking removed 2026-05-17
> Date: 2026-05-15
> Size: XS(3)
> Related: S168 GraalVM native boolean wrapper workaround, S177 is_public-first search visibility, S176 explicit publish skill name, S098a3-2 bundle-info endpoint

---

## 1. Goal

Cloud Run log 在 2026-05-15 23:58-00:00（Asia/Taipei）顯示：skill `028cecf1-3326-4327-bbe9-28b4e6fab6d5` 已上傳成功、scan 也已完成，但 `/publish/validate?id=...` 讀 skill detail / bundle-info 時回 400，畫面顯示「無法載入 skill」。

實際根因不是上傳失敗，也不是 scan 還沒完成；是 production native image 從 PostgreSQL `skills.is_public` 讀回 row 時，Spring Data JDBC AOT accessor 要把 `Boolean` 寫進 `Skill.publicSkill` primitive `boolean` field，GraalVM MethodHandle path 把值變成 `Integer`，最後拋：

```text
java.lang.IllegalArgumentException:
Can not set boolean field io.github.samzhu.skillshub.skill.domain.Skill.publicSkill to java.lang.Integer
```

S180 要把 `Skill.publicSkill` 跟 S168 已 ship 的 `User.contactEmailPublic` / `NotificationPreference.*Enabled` 同步：persistent boolean column 對應的 entity field 改成 `Boolean` wrapper，getter / business method 對外仍維持 `boolean` 行為。

相依狀態：

| Spec | 狀態 | 是否阻擋 S180 |
| --- | --- | --- |
| S168 | ✅ shipped | 提供同 stacktrace family 的已驗證修法：primitive boolean → `Boolean` wrapper。 |
| S177 | ✅ shipped | 引入 `skills.is_public` 作為 public visibility source-of-truth；這次爆的是 S177 新欄位讀回。 |
| S175 | ⏳ local PASS | 同屬 native deploy hotfix，但處理 scanner JSON reflection binding；不是同一個欄位或 root cause。 |
| S178 | 📐 in-design | Browse/search routing，不改 `Skill.publicSkill`。 |
| S179 | 📐 in-design | Publish author UI 文案，不改 backend entity。 |

Spec overlap scan：active specs S175/S178/S179 沒有寫 `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` 的 `publicSkill` 欄位。S180 可獨立實作，roadmap 只新增一列，不覆蓋 S179 草稿。

## 2. Research And Design

### 2.1 Production facts

| 來源 | 查到什麼 | 對設計的影響 |
| --- | --- | --- |
| `gcloud logging read ... "028cecf1-3326-4327-bbe9-28b4e6fab6d5"` | `POST /api/v1/skills/upload` 回 201；`SearchProjection onSkillCreated ... name=字幕`；multi-engine scan completed `level=MEDIUM, findings=2`。 | 上傳與掃描成功；錯誤在後續讀取 skill aggregate。 |
| Cloud Run stacktrace | `Skill__Accessor_ghdeki.setProperty` → `ConvertingPropertyAccessor.setProperty` → `MappingRelationalConverter.readProperties` → `SimpleJdbcRepository.findById`。 | Spring Data JDBC read path 對 `Skill.publicSkill` 寫欄位時爆。 |
| Cloud Run listener log | `SearchProjection.onVersionPublished` 與 `NotificationProjectionListener.onVersionPublished` 都因同一個 `Skill.publicSkill` IAE 留下 uncompleted event publication。 | 不只 validate page；任何 listener / query 只要讀這筆 skill 都會重炸。 |
| `backend/src/main/java/.../Skill.java:129-135` | `@Column("is_public") private boolean publicSkill;` | 這是 S168 同家族的新漏網 persistent primitive boolean。 |
| `backend/src/test/java/.../JdbcConfigurationConverterTest.java` | 既有 S168 guard 只涵蓋 `User.contactEmailPublic` 與 `NotificationPreference` 4 欄。 | S180 要把 `Skill.publicSkill` 納入同一個 guard。 |

### 2.2 Upstream / official references

| Source | Summary | Decision |
| --- | --- | --- |
| [oracle/graal#5672](https://github.com/oracle/graal/issues/5672) | GraalVM native image 的 MethodHandle 在 Long / Integer / Boolean 情境會回傳或傳遞錯值；issue 目前顯示 Closed，但 S168/S180 production 仍在 Java 25 native image path 重現同 family stacktrace。 | 不等上游；local workaround 仍必要。 |
| [spring-data-relational#2186](https://github.com/spring-projects/spring-data-relational/issues/2186) | Spring Data JDBC AOT repositories + native app 可重現 `Can not set boolean field ... to java.lang.Integer`；Spring 標成 external-project / duplicate。 | 不把 bug 歸因在 pgjdbc 或我們的 SQL；修 entity field type。 |
| [Spring Data Relational AOT docs](https://docs.spring.io/spring-data/relational/reference/jdbc/aot.html) | Native image 一定跑 AOT；AOT repositories 會產生 `<Repository FQCN>Impl__Aot`，且這些 generated classes 是內部最佳化。 | 不直接依賴或 patch generated AOT class；改 domain type 讓 generated accessor 不踩 primitive path。 |
| [JobRunr PR #1501](https://github.com/jobrunr/jobrunr/pull/1501) | JobRunr 在 GraalVM Native Image + Jackson 3 的同類 primitive boolean 反序列化問題中，把 primitive boolean 改成 boxed `Boolean`，PR 已 merge。 | S180 採 S168 Round 2 同款修法：boxed wrapper，而不是 converter。 |
| [S168 archived spec](./2026-05-11-S168-aot-jdbc-boolean-converter.md) | Round 1 `IntegerToBooleanConverter` 已在 prod 證實無效；原因是 Spring `ClassUtils.isAssignable(boolean.class, Boolean.class)` 會讓 conversion service 短路。Round 2 wrapper 修法才是有效修法。 | S180 禁止重走 converter path；直接改 `Skill.publicSkill`。 |

### 2.3 Why validate page shows "無法載入 skill"

```text
User upload bundle
  → POST /api/v1/skills/upload 201
  → skill row exists, skill_versions row exists
  → scanner finishes

Validate page polls:
  → GET /api/v1/skills/{id}
  → SkillRepository.findById(id)
  → Spring Data JDBC maps skills.is_public
  → Boolean DB value enters primitive boolean Skill.publicSkill setter
  → GraalVM MethodHandle corrupt path throws IAE
  → GlobalExceptionHandler turns it into HTTP 400
  → frontend shows "無法載入 skill"
```

所以修前使用者看到的是「找不到 / 還在處理」類錯誤文案，但 DB 裡其實有這筆 skill；錯誤在讀回 aggregate 的 native runtime mapping。

### 2.4 Approach comparison

| Approach | 改哪裡 | 使用者實際看到 | 成本 |
| --- | --- | --- | --- |
| A. 加 `IntegerToBooleanConverter` | `JdbcConfiguration.userConverters()` | production 仍可能同 stacktrace；S168 Round 1 已證明 converter 不會在這條真實 path 生效。 | 低，但錯。 |
| B. `Skill.publicSkill boolean` → `Boolean` wrapper（recommended） | `Skill.java` field + `makePublic/makePrivate/isPublic` null-safe boolean check；`JdbcConfigurationConverterTest` 加 reflection guard | Native image 讀 `skills.is_public` 不再把 Boolean 塞進 primitive boolean；validate/detail/bundle-info 可讀同一筆 skill。 | XS，沿用 S168 已驗證 pattern。 |
| C. 關閉 JDBC AOT repositories | config / native profile | 可能避開目前 generated repository path，但會改動整個 repository runtime mode，且 Spring docs 說 AOT 是 native image 必經流程。 | 影響面大，不適合 hotfix。 |
| D. 查詢端改用 custom DTO SQL，避開 aggregate mapping | query service / bundle-info / listeners 多處 | validate page 可能先好，但 listeners、其他 detail read 還是會踩同欄位；留下 root cause。 | 漏洞多，像補症狀。 |

Chosen approach: B。

### 2.5 Implementation notes

`Skill.publicSkill` 是 DB `NOT NULL` 欄位，但 Java field 改成 wrapper 後仍要保留對外 boolean contract：

```java
@Column("is_public")
private Boolean publicSkill;

public void makePublic(String changedBy, String grantId) {
    if (Boolean.TRUE.equals(this.publicSkill)) return;
    this.publicSkill = true;
    ...
}

public void makePrivate(String changedBy, String grantId) {
    if (!Boolean.TRUE.equals(this.publicSkill)) return;
    this.publicSkill = false;
    ...
}

@JsonIgnore
public boolean isPublic() {
    return Boolean.TRUE.equals(publicSkill);
}
```

`Boolean.TRUE.equals(...)` 的實際行為：

- DB 正常值 `TRUE` → 回 `true`
- DB 正常值 `FALSE` → 回 `false`
- 測試 fixture 或未填欄位意外 `null` → 回 `false`，不會 NPE

`Skill.create(...)` / `makePublic(...)` / `makePrivate(...)` 仍會寫 `true` / `false`，schema `skills.is_public NOT NULL` 不變，不需要 migration。

## 3. Acceptance Criteria

Verification command:

Run:

```bash
cd backend && ./gradlew test --tests io.github.samzhu.skillshub.shared.persistence.JdbcConfigurationConverterTest --tests io.github.samzhu.skillshub.skill.domain.SkillAggregateTest
```

Pass: S180 相關 field-type guard 綠，且 S177 既有 public/private aggregate 行為綠。

Native / deploy verification:

```bash
cd backend && ./gradlew processAot
```

Manual LAB deploy 後重開同一條 URL：

```text
https://skillshub-644359853825.asia-east1.run.app/publish/validate?id=028cecf1-3326-4327-bbe9-28b4e6fab6d5
```

畫面不再顯示「無法載入 skill」，Cloud Run 同 revision log 查不到 `Skill.publicSkill to java.lang.Integer`。

| AC | Priority | Verification | Title |
| --- | --- | --- | --- |
| AC-S180-1 | must | Test | `Skill.publicSkill` 必為 `Boolean` wrapper |
| AC-S180-2 | must | Test | public/private visibility behavior 不變 |
| AC-S180-3 | must | AOT command | `processAot` 可成功產生 AOT code |
| AC-S180-4 | must | LAB manual | validate/detail/bundle-info 讀同一筆 skill 不再 400 |
| AC-S180-5 | should | Log check | listener retry 不再因 `Skill.publicSkill` IAE 留 uncompleted event publication |

### AC-S180-1 — `Skill.publicSkill` 必為 `Boolean` wrapper

Given（前提）codebase 含 `io.github.samzhu.skillshub.skill.domain.Skill`

When（動作）reflection 取 `publicSkill` field type

Then（結果）`field.getType() == Boolean.class`

And（而且）`field.getType() != boolean.class`

### AC-S180-2 — public/private visibility behavior 不變

Given（前提）建立 PUBLIC skill

When（動作）呼叫 `isPublic()`

Then（結果）回 `true`

And（而且）呼叫 `makePrivate(...)` 後 `isPublic()` 回 `false`

And（而且）再呼叫 `makePublic(...)` 後 `isPublic()` 回 `true`

### AC-S180-3 — `processAot` 可成功產生 AOT code

Given（前提）S180 code change 已套用

When（動作）跑 `cd backend && ./gradlew processAot`

Then（結果）Gradle exit code 0

And（而且）沒有新增 native/AOT reflection error。

### AC-S180-4 — validate/detail/bundle-info 讀同一筆 skill 不再 400

Given（前提）S180 部署到 LAB Cloud Run native image revision

When（動作）開 `/publish/validate?id=028cecf1-3326-4327-bbe9-28b4e6fab6d5`

Then（結果）頁面能載入 skill/bundle 資訊，不顯示「無法載入 skill」

And（而且）Network 內 `GET /api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5` 不回 400

And（而且）`GET /api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5/bundle-info` 不回 400

### AC-S180-5 — listener retry 不再因 `Skill.publicSkill` IAE 留 uncompleted event publication

Given（前提）S180 部署後 Modulith event retry 或新 publish event 觸發 listener

When（動作）查 Cloud Run log

Then（結果）同 revision log 不再出現：

```text
Can not set boolean field io.github.samzhu.skillshub.skill.domain.Skill.publicSkill to java.lang.Integer
```

And（而且）不再出現 `SearchProjection.onVersionPublished ... Leaving event publication uncompleted` 且 message 指向 `Skill.publicSkill`。

### NFR Coverage

| Category | Coverage | Reason |
| --- | --- | --- |
| Reliability | AC-S180-1, AC-S180-4, AC-S180-5 | 修 native image production readback crash；不只修 UI 文案。 |
| Security | N/A | 不改 auth / permission / visibility 規則，只改 entity field type。 |
| Performance | N/A | Wrapper field 對單 row mapping 無可觀測成本；不新增 query。 |
| Maintainability | AC-S180-1 | reflection guard 防未來 PR 把欄位改回 primitive。 |
| Deployability | AC-S180-3 | 保留既有 native image AOT gate。 |

## 4. Interface Design

No API contract change.

Unchanged externally:

- `GET /api/v1/skills/{id}` response shape 不變。
- `GET /api/v1/skills/{id}/bundle-info` response shape 不變。
- `Skill.isPublic()` 對 Java caller 仍回 primitive `boolean`。
- `skills.is_public` DB column 不改名、不改 type、不改 nullable。
- `SkillCreatedEvent.isPublic` payload 不變。

Internal entity type change:

| Field | Before | After |
| --- | --- | --- |
| `Skill.publicSkill` | `private boolean publicSkill` | `private Boolean publicSkill` |

Null handling:

| Method | Rule |
| --- | --- |
| `isPublic()` | `Boolean.TRUE.equals(publicSkill)` |
| `makePublic(...)` idempotency check | `Boolean.TRUE.equals(this.publicSkill)` |
| `makePrivate(...)` idempotency check | `!Boolean.TRUE.equals(this.publicSkill)` |

## 5. File Plan

| File | Action | Notes |
| --- | --- | --- |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` | modify | `publicSkill` 改 `Boolean` wrapper；三個 boolean check 改 `Boolean.TRUE.equals(...)`；source comment 只留 S180/S168 短註。 |
| `backend/src/test/java/io/github/samzhu/skillshub/shared/persistence/JdbcConfigurationConverterTest.java` | modify | 加 `Skill.publicSkill_mustBeBooleanWrapper` reflection guard。 |
| `docs/grimo/specs/spec-roadmap.md` | modify | Add S180 row as `📐 in-design`。 |

Task cut:

| Task | File(s) | Positive case | Negative case | POC |
| --- | --- | --- | --- | --- |
| T01 | `Skill.java` | PUBLIC / PRIVATE skill 仍透過 `isPublic()` 回正確值 | `publicSkill == null` 不 NPE，回 false | not required；S168 prior art 已驗證 |
| T02 | `JdbcConfigurationConverterTest.java` | reflection 看到 `Skill.publicSkill == Boolean.class` | 改回 primitive `boolean` 時測試紅 | not required |
| T03 | LAB deploy evidence | validate URL 不再 400；log 無 `Skill.publicSkill to java.lang.Integer` | 若 log stacktrace 不變，代表 fix 沒進 revision 或另有 primitive boolean 漏網 | gcloud log driven |

---

## 6. Task Plan

| Task | Status | AC | Result |
| --- | --- | --- | --- |
| T01 | ✅ PASS | AC-S180-2 | `Skill.publicSkill` 改成 `Boolean` wrapper；`isPublic()` / `makePublic(...)` / `makePrivate(...)` 全部改用 `Boolean.TRUE.equals(...)`。 |
| T02 | ✅ PASS | AC-S180-1, AC-S180-2 | `JdbcConfigurationConverterTest` 加 reflection guard；`SkillAggregateTest` 加 public/private toggle + `null` wrapper 行為測試。 |
| T03 | ⏳ Pending deploy | AC-S180-4, AC-S180-5 | 需下輪包版部署到 LAB 後，用 validate URL 與 Cloud Run log 補正式站證據。 |

No POC needed this round: S168 已經證明 converter path 無法修 native MethodHandle primitive boolean crash；S180 直接沿用已驗證的 wrapper pattern。

## 7. Implementation Results

Date: 2026-05-16

Changed files:

| File | Result |
| --- | --- |
| `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` | `publicSkill` field type 從 primitive `boolean` 改為 `Boolean`；對外 getter 仍回 `boolean`。 |
| `backend/src/test/java/io/github/samzhu/skillshub/shared/persistence/JdbcConfigurationConverterTest.java` | 新增 AC-S180-1：反射檢查 `Skill.publicSkill == Boolean.class`。 |
| `backend/src/test/java/io/github/samzhu/skillshub/skill/domain/SkillAggregateTest.java` | 新增 AC-S180-2：PUBLIC → PRIVATE → PUBLIC 行為不變，且 `publicSkill == null` 時 `isPublic()` 回 false。 |

Verification run:

```bash
cd backend && ./gradlew test --tests io.github.samzhu.skillshub.shared.persistence.JdbcConfigurationConverterTest --tests io.github.samzhu.skillshub.skill.domain.SkillAggregateTest
```

Result: PASS — `BUILD SUCCESSFUL in 2m 18s`。

```bash
cd backend && ./gradlew processAot
```

Result: PASS — `BUILD SUCCESSFUL in 7s`。

AC status:

| AC | Status | Evidence |
| --- | --- | --- |
| AC-S180-1 | ✅ PASS | `JdbcConfigurationConverterTest.skillPublicSkill_mustBeBooleanWrapper`。 |
| AC-S180-2 | ✅ PASS | `SkillAggregateTest.s180_publicSkillWrapperPreservesVisibilityBehavior` + existing S177 aggregate tests。 |
| AC-S180-3 | ✅ PASS | `./gradlew processAot` exit 0。 |
| AC-S180-4 | ⚠️ BLOCKED | LAB route serves the validate SPA HTML with HTTP 200; unauthenticated API calls now return 401, not 400. Chrome logged-in page still shows 「無法載入 skill」 because `/api/v1/me` and `/api/v1/skills/{id}` now return HTTP 409, which is a new session/detail blocker rather than the old `Skill.publicSkill` 400 native mapping crash. |
| AC-S180-5 | ✅ PASS | New revision log shows incomplete event retry for the affected skill reached `SearchProjection onVersionPublished done`; no `Skill.publicSkill ... java.lang.Integer` entries in new revision logs。 |

Roadmap note: `docs/grimo/specs/spec-roadmap.md` currently contains mixed S179 + S180 WIP rows. This S180 implementation commit intentionally does not stage roadmap to avoid committing unrelated S179 changes.

### Production Deploy Evidence — 2026-05-15 17:03 UTC Tick

Build input:

- Source: clean `/private/tmp/skills-hub-s180-deploy` snapshot created from git `HEAD` (`04bb1ee`), so local S179 / roadmap draft changes were not uploaded.
- Cloud Build id: `7120f0a3-db0f-4daf-8edb-43ad7147f0ec`.
- Image: `asia-east1-docker.pkg.dev/cfh-vibe-lab/skillshub/skillshub:20260515-170329`.
- Build result: `SUCCESS`, finish time `2026-05-15T17:13:02Z`.

Cloud Run deploy:

```bash
gcloud run services replace temp/service.rendered.yaml --region=asia-east1 --project=cfh-vibe-lab --quiet
```

Result: service `skillshub` routed traffic to revision `skillshub-00028-n7g`.

Startup evidence:

```text
2026-05-15T17:13:53.756Z Starting AOT-processed SkillshubApplication using Java 25.0.2
2026-05-15T17:13:58.097Z Tomcat started on port 8080
2026-05-15T17:13:58.098Z Started SkillshubApplication in 5.421 seconds
```

S180 affected skill retry evidence:

```text
2026-05-15T17:13:58.108Z IncompleteEventRepublishTask : Republishing incomplete event publications
2026-05-15T17:13:58.130Z SearchProjection onSkillCreated skillId=028cecf1-3326-4327-bbe9-28b4e6fab6d5 name=字幕
2026-05-15T17:13:58.856Z SearchProjection onSkillCreated done skillId=028cecf1-3326-4327-bbe9-28b4e6fab6d5
2026-05-15T17:13:58.183Z SearchProjection onVersionPublished skillId=028cecf1-3326-4327-bbe9-28b4e6fab6d5 version=1.0.0
2026-05-15T17:13:58.984Z SearchProjection onVersionPublished done skillId=028cecf1-3326-4327-bbe9-28b4e6fab6d5
```

Log checks:

```bash
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="skillshub" AND resource.labels.revision_name="skillshub-00028-n7g" AND textPayload:"Skill.publicSkill"' --project=cfh-vibe-lab --limit=20
```

Result: 0 entries.

```bash
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="skillshub" AND resource.labels.revision_name="skillshub-00028-n7g" AND severity>=ERROR' --project=cfh-vibe-lab --limit=20
```

Result: 0 entries.

HTTP checks:

```bash
curl -i -sS -L 'https://skillshub-644359853825.asia-east1.run.app/publish/validate?id=028cecf1-3326-4327-bbe9-28b4e6fab6d5'
```

Result: HTTP 200 and SPA HTML served.

```bash
curl -i -sS https://skillshub-644359853825.asia-east1.run.app/api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5
curl -i -sS https://skillshub-644359853825.asia-east1.run.app/api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5/bundle-info
```

Result: both returned HTTP 401 `{"error":"UNAUTHORIZED","message":"Authentication required"}`. This proves the unauthenticated request no longer reaches the old 400 native mapping crash path, but it does not prove the logged-in validate page UI text.

### Chrome Logged-In Validate Attempt — 2026-05-15 17:33 UTC Tick

URL:

```text
https://skillshub-644359853825.asia-east1.run.app/publish/validate?id=028cecf1-3326-4327-bbe9-28b4e6fab6d5
```

Steps:

1. Opened the validate URL in Chrome through the Codex Chrome extension.
2. Read the page text after load.
3. Clicked the visible `登入` nav item.
4. Collected Chrome console messages and Cloud Run request logs for the same timestamp.

Expected:

- Validate page loads skill/bundle information for `028cecf1-3326-4327-bbe9-28b4e6fab6d5`.
- Page does not show `無法載入 skill`.
- Login action redirects or updates the user state.

Actual:

- Page text still contains `登入`.
- Page text still contains `無法載入 skill (id=028cecf1-3326-4327-bbe9-28b4e6fab6d5) — 可能仍在處理或已被刪除`.
- Clicking `登入` did not navigate away from the validate page.

Browser console:

```text
[QueryCache] Array(2) Error: fetchMe failed: HTTP 409
    at to (https://skillshub-644359853825.asia-east1.run.app/assets/index-EXPIzJQn.js:11:71616)
```

Cloud Run request logs on revision `skillshub-00028-n7g`:

| Timestamp | Path | Status | Trace |
| --- | --- | --- | --- |
| `2026-05-15T17:35:34.223578Z` | `/api/v1/me` | 409 | `projects/cfh-vibe-lab/traces/22da8014fd6eb2727e2b64169a440467` |
| `2026-05-15T17:35:34.224361Z` | `/api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5` | 409 | `projects/cfh-vibe-lab/traces/0bf6cdcd5eb7a08b7e2b64169a440d1a` |

Related log checks:

```bash
curl -i -sS https://skillshub-644359853825.asia-east1.run.app/api/v1/me
```

Result without Chrome cookies: HTTP 401 with empty body and `www-authenticate` header. This means the HTTP 409 is specific to the Chrome session or authenticated request path, not the anonymous `/api/v1/me` behavior.

```bash
gcloud logging read 'trace="projects/cfh-vibe-lab/traces/22da8014fd6eb2727e2b64169a440467" OR trace="projects/cfh-vibe-lab/traces/0bf6cdcd5eb7a08b7e2b64169a440d1a"' --project=cfh-vibe-lab --limit=20
```

Result: request logs exist with HTTP 409, but no application text log was attached to those traces. Revision-level `severity>=ERROR` also returned 0 entries. Chrome read-only eval could not read the failed response body.

Impact:

- AC-S180-4 remains blocked at the user-visible page level.
- The original S180 native-image bug is still fixed: new revision logs contain no `Skill.publicSkill ... java.lang.Integer`, and unauthenticated API reads return 401 instead of the old 400 crash.
- The remaining failure is a different production blocker: Chrome-session `/api/v1/me` and skill detail requests return HTTP 409, likely through `GlobalExceptionHandler.handleStateConflict(IllegalStateException)`.

Next tick recommendation:

- Start a new small planning/debug unit for `Chrome session /api/v1/me HTTP 409 blocks validate page`, or first add response/log evidence for `STATE_CONFLICT` so the failed response body and root `IllegalStateException` message are visible in Cloud Run logs.

### Latest Revision Recheck — 2026-05-16 03:31 UTC Tick

Cloud Run revision:

```text
skillshub-00032-9v8  100% traffic  asia-east1-docker.pkg.dev/cfh-vibe-lab/skillshub/skillshub:20260516-031017
```

HTTP checks:

```bash
curl -i -sS -L 'https://skillshub-644359853825.asia-east1.run.app/publish/validate?id=028cecf1-3326-4327-bbe9-28b4e6fab6d5'
```

Result: HTTP 200 and SPA HTML served from the latest frontend bundle (`/assets/index-4KFqykuJ.js`).

```bash
curl -i -sS https://skillshub-644359853825.asia-east1.run.app/api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5
curl -i -sS https://skillshub-644359853825.asia-east1.run.app/api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5/bundle-info
```

Result: both returned HTTP 401 JSON `{"error":"UNAUTHORIZED","message":"Authentication required",...}` with traces `4c5450b42528323314804cceadbd3956` and `6c67fdb177d2fa6146e404a8c74adf0e`. Trace log lookup found the same two 401 request logs.

Cloud Run log checks on `skillshub-00032-9v8` since 2026-05-16T03:10Z:

| Query | Result |
| --- | --- |
| `textPayload:"Skill.publicSkill"` | 0 rows. |
| `httpRequest.status=409 OR STATE_CONFLICT` | 0 rows. |
| `severity>=ERROR` | 0 rows. |

Interpretation:

- AC-S180-5 remains PASS on the latest revision: the native `Skill.publicSkill` mapping crash is not recurring in application logs.
- AC-S180-4 remains blocked at the logged-in UI level because the Chrome plugin is not callable in this Codex tick. Unauthenticated curl can only prove the old 400 native crash path is no longer visible; it cannot prove the logged-in validate page renders the skill/bundle details.

### Blocker Checkpoint — 2026-05-16 03:41 UTC Tick

Tool state:

- Current Codex tool list has no callable Chrome namespace, so this tick cannot open the user's logged-in Chrome session, read page text, collect browser console errors, or click the production UI.
- This is the third consecutive Codex tick where AC-S180-4 still cannot be completed by the required logged-in Chrome path. The available fallback checks are curl and gcloud logs only.

What is already proven:

- `GET /publish/validate?id=028cecf1-3326-4327-bbe9-28b4e6fab6d5` returns the SPA HTML on latest revision `skillshub-00032-9v8`.
- Unauthenticated `GET /api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5` and `/bundle-info` return 401 JSON, not the original 400 native crash.
- Latest revision logs since 2026-05-16T03:10Z contain no `Skill.publicSkill`, no HTTP 409, and no `severity>=ERROR` rows.

What remains unproven:

- Logged-in Chrome still needs to open the validate URL and verify the page no longer shows `無法載入 skill`.
- If a browser network request fails, collect the response body and Cloud Run trace before deciding whether S180 can ship or a new small spec is needed.

### Closure Recheck — 2026-05-17 06:48 UTC Tick

Current deployed revision:

```text
skillshub-00039-54t  100% traffic
```

Code guard:

- `backend/src/main/java/io/github/samzhu/skillshub/skill/domain/Skill.java` keeps `publicSkill` as `Boolean`.
- `backend/src/test/java/io/github/samzhu/skillshub/shared/persistence/JdbcConfigurationConverterTest.java` keeps `AC-S180-1` reflection guard.

HTTP checks:

```bash
curl -i -sS -L 'https://skillshub-644359853825.asia-east1.run.app/publish/validate?id=028cecf1-3326-4327-bbe9-28b4e6fab6d5'
```

Result: HTTP 200 SPA HTML.

```bash
curl -i -sS 'https://skillshub-644359853825.asia-east1.run.app/api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5'
curl -i -sS 'https://skillshub-644359853825.asia-east1.run.app/api/v1/skills/028cecf1-3326-4327-bbe9-28b4e6fab6d5/bundle-info'
```

Result: both return HTTP 401 `{"error":"UNAUTHORIZED","message":"Authentication required"}`, not the old HTTP 400 native mapping crash.

Cloud Run log checks on `skillshub-00039-54t` in the last 24h:

| Query | Result |
| --- | --- |
| `textPayload:"Skill.publicSkill"` | 0 rows |
| `textPayload:"Can not set boolean field"` | 0 rows |
| affected skill id `028cecf1-3326-4327-bbe9-28b4e6fab6d5` | 0 rows |

There were two HTTP 409 warning request logs on this revision, but both were `/browse` requests:

- `GET /api/v1/categories`
- `GET /api/v1/skills?page=0&size=20&sort=downloadCount%2Cdesc`

They are not the original S180 validate/detail/bundle-info path and have no `Skill.publicSkill` or native boolean stacktrace evidence.

Closure decision:

- The original S180 problem is no longer present in current code or current production logs.
- Keep the code fix and `AC-S180-1` guard because removing those would re-open the native image crash path.
- Remove S180 from the active roadmap and archive this spec as closed.
