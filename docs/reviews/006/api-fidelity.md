## Round 1

**Verdict:** Changes requested.

The store and cache stay on the correct side of the frozen boundary: all graph
types come from `dev.codenb.api`, the cache types live in core, edge keys are
the canonical `Edge.key`, and nodes and edges accumulate per `GraphId` without
removal. The cache-key/store consistency and the handling of containment counts
below need tightening before the store is a faithful merge of expansion
results.

### Findings

1. **`expand` can cache and return an expansion that does not match the requested key**

   - Location: `analysis-engine/core/src/main/kotlin/dev/codenb/core/GraphStore.kt:30-45`
     (`GraphStore.expand`).
   - Problem: On a miss, only `expansion.graphId` is checked. `expansion.node.id`
     may differ from `nodeId` and `expansion.revision` may differ from
     `revision`, yet the expansion is merged and cached under the requested key,
     so a later call for the requested node/revision returns a hit for different
     data. On a hit, the cached expansion is returned without any merge, so an
     `ExpansionCache` preloaded or shared outside this store (the interface is
     public and injectable, and the ticket explicitly allows a disk cache behind
     it) leaves `snapshot(graphId)` missing the nodes and edges that the hit
     describes. Either path lets the cache key, the expansion, and the graph
     disagree, which breaks node scoping and the guarantee that a cache hit is
     just a faster expansion.
   - Suggested fix: Before merging and caching, require
     `expansion.node.id == nodeId` and `expansion.revision == revision` in
     addition to the graph id. On a hit, either merge the expansion (the merge
     is idempotent) or make the cache store-owned and only populated through a
     merge. Add tests for a mismatched node, a mismatched revision, and a
     pre-populated cache.

2. **Merging a CONTAINS edge with and without origins can reduce its count**

   - Location: `analysis-engine/core/src/main/kotlin/dev/codenb/core/GraphStore.kt:104-142`
     (`MutableGraph.mergeEdge`).
   - Problem: The frozen contract permits CONTAINS edges both with no origins
     (aggregate `count`) and with origins (`count` equal to the origin total).
     `mergeEdge` switches to the origin representation whenever either side has
     origins and recomputes `count` from those origins, discarding the other
     side's aggregate count. Merging an originless CONTAINS edge of count 9 with
     an origin-bearing one of count 2 produces count 2: the store has accepted
     nine observations but reports two. The merge is lossy and not monotone in
     the count metadata the model keeps on edges.
   - Suggested fix: Establish one containment representation per graph and
     reject a mixed-origin merge as ambiguous, or normalize containment to
     originless and merge aggregate counts with `max` (preserving the frozen
     `Edge` invariant), rather than silently dropping the larger count. Add a
     mixed-origin CONTAINS test in both merge orders.

### Concur

- Node and edge kinds are exactly the frozen `NodeKind` and `EdgeKind`; the
  store introduces no shadow graph vocabulary.
- Edge identity uses the canonical `EdgeKey`; endpoints are canonical because
  `Edge` and `Edge.of` enforce ordering, and self-edges cannot enter the store
  because `Edge`, `EdgeKey`, and `EdgeOrigin` reject them.
- `GraphSnapshot` is built from fresh sorted lists on every call; the internal
  maps and `MutableGraph` are not exposed, and stored `Node`/`Edge` values are
  not mutated after insertion.
- Cache types (`CacheKey`, `ExpansionCache`, `InMemoryExpansionCache`) and
  `GraphSnapshot` are core-only and are not referenced from the frozen `api`
  module.
- Graphs are isolated by `GraphId`, cache keys include graph and node ids, and
  `snapshot` of an unknown graph returns an empty snapshot without registering a
  graph.
- Merge is additive: nodes and edges are only inserted or replaced by key,
  reference origins merge per directed pair with per-origin maximum and a
  recomputed total, and edge ordering is deterministic.
- Reference-edge merge is idempotent and keeps directed origins distinct while
  navigation remains undirected through the canonical key.
- Core imports only `dev.codenb.api` and JDK collections; there are no LSP,
  filesystem, or renderer dependencies, and the core build file adds none.
- The LRU cache uses access-order `LinkedHashMap`, is bounded by entry count,
  and recomputes on a miss; tests cover merge idempotence, conflicting nodes,
  revision changes, eviction, graph isolation, deterministic ordering, and
  concurrent merges.
- The new source and test files contain no comments.

### Dissent

- Finding 1: the cache key, the cached expansion, and the graph store must be
  kept consistent for both hits and misses.
- Finding 2: mixed-origin CONTAINS merges must not reduce the accepted count;
  the store needs a defined containment merge policy.

## Round 2

**Verdict:** Approved.

Both Round 1 findings are addressed, and the fixes introduce no new
api-fidelity problems.

### Finding disposition

- **Finding 1 — Concur (addressed).** `GraphStore.expand` now validates
  `graphId`, `node.id`, and `revision` on both the miss and hit paths
  (`GraphStore.kt:50-65`) and merges a cached expansion before returning it
  (`GraphStore.kt:37-42`). New tests cover a pre-populated cache, mismatched
  graph/node/revision on compute, a mismatched cached node, and compute/cache
  failure paths.
- **Finding 2 — Concur (addressed).** `mergeEdge` now takes the aggregate maximum
  when either CONTAINS operand lacks origins, so a mixed count 10/2 merge yields
  10 in both orders and remains a valid originless CONTAINS edge; both-origin
  merges still union origins with a per-origin maximum and a recomputed total
  (`GraphStore.kt:128-161`). The new mixed-origin test asserts both orders, the
  preserved count, and the empty-origin result.

### Concur

- A cache hit validates the expansion against the requested key, merges it into
  the graph, and only then returns it; the cache can no longer leave the graph
  snapshot incomplete.
- The expansion key checks cover graph id, node id, and revision, so wrong data
  cannot be stored or returned under a different cache key.
- Mixed-origin CONTAINS merges are monotone in count and contract-valid: the
  result is an originless CONTAINS edge whose count is the maximum aggregate.
  Both-origin merges retain directed origins and satisfy count equal to the sum
  of origins.
- Snapshots are still fresh sorted lists; internal maps and `MutableGraph` are
  not exposed, and stored `Node`/`Edge` values are not mutated after insertion.
- Cache types remain in core; no cache type or cache reference was added to
  `api`.
- Core still imports only `dev.codenb.api` and JDK collections; no LSP,
  filesystem, or renderer dependency was introduced.
- The new source and tests contain no comments.

### Dissent

- None.

### New findings

None.
