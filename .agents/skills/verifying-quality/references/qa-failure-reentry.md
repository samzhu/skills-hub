# QA Failure Re-entry Protocol

Use this protocol for every `REJECT-FIX` or `REJECT-BLOCKED` verdict.

The failing spec remains the single source of truth. Do not leave the
failure only in chat, and do not hotfix directly after QA fails.

## Required Sequence

1. Write concrete failure evidence back to the spec before ending the QA turn:
   command, log path, exact failed gate/test, observed output, expected output,
   affected ACs, and whether the issue is inside the current spec or likely a
   separate spec.
2. Change the spec status back to `⏳ Dev (bug fix)` or an equivalent
   non-shippable state when the spec was previously all-tasks-PASS.
   Update the roadmap row at the same time so the loop controller does not
   mistake an all-tasks-PASS-but-QA-failed spec for something ready to ship.
3. Route back to `$planning-tasks <spec-id>` so the controller can re-plan
   from the recorded findings.
4. Add new task files for the fixes. Never edit old `PASS` task files into a
   different story, and never reuse old task IDs. Use the next task number
   (`T04`, `T05`, etc.) or a clearly marked round such as `Round 2`.
5. Each new task must reference the QA finding by spec section and describe
   the observable RED/GREEN check.
6. After the new tasks pass, rerun `/verifying-quality` and append a new QA
   review entry instead of overwriting the old failed one.

## External Blockers

If the QA failure is outside the current spec's scope, still record it in the
spec as an external blocker. Then create or propose the separate bug/testing
spec and link it from the blocked spec.

The current spec does not ship until the blocker is resolved or explicitly
deferred by the user.
