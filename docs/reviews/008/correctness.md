## Round 1

**Verdict:** Changes requested.

The daemon skeleton is sound: a real Unix socket with one reader and one writer per
client, NDJSON framing that survives split reads and multiple objects per write,
JSON-RPC ids preserved as strings, numbers, null, and notifications left
unanswered, plus parse-error recovery. The sequential stale/live/non-socket
checks and the protocol shutdown path are covered by real-socket tests, and the
dot writer is deterministic, undirected, escaped, and clustered. The problems are
in the analysis state behind the daemon: the configured workspace filters never
reach expansion or seed validation, existing socket directories are trusted
without the required `0700`, FIFOs are mistaken for sockets and deleted, `open`
bypasses the expansion cache, cached file/group expansions can go stale, and the
real service has no seed/expansion/serialization tests.

### Findings

1. **Configured workspace filters do not reach the analysis path**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:274-279` and `:299`; `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:104-108`; seed resolution at `RealGraphService.kt:187-223`.
   - Problem: `ensureClient` calls `WorkspaceFiles.discover` with `config.workspace.include`,
     `config.workspace.exclude`, and `config.graph.followSymlinks`, then discards the
     result. `NeighbourExpander.Context` independently discovers files with
     `frontend.include`/`frontend.exclude` and `followSymlinks = false`, so the YAML
     workspace settings have no effect on what is analyzed. `resolveFileSeed` also
     opens and queries `documentSymbol` for a seed file before checking any workspace
     membership, so a seed in a gitignored, excluded, or non-included file is still
     read and sent to the LSP. This contradicts `docs/architecture.md:98` ("A file
     outside the set is never opened or queried") and makes `workspace.exclude` a
     no-op. A `followSymlinks: true` setting is only seen by the startup validation
     call, not by expansion.
   - Suggested fix: Resolve the workspace file set once (or pass
     `include`/`exclude`/`followSymlinks`) into `NeighbourExpander.Context` so
     seed resolution, containment, and expansion all use the same set. Validate the
     seed against that set before `didOpen`, and return `WORKSPACE_ERROR` rather than
     a late `INVALID_REQUEST`.
   - Reproduction: create a temp workspace containing `Excluded.kt`, configure
     `workspace: { exclude: ["Excluded.kt"] }`, and call `graph/open` with
     `{"file": "Excluded.kt", "qualifiedName": "..."}`. The file is opened and
     expanded today; `NeighbourExpander` uses the frontend's default include and
     ignores the exclude.

2. **An existing socket directory is trusted without verifying or restoring `0700`**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/SocketPaths.kt:100-113` (existing parent returns immediately) and `:84-86` (only the socket is chmodded).
   - Problem: When `/tmp/codenb-<uid>` (or an `XDG_RUNTIME_DIR` child) already
     exists, `createParent` returns without checking its owner or mode and without
     calling `setPermissions`. A directory left at `0755` or `0777` by an earlier
     run, or pre-created by another user, is reused, so the required directory
     `0700` invariant is only true on first creation. With a permissive parent,
     another user can create the socket path; `isLive` treats an `EACCES` connect
     as "dead", and `prepare` then deletes that live path (`SocketPaths.kt:78-81`).
   - Suggested fix: When the parent exists, reject a symlink/non-directory, verify
     it is owned by the current uid (where the platform exposes `unix:uid`), and set
     it to `0700`; fail startup if that cannot be enforced. Distinguish
     `ConnectException`/`NoSuchFileException` (stale) from `AccessDeniedException`
     (not ours to remove) instead of treating every `IOException` as stale.
   - Reproduction: `mkdir -m 0777 /tmp/codenb-$(id -u)`, start the daemon with the
     matching root, and stat the directory; it stays `0777`.

3. **`isOther` is not a socket test, so FIFOs and device nodes are deleted**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/SocketPaths.kt:73-81`.
   - Problem: `BasicFileAttributes.isOther` is true for every non-regular,
     non-directory, non-symlink file, including FIFOs and device nodes. The
     `isSymbolicLink || isDirectory || isRegularFile || !isOther` condition accepts
     a FIFO as if it were a Unix socket; `isLive` then fails to connect and
     `Files.delete(socket)` removes it. This violates the requirement that a
     non-socket path is never clobbered. The existing test only covers a regular
     file (`EngineSocketServerTest.kt:256-268`).
   - Suggested fix: Read the Unix file type (for example `unix:mode`) and delete
     only when the type is `S_IFSOCK`. Refuse to delete when the type is unknown or
     is anything else.
   - Reproduction: `mkfifo /tmp/engine.sock`, then start the daemon with
     `--socket /tmp/engine.sock`; the FIFO is deleted and replaced by a socket
     today. The same path should fail with `IOException` and leave the FIFO.

4. **`graph/open` computes through the LSP before checking the expansion cache**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:84-90`.
   - Problem: In the entity branch, `computeEntityExpansion(ensureClient(), node,
     currentRevision)` is evaluated eagerly, then passed to `store.expand`. For an
     already-open graph, `store.expand` checks the `(graph, node, revision)` cache
     first, so the computed result may be thrown away. A reopen by `nodeId` (which
     does not need discovery) therefore still issues `documentSymbol`,
     `references`, and semantic-token calls, and fails if the LSP has gone away
     even though the expansion is cached.
   - Suggested fix: When `currentRevision != null`, put the computation inside the
     `store.expand` lambda, as `expand` already does at `RealGraphService.kt:121-126`.
     Only compute eagerly on the first open, where the revision is not known yet.
   - Reproduction: open a seed by `nodeId`, kill the LSP process, then call
     `graph/open` with the same `nodeId`. The cache has the expansion but the
     request fails with `LSP_FAILURE` today. A fake client factory that throws on
     any second query reproduces this deterministically.

5. **Cached FILE/GROUP expansions are snapshots and can go stale as the graph grows**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:119-126` and `:311-324`; cache behavior in `analysis-engine/core/src/main/kotlin/dev/codenb/core/GraphStore.kt:36-47`.
   - Problem: `storedExpansion` builds an expansion from the current snapshot and
     `GraphStore.expand` caches it under the graph-wide revision. Later entity
     expansions can add nodes and containment edges touching that file or group,
     but a repeated `node/expand` hits the old cache and returns the old subset.
     The response then disagrees with `node/get`, which always uses the current
     snapshot. This breaks "node/expand returns the requested node, its neighbours,
     and the connecting edges" and makes cache results depend on expansion order.
   - Suggested fix: Do not cache snapshot-derived FILE/GROUP expansions; compute
     them from the current snapshot and merge directly, or include a graph
     generation counter in the cache key and invalidate derived entries when a merge
     adds nodes or edges. Add a test that expands a file, expands an entity that
     adds a new entity in that file, then expands the file again and expects the new
     entity.
   - Reproduction: file `F.kt` contains `A`, `B`, and `D`; `A` references `B` and
     `B` references `D`. Open at `A` (store gets `A`, `B`, `F`), expand `F` (cached:
     `A`, `B`, group), expand `B` (adds `D`), then expand `F` again. `node/expand(F)`
     still omits `D`, while `node/get(F)` includes it.

6. **`graph/dot` succeeds before any graph is opened**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:151-164`; compare `:109-112` in `expand` and `:130-149` in `node`.
   - Problem: `graphRevision` is the service's "graph is open" marker, and
     `expand` checks it, but `dot` and `node` do not. On a fresh service
     `graph/dot` returns a successful empty Graphviz document, and `node/get`
     reports `UNKNOWN_NODE` instead of `INVALID_GRAPH`. A renderer cannot
     distinguish "no graph has been opened" from "the graph is empty".
   - Suggested fix: Require `graphRevision != null` for `dot` (and `node`, for
     consistency) and return `INVALID_GRAPH` when it is absent, or explicitly define
     and test the empty-graph behavior. Add tests for both methods before `open`.
   - Reproduction: construct a `RealGraphService` and call
     `dot(graph.info().graphId)` before `open`; it returns
     `graph "code-neighbours" { ... }` with no error.

7. **An ambiguous `file#qualifiedName` seed silently picks one entity**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:232-243`; identity shape at `analysis-engine/core/src/main/kotlin/dev/codenb/core/EntityIdentity.kt:45-53` and constructor naming at `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/KotlinEntityDiscovery.kt:233`.
   - Problem: `minByOrNull { it.id.value }` chooses the lexicographically smallest
     node id among every entity with the requested qualified name. Overloaded
     functions and constructors share a qualified name (all constructors are
     `Class.constructor`), as can a class and a function with the same name; the id
     contains the signature fingerprint, so the selected entity is arbitrary from
     the caller's perspective. The model requires overloads not to collapse, and the
     caller has no way to tell which overload was opened.
   - Suggested fix: Treat multiple matches as an error that lists the candidate ids
     or fingerprints, or extend the selector to carry a fingerprint/kind. Add a test
     with two constructors and with a class/function name collision that asserts
     the error or the documented selection rule.
   - Reproduction: a file with `class Foo(val a: Int)` and
     `class Foo(val a: String)`; `graph/open` with
     `file#pkg.Foo.constructor` opens one constructor with no indication that the
     other exists.

8. **The real service is untested beyond info, unknown-graph, and close**

   - Location: `analysis-engine/server/src/test/kotlin/dev/codenb/server/RealGraphServiceTest.kt:13-71`; injected factory at `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:42-44`.
   - Problem: `clientFactory` is never used by a test. There is no test for
     `open` with a real seed, root-relative versus absolute seed paths, workspace
     containment, overload selection, graph revision handling, reopening, FILE/GROUP
     expansion, cache behavior, or the required serialized LSP access. The daemon
     tests use `FakeGraphService`, so they cannot catch any of Findings 1, 4, 5, or 7.
     The config tests only parse YAML and never show that a config changes analysis.
   - Suggested fix: Add focused tests with a fake LSP session/client factory and a
     temporary Kotlin workspace: assert qualified-name resolution, excluded files,
     reopen-by-node-id not querying the LSP again, revision mismatch, FILE/GROUP
     neighbours, and that two concurrent `open`/`expand` calls never overlap LSP
     calls (a counter or latch in the fake).
   - Reproduction: the current tests pass with `clientFactory` completely unused;
     removing the field would not change any test outcome.

9. **Signal shutdown can leave the socket behind**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/Main.kt:42-48`; `analysis-engine/server/src/main/kotlin/dev/codenb/server/EngineSocketServer.kt:66-73` and `:135-158`.
   - Problem: the JVM shutdown hook only calls `server.close()`, which sets the flag
     and closes the listener. Cleanup — joining clients (up to the 2 s grace),
     closing the LSP, and deleting the socket — runs on the main thread after
     `acceptLoop` returns. The JVM halts after shutdown hooks finish and does not
     wait for that main-thread cleanup, so a SIGTERM/SIGINT can leave the socket
     path in place. The protocol `shutdown` test passes because it runs cleanup
     deterministically.
   - Suggested fix: Make the shutdown hook call `close()` and then
     `awaitTermination(...)` with a bound, or delete the socket in a hook-owned
     finally after waiting. Add a process-level test that starts the daemon, sends
     SIGTERM, and asserts the path is gone.
   - Reproduction: start the daemon with one idle connected client, send SIGTERM,
     and check the socket path; cleanup is waiting in the 2 s client join when the
     JVM halts, so the path remains.

10. **Unknown YAML keys are silently ignored**

    - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/EngineConfig.kt:76-109` and the mapping helpers at `:112-160`.
    - Problem: Only known keys are read; a typo such as `workspace: { exlude: [...] }`
      or `graph: { followSymlink: true }` leaves the default in place with no error
      or warning. The daemon then analyzes a different file set than the config
      appears to request. The config tests cover valid keys and malformed YAML but
      not this case.
    - Suggested fix: Reject unknown keys at the top level and in `lsp`, `workspace`,
      and `graph` (or emit a documented warning), and add tests for a misspelled
      section key and a misspelled property key.

11. **No socket path length guard**

    - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/SocketPaths.kt:31-49`; test at `analysis-engine/server/src/test/kotlin/dev/codenb/server/SocketPathsTest.kt:17-18`.
    - Problem: The path is built from `XDG_RUNTIME_DIR` without checking the Unix
      `sun_path` limit. A long runtime directory produces a path that
      `UnixDomainSocketAddress.of` rejects with a generic
      `IllegalArgumentException` after `prepare` has already removed a stale socket;
      there is no `ConfigException` naming the path and no test at the actual limit.
      The existing test only asserts a short path is under 100 bytes.
    - Suggested fix: Compute the UTF-8 byte length before touching the filesystem and
      throw `ConfigException` if it exceeds the platform limit (107 bytes for
      Linux's 108-byte `sun_path`, including the terminator). Add a test with a
      deliberately long runtime directory.

### Concur

- The NDJSON framing is correct: one reader/writer per client
  (`EngineSocketServer.kt:99-133`), split lines are buffered, multiple objects in
  one write are processed in order, and `GraphJson.encode` plus `newLine()` keeps
  one object per line with JSON escaping preventing embedded newlines.
- JSON-RPC ids are preserved from the original `JsonElement`; strings, numbers,
  zero, empty strings, and JSON `null` pass `validId`, while invalid ids produce a
  null-id invalid-request error. Notifications without an id are not answered;
  parse errors return `-32700` with a null id and leave the connection alive
  (`JsonRpcHandler.kt:24-57`, `:206-244`).
- Unknown methods, unknown graphs, unknown nodes, and revision mismatches are
  mapped to distinct errors; application failures also emit `cint/error` with the
  request id, and the response follows it (`JsonRpcHandler.kt:216-262`).
- The sequential socket-safety cases are right: a regular file is refused, a live
  daemon is refused, a dead socket is removed, and the startup cleanup only
  deletes when the server was bound (`SocketPaths.kt:66-82`,
  `EngineSocketServer.kt:39-58`, `:154-156`).
- `cleanup` closes the listener and clients, joins client threads, closes the
  service, and removes the socket on the protocol shutdown path; the test asserts
  both socket removal and `service.closed` (`EngineSocketServer.kt:135-158`,
  `EngineSocketServerTest.kt:181-196`).
- `RealGraphService` serializes every public operation on one fair lock, and the
  only lock nesting is service lock then `GraphStore`'s synchronized block;
  `GraphStore` never calls back into the service, so there is no lock-order
  inversion. `close` is idempotent and closes the LSP exactly once
  (`RealGraphService.kt:51-54`, `:166-176`).
- The dot writer is deterministic (nodes, edges, groups sorted), undirected
  (`--`), escapes backslashes, quotes, carriage returns, and newlines, emits
  group clusters, uses `box`/`note`/`folder` per kind, and writes reference counts
  (`DotWriter.kt:11-92`; `DotWriterTest.kt`).
- The config loader has sensible defaults, rejects missing files, malformed YAML,
  an empty command, and an invalid grouping, and the CLI rejects unknown
  arguments and missing values (`EngineConfigTest.kt`, `CliTest.kt`).
- The socket tests use real Unix sockets and assert fresh directories are `0700`
  and fresh sockets are `0600` (`EngineSocketServerTest.kt:199-221`).
- The authored server source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review; the
  handover is assumed green.

### Dissent

- Finding 1: the configured workspace include/exclude and `followSymlinks` must be
  the set used by expansion and seed validation, not merely a discarded startup
  check.
- Finding 2: every run must use a `0700` directory, including an existing one, and
  must not treat an access-denied live path as stale.
- Finding 3: a FIFO or device at the socket path must be refused, not deleted.
- Finding 4: `graph/open` by `nodeId` must not query the LSP before the expansion
  cache.
- Finding 5: cached FILE/GROUP expansions must not disagree with the current graph
  snapshot.
- Finding 6: `graph/dot` must report a graph that was never opened as an error (or
  the empty-graph behavior must be explicit and tested).
- Finding 7: an ambiguous qualified-name seed must not silently select an
  arbitrary overload.
- Finding 8: the real service's seed, expansion, cache, config, and serialization
  paths need tests.
- Finding 9: a signal shutdown must remove the socket.
- Finding 10: a misspelled YAML key must not silently change the analysis set.
- Finding 11: the derived socket path must be checked against the Unix path limit
  with a clear error.

## Round 2

**Verdict:** Changes requested.

The Round 1 findings are addressed in the common paths: the configured workspace
set now reaches seed validation and expansion, existing socket directories are
checked and forced to `0700`, FIFO/device paths are refused, path length is
guarded, `nodeId` reopens and FILE/GROUP rejection use the intended cache and
error behavior, the seed schema is singular and overload-aware, shutdown awaits
bounded termination, unknown config keys are rejected, and the real service has
FakeGraphLsp coverage. The remaining problems are state-commit ordering in
`graph/open`, stale content for reconnecting/`nodeId` clients, a socket-path
symlink regression, shutdown ordering when an expansion holds the service lock,
and a revision-reset test that cannot detect a missing reset.

### Round 1 dispositions

1. **Config workspace filters — Concur (addressed).** `refreshWorkspace` applies
   `config.workspace.include`, `exclude`, and `followSymlinks`
   (`RealGraphService.kt:346-361`), the resulting path set is passed to
   `NeighbourExpander` (`:391`), and the seed is checked against `paths` before
   `ensureClient`/`didOpen` (`:254-260`). `NeighbourExpander` uses the supplied
   set instead of re-discovering with frontend defaults
   (`NeighbourExpander.kt:30-33`, `:104-110`). The exclusion tests cover both a
   seed and an incoming reference (`RealGraphServiceTest.kt:55-114`).
2. **Existing socket directory permissions — Concur (addressed).** An existing
   parent must not be a symlink or non-directory, must be owned by the current
   uid when that is available, and is forced to `0700`
   (`SocketPaths.kt:109-131`, `:133-141`). `probe` separates `INACCESSIBLE` from
   stale (`:89-105`), and `restoresOwnerOnlyPermissionsOnAnExistingSocketDirectory`
   covers the `0777` case (`EngineSocketServerTest.kt:269-302`).
3. **Non-socket path detection — Concur (addressed for FIFOs/devices).**
   `isSocket` checks `S_IFSOCK` from `unix:mode` (`SocketPaths.kt:143-149`) and
   `refusesToRemoveFifoAtSocketPath` verifies a FIFO is retained
   (`EngineSocketServerTest.kt:304-318`). The symlink-at-socket-path case is a
   new regression in new finding 1.
4. **`graph/open` cache usage — Concur (addressed for the cache-hit path).**
   `openNode` puts the computation inside `store.expand`, so a cache hit does not
   query the LSP (`RealGraphService.kt:208-218`), and
   `reopenByNodeIdUsesTheCacheWithoutQueryingLsp` asserts the query count is
   unchanged (`RealGraphServiceTest.kt:174-196`). Content freshness for
   `nodeId`/reconnecting clients is new finding 3.
5. **FILE/GROUP expansion — Concur (addressed by explicit deferral).**
   `expand` returns `UNSUPPORTED_CAPABILITY` for FILE/GROUP nodes
   (`RealGraphService.kt:158-160`, `:455-460`), `GraphInfo` advertises only
   `ENTITY` (`:114`), `storedExpansion` is no longer cached, and the follow-up is
   documented in `docs/tasks/016-file-and-group-expansion.md`. The test asserts
   both kinds and the node-kind list (`RealGraphServiceTest.kt:198-222`).
6. **`graph/dot` before open — Concur (addressed).** `dot`, `node`, and `expand`
   all call `requireOpen` (`RealGraphService.kt:148`, `:177`, `:191`), and
   `initialize` reports `open` and `revision` (`JsonRpcHandler.kt:92-100`). The
   socket and service tests cover both before/after states
   (`EngineSocketServerTest.kt:420-471`, `RealGraphServiceTest.kt:287-299`).
7. **Ambiguous seed selector — Concur (addressed).** `graph/open` now accepts one
   schema (`{nodeId}` or `{file, qualifiedName, signatureFingerprint?}`), rejects
   extra/old forms (`JsonRpcHandler.kt:117-137`), and multiple matches return
   `INVALID_REQUEST` with candidate ids and fingerprints instead of choosing one
   (`RealGraphService.kt:235-313`). The overload test covers both the error and
   the fingerprint-selected success (`RealGraphServiceTest.kt:117-146`).
8. **Real-service tests — Concur (addressed, with a residual test gap).** The new
   FakeGraphLsp covers seed resolution, containment/exclusion, overloads,
   revision changes, cache hits, serialized LSP calls, invariant mapping,
   status/lifecycle, and FILE/GROUP rejection. The revision-reset test itself is
   too weak; see new finding 5.
9. **Signal shutdown — Concur for the idle path.** The hook now calls `close()`
   and waits up to 5 seconds (`Main.kt:42-48`), and `DaemonProcessTest` starts a
   real child JVM and sends SIGTERM (`DaemonProcessTest.kt:11-44`). The in-flight
   path is new finding 4.
10. **Unknown config keys — Concur (addressed).** `rejectUnknown` runs for the
    root, `lsp`, `workspace`, and `graph` sections (`EngineConfig.kt:80-86`,
    `:124-137`), with tests for both a misspelled section and property
    (`EngineConfigTest.kt:95-115`).
11. **Socket path length — Concur (addressed).** `validatePath` checks UTF-8
    bytes against 107 before use (`SocketPaths.kt:161-166`, `:174`), and
    `rejectsPathsLongerThanTheUnixLimit` covers the failure (`SocketPathsTest.kt:42-49`).

### New findings

1. **A symlink at the socket path is now treated as a socket and deleted**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/SocketPaths.kt:72-81`
     (`prepare`) and `:143-149` (`isSocket`); the parent-symlink test is
     `analysis-engine/server/src/test/kotlin/dev/codenb/server/EngineSocketServerTest.kt:321-331`.
   - Problem: `isSocket` calls `Files.getAttribute(path, "unix:mode")` without
     `NOFOLLOW_LINKS`, so it follows a symlink at the socket path. A symlink to a
     stale socket therefore appears to be a socket, `probe` returns `STALE`, and
     `Files.delete(socket)` removes the symlink and binds a socket in its place.
     The previous implementation explicitly refused `isSymbolicLink`; only a
     symlink *parent* is tested now, not a symlink at the socket path itself.
     This violates the requirement that a non-socket path is not clobbered.
   - Suggested fix: Reject `Files.isSymbolicLink(socket)` before `isSocket`, or
     read `unix:mode` with `LinkOption.NOFOLLOW_LINKS` and require the link itself
     to be `S_IFSOCK`. Add a test that creates a stale target socket, symlinks the
     requested socket path to it, and asserts `IOException` with the symlink still
     pointing at the target.
   - Reproduction: bind and close a server socket at `/tmp/x/target.sock`, create
     `ln -s /tmp/x/target.sock /tmp/x/engine.sock`, then start the daemon with
     `--socket /tmp/x/engine.sock`. Today the symlink is removed and a socket is
     created at its place.

2. **`graph/open` mutates workspace/store/revision state before the open can succeed**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:220-232`
     (`openSelector`), `:346-361` (`refreshWorkspace`), and `:121-124` (`status`);
     the failure path reaches `store.expand` at `:230-232`.
   - Problem: `refreshWorkspace` assigns `workspacePaths` before
     `contentRevision` and `resolveFileSeed` validate anything. If the revision
     read or seed resolution fails, the old graph's store and revision remain but
     `workspacePaths` is the new set; a later `nodeId` expansion uses the new
     workspace with the old store/revision (`ensureWorkspace` at `:363` and
     `computeEntityExpansion` at `:379-399`). Worse, after resolution
     `openSelector` replaces `policy`, `store`, and `graphRevision` before
     `store.expand` computes the new expansion. If the expansion fails (LSP
     failure, unsupported capability, containment invariant), the previous graph
     has been discarded, the store is empty, and `status()` reports
     `{open=true, revision=R2}` even though no node exists. The same happens on a
     failed first open, which reports an open graph with an empty store.
   - Suggested fix: Treat the whole open as a transaction. Keep `paths`,
     `revision`, the resolved node, and the computed expansion in locals; use a
     temporary grouping policy/store for the compute, and commit
     `workspacePaths`, `policy`, `store`, and `graphRevision` only after the
     expansion succeeds. On any failure leave all prior state unchanged. Add a
     test that opens a seed successfully, changes the workspace, makes the fake
     LSP fail an expansion-only call (for example `references`), calls
     `graph/open` with a selector, and asserts the original node and revision are
     still served by `node/get`/`dot` and `status()` still describes the old
     graph.
   - Reproduction: with the fixture, open `A.kt#app.seed` successfully, edit
     `B.kt` so the revision changes, set a fake `references` failure, and call
     `graph/open` for `A.kt#app.seed` again. `status()` returns open with the new
     revision, `node/get` for the old seed returns `UNKNOWN_NODE`, and `graph/dot`
     is empty.

3. **Only selector opens refresh content, so a reconnecting `nodeId` client can be served stale data**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:208-218`
     (`openNode`), `:137-165` (`expand`), `:167-182` (`node`), and
     `analysis-engine/server/src/main/kotlin/dev/codenb/server/JsonRpcHandler.kt:92-100`
     (`initialize`); the requirement is `docs/architecture.md:82-84` ("Content
     changes invalidate the relevant cache entries on the next session") and
     `docs/tasks/008-unix-socket-daemon.md:42` ("The daemon is reused across
     sessions").
   - Problem: `refreshWorkspace` and `contentRevision` are called only from
     `openSelector`. `openNode`, `expand`, `node`, `dot`, and `initialize` serve
     the last selector revision unconditionally. After a file edit, a new client
     that reconnects and opens a known node by `nodeId`, or that uses its
     previously returned revision with `node/expand`, receives the old cached
     expansion and old revision with no indication that the content changed. The
     daemon has no session token, so `nodeId` cannot be assumed to be
     same-session-only. `reopenByNodeIdUsesTheCacheWithoutQueryingLsp` exercises
     only the unchanged-content case.
   - Suggested fix: On `openNode` (and optionally on `initialize`/a new session
     marker), compute `refreshWorkspace()` and `contentRevision()` without
     starting the LSP; if the revision differs, either reset and re-resolve the
     node from its `fileUri`/`qualifiedName`/`signatureFingerprint`, or return a
     distinct `INVALID_GRAPH`/revision error that tells the client to reopen with
     a selector. Add a test that opens a selector, edits a referenced file, then
     calls `open(Seed.ByNodeId(first.node.id))` and asserts the response either
     contains the new neighbour/new revision or fails with the explicit refresh
     error, not the old expansion.
   - Reproduction: open `A.kt#app.seed` (revision R1), edit `B.kt` to add a caller
     and update the fake LSP references, then call
     `service.open(Seed.ByNodeId(first.node.id))`. The cache key still matches
     `(graph, node, R1)`, so the old expansion is returned and the new caller is
     absent.

4. **Signal shutdown can still leave the socket when an expansion holds the service lock**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/EngineSocketServer.kt:135-158`
     (`service.close()` at `:153` precedes `Files.deleteIfExists` at `:154-156`),
     `analysis-engine/server/src/main/kotlin/dev/codenb/server/Main.kt:42-48`
     (5-second hook wait), and `RealGraphService.kt:196-206` (`close` takes the
     service lock).
   - Problem: Cleanup joins client threads for at most the 2-second grace, but a
     client thread can still be inside an LSP request and hold the service lock.
     `service.close()` then blocks for up to the LSP request timeout (30 seconds)
     before the socket deletion is reached. The shutdown hook's 5-second
     `awaitTermination` returns false, the JVM halts, and the socket file
     remains. `DaemonProcessTest` has no connected or in-flight client, so it only
     proves the idle path.
   - Suggested fix: Remove/close the socket before `service.close()`, or put the
     socket deletion in a `finally` that does not wait for the service lock;
     bound `service.close()` separately and have the hook wait on that bound.
     Add a test with an `EngineSocketServer` over a `GraphService` whose
     `close()` blocks longer than the hook timeout, or a process test with a slow
     fake LSP and an in-flight `node/expand`, and assert the socket is removed.
   - Reproduction: unit-test an `EngineSocketServer` whose `GraphService.close`
     sleeps for 10 seconds; call `close()` and `awaitTermination(1, SECONDS)`.
     The await returns false and the socket still exists, so `Main`'s 5-second
     hook exits with the path present.

5. **The revision-reset test cannot detect a missing store reset**

   - Location: `analysis-engine/server/src/test/kotlin/dev/codenb/server/RealGraphServiceTest.kt:149-172`
     (`reopenAfterEditChangesRevisionAndAddsNewReference`).
   - Problem: The first open is at `A.kt`, and `A` has no reference to
     `B.kt#other`, so `other` is never in the first graph. The second open adds a
     new `caller` reference, and `GraphStore.merge` is monotone, so the assertion
     that `caller` appears passes even if `openSelector` never resets the store.
     The test also never asserts that the old revision is rejected or that an
     entity/reference removed by the edit disappears. The reset path is therefore
     unexercised.
   - Suggested fix: Make the first version of `B.kt` contain an entity that `A`
     references, then edit `B.kt` to remove/rename it and update the fake
     references. After the second open, assert the old entity is absent (for
     example via `node/get`/`dot` or the old node id returning `UNKNOWN_NODE`),
     assert `status().revision` changed, and assert an `expand` with the old
     revision returns `INVALID_REQUEST`. Add a `nodeId` reopen case after the
     edit to cover new finding 3.

### Concur

- The workspace solve is now a single set: `refreshWorkspace` applies the
  configured include/exclude/symlink policy, `NeighbourExpander` consumes that
  set, and the seed is validated before the LSP is started
  (`RealGraphService.kt:254-260`, `:346-361`, `:391`;
  `NeighbourExpander.kt:30-33`, `:104-110`).
- Socket path safety is substantially stronger: `0700` on existing parents,
  owner/symlink checks, `S_IFSOCK`, `INACCESSIBLE` versus `STALE`, a 107-byte
  guard, and real tests for existing permissions, FIFOs, symlink parents, and
  long paths (`SocketPaths.kt`; `EngineSocketServerTest.kt:269-345`;
  `SocketPathsTest.kt:42-49`).
- The JSON-RPC surface is coherent: the singular seed schema, ambiguity error,
  `signatureFingerprint` selection, `node/get` as `Expansion`, `initialize`
  open/revision, single protocol-error responses, and `cint/error` only for
  id-less application failures are all covered by the updated socket tests
  (`JsonRpcHandler.kt`; `EngineSocketServerTest.kt:347-593`).
- FILE/GROUP expansion is explicitly unsupported rather than a silent snapshot
  projection, and the follow-up is named in ticket 016; `GraphInfo` advertises
  only `ENTITY` until then (`RealGraphService.kt:114`, `:158-160`, `:455-460`;
  `docs/tasks/016-file-and-group-expansion.md`).
- The real-service tests use a fake LSP and cover seed resolution, root-relative
  and absolute paths, exclusion of a seed and an incoming reference, overload
  ambiguity, revision changes, cache-only `nodeId` reopen, serialized LSP calls,
  invariant mapping, status/lifecycle, and FILE/GROUP rejection
  (`RealGraphServiceTest.kt`; `FakeGraphLsp.kt`).
- `RealGraphService.close` is idempotent, the service lock serializes LSP and
  store access, and the only lock nesting is service lock then `GraphStore`'s
  monitor; there is no reverse path (`RealGraphService.kt:100-106`, `:196-206`;
  `GraphStore.kt`).
- `DotWriter` is now in `core`, so ticket 009 can reuse it; `graph/dot` remains
  a plain transport string (`analysis-engine/core/src/main/kotlin/dev/codenb/core/DotWriter.kt`).
- The config tests cover defaults, valid YAML, missing files, malformed YAML,
  invalid grouping, empty commands, and unknown section/property keys
  (`EngineConfigTest.kt`).
- The authored source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review; the
  handover is assumed green.

### Dissent

- New finding 1: a symlink at the socket path must be refused, not followed and
  deleted.
- New finding 2: `graph/open` must not commit workspace, policy, store, or
  revision state until the new expansion succeeds; a failed open must leave the
  previous graph and status intact.
- New finding 3: a reconnecting `nodeId` client must not receive a cached
  expansion from a changed workspace; refresh the content revision or require a
  selector reopen.
- New finding 4: signal shutdown must remove the socket even when an in-flight
  expansion holds the service lock; socket deletion must not sit behind an
  unbounded `service.close()`.
- New finding 5: the revision-reset test must assert removal of old content and
  rejection of the old revision; the current test passes without a reset.

## Round 3

**Verdict:** Changes requested.

The Round 2 fixes are present in the main paths: `openSelector` stages its
workspace paths, candidate policy/store, and computed expansion and commits only
after success; `status` and `openNode` recompute the content hash without the
LSP; changed content makes `openNode` fail with old/current revisions and makes
`status` report closed; the socket-path symlink is refused with `NOFOLLOW_LINKS`;
the socket is deleted before service shutdown; and the reset test now detects
removed nodes and retired revisions. The remaining problems are the `expand`
path still accepting the old revision after a content edit, an unchanged-revision
selector open returning a fresh expansion while storing the cached one, the
bounded service-close thread abandoning the LSP close, and a content-hash race
between hashing and the expansion that can commit a graph under a revision that
does not describe it.

### Round 2 dispositions

1. **Symlink at the socket path — Concur (addressed).** `prepare` rejects
   `Files.isSymbolicLink` before examining the mode, and `isSocket` reads
   `unix:mode` with `NOFOLLOW_LINKS` (`SocketPaths.kt:72-80`, `:146-152`). The
   new `refusesSymlinkAtTheSocketPath` keeps both the link and the stale target
   (`EngineSocketServerTest.kt:356-374`).
2. **Transactional `graph/open` — Concur (addressed for failure atomicity).**
   `openSelector` resolves and computes into locals, builds a candidate
   `GraphStore`/`GroupingPolicy`, and only then assigns `store`, `policy`,
   `workspacePaths`, and `graphRevision` (`RealGraphService.kt:238-255`). The
   failed first-open and failed-reopen tests assert status false/null and the old
   node/dot (`RealGraphServiceTest.kt:224-265`). The content-hash TOCTOU is a
   separate new finding 4.
3. **Selector/nodeId freshness — Concur for `nodeId` and status; dissent for
   `expand`.** `openNode` hashes the workspace and throws `staleGraph` with
   old/current revisions (`RealGraphService.kt:221-236`), `status` reports
   `GraphStatus(false, oldRevision)` (`:121-134`), and unchanged `nodeId` remains
   a no-LSP cache hit (`RealGraphServiceTest.kt:268-289`). `expand` still checks
   only `revision == graphRevision` (`RealGraphService.kt:149-177`); see new
   finding 1.
4. **Signal shutdown — Concur for socket removal; new finding 3 for the service
   close.** `cleanup` now deletes the socket immediately after closing the
   listener, before joining clients and before `service.close`
   (`EngineSocketServer.kt:135-158`); `shutdownRemovesSocketEvenWhenServiceCloseBlocks`
   and the SIGTERM process test cover the socket (`EngineSocketServerTest.kt:196-214`,
   `DaemonProcessTest.kt:11-44`). The 500 ms service-close join is addressed in
   new finding 3.
5. **Revision-reset test — Concur (addressed).** The first graph now contains
   `oldTarget`, the edit renames it, the second open changes the revision, the old
   node is `UNKNOWN_NODE`, and the old revision is rejected (`RealGraphServiceTest.kt:154-194`).
   `nodeIdOpenAfterEditRequiresASelectorReopen` covers the open-by-node-id path
   (`:197-221`).

### New findings

1. **`node/expand` still accepts the old revision after a content edit before a successful reopen**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:149-177`
     (`expand` compares only with `graphRevision`), `:121-134` (`status` computes
     the fresh hash but does not retire the revision), and `:221-236`
     (`openNode` does retire it for its own path). The tests at
     `RealGraphServiceTest.kt:197-265` cover `openNode` and a failed reopen but
     never call `expand` with the old revision immediately after an edit.
   - Problem: After a file edit, `graphRevision` remains R1. `status` returns
     `GraphStatus(false, R1)` and `openNode` throws, but
     `expand(graphId, node, R1)` passes the revision check. If the node is cached,
     it returns the old expansion. If it is not cached, it computes with
     `ensureWorkspace()` (the old paths) while `NeighbourExpander` reads the new
     file text, then merges those new facts into the store under R1. The old
     revision is therefore not retired until a successful selector reopen, and a
     client can get stale or mixed content while the protocol claims the graph is
     not open.
   - Suggested fix: Make `expand` recompute `discoverWorkspace()` and
     `contentRevision()` (or consult a content-valid flag) and throw the same
     `staleGraph(old, current)` error when they differ, before touching the cache
     or the LSP. Alternatively, if the old graph is intentionally still readable,
     serve only its cached snapshot and never compute new facts under the old
     revision. Add a test that opens, edits, and immediately calls
     `expand(graphId, node, oldRevision)` and expects `INVALID_GRAPH` with
     old/current details and no new node/edge.
   - Reproduction: in the fixture, open `A.kt#app.seed` (R1) with `oldTarget`,
     edit `B.kt` to `newTarget`, then call
     `service.expand(graphId, first.node.id, R1)` without a selector reopen. The
     call succeeds (old cached expansion or a mix of new facts under R1) instead
     of returning `INVALID_GRAPH`; `status` already reports closed.

2. **An unchanged-revision selector open returns a fresh expansion but retains the cached one**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:238-255`
     (`computeEntityExpansion` before the `graphRevision == revision` branch and
     `store.expand` in the else branch) and the cache hit path in
     `analysis-engine/core/src/main/kotlin/dev/codenb/core/GraphStore.kt:36-47`.
   - Problem: Every selector open computes a fresh expansion through the LSP
     before checking the store. When the revision is unchanged and the store has
     a cached expansion for that node, `store.expand` hits the cache and keeps the
     old expansion, while `openSelector` returns the fresh `expansion`. If the
     LSP facts differ without a content-hash change (for example after an LSP
     restart or a fake/test update to `referencesByCall`), `graph/open` can
     contain neighbours and edges that `node/get`/`node/expand` do not have; a
     neighbour returned by the open can then be `UNKNOWN_NODE` when expanded. The
     same eager compute means a selector open with an unchanged revision still
     queries the LSP and fails if the LSP is unavailable, even though the store
     has a valid cached expansion.
   - Suggested fix: For `graphRevision == revision`, do not precompute; resolve
     the seed (preferably from the store by file/qualifiedName/fingerprint, falling
     back to the LSP if absent) and call `store.expand` with a lazy compute lambda
     so a cache hit returns the cached expansion and no LSP query. If you do
     compute fresh, merge it into the store and return the store's result, so the
     response and the store agree. Add tests that (a) update the fake references
     without changing files and assert `graph/open`, `node/get`, and
     `node/expand` agree, and (b) perform a cache-valid selector open while the
     fake LSP is failing.
   - Reproduction: first open `A.kt#app.seed` with `oldTarget`; update
     `fixture.lsp.referencesByCall` to add `newTarget` without editing files;
     call `service.open(Selector A)`. The result contains `newTarget` while
     `service.node(graphId, A)`/`service.expand(newTarget)` still reflect the old
     store. Setting `queryFailure` after the first open makes the unchanged
     selector reopen fail even though the cached graph is valid.

3. **The bounded service close abandons the service/LSP close and can leak the closer thread**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/EngineSocketServer.kt:135-160`
     (`cleanup` and `closeService`; the 500 ms join at `:159`), `Main.kt:43-51`
     (5-second hook wait), and `RealGraphService.kt:209-219` (`close` takes the
     service lock and closes the LSP). The blocking-close test is
     `EngineSocketServerTest.kt:196-214`.
   - Problem: `closeService` starts a daemon thread and waits only 500 ms, then
     returns and `cleanup` counts down. `RealGraphService.close` may first wait
     for the service lock held by an in-flight expansion (LSP request timeout up
     to 30 seconds) and then spend the LSP client's own 1-second cleanup time.
     When the bound expires, the server reports termination and `Main` exits; the
     daemon thread is killed with the JVM before it can finish the LSP
     shutdown/exit/destroy, and if `service.close` never returns the thread is
     leaked. The test uses a 5-second `BlockingCloseGraphService` but only asserts
     that the socket is gone; it never asserts that the service was closed or that
     the closer thread terminated.
   - Suggested fix: Delete the socket first (already done), then perform the
     service close on a tracked thread with a bound that covers the LSP cleanup
     (or interrupt/force-close the LSP on timeout), and have the hook/process wait
     for that bound. Add a test with a fake `GraphLsp.close` that blocks for
     longer than the bound and assert that the LSP close eventually completes or
     is forcibly terminated, and that no closer thread is left running after the
     server terminates.
   - Reproduction: `BlockingCloseGraphService(FakeGraphService(), 5_000)`; after
     `server.awaitTermination(4, SECONDS)` returns true and the socket is gone,
     the closer thread is still alive and `delegate.closed` is false. The current
     test passes because it does not check either fact. With a real LSP, the
     child process may outlive the daemon.

4. **The content revision is hashed before the expansion reads the files, so a concurrent edit can commit a mismatched revision**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:238-245`
     (hash and compute order in `openSelector`), `:391-403`
     (`contentRevision`), and `:405-426` (`computeEntityExpansion` reads the
     workspace). `openNode` has the same hash-then-compute shape at `:221-235`.
   - Problem: The revision is computed from disk before `resolveFileSeed` and
     `computeEntityExpansion` read/query the files. If a file changes between the
     hash and the expansion, the expansion can contain facts from the new text
     while `exp.revision`/`graphRevision` still identify the old text. The
     candidate store is committed and `graph/open` returns that mismatched
     revision; `status` immediately reports closed because the current hash no
     longer matches, and `node`/`dot`/`expand` can serve a graph whose revision is
     not the content it was built from. This is a transactional gap even though
     all failure paths are staged correctly.
   - Suggested fix: Recompute `contentRevision(paths)` after the expansion and
     before committing; if it differs, discard the candidate and either retry the
     open (bounded) or return a workspace/revision error without changing the
     previous graph. Alternatively derive the revision from the exact
     `ExpansionWithFiles.fileTexts` and paths that produced the expansion, so the
     revision and facts are the same snapshot. Add a deterministic test with a
     fake LSP callback that edits a file during `documentSymbol`/`references`;
     assert the returned revision matches the expansion content or the open
     fails/retries, and that `status` does not immediately disagree.
   - Reproduction: in `FakeGraphLsp.documentSymbol` or `references`, write a
     changed `B.kt` as a side effect during an `open`; the resulting expansion can
     include the new `B` entity while its revision is the pre-edit hash.
     `status()` afterward reports closed, and the returned expansion is under the
     old revision.

### Concur

- `openSelector` now stages `paths`, `revision`, `candidatePolicy`, the resolved
  node, the expansion, and a candidate `GraphStore`, and commits only after the
  candidate `store.expand` succeeds (`RealGraphService.kt:238-255`). A failed
  first open leaves `graphRevision` null and status false/null; a failed reopen
  leaves the old graph, node, and dot intact (`RealGraphServiceTest.kt:224-265`).
- `status` and `openNode` recompute the workspace content revision without an LSP
  query; changed content yields `GraphStatus(false, oldRevision)` and an
  `INVALID_GRAPH` with old/current revision details, while unchanged `nodeId`
  opens remain cache hits (`RealGraphService.kt:121-134`, `:221-236`;
  `RealGraphServiceTest.kt:197-221`, `:268-289`).
- Socket-path safety is now correct for the reported cases: symlinks at the
  socket path are refused with `NOFOLLOW_LINKS`, FIFOs/devices/regular files are
  refused, parent symlinks and owner/mode rules are checked, and the 107-byte
  path guard remains (`SocketPaths.kt:67-152`; `EngineSocketServerTest.kt:327-388`;
  `SocketPathsTest.kt:42-49`).
- The socket is deleted before service shutdown, so a blocking `service.close`
  cannot leave the path; the blocking-close test and the SIGTERM process test
  cover that part (`EngineSocketServer.kt:135-139`;
  `EngineSocketServerTest.kt:196-214`; `DaemonProcessTest.kt:11-44`). The
  abandoned service-close thread is new finding 3.
- The revision-reset test now starts with a referenced `oldTarget`, renames it,
  asserts the old node is unknown and the new target present, checks the changed
  revision, and verifies the old revision is rejected
  (`RealGraphServiceTest.kt:154-194`); the nodeId-after-edit test covers the
  selector-required path (`:197-221`).
- Ambiguous seeds now expose structural candidate details and the multi-parameter
  overload test and socket decode cover them (`RealGraphService.kt:326-340`;
  `RealGraphServiceTest.kt:118-151`; `EngineSocketServerTest.kt:463-506`).
- `GraphInfo.nodeKinds` contains all returnable kinds while `expandableNodeKinds`
  is `[ENTITY]`; the initialize envelope and the expansion test agree, and
  FILE/GROUP expansion remains explicitly unsupported with ticket 016 as the
  follow-up (`RealGraphService.kt:108-136`, `:171-173`;
  `JsonRpcHandler.kt:93-104`; `RealGraphServiceTest.kt:291-316`).
- The seeded explicit-unsupported behavior for FILE/GROUP and the socket-level
  `node/get`/`node/expand` shapes remain coherent, and the NDJSON framing,
  request-id handling, parse-error recovery, and per-client writers are unchanged.
- The authored source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review; the
  handover is assumed green.

### Dissent

- New finding 1: `node/expand` must reject the old revision after a content edit,
  not serve a cached or newly computed expansion under it.
- New finding 2: an unchanged-revision selector open must not return a fresh
  expansion while retaining the cached one, and must not require the LSP for a
  valid cache hit.
- New finding 3: the bounded service close must not abandon the LSP close or leave
  a live closer thread; the test must assert service/thread termination, not only
  socket removal.
- New finding 4: `graph/open` must not commit an expansion whose content revision
  no longer matches the files it read; rehash after compute or derive the
  revision from the expansion's snapshot.

## Round 4

**Verdict:** Changes requested.

The Round 3 fixes are present in the main paths: `expand` checks the content
revision before cache/LSP and rechecks after a cache-miss computation; the
same-revision selector path resolves from the store, uses a lazy `store.expand`,
and survives an LSP failure on a hit; the candidate open rehashes after the
expansion and retries once; `EngineSocketServer` deletes the socket first and
closes the service on an interruptible tracked thread; and the tests now cover
the stale-expand, failed-first/reopen, blocking-close, and retry cases. The
remaining problems are the store selector silently choosing one overload when no
fingerprint was supplied, `node/get` (and the open/expand cache) returning stale
edge metadata when the graph grows, an interrupted `close` allowing an
in-flight operation to start a new LSP after the old one was closed, the
`awaitTermination` success/failure mismatch on a non-terminating service close,
and the missing post-check on a cache hit in `expand`/`openNode`.

### Round 3 dispositions

1. **`expand` old revision — Concur (addressed for the pre-check and cache-miss
   path).** The initial `discoverWorkspace`/`contentRevision` check throws
   `staleGraph` before the cache/LSP (`RealGraphService.kt:164-177`), and the
   lazy compute rechecks after reading facts (`:183-187`). The new
   `expandAfterEditBeforeReopen` test covers both the cached seed and the
   unexpanded neighbour without graph mutation (`RealGraphServiceTest.kt:423-458`).
   The cache-hit post-check gap is new finding 5.
2. **Same-revision selector store expansion — Concur for the unique-seed,
   no-LSP cache path; dissent for the no-fingerprint selector and the
   growth/mutation behavior.** `openWithCurrentRevision` uses `seedFromStore`,
   `store.expand` lazily, and the LSP-failure test (`RealGraphService.kt:300-315`;
   `RealGraphServiceTest.kt:461-491`). A partial store cannot prove that a
   `file#qualifiedName` selector without a fingerprint is unique (new finding
   1), and the incident-key-only cache check returns stale edge metadata when
   the graph grows (new finding 2).
3. **Bounded service close — Concur for the tracked thread, the 2.5 s bound,
   the double interrupt, the `service.closed`/no-closer-thread assertions, and
   the close-interrupt test.** `EngineSocketServer.cleanup` deletes the socket
   first and `closeService` interrupts and joins twice
   (`EngineSocketServer.kt:135-172`; `EngineSocketServerTest.kt:195-218`;
   `RealGraphServiceTest.kt:396-420`). The interrupted-close in-flight LSP leak
   and the failed-close surfacing are new findings 3 and 4.
4. **Candidate retry/TOCTOU — Concur (addressed).** The candidate computes the
   expansion, rehashes, retries once, and only then builds/commits the candidate
   store (`RealGraphService.kt:273-298`); the fake-LSP callback edits a file
   during the expansion and the test verifies the new revision and status
   (`RealGraphServiceTest.kt:494-526`). The second-mismatch path is not tested
   (new finding 6).

### New findings

1. **A same-revision selector without a fingerprint silently picks one overload from a partial store**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:475-491`
     (`seedFromStore`, especially `.singleOrNull()`), used by
     `openWithCurrentRevision` at `:300-315`.
   - Problem: The store contains only the entities that have been merged by
     previous expansions, not every declaration in a seed file. For a selector
     with no `signatureFingerprint`, `seedFromStore` returns the single matching
     store node and bypasses `resolveFileSeed`/LSP discovery, even when the file
     contains another overload (or a class and a function with the same
     qualified name) that has not yet entered the store. A selector that is
     ambiguous in the source is therefore silently resolved to the one overload
     already cached. If the store has multiple candidates, `.singleOrNull()`
     returns null and falls back to the LSP; with the LSP failing, that becomes
     `LSP_FAILURE` instead of the deterministic ambiguity error.
   - Suggested fix: Only use `seedFromStore` when the selector is fully
     identified by `signatureFingerprint`, or maintain a complete per-file
     entity index from discovery and detect multiple matches before returning.
     If no fingerprint is supplied and the store cannot prove uniqueness, resolve
     via `resolveFileSeed` and return the structural ambiguity error. Add a test
     with two overloads: open one by fingerprint, then reopen the same file with
     `file#qualifiedName` and assert `INVALID_REQUEST` with both candidates (and
     the same result with the LSP failing).
   - Reproduction: `O.kt` contains `fun over(a: Int, b: Int)` and
     `fun over(a: String, b: String)`; open the `Int` overload with its
     fingerprint; then call `service.open(Seed.Selector("O.kt", "app.over"))`
     (no fingerprint) while the revision is unchanged. The store has only the
     `Int` node, so the selector succeeds with that overload instead of
     reporting both candidates.

2. **`node/get`'s cached-expansion check compares only edge keys, so merged edge counts/origins are served stale**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:191-217`
     (the `cached` incident-key check), the cache in
     `analysis-engine/core/src/main/kotlin/dev/codenb/core/GraphStore.kt:36-47`,
     and the cached paths in `RealGraphService.kt:183-187` (`expand`) and
     `:266-270`/`:306-311` (`openNode`/`openWithCurrentRevision`).
   - Problem: `node/get` returns the cached expansion when every incident edge
     **key** is present in the cached expansion. `GraphStore.mergeEdge` can keep
     the same `(kind, from, to)` key while changing `count` and `origins` when a
     different expansion observes the same relationship. The cached expansion
     then has the old count/origins, while the snapshot and `graph.dot` have the
     merged values. Cached `node/expand` and same-revision `graph/open`
     responses have the same problem: the cache is keyed only by the content
     revision, not by graph growth. The test
     `unchangedRevisionSelectorOpenUsesTheStoreExpansion` uses a graph that does
     not grow, so it does not exercise this case.
   - Suggested fix: Compare the full incident edge values (`key`, `count`,
     `origins`) and the node/neighbour metadata, not only keys, and use
     `storedExpansion` when they differ. For the cache used by `expand`/`open`,
     add a graph generation to the cache key or invalidate/merge entries when the
     store grows. Add a test that expands another node to add a second origin to
     an existing incident edge and then asserts `node/get`, `node/expand`,
     `graph/open`, and `graph/dot` agree on the count/origins.
   - Reproduction: `A.kt` has `fun seed()` and `B.kt` has
     `fun oldTarget(): Int = seed()`; open `A` (A-B has the `B -> A` origin,
     count 1 and is cached). Set a fake incoming reference from `A` to `B` and
     call `expand(B, R1)`, which merges an `A -> B` origin into the same A-B
     edge (count 2). `node(A)` still returns the cached count 1, while
     `graph/dot` shows count 2; `expand(A, R1)` and the same-revision
     `graph/open` also return the cached count.

3. **An interrupted `close` can be followed by an in-flight operation starting a new LSP that is never closed**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:231-252`
     (`close` proceeds without the lock after an interrupted `lockInterruptibly`)
     and `:418-433` (`ensureClient` has no `closed` check). The affected
     in-flight paths include `expand`/`openNode` at `:183-187`/`:266-270` and
     the candidate `openSelector` which calls `ensureClient` in
     `resolveFileSeed` and again in `computeEntityExpansion` (`:281-282`).
     The existing close test is `RealGraphServiceTest.kt:396-420`.
   - Problem: On the shutdown path the closer is interrupted while waiting for
     the service lock. `close` sets `closed`, nulls `client`, and closes the old
     LSP without the lock. An operation that already passed `checkOpen` and
     holds the lock can then call `ensureClient`; because `ensureClient` never
     checks `closed`, it starts a new LSP and assigns it to `client` after the
     close has returned. No later close runs, so the new LSP is leaked. The
     `closeIsInterruptible` test starts the open before the close and the fake
     `referencesBlock` is after the first `ensureClient`, so it never exercises
     an `ensureClient` after the interrupted close; it also does not assert the
     open thread's outcome.
   - Suggested fix: Make `ensureClient` check `closed` before creating a client
     and again after `initialize`; if closed, close the started client and throw
     `INVALID_GRAPH`. Add a closing/generation check before the in-flight
     operation commits. Add a test with a blocking `clientFactory`: start
     `open`, let the factory block, interrupt `close`, then release the factory,
     and assert no new client is adopted and no LSP remains open.
   - Reproduction: use a `clientFactory` that signals when called and blocks.
     Start `service.open(...)` in a thread, wait for the factory, start
     `service.close()` and interrupt it, then release the factory. The factory
     returns, `ensureClient` calls `initialize` and assigns the new client after
     `closed=true`; `fixture.lsp.closed` remains false and no later close occurs.

4. **A failed service close counts down `finished` before throwing, and `closeService` swallows close exceptions**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/EngineSocketServer.kt:135-172`
     (`cleanup` calls `finished.countDown()` at `:152` before the
     `IllegalStateException` at `:154`; `closeService` wraps `service.close()` in
     `runCatching` at `:159` and returns only `!closer.isAlive`).
   - Problem: When the service close does not terminate, `awaitTermination`
     returns true because the latch was counted down before the failure is
     thrown. The failure is visible only as an exception on the server `run`
     thread; a caller that uses `awaitTermination` (or the shutdown hook) sees a
     successful termination. A `GraphService.close` exception is swallowed
     entirely: the closer thread ends, `closeService` returns true, and the
     server reports success although the service was not closed. The
     `BlockingCloseGraphService` test uses a cooperative fake (it catches the
     interrupt), so neither the abandoned-thread failure path nor the throwing
     close path is exercised. The cleanup's worst case (2 s client join plus
     2.5 + 1 + 1 s close) also exceeds the 5 s hook wait in `Main.kt:43-51`.
   - Suggested fix: Record the close throwable/failure, count down only after a
     successful close (or expose a failure flag/`awaitTermination` result), and
     do not swallow `service.close` exceptions. Add a test with a service whose
     close ignores interrupts and/or throws, and assert that the caller observes
     the failure and that the closer thread is accounted for.
   - Reproduction: a `GraphService` whose `close` sleeps 10 s and ignores
     `InterruptedException`; `server.close(); server.awaitTermination(6, SECONDS)`
     returns true while the service is not closed; the exception is only on the
     server run thread. A `close` that throws is also counted as success.

5. **`expand` and `openNode` skip the post-check on a cache hit**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:183-187`
     (`expand`) and `:266-270` (`openNode`); compare the outer
     `requireRevisionUnchanged` after `store.expand` in
     `openWithCurrentRevision` at `:306-312`.
   - Problem: The post-hash check is inside the `store.expand` compute lambda, so
     it runs only on a cache miss. If the content changes after the initial
     `contentRevision` check but before `GraphStore.expand` returns a cached
     expansion, the cache hit bypasses the post-check and returns the old
     expansion under the current revision. The same window exists in
     `openNode`. `openWithCurrentRevision` already has the outer post-check, so
     the behavior is inconsistent between open and expand.
   - Suggested fix: Call `requireRevisionUnchanged(current)` after `store.expand`
     in both `expand` and `openNode`, regardless of whether the compute lambda
     ran. Add a deterministic test with a cache hit and a file edit between the
     initial hash and the cache lookup (for example by injecting a cache hook or
     a blocking cache lookup), asserting `INVALID_GRAPH` rather than a cached
     result.
   - Reproduction: open a seed (R1) with a cached expansion; arrange for a file
     edit after `contentRevision(paths)` but before the `store.expand` cache
     lookup; call `expand(seed, R1)` (or `open` by nodeId) and observe the
     cached old expansion returned successfully while `status` is false.

6. **The new tests do not exercise several adversarial paths claimed by the state**

   - Location: `analysis-engine/server/src/test/kotlin/dev/codenb/server/RealGraphServiceTest.kt:494-526`
     (the retry test changes the workspace only once; no second mismatch or
     prior-state assertion), `:396-420` (the close test starts the open before
     the interrupted close and does not assert the open result/store),
     `:461-491` (the store test uses a unique no-fingerprint seed and no graph
     growth), and `EngineSocketServerTest.kt:195-218` (only a cooperative
     interruptible close; no non-terminating/throwing service close).
   - Problem: The claims in the handover are broader than the tests: no test
     covers the second-mismatch clean error with untouched prior state, the
     no-fingerprint ambiguous store selector, node/get edge-count/origin growth,
     an `ensureClient` after an interrupted close, or a service close that
     ignores interrupts/throws. A regression in any of these paths would leave
     the suite green.
   - Suggested fix: Add the focused tests described in findings 1-5: an ambiguous
     no-fingerprint store reopen, a graph-growth node/get/open/expand/dot
     consistency test, a second-mismatch retry test, a blocking `clientFactory`
     close test, and a non-cooperative/throwing service-close test.

### Concur

- The content-edit `expand` path now fails before touching the cache or LSP, and
  the cache-miss computation rechecks the revision
  (`RealGraphService.kt:164-187`; `RealGraphServiceTest.kt:423-458`).
- The candidate open stages paths/revision/policy/store, retries once after a
  mismatch, and commits only after a stable revision; the rewritten
  `openRetriesWhenWorkspaceChangesDuringExpansion` test verifies the new
  revision and status (`RealGraphService.kt:273-298`;
  `RealGraphServiceTest.kt:494-526`).
- The same-revision selector path uses the store cache, returns the store
  result, and survives a failing LSP on a cache hit for a unique seed
  (`RealGraphService.kt:300-315`; `RealGraphServiceTest.kt:461-491`).
- `EngineSocketServer` deletes the socket before service close, closes the
  service on a tracked daemon thread with a 2.5 s bound and two interrupts, and
  the blocking-close test asserts `service.closed` and no `codenb-service-close`
  thread (`EngineSocketServer.kt:135-172`; `EngineSocketServerTest.kt:195-218`).
- The interrupted-close test verifies the close thread terminates and the fake
  LSP is closed (`RealGraphServiceTest.kt:396-420`), and the `RealGraphService`
  close is idempotent and uses `lockInterruptibly`.
- The revision-reset, failed-first-open, failed-reopen, revision-retirement,
  overload-ambiguity, serialized-LSP, invariant-mapping, FILE/GROUP
  unsupported, nodeKinds/expandable split, socket symlink/FIFO/path, and
  config tests remain in place and consistent with the handover.
- The NDJSON framing, request-id handling, error envelopes, and per-client
  writers are unchanged.
- The authored source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review; the
  handover is assumed green.

### Dissent

- New finding 1: a no-fingerprint selector must not be resolved from a partial
  store; it must report the file's full ambiguity or require a fingerprint.
- New finding 2: `node/get` and the open/expand cache must compare full incident
  edges/metadata, not only keys, or use a graph generation; stale counts/origins
  are not current.
- New finding 3: `ensureClient` must check `closed` so an interrupted close
  cannot be followed by a new, leaked LSP.
- New finding 4: a failed service close must be visible through the termination
  API and the closer must not swallow close exceptions; the failure path needs a
  test.
- New finding 5: `expand`/`openNode` need the outer post-check on a cache hit, as
  `openWithCurrentRevision` has.
- New finding 6: the second-mismatch, no-fingerprint ambiguity, graph-growth,
  ensure-after-close, and non-cooperative close paths need deterministic tests.

## Round 5

**Verdict:** Changes requested.

The Round 4 fixes are present in their main paths: the fingerprint-only store
shortcut and per-revision seed discovery cache, the rehashed `node/get` with
full incident-edge comparison and cache refresh, the `clientLock`/closed-check
`ensureClient` with a blocking-factory close test, the `EngineSocketServer`
failure recording and `awaitTermination` false path, the cache-hit post-check
hook, and the unified retry loop with same-revision and two-change tests. The
remaining correctness problems are the `storedExpansion` projection dropping an
entity's group and non-incident containment edges (and poisoning the expansion
cache with that projection), the discovery cache being incomplete for
neighbour-discovered files and unused on the changed-revision path, the final
retry error reporting the last attempt's revision instead of the graph's current
revision, the shutdown hook being shorter than the server's worst-case cleanup,
and `node/get` not rechecking `closed` before it refreshes the cache/returns.

### Round 4 dispositions

1. **Partial-store selector — Concur for the fingerprint-only store shortcut and
   the seed discovery cache; dissent for the incomplete cache.** `seedFromStore`
   now requires an exact fingerprint (`RealGraphService.kt:584-600`), and the
   no-fingerprint path uses a complete per-file discovery list (`:396-412`,
   `:473-493`); the overload test covers the seed file (`RealGraphServiceTest.kt:157-181`).
   Files discovered only as neighbours are not cached, and the changed-revision
   resolver ignores the cache (new finding 2).
2. **`node/get` rehash and growth refresh — Concur for the rehash, full incident
   edge comparison, and cache replacement; dissent for the stored projection.**
   `node` rehashes (`RealGraphService.kt:212-217`), `currentExpansion` compares
   full incident `Edge` values and refreshes the cache (`:629-648`), and the
   growth test checks node/get, expand, open, and dot for the incident A-B edge
   (`RealGraphServiceTest.kt:302-341`). `storedExpansion` is only an incident
   projection and omits the entity's group and non-incident containment edges;
   `currentExpansion` then caches that incomplete projection (new finding 1).
3. **`ensureClient`/`clientLock`/close — Concur (addressed).** `ensureClient`
   checks `closed` at entry, after the factory, and after `initialize`, closes a
   started client when shutdown wins, and assigns under `clientLock`
   (`RealGraphService.kt:509-541`); `close` uses `clientLock` and
   `lockInterruptibly` (`:241-264`); `interruptedCloseDoesNotAdoptANewClient`
   covers a blocking factory and asserts the fake client is closed and the open
   fails (`RealGraphServiceTest.kt:441-494`). No lock inversion was found.
4. **`EngineSocketServer` close failure — Concur (addressed).** `closeService`
   records failures, `awaitTermination` is false when `closeFailure` is set, and
   the stubborn/throwing close tests assert the failure and no closer thread
   (`EngineSocketServer.kt:62-67`, `:154-191`; `EngineSocketServerTest.kt:221-291`).
   The `Main` hook wait is shorter than the cleanup worst case (new finding 4).
5. **Cache-hit post-check — Concur (addressed).** `expand` and `openNode` invoke
   `beforeExpandCacheLookup`, then call `checkOpen` and
   `requireRevisionUnchanged` after `store.expand` (`RealGraphService.kt:189-198`,
   `:278-287`); `cacheHitAfterContentChangeIsRejected` edits the file in the hook
   and asserts `INVALID_GRAPH` (`RealGraphServiceTest.kt:412-439`).
6. **Unified retry/tests — Concur for the retry structure; dissent for the final
   error and remaining test gaps.** The loop retries same/changed revisions once,
   and the same-revision race plus two-change prior-state tests exercise it
   (`RealGraphService.kt:290-308`; `RealGraphServiceTest.kt:343-409`). The final
   error uses the last attempt's revision rather than the graph's revision (new
   finding 3), and several adversarial paths remain untested (new finding 6).

### New findings

1. **`storedExpansion` is an incident-only projection; `currentExpansion` caches it, dropping the entity's group and non-incident edges**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:629-648`
     (`currentExpansion`) and `:651-679` (`storedExpansion`/`neighbours`);
     `node` uses `storedExpansion` for a cache miss at `:220-225`.
   - Problem: `storedExpansion` keeps only edges incident to the requested node.
     For an ENTITY, the containing file is incident, but the group is reached
     through the file's `CONTAINS` edge (`file -> group`), not through an edge
     incident to the entity. A cached seed expansion from `ContainmentPipeline`
     includes the group and the file-group edge; when `currentExpansion` decides
     the cached expansion is stale (an incident edge changed) it replaces it with
     `storedExpansion`, which drops the group and every non-incident containment
     edge, then writes that incomplete projection into the expansion cache
     (`store.cache.put` at `:647`). The result is that `node/get`, `node/expand`,
     and same-revision `graph/open` after a graph growth return an entity
     expansion without its group, while `graph/dot` still contains it. The same
     happens for an entity with no cached expansion (`node` at `:220-225`), so
     `node(B)` before `expand(B)` is missing B's group while the later expand
     includes it. The growth test only checks the incident A-B edge and does not
     assert the group or a non-incident edge.
   - Suggested fix: Build a current **full** entity expansion from the store
     (entity, containing file, the file's group, the neighbour entities and
     their files/groups, with the containment/reference edges), or retain the
     cached full expansion and update all of its edge values from the snapshot.
     Do not cache the incident-only `storedExpansion` as the node's expansion.
     Add a test asserting the group node and the file->group edge remain in
     `node/get`/`node/expand`/`graph/open` after a growth, and that they match
     `graph/dot`; also test `node(B)` for an unexpanded neighbour before and
     after `expand(B)`.
   - Reproduction: open `A.kt`; the seed expansion contains the FILE and GROUP
     nodes. Expand another node to add a second origin to A-B (the existing
     growth setup), then call `service.node(graphId, seed.id)`. The returned
     `Expansion` contains the file and target but no group and no file-group
     edge, while `service.dot(graphId)` still contains both; the cache now makes
     `expand`/`open` return the same incomplete expansion.

2. **The discovery cache is not complete and is bypassed on the changed-revision path**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:473-493`
     (`cacheDiscovery`/`cachedDiscovery`), `:356-394` (`resolveFileSeed`, the only
     populator) and `:310-330` (`openChangedRevision` always calls
     `resolveFileSeed`).
   - Problem: `NeighbourExpander` discovers all entities in every file it loads,
     but only exposes `fileTexts` (`analysis-engine/frontend/.../ExpansionWithFiles.kt`),
     so those discoveries never enter `discoveredFiles`. A no-fingerprint
     selector for a file that entered the store as a neighbour therefore has no
     cached discovery; if the LSP is unavailable it falls through to
     `resolveFileSeed` and returns `LSP_FAILURE` instead of the deterministic
     ambiguity error, even though the prior expansion discovered the file. In
     addition, `openChangedRevision` never consults `cachedDiscovery`, so a
     discovery made during a failed candidate at revision R2 is not reused by a
     retry/matching open at R2; the LSP-failure guarantee only holds for the
     same-revision `seedForCurrentRevision` path. The new no-fingerprint test
     resolves the seed file as the first open, so it does not exercise either
     case.
   - Suggested fix: Expose per-file discovered entities from
     `NeighbourExpander` (or cache the discovered entities for every loaded file)
     and populate `discoveredFiles` at the current revision. Have
     `resolveFileSeed` check `cachedDiscovery(revision, uri)` before opening the
     document/querying the LSP, so the changed-revision path and retries use the
     complete cache. Add tests that (a) discover a two-overload file only as a
     neighbour, fail the LSP, and still get the structural ambiguity error, and
     (b) make a failed candidate discover the seed file at R2, then reopen at R2
     with the LSP failing and get the cached/ambiguity result.
   - Reproduction: open a seed in `A.kt` that references both overloads in
     `O.kt`; the expansion loads O and creates both store nodes, but
     `discoveredFiles` contains only A. Set the fake LSP to fail, then call
     `service.open(Seed.Selector("O.kt", "app.over"))` without a fingerprint.
     The store has both, but the service returns `LSP_FAILURE` rather than the
     `INVALID_REQUEST` candidate list.

3. **The final retry error reports the last attempt's revision, not the graph's current revision**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:290-308`
     (`lastRevision` is the attempt revision and is passed to `staleGraph`).
   - Problem: When the graph is already open at R1 and two attempts see R2 and
     R3, `lastRevision` is R2 (never committed) while `graphRevision` and
     `status().revision` remain R1. The final `INVALID_GRAPH` therefore reports
     `revision=R2`/`currentRevision=R3`; a client comparing the error to
     `status`/its known revision R1 sees a revision that never described the
     open graph. `twoContentChangesDuringOpenLeavePriorStateUntouched` asserts
     only that the two revisions differ and that status is R1, not that the
     error names R1. For a first open (`graphRevision == null`) the last attempt
     is the only revision available, but an already-open graph must report its
     current revision.
   - Suggested fix: Use `graphRevision ?: lastRevision` as the `revision`
     argument of the final `staleGraph`, and `lastActual` as the current
     revision; add a test asserting `revision == first.revision`,
     `currentRevision ==` the final observed revision, and
     `status().revision == first.revision` for the two-change case.
   - Reproduction: open A (R1), start a selector open, and edit the workspace
     during each of the two attempts (the `twoContentChanges` setup). The final
     exception details contain the second attempt's revision, not the R1 that
     `status()` still reports.

4. **The shutdown hook can outlive the server cleanup worst case**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/Main.kt:43-51`
     (5-second `awaitTermination`), `EngineSocketServer.kt:138-159` (client join
     `shutdownGraceMillis` default 2 s) and `:161-207` (`2.5 + 1 + 1` s service
     close).
   - Problem: The cleanup worst case is 2 s of client joining plus 4.5 s of
     service closing (6.5 s), which exceeds the hook's 5 s wait. A SIGTERM with a
     connected client and a service close that exhausts both interrupt rounds
     can let the hook time out and the JVM halt before the cleanup reaches its
     failure throw/`closeFailure`; the socket is deleted, but the closer thread
     and service/LSP can be abandoned and the failure is not surfaced. The new
     failure tests call `awaitTermination(6 s)` without an active query and the
     process test has no client, so they do not cover the worst case.
   - Suggested fix: Make the hook wait for at least the server's maximum cleanup
     bound (or reduce the cleanup bounds under the hook budget) and expose the
     `closeFailure` to the hook/process exit. Add a process/unit test with a
     connected client and a service close that ignores interrupts/uses the full
     close bound.
   - Reproduction: start the daemon, keep a client connection with an in-flight
     blocking call, configure a close that ignores interrupts, and send SIGTERM;
     the hook's 5 s expires while cleanup is still joining the client/closing the
     service, so the JVM exits before the failure is reported.

5. **`node/get` can refresh the cache and return after an interrupted close**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:202-227`
     (`node` checks `closed` only at entry) and `:629-648`
     (`currentExpansion` merges and writes the cache with no `checkOpen`).
   - Problem: `close` on an interrupted lock sets `closed` and closes the LSP
     without holding the service lock. A `node/get` that already passed the
     entry check can finish `discoverWorkspace`/`contentRevision`,
     `currentExpansion`, and `store.merge`/`store.cache.put`, then return a
     successful expansion after the service is closed. The `expand`/openNode
     paths add `checkOpen` after the cache/LSP; `node` does not. The
     `closeIsInterruptible` test only exercises an open, not a node/get, so this
     path is untested.
   - Suggested fix: Add `checkOpen` after the content revision and before
     `currentExpansion`/the cache merge, and inside `currentExpansion` before
     `store.merge`/cache write. Add a test that blocks a node/get (content
     revision or a cache hook), closes/interrupts, then releases and asserts the
     node call fails and the cache/store was not refreshed.
   - Reproduction: use a large workspace or an injected hook to let `node/get`
     reach `currentExpansion`; start `close` and interrupt it while the node
     thread holds the service lock; release the node; it returns the stored
     expansion and writes the cache after `closed=true`.

6. **The new tests still miss the group/non-incident and discovery-cache cases**

   - Location: `RealGraphServiceTest.kt:302-341` (growth test only checks the
     incident A-B edge; no group or non-incident edge),
     `:157-181` (no-fingerprint test uses a file first resolved as a seed, not a
     neighbour or changed revision), `:375-409` (two-change test does not assert
     the graph's revision), and `EngineSocketServerTest.kt:221-291` (no connected
     client/worst-case hook test; the stubborn test does not assert
     `closeFailure`, only `awaitTermination` false).
   - Problem: A regression that drops the group/non-incident edges, leaves the
     discovery cache incomplete, reports a non-current retry revision, or
     abandons the close under the 5 s hook would leave the suite green. The
     growth test named for agreement only checks one incident edge.
   - Suggested fix: Add the assertions/reproductions from findings 1-5: the
     group and file-group edge in `node/get`/expand/open/dot after growth and for
     an unexpanded neighbour, neighbour-only and changed-revision discovery
     cache tests, the exact graph/current revision in the two-change error, a
     node/get close test, and a connected-client worst-case shutdown test.

### Concur

- The fingerprint-only store resolver, the seed-file discovery cache, and the
  no-fingerprint same-revision ambiguity test are correct for the tested seed
  file (`RealGraphService.kt:396-412`, `:584-600`;
  `RealGraphServiceTest.kt:157-181`).
- `node/get` rehashes the workspace, rejects stale content, compares full
  incident `Edge` values, refreshes stale cache entries, and the growth test
  verifies the incident A-B count/origins across node/get, expand, open, and dot
  (`RealGraphService.kt:202-227`, `:629-648`;
  `RealGraphServiceTest.kt:302-341`).
- `expand`/`openNode` perform the post-hash check after `store.expand`, and the
  deterministic cache-hit hook test asserts `INVALID_GRAPH` after a pre-cache
  edit (`RealGraphService.kt:183-198`, `:278-287`;
  `RealGraphServiceTest.kt:412-439`).
- `ensureClient` checks closed at entry, after the factory, and after
  `initialize`, closes started clients, and adopts under `clientLock`; the
  blocking-factory interrupted-close test asserts no leaked client
  (`RealGraphService.kt:509-541`; `RealGraphServiceTest.kt:441-494`).
- `EngineSocketServer` records close failures, `awaitTermination` returns false
  on failure, and the stubborn/throwing close tests assert the failure and no
  closer thread (`EngineSocketServer.kt:62-67`, `:154-191`;
  `EngineSocketServerTest.kt:221-291`).
- The unified retry loop stages candidates, retries once, and the same-revision
  race and two-change tests preserve the previous dot/status
  (`RealGraphService.kt:290-330`; `RealGraphServiceTest.kt:343-409`).
- `graph/dot` intentionally continues to serve the old graph after a content
  change until a reopen; `node/get`/expand reject stale content, and the failed
  reopen test asserts both (`RealGraphServiceTest.kt:271-299`).
- The socket symlink/FIFO/path, NDJSON, request-id, error-envelope, and FILE/GROUP
  unsupported behavior remain unchanged and covered.
- The authored source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review; the
  handover is assumed green.

### Dissent

- New finding 1: the cached/current expansion must include the entity's group and
  non-incident containment edges; the incident-only `storedExpansion` must not
  be cached as the node's expansion.
- New finding 2: per-file discovery must cover neighbour-discovered files and be
  used on the changed-revision and retry paths, or no-fingerprint ambiguity is
  not deterministic when the LSP is unavailable.
- New finding 3: the final retry error must report the open graph's revision,
  not the last uncommitted attempt revision.
- New finding 4: the shutdown hook must cover the server cleanup worst case (or
  the cleanup must fit the hook), including a connected client and a
  non-cooperative close.
- New finding 5: `node/get` must recheck `closed` before refreshing the cache and
  returning, like `expand`/`openNode`.
- New finding 6: the tests must assert the group/non-incident expansion, the
  neighbour/changed discovery cache, the exact final revision, and the
  connected-client cleanup path.

## Round 6

**Verdict:** Changes requested.

The Round 5 fixes are present in their main paths: `fullEntityExpansion` builds
the entity, file, group, immediate reference neighbours, and neighbour
files/groups with containment edges and feed the cache refresh; `fileEntities`
is exposed and used for neighbour/changed-revision discovery with offline
ambiguity tests; the final retry error uses `graphRevision ?: lastRevision` with
an exact two-change assertion; the hook waits 8 s and the connected-client
stubborn-close test covers the worst case; `node/get` has an extra `checkOpen`
and a blocked-node test; selector validation is central; and the mutable hook is
replaced by `ContentRevisionProvider`. The remaining problems are that the full
entity projection omits the `FILE_REFERENCES`/`GROUP_REFERENCES` aggregation
edges, that the discovery cache is written before the caller's revision
validation (poisoning the per-revision cache on a concurrent edit), and that the
final `checkOpen`/cache-write windows around an interrupted close remain
untested and unguarded.

### Round 5 dispositions

1. **Full entity expansion/group closure — Concur for the file/group/neighbour
   geometry; dissent for the file/group aggregation edges.** `fullEntityExpansion`
   includes the entity, its file/group, immediate reference entities, their
   files/groups, containment edges, and the reference edges among the entity
   set (`RealGraphService.kt:669-725`); `assertContainmentClosure` and the
   unexpanded-neighbour checks cover the group/file closure
   (`RealGraphServiceTest.kt:404-472`). `FILE_REFERENCES`/`GROUP_REFERENCES` are
   omitted (new finding 1).
2. **`fileEntities` discovery cache — Concur for neighbour and changed-revision
   reuse; dissent for cache atomicity.** `ExpansionWithFiles.fileEntities`
   (`ExpansionWithFiles.kt:9`) is cached for every loaded file
   (`RealGraphService.kt:632-633`), checked before LSP in
   `resolveFileSeedAtPath` (`:417-445`), and the neighbour-only and failed-
   candidate tests pass (`RealGraphServiceTest.kt:185-255`). The writes precede
   the caller's revision post-check (new finding 2).
3. **Final retry error — Concur (addressed).** `openSelector` uses
   `graphRevision ?: lastRevision` and `lastActual`
   (`RealGraphService.kt:309-311`), and the two-change test asserts the first
   graph revision (`RealGraphServiceTest.kt:507-541`).
4. **Hook and connected-client cleanup — Concur (addressed).** The hook waits
   8 s (`Main.kt:43-55`) against the 6.5 s cleanup worst case, and
   `connectedClientDoesNotHideCloseFailure` asserts socket removal,
   `awaitTermination` false, `closeFailure`, `service.closed`, and no closer
   thread (`EngineSocketServerTest.kt:294-332`).
5. **`node/get` close recheck — Concur for the entry/currentExpansion checks and
   the blocked-node test; dissent for the final check-to-merge window.**
   `node` rechecks at `:219`, `currentExpansion` at `:648/658/661`, and
   `nodeGetFailsAfterInterruptedClose` blocks the initial revision and asserts
   `INVALID_GRAPH` (`RealGraphServiceTest.kt:580-627`). The merge/cache writes
   follow the last check (new finding 3).
6. **Tests / provider / selector / FILE-GROUP — Concur for the new coverage and
   the immutable provider.** The `ContentRevisionProvider` is injected and used
   by the cache-hit and blocked-node tests
   (`ContentRevisionProvider.kt:8-32`; `RealGraphServiceTest.kt:543-627`);
   `selectorValidationIsConsistentForFreshAndCachedOpens` covers fresh/cached
   blank/invalid selectors (`:257-285`); FILE/GROUP expansion remains explicitly
   unsupported (`:718-743`). The missing assertions are new finding 4.

### New findings

1. **`fullEntityExpansion` omits `FILE_REFERENCES` and `GROUP_REFERENCES`**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:669-725`,
     especially the `referenceEdges` filter at `:712-715` (only `EdgeKind.REFERENCES`)
     and the final `edges` at `:723` (references plus containment only).
   - Problem: The original seed expansion from `ContainmentBuilder` includes the
     `FILE_REFERENCES` and `GROUP_REFERENCES` aggregation edges. The new full
     projection rebuilds only entity `REFERENCES` and `CONTAINS`. When
     `currentExpansion` refreshes a stale cached seed expansion, or `node` builds
     a full expansion for an entity with no cache, the result has no
     file/group aggregation edges, while `store.merge` leaves them and
     `graph/dot` still prints them. The growth test uses A.kt and B.kt in the
     same package (so the original has a `FILE_REFERENCES` A.kt-B.kt) but only
     asserts the entity reference and containment; the file aggregation is lost
     from node/get/expand/open and the test still passes.
   - Suggested fix: Include the snapshot's `FILE_REFERENCES`/`GROUP_REFERENCES`
     edges whose endpoints are the files/groups in the projection (or derive them
     from the reference edges with the same aggregation rules as
     `ContainmentBuilder`), and assert them in the growth and unexpanded-neighbour
     tests. Add a cross-package case for `GROUP_REFERENCES`.
   - Reproduction: open a seed in `A.kt` that references `B.kt` in a different
     file (same package). The initial expansion has `FILE_REFERENCES`
     A.kt-B.kt. Expand B to add the reverse reference, then call
     `service.node(seed.id)`, `service.expand(seed.id, revision)`, and
     `service.open(A.kt#...)`; the returned expansions contain the entity
     reference and `CONTAINS` but no `FILE_REFERENCES`, while
     `service.dot(graphId)` still prints it. A cross-package fixture has the
     same problem for `GROUP_REFERENCES`.

2. **The discovery cache is written before revision validation**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:619-641`
     (`computeEntityExpansion` caches `fileEntities` at `:632-633`) and
     `:417-445` (`resolveFileSeedAtPath` caches at `:443-444`); the callers
     perform their revision post-check afterwards at `:191-199`, `:280-288`,
     `:314-333`, and `:336-357`.
   - Problem: `cacheDiscovery` stores the entities under the old revision before
     `requireRevisionUnchanged`/`changedRevision` runs. If a file changes while
     the expansion/discovery is reading it, the cache is populated with the
     transient/new content under the old revision and `discoveredRevision` is set
     to the old revision. The caller then correctly throws `staleGraph` or
     `ContentChanged`, but the poisoned discovery remains. A later open at that
     revision (for example after the content is reverted, or on a same-revision
     path) can resolve a no-fingerprint seed from the transient entities instead
     of the actual content, selecting a wrong/ambiguous declaration without the
     LSP. The `openRetries` test clears the cache on its retry revision and the
     neighbour/changed tests keep the content stable, so this is untested.
   - Suggested fix: Stage the discovered entities with the expansion and write
     `cacheDiscovery` only after the caller's revision post-check succeeds, or
     revalidate the revision inside `cacheDiscovery` before storing. For the
     changed-revision candidate, cache after `changedRevision` returns null;
     for the ambiguous seed path, verify the revision before keeping the
     discovery. Add a test that edits a file during the compute, asserts the
     open fails, reverts the file, then opens a no-fingerprint selector with the
     LSP failing and expects the reverted content's candidates, not the transient
     ones.
   - Reproduction: open a seed, then start an expand whose `references`/file
     read changes `B.kt`, with the provider/expansion post-check failing. Revert
     `B.kt` to the original text, set the fake LSP to fail, and open
     `B.kt#...` without a fingerprint. The cache at the old revision contains the
     transient B entities written by the failed compute; the selector uses them
     instead of the reverted content.

3. **The final checkOpen and cache/store write remain non-atomic with an interrupted close**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:643-667`
     (`currentExpansion` checks at `:648/658/661`, then `store.merge`/`cache.put`
     at `:663-664`), `:191-199`/`:280-288` (`store.expand` merges before the
     service's post-check), and `:314-333`/`:336-357` (candidate/current open
     commits after a check). `close` sets `closed` under `clientLock` at
     `:243-266`, but those commit sites do not use that lock.
   - Problem: An interrupted close can set `closed` after the last `checkOpen`
     and before the `store.merge`/`cache.put`/candidate assignment. The service
     is then closed while the in-flight call still mutates the store/cache or
     graph revision and returns a result. `nodeGetFailsAfterInterruptedClose`
     blocks the initial `contentRevision` and is caught by the line 219 check; it
     does not exercise the `currentExpansion` check-to-merge window. The
     `closeIsInterruptible` test blocks an LSP call but does not run a `node`
     refresh, and the cache-hit test has no close. A deterministic hook after the
     final check is absent after the mutable hook was removed.
   - Suggested fix: Serialize the final `checkOpen` plus the cache/store/candidate
     write with `clientLock` (or a closing generation checked inside the commit),
     and check inside the `store.expand` compute before returning so
     `GraphStore` does not merge on a closed service. Add a test that blocks a
     node/get after its preliminary check (a test seam/provider hook or a
     `currentExpansion` callback), interrupts close, releases, and asserts no
     refresh and an `INVALID_GRAPH`.
   - Reproduction: use a blocking node/get hook that lets `node` pass line 219
     and enter `currentExpansion`, then close/interrupt before the merge; release
     the node. It still writes the current expansion into the cache and returns
     although `closed` is true. The existing blocked test stops before this
     window.

4. **The new tests still miss the file/group aggregation, neighbour files/groups, cache atomicity, and final close window**

   - Location: `analysis-engine/server/src/test/kotlin/dev/codenb/server/RealGraphServiceTest.kt:404-472`
     (`nodeGetExpandOpenAndDotAgreeAfterEdgeMetadataGrows` and
     `assertContainmentClosure` check only `CONTAINS` and the entity A-B
     reference; A.kt/B.kt are different files, so a `FILE_REFERENCES` exists but
     is not asserted; the neighbour's file/group and a neighbour-neighbour
     reference are not checked), `:185-255` (discovery tests keep content stable
     during the compute), and `:580-627` (the blocked-node test blocks only the
     initial revision).
   - Problem: A regression dropping `FILE_REFERENCES`/`GROUP_REFERENCES`, the
     neighbour A's file/group, or a B-D reference would leave the suite green;
     the poisoned discovery cache and the final check-to-merge close window are
     untested. The growth test named for agreement checks only one incident
     reference and containment.
   - Suggested fix: Add assertions for `FILE_REFERENCES` (cross-file) and
     `GROUP_REFERENCES` (cross-package) to the growth/no-cache tests, assert the
     immediate neighbour's file and group and a neighbour-neighbour reference,
     add the compute-time edit/revert cache test, and add a hook-driven close
     after the final check.

### Concur

- `fullEntityExpansion` now includes the entity, containing file, group,
  immediate reference entities, their files/groups, `CONTAINS` edges, and the
  entity reference edges; `currentExpansion` refreshes the cache with the full
  projection; the growth and unexpanded-neighbour tests assert containment
  closure (`RealGraphService.kt:669-725`; `RealGraphServiceTest.kt:404-472`).
- `ExpansionWithFiles.fileEntities` is populated for every loaded file and
  cached per revision, checked before the LSP in `resolveFileSeedAtPath`, and
  the neighbour-only offline ambiguity and failed-candidate R2 reuse tests pass
  (`ExpansionWithFiles.kt:9`; `RealGraphService.kt:417-445`, `:632-633`;
  `RealGraphServiceTest.kt:185-255`).
- The final retry error uses `graphRevision ?: lastRevision` and the exact graph
  revision is asserted in the two-change test (`RealGraphService.kt:309-311`;
  `RealGraphServiceTest.kt:507-541`).
- The hook waits 8 s and the connected-client stubborn-close test covers the 2 s
  client join plus 4.5 s service close worst case, asserting the socket,
  `awaitTermination` false, `closeFailure`, `service.closed`, and no closer thread
  (`Main.kt:43-55`; `EngineSocketServerTest.kt:294-332`).
- `node` rechecks `closed` at `:219` and `currentExpansion` rechecks at
  `:648/658/661`; the blocked-node interrupted-close test asserts the failure
  and closed status (`RealGraphServiceTest.kt:580-627`).
- Selector validation is centralized and covered for fresh/cached blank and
  invalid files (`RealGraphService.kt:360-370`; `RealGraphServiceTest.kt:257-285`).
- The mutable expansion hook is gone; `ContentRevisionProvider` is an immutable
  injected fun interface used for the initial/post revision checks and the cache
  hit race test (`ContentRevisionProvider.kt:8-32`;
  `RealGraphServiceTest.kt:543-627`).
- FILE/GROUP expansion remains explicitly unsupported and FILE/GROUP `node/get`
  stays on `storedExpansion`; the unsupported test still passes
  (`RealGraphService.kt:188-189`, `:223-227`; `RealGraphServiceTest.kt:718-743`).
- The socket, NDJSON, request-id, error-envelope, and config tests remain
  unchanged and covered; the authored source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review; the
  handover is assumed green.

### Dissent

- New finding 1: the full entity projection must include the
  `FILE_REFERENCES`/`GROUP_REFERENCES` aggregation edges, or `node/get`,
  `expand`, and `open` disagree with `dot` and lose those edges.
- New finding 2: the discovery cache must be written only after the caller's
  revision validation, so a concurrent edit cannot poison the per-revision
  no-fingerprint path.
- New finding 3: the final `checkOpen` and cache/store/candidate commit must be
  atomic with an interrupted close; the current checks leave a write window.
- New finding 4: the tests must assert the file/group aggregation edges, the
  neighbour files/groups and neighbour-neighbour references, the cache
  atomicity, and the final close window.

## Round 7

**Verdict:** Changes requested.

Round 6's three code fixes are present in their main paths: the full entity
projection now carries `FILE_REFERENCES`/`GROUP_REFERENCES`, discoveries are
staged until revision validation, and the observable store/cache/candidate/
discovery writes are serialized with the interrupted-close flag under
`clientLock`. The remaining correctness problems are deeper than those paths:
the file/group aggregation edges copied from `GraphStore` undercount when
different expansions contribute references between the same file or group pair;
the bounded discovery cache can evict the still-open graph revision and break
the no-fingerprint offline path; `status()` can report the service open after
an interrupted close; and the new tests do not exercise the cache-poisoning or
close windows they claim, nor exact dot/count agreement.

### Round 6 dispositions

1. **Full entity aggregation edges — Concur for inclusion; new count/origin
   finding.** `fullEntityExpansion` now adds the snapshot's
   `FILE_REFERENCES`/`GROUP_REFERENCES` whose file/group endpoints are in the
   projection (`RealGraphService.kt:789-809`), and the cross-file/cross-package
   test asserts the file/group nodes, neighbour-neighbour reference, and the
   key set (`RealGraphServiceTest.kt:808-867`). The copied store aggregation
   edges are not reliable counts/origins after multiple expansions (new
   finding 1).
2. **Discovery cache staging — Concur for write ordering; new eviction
   finding.** The only discovery writes now go through
   `commitDiscoveries`/`commitDiscoveriesLocked` after the caller's revision
   check (`RealGraphService.kt:214`, `:310`, `:353`, `:388`, `:481`,
   `:697-715`); the compute and `resolveFileSeedAtPath` paths no longer cache
   before validation. The revision cache can still evict the current graph's
   entries (new finding 2), and the new poisoning test does not exercise the
   pre-fix window (new finding 4).
3. **Atomic final close commits — Concur for the observable store/cache/
   candidate/discovery writes.** `expandThroughCache`, `currentExpansion`, the
   changed-revision candidate, and both discovery commits now check `closed`
   and mutate under `clientLock` (`RealGraphService.kt:351-361`, `:688-693`,
   `:704-707`, `:736-742`); `close` sets `closed` under the same lock
   (`:270-279`), so an interrupted close cannot slip between the final check
   and those writes. The lock order is consistently service `lock` then
   `clientLock` for operations/open/close, and interrupted close takes only
   `clientLock`; I found no cycle. The only remaining unlocked write is the
   currently unused `workspacePaths = paths` at `:389` (`ensureWorkspace` has
   no callers); it is not a current observable failure, but it should be moved
   under the same guarded block if the field is kept. A read-only close race
   remains in `status()` (new finding 3).
4. **New tests — Dissent.** The aggregation-key, neighbour-closure,
   cache-persistence, and node-get close tests were added, but the failed
   compute test checks a revision populated by the initial open rather than the
   transient revision, the commit gate is exercised only through
   `currentExpansion`, and the dot assertions are substring checks rather than
   exact edge/count agreement (new finding 4).

### New findings

1. **File/group aggregation counts and origins are lost when different
   expansions contribute references between the same file or group pair**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:789-809`
     (`fullEntityExpansion` copies store `FILE_REFERENCES`/`GROUP_REFERENCES`
     at `:793-800`), `analysis-engine/core/src/main/kotlin/dev/codenb/core/ContainmentBuilder.kt:43-70`
     (each expansion collapses all entity references between a file/group pair
     into one file/group origin), and
     `analysis-engine/core/src/main/kotlin/dev/codenb/core/GraphStore.kt:142-180`
     (matching origins are merged with `maxOf`, not a union/sum of distinct
     entity contributions).
   - Problem: A per-expansion file/group origin identifies only the file or
     group pair, not the contributing entity pair. When a later expansion adds
     a different entity reference between the same files/groups, its
     file/group edge has the same origin pair and `GraphStore.merge` keeps only
     the maximum count. The store therefore contains both entity `REFERENCES`
     edges but a file/group edge with fewer counts/origins than their
     aggregation. `fullEntityExpansion` copies that undercounted edge, so
     `node/get`, `expand`, and same-revision `open` can report a
     `FILE_REFERENCES` count that does not match the reference edges in the
     same expansion, and `graph/dot` prints the same undercount. The new tests
     only use distinct file/group direction pairs, so they do not expose the
     collision.
   - Suggested fix: Derive file/group aggregation edges from the entity
     `REFERENCES` edges at projection/export time (as `ContainmentBuilder` does
     for a single expansion), using the entity-to-file/group parents; do this
     in `fullEntityExpansion` and `DotWriter` (and any future FILE/GROUP
     `storedExpansion`). Alternatively change the store merge model so
     file/group edges retain distinct entity contributions instead of
     collapsing them into one file-pair origin. Add a test with two source
     entities in one file that each reference a different entity in one target
     file, expand both, and assert the aggregation count/origins and the dot
     label.
   - Reproduction: Put `seed` and `sibling` in `A.kt` and `one` and `two` in
     `B.kt` across two packages. Make `seed` reference `one`, `two`, and
     `sibling`, and make `sibling` reference `one`. Open `seed`, then expand
     `sibling`; call `node(seed.id)`, `expand(seed.id, revision)`, and
     `open(A.kt#app.a.seed)`. The full seed expansion contains
     `one -> seed`, `two -> seed`, `sibling -> seed`, and `one -> sibling`;
     the `FILE_REFERENCES` A.kt-B.kt edge should aggregate the first, second,
     and fourth references (count 3; the third is same-file) but is count 2
     because the sibling expansion's file origin is merged by maximum. The
     cross-package `GROUP_REFERENCES` edge is likewise count 2 instead of 3,
     and `graph/dot` prints `label="2"`.

2. **The bounded discovery cache can evict the current graph revision**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:537-546`
     (`cacheDiscovery` removes `keys.first()` at `:542-543`) and the
     `MAX_DISCOVERY_REVISIONS = 16` bound at `:939`.
   - Problem: Eviction is by oldest insertion and does not protect
     `graphRevision`, the revision whose discoveries the open service is
     expected to reuse. Failed candidate opens `commitDiscoveries` for their
     candidate revision before `selectSeed` fails (`:481`), and repeated edits
     followed by failing selector opens can add 16 newer revision entries while
     the original graph revision stays open. The next new revision then evicts
     the current graph's discovery entries. After the content is reverted to
     that revision, a no-fingerprint selector with the LSP unavailable must
     query the LSP and returns an error (`LSP_FAILURE` for a real LSP;
     `INTERNAL` with the fake's `IllegalStateException`) instead of using the
     cached candidates/ambiguity. `getOrPut` also does not refresh an existing
     revision's insertion order, so a current revision that was first seen
     early remains a prime eviction candidate.
   - Suggested fix: Pin `graphRevision` while choosing a victim (remove the
     oldest revision other than `graphRevision`), or clear retired revisions
     when a new graph revision is adopted. Optionally refresh the insertion
     order on a cache hit/write. Add a test that populates more than
     `MAX_DISCOVERY_REVISIONS` candidate revisions through failed opens, reverts
     to the graph revision, disables the LSP, and asserts the no-fingerprint
     selector still resolves from the cache with the correct ambiguity/seed.
   - Reproduction: Open a valid seed at revision R1. Edit an unrelated file,
     then repeatedly call `open` with a valid `A.kt` path and an unknown
     qualified name so `resolveFileSeedAtPath` caches A.kt at each new revision
     but `selectSeed` fails and the graph stays at R1. After 17 distinct
     revisions, restore the unrelated file to its original content (which
     hashes back to R1), set `FakeGraphLsp.queryFailure`, and call `open` for
     `A.kt` without a fingerprint. The R1 cache entry has been evicted and the
     call returns an error instead of the cached seed/ambiguity result.

3. **`status()` can report the graph open after an interrupted close**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:133-146`.
   - Problem: `status()` checks `closed` once, before computing
     `contentRevision(discoverWorkspace())`. An interrupted `close()` takes
     `clientLock`, not the service `lock`, so it can set `closed` while the
     content hash is running. When the hash returns the same revision,
     `status()` returns `GraphStatus(true, revision)` even though the service
     is closed. The sequential `statusAndGraphChecksAreConsistentAfterClose`
     test cannot observe this. This is the same close-check pattern that was
     added to `node/get` in Round 5, but it is missing here.
   - Suggested fix: Recheck `closed` (under `clientLock`, or at least after
     the hash) before returning an open status; return
     `GraphStatus(false, null)` if the service closed during the call. Add a
     provider-blocked status test analogous to
     `nodeGetFailsAfterInterruptedClose` that interrupts `close` while
     `status()` is hashing and asserts the final status is closed.
   - Reproduction: Open a graph. Use a `ContentRevisionProvider` that blocks
     after the first open. Start `status()` on another thread, wait until the
     provider is entered, start `close()`, interrupt it after the service lock
     wait, and release the provider. `close()` sets `closed` and returns;
     `status()` then returns `GraphStatus(true, revision)`.

4. **The new tests do not exercise the cache-poisoning, close-gate, or exact
   dot/count claims**

   - Location:
     `analysis-engine/server/src/test/kotlin/dev/codenb/server/RealGraphServiceTest.kt:257-310`
     (`failedComputeDoesNotPoisonDiscoveryCache`), `:312-376`
     (`commitGateInterruptionLeavesCacheAndStoreUntouched`), and `:808-877`
     (`fullProjectionIncludesAggregationEdgesAndNeighbourClosure`,
     `edgeBetween`).
   - Problem: The poisoning test edits C.kt to two overloads, reverts it during
     the compute, then asserts the cache at `first.revision`. That revision's
     C.kt discovery was already populated by the initial successful open before
     the edit; the retry at the reverted revision cache-hits the seed
     expansion, so no new discovery is written. The assertion therefore also
     passes with the old premature-cache implementation. The commit-gate test
     blocks only `currentExpansion`; the `expandThroughCache` cache-miss and
     `openChangedRevision` gate sites are untested. The projection test checks
     `stored`/`expanded`/`reopened` sets against each other but only checks
     that dot contains the kind strings; it does not parse dot edges, assert
     file/group counts/origins, assert duplicate-free edges, or cover the
     same-file-pair collision in finding 1. A regression in those paths would
     leave the suite green.
   - Suggested fix: After the failed compute, restore C.kt to the two-overload
     content (revision R2), set the fake LSP to fail, open C.kt without a
     fingerprint, and assert no transient one-overload discovery is used (for
     example, assert the expected ambiguity or an LSP failure rather than a
     selected stale declaration); inspect the R2 cache directly. Add
     gate-interruption tests for a cache-miss `expand` and a changed-revision
     `open`, asserting the graph revision/store/cache/discoveries stay
     untouched and the call returns `INVALID_GRAPH`. Add dot edge parsing and
     count/origin assertions, and the collision fixture from finding 1.
   - Reproduction: Run the current poisoning test against a deliberately
     restored `cacheDiscovery` call in `computeEntityExpansion` and observe it
     still passes. Likewise, move the `store.merge` in `expandThroughCache`
     before its `clientLock`/`checkOpen` and the current gate test still
     passes. Change the file/group count in the projection fixture and the dot
     substring assertions still pass.

### Concur

- `fullEntityExpansion` now includes the entity, containing file and group,
  immediate reference entities, their files/groups, containment edges, entity
  references among the entity set, and the file/group aggregation edges;
  `currentExpansion` refreshes the cache with that projection
  (`RealGraphService.kt:746-810`). The cross-file/cross-package test asserts
  the file/group nodes, all three file/group pairs, the entity and
  neighbour-neighbour references, and equality of the stored/expanded/reopened
  edge sets (`RealGraphServiceTest.kt:808-867`).
- Discovery writes are staged: `computeEntityExpansion` returns
  `fileEntities` without writing, and `commitDiscoveries` is called only after
  the candidate/caller revision checks; the only calls to
  `commitDiscoveriesLocked` are inside the validated open or the clientLock-
  guarded commit (`RealGraphService.kt:214`, `:310`, `:353`, `:388`, `:481`,
  `:697-715`). Neighbour-discovered and failed-candidate reuse tests remain.
- The observable commit sites now serialize the final `checkOpen` with the
  write under `clientLock`, and `close` uses the same lock for `closed` and
  `client`. The `commitGateInterruptionLeavesCacheAndStoreUntouched` test
  asserts no store/cache mutation and an `INVALID_GRAPH` failure for the
  `currentExpansion` path (`RealGraphService.kt:270-279`, `:688-693`,
  `:704-707`, `:736-742`; `RealGraphServiceTest.kt:312-376`). The lock order
  is service `lock` then `clientLock` for operations and normal close;
  interrupted close takes only `clientLock`, and I found no deadlock cycle.
- The test accessors are internal and read-only in production paths; FILE/GROUP
  expansion remains explicitly unsupported and FILE/GROUP `node/get` remains on
  `storedExpansion` (`RealGraphService.kt:150-160`, `:219-248`, `:812-825`;
  `RealGraphServiceTest.kt:913-938`).
- No build, test, formatter, or `just` command was run for this review; the
  handover is assumed green.

### Dissent

- New finding 1: the full projection and dot must not copy undercounted
  file/group aggregation edges from the store; the aggregation must be derived
  from the merged entity references or the store merge must retain distinct
  entity contributions.
- New finding 2: the discovery cache must pin the open graph revision (or clear
  retired revisions) so failed candidate revisions cannot evict the offline
  no-fingerprint cache.
- New finding 3: `status()` must recheck `closed` after its content hash before
  reporting an open graph.
- New finding 4: the new tests must exercise the transient-revision cache
  window, all three commit-gate sites, and exact dot/count/origin agreement,
  including the same-file-pair collision.

**Summary.** Round 7 confirms the headline Round 6 fixes but not their
correctness at the aggregation, eviction, close-read, and test boundaries: the
new full projection exposes a file/group count loss in the store merge, the
bounded discovery cache can discard the current revision, `status()` has no
post-hash close recheck, and the added tests do not fail on the corresponding
regressions.

**Verdict:** Changes requested.

## Round 8

**Verdict:** Changes requested.

Round 7's four fixes are present: `ReferenceAggregation` replaces the stored
file/group edge counts within the projected reference set, the discovery cache
pins and refreshes the open revision, `status()` rechecks `closed` under
`clientLock` after hashing, and the cache-miss/changed-revision gate tests plus
the parsed dot assertions were added. The remaining problem is that the full
entity projection now aggregates only the reference edges whose endpoints are
in `entityIds`, while `DotWriter` aggregates the entire store; a file/group pair
that is included through an immediate neighbour but also has references from a
non-immediate entity is missing from `node/get`/`expand`/`open` and appears in
`dot`. I also found no direct unit coverage for the new aggregation function's
count, fallback, and ordering branches.

### Round 7 dispositions

1. **Aggregation count undercount — Concur for the addressed collision; new
   completeness finding.** `ReferenceAggregation` sums directed entity origins
   by file/group pair, canonicalizes the undirected edge, emits one canonical
   origin per direction, and suppresses same-file/same-group edges
   (`ReferenceAggregation.kt:11-78`); `fullEntityExpansion` and `DotWriter` use
   it (`RealGraphService.kt:803-811`; `DotWriter.kt:13-22`). The
   same-file-pair collision test asserts count 3 and a single directed origin
   for both file and group edges and checks the dot counts
   (`RealGraphServiceTest.kt:1075-1155`). The full projection still uses only
   the projected reference set; see new finding 1.
2. **Discovery cache eviction — Concur (addressed).** The cache is now
   insertion-ordered, a write refreshes an existing revision, a read refreshes
   the accessed revision, and eviction skips `graphRevision`
   (`RealGraphService.kt:540-565`). The 17-failed-open, revert, LSP-disabled
   test verifies the current revision remains usable
   (`RealGraphServiceTest.kt:322-344`).
3. **`status()` close race — Concur (addressed).** The content hash completes
   before a final `closed` check under `clientLock`, returning
   `GraphStatus(false, null)` if close won (`RealGraphService.kt:134-149`). The
   provider-blocked status/close test covers the window
   (`RealGraphServiceTest.kt:346-386`).
4. **New tests — Concur for the listed cases; additional direct tests are
   still missing.** The transient R2 poisoning assertion, cache-miss expand
   gate, changed-revision open gate, existing node gate, parsed dot counts, and
   collision case are present (`RealGraphServiceTest.kt:260-320`, `:389-580`,
   `:1030-1203`). The new core aggregation function itself still has no direct
   unit tests; see new finding 2.

### New findings

1. **The full entity projection aggregates only projected references, so
   file/group edges between included nodes can be missing relative to dot**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:803-811`
     (the `referenceEdges` filter at `:803-806` is passed to
     `ReferenceAggregation` at `:807-811`);
     `analysis-engine/core/src/main/kotlin/dev/codenb/core/ReferenceAggregation.kt:11-39`
     (the aggregation only sees the supplied reference edges);
     `analysis-engine/core/src/main/kotlin/dev/codenb/core/DotWriter.kt:13-22`
     (dot uses every snapshot reference edge).
   - Problem: `fullEntityExpansion` builds `entityIds` from the seed plus the
     endpoints of incident references, then passes only references whose two
     endpoints are in that set. The returned `files`/`groups` maps, however,
     contain every immediate neighbour's file and group. A reference from an
     entity in one included file (but not an immediate neighbour) to an entity
     in another included file therefore contributes no file/group edge, even
     though both file/group nodes are in the expansion. `DotWriter` derives the
     same aggregation from the full store and includes that edge. The result is
     an exact edge-set disagreement between `node/get`, `expand`, same-revision
     `open`, and `dot`, plus undercounted file/group edges when the pair already
     exists. The new collision test does not catch this because `sibling` is an
     immediate neighbour and `one`/`two` are immediate entities; all
     contributing reference endpoints are in `entityIds`.
   - Suggested fix: Resolve the aggregation from all snapshot `REFERENCES`
     edges whose endpoints have file/group nodes in the projection, using a
     node map that includes those outside entities, and filter the resulting
     `FILE_REFERENCES`/`GROUP_REFERENCES` edges to the `files`/`groups` keys
     being returned. Alternatively, explicitly include the contributing
     entities in the expansion (which changes the lazy projection) or define a
     documented local aggregation and make `dot`/tests use the same scope. Add
     a test where a non-neighbour entity in an included file adds the only
     reference between two included neighbour files.
   - Reproduction: Put `seed` in `A.kt`, `b1` and `b2` in `B.kt`, and `c1` and
     `c2` in `C.kt` across separate packages. Make `seed` reference `b1` and
     `c1`, and make `b2` reference `c2`. Open `seed`; open `b2` by selector at
     the same revision so the store contains `b2 -> c2`. `node(seed.id)`,
     `expand(seed.id, revision)`, and `open(A.kt#app.a.seed)` return files
     A/B/C and `FILE_REFERENCES` A-B and A-C, but no B-C edge; `dot` includes
     `FILE_REFERENCES` B-C and `GROUP_REFERENCES` B-C from `b2 -> c2`. The
     A-B/A-C counts for other outside contributors are likewise lower in the
     expansion than in dot.

2. **`ReferenceAggregation` has no direct unit coverage for count, fallback,
   and ordering branches**

   - Location: `analysis-engine/core/src/main/kotlin/dev/codenb/core/ReferenceAggregation.kt:11-84`
     (new core function); the only coverage is indirect through
     `RealGraphServiceTest.kt:1075-1155` and `:1030-1073`.
   - Problem: The new pure aggregation is the source of truth for the edge
     math, but the tests use entities with parent files/groups and reference
     origins with count 1. There is no direct assertion for an origin with
     `count > 1`, the `fileFor` URI fallback when an entity has no parent,
     missing parent groups, a file pair in the same group, or deterministic
     output after shuffled reference input. A regression that replaced `origin.count` with
     `1`/`origins.size`, removed the fallback, or made output order
     input-dependent could still pass the service suite. This matters for the
     ticket's `FILE_REFERENCES`/`GROUP_REFERENCES` count/origin contract even
     though the current integration scenarios pass.
   - Suggested fix: Add a `ReferenceAggregationTest` with a small node graph
     covering same-file suppression, same-group suppression, cross-file and
     cross-group edges, one origin with count greater than one, a parent-less
     entity with a file URI, a missing group, and the same input in two orders;
     assert the exact edge list, counts, origins, and order. Include a
     projected-vs-full reference-set case for finding 1.
   - Reproduction: In a scratch test, change
     `fileOrigins.merge(Directed(fromFile, toFile), origin.count, Int::plus)` to
     merge a constant `1`, or remove the `fileFor` fallback, and run the new
     service tests; the collision fixture still observes one reference per
     origin and all returned entities have parents, so the current tests do not
     distinguish the mutation.

### Concur

- `ReferenceAggregation` correctly sums per directed file/group pair, collapses
  reversed directions into one canonical `Edge`, emits sorted origins/edges,
  and suppresses same-file/same-group/self aggregate edges for the exercised
  collisions (`ReferenceAggregation.kt:11-78`; `RealGraphServiceTest.kt:1075-1155`).
- The discovery cache now pins the open revision on eviction and refreshes on
  writes/reads; the 17-failed-open/revert/disabled-LSP test verifies that the
  current no-fingerprint path survives candidate churn
  (`RealGraphService.kt:540-565`; `RealGraphServiceTest.kt:322-344`).
- `status()` performs the final closed check after hashing under `clientLock`,
  and the provider-blocked status/close test asserts the closed result
  (`RealGraphService.kt:134-149`; `RealGraphServiceTest.kt:346-386`).
- The cache-miss expand, changed-revision open, and node refresh interruption
  tests all assert `INVALID_GRAPH` and no store/cache mutation; the added dot
  helper parses edges and checks counts, duplicate keys, and no self edges
  (`RealGraphServiceTest.kt:389-580`, `:1167-1203`).
- Test helpers use `CountDownLatch`/timeouts and join both threads; no build,
  test, formatter, or `just` command was run for this review; the handover is
  assumed green.

### Dissent

- New finding 1: the full entity projection must aggregate references from
  included files/groups even when the contributing entity is outside
  `entityIds`, or the projection must not claim to include the corresponding
  file/group aggregation edges while `dot` does.
- New finding 2: `ReferenceAggregation` needs direct unit coverage for origin
  counts, fallback/missing parent handling, same-group suppression, and
  deterministic ordering.

**Summary.** Round 8 verifies the three Round 7 code fixes and the requested
regression tests, but the new aggregation implementation is complete only for
references whose endpoints are immediate neighbours: `fullEntityExpansion`
drops or undercounts file/group edges contributed by non-neighbour entities in
included files, while `DotWriter` includes them. The aggregation class also
lacks direct tests for several count/fallback/order branches.

**Verdict:** Changes requested.

## Round 9

**Verdict:** Changes requested.

Round 8's two findings are largely addressed: `fullEntityExpansion` now
aggregates from the full snapshot reference set and filters the resulting
file/group edges to the projected nodes, the non-neighbour-in-included-file
fixture agrees with dot, `discoveredForTest` is non-mutating, and
`ReferenceAggregationTest` covers the requested count/fallback/suppression/
determinism branches. The remaining scope problem is narrower: because the
filter is applied to the **resulting** file/group edges only, a
`GROUP_REFERENCES` edge can include references whose **files** are outside the
projection as long as their groups are included. That group edge then has no
corresponding `FILE_REFERENCES` edge in the expansion and is not derivable from
the expansion's file edges, while dot contains the outside file edge. The new
projection test uses `b2`/`c2` whose files are included and therefore does not
exercise this case.

### Round 8 dispositions

1. **Full projection aggregation from included files — Concur for the
   non-neighbour entity case; new group-scope finding.** The projection now
   passes every snapshot `REFERENCES` edge to `ReferenceAggregation`, filters
   `FILE_REFERENCES` by `files.keys`, and filters `GROUP_REFERENCES` by
   `groups.keys` (`RealGraphService.kt:808-821`). The new fixture opens a
   non-neighbour `b2`, asserts the aggregation B-C edge and no projected
   b2-c2 entity edge, and compares the aggregation edges with dot
   (`RealGraphServiceTest.kt:1226-1311`). A group edge can still include
   references whose files are outside the projection (new finding 1).
2. **`ReferenceAggregation` direct tests — Concur (addressed).**
   `ReferenceAggregationTest` covers multiple references/counts, reversed
   origins, same-file and same-group suppression, a parent-less fallback, a
   missing group, cross-file/cross-group output, and deterministic results
   after reversed/shuffled input (`ReferenceAggregationTest.kt:12-123`). No
   separate test finding remains for the exercised behavior; the new fixture
   for finding 1 should be added to the service projection test.

### New findings

1. **The full projection can include `GROUP_REFERENCES` contributions from
   files outside the projected file set**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:808-821`,
     especially the filter at `:815-820`; the aggregation itself is computed
     over all snapshot references at `:812-814`.
   - Problem: `ReferenceAggregation` derives both file and group counts from
     all references. `fullEntityExpansion` then filters file edges by
     `files.keys` and group edges by `groups.keys`. A reference between two
     files that are outside the projection can therefore contribute to a
     `GROUP_REFERENCES` edge between two included groups even though its
     `FILE_REFERENCES` edge is filtered out. The returned expansion has a
     group edge whose count includes hidden outside-file references and no
     corresponding file edge, so `GROUP_REFERENCES` is no longer the
     aggregation of the `FILE_REFERENCES` edges in that projection. `dot`
     includes the outside file edge as well. The scope is “both group endpoints
     included”, not the stated “parent files/groups in the projected set”. The
     new projection test includes B/C files through b1/c1, so it does not
     cover this.
   - Suggested fix: Filter the reference set (or the origin endpoints) by the
     projected files/groups before aggregation, or derive group edges from the
     already-filtered file aggregation edges so their counts/origins match the
     returned file edges. If full-group-including-outside-files facts are
     intentional, document that scope and make the test compare only the
     corresponding group subset rather than claiming file/group aggregation
     coherence. Add a fixture with an outside file pair in included groups.
   - Reproduction: Put `seed` in `A.kt` and `helper` in `A2.kt` (both package
     `app.a`), and `b1` in `B.kt` and `helper2` in `B2.kt` (both package
     `app.b`). Make `seed` reference `b1` and `helper` reference `helper2`.
     Open `seed`, then open `helper` by selector at the same revision. The
     seed projection contains files A/B and groups app.a/app.b, with
     `FILE_REFERENCES` A-B count 1. `ReferenceAggregation` over all references
     sees the outside A2-B2 reference, so the projected
     `GROUP_REFERENCES` app.a-app.b has count 2, while the expansion contains
     no A2/B2 nodes or A2-B2 file edge. `dot` contains the A2-B2 file edge and
     the group count 2.

### Concur

- `ReferenceAggregation` correctly sums directed origin counts, canonicalizes
  both directions into one undirected edge, sorts origins/edges, and
  suppresses same-file/same-group/self outputs; the unit table covers
  count/origin arithmetic, fallback and missing-group behavior, and
  deterministic output (`ReferenceAggregation.kt:11-78`;
  `ReferenceAggregationTest.kt:12-123`).
- `ContainmentBuilder` now routes its aggregation through the shared function
  and preserves entity/file/group containment and reference invariants; the
  existing ContainmentBuilder cases for cross-file, same-file, same-group, and
  grouping determinism remain covered (`ContainmentBuilder.kt:41-70`).
- The full projection test for a non-neighbour entity in an included file
  asserts the aggregation edge, the absence of the outside entity reference in
  the projection, the exact file/group counts/origins, and dot equality
  (`RealGraphServiceTest.kt:1226-1311`).
- `peekDiscovery` is non-mutating and is used by `discoveredForTest`; the
  eviction-order test shows an inspection does not refresh an older revision
  (`RealGraphService.kt:557-570`);
  `RealGraphServiceTest.kt:518-544`).
- The status close check, discovery-current pinning, and commit-gate paths
  remain covered by the Round 8 tests; no build, test, formatter, or `just`
  command was run for this review; the handover is assumed green.

### Dissent

- New finding 1: the projection's group aggregation must use the same
  projected file scope as its file aggregation (or explicitly document the
  broader group scope); currently an outside-file reference can inflate a
  returned `GROUP_REFERENCES` edge without any corresponding returned
  `FILE_REFERENCES` edge.

**Summary.** Round 9 confirms the Round 8 fixes and the new aggregation test
matrix, but the full projection still has one scope inconsistency: group
aggregation filters only group endpoints after aggregating every store
reference, so references between files outside the projection can contribute
to a returned group edge with no matching file edge. **Verdict: Changes
requested.**

## Round 10

**Verdict:** Approved.

Round 9's group-scope finding is addressed. `ReferenceAggregation` now accepts
an optional projected-file scope and evaluates both `fromFile` and `toFile`
against it before deriving either file or group origins
(`ReferenceAggregation.kt:12-43`). `fullEntityExpansion` passes the projected
`files.keys` scope and still applies its final endpoint filters
(`RealGraphService.kt:812-825`). `ContainmentBuilder` continues to call the
shared aggregation without a scope, preserving whole-expansion behavior
(`ContainmentBuilder.kt:69`), and `DotWriter` remains global
(`DotWriter.kt:13-22`). The outside-file fixture asserts that the projection
has only the included file/group aggregation, that its group edge is derivable
from its file edges, and that dot still contains the outside file and group
counts (`RealGraphServiceTest.kt:1313-1371`). The non-neighbour-in-included-file
fixture still passes (`RealGraphServiceTest.kt:1226-1311`).

### Round 9 disposition

1. **Group aggregation from outside projected files — Concur (addressed).**
   The scope check occurs before both aggregation paths, so a reference with
   either file outside `files.keys` contributes neither a
   `FILE_REFERENCES` nor a `GROUP_REFERENCES` edge
   (`ReferenceAggregation.kt:26-38`). `fullEntityExpansion` passes the
   projected file ID set (`RealGraphService.kt:812-817`) and keeps the final
   endpoint filters (`:818-825`). The new outside-file test asserts the
   projected group edge count/origins, verifies that the outside file and
   entities are absent, verifies the group count is derivable from the
   returned file edges, and verifies dot still sees the outside contribution
   (`RealGraphServiceTest.kt:1313-1371`). The non-neighbour included-file case
   remains covered by `:1226-1311`.

### Concur

- The optional `fileScope` is applied symmetrically to both origin files before
  either file or group accumulation, so no group edge can outlive its
  underlying projected files (`ReferenceAggregation.kt:26-38`).
- The projection test with `A2.kt`/`B2.kt` outside the seed file set asserts no
  A2/B2 nodes, a projected group edge count 1, and dot's global group count 2
  with the outside file edge (`RealGraphServiceTest.kt:1313-1371`).
- `assertGroupAggregationDerivable` sums the returned file edges for the
  corresponding file/group parents and matches each returned group edge count,
  ruling out the orphan group edge from Round 9 (`RealGraphServiceTest.kt:1373-1392`).
- The non-neighbour entity in an included file still contributes to the
  projected file/group aggregation, and the aggregation set agrees across
  `node/get`, `expand`, and same-revision `open`
  (`RealGraphServiceTest.kt:1226-1311`).
- `ReferenceAggregationTest` covers directed counts/origins, canonicalization,
  same-file/same-group suppression, fallback/missing groups, cross-file/
  cross-group edges, and deterministic output under reversed and shuffled
  input (`ReferenceAggregationTest.kt:12-123`).
- No duplicate/self edges were found in the new paths: `buildEdges` groups by
  canonical `EdgeKey`, suppresses equal file/group endpoints, and returns one
  sorted origin per direction; the projection/dot tests also assert no self
  edges and unique dot edge keys.
- The file/group aggregation filter and test use no wall-clock sleeps; the
  eviction/status/gate tests retain bounded latches/timeouts. No build, test,
  formatter, or `just` command was run for this review; the handover is
  assumed green.

### Dissent

No unresolved correctness finding remains for Round 9's scope issue or the
new aggregation/test changes.

**Summary.** Round 10 verifies that projected-file scope now filters both
origin endpoints before file/group aggregation, that `fullEntityExpansion`
uses that scope while `ContainmentBuilder` and dot retain their whole-graph
semantics, and that the outside-file and non-neighbour fixtures assert the
intended counts, origins, derivability, and global dot behavior.

**Verdict:** Approved.
