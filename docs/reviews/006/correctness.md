## Round 1

**Verdict:** Changes requested.

The store and cache have the right shape: graphs are keyed by `GraphId`, merges are atomic and monotone for the covered edge shapes, node conflicts and missing edge endpoints are rejected, node/edge/origin ordering is deterministic, the cache is a size-bounded access-order LRU keyed by graph/node/revision, and compute/merge failures are not cached. The remaining problems are a cache hit that can leave the graph incomplete, cache keys that trust unvalidated expansion content, mixed-origin `CONTAINS` merges that can decrease an edge count, and boundary/concurrency coverage that does not exercise these paths.

### Findings

1. **A cache hit returns the expansion without merging it into the graph**

   - Location: `analysis-engine/core/src/main/kotlin/dev/codenb/core/GraphStore.kt:36-44`.
   - Problem: `cache.get(key)?.let { return it }` returns before `merge`. This is consistent only because `GraphStore` itself inserts cache entries after merging. The constructor accepts any `ExpansionCache` and exposes it as `val cache`, so a shared, pre-populated, or disk-backed cache (the interface the ticket reserves for disk persistence) can hit in a fresh `GraphStore` whose `MutableGraph` contains none of the expansion's nodes or edges. `expand` returns the expansion while `snapshot`/`graph` omit it, so the store's graph is incomplete on a hit.
   - Suggested fix: Merge the cached expansion (idempotently) before returning it, or validate/rebuild the graph from the cache entry. Add a test that pre-populates a cache, constructs a new store with it, expands the node, and asserts the snapshot contains the expansion.

2. **`expand` caches under the requested key without validating the node id or revision**

   - Location: `analysis-engine/core/src/main/kotlin/dev/codenb/core/GraphStore.kt:36-44`, especially the graph-id check at `39-41`.
   - Problem: Only `expansion.graphId` is checked. If `compute` returns an expansion for a different node id or with a different `revision`, the result is merged and stored under `(requested graphId, requested nodeId, requested revision)`. A later lookup with the requested key then serves another node's or another revision's data. That is exactly the stale/mismatched result the revision key is meant to prevent, and `Expansion.revision` is currently unused by the store.
   - Suggested fix: Require `expansion.node.id == nodeId` and `expansion.revision == revision` before merging/caching (or derive the key from the returned expansion instead of taking `revision` up front), and validate cached entries on hit as well. Add mismatch tests for both fields.

3. **Merging a `CONTAINS` edge that has origins with one that does not discards the larger count**

   - Location: `analysis-engine/core/src/main/kotlin/dev/codenb/core/GraphStore.kt:104-142` (`mergeEdge`).
   - Problem: The two empty-origin cases use `maxOf(existing.count, incoming.count)`, but when exactly one side has origins the other side's count is dropped entirely; the result is the sum of the origin-bearing side. For example an existing originless `CONTAINS` edge with count 10 merged with an origin-bearing edge of count 2 becomes 2, and merging a further originless edge with count 12 still yields 2 rather than 12. `CONTAINS` may legally carry origins (`Edge.kt:62-67` only requires origins for non-`CONTAINS` edges), so this is a reachable valid input. Edge counts therefore are not monotone even though the store never removes the edge.
   - Suggested fix: Handle the mixed case explicitly, e.g. preserve at least `max(existing.count, incoming.count)` alongside the merged origins, or reject/forbid origins on `CONTAINS` at the contract boundary and keep one representation. Add a test merging originless and origin-bearing `CONTAINS` edges in both orders and assert the count does not decrease.

4. **Concurrency and cache-failure boundary coverage is incomplete**

   - Location: `analysis-engine/core/src/test/kotlin/dev/codenb/core/GraphStoreTest.kt:331-368`, `GraphStoreTest.kt:251-280`, `ExpansionCacheTest.kt`.
   - Problem: The only concurrency test repeatedly merges disjoint, non-overlapping expansions; it does not exercise concurrent `expand`/cache access or concurrent `snapshot`, and it would not catch a lost update in cache/graph coordination. The LRU test checks recomputation counts but never asserts that eviction left the graph's nodes and edges intact. There is no test for a pre-populated/shared cache hit, a node-id or revision mismatch, or mixed-origin `CONTAINS` merging, so Findings 1-3 are unobserved. The cache tests are entirely single-threaded.
   - Suggested fix: Add concurrent `expand`/`snapshot` tests (including the same key from multiple threads), assert the store never loses nodes/edges after eviction, and add the three boundary tests named above. A concurrent cache test should verify hit/miss and eviction under contention.

### Concur

- Graphs are isolated by `GraphId`; the same `NodeId` may carry different data in different graphs, and `graphIds`/`snapshots` are deterministically ordered.
- `merge` adds nodes to a working copy, validates every edge endpoint against the combined existing/expanded node set before assigning, and only assigns the new node/edge maps at the end, so a conflicting node or missing endpoint leaves the graph unchanged.
- Duplicate node ids with different data are rejected by full data equality, while identical repeated merges are idempotent.
- Reference edges retain directed origins, merge by directional pair with the maximum per origin, and sum the merged origin counts; repeated expansion of the same result is idempotent and merge order is independent when both edges carry origins.
- Originless `CONTAINS` merges use the maximum count, and node/edge/origin ordering is deterministic.
- The store only grows; no node or edge is removed.
- `InMemoryExpansionCache` is an access-order, size-bounded LRU with synchronized `get`/`put`/`remove`/`clear` and a correct eviction test.
- `GraphStore` serializes merge/snapshot/graph enumeration on one lock, and snapshots allocate fresh node and edge lists from the store.
- Compute, graph-id validation, merge, and cache-put failures do not leave a partially cached or partially merged result.
- The ticket-required merge, repeated expansion, conflict, revision, eviction, cache miss, graph isolation, and deterministic-order tests are present, and the concurrency test exercises repeated concurrent merges.
- The authored source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review; the handover is assumed green.

### Dissent

- Finding 1: a cache hit must still leave the graph store containing the expansion.
- Finding 2: cached data must be validated against the node id and revision it is stored under.
- Finding 3: merging a valid originless `CONTAINS` edge with an origin-bearing one must not decrease the edge count.
- Finding 4: concurrency and the cache/mixed-origin boundaries must be exercised by tests.

## Round 2

**Verdict:** Changes requested.

The four Round 1 findings are addressed: cache hits now validate and merge before returning, `expand` validates graph id, node id, and revision for both computed and cached expansions, mixed-origin `CONTAINS` merges keep the larger count, and the new tests cover cache get/put/compute failures, eviction retention, and concurrent expand/snapshot. One new correctness problem remains: the node conflict rule still treats source span as identity, so a legitimate revision change that moves the same entity to a new line or column makes the next expansion fail instead of updating the node.

### Finding disposition

1. **Cache hits merge into the store — Concur.** `GraphStore.kt:36-42` now validates the cached expansion before `merge(cached)` and returns it. `cacheHitMergesCachedExpansionIntoStore` pre-populates a fresh store's cache and asserts the snapshot contains both the seed and the neighbour.
2. **Expansion key validation — Concur.** `validate` at `GraphStore.kt:50-65` checks graph id, node id, and revision. The computed graph-id, node-id, and revision mismatch tests and the cached node-id mismatch test fail the expansion without merging or caching.
3. **Mixed `CONTAINS` count — Concur for the count requirement.** `GraphStore.kt:136-143` now uses `maxOf(existing.count, incoming.count)` whenever either side lacks origins, so the count cannot decrease. `mixedOriginContainsMergePreservesLargerCountInBothOrders` verifies both orders. The result is normalized to an originless edge, which is consistent with the test and with `CONTAINS` origins being optional; origin metadata is not retained in this mixed case.
4. **Boundary and concurrency coverage — Concur.** Compute failure, cache get failure, cache put failure, eviction graph retention, cache-hit-after-eviction, and concurrent expand/snapshot tests are present and exercise the stated paths.

### New findings

1. **A revision change that only moves a node's source span is treated as a node conflict**

   - Location: `analysis-engine/core/src/main/kotlin/dev/codenb/core/GraphStore.kt:112-122` (`addNode`), with node construction in `analysis-engine/core/src/main/kotlin/dev/codenb/core/EntityIdentity.kt:10-21` and the identity rule in `docs/neighbour-model.md:43-56`.
   - Problem: `addNode` rejects any same-id node whose full `Node` value differs. `Node` equality includes `span`, but the model explicitly keeps line and column out of entity identity. After an edit above an entity, or any edit that moves it, the next expansion at the new revision produces the same id with a new span; the store still holds the old node because it never removes nodes. `merge` then throws `IllegalArgumentException` and the whole expansion is discarded, so a normal span-only edit makes the entity, or any later expansion that includes it as a neighbour, unexpandable for the lifetime of the store. The current conflict test only changes the label, so this path is unobserved.
   - Suggested fix: Separate identity-defining fields from mutable location metadata. For an existing id with matching kind, file URI, qualified name, signature fingerprint, and label, refresh `span` (and other non-identity metadata such as `parent` or `unstable`) instead of rejecting; keep rejecting genuine identity conflicts. Add a test that expands a node, changes only its span under a new revision, expands again, and asserts success with the updated span.

### Concur

- The Round 1 findings are addressed as described above, including the cache-hit merge, all three key-field checks, the monotone mixed `CONTAINS` count, and the requested failure/concurrency tests.
- The cache-hit path validates before merging, so a mismatched cache entry cannot update the wrong graph or node.
- The merge remains atomic: node conflicts, missing endpoints, and validation failures leave the existing node and edge maps unchanged.
- The added failure tests distinguish cache-get failure before compute, compute failure before merge/caching, and cache-put failure after a successful merge without caching.
- The LRU retention tests confirm eviction removes only the cache entry and leaves the graph's nodes and edges intact.
- The concurrent expand/snapshot test exercises shared keys, cache hits/misses, and snapshots while merges are in flight.
- No lock nesting was introduced: cache operations and store operations are serialized by their own locks in a non-overlapping order.
- The store still only grows, and node/edge/origin ordering remains deterministic.
- The authored source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review; the handover is assumed green.

### Dissent

- New Finding 1: a same-id node whose identity fields match but whose source span moved must be refreshed, not rejected, when expanding at a new revision.

## Round 3

**Verdict:** Approved.

The Round 2 finding is addressed. `addNode` now separates the entity identity tuple from mutable metadata, refreshes metadata for a same-id node instead of throwing, keeps the existing parent when the incoming parent is null, and still rejects genuine identity conflicts. The new tests cover moved spans, null-parent preservation with other metadata updates, and identity rejection, and the existing merge/cache/concurrency behavior is unchanged. No new correctness problems were found.

### Finding disposition

1. **Identity versus metadata separation — Concur.** `GraphStore.kt:127-135` compares id, kind, file URI, qualified name, and signature fingerprint, which matches the identity tuple in `docs/neighbour-model.md:43-56`. Label, span, parent, and unstable are not treated as identity.
2. **Moved span refreshes instead of throwing — Concur.** `mergeMetadata` at `GraphStore.kt:137-140` stores the incoming node with the parent fallback, so span, label, and unstable refresh. `movedEntityRefreshesSpanInsteadOfConflicting` merges an original and a span-moved node with the same identity and asserts the stored span is the moved one.
3. **Null incoming parent preserves the existing parent — Concur.** `incoming.parent ?: existing.parent` retains the stored parent while the rest of the incoming metadata is applied. `nullIncomingParentKeepsExistingParentAndRefreshesMetadata` checks the preserved parent together with the refreshed span, label, and unstable flag.
4. **Genuine identity conflicts still throw — Concur.** `conflictingNodeIdentityIsRejected` changes the qualified name under the same id and asserts an `IllegalArgumentException` with the original node still stored. Because the check runs before assignment, the failed merge leaves the graph unchanged.
5. **Tests cover the cases — Concur.** The three focused tests exercise refresh, parent preservation, and identity rejection. The moved-span test uses `merge` directly, but `expand` delegates to `merge` after key validation, so the revised merge behavior is the behavior under test.

### New findings

None.

### Concur

- The identity/metadata split is consistent with the model and with the existing `EntityIdentity` construction, where span, label, parent, and unstable are presentation/location metadata.
- Metadata refresh updates the whole incoming node while preserving only the existing parent on a null incoming parent, so the requested fallback does not accidentally preserve other stale fields.
- Same-id nodes with a different kind, file URI, qualified name, or signature fingerprint are still rejected, so overload and identity collisions are not silently collapsed.
- A failed identity merge is atomic and leaves the prior node unchanged.
- Merge ordering, edge-origin merging, cache validation/merging, LRU retention, and the concurrency behavior from earlier rounds are unaffected by this change.
- The store still never removes nodes or edges, and snapshot ordering remains deterministic.
- The authored source and tests contain no comments.
- No build, test, formatter, or `just` command was run for this review; the handover is assumed green.

### Dissent

- None.
