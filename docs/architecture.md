# Code Neighbours — Architecture

Target design. The source of truth for module boundaries, transport, and the
analysis lifecycle.

## Goal

Given a codebase root, an LSP-backed language, and a seed entity, produce a
walkable neighbour graph:

- the seed entity,
- its containing file,
- its directory or namespace,
- every entity that references it or is referenced by it,
- and the file/directory-or-namespace nodes those entities belong to.

The browser view starts at the seed and expands one node at a time. Every
expansion is cached. There is no live update loop.

## Boundary

Two processes:

```
analysis-engine (Kotlin/JVM) ── Unix socket ── renderer server (Kotlin/JVM)
                                                      │
                                                   HTTP
                                                      │
                                              Kotlin/JS browser app
```

- The engine owns LSP communication, entity discovery, expansion, caching, and
  graph serialization. It does not know about browsers or drawing.
- The renderer server is the only client of the engine socket. It exposes a
  plain HTTP API and serves the compiled Kotlin/JS app.
- The browser never touches the Unix socket. It only uses HTTP.
- The engine and renderer are separate Gradle builds and separate top-level
  directories. They communicate through the frozen socket contract, not through
  shared source.

## Repository layout

```
analysis-engine/
  api/          graph and socket contract
  core/         graph store, lazy expansion, cache, config, grouping
  lsp/          stdio JSON-RPC client and LSP operations
  frontend/     language registry; Kotlin first, then JS/TS/Vue/C#
  cli/          seed query, one-shot expansion, JSON and dot output
  server/       per-project Unix-socket daemon
renderer/
  shared/       Kotlin Multiplatform transport DTOs for server and web
  server/       Ktor/JVM, Unix-socket client, HTTP API, static assets
  web/          Kotlin/JS, Canvas 2D graph view and keyboard navigation
docs/
  architecture.md
  neighbour-model.md
  workflow.md
  manual-test.md
  notes.md
  tasks/
  reviews/
```

The engine modules follow the `code-intent` dependency line: `api` is frozen;
`core` depends on `api`; `lsp` and `frontend` provide language facts; `cli` and
`server` are hosts. Detection code does not belong in the engine core because
there is no detection layer here.

## Analysis lifecycle

1. The caller supplies a root, an LSP selection, and a seed entity.
2. The engine starts or attaches to one LSP process for that root and language.
3. The engine discovers the workspace files, excluding anything gitignored.
4. The seed is resolved to an entity node. With no seed, a `workspace/symbol`
   lookup or the CLI's `file#qualifiedName` selector is used.
5. Expanding a node returns its immediate neighbours and the edges between
   them. The result is cached.
6. The renderer requests expansions through the socket as the user moves.
7. A new session can reuse cached expansions from disk, keyed by graph and file
   content hashes.

There is no file watcher and no push channel. Content changes invalidate the
relevant cache entries on the next session.

## LSP strategy

LSP is the only source of code intelligence. Tree-sitter is not used.

- One LSP process and one graph per language server. Files are referencable only
  within their LSP's graph. There are no cross-LSP edges.
- The first frontend is Kotlin (`kotlin-lsp` or a configured compatible server).
  Later frontends add JavaScript/TypeScript, Vue, and C# without changing the
  graph model.
- The client speaks JSON-RPC over stdio. It owns initialization, capability
  negotiation, document open/close, timeouts, and shutdown.
- The workspace file set is discovered by walking the root and applying gitignore
  rules. A file outside the set is never opened or queried.
- Entity discovery uses `textDocument/documentSymbol` per file. A declaration is
  an entity when code outside its file can reference it.
- Incoming neighbours: `textDocument/references` on the entity declaration.
- Outgoing neighbours: `textDocument/semanticTokens/full` identifies reference
  tokens in the entity span; `textDocument/definition` resolves each candidate to
  a declaration. Only resolved in-workspace entities become neighbours.
- Locations are mapped back to entities by looking up the enclosing declaration
  in the file's symbol tree. A reference to a local or private declaration does
  not create an entity edge.
- LSP results are the only facts. A failed or unavailable LSP operation degrades
  the expansion with an explicit error; the engine does not invent facts.

Call hierarchy is an optional optimization for callable entities. It must not
change the graph contract because it covers only a subset of entity kinds.

## Graph model

The full model is in [neighbour-model.md](neighbour-model.md). In short:

- Nodes: `ENTITY`, `FILE`, `GROUP` where `GROUP` is a directory or namespace.
- Containment edges: entity to file, file to group.
- Reference edges: entity to entity. File and group edges are aggregations of
  entity edges.
- Reference adjacency is undirected for navigation. Incoming and outgoing
  references both count as neighbours. Direction and reference counts remain
  metadata on the edge.
- A graph is scoped to one LSP. Node ids are stable within that graph.

Entity identity is a qualified name plus kind plus a signature fingerprint,
scoped by the containing file and LSP. Overloads must not collapse. If a
signature cannot be recovered, the declaration range is folded into the
fingerprint and the entity is marked unstable.

## Lazy expansion and cache

Expansion is node-local and monotone: expanding a node only adds nodes and
edges. The store deduplicates by node id and edge key.

- Entity expansion: containing file, group, incoming entities, outgoing
  entities.
- File expansion: entities in the file, its group, files connected by entity
  references.
- Group expansion: files and entities in the group, connected groups.

The cache stores expansion results per graph. In-memory caching is mandatory;
disk persistence is optional and keyed by the graph's LSP/root identity and the
content hashes of the files that contributed a result. No stale result may be
served after a hash mismatch. Cache eviction is by size limit; correctness does
not depend on a cache hit.

## Transport

Engine socket:

- Unix domain socket, one per project/LSP.
- NDJSON JSON-RPC 2.0.
- Client methods: `initialize`, `graph/open`, `node/expand`, `node/get`,
  `graph/dot`, `shutdown`.
- Server notifications: `cint/error` for recoverable failures.
- `initialize` returns the protocol version, root, LSP id, and node kinds.
- `node/expand` returns the requested node, its neighbours, and the connecting
  edges, with the graph id and a content revision.

Renderer HTTP API:

- `GET /` serves the web app.
- `GET /api/status` reports engine connection and graph identity.
- `POST /api/open` opens a graph at a seed entity.
- `POST /api/expand` expands one node.
- `GET /api/graph.dot` returns the Graphviz export for the cached graph.

HTTP is plain request/response. There are no WebSocket or SSE endpoints in v1.

## Renderer

- `renderer/server` is a Ktor/JVM application. It spawns or attaches to the
  engine daemon, validates requests, and maps socket results to HTTP JSON.
- `renderer/web` is Kotlin/JS. It draws with Canvas 2D and reads keyboard events
  from the DOM. It uses an HTTP client, not a socket.
- The browser app owns presentation state: active position, viewport, and the
  local copy of the expanded graph.
- Profiles, styling, and graph presets are renderer concerns and never trigger
  engine re-analysis.

## Layout and navigation

The graph is drawn as an orthogonal map. Nodes are boxes. Connectors are
axis-aligned segments. There are no curves or diagonal segments.

- A position is either a node or a point on a connector.
- Direction keys move to the next node or crossroad along the current connector
  in that direction.
- Bends are transparent. Only intersections and junction points are crossroads.
- At a crossroad, the direction key selects a departing segment in that
  direction. If no segment exists, the move is ignored.
- The active node is always visible. The viewport follows it.
- Layout is deterministic for a given graph and expansion order. Newly expanded
  nodes must not move the active node.

Graphviz is an export format and a static rendering path, not the source of the
web layout. The web layout is implemented in the renderer so navigation and
crossroads are under our control.

## Configuration

A project config file is YAML and is owned by the engine. The renderer has its
own presentation config.

Engine config:

```yaml
lsp:
  id: kotlin
  command: ["kotlin-lsp", "--stdio"]
  rootMarkers: [".git", "settings.gradle.kts", "build.gradle.kts"]

workspace:
  include: ["**/*.kt", "**/*.kts"]
  exclude: []

graph:
  grouping: namespace   # namespace | directory
  followSymlinks: false
  cacheDir: null
```

The renderer config covers layout spacing, colors, key bindings, and starting
viewport. Renderer config changes never re-run analysis.

## Testing

- Engine unit tests: node identity, visibility filtering, symbol tree lookup,
  expansion deduplication, cache invalidation.
- LSP contract tests: a fake stdio server that exercises initialize, document
  symbols, references, definition, and semantic tokens.
- Socket tests: NDJSON framing, request ids, errors, shutdown.
- Renderer server tests: HTTP mapping and engine client behavior against a fake
  Unix socket.
- Web tests: layout determinism, connector/crossroad traversal, keyboard moves,
  and lazy expansion requests.
- Manual: a real Kotlin project with a known reference shape.

No test may depend on a live LSP unless it is explicitly marked as an LSP
integration test.

## Non-goals

- live updates or file watching
- cross-LSP edges
- tree-sitter or a second analysis backend
- name/type resolution outside LSP
- graph editing or annotations
- multi-user or remote access
- Graphviz as the interactive layout engine
- a nvim renderer

## Sequencing

1. Build scaffold and frozen graph/socket contract.
2. LSP client, workspace discovery, and Kotlin entity discovery.
3. Lazy expansion, graph store, cache, and containment/grouping.
4. Unix-socket daemon and CLI dot export.
5. Renderer server, Kotlin/JS graph view, and navigation.
6. End-to-end Kotlin vertical slice.
7. Additional language frontends.
