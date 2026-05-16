# S186 Release Branch Merge Blocker — 2026-05-17

## 現況

`release/S186-ship` 已有 release commit：

- Commit: `75ac61f docs(S186): ship embedding colocation release`
- Base: `main` 的 `2e71766 docs(S186): record shipping preflight blocker`
- `git merge-base --is-ancestor main release/S186-ship` 回傳 `0`，代表 branch 仍可從 main 快轉。

## 本輪重試

Command:

```bash
git merge --ff-only release/S186-ship
```

Result:

```text
error: Your local changes to the following files would be overwritten by merge:
	docs/grimo/specs/spec-roadmap.md
Please commit your changes or stash them before you merge.
Aborting
Updating 2e71766..75ac61f
```

## Blocker

main checkout 目前有使用者未提交的 `docs/grimo/specs/spec-roadmap.md` 修改。S186 release commit 也會改同一個檔案，所以不能安全 merge；本輪沒有 stash、覆寫或 commit 任何 unrelated user changes。

## 下一步

1. 先由使用者或後續 tick 處理 main 上的 `docs/grimo/specs/spec-roadmap.md` 未提交修改：commit、stash、或手動整合。
2. 因為本 blocker note 已先 commit 到 main，後續有兩個安全選項：

   ```bash
   git merge release/S186-ship
   ```

   或先把 `release/S186-ship` rebase 到最新 main，再快轉：

   ```bash
   git rebase main release/S186-ship
   git merge --ff-only release/S186-ship
   ```

3. merge 成功後，S186 的 local release docs 才會進 main；production deploy / Cloud Run 覆測仍是後續工作。
