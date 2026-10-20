## Round 1

**Verdict:** Changes requested.

The socket envelope is largely sound: NDJSON framing, verbatim request-id echo,
standard JSON-RPC error codes, frozen `api` reuse, and the server/host boundary
are in place. The contract is not yet ready for ticket 010 because `node/get`
returns a shadow expansion, graph lifecycle errors are inconsistent, the
revision is not a content revision, protocol and application errors are
conflated, the seed schema and overload selection are ambiguous, FILE/GROUP
expansion is a no-op, and the dot writer/config plumbing cross module and
ticket boundaries.

### Findings

1. **`node/get` returns a shadow `Expansion` without graph id or revision**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/JsonRpcHandler.kt:114-121`
     (`getNode`), `analysis-engine/server/src/main/kotlin/dev/codenb/server/GraphService.kt:23-27`
     (`StoredNode`), compared with
     `analysis-engine/api/src/main/kotlin/dev/codenb/api/Expansion.kt:6-12`.
   - Problem: The wire result is built by hand as `{node, neighbours, edges}`,
     which is `Expansion` minus `graphId` and `revision`. A client cannot verify
     which graph the node belongs to, cannot tell which revision the snapshot
     is at, and ticket 010 has to implement a second DTO for this method. This
     is exactly the shadow graph result the frozen contract is meant to prevent.
   - Suggested fix: Make `GraphService.node` return `Expansion` (with the
     current graph revision) and return that value directly from
     `JsonRpcHandler`, so `node/get` and `node/expand` share one frozen shape.
     If a distinct node view is intended, it must be defined in `api` rather
     than assembled in the handler; prefer `Expansion` because `api` is frozen.
     Add a socket test that decodes `node/get` as `Expansion`.

2. **Graph lifecycle errors are inconsistent, and `initialize` does not expose open state or revision**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:151-164`
     (`dot`), `:130-149` (`node`), `:109-118` (`expand`), and
     `analysis-engine/server/src/main/kotlin/dev/codenb/server/JsonRpcHandler.kt:68-72`
     (`initialize`).
   - Problem: `expand` rejects calls while `graphRevision == null` with
     `invalid-graph` ("Graph is not open"). `node` and `dot` do not perform that
     check. Before any `graph/open`, `graph/dot` returns a valid DOT document
     for an empty graph and `node/get` returns `unknown-node`; a client cannot
     distinguish "the graph is not open" from "this node does not exist".
     `initialize` advertises a graph id but no open flag and no revision, even
     though `node/expand` requires a revision. A renderer reattaching to the
     reused daemon therefore has to guess whether a graph is open and which
     revision to send. The empty-dot behavior may be a deliberate choice, but
     it is not documented and contradicts the other two methods.
   - Suggested fix: Add one lifecycle check used by `node`, `dot`, and `expand`
     so an unopened graph consistently returns `invalid-graph` (or explicitly
     document that `graph/dot` intentionally serves an empty graph and exempt
     it). Add the current `revision` (nullable) and/or an open flag to the
     `initialize` result so ticket 010 can reattach without guessing. Pin the
     behavior with a socket test: `initialize`, then `graph/dot` and `node/get`
     before `graph/open`.

3. **`Expansion.revision` is pinned to the first seed expansion and can serve stale data**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:74-90`
     (`open` reuses `currentRevision`), `:109-118` (`expand` compares against
     it), `:288-309` (`computeEntityExpansion` overwrites the computed revision
     with the requested one at `:308`), `analysis-engine/core/src/main/kotlin/dev/codenb/core/GraphStore.kt:36-47`
     (cache hit returns before `compute`), and
     `analysis-engine/core/src/main/kotlin/dev/codenb/core/ExpansionCache.kt:8-12`
     (cache key includes the revision).
   - Problem: `graphRevision` is set once from the first entity expansion's
     `NeighbourExpander` hash and never changes. Every later expansion is
     relabelled with that revision, and `graph/open` reuses the same cache key.
     On a second open after a file edit, `GraphStore.expand` finds the old
     cached expansion at `(graph, node, oldRevision)` and returns it without
     invoking or using the newly computed `computed` value. New results can
     also be merged under the old revision (for a different seed), so the
     returned `revision` does not identify the content the expansion came from.
     This contradicts `docs/architecture.md:155-160` ("content revision") and
     `:143-147` (no stale result after a hash mismatch).
   - Reproduction: start the daemon on a workspace with a Kotlin file, open a
     seed entity (revision R1), edit the seed file to add a new reference while
     keeping the declaration header/identity unchanged, then call `graph/open`
     again with the same `file#qualifiedName`. The response is the old
     expansion labelled R1 and does not contain the new reference until the
     daemon is restarted. A fake-LSP test that changes the text between opens
     reproduces the same behavior without a live LSP.
   - Suggested fix: Make the revision a true graph/session content revision.
     On `graph/open`, compute it from the content that contributes to the
     graph, update `graphRevision` when it changes, and ensure the store uses
     the new key; do not overwrite a computed expansion's revision with the
     client-supplied value. If the revision is intentionally only a session
     epoch, document that and reject stale values with a distinct error so the
     renderer knows to re-open. Add the fake-LSP test above.

4. **Protocol errors and application errors are conflated, and every request error is double-delivered**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/JsonRpcHandler.kt:90-97`
     (`EngineException`/`Throwable` handling), `:216-244` (`failure` emits
     `cint/error` and a response), `:288-300` (`invalidParams` sets
     `notify = true`), and `:81-87` (method-not-found carries an
     `EngineError(INVALID_REQUEST)` data payload).
   - Problem: `invalidParams` is a protocol error (`-32602`), but it also emits
     a `cint/error` notification. Every `EngineException` (unknown node,
     invalid graph, unsupported capability, LSP failure, workspace error) and
     every unexpected `Throwable` raised while handling a request with an id
     produces both a `cint/error` notification and an error response. A client
     that reads one line per request receives the notification first and must
     skip and correlate it; a malformed request therefore looks like a
     recoverable engine failure. Method-not-found also embeds an application
     `EngineError` with code `invalid-request`, mixing the JSON-RPC protocol
     layer with the engine error layer. The architecture reserves `cint/error`
     for recoverable failures, not protocol errors.
   - Suggested fix: Keep `-326xx` protocol errors as a single JSON-RPC error
     response with no `cint/error` and no `EngineError` data payload (or a
     dedicated protocol-error code). Keep request-scoped application errors as
     one `-32000` response with `EngineError` in `data`. Emit `cint/error` only
     for id-less/asynchronous/recoverable failures, or explicitly document that
     a request may produce two lines and that clients must correlate by `id`
     and `params.requestId`. Make `invalidParams` `notify = false`. Update
     `analysis-engine/server/src/test/kotlin/dev/codenb/server/EngineSocketServerTest.kt:271-305`,
     which currently encodes the double-delivery.

5. **The `graph/open` seed schema and overload selection are ambiguous**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/JsonRpcHandler.kt:123-160`
     (`parseSeed` accepts `selector`, `seed` string, `seed` object, and
     top-level fields with silent precedence) and
     `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:232-243`
     (`minByOrNull { it.id.value }`); model identity at
     `docs/neighbour-model.md:43-56`.
   - Problem: `graph/open` accepts four different seed shapes, silently prefers
     `selector` over `seed` over top-level fields, and ignores extra members.
     Ticket 010 has no single request schema to implement, and ticket 009 cannot
     describe its selector. More seriously, a `file#qualifiedName` selector can
     match multiple overloads; `minByOrNull` silently picks one, which is the
     overload collapse the model forbids. The same qualified name can also
     select a different overload on a later call after overloads are added or
     signatures change.
   - Suggested fix: Define one seed schema (for example `{file,
     qualifiedName, signatureFingerprint?}` or `{nodeId}`), reject ambiguous or
     extra forms, and when a selector matches multiple entities return
     `invalid-request` with the candidate fingerprints rather than choosing
     one. Add socket tests for each accepted and rejected shape, and pin the
     request-id edge cases (`0`, empty string, `null`) and the
     `initialize`/`graph/open`/`node/get` envelopes while the contract is
     fresh.

6. **`node/expand` on FILE and GROUP nodes is a no-op snapshot projection while all node kinds are advertised**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:121-126`
     (FILE/GROUP branch) and `:311-324` (`storedExpansion`); model at
     `docs/neighbour-model.md:78-95` and `:116`.
   - Problem: `storedExpansion` only re-projects edges already in the store. It
     never computes the model's file expansion (entities in the file, its
     group, files connected by entity references) or group expansion (files and
     entities in the group, connected groups). Yet `GraphInfo.nodeKinds`
     advertises all three kinds (`RealGraphService.kt:62-63`) and the model says
     a node returned as a neighbour is expandable. A renderer that navigates
     from the seed to its file and expands it sees nothing new, even when the
     file contains other entities.
   - Reproduction: put two entities in one file, open a seed that references
     nothing in that file, then expand the returned file node. The sibling
     entity is absent because the first expansion only kept the referenced
     entity, not every entity in the file.
   - Suggested fix: Implement FILE and GROUP expansion in `core`/`frontend`
     using the discovery and aggregation facts, and call it from
     `RealGraphService`. If that is out of scope for 008, return
     `unsupported-capability` for FILE/GROUP expansion and advertise only
     `ENTITY` in `GraphInfo.nodeKinds` until it exists. Add the two-entity test.

7. **Broad exception mapping labels server invariants as client errors or LSP failures**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:354-398`,
     especially `:383-392`; examples at
     `analysis-engine/core/src/main/kotlin/dev/codenb/core/GraphStore.kt:56-64`
     (`require`) and
     `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:54`
     (`IllegalStateException`).
   - Problem: `guarded` maps every `IllegalArgumentException` to
     `invalid-request` and every `IllegalStateException` to `lsp-failure`.
     `IllegalArgumentException` is also thrown by `require` in `Node`, `Edge`,
     `GraphStore`, `ContainmentBuilder`, and `EntityIdentity`; those are
     server-side invariant failures, not malformed client requests.
     `IllegalStateException` also covers "Seed entity is not present in its
     file", which is an internal consistency failure, not an LSP failure. The
     renderer will map these to the wrong HTTP status and recovery path.
     `IOException` is also mapped to `workspace-error`, which is wrong for
     unwrapped LSP transport I/O.
   - Suggested fix: Catch `LspException`, `LspUnsupportedCapabilityException`,
     and `WorkspaceException` explicitly at the service boundary and map those;
     map invariant failures from the store/frontend to `internal`; keep client
     parameter validation in `JsonRpcHandler`. Add a test that a simulated
     `IllegalStateException` or an invalid `Expansion` returns `internal`, not
     `lsp-failure`/`invalid-request`.

8. **The dot writer lives in the server host, so ticket 009 cannot reuse it**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/DotWriter.kt:10-35`;
     ticket 009 at `docs/tasks/009-cli-and-dot-export.md:23-24`; layout at
     `docs/architecture.md:49-53`.
   - Problem: `DotWriter` is a pure graph export, but it is in
     `analysis-engine/server`, which is a host. Ticket 009's CLI is a separate
     host in `analysis-engine/cli`; it cannot depend on `server` without a
     host-to-host dependency. The likely outcomes are a duplicate writer or two
     dot formats that drift. The architecture assigns dot output to the CLI,
     while the server needs it only as a transport result.
   - Suggested fix: Move the writer to `core` (it uses only `api` types and the
     JDK) or to a small shared engine module, and have both `server`'s
     `graph/dot` and ticket 009's CLI call it. Keep the server result a string;
     do not add Ktor or renderer concerns.

9. **`--config` workspace scope and `followSymlinks` are loaded but not applied to expansion**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:265-286`
     (`ensureClient` discovers with the config and discards the result),
     `:299-307` (pipeline call),
     `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:103-107`
     (re-discovers with frontend defaults), and
     `analysis-engine/server/src/main/kotlin/dev/codenb/server/EngineConfig.kt:98-108`
     (config fields).
   - Problem: `config.workspace.include`, `config.workspace.exclude`, and
     `config.graph.followSymlinks` never reach the expansion path.
     `ensureClient` runs `WorkspaceFiles.discover` with them and throws the
     result away; `NeighbourExpander.Context` re-discovers with
     `frontend.include`/`frontend.exclude` and the default symlink policy.
     `resolveFileSeed` also checks only `frontend.handles`, not the configured
     include/exclude. An excluded file can therefore appear as a neighbour, a
     narrowed include has no effect, and `--config` does not honor the
     architecture's workspace scope for graph results.
   - Reproduction: configure `workspace.exclude: ["**/Ignored.kt"]`, have the
     seed reference an entity in `Ignored.kt`, and open the seed; the excluded
     entity is returned. The same applies to a configured include that excludes
     a referenced directory.
   - Suggested fix: Keep the configured workspace file set (or pass the config)
     into `NeighbourExpander`/`ContainmentPipeline` and `resolveFileSeed`, and
     use it for `Context.file`, `fileForLocation`, and seed validation. Add a
     fake-LSP test with an excluded referenced file. If this is deferred, it
     needs a named follow-up ticket because `--config` is in 008's scope.

### Concur

- The frozen `api` wire format is untouched: `git status`/`git diff` show no
  changes under `analysis-engine/api`. The server reuses `Node`, `Edge`,
  `Expansion`, `GraphInfo`, `EngineError`, `NodeKind`, `EdgeKind`,
  `GroupingKind`, `PROTOCOL_VERSION`, and `GraphJson`
  (`JsonRpcHandler.kt:3-17`, `RealGraphService.kt:3-14`); there is no renamed
  kind or shadow error enum.
- `initialize` reports the required identity through `GraphInfo`: protocol
  version, root, LSP id, grouping kind, node kinds, and edge kinds, plus the
  socket path (`JsonRpcHandler.kt:68-72`, `RealGraphService.kt:56-65`). The
  kinds are in enum order and pass `GraphInfo`'s validation.
- NDJSON framing is correct: one `GraphJson.encode(output)` plus a newline per
  object, UTF-8, a per-client writer, and `readLine` handling for split reads
  (`EngineSocketServer.kt:99-133`; tests at
  `EngineSocketServerTest.kt:51-86`). `graph/dot` is returned as a JSON string,
  so its newlines are escaped inside one line.
- Request ids are echoed verbatim for strings, numbers, and null
  (`JsonRpcHandler.kt:36-40`, `:206-214`, `:302-304`); `0` and the empty string
  also pass `validId`, although the tests only pin string/number/null.
  `id: null` is answered with `id: null`, which JSON-RPC 2.0 permits.
- Standard error codes are used correctly: parse `-32700`, invalid request
  `-32600`, method not found `-32601`, invalid params `-32602`, internal
  `-32603`, application `-32000` (`JsonRpcHandler.kt:315-322`). The error
  `data` is the frozen `EngineError` with the lowercase serial names.
- `node/expand` returns a raw `Expansion` containing graph id, revision,
  requested node, neighbours, and edges (`JsonRpcHandler.kt:107-112`);
  `graph/open` composes `GraphInfo` and `Expansion`; `graph/dot` is a plain
  transport string. There is no browser, HTTP, or layout concern in these
  results.
- Error-code coverage is complete: the implementation emits
  `invalid-request`, `unknown-node`, `invalid-graph`, `unsupported-capability`,
  `lsp-failure`, `workspace-error`, and `internal` through the frozen enum.
  Findings 4 and 7 concern which layer gets which code, not missing codes.
- The module boundary is respected: `server` is a host, uses JDK NIO/IO for the
  socket, and has no Ktor, HTTP, WebSocket, SSE, tree-sitter, or second
  analysis backend. `core`, `frontend`, and `api` contain no socket or
  transport types. Finding 8 is about the dot writer's future reuse, not a
  current leak into core.
- Socket lifecycle and permissions are implemented: path derivation from
  `XDG_RUNTIME_DIR`/uid/root/LSP, owner-only directory and socket, stale-socket
  cleanup, refusal to clobber a live daemon or non-socket file, and socket
  removal on shutdown (`SocketPaths.kt`, `EngineSocketServer.kt:39-59`,
  `:135-158`). The tests cover permissions, stale/live/non-socket cases, and
  shutdown.
- Concurrent clients are isolated: each connection has its own reader/writer
  and handler thread, while `RealGraphService` serializes store and LSP access
  with a fair lock (`EngineSocketServer.kt:92-133`,
  `RealGraphService.kt:51`, `:99-149`). The concurrency test asserts no
  cross-talk.
- `shutdown` returns a result before the socket is removed, and the cleanup
  joins client threads before deleting the path (`EngineSocketServer.kt:66-73`,
  `:135-158`; test at `EngineSocketServerTest.kt:180-196`).
- The new source and test files contain no comments.
- No build, test, formatter, or `just` command was run for this review; the
  green handover is assumed.

### Dissent

- Finding 1: `node/get` must return `Expansion` or another frozen type, not a
  hand-built `{node, neighbours, edges}` shadow.
- Finding 2: unopened-graph behavior must be consistent across `node`,
  `dot`, and `expand`, and `initialize` must let a reattaching client know the
  open state and revision.
- Finding 3: `Expansion.revision` must identify the content it was computed
  from; a re-open after a content change must not serve the old cached
  expansion.
- Finding 4: protocol errors must not emit `cint/error` or carry engine
  application errors; request-scoped application errors must not be
  double-delivered without an explicit contract.
- Finding 5: the seed request schema must be singular, and a selector that
  matches multiple overloads must not silently choose one.
- Finding 6: FILE/GROUP expansion must either implement the model's neighbours
  or be explicitly unsupported and removed from the advertised node kinds.
- Finding 7: invariant failures must map to `internal`, not `invalid-request`
  or `lsp-failure`.
- Finding 8: the dot writer must be usable by ticket 009 without a host-to-host
  dependency.
- Finding 9: `--config` workspace include/exclude and `followSymlinks` must be
  applied to the expansion that produces the graph.

## Round 2

**Verdict:** Changes requested.

Round 1 is substantially addressed: `node/get` now returns a frozen
`Expansion`, graph lifecycle checks are shared, selector opens compute a
workspace content revision, error layers are separated, the seed schema is
canonical, FILE/GROUP expansion is explicitly unsupported, invariant failures
map to `internal`, the dot writer is in `core`, and config reaches expansion.
Three new contract problems remain: an open commits the new workspace, revision,
and store before its first expansion succeeds; ambiguous-seed candidates are
encoded as comma-joined strings that cannot be parsed; and `GraphInfo.nodeKinds`
no longer describes the FILE/GROUP nodes that expansions still contain. One
Round 1 finding is only partially addressed: content freshness is limited to
selector opens, so `nodeId` opens and `initialize` status can still serve a
stale revision, and a retired revision maps to `invalid-request`.

### Round 1 finding disposition

- **Finding 1 — Concur (addressed).** `GraphService.node` returns `Expansion`
  (`GraphService.kt:40-43`), the handler returns it directly
  (`JsonRpcHandler.kt:70`), and the socket test decodes it as `Expansion` with
  graph id and revision (`EngineSocketServerTest.kt:564-580`). The shadow
  `StoredNode` is gone.
- **Finding 2 — Concur (addressed).** `requireOpen()` is used by `expand`,
  `node`, and `dot` (`RealGraphService.kt:148`, `:177`, `:191`, `:437-439`),
  and `initialize` reports `open` and `revision`
  (`JsonRpcHandler.kt:92-99`). The before-open behavior is pinned by
  `EngineSocketServerTest.kt:420-471` and `RealGraphServiceTest.kt:22-53`,
  `:286-299`. The new open-atomicity finding below concerns failures during an
  open, not the unopened state.
- **Finding 3 — Dissent (partially addressed).** Selector opens now refresh the
  workspace set, compute `contentRevision`, and reset the store and grouping
  on change (`RealGraphService.kt:220-233`, `:346-377`); the Round 1 stale-cache
  reproduction is fixed by `RealGraphServiceTest.kt:148-172`. However:
  `openNode` still uses the cached revision and cached workspace paths without
  refreshing (`:208-218`); `status()` reports that cached revision
  (`:121-124`); and `RealGraphServiceTest.kt:175-196` explicitly asserts that a
  `nodeId` open uses the cache without querying the LSP. After a workspace edit,
  `initialize` can report `open=true` with the old revision, and
  `graph/open {nodeId}` returns the old expansion. The only freshness path is a
  selector open, which is not stated in the contract; ticket 010 can reasonably
  use a node id for an open or a reattach. In addition, a retired revision maps
  to `invalid-request` (`:149-155`), the same application code as a malformed
  request, so 010 cannot map it to a "re-open the graph" response. Refresh on
  `nodeId` opens/status, or document `nodeId` as a cache-only resume and
  require selector opens for freshness; map a retired content revision to
  `invalid-graph` with the current revision in details. Add a test that edits
  the workspace and then calls `initialize`/`open {nodeId}`.
- **Finding 4 — Concur (addressed).** Protocol failures are single `-326xx`
  responses with no `data` and no `cint/error`
  (`JsonRpcHandler.kt:79-90`, `:211-221`, `:256`); application failures are a
  single `-32000` response with `EngineError` data
  (`:193-209`); `cint/error` is emitted only for id-less application failures
  (`:207-209`). Tests at `EngineSocketServerTest.kt:136-171`, `:347-418`.
- **Finding 5 — Concur (addressed).** The seed schema is now canonical:
  `{nodeId}` or `{file, qualifiedName, signatureFingerprint?}` with unknown
  fields rejected (`JsonRpcHandler.kt:117-137`); multiple matches without a
  fingerprint return an error with candidates (`RealGraphService.kt:296-312`);
  `RealGraphServiceTest.kt:116-146` covers ambiguity and fingerprint selection.
  The candidate encoding has a new problem (new finding 2).
- **Finding 6 — Concur (addressed as scoped).** `node/expand` and
  `open {nodeId}` return `unsupported-capability` for FILE/GROUP
  (`RealGraphService.kt:158-160`, `:208-214`); `GraphInfo.nodeKinds` is
  `[ENTITY]` (`:108-117`); `RealGraphServiceTest.kt:198-222` covers it; and
  `docs/tasks/016-file-and-group-expansion.md` records the follow-up. The
  deferral is explicit. `GraphInfo.nodeKinds` semantics are still a new
  finding (new finding 3).
- **Finding 7 — Concur (addressed).** `IllegalArgumentException` and
  `IllegalStateException` now map to `internal`
  (`RealGraphService.kt:491-500`), and `RealGraphServiceTest.kt:274-284`
  verifies an invariant failure. LSP and workspace exceptions are still mapped
  explicitly. Raw `IOException` remains `workspace-error` (`:486-490`), which
  is correct for the file/discovery paths; the LSP client wraps transport
  failures in `LspException`, so this is not a residual defect.
- **Finding 8 — Concur (addressed).** `DotWriter` is now
  `analysis-engine/core/src/main/kotlin/dev/codenb/core/DotWriter.kt`, uses only
  `api` and JDK types, has its determinism/escaping test in `core`, and the
  server imports it (`RealGraphService.kt:15`). Ticket 009 can reuse it.
- **Finding 9 — Concur (addressed).** `refreshWorkspace` applies
  `config.workspace.include`, `exclude`, and `config.graph.followSymlinks`
  (`RealGraphService.kt:346-361`); `NeighbourExpander` accepts the workspace
  set (`NeighbourExpander.kt:30-32`, `:108-115`); and `resolveFileSeed`
  validates against it (`RealGraphService.kt:253-260`). Tests at
  `RealGraphServiceTest.kt:55-114` cover an excluded seed and an excluded
  reference target.

### New findings

1. **An open commits new workspace and revision state before its first expansion succeeds**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:220-233`
     (`openSelector`), `:346-361` (`refreshWorkspace` sets `workspacePaths`),
     `:121-124` (`status`), and `:437-439` (`requireOpen`).
   - Problem: `openSelector` calls `refreshWorkspace()` (which commits
     `workspacePaths` at `:359`), then resolves the seed, then, when the
     revision changed, replaces `policy`/`store` and sets `graphRevision`
     (`:224-228`) **before** `store.expand` computes the first expansion
     (`:230-232`). If seed resolution fails, the old graph keeps its revision
     but points at the new workspace set. If the first expansion fails (LSP
     failure, unsupported semantic tokens, invalid expansion), a new revision
     is marked open with an empty store, or the old store is already discarded.
     `status()` and `initialize` then report `open=true` with a revision that
     has no graph behind it, and `node/get`/`node/expand` report unknown nodes.
     This violates the graph lifecycle contract and the rule that a partial
     expansion is not cached as if complete.
   - Reproduction: use a fake LSP that returns a document symbol for the seed
     but throws `LspUnsupportedCapabilityException` (or `LspException`) from
     `semanticTokensFull`/`references` during the first entity expansion, or
     returns null semantic tokens for a non-empty seed span. Call
     `graph/open` with `{file, qualifiedName}`; it returns
     `unsupported-capability`/`lsp-failure`. Then call `initialize`: it reports
     `open=true` and a revision, while `node/get` on the seed returns
     `unknown-node` and `graph/dot` is empty. Variant: open a graph, edit the
     workspace so the revision changes, and call `graph/open` with an ambiguous
     or deleted seed; the call fails before the reset, but `workspacePaths` is
     already new, so a subsequent `node/expand` with the old revision computes
     against the new workspace set.
   - Suggested fix: Stage the new `workspacePaths`, revision, `GraphStore`, and
     `GroupingPolicy` locally; resolve the seed and compute the first expansion
     before committing any of them. Only after a successful expansion swap the
     store/policy/revision/workspace paths and return the expansion; on failure
     leave the previous graph and workspace set untouched. Add a fake-LSP test
     that fails after seed resolution and asserts `status()` remains false (or
     the previous revision) and the old store is intact.

2. **Ambiguous-seed candidates are comma-joined and not machine-readable**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:304-310`;
     `EngineError.details` is `Map<String, String>` in
     `analysis-engine/api/src/main/kotlin/dev/codenb/api/EngineError.kt:31-35`;
     the ambiguity test is `RealGraphServiceTest.kt:116-146`.
   - Problem: `candidates` and `fingerprints` are built with
     `joinToString(",")`. A Kotlin fingerprint commonly contains a comma
     (`fun over(a: Int, b: Int)`), and node ids can contain commas through file
     paths, so the values cannot be split into candidates. Ticket 010 cannot
     expose a reliable candidate list from this error. The existing test uses
     single-parameter overloads, so it does not detect the ambiguity.
   - Reproduction: open a `file#qualifiedName` selector for two overloads with
     multi-parameter signatures, for example `fun over(a: Int, b: Int)` and
     `fun over(a: String, b: String)`; the error details
     `fingerprints` becomes one comma-joined string with four comma-separated
     fragments and no way to recover the two candidates.
   - Suggested fix: Encode the candidate list as a JSON array string in a
     single `details` value (via `GraphJson.encode` on a small serializable
     candidate type), or use indexed keys such as `candidates.0.id` and
     `candidates.0.fingerprint`. Add a multi-parameter overload test and a
     socket test that decodes the candidates.

3. **`GraphInfo.nodeKinds` excludes FILE/GROUP while expansions still contain them**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:108-117`
     (`nodeKinds = listOf(ENTITY)`, `edgeKinds = EdgeKind.entries`), the
     containment nodes at
     `analysis-engine/core/src/main/kotlin/dev/codenb/core/ContainmentBuilder.kt:24-38`,
     `:124-166`; `JsonRpcHandler.kt:92-99`; tests at
     `EngineSocketServerTest.kt:48-51` and `RealGraphServiceTest.kt:198-222`;
     deferral at `docs/tasks/016-file-and-group-expansion.md:24-25`, `:45-46`,
     and the renderer requirement at `docs/tasks/011-web-graph-rendering.md:21`.
   - Problem: The graph still returns FILE and GROUP nodes in
     `Expansion.neighbours` (containment creates them), and `edgeKinds`
     advertises `CONTAINS`, `FILE_REFERENCES`, and `GROUP_REFERENCES`, but
     `nodeKinds` is only `[ENTITY]`. The deferral in ticket 016 says
     `nodeKinds` will advertise all implemented kinds once FILE/GROUP expand,
     which makes `nodeKinds` an "expandable kinds" capability list, but the
     architecture and the frozen `GraphInfo` do not define that meaning.
     Ticket 010 passes this metadata to ticket 011, whose node styling is by
     kind; if 011 treats `nodeKinds` as the set of present/renderable kinds, it
     will drop the FILE/GROUP nodes that every entity expansion returns. A
     client that validates an `Expansion` against `nodeKinds` would reject
     valid payloads.
   - Suggested fix: Decide and document the semantics. Prefer keeping
     `nodeKinds` as the kinds the graph can return (`ENTITY`, `FILE`, `GROUP`)
     and add a separate `expandableNodeKinds` (or a capability object) to the
     `initialize` result with `[ENTITY]` until ticket 016; alternatively,
     document in the architecture and ticket 010 that `nodeKinds` means
     expandable kinds and that FILE/GROUP are present-but-unsupported, and
     ensure 011/012 render them from `Node.kind`. Add a test that an
     `Expansion` containing FILE/GROUP is accepted alongside the initialize
     metadata.

### Concur

- The frozen `api` wire format is untouched: `git diff` shows no changes under
  `analysis-engine/api`. The server reuses `Expansion`, `Node`, `Edge`,
  `GraphInfo`, `EngineError`, the kind enums, and `PROTOCOL_VERSION`.
- `node/get`, `node/expand`, `graph/open`, and `graph/dot` return the frozen
  shapes; `node/get`/`node/expand` are `Expansion`, `graph/dot` is a transport
  string, and node/get's socket test decodes it as `Expansion`
  (`EngineSocketServerTest.kt:564-580`).
- The error layering is now clean: protocol errors are single `-326xx`
  responses without `data`/`cint`; application errors are single `-32000`
  responses with `EngineError` data; `cint/error` is reserved for id-less
  application failures (`JsonRpcHandler.kt:79-90`, `:193-221`). Tests at
  `EngineSocketServerTest.kt:136-171`, `:347-418`.
- `initialize` reports the graph identity plus `open` and `revision`, and the
  before-open/after-open transitions are tested
  (`JsonRpcHandler.kt:92-99`, `EngineSocketServerTest.kt:420-471`). The
  open-atomicity finding concerns failures during an open, not the never-opened
  state.
- The seed schema is canonical and validated: `{nodeId}` or
  `{file, qualifiedName, signatureFingerprint?}`, unknown fields rejected, and
  ambiguity returns `invalid-request` with candidates
  (`JsonRpcHandler.kt:117-137`, `RealGraphService.kt:296-312`). The candidate
  encoding needs the fix in new finding 2.
- Selector opens now compute a workspace content revision, reset the store and
  grouping on a change, and keep computed expansion revisions
  (`RealGraphService.kt:220-233`, `:365-377`); `NeighbourExpander` accepts the
  workspace set and revision override (`NeighbourExpander.kt:30-32`, `:108-115`,
  `:173`). The stale-cache reproduction is covered by
  `RealGraphServiceTest.kt:148-172`.
- FILE/GROUP expansion is explicitly `unsupported-capability` and `nodeKinds`
  is only `[ENTITY]`; the deferral is recorded in ticket 016, and ticket 012
  now depends on 016 (`docs/tasks/012-web-navigation.md:4`). The semantic
  ambiguity in `nodeKinds` is new finding 3. Ticket 016's own frontmatter lists
  `[005, 007]`; it should also list 008 because its acceptance includes
  socket-level tests, but that is a sequencing note rather than a wire-contract
  defect.
- Invariant failures now map to `internal`, LSP/workspace exceptions are mapped
  explicitly, and the invariant test passes
  (`RealGraphService.kt:462-506`, `RealGraphServiceTest.kt:274-284`).
- `DotWriter` is in `core`, uses only `api`/JDK types, and is covered by
  `core/DotWriterTest.kt`; the server imports it. Ticket 009 can reuse one
  writer.
- Config include/exclude and symlink policy reach the workspace set, the
  expander, and seed validation
  (`RealGraphService.kt:346-361`, `NeighbourExpander.kt:108-115`,
  `RealGraphServiceTest.kt:55-114`).
- Socket framing, request-id preservation (including `0`, empty string, and
  null at `EngineSocketServerTest.kt:175-191`), concurrent-client isolation,
  error shaping, permissions, stale/live socket handling, shutdown, and SIGTERM
  socket cleanup are covered by `EngineSocketServerTest.kt` and
  `DaemonProcessTest.kt`.
- The server remains a host: no Ktor, HTTP, WebSocket, SSE, tree-sitter, or
  second analysis backend; `core`, `frontend`, and `api` contain no socket
  transport types; `server` uses JDK NIO/IO.
- The new source and test files contain no comments (the only `//` matches are
  `file://` URIs and glob strings).
- No build, test, formatter, or `just` command was run for this review; the
  green handover is assumed.

### Dissent

- **Round 1 finding 3 (partial).** Selector opens are fixed, but `nodeId` opens
  and `initialize` status can still serve a cached old revision after a
  workspace edit, and a retired revision maps to `invalid-request`. Refresh
  those paths or document the cache-only semantics and map retired revisions to
  `invalid-graph`.
- **New finding 1.** An open must not commit workspace paths, a new store, or a
  new revision before its first expansion succeeds; failure must leave the
  previous graph intact and status must not report an empty graph as open.
- **New finding 2.** Ambiguous-seed candidates and fingerprints must be encoded
  structurally, not comma-joined, so ticket 010 can expose them.
- **New finding 3.** `GraphInfo.nodeKinds` must have a defined meaning that is
  consistent with expansions that still contain FILE/GROUP nodes; document it
  and/or add an expandable-kinds capability for ticket 010/011.

## Round 3

**Verdict:** Changes requested.

Round 2 is substantially addressed: `graph/open` is transactional, `openNode`
and `status` detect content changes without the LSP, retired revisions after a
successful reopen return `invalid-graph` with both revisions, shutdown deletes
the socket before a bounded service close, symlinks/FIFOs/long paths are
refused, ambiguous candidates are structural, and `nodeKinds` is all
graph-returnable kinds with `expandableNodeKinds: [ENTITY]`. One wire-contract
gap remains: `node/expand` does not recompute the content revision, so after a
content edit (or a failed reopen) it still accepts the old revision and can
compute new file facts under it while `initialize`/`status` report
`open=false`. The contract documentation also still describes the old
only-ENTITY behavior.

### Round 2 disposition

- **Round 2 new finding 1 — Concur (addressed).** `openSelector` stages the
  workspace paths, content revision, candidate policy, resolved node, computed
  expansion, and candidate store, and commits them only after
  `candidateStore.expand` succeeds (`RealGraphService.kt:238-255`). Failed
  first opens leave `GraphStatus(false, null)`, and failed reopens leave the
  old store/revision intact (`RealGraphServiceTest.kt:223-238`, `:240-265`).
- **Round 2 new finding 2 — Concur (addressed).** Ambiguity details now use
  `candidateCount` and `candidates.N.id`/`candidates.N.fingerprint`
  (`RealGraphService.kt:326-338`), tested with multi-parameter signatures
  containing commas (`RealGraphServiceTest.kt:119-151`) and decoded over the
  socket (`EngineSocketServerTest.kt:463-506`).
- **Round 2 new finding 3 — Concur (code addressed).** `GraphInfo.nodeKinds` is
  `NodeKind.entries` (`RealGraphService.kt:108-117`), and
  `expandableNodeKinds()` is `[ENTITY]` (`:136`); initialize exposes
  `expandableNodeKinds` (`JsonRpcHandler.kt:93-104`). Tests at
  `EngineSocketServerTest.kt:48-51`, `:508-536`, and
  `RealGraphServiceTest.kt:198-220` verify that expansions may contain
  FILE/GROUP while only ENTITY is expandable. The documentation is stale (new
  finding 2).
- **Round 2 carried-over Round 1 finding 3 — Concur (addressed as stated).**
  `openNode` and `status` recompute the content revision without the LSP
  (`RealGraphService.kt:121-134`, `:221-236`), `initialize` reflects the false
  open state, and `nodeIdOpenAfterEditRequiresASelectorReopen`
  (`RealGraphServiceTest.kt:196-221`) verifies the error and both revisions.
  After a successful selector reopen, an old revision is rejected by
  `node/expand` with `invalid-graph` and both revisions
  (`RealGraphService.kt:149-168`, `RealGraphServiceTest.kt:153-194`). The
  remaining pre-reopen `expand` path is new finding 1.

### New findings

1. **`node/expand` still accepts a pre-edit revision and can compute new facts under it**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:149-177`
     (`expand` compares only to `graphRevision`), `:174-176`
     (`computeEntityExpansion` reads the current workspace/files;
     `analysis-engine/frontend/src/main/kotlin/dev/codenb/frontend/NeighbourExpander.kt:188-198`
     reads the file text and document symbols), `:121-134` (`status` detects
     the content change), `:221-236` (`openNode` rejects it), and `:238-255`
     (`openSelector` is the only path that advances the revision).
   - Problem: `status` and `openNode` recompute `contentRevision` and treat a
     changed revision as stale, but `expand` only compares the client revision
     to `graphRevision`, which a content edit does not update. After an edit
     (or a failed reopen) `initialize` reports `open=false`, yet
     `node/expand` with the old revision still succeeds. If the node is cached,
     it returns old data; if it is not cached, `NeighbourExpander` reads the
     new file text and merges new facts under the old revision. That contradicts
     the "retired revision -> invalid-graph" contract and the content-revision
     invariant. `node/get` and `graph/dot` intentionally serve the old graph
     after a failed reopen (`RealGraphServiceTest.kt:240-265`), but `expand` is
     not a pure read: it can add nodes and edges.
   - Reproduction: open a seed at revision R1; edit a source file that contains
     a neighbour returned by the seed but not yet expanded; call `initialize`
     (or `status`) and observe `open=false`; then call `node/expand` for that
     neighbour with R1. It succeeds and may return neighbours/edges derived
     from the edited file, labelled R1, while `graphRevision` remains R1. The
     same happens after the failed-reopen fixture at
     `RealGraphServiceTest.kt:240-265`.
   - Suggested fix: Recompute the content revision in `expand` and throw
     `staleGraph(current, actual)` with both revisions when it differs, or
     maintain a stale flag set by `status`/`openNode` and reject expansion until
     a selector reopen. Decide and document whether `node/get`/`graph/dot` keep
     serving the old graph while `open=false` (currently tested as intentional),
     but do not compute new facts under an old revision. Add a test that edits
     a file and calls `expand` on an unexpanded neighbour with the old revision.

2. **Contract documentation still describes the old initialize and node-kinds behavior**

   - Location: `docs/architecture.md:158` (initialize returns only protocol
     version, root, LSP id, and node kinds), and
     `docs/tasks/016-file-and-group-expansion.md:4`, `:24-25`, `:35`, `:43-46`
     (depends on `[005, 007]`, says to advertise all kinds in
     `GraphInfo.nodeKinds` once both expand, and says ticket 008 advertises only
     `ENTITY`). Current code: `RealGraphService.kt:108-117` (`nodeKinds` all),
     `:136` (`expandableNodeKinds` [ENTITY]), and `JsonRpcHandler.kt:93-104`
     (initialize `open`, `revision`, `expandableNodeKinds`).
   - Problem: The wire now has `open`, `revision`, and
     `expandableNodeKinds`, and `nodeKinds` is all graph-returnable kinds while
     `expandableNodeKinds` is the capability list. The architecture does not
     describe these fields, and ticket 016 still says 008 advertises only
     ENTITY and that all kinds should be added to `nodeKinds` later. A future
     implementer following 016 could remove FILE/GROUP from `nodeKinds`,
     breaking the Round 3 contract and ticket 010/011 rendering. Ticket 016
     also has socket-level acceptance tests but does not list 008 as a
     dependency.
   - Suggested fix: Update `docs/architecture.md` and the ticket 010 notes to
     document the initialize result (`graph`, `socketPath`, `open`, `revision`,
     `expandableNodeKinds`) and the distinction between `nodeKinds` (present /
     returnable) and `expandableNodeKinds` (currently expandable). Update ticket
     016 to keep `nodeKinds` unchanged and add FILE/GROUP to
     `expandableNodeKinds` when implemented, and add 008 to its `depends-on`.
     This is documentation work for the scribe/orchestrator; no frozen `api`
     change is required.

### Concur

- `graph/open` is transactional: the paths, revision, candidate policy, node,
  expansion, and store are staged and only committed after a successful
  `candidateStore.expand` (`RealGraphService.kt:238-255`). Failed first opens
  leave the graph closed; failed reopens leave the old store/revision intact
  (`RealGraphServiceTest.kt:223-238`, `:240-265`).
- `openNode`/`status`/`initialize` detect content changes without the LSP
  (`RealGraphService.kt:121-134`, `:221-236`), `openNode` reports both
  revisions, and unchanged node-id opens still hit the cache without an LSP
  query (`RealGraphServiceTest.kt:196-221`, `:267-289`).
- A revision retired by a successful selector reopen is rejected by
  `node/expand` with `invalid-graph` and both `revision`/`currentRevision`
  details (`RealGraphService.kt:149-168`, `RealGraphServiceTest.kt:153-194`).
  After a failed reopen, `node/get` and `graph/dot` continue to serve the old
  graph and old revision (`RealGraphServiceTest.kt:240-265`), which is coherent
  with "old graph/revision intact"; the contract should document this as
  stale-but-readable. The new finding 1 covers the pre-reopen content-edit
  case, where `node/expand` is not a pure read.
- Shutdown deletes the socket before `service.close` and bounds service close
  to 500 ms on a daemon thread (`EngineSocketServer.kt:135-160`); the blocking
  close test passes (`EngineSocketServerTest.kt:195-214`) and the normal
  shutdown response/socket removal path is still tested (`:216-232`).
- Symlinks at the socket path are refused before probing, with `NOFOLLOW_LINKS`
  and the `S_IFSOCK` mode check (`SocketPaths.kt:67-85`, `:146-152`); the
  symlink-at-path test passes (`EngineSocketServerTest.kt:356-374`), and
  FIFO/device and long-path guards remain (`:327-341`,
  `SocketPathsTest.kt:35-42`).
- Ambiguous seeds expose `candidateCount` and indexed candidate id/fingerprint
  values, including multi-parameter signatures with commas
  (`RealGraphService.kt:326-338`, `RealGraphServiceTest.kt:119-151`,
  `EngineSocketServerTest.kt:463-506`). The wire result is still a single
  `-32000` application error with `EngineError` data.
- `GraphInfo.nodeKinds` is all returnable kinds and `expandableNodeKinds` is
  `[ENTITY]`; FILE/GROUP remain explicitly `unsupported-capability` for
  `node/expand` and `open {nodeId}` (`RealGraphService.kt:108-117`, `:136`,
  `:158-160`, `:208-214`; `EngineSocketServerTest.kt:48-51`, `:508-536`;
  `RealGraphServiceTest.kt:198-220`). This resolves the Round 2 node-kinds
  ambiguity; only the docs lag (new finding 2).
- The wire envelope is unchanged and coherent for ticket 010: `initialize`
  returns `graph`, `socketPath`, `open`, `revision`, and
  `expandableNodeKinds`; `graph/open` returns `graph` + `Expansion`;
  `node/expand` and `node/get` return `Expansion`; `graph/dot` returns a
  transport string; `shutdown` returns an empty result. Framing, id echo,
  protocol/application error separation, `cint/error` for id-less failures,
  request-id edge cases, concurrency, permissions, and no-Ktor/no-second-
  backend rules remain as reviewed in Rounds 1-2.
- The frozen `api` wire format is untouched, `DotWriter` remains in `core`,
  config include/exclude/symlink policy still reaches expansion, the new
  source/tests contain no comments, and no build/test/formatter command was run
  for this review (green handover assumed).

### Dissent

- **New finding 1.** `node/expand` must not accept a revision after a content
  edit or failed reopen while `initialize`/`status` report `open=false`; it can
  currently read changed files and merge new facts under the old revision.
- **New finding 2.** The initialize/capability contract and ticket 016's
  deferral note are stale; document `open`, `revision`, and
  `expandableNodeKinds`, keep `nodeKinds` as all returnable kinds, and add the
  008 dependency to ticket 016.

## Round 4

**Verdict:** Changes requested.

Round 3 is addressed: `expand` recomputes the content revision before cache/LSP
and rechecks during lazy compute, selector opens stage and commit
transactionally, same-revision opens use the store and avoid LSP on a cache hit,
close is bounded and interruptible, retry-once and docs updates are present.
Three new api-fidelity problems remain: the same-revision store shortcut can
bypass overload ambiguity; `node/get` is not revision-aware and its incident
check is key-only, so it does not always agree with `node/expand`/`open`; and
selector-open retry/error semantics are not uniform between the changed-revision
and same-revision paths.

### Round 3 disposition

- **Round 3 finding 1 — Concur (addressed).** `expand` checks
  `contentRevision` before the cache and LSP, rejects a stale content revision
  with `staleGraph`/`invalid-graph` and both revisions, and rechecks inside the
  lazy compute (`RealGraphService.kt:153-189`, `:465-472`, `:576-585`). The
  edit-before-reopen test verifies both the seed and a neighbour fail without
  mutating the graph (`RealGraphServiceTest.kt:422-458`). Retired revisions
  after a successful reopen still return `invalid-graph` with both revisions
  (`RealGraphServiceTest.kt:153-194`).
- **Round 3 finding 2 — Concur (addressed).** `docs/architecture.md:155-166`
  now documents `open`, `revision`, `nodeKinds`, and `expandableNodeKinds`;
  `docs/tasks/010-renderer-server.md:41-43` describes the stale-revision
  contract; and `docs/tasks/016-file-and-group-expansion.md:4`, `:24-28`,
  `:43-47` keeps `nodeKinds` as returnable kinds, moves FILE/GROUP to
  `expandableNodeKinds`, and adds 008 as a dependency. The earlier docs finding
  is closed.

### New findings

1. **Same-revision selector opens can bypass overload ambiguity**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:300-315`
     (`openWithCurrentRevision` uses `seedFromStore`), `:475-491`
     (`seedFromStore` returns one cached match), and `:317-402`
     (`resolveFileSeed` performs full discovery and ambiguity handling); the
     ambiguity test is `RealGraphServiceTest.kt:119-151`.
   - Problem: When the content revision is unchanged, `openWithCurrentRevision`
     first calls `seedFromStore(seed)`. For a selector without
     `signatureFingerprint`, that function returns a single node if exactly one
     matching node is in the lazy store. A file can contain another overload
     that has not been expanded or stored. The same-revision open then succeeds
     at the cached overload instead of returning the `invalid-request`
     candidate list required for an ambiguous selector. Whether an open is
     ambiguous therefore depends on what has already been cached, and the
     lazy store shortcut hides the ambiguity.
   - Reproduction: open `O.kt#app.over` with
     `signatureFingerprint: "fun over(a: Int)"`; the store contains that
     overload but not `fun over(a: String)` in the same file. Re-open the same
     revision with `{file: "O.kt", qualifiedName: "app.over"}` and no
     fingerprint. `seedFromStore` finds one node and the open succeeds, while a
     fresh selector open (`graphRevision` null) returns `invalid-request` with
     `candidateCount` and both candidates. The existing ambiguity test only
     covers the fresh-open path.
   - Suggested fix: Use `seedFromStore` only when `signatureFingerprint` is
     present, or when uniqueness can be proven from a complete discovery
     snapshot rather than the lazy store. For a no-fingerprint selector, call
     `resolveFileSeed` and preserve the ambiguity error and candidates. Add a
     test that opens one overload with a fingerprint, then reopens without one
     and expects the ambiguity error.

2. **`node/get` is not revision-aware and its incident check is key-only, so `open`/`get`/`expand` do not always agree**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:191-217`
     (`node`), `:204-215` (the `EdgeKey`-only cache check), `:125-138`
     (`status`), `:153-189` (`expand`), `:254-271` (`openNode`), and
     `:300-315` (`openWithCurrentRevision`); tests at
     `RealGraphServiceTest.kt:422-458` (node succeeds after an edit while
     expand fails), `:460-491` (same-revision agreement), and `:240-265`
     (failed reopen keeps the old node).
   - Problem: `node` never checks `contentRevision`. After a content edit it
     returns the cached old `Expansion` successfully, while `node/expand` and
     `open {nodeId}` return `invalid-graph`; the test at
     `RealGraphServiceTest.kt:455-456` currently encodes that disagreement. Its
     cache check also compares only `EdgeKey`s: an incident edge whose
     `count`/`origins` changed, or a node whose metadata changed, is treated as
     current and the cached old edge/node is returned. If a new incident edge
     key appears, `node` returns `storedExpansion` from the current snapshot
     while `openWithCurrentRevision`/`expand` return the cached old expansion.
     Thus the three methods agree only in the narrow no-edit/no-new-edge case
     covered by `unchangedRevisionSelectorOpenUsesTheStoreExpansion`.
   - Reproduction: after opening a seed and editing a referenced file,
     `node/get` on the seed/neighbour succeeds with the old `Expansion` while
     `node/expand` rejects it with `invalid-graph`; after a later expansion adds
     an incident edge key, `node/get` returns the snapshot projection while
     `node/expand` returns the cached expansion without that edge.
   - Suggested fix: Define one node/get contract. If it is a current read,
     recompute `contentRevision` and return `staleGraph`/`invalid-graph` like
     `expand`/`openNode`, and compare full incident `Edge` values (count,
     origins, endpoints) rather than keys; invalidate or refresh a node's cache
     when the store merges a changed incident edge. If it is intentionally a
     stale-readable cache, document that and stop claiming agreement with
     `expand`/`open`; ticket 010 must then use `expand`/`open` for current
     data. Add tests for the edit case, a new incident edge, and changed edge
     metadata.

3. **Selector-open retry and error semantics are not uniform**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:273-298`
     (retry only in the changed-revision branch), `:300-315`
     (`openWithCurrentRevision` has no retry), `:493-497`
     (`workspaceChangedDuringOpen` is a `WORKSPACE_ERROR` without revisions),
     and `:576-585` (`staleGraph` is `INVALID_GRAPH` with both revisions); tests
     at `RealGraphServiceTest.kt:493-526`.
   - Problem: The `graphRevision != revision` branch retries once when the
     content changes during expansion and, on a second mismatch, throws
     `workspaceChangedDuringOpen` (`workspace-error`, no revision details). The
     same-revision branch (`openWithCurrentRevision`) has no retry and throws
     `staleGraph` (`invalid-graph`, old/current details) on the first content
     change. A ticket-010 client therefore sees success after retry,
     `invalid-graph` with revisions, or `workspace-error` without revisions for
     the same content-race condition. The state says open retries once and a
     second mismatch fails without committing; the first part is not true for
     the same-revision path, and the failure code differs.
   - Reproduction: with a graph open at R1, make a fake LSP edit the workspace
     during a same-revision selector open that misses the seed cache; the open
     returns `invalid-graph` without retrying. With a closed graph, change the
     content on both expansion attempts; the second mismatch returns
     `workspace-error` with no `revision`/`currentRevision`. The existing
     `openRetriesWhenWorkspaceChangesDuringExpansion` test covers only the
     one-change first-open path.
   - Suggested fix: Route same-revision opens through the same retry loop, or
     explicitly document that only changed-revision opens retry. On final
     mismatch, return `staleGraph`/`invalid-graph` with both revisions (or a
     documented dedicated content-race error) so ticket 010 has one retry/
     re-open contract. Add tests for a same-revision race and for two
     consecutive content changes.

### Concur

- `expand` now recomputes the content revision before cache and LSP, rejects
  stale content with `invalid-graph` and both revisions, and rechecks inside
  lazy compute before caching (`RealGraphService.kt:153-189`, `:465-472`,
  `:576-585`; `RealGraphServiceTest.kt:422-458`, `:153-194`).
- `openSelector` stages paths, revision, policy, node, expansion, and candidate
  store and commits only after success (`RealGraphService.kt:273-298`);
  failed first/reopen paths leave the graph closed or the previous
  store/revision intact (`RealGraphServiceTest.kt:223-265`).
- The changed-revision open retries once and returns the new expansion without
  committing on a second mismatch (`RealGraphService.kt:273-298`;
  `RealGraphServiceTest.kt:493-526`). The same-revision retry gap is new
  finding 3.
- Same-revision selector opens resolve the seed from the store when possible
  and use `store.expand`; a cache hit queries no LSP
  (`RealGraphService.kt:300-315`; `RealGraphServiceTest.kt:460-491`). The
  store shortcut's ambiguity issue is new finding 1, and the node/get
  agreement gap is new finding 2.
- `node/get` returns the cached `Expansion` in the same-revision/no-new-edge
  case and the test decodes it as a frozen `Expansion`
  (`RealGraphService.kt:191-217`; `RealGraphServiceTest.kt:460-491`,
  `EngineSocketServerTest.kt:682-699`). The stale/new-edge cases are new
  finding 2.
- Service close is tracked and bounded: the closer is joined, interrupted, and
  joined again; `RealGraphService.close` uses `lockInterruptibly` and closes
  the client even after an interrupt (`RealGraphService.kt:231-252`,
  `EngineSocketServer.kt:151-189`). The close-interrupt and blocking-close
  tests cover the paths (`RealGraphServiceTest.kt:395-420`,
  `EngineSocketServerTest.kt:195-214`).
- A revision retired by a successful reopen is rejected by `node/expand` with
  `invalid-graph` and both `revision`/`currentRevision` details
  (`RealGraphService.kt:153-168`, `RealGraphServiceTest.kt:153-194`);
  `openNode`/`status` use the same `staleGraph` details
  (`RealGraphService.kt:121-138`, `:254-271`,
  `RealGraphServiceTest.kt:196-221`). The open-race inconsistency is new
  finding 3.
- The initialize metadata is coherent: `graph` contains all returnable
  `nodeKinds`, `expandableNodeKinds` is `[ENTITY]`, and expansions with
  FILE/GROUP are accepted while only ENTITY is expandable
  (`RealGraphService.kt:112-140`, `JsonRpcHandler.kt:93-104`;
  `EngineSocketServerTest.kt:48-51`, `:508-536`;
  `RealGraphServiceTest.kt:292-316`). Docs now match
  (`docs/architecture.md:155-166`, `docs/tasks/010-renderer-server.md:41-43`,
  `docs/tasks/016-file-and-group-expansion.md:4`, `:24-28`, `:43-47`).
- The wire envelope, id echo, protocol/application error separation,
  `cint/error` id-less behavior, `node/get`/`node/expand` `Expansion` shapes,
  `graph/dot` transport string, shutdown result, framing, concurrency,
  permissions, symlink/FIFO/long-path guards, no Ktor/no-second-backend rule,
  and no frozen `api` changes remain as reviewed. The new source/tests contain
  no comments and no build/test/formatter was run for this review (green
  handover assumed).

### Dissent

- **New finding 1.** A same-revision selector open must not use a single cached
  store match to bypass overload ambiguity; a no-fingerprint selector needs the
  full discovery result and candidate list.
- **New finding 2.** `node/get` must either be revision-aware/fully current or
  be explicitly documented as stale-readable; it currently succeeds with old
  data after an edit and can return a snapshot projection while
  `node/expand`/`open` return a cached view, so `open`/`get`/`expand` do not
  always agree.
- **New finding 3.** Selector-open retry and failure semantics must be uniform:
  same-revision opens should retry like changed-revision opens, and a final
  content-race failure should use one documented error shape with both
  revisions rather than `workspace-error` without details.

## Round 5

**Verdict:** Changes requested.

Round 4 is substantially addressed: discovery-cache ambiguity works with no LSP
after prior discovery, `node/get` is a current read with full edge/metadata
comparison, cache refresh keeps it agreeing with expand/open/dot, close failures
are recorded and surfaced, `ensureClient` has closed checks around the factory
and initialization, post-hash checks cover cache hits, and one retry loop serves
both same- and changed-revision opens. One Round 4 finding remains partially
addressed: final open-mismatch details report the last attempted content
revision rather than the open graph revision. Two new issues also remain:
selector validation/error mapping is not centralized across fresh and cached
paths, and `beforeExpandCacheLookup` is a mutable test hook in the production
service. The last point is not exposed on the wire, but it is a
production-class seam.

### Round 4 disposition

- **Round 4 new finding 1 — Concur (addressed).** `seedForCurrentRevision` no
  longer uses the store for a no-fingerprint selector; it uses the complete
  cached discovery or `resolveFileSeed` (`RealGraphService.kt:396-412`,
  `:473-493`). The discovery cache is cleared on revision change and
  `selectSeed` reports structural candidates
  (`RealGraphService.kt:435-471`). The multi-parameter and offline ambiguity
  test passes (`RealGraphServiceTest.kt:156-181`).
- **Round 4 new finding 2 — Concur (addressed).** `node` rehashes content,
  rejects stale content with `invalid-graph`, and `currentExpansion` compares
  full incident `Edge` values and node metadata, falls back to
  `storedExpansion`, merges and refreshes the cache
  (`RealGraphService.kt:202-227`, `:629-649`). `expand`/`openNode`/
  `openWithCurrentRevision` also call `currentExpansion` after post-store checks
  (`:190-198`, `:279-287`, `:332-353`). The graph-growth test asserts
  `node/get`, `node/expand`, `graph/open`, and `graph/dot` agree
  (`RealGraphServiceTest.kt:301-341`); the edit/failed-reopen tests assert
  `node`/`expand` reject while `dot` keeps the old graph
  (`:422-458`, `:271-299`).
- **Round 4 new finding 3 — Dissent (partially addressed).** Same- and
  changed-revision opens now share one retry loop, a same-revision race retries,
  and a second mismatch is `invalid-graph` with both revisions
  (`RealGraphService.kt:290-308`; `RealGraphServiceTest.kt:343-408`). However,
  the final error's `revision` value is `lastRevision`, the last attempt's
  starting content revision, rather than the open graph's `graphRevision`;
  `expand`/`openNode` use the graph revision. On an already-open graph at R0,
  two failed attempts can report R2/R3 instead of R0/R3. The two-content-change
  test only asserts the values differ (`RealGraphServiceTest.kt:399-403`).
  Use `graphRevision ?: lastRevision` as the final `revision` (optionally add
  `attemptedRevision`) and add an assertion for the open graph revision.

### New findings

1. **Selector validation and error mapping are not centralized across fresh and cached paths**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:356-365`
     (`resolveFileSeed` performs the blank checks), `:396-412`
     (`seedForCurrentRevision`), `:414-433` (`seedPath` swallows invalid paths
     and returns null), and `:435-455` (`selectSeed`); tests at
     `RealGraphServiceTest.kt:118-181`.
   - Problem: Selector validation depends on the path taken.
     `resolveFileSeed` rejects blank `file`, `qualifiedName`, and
     `signatureFingerprint` with `invalid-request`, but the same-revision cached
     path (`seedForCurrentRevision` using `cachedDiscovery`/`selectSeed`) does
     not. A blank `qualifiedName` or blank `signatureFingerprint` therefore
     falls through to an empty match and returns `unknown-node` when a
     discovery cache exists, while the same selector on a fresh open returns
     `invalid-request`. In addition, `seedPath` catches `Path.of` failures and
     returns null, so `resolveFileSeed` wraps an invalid path as
     `workspace-error` ("outside the configured workspace") instead of the
     previous `invalid-request`. The error code depends on cache state and on
     which validation helper returns null, which ticket 010 cannot rely on.
   - Reproduction: open `O.kt#app.over` with a valid fingerprint, then reopen
     the same revision with `{file: "O.kt", qualifiedName: ""}` or a blank
     `signatureFingerprint`; the cached `selectSeed` returns `unknown-node`,
     while a fresh open returns `invalid-request`. Separately, send a selector
     whose `file` contains a NUL/invalid path; it now returns `workspace-error`
     instead of `invalid-request`.
   - Suggested fix: Centralize selector field/path validation before the store,
     discovery cache, and `selectSeed` shortcuts, preserving `invalid-request`
     for blank fields and invalid paths and `workspace-error` only for
     valid-but-out-of-scope/missing/unhandled files. Add fresh-vs-cached tests
     for blank qualifiedName/fingerprint and an invalid path.

2. **`beforeExpandCacheLookup` is a mutable test hook in the production service**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:116`
     (`internal var beforeExpandCacheLookup`), invoked at `:189` (`expand`) and
     `:278` (`openNode`); used by `RealGraphServiceTest.kt:411-439`
     (`cacheHitAfterContentChangeIsRejected`).
   - Problem: The hook is declared in main source on the production
     `RealGraphService`, is mutable, is not cleared by the service, and runs on
     the production `expand`/`openNode` paths. It is not on `GraphService`, in
     `JsonRpcHandler`, or in the wire, so it does not leak to ticket 010; but it
     is a test-only seam in the production class, lets any server-module code
     mutate behavior between the stale check and cache lookup, and couples
     production execution to a test hook. The test must rely on the hook being
     reset by the lambda.
   - Suggested fix: Remove the hook from `RealGraphService`. Make the race
     deterministic through test injection instead: a fake content-revision/file
     provider, a test-only subclass or factory, or a constructor-injected
     no-op hook used only by tests. If an internal hook is retained, make it
     immutable/test-only and document it explicitly rather than a public-in-
     module mutable property.

### Concur

- Discovery-cache behavior is correct: same-revision no-fingerprint selectors
  use the complete per-file discovery cache (or full discovery), never the
  partial store; ambiguity returns `invalid-request` with structural
  `candidateCount`/`candidates.N.*` even when the LSP is unavailable after a
  prior discovery (`RealGraphService.kt:396-493`;
  `RealGraphServiceTest.kt:156-181`).
- `node/get` is a current read: it rehashes content, returns `invalid-graph` on
  stale content, compares full incident edge values and node metadata, falls
  back to `storedExpansion`, and refreshes the cache; `expand`/`openNode`/
  post-store validation do the same (`RealGraphService.kt:202-227`,
  `:190-198`, `:279-287`, `:332-353`, `:629-649`).
- The graph-growth test verifies `node/get`, `node/expand`, `graph/open`, and
  `graph/dot` agreement after incident edge metadata changes
  (`RealGraphServiceTest.kt:301-341`). Edit/failed-reopen tests verify
  `node/get`/`node/expand` reject while `graph/dot` intentionally keeps the
  stored old graph (`:271-299`, `:422-458`).
- `ensureClient` checks `closed` before using an existing client, after the
  factory, and after initialize; the interrupted-close test verifies it does
  not adopt a new LSP (`RealGraphService.kt:509-541`,
  `RealGraphServiceTest.kt:441-494`).
- `EngineSocketServer` records service-close failures, `awaitTermination`
  returns false on failure/timeout, and tests cover blocking, non-cooperative,
  and throwing close implementations (`EngineSocketServer.kt:62-67`,
  `:138-191`; `EngineSocketServerTest.kt:195-286`).
- Post-hash checks cover cache hits: the explicit pre-cache hook test
  (`RealGraphServiceTest.kt:411-439`) makes `expand` reject a content change
  after the initial check; `openWithCurrentRevision` also rechecks after
  `store.expand` (`RealGraphService.kt:332-353`).
- Same-revision and changed-revision opens share the retry loop; the
  same-revision race and two-consecutive-change tests pass
  (`RealGraphService.kt:290-308`; `RealGraphServiceTest.kt:343-408`). The final
  mismatch's revision value is the Round 4 finding 3 residual below.
- `graph/dot` intentionally serves the old stored graph while status is false;
  `node/get` and `node/expand` reject stale content
  (`RealGraphService.kt:229-239`; `RealGraphServiceTest.kt:271-299`,
  `:422-458`). This is the intended split.
- The initialize/status contract is fresh: `initialize` derives `open` and
  `revision` from `status`, `nodeKinds` remains all returnable kinds,
  `expandableNodeKinds` is `[ENTITY]`, and expansions may contain FILE/GROUP
  (`JsonRpcHandler.kt:93-104`, `RealGraphService.kt:118-140`;
  `EngineSocketServerTest.kt:48-51`, `:508-536`).
- The wire envelope, id echo, error separation, `cint/error`, `Expansion`
  shapes, shutdown result, framing, concurrency, permissions, symlink/FIFO/
  long-path guards, no Ktor/no-second-backend rule, and no frozen `api` changes
  remain as reviewed. The new source/tests contain no comments, and no build,
  test, or formatter command was run for this review (green handover assumed).

### Dissent

- **Round 4 finding 3 (partial).** Final selector-open failure details must use
  the open graph revision (or clearly separate the attempted content revision)
  so they are consistent with `expand`/`openNode`; the retry behavior itself is
  fixed.
- **New finding 1.** Selector validation/error mapping must not depend on
  whether a discovery cache exists or which helper returns null; blank fields
  and invalid paths should be `invalid-request` on both fresh and cached opens.
- **New finding 2.** Move `beforeExpandCacheLookup` out of the production
  service or make it a proper test-only, non-mutable seam; it does not reach
  the wire but is a production-class test hook.

## Round 6

**Verdict:** Changes requested.

Round 5 is addressed: selector validation/error mapping is centralized,
fresh/cached tests cover blank and malformed selectors, the final retry error
uses the graph revision, shutdown and close-failure paths are tested, node
closed checks are present, and the mutable test hook is replaced by an immutable
`ContentRevisionProvider`. `fileEntities` is frontend-only and the new
full ENTITY projection is a valid `Expansion`, but it omits the
`FILE_REFERENCES`/`GROUP_REFERENCES` aggregation edges that the initial
`ContainmentBuilder` expansion and `graph/dot` contain. That is a wire
inconsistency across `node/get`, `node/expand`, `graph/open`, and
`graph/dot`.

### Round 5 disposition

- **Round 5 finding 1 — Concur (addressed).** `openSelector` validates blank
  fields before any shortcut (`RealGraphService.kt:291-292`, `:360-370`), and
  `selectorPath` preserves `invalid-request` for malformed paths while
  out-of-scope/missing/unhandled files are `workspace-error`
  (`:372-409`). The fresh/cached validation test passes
  (`RealGraphServiceTest.kt:257-285`).
- **Round 5 finding 2 — Concur (addressed).** The mutable
  `beforeExpandCacheLookup` property is gone; an immutable
  `ContentRevisionProvider` constructor dependency with a production hash
  default is used by the initial/post checks and race tests
  (`RealGraphService.kt:97-100`; `ContentRevisionProvider.kt:8-31`). No
  production test hook remains (`internal var`/`beforeExpandCacheLookup` grep
  is empty).
- **Round 5 finding 3 — Concur (addressed).** The final selector-open error
  uses `graphRevision ?: lastRevision` with `lastActual` as
  `currentRevision` (`RealGraphService.kt:309-311`), and the two-change test
  asserts the open graph revision (`RealGraphServiceTest.kt:522-539`).
- **Round 4 finding 3 residual — Concur (addressed).** Retry and final error
  details are now consistent with `expand`/`openNode`; the retry tests cover
  same-revision and two-consecutive-change cases (`:343-408`).

### New findings

1. **The full ENTITY projection drops `FILE_REFERENCES`/`GROUP_REFERENCES`, so `node/get`, `node/expand`, `graph/open`, and `graph/dot` disagree on edge kinds**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:669-725`
     (`fullEntityExpansion` adds only `EdgeKind.REFERENCES` and containment
     edges), `:643-667` (`currentExpansion` refreshes/caches the full
     projection), `:191-199` (`expand`), `:280-288` (`openNode`), and
     `:336-357` (`openWithCurrentRevision`). The initial expansion path adds
     aggregation edges in
     `analysis-engine/core/src/main/kotlin/dev/codenb/core/ContainmentBuilder.kt:45-70`,
     `:85-98`. The graph-growth test only checks containment and a
     `REFERENCES` edge (`RealGraphServiceTest.kt:404-449`, helper `:451-472`).
   - Problem: `ContainmentBuilder.apply` adds `FILE_REFERENCES` and
     `GROUP_REFERENCES` edges to every initial entity expansion. The new
     `fullEntityExpansion` used by `node/get` and by `currentExpansion` only
     constructs `REFERENCES` edges and `CONTAINS` edges. It does not construct
     the file/group aggregation edges. Since `currentExpansion` uses the full
     projection for cached entity nodes and writes it back to the cache,
     `node/get`, `node/expand`, and same-revision `graph/open` return an
     `Expansion` missing those edge kinds, while `graph/dot` (the store
     snapshot) still contains them and the first `graph/open`/changed-revision
     response contains them. The wire shape is a valid `Expansion`, but the
     edge set is not consistent across methods or across repeated opens.
     Ticket 010/011 can lose the file/group reference labels and aggregation
     edges.
   - Reproduction: open a seed that references a target in a different file
     and group; the first `open` expansion contains `REFERENCES`, `CONTAINS`,
     `FILE_REFERENCES`, and `GROUP_REFERENCES`. Call `node(seed)`,
     `expand(seed, revision)`, and `open` the same revision; the returned
     expansions contain only `REFERENCES`/`CONTAINS` (the full projection),
     while `graph/dot` still contains the file/group reference edges. The
     existing graph-growth test does not assert those edge kinds.
   - Suggested fix: Include file/group aggregation edges in
     `fullEntityExpansion`, with origins/counts derived from the reference
     edges in the projected set (reuse the `ContainmentBuilder` reference/
     aggregation logic or a shared full-projection builder). Alternatively
     merge the current cache expansion with the full projection rather than
     replacing it. Add a test that compares the full `EdgeKind`/edge
     key/count/origin sets of the first open, `node/get`, `node/expand`,
     same-revision `open`, and `graph/dot` for a seed with a cross-file/
     cross-group reference.

### Concur

- The full ENTITY projection now includes the entity, its file/group, immediate
  reference neighbours, neighbour files/groups, containment edges, and
  reference edges; `currentExpansion` uses it for cache refresh and
  `node/get`/`expand`/`open` agree on node and containment closure in the
  graph-growth test (`RealGraphService.kt:643-725`;
  `RealGraphServiceTest.kt:404-472`). The missing file/group aggregation edges
  are new finding 1.
- `fileEntities` is frontend-only: it is added to
  `ExpansionWithFiles` (`ExpansionWithFiles.kt:6-9`), populated by
  `NeighbourExpander` (`NeighbourExpander.kt:150-155`), and consumed by the
  server to fill the discovery cache (`RealGraphService.kt:633`); it is not in
  `api`, `GraphInfo`, or the socket JSON.
- Discovery cache behavior is correct: all loaded files are cached by
  `computeEntityExpansion`, and `resolveFileSeedAtPath`/`seedForCurrentRevision`
  use the complete per-file list before LSP; the neighbour-only ambiguity and
  failed-candidate offline tests pass (`RealGraphService.kt:411-460`,
  `:500-520`; `RealGraphServiceTest.kt:184-255`).
- Selector validation and error mapping are centralized: blank fields and
  malformed paths are `invalid-request`, while out-of-scope/missing/unhandled
  files are `workspace-error`; fresh and cached paths are tested
  (`RealGraphService.kt:291-292`, `:360-409`;
  `RealGraphServiceTest.kt:257-285`).
- The final retry error uses `graphRevision ?: lastRevision` and `lastActual`
  (`RealGraphService.kt:309-311`); the two-change test asserts the open graph
  revision (`RealGraphServiceTest.kt:522-539`).
- `ContentRevisionProvider` is immutable with a production default and no
  mutable test hook remains; race tests inject it
  (`RealGraphService.kt:97-100`; `ContentRevisionProvider.kt:8-31`;
  `RealGraphServiceTest.kt:543-577`, `:580-627`).
- `node/get` rechecks `closed` before refresh and inside `currentExpansion`;
  the interrupted-node test verifies `invalid-graph` after close
  (`RealGraphService.kt:203-229`, `:643-667`;
  `RealGraphServiceTest.kt:580-627`).
- Shutdown covers the 8-second hook, bounded service close, closeFailure
  reporting, and a connected-client stubborn-close test
  (`Main.kt:43-59`; `EngineSocketServer.kt:62-67`, `:138-191`;
  `EngineSocketServerTest.kt:195-332`).
- FILE/GROUP expansion remains unsupported; `GraphInfo.nodeKinds` is all
  returnable kinds and `expandableNodeKinds` is `[ENTITY]`
  (`RealGraphService.kt:120-148`; `RealGraphServiceTest.kt:718-742`). The docs
  are unchanged this round and remain consistent with the prior round.
- The wire envelope, id echo, error separation, `cint/error`, `Expansion`
  shapes, shutdown result, framing, concurrency, permissions, symlink/FIFO/
  long-path guards, no Ktor/no-second-backend rule, and no frozen `api` changes
  remain as reviewed. We found no production test seams (`beforeExpandCacheLookup`
  is gone); no build, test, or formatter was run (green handover assumed).

### Dissent

- **New finding 1.** The full ENTITY projection must include the
  `FILE_REFERENCES`/`GROUP_REFERENCES` aggregation edges that the initial
  `ContainmentBuilder` expansion and `graph/dot` contain; otherwise
  `node/get`, `node/expand`, `graph/open`, and `graph/dot` do not expose the
  same edge contract.

## Round 7

**Verdict:** Approved.

Round 6's finding is addressed: `fullEntityExpansion` now includes the snapshot
`FILE_REFERENCES`/`GROUP_REFERENCES` edges between projected files/groups,
preserving their keys, counts, and origins, and the graph-growth and
cross-package tests assert read-path agreement across `node/get`,
`node/expand`, and same-revision `graph/open`, with `graph/dot` rendering the
same aggregation kinds. The discovery/commit work stays out of the wire shape,
the read-only test accessors are internal and not part of `GraphService`/JSON,
and no frozen `api` type was changed. I found no new api-fidelity problems.

### Round 6 disposition

- **Round 6 finding 1 — Concur (addressed).** `fullEntityExpansion` now adds
  snapshot `FILE_REFERENCES` and `GROUP_REFERENCES` edges whose endpoints are
  projected files/groups (`RealGraphService.kt:793-800`, `:808`) and keeps the
  snapshot `Edge` values (kind, endpoints, count, origins). The cross-package
  and neighbour-neighbour test checks `FILE_REFERENCES`/`GROUP_REFERENCES`
  between `A/B/C` files and groups, then asserts `stored`, `expanded`, and
  same-revision `reopened` edge sets are equal and `graph/dot` contains both
  aggregation kinds (`RealGraphServiceTest.kt:808-867`). The edge-metadata
  growth test also asserts full edge-set equality and a `FILE_REFERENCES`
  edge (`:525-573`).

### New findings

None. I found no new api-fidelity problem in this round.

### Concur

- The full ENTITY projection is a valid frozen `Expansion` and now carries the
  entity, files, groups, immediate reference neighbours, neighbour files/
  groups, `CONTAINS` edges, `REFERENCES` edges, and the snapshot
  `FILE_REFERENCES`/`GROUP_REFERENCES` aggregation edges with their keys,
  counts, and origins (`RealGraphService.kt:746-809`). `currentExpansion`
  refreshes/caches that projection and is used by `node/get`, `node/expand`,
  and same-revision `graph/open` (`:717-744`). Tests assert edge-set agreement
  and containment closure (`RealGraphServiceTest.kt:525-573`, `:808-867`).
- `graph/dot` still renders the stored graph snapshot, including the
  aggregation edges; the growth tests assert the same file/group edge kinds
  appear in the stored projection and dot (`RealGraphServiceTest.kt:808-867`).
- Discovery handling is internal: `contentRevision` and `CommitGate` are
  immutable constructor dependencies, staged discoveries are committed only
  after compute/revision checks, and the per-revision discovery cache is
  bounded (`RealGraphService.kt:97-101`, `:537-545`, `:655-715`;
  `ContentRevisionProvider.kt:8-17`). The failed-compute/revert and commit-gate
  tests cover the failure paths (`RealGraphServiceTest.kt:258-375`).
- The store/cache/candidate/discovery commits and final `checkOpen` are under
  `clientLock`; the compute-time checks remain in place
  (`RealGraphService.kt:680-744`, `:337-363`). The commit-interruption test
  reads back the snapshot/cache via the internal accessors and asserts no
  mutation (`RealGraphServiceTest.kt:313-375`).
- The new read-only accessors (`snapshotForTest`, `cachedExpansionForTest`,
  `discoveredForTest`) are `internal`, return only immutable views, are not on
  `GraphService`, and are not serialized or visible to ticket 010's separate
  build (`RealGraphService.kt:150-160`). They are not a wire/production seam.
- No mutable production test seam remains: `beforeExpandCacheLookup` is gone;
  `ContentRevisionProvider` and `CommitGate` are immutable injected
  dependencies with production defaults, and the accessors are read-only.
- Selector validation/error mapping remains centralized and fresh/cached
  consistent (`RealGraphService.kt:393-448`;
  `RealGraphServiceTest.kt:257-285`); final retry details use
  `graphRevision ?: lastRevision` (`:309-311`, test `:522-539`).
- `node/get` rechecks `closed` before refresh and inside `currentExpansion`;
  the interrupted-node test passes (`RealGraphService.kt:219-248`, `:717-744`;
  `RealGraphServiceTest.kt:580-627`). Shutdown hooks and close-failure
  reporting remain covered (`Main.kt:43-59`; `EngineSocketServer.kt:62-67`,
  `:138-191`; `EngineSocketServerTest.kt:195-332`).
- `fileEntities` remains frontend-only (`ExpansionWithFiles.kt:6-9`,
  `NeighbourExpander.kt:150-155`); the server only reads it to populate the
  discovery cache (`RealGraphService.kt:655-677`). It is not in `api`,
  `GraphInfo`, or the socket JSON.
- FILE/GROUP expansion remains unsupported; `GraphInfo.nodeKinds` is all
  returnable kinds and `expandableNodeKinds` is `[ENTITY]`
  (`RealGraphService.kt:120-148`; `RealGraphServiceTest.kt:718-742`). Docs are
  unchanged and consistent with the prior round.
- No frozen `api` changes are present; the wire envelope, id echo, error
  separation, `cint/error`, `Expansion` shapes, `graph/dot` string, shutdown
  result, framing, concurrency, permissions, symlink/FIFO/long-path guards,
  and no Ktor/no-second-backend rule remain as reviewed. No comments were added,
  and no build, test, or formatter was run for this review (green handover
  assumed).

### Dissent

None.

## Round 8

**Verdict:** Changes requested.

Round 7 remains approved. The correctness follow-up is largely sound: the new
`ReferenceAggregation` produces valid frozen `Edge`/`EdgeOrigin` shapes; the
full projection and `DotWriter` use it and exclude stored aggregation; the
discovery cache pins `graphRevision` and refreshes recency; `status()` rechecks
`closed` after hashing; and the collision, eviction, status-close, commit-gate,
and dot-parsing tests exercise those paths. Two new issues remain: one of the
advertised read-only test accessors is not read-only, and the initial/changed-
revision `graph/open` path still has a separate aggregation derivation that is
not covered by the exact edge-set test.

### Round 7 correctness disposition

- **ReferenceAggregation — Concur (addressed).** It derives
  `FILE_REFERENCES`/`GROUP_REFERENCES` only from entity `REFERENCES` origins,
  maps entities through their file/group parents, sums per directed pair,
  suppresses same-file/same-group origins, and materializes canonical
  `Edge.of` edges with origin counts equal to the edge count
  (`analysis-engine/core/src/main/kotlin/dev/codenb/core/ReferenceAggregation.kt:11-83`).
  The collision test asserts the summed count and canonical origin
  (`RealGraphServiceTest.kt:1076-1154`).
- **Full projection and dot derivation — Concur (addressed).**
  `fullEntityExpansion` passes the projected entity references to
  `ReferenceAggregation` (`RealGraphService.kt:760-812`), and `DotWriter`
  derives aggregation from snapshot references while excluding stored
  `FILE_REFERENCES`/`GROUP_REFERENCES` (`DotWriter.kt:13-22`). The tests compare
  projected `node/get`, `expand`, same-revision `open`, and dot output
  (`RealGraphServiceTest.kt:1014-1072`, `:1076-1154`).
- **Discovery cache and status close — Concur (addressed).** `cacheDiscovery`
  protects the current `graphRevision` from eviction, `cachedDiscovery`
  refreshes recency, and `status()` hashes before rechecking `closed` under
  `clientLock` (`RealGraphService.kt:137-148`, `:538-565`). The eviction,
  close-interruption, and commit-gate tests exercise the failure paths
  (`RealGraphServiceTest.kt:323-390`, `:390-530`).
- **Strengthened tests — Concur.** Collision summation, 16-revision eviction,
  status close recheck, commit-gate interruption, and dot edge parsing are
  covered. No source/test/doc other than this report was changed by this
  review, and no build, test, or formatter was run (green handover assumed).

### New findings

1. **`discoveredForTest` is not read-only: it mutates the discovery cache's
   eviction order**

   - Location: `analysis-engine/server/src/main/kotlin/dev/codenb/server/RealGraphService.kt:160-163`
     (`discoveredForTest` delegates to `cachedDiscovery`) and `:557-565`
     (`cachedDiscovery` removes and reinserts the revision in
     `discoveredFiles`).
   - Problem: Round 7 states that the internal accessors are read-only, but
     `discoveredForTest` calls the production lookup that refreshes LRU/insertion
     order. A test that merely inspects a revision therefore changes which
     revision is evicted next, so a read can silently protect an otherwise
     evictable revision and make eviction/pinning tests order-dependent. The
     accessor is internal and not exposed on `GraphService`/JSON (so this is not
     a ticket 010 wire leak), but it is a mutable test seam contrary to the
     stated read-only contract.
   - Suggested fix: make `discoveredForTest` a non-mutating lookup
     (`discoveredFiles[revision]?.get(uri)`) or add a separate `peekDiscovery`
     helper; keep the recency refresh in production `cachedDiscovery`. Add a
     test asserting that repeated `discoveredForTest` calls do not change which
     revision is evicted.

2. **Initial/changed-revision `graph/open` still uses a separate aggregation
   implementation and is not covered by the exact edge-set test**

   - Location: `analysis-engine/core/src/main/kotlin/dev/codenb/core/ContainmentBuilder.kt:43-98`
     duplicates the file/group origin derivation, while
     `ReferenceAggregation.kt:11-83` is the new shared derivation.
     `RealGraphService.openChangedRevision` returns the `ContainmentBuilder`
     expansion (`RealGraphService.kt:340-365`), whereas
     `fullEntityExpansion` and `DotWriter` use `ReferenceAggregation`
     (`RealGraphService.kt:760-812`, `DotWriter.kt:17-22`).
   - Problem: The initial and changed-revision `open` responses are still
     produced by an independent aggregation implementation. The two
     implementations are currently equivalent, but the exact agreement
     contract is not enforced: `fullProjectionIncludesAggregationEdgesAndNeighbourClosure`
     only asserts that the first `open` contains the two kinds, then compares
     stored/expanded/reopened after graph growth
     (`RealGraphServiceTest.kt:1014-1072`); the first/changed-revision `open`
     edge keys/counts/origins are never compared with the full/Dot derivation.
     A future divergence would make the initial `graph/open` response disagree
     with `node/get`, `node/expand`, and `graph/dot`.
   - Suggested fix: route `ContainmentBuilder.apply` through
     `ReferenceAggregation.edges` (or extract one shared builder) so there is a
     single aggregation source, and add a test comparing the first and
     changed-revision `open` edge set (kind, canonical key, count, origins) with
     the corresponding full projection and dot output for a cross-file/
     cross-group seed.

### Concur

- `ReferenceAggregation` returns valid frozen `Edge`/`EdgeOrigin` values:
  canonical endpoints via `Edge.of`, one canonical origin per directed pair,
  count equal to the origin sum, no same-file/same-group/self aggregation, and
  deterministic ordering (`ReferenceAggregation.kt:11-83`). The collision test
  covers the summed count and origin (`RealGraphServiceTest.kt:1134-1139`).
- `fullEntityExpansion` derives aggregation from projected entity references
  and includes only projected files/groups (`RealGraphService.kt:760-812`);
  `DotWriter` derives from snapshot references and excludes stored aggregation
  (`DotWriter.kt:13-22`). The exact-size dot comparison and kind assertions
  pass in the full-graph fixtures (`RealGraphServiceTest.kt:1068-1071`,
  `:1140-1154`), and the dot parser rejects self edges/duplicates.
- Dot output remains deterministic and valid Graphviz: nodes and groups are
  sorted, edges are ordered by kind/from/to, aggregation endpoints are existing
  snapshot nodes, and counts come from the derived edges (`DotWriter.kt:10-70`).
- Discovery pinning and recency refresh are otherwise correct: the current
  `graphRevision` is protected while other revisions are evicted, and a
  non-current cached revision is refreshed on access
  (`RealGraphService.kt:538-565`). The 17-revision test covers the pin
  (`RealGraphServiceTest.kt:323-345`); the accessor mutation is new finding 1.
- `status()` returns `GraphStatus(false, null)` after close and rechecks
  `closed` after the content hash, so a close that interrupts hashing cannot
  return an open status (`RealGraphService.kt:137-148`; test `:347-388`).
- `initialize` capability/status behavior remains consistent with the frozen
  contract, and no `api`/wire shape changed. `nodeKinds` remains all returnable
  kinds; `expandableNodeKinds` remains `[ENTITY]`; FILE/GROUP expansion is
  explicitly unsupported.
- The internal accessors remain off `GraphService`, the JSON envelope, and the
  ticket 010 build; `snapshotForTest` and `cachedExpansionForTest` are
  non-mutating. `discoveredForTest` is the exception described in new finding
  1.
- `ContentRevisionProvider` and `CommitGate` remain immutable injected
  dependencies with production defaults; the removed mutable
  `beforeExpandCacheLookup` hook has not returned. No production test seam was
  added, and the commit-gate tests leave the store/cache/discovery unchanged
  (`RealGraphServiceTest.kt:390-530`).
- The first/changed-revision aggregation duplication in new finding 2 is a
  consistency/test gap, not an observed wire mismatch in the exercised graphs;
  the current full/Dot paths and same-revision reads agree. No frozen `api`
  changes, no second backend/Ktor dependency, and no comments were added. No
  build, test, or formatter was run for this review (green handover assumed).

### Dissent

- New finding 1: `discoveredForTest` is not read-only and can perturb discovery
  eviction order.
- New finding 2: initial/changed-revision `graph/open` retains a separate
  aggregation derivation and lacks an exact first/changed-open edge-set test
  against the full/Dot path.

**Summary for the orchestrator:** The Round 8 correctness fixes are functionally
sound and the frozen wire contract is unchanged: `ReferenceAggregation` emits
valid canonical edges, full projection/Dot derive aggregation correctly,
discovery pinning and status-close rechecks are covered, and the strengthened
tests pass on handover. Approval is withheld for two issues: the
`discoveredForTest` accessor calls the mutating `cachedDiscovery` and therefore
changes eviction order, and the initial/changed-revision `graph/open` path still
uses `ContainmentBuilder`'s duplicate aggregation implementation without an
exact comparison to the full/Dot edge set. Both can be fixed with a direct
non-mutating test lookup and by routing `ContainmentBuilder` through the shared
aggregation builder plus a first/changed-open equality test.

## Round 9

**Verdict:** Approved.

Round 8's two findings are addressed and no new api-fidelity finding remains.
`discoveredForTest` now delegates to the private, non-mutating `peekDiscovery`;
`ContainmentBuilder` uses the shared `ReferenceAggregation`; the first and
changed-revision `graph/open` edge sets are compared with `node/get`,
`node/expand`, same-revision `open`, and parsed dot; the full projection derives
aggregation from all snapshot entity references while keeping entity
references/containment scoped; and direct core tests cover counts, origins,
fallback, suppression, and determinism. The frozen `api` module and wire shapes
are unchanged, and the internal accessors remain non-wire and non-mutating.

### Round 8 disposition

- **New finding 1 — Concur (addressed).** `discoveredForTest` now calls
  `peekDiscovery`, which performs a direct map lookup without removing or
  reinserting the revision (`RealGraphService.kt:160-163`, `:567-570`). The
  accessor remains `internal` and is not on `GraphService`/JSON. The new test
  proves that inspecting a cached revision does not refresh eviction order:
  after the extra revision is added, the untouched revision is evicted while the
  inspected older revision remains (`RealGraphServiceTest.kt:518-543`).
- **New finding 2 — Concur (addressed).** `ContainmentBuilder.apply` now builds
  the contained entity/file/group nodes and calls
  `ReferenceAggregation.edges(containedNodes.values, references)`
  (`ContainmentBuilder.kt:41-70`), retaining the prior reference-origin
  validation before aggregation. The duplicate file/group origin derivation and
  local `Directed`/`referenceEdges` implementation are gone. The first/changed-
  revision open equality test compares the initial and changed opens with
  `node/get`, `node/expand`, same-revision open, and parsed dot
  (`RealGraphServiceTest.kt:1113-1141`). The full projection's expanded
  aggregation scope is covered by the non-neighbour-in-included-file fixture
  (`:1225-1310`).

### New findings

None.

### Concur

- `ReferenceAggregation` continues to produce valid frozen `Edge`/`EdgeOrigin`
  values: canonical endpoints via `Edge.of`, one origin per directed pair,
  counts equal to the sum of origin counts, no local/self file/group edges, and
  deterministic ordering (`ReferenceAggregation.kt:11-83`).
- Direct core tests cover aggregation counts/origins, the file-URI fallback for
  an entity without a parent, same-file/same-group suppression, the no-group
  file case, reverse input order, and deterministic output
  (`ReferenceAggregationTest.kt:12-123`). The expanded `ContainmentBuilder`
  assertions also confirm edge/order/count/origin invariants
  (`ContainmentBuilderTest.kt:282-350`).
- `ContainmentBuilder` behavior remains compatible for valid expansions: it
  still rejects missing file locations and reference origins, still creates the
  same entity→file→group containment, and still sorts edges deterministically.
  The shared helper changes only the aggregation implementation
  (`ContainmentBuilder.kt:27-70`).
- `fullEntityExpansion` derives file/group aggregation from all snapshot entity
  `REFERENCES`, filtered to aggregation edges whose file/group endpoints are in
  the projected set, while entity `REFERENCES` and `CONTAINS` edges remain
  scoped to the projected entity closure (`RealGraphService.kt:765-831`). The
  non-neighbour test confirms that a reference from a non-projected entity in an
  included file contributes file/group aggregation but not an entity
  `REFERENCES` edge, and that `node/get`, `expand`, same-revision `open` agree
  on those aggregation edges (`RealGraphServiceTest.kt:1225-1310`).
- First and changed-revision `graph/open` edge sets are exactly equal to each
  other and to `node/get`, `node/expand`, and same-revision `open` in the exact
  test, and dot matches the fully covered graph fixture
  (`RealGraphServiceTest.kt:1113-1141`). In the local non-neighbour fixture,
  entity references are intentionally scoped, so the aggregation edge sets are
  compared with parsed dot rather than requiring graph-wide entity references to
  appear in a local expansion (`:1281-1309`). This matches the prior
  graph-wide-dot/local-projection contract.
- `DotWriter` remains deterministic and valid Graphviz: nodes/groups are sorted,
  edges are ordered by kind/from/to, derived aggregation endpoints are existing
  snapshot nodes, labels are escaped, and the output parses as undirected
  `--` edges with correct counts (`DotWriter.kt:10-62`;
  `DotWriterTest.kt:59-80`; `RealGraphServiceTest.kt:1347-1368`).
- Discovery pinning/recency refresh and status close behavior remain as approved
  in Round 8; the accessor change does not alter production
  `cachedDiscovery`/`cacheDiscovery` (`RealGraphService.kt:137-148`,
  `:540-570`).
- No frozen `api` changes are present; the socket envelope, request-id echo,
  `EngineError`/protocol mapping, `Expansion`/`Edge` JSON shapes, and
  `graph/dot` string contract are unchanged. `nodeKinds` remains all returnable
  kinds; `expandableNodeKinds` remains `[ENTITY]`; FILE/GROUP expansion stays
  explicitly unsupported. The accessors remain off the wire and ticket 010's
  separate build, and no mutable production test seam was added.
- No comments were added, and no build, test, or formatter was run for this
  review (green handover assumed).

### Dissent

None.

**Summary for the orchestrator:** Round 9 is Approved. `discoveredForTest` is
now non-mutating via `peekDiscovery` with an eviction-order regression test;
`ContainmentBuilder` uses the shared `ReferenceAggregation` while preserving its
validation/containment behavior; first/changed `graph/open` matches the
projection/read paths and dot; the full projection's all-reference aggregation
scope is covered by the non-neighbour fixture and direct core tests; and
`DotWriter` remains deterministic and valid. The frozen `api`/wire contract is
unchanged, all internal accessors remain non-wire and non-mutating, and no new
api-fidelity finding remains.

## Round 10

**Verdict:** Approved.

The projection-aggregation-scope correctness fix is coherent and does not
introduce a new api-fidelity problem. `ReferenceAggregation` accepts an optional
projected-file scope and applies it after resolving each reference origin's file
but before deriving either file or group aggregation, so a group edge cannot be
created from a file outside the projection. `fullEntityExpansion` passes the
projected file set, retains non-neighbour entities in included files, and still
keeps entity `REFERENCES`/`CONTAINS` scoped. The new outside-file/included-group
test verifies that the projection has no orphan group edge while
`graph/dot` remains global. Frozen `api` types and wire shapes are unchanged,
accessors remain non-wire, and dot output remains deterministic.

### Round 9 correctness disposition

- **Projection aggregation scope — Concur (addressed).** The optional
  `fileScope` is consulted after `fileFor` resolves both endpoints and before
  `fileOrigins`/`groupOrigins` are merged
  (`analysis-engine/core/src/main/kotlin/dev/codenb/core/ReferenceAggregation.kt:12-42`).
  An origin is skipped unless both files are in scope, so a same-group but
  outside-file reference cannot contribute an orphan `GROUP_REFERENCES` edge.
- **Full projection wiring — Concur.** `fullEntityExpansion` passes
  `files.keys` to `ReferenceAggregation` and then retains the projected
  file/group endpoint filters (`RealGraphService.kt:812-825`). Entity
  `REFERENCES` and containment remain scoped to `entityIds`/projected
  files/groups (`:775-810`, `:826-833`).
- **Coverage — Concur.** `projectionAggregatesReferencesFromIncludedFiles`
  confirms that a non-neighbour entity in an included file contributes
  file/group aggregation without adding its entity reference
  (`RealGraphServiceTest.kt:1226-1310`).
  `projectionDoesNotAggregateOutsideProjectedFiles` opens A2/B2 in the same
  groups but outside the A projection, asserts those files/entities are absent
  from the projection, confirms the projection's A→B file and group edges have
  count 1 and are mutually derivable, and confirms global dot still reports the
  A2→B2 file edge and group count 2 (`:1313-1391`).

### New findings

None.

### Concur

- Scoped aggregation still yields valid frozen `Edge`/`EdgeOrigin` values:
  endpoints are canonical `Edge.of` keys, origins are unique directed file/group
  pairs, counts equal origin sums, same-file/same-group/self edges are
  suppressed, and output order is deterministic
  (`ReferenceAggregation.kt:27-42`, `:70-87`).
- The file scope is applied consistently to `FILE_REFERENCES` and
  `GROUP_REFERENCES`: both endpoints must be projected files, so every surviving
  group edge is backed by file edges in the same projection; the projection
  test explicitly checks this via `assertGroupAggregationDerivable`
  (`RealGraphServiceTest.kt:1351-1391`).
- Non-neighbour entities in included files still contribute aggregation because
  `fullEntityExpansion` passes all snapshot nodes/reference edges to the scoped
  helper; outside files in the same groups are excluded before aggregation, so
  there is no group-only orphan (`RealGraphService.kt:783-825`;
  `RealGraphServiceTest.kt:1290-1309`, `:1344-1369`).
- The projection contract is coherent: a full entity expansion is local to the
  projected files/groups and entity closure, while `DotWriter` intentionally
  derives aggregation from the whole snapshot and has no file scope
  (`DotWriter.kt:10-22`). The outside-file test verifies both sides of that
  distinction (`RealGraphServiceTest.kt:1357-1369`).
- `ContainmentBuilder` continues to call `ReferenceAggregation` without a file
  scope for its initial expansion, where all files are present in the contained
  node set (`ContainmentBuilder.kt:41-70`). Its prior validation and containment
  behavior are unchanged.
- First/changed-revision `open`, `node/get`, `node/expand`, and same-revision
  `open` remain covered by the exact first/changed equality test; the new scope
  does not change initial open behavior because those expansions contain only
  their own referenced files (`RealGraphServiceTest.kt:1113-1141`).
- `DotWriter` remains deterministic and valid Graphviz, with sorted nodes/edges,
  escaped labels, existing endpoints, and global aggregation counts
  (`DotWriter.kt:10-62`; `DotWriterTest.kt:59-80`;
  `RealGraphServiceTest.kt:1347-1368`).
- No frozen `api` changes are present; the socket envelope, request-id echo,
  error mapping, `Expansion`/`Edge` JSON shapes, and `graph/dot` string contract
  are unchanged. Internal accessors remain off `GraphService`, JSON, and ticket
  010; no production test hook was added, and no comments or build/test/formatter
  changes were made by this review (green handover assumed).

### Dissent

None.

**Summary for the orchestrator:** Round 10 is Approved. Scoped
`ReferenceAggregation` correctly filters reference origins by projected files
before deriving file/group edges, so projections cannot contain group-only
orphans while non-neighbour references inside included files still aggregate.
`fullEntityExpansion` passes the projected file set, the outside-file/included-
group test proves projection/dot coherence, frozen `api`/wire shapes remain
unchanged, accessors remain non-wire, and dot output remains deterministic and
valid. No new api-fidelity finding remains.
