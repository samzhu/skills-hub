---
name: git-conflict-resolver
description: Resolves Git merge conflicts interactively. Use when `git merge`, `git rebase`, or `git pull` produces conflict markers (<<<<<<<, =======, >>>>>>>). Not for resolving logical code conflicts or reviewing unrelated changes.
allowed-tools:
  - Bash(git:*)
  - Read
  - Edit
---
# Git Conflict Resolver

Resolves merge/rebase conflicts by reading conflict markers, understanding both sides, and applying the correct resolution.

## When to use

Activate when the user says any of:
- "I have merge conflicts"
- "git merge failed"
- "git pull produced conflicts"
- File shows `<<<<<<<` markers

Do NOT use for: cherry-pick strategy decisions, rebase squash operations, or conflicts caused by encoding issues.

## Steps

1. **List conflicted files** — `git diff --name-only --diff-filter=U`
2. **For each file**, read the conflict markers:
   ```
   <<<<<<< HEAD
   your changes
   =======
   incoming changes
   >>>>>>> branch-name
   ```
3. **Determine the correct resolution**:
   - Keep HEAD: delete the `=======` to `>>>>>>>` section
   - Keep incoming: delete the `<<<<<<<` to `=======` section
   - Merge both: combine both sections, remove all markers
4. **Edit the file** — use the Edit tool to apply the resolution
5. **Stage the resolved file** — `git add <file>`
6. **Verify** — `git diff --cached` to confirm the resolution is staged
7. **Complete** — `git merge --continue` (or `git rebase --continue`)

## Example

```
<<<<<<< HEAD
const timeout = 5000;
=======
const timeout = 10000; // increased for slow networks
>>>>>>> feature/network-retry
```

Resolution (keep incoming with the comment):
```
const timeout = 10000; // increased for slow networks
```

## Output format

After resolution, report:
- Files resolved (list)
- Resolution strategy used (keep ours / keep theirs / manual merge)
- `git status` output confirming no remaining conflicts
