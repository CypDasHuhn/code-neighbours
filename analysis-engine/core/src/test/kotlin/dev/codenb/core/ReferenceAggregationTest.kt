package dev.codenb.core

import dev.codenb.api.Edge
import dev.codenb.api.EdgeKind
import dev.codenb.api.EdgeOrigin
import dev.codenb.api.Node
import dev.codenb.api.NodeId
import dev.codenb.api.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals

class ReferenceAggregationTest {
    @Test
    fun aggregatesCountsFallbacksAndSuppressesLocalEdges() {
        val groupA = group("g/a")
        val groupB = group("g/b")
        val fileA1 = file("f/a1", "file:///a1", groupA)
        val fileA2 = file("f/a2", "file:///a2", groupA)
        val fileB1 = file("f/b1", "file:///b1", groupB)
        val fileX = file("f/x", "file:///x", null)
        val e1 = entity("e/1", "file:///a1", fileA1)
        val e2 = entity("e/2", "file:///a2", fileA2)
        val e3 = entity("e/3", "file:///b1", fileB1)
        val orphan = entity("e/4", "file:///a1", null)
        val noGroup = entity("e/5", "file:///x", fileX)
        val nodes =
            listOf(groupA, groupB, fileA1, fileA2, fileB1, fileX, e1, e2, e3, orphan, noGroup)
        val references =
            listOf(
                reference(e1, e3, 2),
                reference(e2, e3, 1),
                reference(e1, e2, 1),
                reference(orphan, e3, 1),
                reference(orphan, e1, 1),
                reference(noGroup, e3, 1),
            )
        val expected =
            listOf(
                Edge.of(
                    EdgeKind.FILE_REFERENCES,
                    fileA1.id,
                    fileA2.id,
                    1,
                    listOf(EdgeOrigin(fileA1.id, fileA2.id, 1)),
                ),
                Edge.of(
                    EdgeKind.FILE_REFERENCES,
                    fileA1.id,
                    fileB1.id,
                    3,
                    listOf(EdgeOrigin(fileA1.id, fileB1.id, 3)),
                ),
                Edge.of(
                    EdgeKind.FILE_REFERENCES,
                    fileA2.id,
                    fileB1.id,
                    1,
                    listOf(EdgeOrigin(fileA2.id, fileB1.id, 1)),
                ),
                Edge.of(
                    EdgeKind.FILE_REFERENCES,
                    fileB1.id,
                    fileX.id,
                    1,
                    listOf(EdgeOrigin(fileX.id, fileB1.id, 1)),
                ),
                Edge.of(
                    EdgeKind.GROUP_REFERENCES,
                    groupA.id,
                    groupB.id,
                    4,
                    listOf(EdgeOrigin(groupA.id, groupB.id, 4)),
                ),
            )
        assertEquals(expected, ReferenceAggregation.edges(nodes, references))
        assertEquals(expected, ReferenceAggregation.edges(nodes, references.reversed()))
    }

    @Test
    fun sumsMultipleReferencesBetweenTheSamePairDeterministically() {
        val groupA = group("g/a")
        val groupB = group("g/b")
        val fileA = file("f/a", "file:///a", groupA)
        val fileB = file("f/b", "file:///b", groupB)
        val a1 = entity("e/a1", "file:///a", fileA)
        val a2 = entity("e/a2", "file:///a", fileA)
        val b1 = entity("e/b1", "file:///b", fileB)
        val b2 = entity("e/b2", "file:///b", fileB)
        val nodes = listOf(groupA, groupB, fileA, fileB, a1, a2, b1, b2)
        val references =
            listOf(
                reference(a1, b1, 2),
                reference(a2, b1, 3),
                reference(a1, b2, 1),
                reference(b1, a1, 4),
                reference(a2, b2, 2),
            )
        val expectedFile =
            Edge.of(
                EdgeKind.FILE_REFERENCES,
                fileA.id,
                fileB.id,
                12,
                listOf(
                    EdgeOrigin(fileA.id, fileB.id, 8),
                    EdgeOrigin(fileB.id, fileA.id, 4),
                ),
            )
        val expectedGroup =
            Edge.of(
                EdgeKind.GROUP_REFERENCES,
                groupA.id,
                groupB.id,
                12,
                listOf(
                    EdgeOrigin(groupA.id, groupB.id, 8),
                    EdgeOrigin(groupB.id, groupA.id, 4),
                ),
            )
        val first = ReferenceAggregation.edges(nodes, references)
        assertEquals(listOf(expectedFile, expectedGroup), first)
        assertEquals(first, ReferenceAggregation.edges(nodes, references.shuffled()))
    }

    private fun reference(
        from: Node,
        to: Node,
        count: Int,
    ): Edge =
        Edge.of(
            EdgeKind.REFERENCES,
            from.id,
            to.id,
            count,
            listOf(EdgeOrigin(from.id, to.id, count)),
        )

    private fun group(id: String): Node = Node(NodeId(id), NodeKind.GROUP, id)

    private fun file(
        id: String,
        uri: String,
        parent: Node?,
    ): Node = Node(NodeId(id), NodeKind.FILE, id, fileUri = uri, parent = parent?.id)

    private fun entity(
        id: String,
        uri: String,
        parent: Node?,
    ): Node =
        Node(
            id = NodeId(id),
            kind = NodeKind.ENTITY,
            label = id,
            qualifiedName = id,
            fileUri = uri,
            signatureFingerprint = "fun $id()",
            parent = parent?.id,
        )
}
