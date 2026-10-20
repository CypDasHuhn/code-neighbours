package dev.codenb.api

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContractJsonTest {
    private val graphId = GraphId("graph-1")
    private val groupId = NodeId("graph-1/groups/app")
    private val fileId = NodeId("graph-1/files/app/Main.kt")
    private val entityId = NodeId("graph-1/entities/app.Main")
    private val otherId = NodeId("graph-1/entities/app.Other")

    private val span = SourceSpan(1, 2, 3, 4)
    private val node =
        Node(
            id = entityId,
            kind = NodeKind.ENTITY,
            label = "Main",
            qualifiedName = "app.Main",
            fileUri = "app/Main.kt",
            signatureFingerprint = "fun Main()",
            span = span,
            parent = fileId,
            unstable = true,
        )
    private val otherNode =
        Node(
            id = otherId,
            kind = NodeKind.ENTITY,
            label = "Other",
            qualifiedName = "app.Other",
            fileUri = "app/Other.kt",
            signatureFingerprint = "fun Other()",
            span = SourceSpan(5, 6, 7, 8),
            parent = fileId,
        )
    private val edge =
        Edge.of(
            kind = EdgeKind.REFERENCES,
            a = otherId,
            b = entityId,
            count = 3,
            origins =
                listOf(
                    EdgeOrigin(entityId, otherId, 1),
                    EdgeOrigin(otherId, entityId, 2),
                ),
        )
    private val expansion =
        Expansion(
            graphId = graphId,
            revision = "revision-1",
            node = node,
            neighbours = listOf(otherNode),
            edges = listOf(edge),
        )
    private val graphInfo =
        GraphInfo(
            graphId = graphId,
            root = "/workspace",
            lspId = "kotlin",
            groupingKind = GroupingKind.NAMESPACE,
            nodeKinds = listOf(NodeKind.ENTITY, NodeKind.FILE, NodeKind.GROUP),
            edgeKinds =
                listOf(
                    EdgeKind.CONTAINS,
                    EdgeKind.REFERENCES,
                    EdgeKind.FILE_REFERENCES,
                    EdgeKind.GROUP_REFERENCES,
                ),
            protocolVersion = PROTOCOL_VERSION,
        )
    private val engineError =
        EngineError(
            code = EngineErrorCode.UNSUPPORTED_CAPABILITY,
            message = "Semantic tokens are unavailable",
            details = mapOf("lspId" to "kotlin"),
        )

    @Test
    fun protocolVersionIsOne() {
        assertEquals(1, PROTOCOL_VERSION)
    }

    @Test
    fun roundTripsIdentifiersAndKinds() {
        assertRoundTrip(graphId)
        assertRoundTrip(groupId)
        NodeKind.entries.forEach { assertRoundTrip(it) }
        GroupingKind.entries.forEach { assertRoundTrip(it) }
        EdgeKind.entries.forEach { assertRoundTrip(it) }
        EngineErrorCode.entries.forEach { assertRoundTrip(it) }
    }

    @Test
    fun roundTripsSourceSpan() {
        assertRoundTrip(span)
        assertRoundTrip(SourceSpan(2, 3, 2, 3))
    }

    @Test
    fun roundTripsNode() {
        assertRoundTrip(node)
        assertRoundTrip(Node(groupId, NodeKind.GROUP, "app"))
        assertRoundTrip(Node(fileId, NodeKind.FILE, "Main.kt", fileUri = "app/Main.kt", parent = groupId))
    }

    @Test
    fun roundTripsEdgeOriginAndKey() {
        assertRoundTrip(EdgeOrigin(entityId, otherId, 1))
        assertRoundTrip(EdgeKey.of(EdgeKind.REFERENCES, otherId, entityId))
    }

    @Test
    fun roundTripsEdge() {
        assertRoundTrip(edge)
        assertRoundTrip(Edge.of(EdgeKind.CONTAINS, entityId, fileId, 1))
        assertRoundTrip(
            Edge.of(EdgeKind.CONTAINS, entityId, fileId, 2, listOf(EdgeOrigin(entityId, fileId, 2))),
        )
    }

    @Test
    fun roundTripsExpansion() {
        assertRoundTrip(expansion)
    }

    @Test
    fun roundTripsGraphInfo() {
        assertRoundTrip(graphInfo)
    }

    @Test
    fun roundTripsEngineError() {
        assertRoundTrip(engineError)
    }

    @Test
    fun encodesNullsExplicitly() {
        val encoded = GraphJson.encode(Node(groupId, NodeKind.GROUP, "app"))

        assertTrue(encoded.contains("\"qualifiedName\":null"))
        assertTrue(encoded.contains("\"fileUri\":null"))
        assertTrue(encoded.contains("\"signatureFingerprint\":null"))
        assertTrue(encoded.contains("\"span\":null"))
        assertTrue(encoded.contains("\"parent\":null"))
        assertEquals(Node(groupId, NodeKind.GROUP, "app"), GraphJson.decode<Node>(encoded))
    }

    @Test
    fun rejectsUnknownKeys() {
        val encoded = """{"id":"node-1","kind":"GROUP","label":"app","unknown":true}"""

        assertFailsWith<SerializationException> {
            GraphJson.decode<Node>(encoded)
        }
    }

    private inline fun <reified T> assertRoundTrip(value: T) {
        val encoded = GraphJson.encode(value)
        assertEquals(value, GraphJson.decode<T>(encoded))
    }
}
