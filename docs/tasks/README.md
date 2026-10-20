# Tickets

One file per unit of work. Status is owned by the orchestrator. See
[../workflow.md](../workflow.md) for the format and pipeline, and
[../architecture.md](../architecture.md) for the target design.

The queue follows architecture.md sequencing: scaffold and contract, LSP and
entities, expansion and cache, daemon and dot, renderer, end-to-end Kotlin, then
more languages.

| id | name | status | depends-on | scope |
| --- | --- | --- | --- | --- |
| 001 | [repository and build scaffold](001-repository-and-build-scaffold.md) | done | — | Engine and renderer Gradle builds, module skeletons, justfile, README links. |
| 002 | [api graph contract](002-api-graph-contract.md) | done | 001 | Frozen node/edge/expansion/socket DTOs and JSON codec. |
| 003 | [lsp client and workspace](003-lsp-client-and-workspace.md) | done | 001 | Stdio JSON-RPC client, Kotlin LSP wiring, gitignore-aware file discovery. |
| 004 | [kotlin entity discovery and identity](004-kotlin-entity-discovery-and-identity.md) | done | 002, 003 | Document symbols, visibility filtering, qualified names, stable ids. |
| 005 | [neighbour expansion](005-neighbour-expansion.md) | done | 002, 003, 004 | Incoming references and outgoing semantic-token/definition expansion. |
| 006 | [graph store and lazy cache](006-graph-store-and-lazy-cache.md) | todo | 002, 005 | Per-LSP store, deduplication, expansion cache, revision checks. |
| 007 | [containment and grouping](007-containment-and-grouping.md) | todo | 002, 004 | Entity→file→group nodes and file/group reference aggregation. |
| 008 | [unix socket daemon](008-unix-socket-daemon.md) | todo | 002, 006 | NDJSON JSON-RPC daemon, permissions, lifecycle, error mapping. |
| 009 | [cli and dot export](009-cli-and-dot-export.md) | todo | 005, 006, 007 | Seed selection, one-shot graph output, Graphviz writer. |
| 010 | [renderer server](010-renderer-server.md) | todo | 008 | Ktor server, Unix-socket client, HTTP API, static assets. |
| 011 | [web graph rendering](011-web-graph-rendering.md) | todo | 002, 010 | Kotlin/JS Canvas graph, orthogonal layout, node/edge drawing. |
| 012 | [web navigation](012-web-navigation.md) | todo | 011 | Active node/connector, direction keys, crossroads, lazy HTTP expansion. |
| 013 | [end-to-end kotlin](013-end-to-end-kotlin.md) | todo | 009, 012 | Real Kotlin fixture, manual checks, docs reconciliation. |
| 014 | [additional language frontends](014-additional-language-frontends.md) | todo | 013 | JavaScript/TypeScript, Vue, C# frontends on the same graph contract. |
| 015 | [lsp lifecycle hardening](015-lsp-lifecycle-hardening.md) | todo | 003 | Bounded close, no lock-held awaits, callback state, git failure handling. |

## Resolved decisions

1. **Entity** — externally referencable declarations only. Locals and private
   declarations are excluded. Affects 004.
2. **Neighbours** — incoming and outgoing references are both neighbours; the
   graph is undirected for navigation. Affects 002, 005.
3. **Containment** — entity→file and file→group edges are part of the graph; a
   group is either directory or namespace. Affects 002, 007.
4. **Per-LSP graph** — no cross-LSP edges. Affects 003, 006.
5. **No tree-sitter** — LSP is the only intelligence source. Affects 003, 005.
6. **Lazy cached expansion** — no live updates. Affects 006, 012.
7. **Renderer transport** — engine Unix socket, renderer HTTP, browser HTTP only.
   Affects 008, 010.
8. **Renderer stack** — Kotlin/JVM server and Kotlin/JS Canvas web app. Affects
   001, 010, 011.
