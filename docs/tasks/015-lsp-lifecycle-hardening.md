---
name: LSP lifecycle hardening
status: todo
depends-on: [003]
reviewers: [correctness]
---

# 015 — LSP lifecycle hardening

## Goal

Remove the remaining concurrency trade-offs accepted when ticket 003 landed.

## Scope

- Stop holding the lifecycle read lock across LSP awaits. Use an in-flight
  operation count or latch so close can cancel outstanding requests without
  waiting for request timeouts.
- Keep initialize/shutdown/exit ordering and document-mutation/close exclusion
  without blocking close behind a pending query or shutdown.
- Make server-pushed diagnostics and messages unable to repopulate state after
  close.
- Ensure git repository detection surfaces drain failures instead of falling
  back to a non-git walk.
- Ensure a blocked git descendant is terminated reliably.

## Acceptance criteria

- Close completes within its cleanup bound while a query or shutdown is
  pending.
- No callback can write diagnostics or messages after close.
- A rev-parse drain failure fails discovery instead of silently ignoring
  gitignore.
- The original ticket 003 behavior and tests remain green.
- `just test-engine` and `just lint` pass.
- No comments.

## Out of scope

New LSP methods, expansion logic, and renderer behavior.

## Notes

Ticket 003 may close with findings explicitly deferred here. The deferral must
name this ticket in its review report.
