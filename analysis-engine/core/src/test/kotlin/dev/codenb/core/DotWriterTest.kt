package dev.codenb.core

import dev.codenb.api.Edge
import dev.codenb.api.EdgeKind
import dev.codenb.api.EdgeOrigin
import dev.codenb.api.GraphId
import dev.codenb.api.Node
import dev.codenb.api.NodeId
import dev.codenb.api.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DotWriterTest {
    private val graphId = GraphId("graph/dot")
    private val group = Node(NodeId("g/app"), NodeKind.GROUP, "app")
    private val file =
        Node(
            id = NodeId("f/A.kt"),
            kind = NodeKind.FILE,
            label = "A.kt",
            fileUri = "file:///tmp/A.kt",
            parent = group.id,
        )
    private val seed =
        Node(
            id = NodeId("e/seed"),
            kind = NodeKind.ENTITY,
            label = "seed\"quoted\nlabel",
            qualifiedName = "app.seed",
            fileUri = "file:///tmp/A.kt",
            signatureFingerprint = "fun seed()",
            parent = file.id,
        )
    private val target =
        Node(
            id = NodeId("e/target"),
            kind = NodeKind.ENTITY,
            label = "target",
            qualifiedName = "app.target",
            fileUri = "file:///tmp/A.kt",
            signatureFingerprint = "fun target()",
            parent = file.id,
        )
    private val edges =
        listOf(
            Edge.of(EdgeKind.CONTAINS, seed.id, file.id, 1),
            Edge.of(EdgeKind.CONTAINS, target.id, file.id, 1),
            Edge.of(EdgeKind.CONTAINS, file.id, group.id, 1),
            Edge.of(
                EdgeKind.REFERENCES,
                seed.id,
                target.id,
                3,
                listOf(EdgeOrigin(seed.id, target.id, 3)),
            ),
        )

    @Test
    fun writesDeterministicUndirectedGraphWithClustersAndShapes() {
        val snapshot = GraphSnapshot(graphId, listOf(target, seed, file, group), edges.reversed())
        val first = DotWriter.write(snapshot)
        val second = DotWriter.write(snapshot)
        assertEquals(first, second)
        assertTrue(first.startsWith("graph \"code-neighbours\" {"))
        assertTrue(first.contains("subgraph \"cluster_"))
        assertTrue(first.contains("shape=\"box\""))
        assertTrue(first.contains("shape=\"note\""))
        assertTrue(first.contains("shape=\"folder\""))
        assertTrue(first.contains("label=\"3\""))
        assertTrue(first.contains("--"))
        assertTrue(!first.contains("->"))
    }

    @Test
    fun escapesLabels() {
        val snapshot = GraphSnapshot(graphId, listOf(group, file, seed, target), edges)
        val dot = DotWriter.write(snapshot)
        assertTrue(dot.contains("label=\"seed\\\"quoted\\nlabel\""))
    }
}
