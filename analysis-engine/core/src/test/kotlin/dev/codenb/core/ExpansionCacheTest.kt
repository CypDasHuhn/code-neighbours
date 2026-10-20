package dev.codenb.core

import dev.codenb.api.Expansion
import dev.codenb.api.GraphId
import dev.codenb.api.Node
import dev.codenb.api.NodeId
import dev.codenb.api.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ExpansionCacheTest {
    private val graphId = GraphId("graph")

    private fun key(
        nodeId: String,
        revision: String = "r1",
    ): CacheKey = CacheKey(graphId, NodeId(nodeId), revision)

    private fun expansion(nodeId: String): Expansion {
        val node = Node(NodeId(nodeId), NodeKind.GROUP, nodeId)
        return Expansion(graphId, "r1", node, emptyList(), emptyList())
    }

    @Test
    fun storesAndReturnsExpansions() {
        val cache = InMemoryExpansionCache(10)
        val expansion = expansion("a")

        assertNull(cache.get(key("a")))
        cache.put(key("a"), expansion)

        assertSame(expansion, cache.get(key("a")))
        assertEquals(1, cache.size)
    }

    @Test
    fun treatsDifferentKeysAsDistinctEntries() {
        val cache = InMemoryExpansionCache(10)
        val first = expansion("a")
        val second = expansion("b")

        cache.put(key("a"), first)
        cache.put(key("b"), second)

        assertSame(first, cache.get(key("a")))
        assertSame(second, cache.get(key("b")))
        assertNull(cache.get(key("a", "r2")))
    }

    @Test
    fun evictsTheLeastRecentlyUsedEntry() {
        val cache = InMemoryExpansionCache(2)
        val a = expansion("a")
        val b = expansion("b")
        val c = expansion("c")

        cache.put(key("a"), a)
        cache.put(key("b"), b)
        assertSame(a, cache.get(key("a")))
        cache.put(key("c"), c)

        assertNull(cache.get(key("b")))
        assertSame(a, cache.get(key("a")))
        assertSame(c, cache.get(key("c")))
        assertEquals(2, cache.size)
    }

    @Test
    fun replacesExistingEntryWithoutGrowing() {
        val cache = InMemoryExpansionCache(2)
        val first = expansion("a")
        val second = expansion("a")

        cache.put(key("a"), first)
        cache.put(key("a"), second)

        assertSame(second, cache.get(key("a")))
        assertEquals(1, cache.size)
    }

    @Test
    fun removesAndClearsEntries() {
        val cache = InMemoryExpansionCache(10)
        val a = expansion("a")
        val b = expansion("b")

        cache.put(key("a"), a)
        cache.put(key("b"), b)
        cache.remove(key("a"))

        assertNull(cache.get(key("a")))
        assertSame(b, cache.get(key("b")))

        cache.clear()

        assertNull(cache.get(key("b")))
        assertEquals(0, cache.size)
    }
}
