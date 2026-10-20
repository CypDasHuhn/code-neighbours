## Round 1

**Verdict:** Changes requested.

The stdio client, workspace discovery, and Kotlin descriptor cover the ticket's
happy path, and the fake server exercises initialize, initialized, every query
method, a timeout, the semantic-token capability gate, failed process start,
document versions, gitignore discovery, and the default no-symlink policy. The
remaining correctness problems are nullable LSP results, a workspace flag
silently ignored in git mode, ineffective git timeouts, document-version and
close races, stale document/diagnostic state, a mode-dependent exclusion policy,
undetected transport failure, and failure-path test gaps.

### Findings

1. **Nullable LSP results are not normalized**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:217-260`.
   - Problem: LSP permits a null result for `textDocument/documentSymbol`,
     `textDocument/references`, `textDocument/definition`,
     `textDocument/semanticTokens/full`, and `workspace/symbol`. `await` returns
     the raw future result. `references` calls `.toList()` on it (line 230),
     `definition` and `workspaceSymbol` call `.isLeft` on it (lines 241 and 257),
     `semanticTokensFull` returns it as a non-null `SemanticTokens` (line 250),
     and `documentSymbol` returns it as a non-null list (line 220). A legal null
     response therefore produces a raw `NullPointerException` or leaks null
     through a non-null Kotlin type instead of an empty/absent result or an
     `LspException`.
   - Suggested fix: Normalize each nullable result (`?: emptyList()`, a nullable
     return for semantic tokens, or an empty token set) and keep genuine failures
     mapped to `LspException`. Add fake-server tests that return null for every
     query method.

2. **`followSymlinks` is ignored in git repositories**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/WorkspaceFiles.kt:37-42`, `53-65`, `89-116`.
   - Problem: `discover` passes `followSymlinks` only to `walkPaths`. When the
     root is inside a git work tree it calls `gitPaths`, which cannot follow
     symlinks, and `toWorkspaceFile` always rejects symlinks with
     `LinkOption.NOFOLLOW_LINKS`. Setting `followSymlinks = true` therefore has
     no effect in a git repository, silently violating the configurable policy.
     The task requires no following *by default*, so true must follow.
   - Suggested fix: Apply the chosen symlink policy in both modes, for example
     by using git only to obtain the ignore-filtered path set and then walking or
     resolving with the requested policy, or by resolving symlink entries when
     true. If git mode cannot support it, reject the combination explicitly.
     Add a test with `followSymlinks = true` for both a symlinked file and a
     symlinked directory.

3. **Git command timeouts can never fire**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/WorkspaceFiles.kt:76-86`, `106-111`.
   - Problem: Both `isGitRepository` and `gitPaths` fully drain stdout with
     `readAllBytes()` before calling `waitFor(timeout)`. If git stalls with
     stdout still open, the read blocks forever and the timeout is never
     reached, so workspace discovery can hang indefinitely and the non-git
     fallback is never taken.
   - Suggested fix: Bound the whole operation, for example by reading on a
     separate thread with a bounded join, waiting with a timeout while draining
     concurrently, or closing/destroying the process on timeout. Surface the
     timeout as an explicit workspace error. Add a test through an injectable
     command runner or a fake git executable.

4. **Document version updates are not atomic**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:188-199` (also `176-205`).
   - Problem: `didChange` performs a read-modify-write of the version on a
     `ConcurrentHashMap`. Two concurrent changes for the same URI both read
     version N, both send N+1, and the last map write wins, producing duplicate
     versions and potentially lost text. `didOpen` and `didClose` can also
     interleave with a change. Multiple query requests are supported, but this
     document state is not safe for concurrent use.
   - Suggested fix: Serialize mutations per URI (a per-document lock or an
     atomic `compute`), and define duplicate-`didOpen`/close-while-changing
     behavior. Add a test that applies concurrent changes and asserts
     strictly increasing, unique versions and the final text.

5. **Close is not idempotent and document state is not torn down**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:283-311`, `201-205`, `316-329`.
   - Problem: `close()` checks `closed`, then performs shutdown and exit, and
     sets `closed` only at the end (lines 284-300). Concurrent callers can both
     pass the check and send duplicate `shutdown`/`exit` calls; a request can
     also start between the check and the flag. Open documents are never
     `didClose`d before shutdown, and `documents`, `diagnosticsByUri`, and
     `messages` are never cleared. `didClose` also leaves the URI's diagnostics,
     so a closed or reopened document exposes stale diagnostics. This is the
     close-ordering and state-leak path.
   - Suggested fix: Claim close atomically at entry and funnel shutdown/exit
     through one path, send `didClose` for tracked documents before shutdown,
     clear all per-document maps (including diagnostics on `didClose`), and
     bound the shutdown wait before destroying the process. Add tests for
     concurrent close and close with open documents.

6. **Directory exclusions differ between git and non-git discovery**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/WorkspaceFiles.kt:24-25`, `37-42`, `137-139`.
   - Problem: `EXCLUDED_DIRECTORIES` is applied only to the non-git walk. In a
     git repository a tracked `build/...kt` or `node_modules/...kt` is returned
     by `git ls-files` and included, while the identical tree outside git is
     silently excluded. An explicit include also cannot re-include such a
     directory outside git. Discovery therefore depends on the presence of
     `.git`, and include is not authoritative.
   - Suggested fix: Apply one directory policy in both modes and let explicit
     include override defaults, or remove the hard-coded set and make
     include/exclude the only policy. Add tests for a tracked `build` file in a
     repository and the equivalent non-git tree.

7. **Transport failure is not observed while a request is pending**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:101-127`, `358-383`.
   - Problem: The `listening` future and process state are never consulted.
     LSP4J does not complete pending request futures when the input stream ends,
     so if the server dies after a request is written, `await` waits for the
     full timeout and reports `LspTimeoutException` rather than a transport
     failure. Subsequent calls can repeat the same delay, and the error path
     cannot distinguish a dead server from a slow one.
   - Suggested fix: Fail pending requests when the listener terminates or the
     process exits, or check `listening.isDone`/`process.isAlive` in `await` and
     map that state to `LspTransportException`. Add a fake server that exits
     after accepting a request and assert a transport error rather than a
     timeout.

8. **Failure-path and boundary tests are missing**

   - Location: `analysis-engine/lsp/src/test/kotlin/dev/codenb/lsp/LspClientTest.kt`, `FakeLspServer.kt`, `WorkspaceFilesTest.kt`, `LspProcessTest.kt`.
   - Problem: There is no test for an LSP error response (`LspResponseException`
     is unexercised), a malformed response, a server that dies mid-request, an
     initialize failure/cleanup, null or empty query results, concurrent
     document changes, multiple in-flight requests, or concurrent close. The
     timeout test proves only that the timeout fires; the workspace tests do not
     cover `followSymlinks = true`, a nested git root, or a tracked excluded
     directory.
   - Suggested fix: Extend `FakeLspServer` with options to return a response
     error, return null, and exit on a chosen method, and add a raw stdio server
     for malformed framing. Add the workspace and concurrency cases above and
     assert the specific exception type plus cleanup after each failure.

### Concur

- The happy-path lifecycle is correct: `initialize` sends root, workspace
  folders, client info, and the negotiated capabilities, marks the client
  initialized, and sends `initialized` once. `shutdown` and `exit` are sent in
  the expected order by a single-threaded `close`.
- Request timeout cancellation is real: LSP4J's request future sends
  `$/cancelRequest` on `cancel(true)`, so the timeout path cancels the server
  request as well as the local wait.
- A missing `semanticTokens/full` provider produces an explicit
  `LspUnsupportedCapabilityException`, and the capability check handles both
  boolean and registration-option forms.
- Failed process start maps to `LspTransportException`, and the successful
  process test verifies cleanup and process death.
- Document open/change/close tracks text and increments versions correctly in
  the single-threaded path; full-text change events are valid.
- Git discovery uses `--cached --others --exclude-standard`, respects
  `.gitignore` in the tested repository, and returns root-relative paths.
  Include then exclude precedence, deduplication, sorting, and the root-prefix
  guard behave as intended.
- Symlinks are not followed by default in both the walk and git paths, and the
  walker skips excluded directories and loops safely.
- The Kotlin descriptor uses one id and graph scope, declares `.kt`/`.kts`, the
  `kotlin-lsp --stdio` default command, namespace grouping, and root markers,
  and the registry resolves it uniquely.
- The tests use a fake stdio server and a spawned fake process, so no live LSP
  installation is required.
- The authored source and tests contain no comments.

### Dissent

- Finding 1: nullable LSP results must be normalized, not left to raw NPEs.
- Finding 2: `followSymlinks = true` must not be silently ignored in git mode.
- Finding 3: git timeouts must actually bound the wait.
- Finding 4: document version mutations must be atomic under concurrent use.
- Finding 5: close must be idempotent and must tear down document state.
- Finding 6: directory filtering must not depend on whether `.git` exists.
- Finding 7: server death must map to a transport failure, not a timeout.
- Finding 8: the listed failure paths and boundaries need direct tests.

## Round 2

**Verdict:** Changes requested.

Most of the Round 1 findings are addressed and now have direct tests. The main
remaining correctness problem is in close coordination: a document mutation that
has already passed its `closeClaimed`/lifecycle check can still complete after
`close()` has claimed and torn the client down, and the `shutdown()`-first path
skips the new document cleanup entirely. Finding 8 is also only partially met
because concurrent in-flight queries remain untested, and the bounded git
runner can silently return truncated output when a drain thread does not finish
after the process exits.

### Finding disposition

1. **Nullable results — Concur.** `await` now returns `T?`, each query
   normalizes null to an empty result or nullable token set
   (`LspClient.kt:245-288`, `463-505`), and
   `normalizesNullResultsForEveryQuery` exercises every method
   (`LspClientTest.kt:172-193`).

2. **`followSymlinks` in git mode — Concur.** Git workspaces now reject
   `followSymlinks = true` with `WorkspaceSymlinkException`
   (`WorkspaceFiles.kt:122-125`), non-git roots follow both symlinked files and
   directories, and both paths are tested (`WorkspaceFilesTest.kt:106-151`).
   Explicit rejection is an acceptable resolution.

3. **Git command timeouts — Concur.** `ProcessGitCommandRunner` drains stdout
   and stderr concurrently, waits with a deadline, destroys the process on
   timeout, and surfaces `WorkspaceTimeoutException`
   (`WorkspaceFiles.kt:47-103`, `182-218`). The stalled-command test confirms
   the operation is bounded (`WorkspaceFilesTest.kt:182-217`). The separate
   successful-exit drain truncation risk is New Finding 3.

4. **Atomic document versions — Concur.** `didOpen`, `didChange`, and
   `didClose` serialize on a per-URI lock, duplicate opens are rejected, and
   the concurrent-change test asserts the exact `1..21` version sequence
   (`LspClient.kt:193-233`, `406-411`; `LspClientTest.kt:326-358`).

5. **Close idempotency, bound, and teardown — Dissent (partial).** The close
   claim is atomic, the shutdown wait uses `cleanupTimeout`, documents are
   closed and all maps cleared on the direct close path, and concurrent-close,
   open-document, and unresponsive-shutdown tests exist
   (`LspClient.kt:324-360`; `LspClientTest.kt:252-324`). However, close is not
   serialized with mutations already past their check, and the cleanup is
   skipped when `shutdown()` ran first. See New Findings 1 and 2.

6. **Directory exclusions — Concur.** Default directory exclusions now apply in
   both git and walk modes, explicit include patterns can opt a directory back
   in, and the test covers a tracked `build/Generated.kt` in git and the same
   tree outside git (`WorkspaceFiles.kt:106-159`, `220-265`;
   `WorkspaceFilesTest.kt:153-180`).

7. **Transport failure during a pending request — Concur.** `await` polls the
   future, listener, and process, and maps listener/process termination to
   `LspTransportException` (`LspClient.kt:463-505`). The spawned-server test
   exits during `documentSymbol` and asserts a transport failure well before
   the request timeout (`LspProcessTest.kt:30-48`).

8. **Failure-path tests — Dissent (partial).** Null results, LSP error
   responses, malformed input, server death, concurrent document changes,
   concurrent close, unresponsive shutdown, symlink policy, nested git root,
   tracked excluded directories, and git timeout now have tests. Concurrent
   in-flight queries are still untested even though the review focus calls for
   them. Add a test with at least two simultaneous requests where one is
   delayed or fails and the other completes, asserting independent request ids,
   results, and cancellation.

### New findings

1. **A mutation can still complete after close claims the client**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:193-233`, `324-360`, `401-411`.
   - Problem: Document operations check `closeClaimed` and `lifecycle` inside
     their per-URI lock, but `close()` never takes a lock for URIs that are not
     yet in `documents`. A thread that has already passed both checks can be
     preempted before `server.textDocumentService.didOpen`; `close()` then
     claims, snapshots the empty map, performs shutdown/exit, clears state, and
     returns. The paused thread resumes, sends `didOpen` after shutdown/exit,
     and writes `documents[uri]`. LSP4J's notification path catches write
     failures, so the write can succeed silently and leave `openDocuments()`
     non-empty after close, with no matching `didClose`. The concurrent-close
     test does not exercise a mutation racing close.
   - Suggested fix: Serialize document mutations against close with one gate,
     for example a read/write lock where mutations take the read side and close
     takes the write side before snapshotting and tearing down, or a global
     mutation lock held across the check/notify/update and the close claim.
     Add a deterministic test using a blocking output stream so a `didOpen`
     can be paused after its checks while `close()` runs.

2. **`shutdown()` followed by `close()` skips document cleanup**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:296-312`, `324-340`.
   - Problem: `close()` only sends `didClose` for open documents when
     `lifecycle == INITIALIZED`. If a caller opens documents and calls the
     public `shutdown()` first, lifecycle is already `SHUTDOWN`, so `close()`
     skips the document loop, sends exit, and clears the maps without notifying
     the server about the open documents. The behavior depends on whether
     `shutdown()` or `close()` initiated teardown, and the new open-document
     test only covers direct `close()`.
   - Suggested fix: Close open documents before the first transition out of
     `INITIALIZED`, in a shared `beginShutdown` used by both `shutdown()` and
     `close()`, so the documents are closed while the server is still in the
     shutdown phase. Add a test that opens a document, calls `shutdown()`, then
     `close()`/`exit()`, and asserts the server received `didClose`.

3. **The git runner can return truncated output without failing**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/WorkspaceFiles.kt:85-87`, `90-103`.
   - Problem: After a successful `waitFor`, the stdout and stderr drain threads
     are joined for only one second each. If a drain thread is still alive, the
     runner returns whatever bytes it has and `gitPaths` parses them as a
     complete `ls-files` result. A git invocation whose descendant keeps stdout
     open can therefore silently omit workspace files instead of reporting a
     failure. The bounded timeout itself is correct; this is the success path
     after the process exits.
   - Suggested fix: If either drain thread does not finish, close the streams,
     destroy the process tree, and throw `WorkspaceException` rather than
     returning partial output, or enforce one deadline for process exit plus
     drains. Add a fake git script that leaves a background child holding
     stdout and assert a failure.

### Concur

- Findings 1-4, 6, and 7 are addressed without regressing the happy path.
- Null results, error responses, malformed input, server death, concurrent
  document changes, concurrent close, and unresponsive shutdown now have direct
  tests, and the timeout test verifies a later request still succeeds.
- The git timeout runner drains concurrently, is bounded, and reports a typed
  timeout; the default and explicit directory-exclusion cases are consistent
  across git and non-git roots.
- Close is atomic for concurrent close callers, uses the cleanup timeout, and
  clears documents, diagnostics, and messages on the direct cleanup path.
- No comments were added, and no live LSP installation is required by tests.

### Dissent

- Finding 5: close still races with an already-admitted mutation, and the
  `shutdown()`-first path skips document cleanup (New Findings 1 and 2).
- Finding 8: concurrent in-flight query behavior remains untested.
- New Findings 1-3: each is a correctness gap in close coordination or the git
  runner and should be fixed or explicitly deferred with a reason.

## Round 3

**Verdict:** Changes requested.

The Round 2 new findings are implemented and the new tests exercise the intended
paths: public document mutations are serialized against close, `shutdown()`
closes open documents, the git runner fails after a blocked drain, and
concurrent queries have independent ids and cancellation. The new read/write
gate introduces two lifecycle problems, however: an interrupt during close
cleanup can leave the write lock held, and server-pushed diagnostics/messages
bypass the gate and can repopulate state after close. Close can also wait for
the write lock behind in-flight queries or an in-progress shutdown, so the
cleanup timeout no longer bounds close under concurrency, and a drain failure in
the git repository-detection call is swallowed as a non-git fallback.

### Verification

- **Mutation after close — Concur for document methods.** `didOpen`,
  `didChange`, and `didClose` hold the gate read lock across the check, send,
  and map update, while `close()` holds the write lock through teardown
  (`LspClient.kt:202-260`, `384-417`).
  `documentMutationCannotCompleteAfterCloseClaimsClient` blocks a `didOpen`
  notification and verifies `close()` waits, then asserts `didOpen` < `didClose`
  < `shutdown` and empty open documents
  (`LspClientTest.kt:421-449`). See New Finding 2 for server-pushed state.

- **`shutdown()` followed by `close()` — Concur.** `beginShutdownLocked` removes
  and `didClose`s open documents before sending `shutdown`
  (`LspClient.kt:459-479`), and
  `shutdownClosesOpenDocumentsBeforeClose` asserts `didClose` precedes
  `shutdown` and that close still sends exit
  (`LspClientTest.kt:451-473`).

- **Blocked git drain — Concur for the `ls-files` path.** The runner joins both
  drains against one deadline, and if either remains alive it closes streams,
  attempts descendant cleanup, and throws `WorkspaceException` instead of
  returning partial output (`WorkspaceFiles.kt:85-96`). The new fake-git test
  leaves a background child holding stdout and asserts the failure
  (`WorkspaceFilesTest.kt:219-255`). See New Finding 4 for repository
  detection.

- **Concurrent in-flight queries — Concur.** Queries share the read lock,
  retain separate futures, and cancel only the timed-out future
  (`LspClient.kt:272-345`, `498-540`).
  `concurrentQueriesHaveIndependentIdsAndCancelOnlyTimedOutRequest` verifies two
  distinct request ids, an independent quick result, and cancellation of the
  document-symbol request only (`LspClientTest.kt:475-523`).

- **Lock ordering and reentrancy — mostly concur.** Mutations take the gate read
  lock before the per-URI lock; close takes only the gate write lock; there is
  no inverse acquisition and the reentrant `close()` from `initialize`'s catch
  does not deadlock because the write lock is reentrant. The interrupt and
  liveness problems are New Findings 1 and 3.

### New findings

1. **An interrupt in close cleanup can leave the write lock held**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:384-417`.
   - Problem: `write.unlock()` is the last statement of the `finally` block,
     after `process.waitFor(2, TimeUnit.SECONDS)` (line 411). If the closing
     thread is interrupted, `waitFor` throws `InterruptedException`, the
     remaining cleanup is abandoned, and `write.unlock()` is skipped. The
     client then permanently holds the write lock, so later `close()` calls and
     all gate-protected operations block forever. When `close()` was invoked
     reentrantly from `initialize`'s catch, the outer `initialize` finally
     releases only the outer hold and one reentrant hold remains. The existing
     close tests never interrupt the closer.
   - Suggested fix: Guarantee lock release in a nested `try/finally`; catch
     `InterruptedException` from `waitFor`, force-destroy the process, restore
     the interrupt flag, and still release the lock. Add a test that interrupts
     a close blocked in process wait and then asserts a second close returns.

2. **Server callbacks bypass the gate and can repopulate state after close**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:119-120`, `422-441`.
   - Problem: `closeClaimed` is now a plain non-volatile field, and
     `publishDiagnostics`, `showMessage`, and `logMessage` read it and write
     `diagnosticsByUri`/`messages` without taking the gate. These callbacks run
     on the LSP listener thread, so close's write lock and any visibility
     ordering do not apply. A callback can observe stale `false`, be preempted,
     and write after close has cleared the maps; even with visibility fixed, the
     check-then-write is not atomic with close's clearing. Post-close
     `diagnostics()`/`allDiagnostics()` can then expose notifications that
     arrived during teardown.
   - Suggested fix: Make the closed flag atomic/volatile and serialize the
     callback check-and-write with close's state clearing, for example with a
     dedicated state lock (take care not to hold the gate write lock while
     waiting on a response that the listener thread must process). Add a test
     where the server publishes diagnostics while close is in progress and
     assert the diagnostic map stays empty.

3. **Close can wait for the gate far beyond the cleanup timeout**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:384-390`, `272-345`, `353-366`.
   - Problem: `close()` calls the uninterruptible
     `gate.writeLock().lock()` with no deadline. Queries hold the read lock for
     the whole `await`, up to `requestTimeout` (default 30 seconds), and a
     concurrent `shutdown()` holds the write lock for its own request timeout.
     Close therefore waits for the full outstanding operation before it can even
     claim teardown; `cleanupTimeout` only bounds a shutdown that close itself
     starts. If a holder is blocked writing to an unread pipe, the wait can be
     indefinite, so a shutdown hook can hang.
   - Suggested fix: Bound close's lock acquisition with `tryLock` and a
     deadline, cancel or interrupt in-flight requests when the deadline expires,
     and force teardown. Alternatively, do not hold the gate across response
     waits; track in-flight requests so close can cancel them. Add a test with
     an ignored query in flight and assert close completes within its bound.

4. **Drain failure during git detection is treated as "not a repository"**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/WorkspaceFiles.kt:202-216`, `47-98`.
   - Problem: The new drain failure throws `WorkspaceException`, but
     `isGitRepository` catches every non-timeout `WorkspaceException` and
     returns false. A blocked drain during `rev-parse` therefore silently falls
     back to the plain walk, ignoring `.gitignore`, instead of failing as the
     new drain policy requires. The new drain test only exercises `ls-files`.
     In addition, the failure path enumerates `process.descendants()` after the
     process has exited; the child that kept the pipe open may already be
     reparented and is not reliably destroyed, so the failing invocation can
     leave a process behind.
   - Suggested fix: Introduce a distinct drain/transport failure (or rethrow it
     from `isGitRepository` while keeping fallback only for start failure),
     capture and destroy descendants before or while waiting for exit, and add a
     test for a blocked drain in `rev-parse` plus descendant termination.

### Concur

- The Round 2 mutation/close, shutdown-document, git-drain, and concurrent-query
  findings are covered by the gate, `beginShutdownLocked`, the drain deadline,
  and the new tests respectively.
- Document mutations take gate read then the per-URI lock; close takes gate
  write only, so there is no lock-order inversion.
- The reentrant close from `initialize`'s catch is safe on the normal path, and
  the new ordering tests cover concurrent initialize/close and shutdown/exit.
- The git runner now joins both drains against one deadline and fails rather
  than returning partial output on the `ls-files` path.
- Nullable results, capability rejection, failed start, transport failure, and
  the existing document/version behavior remain intact.
- No source comments were added, and the frontend changes are unchanged.

### Dissent

- New Finding 1: interrupted close can leak the write lock and block every later
  operation.
- New Finding 2: diagnostics/messages can be written after close because the
  callbacks are outside the gate and `closeClaimed` is not safely published.
- New Finding 3: close is not bounded by `cleanupTimeout` when another operation
  holds the gate, and an uninterruptible lock acquisition can hang teardown.
- New Finding 4: a drain failure during git detection is swallowed as a
  non-repository fallback, and the blocked descendant is not reliably killed.

## Round 4

**Verdict:** Approved.

The four Round 3 new findings are addressed and the new tests exercise the
intended paths. The remaining query read-lock-across-await pattern is a known
trade-off that is explicitly deferred to ticket 015; it does not make ticket 003
incorrect because close now bounds gate acquisition and force-tears down.

### Verification

- **Interrupted close — Concur.** `close()` claims the write lock with an
  interruptible `tryLock`, releases it before waiting for shutdown/process
  teardown, and the outer `finally` releases it if cleanup exits early
  (`LspClient.kt:425-467`). `destroyProcess` catches `InterruptedException`,
  force-destroys the process, and restores the interrupt flag
  (`LspClient.kt:572-591`). `interruptedCloseReleasesTheGateAndAllowsAnotherClose`
  interrupts a close blocked in process wait and verifies a second close
  completes (`LspProcessTest.kt:51-76`).

- **Bounded close and force teardown — Concur.** Close waits for the write lock
  only up to `cleanupTimeout`; on timeout it claims `closeClaimed` and calls
  `teardownResources(force = true)` without sending shutdown/exit
  (`LspClient.kt:425-440`). The normal path awaits shutdown only up to
  `cleanupTimeout`, and process exit is bounded in `destroyProcess`
  (`LspClient.kt:445-466`, `572-591`).
  `closeIsBoundedWhenAQueryHoldsTheGate` leaves a query holding the read gate,
  asserts close completes quickly, the query fails as transport, and later
  operations are rejected (`LspClientTest.kt:525-555`).

- **Callback state after close — Concur.** `closeClaimed` is an
  `AtomicBoolean`, and `publishDiagnostics`, `showMessage`, and `logMessage`
  check it and write under `stateLock`; teardown clears the maps under the same
  lock (`LspClient.kt:120-122`, `472-497`, `557-564`).
  `ignoresDiagnosticsPublishedDuringClose` publishes a diagnostic from the
  server's shutdown handler and asserts the diagnostic maps stay empty
  (`LspClientTest.kt:557-571`).

- **`rev-parse` drain failure and descendants — Concur.** `isGitRepository`
  falls back only on `WorkspaceStartException`, so a
  `WorkspaceDrainException` or timeout propagates and fails discovery
  (`WorkspaceFiles.kt:242-254`). The runner records descendants while the
  process is alive (`WorkspaceFiles.kt:111-138`) and force-destroys them in
  `destroyTree`. `failsWhenRevParseDrainRemainsBlocked` exercises the blocked
  `rev-parse` drain and asserts `WorkspaceDrainException`
  (`WorkspaceFilesTest.kt:257-290`).

- **Tests — Concur.** The interrupted close, bounded/force close, callback, and
  `rev-parse` drain paths all have direct tests, and the existing Round 1-3
  coverage for framing, lifecycle, nullable results, error responses,
  malformed input, process death, document versions, workspace discovery,
  symlinks, concurrency, and cancellation remains intact.

- **Ticket 015 deferral — Concur.** Queries still take the gate read lock and
  hold it across `await` (`LspClient.kt:309-382`). This is explicitly deferred
  to [015 — LSP lifecycle hardening](../../tasks/015-lsp-lifecycle-hardening.md)
  because ticket 003 now remains correct: close uses a bounded `tryLock` and
  force teardown instead of waiting for request timeouts. Ticket 015 will
  replace the read-lock-across-await with in-flight tracking/cancellation so
  close can stop queries gracefully rather than aborting them by tearing down
  the transport. The deferral is named here and recorded in the 015 scope and
  ticket index.

### Concur

- Interrupted close cannot leak the gate; the lock is released before waits and
  guarded by a dedicated unlock `finally`.
- Close is bounded for gate acquisition, shutdown response, and process wait,
  and the forced path does not send shutdown/exit after claiming close.
- Callback diagnostics/messages cannot repopulate state after close.
- A `rev-parse` drain failure fails discovery, and blocked descendants are
  recorded and force-destroyed.
- No new correctness problems were found in the lock, shutdown path, or git
  runner changes.
- No comments were added, and the frontend remains unchanged.

### Dissent

- None.
- The only remaining item is the query read-lock-across-await hardening, which
  is explicitly deferred to ticket 015 with the reason above.
