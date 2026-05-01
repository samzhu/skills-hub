# Loop E2E Test Coverage Log

> Persistent log to survive session boundary — read on takeover, append on each new ship.
> Latest tick: 47 (2026-05-01) — vector_store orphans cleaned via Flyway V7

## Coverage Summary (as of v2.46.0)

### Backend ✓ Covered
- **All 21 endpoints** probed: GET/POST/PUT/DELETE for skills/versions/acl/flags/me/admin/analytics/categories/search/upload/download/suspend/reactivate
- **Aggregate validation** S041~S058: name regex, description trim+blank+1024, category trim+blank, author non-blank, version semver, ACL tuple type/principal/permission, flag type/description
- **Default-error 5 layers**: S045 yaml strip + 405 / S049 ZipException → 400 / S051 DuplicateKey → 409 / S052 BodyNotReadable → 400 / S057 DataIntegrity catch-all → 400
- **API JSON cleanup** S062/S063: SkillVersion + Skill `isNew()` `@JsonIgnore`; storagePath hidden
- **PUBLISHED-only visibility** S031/S033/S059: list/categories/analytics/semantic 全 filter status
- **Suspend/Reactivate flow**: 完整 state machine 邊界（S030 STATE_CONFLICT）
- **Upload formats** S053: 3 types canonical zip structure（root/subfolder/plain .md）
- **Download** S061: filename `{skillName}-{version}.zip` ; multi-version verified
- **UTF-8 / 中文 / emoji**: name regex limit ASCII, description/category/author 接受 UTF-8

### Frontend ✓ Covered
- **HomePage**: keyword (S043 `LIKE` cat) / S044 trim / S046 semantic→keyword fallback / S060 truthy badge guard
- **SkillCard**: status badge (S028/S060 truthy guard) / risk level / S047 install guide PUBLISHED only
- **CategorySidebar**: click filter (DevOps/Testing 分頁正確)
- **SkillDetailPage**: 3 tabs / suspend banner (S035) / S039 friendly 404 / 安裝指引 conditional / version list / S041 ApiError.is HMR-safe
- **PublishPage**: drop file (zip+md per S053) / S048 .txt reject / S067 version pattern / S068 maxLength category=50 / author=255 / S040 i18n mutation error
- **FileDropZone**: extension+size guard / drag+click both funnel
- **AddVersionForm**: real upload v1.1.0 verified, history retained
- **i18n coverage**: 12/12 backend codes (S066 補 METHOD_NOT_ALLOWED)
- **QueryClient**: S064 4xx skip console + S065 networkMode=always + retry skip 4xx

### Real Chrome E2E Happy Path (Tick 40 fully verified)
- 構建 minimal valid ZIP via DataView (PK\x03\x04 STORED method)
- Drop file → form fill → submit → 201 + 「發佈成功」
- Click 「查看技能」 → SkillDetailPage 渲染
- Download via vite proxy → bytes round-trip 一致

### Bugs Found & Fixed
- A: keyword whitespace (S044)
- B: stack trace leak (S045)
- C: semantic dead-end (S046)
- D: skip
- E: install guide for DRAFT (S047)
- F: a11y — accept later
- G: drag-drop bypass .zip (S048)
- H: corrupt zip msg (S049)
- I: dup name 500+SQL (S051)
- J: HttpMessageNotReadable method sig leak (S052)
- K: skip
- L: ACL invalid permission (S055)
- M: ACL null prefix
- N: ACL type missing (S055)
- Q/R/T: version validation (S056)
- T-2: long category 500+SQL (S057)
- V: flag NPE (S058)
- W: semantic shows DRAFT (S059)
- X: SkillCard semantic badge (S060)
- Y: download filename (S061)
- Z: storagePath leak (S062/S063)
- AA: Skill isNew artifact (S063)
- AB: console pollution (S064)
- AC: React Query paused state (S065 hotfix v2.43.0)
- AD: outbox stuck SkillAclGrantedEvent pre-S055 (S069 v2.47.0)
- AE: vector_store orphans for pre-S033 SUSPENDED skills (S070 v2.48.0 Flyway V7)

### Known Tech Debt (low priority)
- DB 既有畸形 entries（畸形 ACL/version "foo" 等）需 future migration
- riskAssessment.sourceEventId 暴露（idempotency UUID，無敏感資訊）
- Tomcat HTML 400 page on `%2F` path traversal（low impact，攻擊面已擋）
- analytics「本週新增」rolling 7 days（vs calendar week）— 文字選擇

## Current Health (Tick 45 baseline)
- **Backend tests**: 286 / 0 fail
- **Frontend tests**: 10 / 0 fail
- **verify-all.sh**: V01-V06 全 PASS（line coverage 82.3%）
- **Total skills DB**: 36 PUBLISHED + 18 DRAFT + 2 SUSPENDED
- **No active bugs**

## Next Tick Suggestions
- 監控 cron 運行情況
- E2E 已飽和；新 bug 須從 user-reported / production telemetry 找
- 維持 health check baseline；如新 bug 才 ship
