package dev.codenb.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EdgeContractTest {
    private val a = NodeId("graph/node-a")
    private val b = NodeId("graph/node-b")

    @Test
    fun reversedEndpointsHaveTheSameKey() {
        val origin = EdgeOrigin(a, b, 1)
        val forward = Edge.of(EdgeKind.REFERENCES, a, b, 1, listOf(origin))
        val reverse = Edge.of(EdgeKind.REFERENCES, b, a, 1, listOf(origin))

        assertEquals(forward.key, reverse.key)
        assertEquals(forward, reverse)
        assertEquals(EdgeKey.of(EdgeKind.REFERENCES, a, b), EdgeKey.of(EdgeKind.REFERENCES, b, a))
    }

    @Test
    fun rejectsSelfEdges() {
        assertFailsWith<IllegalArgumentException> {
            Edge.of(EdgeKind.REFERENCES, a, a, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            Edge(EdgeKind.REFERENCES, a, a, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            EdgeOrigin(a, a, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            EdgeKey.of(EdgeKind.REFERENCES, a, a)
        }
    }

    @Test
    fun rejectsNonCanonicalEndpoints() {
        assertFailsWith<IllegalArgumentException> {
            Edge(EdgeKind.REFERENCES, b, a, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            EdgeKey(EdgeKind.REFERENCES, b, a)
        }
    }

    @Test
    fun rejectsInvalidCounts() {
        assertFailsWith<IllegalArgumentException> {
            EdgeOrigin(a, b, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            EdgeOrigin(a, b, -1)
        }
        assertFailsWith<IllegalArgumentException> {
            Edge.of(EdgeKind.CONTAINS, a, b, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            Edge.of(EdgeKind.CONTAINS, a, b, -1)
        }
    }

    @Test
    fun rejectsOriginsThatDoNotMatchTheEdge() {
        val c = NodeId("graph/node-c")

        assertFailsWith<IllegalArgumentException> {
            Edge.of(EdgeKind.REFERENCES, a, b, 1, listOf(EdgeOrigin(a, c, 1)))
        }
    }

    @Test
    fun referenceEdgesRequireOrigins() {
        assertFailsWith<IllegalArgumentException> {
            Edge.of(EdgeKind.REFERENCES, a, b, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            Edge.of(EdgeKind.FILE_REFERENCES, a, b, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            Edge.of(EdgeKind.GROUP_REFERENCES, a, b, 1)
        }
        Edge.of(EdgeKind.CONTAINS, a, b, 1)
    }

    @Test
    fun rejectsDuplicateOrigins() {
        val origin = EdgeOrigin(a, b, 1)

        assertFailsWith<IllegalArgumentException> {
            Edge.of(EdgeKind.REFERENCES, a, b, 2, listOf(origin, origin))
        }
    }

    @Test
    fun rejectsOriginCountMismatches() {
        assertFailsWith<IllegalArgumentException> {
            Edge.of(EdgeKind.REFERENCES, a, b, 2, listOf(EdgeOrigin(a, b, 1)))
        }
        assertFailsWith<IllegalArgumentException> {
            Edge.of(EdgeKind.CONTAINS, a, b, 2, listOf(EdgeOrigin(a, b, 1)))
        }
    }

    @Test
    fun preservesOriginsAsMetadata() {
        val forward = EdgeOrigin(a, b, 2)
        val reverse = EdgeOrigin(b, a, 3)
        val edge = Edge.of(EdgeKind.REFERENCES, b, a, 5, listOf(forward, reverse))

        assertEquals(listOf(forward, reverse), edge.origins)
        assertEquals(5, edge.count)
    }
}
