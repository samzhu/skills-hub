# S176 Shipping Preflight Blocked — 2026-05-15

## Tick Context

- Automation: `skills-hub-production-debug-loop`
- Target spec: `S176 Explicit Publish Skill Name`
- Intended unit: run `$shipping-release` pre-flight for S176 after `verify-all` PASS.

## Evidence

`docs/grimo/specs/2026-05-15-S176-explicit-publish-skill-name.md` already records:

- `SKIP_NATIVE=1 ./scripts/verify-all.sh`
- V01/V03/V04/V05/V06/V07/V08a PASS
- V02 INFO line coverage 85.9%（covered=4622 / total=5382）
- V08b SKIP because `SKIP_NATIVE=1`
- Verdict: all CRITICAL passed; exit=0

This satisfies the local verification result for S176.

## Blocker

`$shipping-release` pre-flight requires `git status` to be clean of unrelated changes before archive/changelog/release edits. Current checkout is not clean:

```text
 M backend/src/main/java/io/github/samzhu/skillshub/search/SearchProjection.java
 M backend/src/test/java/io/github/samzhu/skillshub/search/SearchProjectionAclWriteTest.java
 M backend/src/test/java/io/github/samzhu/skillshub/search/SearchProjectionTest.java
?? backend/src/main/resources/db/migration/V26__sync_vector_store_acl_entries.sql
```

The changed files mention `S177` / `AC-S177` and update `vector_store.acl_entries` behavior, so they are not part of S176. This tick did not stage or commit those files.

## Next Step

After the S177 ACL/vector-store working tree changes are committed, moved to another worktree, or otherwise cleared by the owner, resume `$shipping-release` for S176:

1. Confirm `git status --short` is clean or only contains S176 release-doc changes.
2. Re-run `SKIP_NATIVE=1 ./scripts/verify-all.sh` because shipping-release requires same-tick evidence.
3. Archive S176 spec, delete `docs/grimo/tasks/*-S176-*.md`, update `docs/grimo/CHANGELOG.md`, update `spec-roadmap.md`, and commit the release.
