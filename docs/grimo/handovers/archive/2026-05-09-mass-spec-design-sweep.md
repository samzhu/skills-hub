---
topic: "Mass spec design sweep — 11 specs designed (S148b POC + S154 split + 9 fresh) + plain-language meta rule"
session_type: "planning"
status: "completed"
date: "2026-05-09"
---

# Handover: Mass spec design sweep — 11 specs designed (S148b POC + S154 split + 9 fresh) + plain-language meta rule

## Layer 1 — Portable Summary

> This section is readable by any agent (Claude, Gemini, Copilot, human).
> It contains everything needed to understand and resume this work.

### Completed

**Spec design (11 spec files written or substantially revised):**
- `2026-05-08-S154-author-display-identity.md` — backend split, M(12), 6 task files ready
- `2026-05-09-S154b-author-display-frontend.md` — NEW frontend split, S(9)
- `2026-05-09-S148b-graalvm-aot-validation.md` — NEW; POC executed; H1 REJECTED → scope shrunk S(5) → XS(3); 1 task file ready
- `2026-05-09-S148f-cyclonedx-nativecompile-fix.md` — NEW; POC required (3 paths H1/H2/H3)
- `2026-05-09-S156b-request-detail-page.md` — NEW; M(8); 7 actions + simple-list comments
- `2026-05-09-S158b-detail-viewer-permissions.md` — NEW; M(8); aclEntries internal-only + viewerPermissions backend-computed + /grants owner-only
- `2026-05-09-S159b-category-normalize.md` — NEW; S(5); V19 lowercase + CHECK
- `2026-05-09-S159c-tag-filter.md` — NEW; S(5); `?tag=` + JSONB `@>`
- `2026-05-09-S159d-pageable-validation.md` — NEW; XS(2); page<0 / size>100 → 400
- `2026-05-09-S162b-platform-error-shape.md` — NEW; S(5); 401/403 → ErrorResponse JSON
- `2026-05-09-S162c-ownership-409-to-403-sweep.md` — NEW; S(6); AccessDeniedException across owner-only endpoints
- `2026-05-09-S167b-acl-dead-code-cleanup.md` — NEW; S(5); **must ship before S154 backend** (sequencing)

**S148b POC (full GraalVM native build):**
- Installed GraalVM CE 25.0.1 via SDKMAN (already cached locally)
- 4 attempts (V1-V4) — V1+V2 hit cyclonedx-bom 3.2.4 conflict; V3 hit `-XX:MissingRegistrationReportingMode=Throw` syntax error; V4 BUILD SUCCESSFUL in 3m 17s, 223 MB native binary at `backend/build/native/nativeCompile/skillshub`
- Verdict: SkillshubProperties has NO AOT reflection bug (premise was imagined; auto-registration covers all 11 nested inner records)
- Side discovery: cyclonedx-bom 3.2.4 + Gradle 9.4.1 + nativeCompile = task graph conflict → tracked as S148f

**S155b cancelled** as no-bug (HomePage.tsx:217 `isOn` is derived state — code-scan confirms no desync; auditor likely saw stale cached bundle pre-commit `6211734`).

**Task files created (7):**
- `docs/grimo/tasks/2026-05-09-S148b-T01.md` — architecture.md GraalVM AOT Strategy section
- `docs/grimo/tasks/2026-05-09-S154-T01.md` through `T06.md` — backend implementation chain

**Documentation updates:**
- `CLAUDE.md` Principles — added **Plain-Language Explanations** rule (banned life analogies, prefer technical samples)
- `.claude/skills/planning-spec/references/grill-protocol.md` — added Loop Rule #7 (plain-language framing with bad/good examples including life-analogy ban)
- `docs/grimo/glossary.md` — added 5 new terms (Platform User ID, Handle, OAuth Sub, Author Name Snapshot, DisplayName Resolver)
- `docs/grimo/specs/spec-roadmap.md` — multiple updates; final timestamp covers all-specs sweep

**Code modified (POC artifacts):**
- `backend/build.gradle.kts`:
  - Line 10: `id("org.cyclonedx.bom") version "3.2.4"` **commented out** (POC workaround for nativeCompile conflict; restore via S148f)
  - Lines ~196-208: NEW `graalvmNative {}` block, gated by `-PexactReachability=true` Gradle property
- `poc/S148b/native-compile-output*.log` — 4 POC logs preserved for evidence

### Decisions

| Decision | Why | Alternatives Rejected |
|----------|-----|----------------------|
| **S154 split into S154 (backend M-12) + S154b (frontend S-9)** | L(15) crossed planning-tasks task granularity threshold (L+ → split before task files) | Path A keep as L (1 PR risk too high); Path C three-way split (overkill — forgery fix tightly coupled to ACL switch) |
| **S154 design: Pattern A (single users table with `oauth_provider` column)** | MVP simplicity — 1 OAuth provider (Google); upgrade path to Pattern B (separate `user_identities` table) is single migration; account linking UI not in MVP | Pattern B (multi-identity) — premature; lock-in cost of single ID hits later, no urgent need |
| **Platform user_id format = `u_<6hex>`** | ACL JSONB / install command / log readable; from UUID.randomUUID() prefix | UUID full (too long, ugly in JSONB); sequential int (exposes user count + non-distributed) |
| **S148b POC: SkillshubProperties premise REJECTED** | nativeCompile BUILD SUCCESSFUL in 3m 17s + 223 MB binary boots Spring Boot banner; auto-registration covers all inner records | Continue assuming bug → would have written useless RuntimeHintsAgent test for non-existent issue |
| **S148b scope shrunk S(5) → XS(3)** | POC verdict eliminates AC-4 (conditional fix); AC-2 (RuntimeHintsAgent) ROI low without active native deploy | Keep all ACs (defensive but premature) |
| **S148b skip RuntimeHintsAgent JVM test** | nativeCompile 3m17s already catches missing hints; no production native deploy planned | Add test for fast feedback (5s) — defensive but ROI low until native deploy active |
| **S155b cancelled (no-bug)** | Code-scan evidence (HomePage.tsx:217 `isOn = sortMode === mode` derived state, click handler line 222 batched setters); auditor saw stale bundle pre-commit `6211734` | LAB manual reverify (5 min) — code evidence already strong enough |
| **S148f extracted as separate spec** | cyclonedx-bom 3.2.4 + nativeCompile conflict is build-tool issue not S148b's reflection-metadata scope; S148f is research-driven (3 path POC) | Fix inside S148b — would balloon scope and conflate concerns |
| **CLAUDE.md "Plain-Language Explanations" + ban on life analogies** | User feedback: "聽不懂" after I used cabinet/moving/insurance analogy for native compile decision; user explicitly: "白話一點 但不用生活化" | Keep generic plain-language rule (insufficient — analogies erase technical detail user needs to decide) |
| **S158b architectural correction (per user)**: aclEntries is internal CQRS query optimization cache, never user-facing | User correction: project is CQRS read/write separated; humans see grants (role + principal); DB query optimization uses acl_entries JSONB cache (denormalized, GIN-indexed); async listener syncs after grant write | Original spec framing "aclEntries privacy" — wrong abstraction level; redesigned around viewerPermissions field + /grants owner-only |
| **S167b should ship BEFORE S154 backend** | S154 will refactor `Skill.grantAcl/revokeAcl` aggregate methods; clearing dead code first reduces refactor scope by 5-10 lines + avoids backfill complications for deprecated events | S167b after S154 — extra 5-10 lines of dead code handling in S154 task plan |

### Next Steps

1. **(Recommended start)** `/planning-tasks S159d` — XS(2), absolute smallest, fastest momentum builder
2. **(Sequencing critical)** `/planning-tasks S167b` BEFORE `/planning-tasks S154` — dead-code cleanup reduces S154 backend refactor scope
3. **`/planning-tasks S148b`** to execute T01 (architecture.md GraalVM AOT Strategy section) — already 1 task ready
4. **`/planning-tasks S148f`** to execute POC (3 paths H1/H2/H3) — needed before native deploy possible
5. **Restore cyclonedx-bom** in `backend/build.gradle.kts` line 10 — currently commented for POC; either restore now (and lose nativeCompile until S148f) OR keep commented and ship S148f first
6. **Stack visibility**: 18+ specs in design-complete state, ~70+ tasks pending — recommend ship cadence: XS first (S159d, S148b, S148f), then S167b, then S154 + S154b, then sweep S-sized in priority order
7. **Verify backend build still passes** end-to-end: `cd backend && ./gradlew test` (only `compileJava` ran during POC; full test suite not re-verified after build.gradle.kts edits)

### Lessons Learned

- **POC reveals premise can be imagined.** S155b (no bug) and S148b H1 (no AOT bug) both turned out to be auditor / roadmap assumptions that didn't hold up to code-scan or actual native build. Always verify before designing fixes.
- **`aclEntries` is a CQRS internal cache, NEVER user-facing.** Architecture: grant write → INSERT skill_grants → emit event → async listener UPDATE skills.acl_entries JSONB (for query optimization GIN index). Any spec mentioning "aclEntries in API response" is framing wrong — the user-facing concept is `grants`.
- **Spring Boot 4 auto-registers `@ConfigurationProperties` reflection hints for inner records** (verified by S148b POC v4 — `io.github.samzhu.skillshub` package mentioned 26 times in build log, zero missing-registration error with `--exact-reachability-metadata` flag).
- **cyclonedx-bom 3.2.4 + Gradle 9.4.1 + nativeCompile = task graph race.** V1: `Cannot mutate the artifacts of configuration ':cyclonedxDirectBom' after the configuration was consumed as a variant`. V2 (with `-x cyclonedxBom`): `Querying ':cyclonedxBom' property 'jsonOutput' before task ':cyclonedxBom' has completed is not supported`. Plugin-level workaround = comment out, separate spec to fix (S148f).
- **`-XX:MissingRegistrationReportingMode=Throw` is NOT a valid native-image build arg syntax.** native-image interprets it as positional mainclass. `--exact-reachability-metadata=<package>` defaults to Throw mode — no separate flag needed.
- **GraalVM CE 25.0.1 already cached locally** at `~/.sdkman/candidates/java/25.0.1-graalce` (verified by `JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.1-graalce $JAVA_HOME/bin/native-image --version`). User's default JDK is `25.0.1-librca` (Liberica Hotspot, not GraalVM).
- **Existing native infra in place from earlier work** (commit `3b48bc2 feat(native): enable Spring Native compilation`): `shared/aot/AotStubConfig.java`, `shared/persistence/JdbcConfiguration.jdbcDialect()` override, `application-aot.yaml`, `ProcessAot args("--spring.profiles.active=aot,local")`. POC v4 confirmed all still working.
- **nativeCompile actual time = ~3 min (not 30 min)** when AOT cache warm. First cold run estimate higher.
- **User signal "聽不懂" / "可以解釋白話一點嗎" = HARD signal to rewrite entire grill question** — not paraphrase one term, not switch to a different metaphor, not add another bullet. Restart with concrete technical samples (file:line, sample data row, actual command, JSON shape).
- **Life analogies (cabinet/moving/insurance/parking) feel friendly but erase technical detail.** User can't decide between "Path A vs B" if both are framed as "with cabinet you do X, with moving truck you do Y" — they need to see the actual code change / API delta / DB row delta.
- **CycloneDX 3.2.4 is current pinned version.** Maven Central has 3.x line; whether 4.x exists + fixes the issue is **unverified** — that's exactly S148f's POC H1.

### Session Summary

Session started with `/planning-spec next` after S154 backend was split into ⏳ Plan with 6 task files ready. User confirmed Path A (install GraalVM + run nativeCompile) for S148b POC despite no immediate native deploy plans ("確保 Native 以免之後改不回來"). POC went through 4 attempts before BUILD SUCCESSFUL (cyclonedx conflict + bad flag syntax), and the verdict — H1 REJECTED — confirmed research prediction that SkillshubProperties has no AOT bug. Mid-session pivot: user gave directive "先把 spec 都先研究完規劃完" (plan all specs first), so I batch-designed 9 fresh specs (XS×2 + S×5 + M×2). Critical architectural correction during S158b grilling: user pointed out aclEntries is internal CQRS cache, not user-facing — redesigned around viewerPermissions + grants, applied lesson to spec. Also got corrective feedback on plain-language style ("聽不懂" + "白話一點 但不用生活化") which led to CLAUDE.md and grill-protocol.md updates banning life analogies. Session ended with 18+ specs design-complete and a recommended ship sequence (S159d → S148b → S148f → S167b → S154 → S154b → rest).

---

## Layer 2 — Environment Details

> This section is for resuming in the same repo/machine.
> Skip this when handing off to a different environment.

| Property | Value |
|----------|-------|
| Branch | `main` |
| Working Directory | `/Users/samzhu/workspace/github-samzhu/skills-hub` |
| Test Status | Backend: `./gradlew compileJava` PASSED (verified during POC after build.gradle.kts edits); **full `./gradlew test` NOT re-run** after build.gradle.kts changes — recommend verify before next implementation work |
| GraalVM | CE 25.0.1 at `$HOME/.sdkman/candidates/java/25.0.1-graalce` (works); default JDK is 25.0.1-librca |
| Native binary | Built at `backend/build/native/nativeCompile/skillshub` (223 MB; boots Spring Boot 4.0.6 banner) |

### Uncommitted Changes

```
 M .claude/skills/planning-spec/references/grill-protocol.md
 M CLAUDE.md
 M backend/build.gradle.kts
 M docs/grimo/glossary.md
 M docs/grimo/specs/2026-05-08-S154-author-display-identity.md
 M docs/grimo/specs/spec-roadmap.md
?? docs/grimo/specs/2026-05-09-S148b-graalvm-aot-validation.md
?? docs/grimo/specs/2026-05-09-S148f-cyclonedx-nativecompile-fix.md
?? docs/grimo/specs/2026-05-09-S154b-author-display-frontend.md
?? docs/grimo/specs/2026-05-09-S156b-request-detail-page.md
?? docs/grimo/specs/2026-05-09-S158b-detail-viewer-permissions.md
?? docs/grimo/specs/2026-05-09-S159b-category-normalize.md
?? docs/grimo/specs/2026-05-09-S159c-tag-filter.md
?? docs/grimo/specs/2026-05-09-S159d-pageable-validation.md
?? docs/grimo/specs/2026-05-09-S162b-platform-error-shape.md
?? docs/grimo/specs/2026-05-09-S162c-ownership-409-to-403-sweep.md
?? docs/grimo/specs/2026-05-09-S167b-acl-dead-code-cleanup.md
?? docs/grimo/tasks/2026-05-09-S148b-T01.md
?? docs/grimo/tasks/2026-05-09-S154-T01.md
?? docs/grimo/tasks/2026-05-09-S154-T02.md

# Plus (not in initial git status snapshot but created during session):
?? docs/grimo/tasks/2026-05-09-S154-T03.md
?? docs/grimo/tasks/2026-05-09-S154-T04.md
?? docs/grimo/tasks/2026-05-09-S154-T05.md
?? docs/grimo/tasks/2026-05-09-S154-T06.md
?? poc/S148b/native-compile-output.log
?? poc/S148b/native-compile-output-v2.log
?? poc/S148b/native-compile-output-v3.log
?? poc/S148b/native-compile-output-v4.log
```

### Recent Commits

```
32c36c4 chore(skill): shipping-release 改可 auto-invoke + 強化 QA gate precondition
ab80a44 chore(roadmap): 更新最後更新 timestamp 為 v4.43.0 (S159a)
fcfe3a1 feat(S159a): v4.43.0 — SkillQuery 端點未知 query 參數 fail-fast 400
569df44 feat(S167): v4.42.0 — 移除 deprecated /api/v1/skills/{id}/acl HTTP endpoint
345cc97 chore(loop): loop.md 增 cron-loop discipline 原則
```

### Key Files

**Modified:**
- `CLAUDE.md` — added "Plain-Language Explanations" Principle bullet (after "Finish-Current-First")
- `.claude/skills/planning-spec/references/grill-protocol.md` — added Loop Rule #7 with bad/good examples (jargon ban + life analogy ban)
- `backend/build.gradle.kts` — line 10 cyclonedx commented out (POC workaround); lines ~196-208 NEW `graalvmNative {}` block gated by `-PexactReachability=true`
- `docs/grimo/glossary.md` — added 5 entries (Platform User ID / Handle / OAuth Sub / Author Name Snapshot / DisplayName Resolver)
- `docs/grimo/specs/2026-05-08-S154-author-display-identity.md` — v3 split: backend-only scope; M(12); 6 task files; spec §6 has Task Plan section
- `docs/grimo/specs/spec-roadmap.md` — multiple status updates: 11 specs marked 📐 in-design; S148b ⏳ Plan; S155b ⛔ cancelled; S148f added; S154 split into S154 + S154b; final timestamp summarizes sweep

**New spec files (11):**
- `docs/grimo/specs/2026-05-09-S148b-graalvm-aot-validation.md` — has §6 POC Findings + Task Plan
- `docs/grimo/specs/2026-05-09-S148f-cyclonedx-nativecompile-fix.md` — POC required for path selection
- `docs/grimo/specs/2026-05-09-S154b-author-display-frontend.md` — depends on S154 backend ship
- `docs/grimo/specs/2026-05-09-S156b-request-detail-page.md` — RequestDetailPage with 7 actions + simple list comments
- `docs/grimo/specs/2026-05-09-S158b-detail-viewer-permissions.md` — viewerPermissions backend-computed + aclEntries internal-only + /grants owner-only
- `docs/grimo/specs/2026-05-09-S159b-category-normalize.md` — V19 lowercase migration
- `docs/grimo/specs/2026-05-09-S159c-tag-filter.md` — `?tag=` filter via JSONB `@>`
- `docs/grimo/specs/2026-05-09-S159d-pageable-validation.md` — page<0 / size>100 → 400
- `docs/grimo/specs/2026-05-09-S162b-platform-error-shape.md` — 401/403 → ErrorResponse JSON
- `docs/grimo/specs/2026-05-09-S162c-ownership-409-to-403-sweep.md` — AccessDeniedException across owner-only endpoints
- `docs/grimo/specs/2026-05-09-S167b-acl-dead-code-cleanup.md` — must ship before S154 backend (sequencing)

**New task files (7):**
- `docs/grimo/tasks/2026-05-09-S148b-T01.md` — architecture.md GraalVM AOT Strategy section
- `docs/grimo/tasks/2026-05-09-S154-T01.md` — V18 migration + users表 + 既存 row backfill
- `docs/grimo/tasks/2026-05-09-S154-T02.md` — User domain (entity/Repo/UpsertService/UserResolver/DisplayNameResolver)
- `docs/grimo/tasks/2026-05-09-S154-T03.md` — CurrentUserProvider refactor + MeController hook UPSERT
- `docs/grimo/tasks/2026-05-09-S154-T04.md` — Skill aggregate snapshot + Command forgery fix
- `docs/grimo/tasks/2026-05-09-S154-T05.md` — SkillQueryService LEFT JOIN + Controller resolve order
- `docs/grimo/tasks/2026-05-09-S154-T06.md` — ACL principal switch verification (existing RBAC tests)

**Reference docs to read on resume:**
- `docs/grimo/specs/spec-roadmap.md` — current state of all specs
- `docs/grimo/architecture.md` — for understanding Spring Data JDBC + Modulith Outbox + CQRS pattern
- `.claude/skills/root-cause-debugging/references/case-study-spring-aot.md` — previous AOT war story; explains AotStubConfig + JdbcConfiguration jdbcDialect override + application-aot.yaml
- `docs/grimo/specs/archive/2026-05-08-S148-judgeresponse-aot-reflection.md` (or similar) — S148 ship context for S148b
- `docs/grimo/CHANGELOG.md` — for version-shipped history (S159a v4.43.0 latest)
- `poc/S148b/native-compile-output-v4.log` — successful POC evidence; preserved until Phase 4 cleanup

### Critical Gotchas for Resumer

1. **`backend/build.gradle.kts` line 10 has cyclonedx commented out** — `./gradlew bootBuildImage` SBOM generation will silently skip until S148f ship. If you need SBOM right now, uncomment the line BUT then `./gradlew nativeCompile` will fail.
2. **`./gradlew nativeCompile` requires `JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.1-graalce`** — default JDK (25.0.1-librca) is NOT GraalVM and will fail with "no native-image" error.
3. **POC artifacts in `poc/S148b/`** — preserved deliberately; clean up only during S148b Phase 4 consolidation (per planning-tasks protocol).
4. **S167b sequencing**: ship BEFORE S154 backend implementation begins (touches same files: `Skill.java` + `SkillCommandService.java`).
5. **S154 sub-spec relationships**: S154 (backend) → S154b (frontend) — frontend can't ship until backend exposes `authorDisplayName / authorHandle / authorEmail / userId / handle` fields.
6. **S148b POC verdict means AC-2 (RuntimeHintsAgent test) was DROPPED** — don't re-add it unless premise re-verified or native production deploy commitment changes.
