## Round 1

**Verdict:** Changes requested.

The client is a real LSP4J/stdio client rather than a parser: it negotiates capabilities, sends the expected document notifications, exposes the five query methods, maps transport failures to typed exceptions, and discovers files with gitignore-aware path selection. The findings below are LSP-contract and lifecycle gaps that later expansion work will inherit, plus the test gaps that let those gaps pass.

### Findings

1. **Server-initiated semantic-token cancellation is advertised but not handled**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:398-408`, `358-383`.
   - Problem: The client sets `serverCancelSupport = true`, which tells the server it may cancel a semantic-token request and return a `ServerCancelled` error because the client will retrigger it. `await` maps every `ResponseErrorException` to `LspResponseException` and never retries. A server that takes the client at its word can therefore make `semanticTokensFull` fail permanently instead of being retried, which is the opposite of the advertised capability.
   - Suggested fix: Set `serverCancelSupport = false` until retry exists, or catch the `ServerCancelled` response for semantic-token requests and retrigger with a bounded retry policy. Add a fake-server test that returns `ServerCancelled` and asserts either a retry or a deterministic typed failure.

2. **Lifecycle does not reject requests after shutdown**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:268-277`, `335-338`.
   - Problem: `shutdown()` sends the shutdown request but leaves `initializedState` true and `closed` false. `requireInitialized()` therefore accepts every query after shutdown, and `documentSymbol`, `references`, `definition`, `semanticTokensFull`, and `workspaceSymbol` can be sent to a server that has been told to shut down. LSP forbids further requests after shutdown except `exit`; the client currently turns that into server-dependent errors or delays instead of a local lifecycle error. `exit()` is also callable without a preceding shutdown.
   - Suggested fix: Track a shutdown state, reject query and document mutations once shutdown has been requested, allow only `exit`/`close`, and require shutdown before exit in the public lifecycle. Add tests for query-after-shutdown and exit-before-shutdown.

3. **Nullable LSP query results are dereferenced**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:217-260`.
   - Problem: LSP permits a null result for `textDocument/documentSymbol`, `textDocument/references`, `textDocument/definition`, `textDocument/semanticTokens/full`, and `workspace/symbol`. `await` returns the raw result; `references` calls `.toList()` on it, `definition` and `workspaceSymbol` call `.isLeft`, and `documentSymbol`/`semanticTokensFull` return it through non-null Kotlin types. A legal null response becomes a raw `NullPointerException` or an invalid null return instead of an empty result or a typed `LspException`.
   - Suggested fix: Normalize each nullable result to an empty list or an explicit absent value for semantic tokens, and keep genuine failures mapped to `LspException`. Add fake-server tests that return null for each query method.

4. **The semantic-token legend is not part of the client contract**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:153-158`, `246-266`.
   - Problem: `semanticTokensFull` returns raw integer arrays, while the meaning of each token's type/modifier indices lives in the server's `SemanticTokensLegend` from the initialize result. The client stores capabilities privately and exposes only `supportsSemanticTokensFull()`, so a later expansion component that receives an `LspClient` cannot decode the tokens without separately retaining the raw `InitializeResult`. The query method alone does not carry enough information for the architecture's "identify reference tokens" use.
   - Suggested fix: Retain the negotiated legend on successful initialize and expose it (or decode tokens against it) through the client API. Add a test asserting the exposed legend matches the fake server's advertised legend.

5. **Git discovery timeouts cannot bound the blocking read**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/WorkspaceFiles.kt:76-86`, `106-111`.
   - Problem: Both `isGitRepository` and `gitPaths` drain stdout completely with `readText()`/`readAllBytes()` before calling `waitFor(timeout)`. If git stalls while keeping stdout open, the read blocks forever and neither the 10-second nor the 30-second timeout is reached, so discovery can hang indefinitely instead of falling back or failing. The timeout values are therefore not a real bound.
   - Suggested fix: Bound the whole operation, for example by draining on a separate thread with a bounded join, or by reading from a redirected file while `waitFor(timeout)` runs. Destroy the process and surface an explicit workspace error on timeout. Add a test with an injectable command runner or a fake `git` executable that sleeps with stdout open.

6. **Close cleanup can block for the request timeout and repeat shutdown**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:283-311`, `346-356`.
   - Problem: `close()` calls `requestShutdown()`, which waits the full configured request timeout. If shutdown times out, `shutdownSent` remains false, so a later `close()` retries the same wait before the process is destroyed; a hung server can delay cleanup by a multiple of the request timeout. The initial `closed` check is also released before shutdown/exit, so concurrent callers can both proceed. Process cleanup is therefore not bounded independently of request latency.
   - Suggested fix: Claim the close transition atomically at entry, use a dedicated short cleanup timeout for shutdown, mark the shutdown attempt before awaiting so it is not repeated, and always destroy the process on timeout. Add a test with a server that never answers shutdown and assert close completes within a small bound.

7. **Query contract tests do not assert request parameters or error paths**

   - Location: `analysis-engine/lsp/src/test/kotlin/dev/codenb/lsp/LspClientTest.kt:55-71`, `107-124`; `analysis-engine/lsp/src/test/kotlin/dev/codenb/lsp/FakeLspServer.kt:175-216`; `analysis-engine/lsp/src/test/kotlin/dev/codenb/lsp/WorkspaceFilesTest.kt:11-78`.
   - Problem: The fake server returns fixed payloads and records only document state, so the tests do not assert the request URI, position, `includeDeclaration`, or workspace query sent by the client; a client that swapped arguments would still pass. There is no test for an LSP error response, so `LspResponseException` is unexercised, and the timeout test only checks elapsed time rather than that the client remains usable or that cancellation is visible. There is also no query-after-shutdown test, no `ServerCancelled` test, no null-result test, and no workspace test for a root below a git repository root.
   - Suggested fix: Record and assert the parameters for every query method, add fake-server modes for response errors, null results, and `ServerCancelled`, assert the typed exception and post-timeout usability, and add a workspace test whose root is a subdirectory of a git repository.

### Concur

- `initialize` sends root, workspace folders, client info, and negotiated capabilities; `initialized` is sent once after the initialize result; `close` sends shutdown then exit and destroys the process, and the process test verifies the pid dies.
- The semantic-token capability gate produces a typed `LspUnsupportedCapabilityException` when the provider is absent and handles both boolean and registration-option forms of `full`.
- The query surface matches the architecture: `documentSymbol`, `references`, `definition`, `semanticTokens/full`, and `workspace/symbol` all exist with the expected LSP4J result shapes.
- In the single-threaded path, document open/change/close and version-increment tracking are coherent, and full-text change events are valid for the server's full-sync advertisement.
- Workspace discovery uses `git ls-files --cached --others --exclude-standard`, applies include/exclude after gitignore filtering, walks the tree outside git, rejects symlinks by default, and guards against relative-path escape.
- The Kotlin frontend is small and data-driven: one id/language id, `.kt`/`.kts`, the default `kotlin-lsp --stdio` command, namespace grouping, and root markers. Its source has no LSP imports and does not interpret symbols.
- There is no parser or fallback analysis path in the reviewed code; the client and workspace layer only carry LSP and file-system facts.
- Tests use an in-process fake server and a spawned fake process, so no live LSP installation is required.
- The authored source, tests, and build file contain no comments.

### Dissent

- Finding 1: the advertised server-cancel capability must have matching retry behavior or be disabled.
- Finding 2: requests must be rejected once shutdown has been requested.
- Finding 3: nullable LSP results must be normalized rather than dereferenced.
- Finding 4: the semantic-token legend must be available through the client contract for later decoding.
- Finding 5: git discovery timeouts must bound the operation, not run after an unbounded read.
- Finding 6: close must be atomic and its cleanup wait must be bounded and non-repeating.
- Finding 7: the query contract needs parameter, error, cancellation-recovery, and git-subdirectory tests.

## Round 2

**Verdict:** Changes requested. All seven Round 1 findings are addressed. One new LSP-lifecycle ordering problem remains: state is published before the corresponding notification is written, so a concurrent `exit()` can precede the `shutdown` request.

### Finding disposition

1. **Server-cancel capability — Concur (addressed).** `serverCancelSupport` is now `false` (`LspClient.kt:529`), and the capability test asserts it (`LspClientTest.kt:51`). No retry is advertised, so no `ServerCancelled` retry path is required.

2. **Shutdown lifecycle — Concur (addressed).** The `Lifecycle` enum, `requireInitialized`, and `requireDocumentMutation` reject queries and document mutations after shutdown, and `exit()` requires the shutdown state (`LspClient.kt:96-102`, `296-318`, `401-409`). The sequential rejection and exit-before-shutdown cases are tested (`LspClientTest.kt:221-250`). The remaining concurrent ordering edge is covered as a new finding below.

3. **Nullable query results — Concur (addressed).** `documentSymbol` and `references` normalize to empty lists, `definition` and `workspaceSymbol` guard the nullable result before reading `Either`, and `semanticTokensFull` returns `SemanticTokens?` (`LspClient.kt:245-288`). The null-result test covers all five methods (`LspClientTest.kt:173-193`).

4. **Semantic-token legend — Concur (addressed).** The legend is retained on initialize and exposed as `semanticTokensLegend` (`LspClient.kt:130-132`, `176-177`), and the test compares the exposed token types and modifiers with the fake server's legend (`LspClientTest.kt:54-55`).

5. **Git discovery timeout — Concur (addressed).** `ProcessGitCommandRunner` drains stdout/stderr on separate threads, waits with the supplied timeout, destroys the process on timeout, and throws `WorkspaceTimeoutException` (`WorkspaceFiles.kt:47-103`). `discover` threads the timeout through both git calls and rethrows timeouts instead of falling back (`WorkspaceFiles.kt:109-143`, `182-218`). The stalled-git test bounds elapsed time and expects the typed timeout (`WorkspaceFilesTest.kt:182-217`).

6. **Close atomicity and cleanup — Concur (addressed).** `close()` claims the transition with `AtomicBoolean`, sends `didClose` for open documents, uses `cleanupTimeout`, marks the shutdown attempt before awaiting so it is not repeated, sends exit, and clears document/diagnostic/message state (`LspClient.kt:324-360`, `413-451`). Tests cover concurrent close, open-document cleanup, and an unresponsive shutdown (`LspClientTest.kt:252-324`).

7. **Query contract tests — Concur (addressed).** The tests assert the URI, position, `includeDeclaration`, and workspace query for every query method (`LspClientTest.kt:63-91`), and add null results, response-error mapping, malformed framing, server death while pending, query-after-shutdown, exit-before-shutdown, concurrent document changes, and git-subdirectory discovery (`LspClientTest.kt:173-381`; `WorkspaceFilesTest.kt:51-72`). The timeout test also proves the client remains usable afterward.

### New findings

1. **Lifecycle state is published before the corresponding LSP notification is written**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:296-318`, `432-461`, `172-180`.
   - Problem: `shutdown()` sets `lifecycle = Lifecycle.SHUTDOWN` before `requestShutdownOnce` calls `server.shutdown()`. A concurrent `exit()` can observe `SHUTDOWN` and call `server.exit()` before the shutdown request is written, violating the required shutdown-then-exit order. The same state-before-notification pattern exists in `initialize`: `Lifecycle.INITIALIZED` becomes visible before `initializedNotification()` sends `initialized`, so a concurrent query or document operation can reach the server before the initialized notification. The tests exercise only sequential shutdown/exit and do not cover these interleavings.
   - Suggested fix: Publish `SHUTDOWN` only after the shutdown request has been written, for example with a distinct `SHUTTING_DOWN` state or a `shutdownRequestSent` latch that `exit()` waits on. Publish `INITIALIZED` only after `initialized` has been sent, or serialize the transition and notification under the lifecycle lock. Add concurrent shutdown/exit and initialize/close tests.

### Concur

- All seven Round 1 findings are addressed without weakening the LSP-only boundary, the query surface, or the workspace discovery contract.
- The client still contains no parser or fallback analysis path; the Kotlin frontend remains data-only and unchanged.
- The fixes introduce no comments, and the added tests use the fake server or a fake process rather than a live LSP.

### Dissent

- New finding 1: a concurrent `exit()` can still be sent before the shutdown request, so lifecycle ordering must be tied to the notification being written rather than only to the published state.

## Round 3

**Verdict:** Changes requested. The Round 2 ordering fixes are correct and are exercised by the new interleaving tests. The lifecycle gate introduces two new cleanup problems: cleanup can wait for the full request timeout behind in-flight work, and the write lock can be left held if close's cleanup is interrupted.

### Finding disposition

- **Round 2 lifecycle publication — Concur (addressed).** `initialize` sends `server.initialized` before setting `Lifecycle.INITIALIZED` (`LspClient.kt:175-177`), and `beginShutdownLocked` calls `server.shutdown()` before setting `Lifecycle.SHUTDOWN` (`LspClient.kt:459-467`). The fair write gate serializes `initialize`, `shutdown`, `exit`, and `close`, and the read gate keeps readers out until initialization has finished.
- **Concurrent shutdown/exit ordering — Concur (addressed).** A concurrent `exit()` waits for the write gate held by `shutdown()`, so it cannot precede the shutdown request (`LspClient.kt:353-378`).
- **Concurrent interleaving tests — Concur (addressed).** `concurrentInitializeAndClosePreserveOrdering` blocks the `initialized` write and asserts `initialize < initialized < shutdown < exit`; `concurrentShutdownAndExitPreserveOrdering` blocks the `shutdown` write and asserts `shutdown < exit`; `documentMutationCannotCompleteAfterCloseClaimsClient` forces `didOpen < didClose < shutdown` (`LspClientTest.kt:366-449`). The blocking output stream makes these interleavings deterministic rather than merely timing-dependent.

### New findings

1. **The lifecycle gate is held across remote waits, so close can be delayed by the full request timeout**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:272-345`, `353-366`, `384-417`, `459-467`.
   - Problem: Every query holds the shared read lock while it awaits the server response, and `shutdown()` holds the exclusive write lock while it awaits the shutdown response. `close()` needs the write lock, so an in-flight query or a concurrent unresponsive `shutdown()` blocks cleanup for up to `requestTimeout` (default 30 seconds), bypassing the `cleanupTimeout` bound that Round 2 introduced. `initialize` has the same issue while awaiting the initialize response. The fair gate also blocks new queries behind the waiting writer. The current cleanup tests cover a close that starts its own shutdown, but not a close that is waiting behind a pending query, initialization, or shutdown.
   - Suggested fix: Do not hold the read lock across `await`; use a short-lived lifecycle check plus an in-flight-operation count or latch. Make `close()` cancel outstanding requests and bound its wait for the gate, and keep `exit()` ordered with a shutdown-request latch rather than holding the write lock through the shutdown await. Add tests that leave `documentSymbol`, `initialize`, or `shutdown` pending and assert `close()` completes within `cleanupTimeout` while preserving the required call order.

2. **Close can leave the write lock held if cleanup is interrupted**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:384-417`, especially `399-415`.
   - Problem: `close()` acquires the write lock, then performs cleanup inside `finally`, and calls `write.unlock()` as the last statement of that block. If `process.waitFor` throws `InterruptedException` (for example, close runs on an interrupted thread, or `executorService.shutdownNow()` interrupts the calling thread when close is invoked from an LSP executor task), the unlock is skipped. Because `initialize` calls `close()` reentrantly from its catch path, an exception there can leave the write lock held after `initialize` returns, blocking later queries, shutdown, and close.
   - Suggested fix: Wrap the entire close body in an outer `try/finally` whose only job is `write.unlock()`, and make the cleanup tolerate interruption without skipping the release. Add a test that invokes close from an interrupted thread (or injects a failing cleanup step) and verifies another thread can still acquire the lifecycle gate and close/shutdown.

### Concur

- The Round 2 ordering defect is resolved: state publication now follows the corresponding notification write, and `exit` is serialized behind `shutdown`.
- The new interleaving tests exercise the actual races with a blocking output stream rather than relying on sleeps alone.
- The query surface, nullable-result handling, semantic-token legend exposure, workspace discovery, and Kotlin frontend remain unchanged by this round.
- There is still no parser or fallback analysis path, and the reviewed source contains no comments.

### Dissent

- New finding 1: cleanup must not wait for in-flight request timeouts; `close()` and `shutdown()` must remain bounded under the gate.
- New finding 2: the lifecycle gate must be released even when close's cleanup is interrupted.

## Round 4

**Verdict:** Changes requested. The Round 3 lock fixes are present and tested, and the remaining query-read-gate work is explicitly deferred to ticket 015, but the initialize refactor introduced a new lifecycle regression: a duplicate or concurrent initialize tears down a live client.

### Finding disposition

- **Close releases the write lock on interruption — Concur (addressed).** `tryLock` catches `InterruptedException`, reinterrupts, and continues with the forced teardown path; the normal path releases the gate before awaiting or tearing down, and the outer `finally` releases it if a failure occurs while it is held. `destroyProcess` catches `InterruptedException`, force-kills the process and descendants, and restores the interrupt flag. `LspProcessTest.interruptedCloseReleasesTheGateAndAllowsAnotherClose` covers an interrupted close and a subsequent close.
- **Close is bounded and force-tears down when the gate cannot be acquired — Concur (addressed).** `close()` waits at most `cleanupTimeout` for the write gate, and on failure claims close and calls `teardownResources(force = true)`. `LspClientTest.closeIsBoundedWhenAQueryHoldsTheGate` leaves `documentSymbol` holding the read gate, asserts close completes within two seconds, and verifies the pending query fails with `LspTransportException` while later queries are rejected.
- **Initialization/shutdown ordering after removing gate-held awaits — Concur (addressed).** `initialize` releases the gate before awaiting the initialize response, reacquires it to write `initialized`, and only then publishes `INITIALIZED`; `shutdown` writes `server.shutdown()` before publishing `SHUTDOWN` and releases the gate before awaiting the response. The existing interleaving tests still assert `initialize < initialized < shutdown < exit` and `shutdown < exit`, so exit cannot precede the shutdown request.
- **Query-read-gate deferral — Concur with deferral.** The query methods still hold the read gate across `await` (`LspClient.kt:309-382`), which is the remaining part of Round 3 finding 1. This is explicitly deferred to ticket 015 (`docs/tasks/015-lsp-lifecycle-hardening.md:16-19`), whose scope is to replace the read lock with an in-flight count or latch and cancel outstanding requests. The reason for deferral is that close now has a bounded gate wait and a force-teardown path, so cleanup no longer hangs behind a pending query; the deferred work is the graceful cancellation and read-availability improvement, including the removal of request-latency waits from the gate.

### New findings

1. **A duplicate or concurrent initialize closes a live client**

   - Location: `analysis-engine/lsp/src/main/kotlin/dev/codenb/lsp/LspClient.kt:147-180`.
   - Problem: The `check(lifecycle == Lifecycle.NEW)` validation sits inside the `try` whose `catch` calls `close()`. A second `initialize()` on an already initialized client, or a concurrent `initialize()` while the first is awaiting the initialize response, throws `IllegalStateException` and then closes the live client through shutdown, exit, and process teardown. The earlier implementation rejected the duplicate call without closing it. No test covers duplicate or concurrent initialization.
   - Suggested fix: Validate `closeClaimed` and `lifecycle == Lifecycle.NEW` before entering the catch that tears down, or track whether this call actually transitioned the client to `INITIALIZING` and call `close()` only for failures after that transition. Add tests that a duplicate initialize throws without closing a usable client and that a concurrent initialize does not abort the winning initialization.

### Concur

- The Round 3 gate-release and bounded-close defects are resolved, including the interruption path and the forced teardown when the gate is held by a pending query.
- Initialization, shutdown, and exit remain correctly ordered after the gate-held awaits were removed.
- The remaining read-gate-across-await work is named and scoped in ticket 015 with a concrete reason; deferral is appropriate for ticket 003.
- The query surface, nullable-result handling, semantic-token legend, workspace discovery, and Kotlin frontend are unchanged, and there is no parser or fallback path or authored comment.

### Dissent

- New finding 1: a duplicate or concurrent initialize must reject the call without tearing down the client that is already initialized or initializing.

## Round 5

**Verdict:** Approved. The Round 4 duplicate/concurrent initialize regression is fixed and covered by tests. No new lifecycle problems were found. The only remaining item is the query-read-gate-across-await work explicitly deferred to ticket 015, and I concur with that deferral.

### Finding disposition

- **Initialize validates lifecycle before the cleanup catch — Concur (addressed).** The `closeClaimed` and `Lifecycle.NEW` checks run before `transitioned` is set, and the catch calls `close()` only when this call actually transitioned the client to `INITIALIZING` (`LspClient.kt:147-184`). State validation failures therefore reject without teardown.
- **Duplicate initialize rejects without closing — Concur (addressed).** `duplicateInitializeDoesNotCloseAnInitializedClient` asserts the second call throws `IllegalStateException`, then verifies `workspaceSymbol` and `didOpen` still work on the original client (`LspClientTest.kt:104-128`).
- **Concurrent initialize does not abort the winner — Concur (addressed).** `concurrentInitializeDoesNotAbortTheWinningInitialization` blocks the fake server inside `initialize`, calls a second initialize while the first is awaiting, asserts rejection, releases the blocker, and verifies the winner returns and the client remains usable (`LspClientTest.kt:130-158`). The fake server's `initializeBlocker` holds the winning request without touching the client's lifecycle state.
- **Client remains usable after rejection — Concur (addressed).** Both tests exercise a post-rejection query, and the duplicate test also opens a document, so the rejection path is verified against a live client rather than only asserting the exception.
- **Query-read-gate deferral — Concur.** The remaining query methods holding the read gate across `await` are explicitly scoped in ticket 015 with the reason stated in Round 4; no further action is required for ticket 003.

### New findings

None. The `transitioned` guard resolves the Round 4 regression without changing the initialization, shutdown, exit, document, or cleanup paths, and no new lifecycle problem is introduced.

### Concur

- Duplicate and concurrent initialize calls now reject without closing or aborting the existing client.
- The winning initialization remains ordered: initialization response, `initialized` notification, then `INITIALIZED` publication.
- The Round 3 close/interruption fixes and the Round 2 ordering fixes remain intact.
- The query surface, nullable-result handling, semantic-token legend, workspace discovery, and Kotlin frontend are unchanged; there is no parser or fallback path and no authored comment.

### Dissent

- None.
