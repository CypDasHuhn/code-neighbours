package dev.codenb.api

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GraphInfoTest {
    private val graphId = GraphId("graph-1")

    private fun graphInfo(
        nodeKinds: List<NodeKind> = listOf(NodeKind.ENTITY, NodeKind.FILE, NodeKind.GROUP),
        edgeKinds: List<EdgeKind> = listOf(EdgeKind.CONTAINS, EdgeKind.REFERENCES),
        protocolVersion: Int = PROTOCOL_VERSION,
    ): GraphInfo =
        GraphInfo(
            graphId = graphId,
            root = "/workspace",
            lspId = "kotlin",
            groupingKind = GroupingKind.NAMESPACE,
            nodeKinds = nodeKinds,
            edgeKinds = edgeKinds,
            protocolVersion = protocolVersion,
        )

    @Test
    fun acceptsCanonicalKindLists() {
        assertEquals(
            listOf(NodeKind.ENTITY, NodeKind.FILE, NodeKind.GROUP),
            graphInfo().nodeKinds,
        )
    }

    @Test
    fun rejectsUnsortedNodeKinds() {
        assertFailsWith<IllegalArgumentException> {
            graphInfo(nodeKinds = listOf(NodeKind.FILE, NodeKind.ENTITY))
        }
    }

    @Test
    fun rejectsDuplicateNodeKinds() {
        assertFailsWith<IllegalArgumentException> {
            graphInfo(nodeKinds = listOf(NodeKind.ENTITY, NodeKind.ENTITY))
        }
    }

    @Test
    fun rejectsUnsortedEdgeKinds() {
        assertFailsWith<IllegalArgumentException> {
            graphInfo(edgeKinds = listOf(EdgeKind.REFERENCES, EdgeKind.CONTAINS))
        }
    }

    @Test
    fun rejectsDuplicateEdgeKinds() {
        assertFailsWith<IllegalArgumentException> {
            graphInfo(edgeKinds = listOf(EdgeKind.CONTAINS, EdgeKind.CONTAINS))
        }
    }

    @Test
    fun rejectsWrongProtocolVersion() {
        assertFailsWith<IllegalArgumentException> {
            graphInfo(protocolVersion = PROTOCOL_VERSION + 1)
        }
    }

    @Test
    fun rejectsMissingProtocolVersion() {
        val encoded =
            """{"graphId":"graph-1","root":"/workspace","lspId":"kotlin","groupingKind":"NAMESPACE",""" +
                """"nodeKinds":["ENTITY"],"edgeKinds":["CONTAINS"]}"""

        assertFailsWith<SerializationException> {
            GraphJson.decode<GraphInfo>(encoded)
        }
    }
}
