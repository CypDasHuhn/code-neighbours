# Notes

Working notes, pinned decisions, and known risks.

## Pinned decisions

1. **Entity** — any declaration that code outside its file can reference. Local
   and file-private declarations are not entities.
2. **Neighbours** — incoming and outgoing references are both neighbours. The
   graph is undirected for navigation and keeps direction only as metadata.
3. **Containment** — every entity has a file and a group. A group is either a
   directory or a namespace/package, chosen per graph. The nodes are part of the
   graph, not just labels.
4. **Per-LSP graphs** — one graph per language server. Files are referencable
   only within their own graph. No cross-LSP edges.
5. **LSP only** — no tree-sitter or second analysis backend. LSP facts are the
   source of truth. Declaration text is read only for visibility and signature
   fingerprints.
6. **Lazy expansion** — start at the seed, expand on visit, cache results. No
   live updates.
7. **Transport** — engine over a Unix socket; renderer server over HTTP to the
   browser. The browser never speaks to the socket.
8. **Renderer stack** — Kotlin/JVM Ktor server and Kotlin/JS Canvas web app.
9. **Layout** — orthogonal connectors, no curves or diagonals. Crossroads are
   intersections/junctions, not graph nodes.
10. **Git** — initial scaffold commit, ticket branches, conventional commits,
    precise staging, no push unless asked.

## Risks

- Kotlin visibility is not in `DocumentSymbol`. The modifier filter is
  conservative and must be exercised against real sources.
- Semantic tokens are required for outgoing references. A server that lacks them
  must fail loudly instead of returning an incomplete graph.
- Stable overload identity depends on a signature fingerprint. If the LSP
  detail is insufficient, the range fallback is marked unstable.
- Orthogonal routing can create false crossroads. Prefer crossing-free routes
  and review any unavoidable intersection.
- Lazy layout must not move the active node when a new node is expanded.

## Glossary

- **Seed** — the entity the session starts at.
- **Expansion** — the immediate neighbours of one node plus the connecting edges.
- **Group** — a directory or namespace node.
- **Connector** — an axis-aligned segment in the rendered graph.
- **Crossroad** — an intersection or junction on connectors.
- **Graph id** — the identity of one LSP's graph for one workspace.
- **Revision** — the content hashes that make a cached expansion valid.
