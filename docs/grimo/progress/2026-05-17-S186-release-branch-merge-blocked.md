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

### 2026-05-17 01:50 CST 更新

後續 tick 已把 `75ac61f` 乾淨重放到最新 main blocker-note 之後。因 blocker note 本身會繼續前進 main，後續不要依賴文件中的舊 commit hash；合併前先跑 `merge-base` 檢查。

- Preferred branch: `release/S186-merge-ready-v2`
- Check command: `git merge-base --is-ancestor main release/S186-merge-ready-v2`
- Expected result before ff-only merge: exit `0`

`release/S186-ship` 與 `release/S186-merge-ready` 保留作歷史對照；後續不要再優先使用它們。

1. 先由使用者或後續 tick 處理 main 上的 `docs/grimo/specs/spec-roadmap.md` 未提交修改：commit、stash、或手動整合。
2. 使用新的 merge-ready-v2 branch；若 check command 回 `0`，執行：

   ```bash
   git merge --ff-only release/S186-merge-ready-v2
   ```

3. 若 check command 回非 0，代表 main 又有新的 blocker/progress commit；先把 `release/S186-merge-ready-v2` rebase 到 main，再重新檢查。

4. 只有需要比對舊 commit 時才看 `release/S186-ship`。若要用舊 branch，需要 normal merge 或先 rebase：

   ```bash
   git merge release/S186-ship
   ```

   或先把 `release/S186-ship` rebase 到最新 main，再快轉：

   ```bash
   git rebase main release/S186-ship
   git merge --ff-only release/S186-ship
   ```

5. merge 成功後，S186 的 local release docs 才會進 main；production deploy / Cloud Run 覆測仍是後續工作。
