# S186 Release Waiting on Dirty Main — 2026-05-17 02:00 CST

## 本輪檢查

Command:

```bash
git status --short
git merge-base --is-ancestor main release/S186-merge-ready-v2
git log --oneline main..release/S186-merge-ready-v2
```

Result:

```text
docs/grimo/specs/spec-roadmap.md remains modified in the main checkout.
merge-base exit: 0
release/S186-merge-ready-v2 ahead commit: f077718 docs(S186): ship embedding colocation release
```

## Blocker

S186 release docs still cannot be merged into main while the main checkout has an unrelated uncommitted edit to `docs/grimo/specs/spec-roadmap.md`.

This tick did not stage, stash, overwrite, or commit the unrelated PRD / architecture / glossary / roadmap / S178 / S187 / S188 / S189 changes.

## 下一步

1. Resolve the existing main checkout changes first: commit, stash, or manually integrate `docs/grimo/specs/spec-roadmap.md`.
2. Before merging S186, re-run:

   ```bash
   git merge-base --is-ancestor main release/S186-merge-ready-v2
   ```

3. If the command exits `0`, run:

   ```bash
   git merge --ff-only release/S186-merge-ready-v2
   ```

4. If the command exits non-zero, rebase `release/S186-merge-ready-v2` onto main first, then retry the fast-forward merge.
