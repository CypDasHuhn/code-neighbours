package dev.codenb.core

import dev.codenb.api.Edge
import dev.codenb.api.EdgeKey
import dev.codenb.api.EdgeKind
import dev.codenb.api.EdgeOrigin
import dev.codenb.api.Expansion
import dev.codenb.api.GraphId
import dev.codenb.api.Node
import dev.codenb.api.NodeId
import dev.codenb.api.NodeKind
import dev.codenb.api.SourceSpan
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GraphStoreTest {
    private val graphA = GraphId("graph-a")
    private val graphB = GraphId("graph-b")

    private fun node(
        id: String,
        label: String = id,
    ): Node = Node(id = NodeId(id), kind = NodeKind.GROUP, label = label)

    private fun entity(
        id: String,
        span: SourceSpan = SourceSpan(0, 0, 0, 1),
        label: String = id,
        qualifiedName: String = "app.$id",
        signatureFingerprint: String = "class $id",
        parent: NodeId? = null,
        unstable: Boolean = false,
    ): Node =
        Node(
            id = NodeId(id),
            kind = NodeKind.ENTITY,
            label = label,
            qualifiedName = qualifiedName,
            fileUri = "file:///src/App.kt",
            signatureFingerprint = signatureFingerprint,
            span = span,
            parent = parent,
            unstable = unstable,
        )

    private fun expansion(
        graphId: GraphId,
        node: Node,
        neighbours: List<Node> = emptyList(),
        edges: List<Edge> = emptyList(),
        revision: String = "r1",
    ): Expansion = Expansion(graphId, revision, node, neighbours, edges)

    private fun referenceEdge(
        from: NodeId,
        to: NodeId,
        origins: List<EdgeOrigin>,
    ): Edge = Edge.of(EdgeKind.REFERENCES, from, to, origins.sumOf { it.count }, origins)

    private fun containsEdge(
        from: NodeId,
        to: NodeId,
        count: Int,
        origins: List<EdgeOrigin> = emptyList(),
    ): Edge = Edge.of(EdgeKind.CONTAINS, from, to, count, origins)

    @Test
    fun mergeIsIdempotent() {
        val a = node("a")
        val b = node("b")
        val expansion =
            expansion(
                graphA,
                a,
                listOf(b),
                listOf(
                    referenceEdge(a.id, b.id, listOf(EdgeOrigin(a.id, b.id, 2))),
                    containsEdge(a.id, b.id, 3),
                ),
            )
        val store = GraphStore()

        store.merge(expansion)
        val first = store.snapshot(graphA)
        store.merge(expansion)
        val second = store.snapshot(graphA)

        assertEquals(first, second)
        assertEquals(listOf(a.id, b.id), second.nodes.map { it.id })
        assertEquals(2, second.edges.single { it.kind == EdgeKind.REFERENCES }.count)
        assertEquals(3, second.edges.single { it.kind == EdgeKind.CONTAINS }.count)
    }

    @Test
    fun overlappingEdgesMergeOriginsAndCountsWithMaximumPerOrigin() {
        val a = node("a")
        val b = node("b")
        val first =
            expansion(
                graphA,
                a,
                listOf(b),
                listOf(
                    referenceEdge(
                        a.id,
                        b.id,
                        listOf(EdgeOrigin(a.id, b.id, 1), EdgeOrigin(b.id, a.id, 2)),
                    ),
                    containsEdge(a.id, b.id, 2),
                ),
            )
        val second =
            expansion(
                graphA,
                a,
                listOf(b),
                listOf(
                    referenceEdge(
                        a.id,
                        b.id,
                        listOf(EdgeOrigin(a.id, b.id, 3), EdgeOrigin(b.id, a.id, 4)),
                    ),
                    containsEdge(a.id, b.id, 9),
                ),
            )
        val store = GraphStore()

        store.merge(first)
        store.merge(second)

        val snapshot = store.snapshot(graphA)
        val reference = snapshot.edges.single { it.kind == EdgeKind.REFERENCES }
        assertEquals(7, reference.count)
        assertEquals(
            listOf(EdgeOrigin(a.id, b.id, 3), EdgeOrigin(b.id, a.id, 4)),
            reference.origins,
        )
        assertEquals(9, snapshot.edges.single { it.kind == EdgeKind.CONTAINS }.count)
    }

    @Test
    fun overlappingEdgeMergeIsOrderIndependent() {
        val a = node("a")
        val b = node("b")
        val first =
            expansion(
                graphA,
                a,
                listOf(b),
                listOf(referenceEdge(a.id, b.id, listOf(EdgeOrigin(a.id, b.id, 1), EdgeOrigin(b.id, a.id, 2)))),
            )
        val second =
            expansion(
                graphA,
                a,
                listOf(b),
                listOf(referenceEdge(a.id, b.id, listOf(EdgeOrigin(a.id, b.id, 3), EdgeOrigin(b.id, a.id, 4)))),
            )
        val forward = GraphStore()
        val reverse = GraphStore()

        forward.merge(first)
        forward.merge(second)
        reverse.merge(second)
        reverse.merge(first)

        assertEquals(forward.snapshot(graphA), reverse.snapshot(graphA))
    }

    @Test
    fun movedEntityRefreshesSpanInsteadOfConflicting() {
        val original = entity("widget", span = SourceSpan(1, 0, 3, 1))
        val moved = original.copy(span = SourceSpan(10, 0, 12, 1))
        val store = GraphStore()

        store.merge(expansion(graphA, original))
        store.merge(expansion(graphA, moved))

        val stored = store.snapshot(graphA).nodes.single()
        assertEquals(moved.span, stored.span)
    }

    @Test
    fun nullIncomingParentKeepsExistingParentAndRefreshesMetadata() {
        val parent = NodeId("parent")
        val original = entity("widget", parent = parent, span = SourceSpan(1, 0, 3, 1))
        val updated =
            original.copy(
                label = "Renamed",
                span = SourceSpan(10, 0, 12, 1),
                parent = null,
                unstable = true,
            )
        val store = GraphStore()

        store.merge(expansion(graphA, original))
        store.merge(expansion(graphA, updated))

        val stored = store.snapshot(graphA).nodes.single()
        assertEquals(parent, stored.parent)
        assertEquals(updated.span, stored.span)
        assertEquals("Renamed", stored.label)
        assertTrue(stored.unstable)
    }

    @Test
    fun conflictingNodeIdentityIsRejected() {
        val existing = entity("widget", qualifiedName = "app.Widget")
        val conflicting = entity("widget", qualifiedName = "app.Other")
        val store = GraphStore()

        store.merge(expansion(graphA, existing))

        assertFailsWith<IllegalArgumentException> {
            store.merge(expansion(graphA, conflicting))
        }
        assertEquals(listOf(existing), store.snapshot(graphA).nodes)
    }

    @Test
    fun edgeWithMissingEndpointIsRejected() {
        val a = node("a")
        val missing = node("b")
        val edge = referenceEdge(a.id, missing.id, listOf(EdgeOrigin(a.id, missing.id, 1)))
        val store = GraphStore()

        assertFailsWith<IllegalArgumentException> {
            store.merge(expansion(graphA, a, edges = listOf(edge)))
        }
        assertTrue(store.snapshot(graphA).nodes.isEmpty())
        assertTrue(store.snapshot(graphA).edges.isEmpty())
    }

    @Test
    fun cacheHitAvoidsCompute() {
        val store = GraphStore(InMemoryExpansionCache(10))
        val a = node("a")
        val expansion = expansion(graphA, a)
        var calls = 0

        val first =
            store.expand(graphA, a.id, "r1") {
                calls++
                expansion
            }
        val second =
            store.expand(graphA, a.id, "r1") {
                calls++
                expansion
            }

        assertSame(expansion, first)
        assertSame(expansion, second)
        assertEquals(1, calls)
    }

    @Test
    fun cacheMissComputesAndMerges() {
        val store = GraphStore(InMemoryExpansionCache(10))
        val a = node("a")
        val expansion = expansion(graphA, a)
        var calls = 0

        val result =
            store.expand(graphA, a.id, "r1") {
                calls++
                expansion
            }

        assertSame(expansion, result)
        assertEquals(1, calls)
        assertEquals(listOf(a), store.snapshot(graphA).nodes)
    }

    @Test
    fun revisionChangeRecomputes() {
        val store = GraphStore(InMemoryExpansionCache(10))
        val a = node("a")
        val first = expansion(graphA, a, revision = "r1")
        val second = expansion(graphA, a, revision = "r2")
        var calls = 0

        store.expand(graphA, a.id, "r1") {
            calls++
            first
        }
        val result =
            store.expand(graphA, a.id, "r2") {
                calls++
                second
            }
        val cached =
            store.expand(graphA, a.id, "r2") {
                calls++
                second
            }

        assertSame(second, result)
        assertSame(second, cached)
        assertEquals(2, calls)
    }

    @Test
    fun lruEvictionForcesRecomputation() {
        val store = GraphStore(InMemoryExpansionCache(1))
        val a = node("a")
        val b = node("b")
        val expansionA = expansion(graphA, a)
        val expansionB = expansion(graphA, b)
        var callsA = 0
        var callsB = 0

        store.expand(graphA, a.id, "r1") {
            callsA++
            expansionA
        }
        store.expand(graphA, b.id, "r1") {
            callsB++
            expansionB
        }
        store.expand(graphA, a.id, "r1") {
            callsA++
            expansionA
        }
        store.expand(graphA, b.id, "r1") {
            callsB++
            expansionB
        }

        assertEquals(2, callsA)
        assertEquals(2, callsB)
    }

    @Test
    fun graphsAreIsolatedByGraphId() {
        val sharedId = NodeId("shared")
        val nodeA = Node(sharedId, NodeKind.GROUP, "A")
        val nodeB = Node(sharedId, NodeKind.GROUP, "B")
        val store = GraphStore()

        store.merge(expansion(graphA, nodeA))
        store.merge(expansion(graphB, nodeB))

        assertEquals(listOf(nodeA), store.snapshot(graphA).nodes)
        assertEquals(listOf(nodeB), store.snapshot(graphB).nodes)
        assertEquals(setOf(graphA, graphB), store.graphIds())
    }

    @Test
    fun snapshotsOrderNodesAndEdgesDeterministically() {
        val a = node("a")
        val b = node("b")
        val c = node("c")
        val edges =
            listOf(
                Edge.of(EdgeKind.REFERENCES, a.id, c.id, 1, listOf(EdgeOrigin(a.id, c.id, 1))),
                Edge.of(EdgeKind.GROUP_REFERENCES, a.id, c.id, 1, listOf(EdgeOrigin(a.id, c.id, 1))),
                containsEdge(a.id, b.id, 1),
                Edge.of(EdgeKind.FILE_REFERENCES, b.id, c.id, 1, listOf(EdgeOrigin(b.id, c.id, 1))),
                Edge.of(EdgeKind.REFERENCES, a.id, b.id, 1, listOf(EdgeOrigin(a.id, b.id, 1))),
            )
        val store = GraphStore()
        val reverseStore = GraphStore()

        store.merge(expansion(graphA, c, listOf(b, a), edges))
        reverseStore.merge(expansion(graphA, c, listOf(a, b), edges.reversed()))

        val snapshot = store.snapshot(graphA)
        assertEquals(listOf(NodeId("a"), NodeId("b"), NodeId("c")), snapshot.nodes.map { it.id })
        assertEquals(
            listOf(
                EdgeKey(EdgeKind.CONTAINS, a.id, b.id),
                EdgeKey(EdgeKind.FILE_REFERENCES, b.id, c.id),
                EdgeKey(EdgeKind.GROUP_REFERENCES, a.id, c.id),
                EdgeKey(EdgeKind.REFERENCES, a.id, b.id),
                EdgeKey(EdgeKind.REFERENCES, a.id, c.id),
            ),
            snapshot.edges.map { it.key },
        )
        assertEquals(snapshot, reverseStore.snapshot(graphA))
    }

    @Test
    fun concurrentMergesDoNotCorruptTheGraph() {
        val store = GraphStore()
        val seed = node("seed")
        val threadCount = 8
        val executor = Executors.newFixedThreadPool(threadCount)
        val start = CountDownLatch(1)
        try {
            store.merge(expansion(graphA, seed))
            val futures =
                (0 until threadCount).map { index ->
                    executor.submit {
                        start.await()
                        val neighbour = node("n$index")
                        val expansion =
                            expansion(
                                graphA,
                                seed,
                                listOf(neighbour),
                                listOf(containsEdge(seed.id, neighbour.id, 1)),
                            )
                        repeat(50) { store.merge(expansion) }
                    }
                }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val snapshot = store.snapshot(graphA)
        assertEquals(threadCount + 1, snapshot.nodes.size)
        assertEquals(threadCount, snapshot.edges.size)
        (0 until threadCount).forEach { index ->
            assertTrue(snapshot.nodes.any { it.id == NodeId("n$index") })
        }
        assertTrue(snapshot.edges.all { it.count == 1 })
    }

    @Test
    fun cacheHitMergesCachedExpansionIntoStore() {
        val cache = InMemoryExpansionCache(10)
        val a = node("a")
        val b = node("b")
        val cached =
            expansion(
                graphA,
                a,
                listOf(b),
                listOf(containsEdge(a.id, b.id, 4)),
            )
        cache.put(CacheKey(graphA, a.id, "r1"), cached)
        val store = GraphStore(cache)
        var calls = 0

        val result =
            store.expand(graphA, a.id, "r1") {
                calls++
                error("compute must not be called")
            }

        assertSame(cached, result)
        assertEquals(0, calls)
        val snapshot = store.snapshot(graphA)
        assertEquals(listOf(a, b), snapshot.nodes)
        assertEquals(listOf(containsEdge(a.id, b.id, 4)), snapshot.edges)
    }

    @Test
    fun rejectsComputedExpansionWithMismatchedGraphId() {
        val cache = InMemoryExpansionCache(10)
        val store = GraphStore(cache)
        val a = node("a")

        assertFailsWith<IllegalArgumentException> {
            store.expand(graphA, a.id, "r1") {
                expansion(graphB, a)
            }
        }

        assertTrue(store.snapshot(graphA).nodes.isEmpty())
        assertTrue(store.snapshot(graphB).nodes.isEmpty())
        assertEquals(0, cache.size)
    }

    @Test
    fun rejectsComputedExpansionWithMismatchedNodeId() {
        val cache = InMemoryExpansionCache(10)
        val store = GraphStore(cache)
        val a = node("a")
        val b = node("b")

        assertFailsWith<IllegalArgumentException> {
            store.expand(graphA, a.id, "r1") {
                expansion(graphA, b)
            }
        }

        assertTrue(store.snapshot(graphA).nodes.isEmpty())
        assertEquals(0, cache.size)
    }

    @Test
    fun rejectsComputedExpansionWithMismatchedRevision() {
        val cache = InMemoryExpansionCache(10)
        val store = GraphStore(cache)
        val a = node("a")

        assertFailsWith<IllegalArgumentException> {
            store.expand(graphA, a.id, "r1") {
                expansion(graphA, a, revision = "r2")
            }
        }

        assertTrue(store.snapshot(graphA).nodes.isEmpty())
        assertEquals(0, cache.size)
    }

    @Test
    fun rejectsCachedExpansionWithMismatchedNodeId() {
        val cache = InMemoryExpansionCache(10)
        val a = node("a")
        val b = node("b")
        cache.put(CacheKey(graphA, a.id, "r1"), expansion(graphA, b))
        val store = GraphStore(cache)

        assertFailsWith<IllegalArgumentException> {
            store.expand(graphA, a.id, "r1") {
                error("compute must not be called")
            }
        }

        assertTrue(store.snapshot(graphA).nodes.isEmpty())
    }

    @Test
    fun mixedOriginContainsMergePreservesLargerCountInBothOrders() {
        val a = node("a")
        val b = node("b")

        assertMixedContains(a, b, originlessCount = 10, originBearingCount = 2, expected = 10)
        assertMixedContains(a, b, originlessCount = 2, originBearingCount = 10, expected = 10)
    }

    @Test
    fun computeFailureLeavesStoreAndCacheUnchanged() {
        val cache = InMemoryExpansionCache(10)
        val store = GraphStore(cache)
        val a = node("a")

        assertFailsWith<IllegalStateException> {
            store.expand(graphA, a.id, "r1") {
                throw IllegalStateException("compute")
            }
        }

        assertTrue(store.snapshot(graphA).nodes.isEmpty())
        assertEquals(0, cache.size)
    }

    @Test
    fun cacheGetFailurePropagatesBeforeComputeOrMerge() {
        val store = GraphStore(ThrowingGetCache())
        val a = node("a")
        var calls = 0

        assertFailsWith<IllegalStateException> {
            store.expand(graphA, a.id, "r1") {
                calls++
                expansion(graphA, a)
            }
        }

        assertEquals(0, calls)
        assertTrue(store.snapshot(graphA).nodes.isEmpty())
    }

    @Test
    fun cachePutFailurePropagatesAfterMergeWithoutCaching() {
        val cache = FailOncePutCache()
        val store = GraphStore(cache)
        val a = node("a")
        val expansion = expansion(graphA, a)
        var calls = 0

        assertFailsWith<IllegalStateException> {
            store.expand(graphA, a.id, "r1") {
                calls++
                expansion
            }
        }

        assertEquals(1, calls)
        assertEquals(listOf(a), store.snapshot(graphA).nodes)
        assertEquals(0, cache.size)

        val result =
            store.expand(graphA, a.id, "r1") {
                calls++
                expansion
            }

        assertSame(expansion, result)
        assertEquals(2, calls)
        assertEquals(1, cache.size)
    }

    @Test
    fun lruEvictionRemovesCacheEntryButKeepsGraph() {
        val cache = InMemoryExpansionCache(1)
        val store = GraphStore(cache)
        val a = node("a")
        val b = node("b")
        val expansionA =
            expansion(
                graphA,
                a,
                listOf(b),
                listOf(containsEdge(a.id, b.id, 1)),
            )
        val expansionB = expansion(graphA, b)

        store.expand(graphA, a.id, "r1") { expansionA }
        store.expand(graphA, b.id, "r1") { expansionB }

        assertNull(cache.get(CacheKey(graphA, a.id, "r1")))
        val snapshot = store.snapshot(graphA)
        assertEquals(listOf(a.id, b.id), snapshot.nodes.map { it.id })
        assertEquals(listOf(containsEdge(a.id, b.id, 1)), snapshot.edges)
    }

    @Test
    fun cacheHitAfterEvictionRecomputesAndKeepsGraph() {
        val cache = InMemoryExpansionCache(1)
        val store = GraphStore(cache)
        val a = node("a")
        val b = node("b")
        val expansionA =
            expansion(
                graphA,
                a,
                listOf(b),
                listOf(containsEdge(a.id, b.id, 1)),
            )
        val expansionB = expansion(graphA, b)
        var callsA = 0

        store.expand(graphA, a.id, "r1") {
            callsA++
            expansionA
        }
        store.expand(graphA, b.id, "r1") { expansionB }
        val result =
            store.expand(graphA, a.id, "r1") {
                callsA++
                expansionA
            }

        assertEquals(2, callsA)
        assertSame(expansionA, result)
        val snapshot = store.snapshot(graphA)
        assertEquals(listOf(a.id, b.id), snapshot.nodes.map { it.id })
        assertEquals(listOf(containsEdge(a.id, b.id, 1)), snapshot.edges)
    }

    @Test
    fun concurrentExpandsAndSnapshotsStayConsistent() {
        val store = GraphStore(InMemoryExpansionCache(64))
        val expansions =
            (0 until 4).map { index ->
                val seed = node("s$index")
                val neighbour = node("n$index")
                expansion(
                    graphA,
                    seed,
                    listOf(neighbour),
                    listOf(containsEdge(seed.id, neighbour.id, 1)),
                )
            }
        val threadCount = 8
        val executor = Executors.newFixedThreadPool(threadCount)
        val start = CountDownLatch(1)
        try {
            val futures =
                (0 until threadCount).map { index ->
                    executor.submit {
                        start.await()
                        val expansion = expansions[index % expansions.size]
                        repeat(25) {
                            store.expand(graphA, expansion.node.id, expansion.revision) { expansion }
                            store.snapshot(graphA)
                        }
                    }
                }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val snapshot = store.snapshot(graphA)
        assertEquals(8, snapshot.nodes.size)
        assertEquals(4, snapshot.edges.size)
    }

    private fun assertMixedContains(
        a: Node,
        b: Node,
        originlessCount: Int,
        originBearingCount: Int,
        expected: Int,
    ) {
        val originless = containsEdge(a.id, b.id, originlessCount)
        val originBearing =
            containsEdge(
                a.id,
                b.id,
                originBearingCount,
                listOf(EdgeOrigin(a.id, b.id, originBearingCount)),
            )
        listOf(originless to originBearing, originBearing to originless).forEach { (first, second) ->
            val store = GraphStore()
            store.merge(expansion(graphA, a, listOf(b), listOf(first)))
            store.merge(expansion(graphA, a, listOf(b), listOf(second)))

            val edge = store.snapshot(graphA).edges.single { it.kind == EdgeKind.CONTAINS }
            assertEquals(expected, edge.count)
            assertTrue(edge.origins.isEmpty())
        }
    }
}

private class ThrowingGetCache : ExpansionCache {
    override fun get(key: CacheKey): Expansion? = throw IllegalStateException("get")

    override fun put(
        key: CacheKey,
        expansion: Expansion,
    ) = Unit

    override fun remove(key: CacheKey) = Unit

    override fun clear() = Unit
}

private class FailOncePutCache : ExpansionCache {
    private val entries = mutableMapOf<CacheKey, Expansion>()
    private var failNextPut = true

    override fun get(key: CacheKey): Expansion? = entries[key]

    override fun put(
        key: CacheKey,
        expansion: Expansion,
    ) {
        if (failNextPut) {
            failNextPut = false
            throw IllegalStateException("put")
        }
        entries[key] = expansion
    }

    override fun remove(key: CacheKey) {
        entries.remove(key)
    }

    override fun clear() {
        entries.clear()
    }

    val size: Int
        get() = entries.size
}
