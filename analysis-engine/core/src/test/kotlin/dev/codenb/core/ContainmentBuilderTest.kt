package dev.codenb.core

import dev.codenb.api.Edge
import dev.codenb.api.EdgeKey
import dev.codenb.api.EdgeKind
import dev.codenb.api.EdgeOrigin
import dev.codenb.api.Expansion
import dev.codenb.api.GraphId
import dev.codenb.api.GroupingKind
import dev.codenb.api.Node
import dev.codenb.api.NodeId
import dev.codenb.api.NodeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContainmentBuilderTest {
    private val graphId = GraphId("graph")

    @Test
    fun crossFileReferenceProducesFileAndGroupReferences() {
        val first = entity("src/app/First.kt", "app.alpha.First")
        val second = entity("src/app/Second.kt", "app.beta.Second")
        val expansion =
            Expansion(
                graphId,
                "revision",
                first,
                listOf(second),
                listOf(references(first, second, 3)),
            )
        val result =
            apply(
                expansion,
                GroupingKind.NAMESPACE,
                listOf(
                    FileLocation(first.fileUri!!, "src/app/First.kt", "app.alpha"),
                    FileLocation(second.fileUri!!, "src/app/Second.kt", "app.beta"),
                ),
            )

        val firstFile = result.fileNode(first.fileUri!!)
        val secondFile = result.fileNode(second.fileUri!!)
        val firstGroup = result.groupNode("app.alpha")
        val secondGroup = result.groupNode("app.beta")
        assertEdge(result, EdgeKind.FILE_REFERENCES, firstFile.id, secondFile.id, mapOf(firstFile.id to secondFile.id to 3))
        assertEdge(result, EdgeKind.GROUP_REFERENCES, firstGroup.id, secondGroup.id, mapOf(firstGroup.id to secondGroup.id to 3))
        assertEquals(expansion.edges, result.edges.filter { it.kind == EdgeKind.REFERENCES })
        assertEquals(firstFile.id, result.node.parent)
        assertEquals(secondFile.id, result.neighbours.single { it.id == second.id }.parent)
        assertEquals(firstGroup.id, firstFile.parent)
        assertEquals(secondGroup.id, secondFile.parent)
    }

    @Test
    fun sameFileReferenceCreatesNoFileOrGroupSelfEdge() {
        val first = entity("src/app/Same.kt", "app.first")
        val second = entity("src/app/Same.kt", "app.second")
        val expansion =
            Expansion(
                graphId,
                "revision",
                first,
                listOf(second),
                listOf(references(first, second, 2)),
            )
        val result =
            apply(
                expansion,
                GroupingKind.NAMESPACE,
                listOf(FileLocation(first.fileUri!!, "src/app/Same.kt", "app")),
            )

        assertEquals(1, result.neighbours.count { it.kind == NodeKind.FILE })
        assertTrue(result.edges.none { it.kind == EdgeKind.FILE_REFERENCES })
        assertTrue(result.edges.none { it.kind == EdgeKind.GROUP_REFERENCES })
    }

    @Test
    fun sameGroupReferenceCreatesAFileReferenceButNoGroupSelfEdge() {
        val first = entity("src/one/First.kt", "app.shared.First")
        val second = entity("src/two/Second.kt", "app.shared.Second")
        val expansion =
            Expansion(
                graphId,
                "revision",
                first,
                listOf(second),
                listOf(references(first, second, 4)),
            )
        val locations =
            listOf(
                FileLocation(first.fileUri!!, "src/one/First.kt", "app.shared"),
                FileLocation(second.fileUri!!, "src/two/Second.kt", "app.shared"),
            )
        val namespaceResult = apply(expansion, GroupingKind.NAMESPACE, locations)
        val directoryResult = apply(expansion, GroupingKind.DIRECTORY, locations)

        val firstFile = namespaceResult.fileNode(first.fileUri!!)
        val secondFile = namespaceResult.fileNode(second.fileUri!!)
        assertEdge(
            namespaceResult,
            EdgeKind.FILE_REFERENCES,
            firstFile.id,
            secondFile.id,
            mapOf(firstFile.id to secondFile.id to 4),
        )
        assertTrue(namespaceResult.edges.none { it.kind == EdgeKind.GROUP_REFERENCES })
        assertEquals(1, namespaceResult.neighbours.count { it.kind == NodeKind.GROUP })

        val directoryFirstFile = directoryResult.fileNode(first.fileUri!!)
        val directorySecondFile = directoryResult.fileNode(second.fileUri!!)
        val directoryFirstGroup = directoryResult.groupNode("one")
        val directorySecondGroup = directoryResult.groupNode("two")
        assertEdge(
            directoryResult,
            EdgeKind.GROUP_REFERENCES,
            directoryFirstGroup.id,
            directorySecondGroup.id,
            mapOf(directoryFirstGroup.id to directorySecondGroup.id to 4),
        )
        assertTrue(
            directoryResult.edge(EdgeKind.FILE_REFERENCES, directoryFirstFile.id, directorySecondFile.id).count == 4,
        )
    }

    @Test
    fun namespaceAndDirectoryGroupingUseDifferentStableGroupIds() {
        val first = entity("src/First.kt", "app.first.First")
        val second = entity("src/Second.kt", "app.second.Second")
        val expansion = Expansion(graphId, "revision", first, listOf(second), emptyList())
        val locations =
            listOf(
                FileLocation(first.fileUri!!, "src/First.kt", "app.first"),
                FileLocation(second.fileUri!!, "src/Second.kt", "app.second"),
            )

        val namespaceResult = apply(expansion, GroupingKind.NAMESPACE, locations)
        val repeatResult = apply(expansion, GroupingKind.NAMESPACE, locations.reversed())
        val directoryResult = apply(expansion, GroupingKind.DIRECTORY, locations)

        val namespaceGroups = namespaceResult.neighbours.filter { it.kind == NodeKind.GROUP }
        val directoryGroups = directoryResult.neighbours.filter { it.kind == NodeKind.GROUP }
        assertEquals(setOf("app.first", "app.second"), namespaceGroups.map { it.label }.toSet())
        assertEquals(setOf("src"), directoryGroups.map { it.label }.toSet())
        assertEquals(
            namespaceGroups.map { it.id }.toSet(),
            repeatResult.neighbours
                .filter { it.kind == NodeKind.GROUP }
                .map { it.id }
                .toSet(),
        )
        assertNotEquals(namespaceGroups.map { it.id }.toSet(), directoryGroups.map { it.id }.toSet())
        assertTrue(namespaceGroups.all { it.id.value.contains("/GROUP/NAMESPACE/") })
        assertTrue(directoryGroups.all { it.id.value.contains("/GROUP/DIRECTORY/") })
        assertEquals(directoryResult, apply(expansion, GroupingKind.DIRECTORY, locations.reversed()))
    }

    @Test
    fun nestedDirectoriesAndRootFilesUseStableRootAndDirectoryGroups() {
        val root = entity("Root.kt", "Root")
        val nested = entity("src/nested/Deep.kt", "Deep")
        val expansion = Expansion(graphId, "revision", root, listOf(nested), emptyList())
        val locations =
            listOf(
                FileLocation(root.fileUri!!, "Root.kt", ""),
                FileLocation(nested.fileUri!!, "src/nested/Deep.kt", ""),
            )

        val namespaceResult = apply(expansion, GroupingKind.NAMESPACE, locations)
        val directoryResult = apply(expansion, GroupingKind.DIRECTORY, locations)

        val namespaceRootFile = namespaceResult.fileNode(root.fileUri!!)
        val namespaceNestedFile = namespaceResult.fileNode(nested.fileUri!!)
        assertEquals("Root.kt", namespaceRootFile.label)
        assertEquals("Deep.kt", namespaceNestedFile.label)
        assertEquals("(root)", node(namespaceResult, namespaceRootFile.parent!!).label)
        assertEquals("(root)", node(namespaceResult, namespaceNestedFile.parent!!).label)
        assertEquals(NodeId("graph/GROUP/NAMESPACE/."), namespaceRootFile.parent)
        assertEquals(NodeId("graph/GROUP/NAMESPACE/."), namespaceNestedFile.parent)

        val directoryRootFile = directoryResult.fileNode(root.fileUri!!)
        val directoryNestedFile = directoryResult.fileNode(nested.fileUri!!)
        assertEquals("(root)", node(directoryResult, directoryRootFile.parent!!).label)
        assertEquals("nested", node(directoryResult, directoryNestedFile.parent!!).label)
        assertEquals(NodeId("graph/GROUP/DIRECTORY/."), directoryRootFile.parent)
        assertEquals(NodeId("graph/GROUP/DIRECTORY/src/nested"), directoryNestedFile.parent)
        assertNotEquals(namespaceRootFile.parent, directoryRootFile.parent)
    }

    @Test
    fun rootPackageFilesInDifferentDirectoriesShareOneNamespaceGroup() {
        val first = entity("src/one/First.kt", "First")
        val second = entity("src/two/Deep.kt", "Deep")
        val expansion = Expansion(graphId, "revision", first, listOf(second), emptyList())
        val locations =
            listOf(
                FileLocation(first.fileUri!!, "src/one/First.kt", ""),
                FileLocation(second.fileUri!!, "src/two/Deep.kt", ""),
            )

        val namespaceResult = apply(expansion, GroupingKind.NAMESPACE, locations)
        val directoryResult = apply(expansion, GroupingKind.DIRECTORY, locations)

        val namespaceFirstFile = namespaceResult.fileNode(first.fileUri!!)
        val namespaceSecondFile = namespaceResult.fileNode(second.fileUri!!)
        assertEquals(namespaceFirstFile.parent, namespaceSecondFile.parent)
        assertEquals(NodeId("graph/GROUP/NAMESPACE/."), namespaceFirstFile.parent)
        assertEquals(1, namespaceResult.neighbours.count { it.kind == NodeKind.GROUP })
        assertEquals("(root)", node(namespaceResult, namespaceFirstFile.parent!!).label)

        val directoryFirstFile = directoryResult.fileNode(first.fileUri!!)
        val directorySecondFile = directoryResult.fileNode(second.fileUri!!)
        assertNotEquals(directoryFirstFile.parent, directorySecondFile.parent)
        assertEquals(NodeId("graph/GROUP/DIRECTORY/src/one"), directoryFirstFile.parent)
        assertEquals(NodeId("graph/GROUP/DIRECTORY/src/two"), directorySecondFile.parent)
    }

    @Test
    fun everyEntityHasExactlyOneFileParentAndOneGroupParent() {
        val first = entity("src/app/First.kt", "app.alpha.First")
        val second = entity("src/app/Second.kt", "app.beta.Second")
        val expansion =
            Expansion(
                graphId,
                "revision",
                first,
                listOf(second),
                listOf(references(first, second, 1)),
            )
        val result =
            apply(
                expansion,
                GroupingKind.NAMESPACE,
                listOf(
                    FileLocation(first.fileUri!!, "src/app/First.kt", "app.alpha"),
                    FileLocation(second.fileUri!!, "src/app/Second.kt", "app.beta"),
                ),
            )

        val entities = (sequenceOf(result.node) + result.neighbours.asSequence()).filter { it.kind == NodeKind.ENTITY }
        for (entity in entities) {
            val fileId = assertNotNull(entity.parent)
            val file = node(result, fileId)
            assertEquals(NodeKind.FILE, file.kind)
            val entityContains =
                result.edges.filter {
                    it.kind == EdgeKind.CONTAINS &&
                        it.key == EdgeKey.of(EdgeKind.CONTAINS, entity.id, fileId)
                }
            assertEquals(1, entityContains.size)
            val groupId = assertNotNull(file.parent)
            val group = node(result, groupId)
            assertEquals(NodeKind.GROUP, group.kind)
            val fileContains =
                result.edges.filter {
                    it.kind == EdgeKind.CONTAINS &&
                        it.key == EdgeKey.of(EdgeKind.CONTAINS, fileId, groupId)
                }
            assertEquals(1, fileContains.size)
        }
    }

    @Test
    fun missingLocationForAnEntityFileThrows() {
        val first = entity("src/app/First.kt", "app.First")
        val second = entity("src/app/Second.kt", "app.Second")
        val expansion = Expansion(graphId, "revision", first, listOf(second), emptyList())

        assertFailsWith<IllegalArgumentException> {
            apply(
                expansion,
                GroupingKind.NAMESPACE,
                listOf(FileLocation(first.fileUri!!, "src/app/First.kt", "app")),
            )
        }
    }

    @Test
    fun outputIsDeterministicAndOriginCountsAreConsistent() {
        val first = entity("src/a/First.kt", "app.a.First")
        val second = entity("src/b/Second.kt", "app.b.Second")
        val third = entity("src/a/Third.kt", "app.a.Third")
        val expansion =
            Expansion(
                graphId,
                "revision",
                first,
                listOf(second, third),
                listOf(
                    Edge.of(
                        EdgeKind.REFERENCES,
                        first.id,
                        second.id,
                        5,
                        listOf(EdgeOrigin(first.id, second.id, 2), EdgeOrigin(second.id, first.id, 3)),
                    ),
                    references(third, second, 1),
                ),
            )
        val locations =
            listOf(
                FileLocation(first.fileUri!!, "src/a/First.kt", "app.a"),
                FileLocation(second.fileUri!!, "src/b/Second.kt", "app.b"),
                FileLocation(third.fileUri!!, "src/a/Third.kt", "app.a"),
            )
        val firstResult = apply(expansion, GroupingKind.DIRECTORY, locations)
        val secondResult = apply(expansion, GroupingKind.DIRECTORY, locations.reversed())

        assertEquals(firstResult, secondResult)
        assertEquals(firstResult.neighbours.sortedBy { it.id.value }, firstResult.neighbours)
        assertEquals(
            firstResult
                .nodes()
                .map { it.id }
                .distinct()
                .size,
            firstResult.nodes().size,
        )
        assertEquals(firstResult.edges.sortedWith(EDGE_ORDER), firstResult.edges)
        firstResult.edges.forEach { edge ->
            assertTrue(edge.from != edge.to)
            assertEquals(
                edge.origins.size,
                edge.origins
                    .map { it.from to it.to }
                    .distinct()
                    .size,
            )
            if (edge.kind == EdgeKind.CONTAINS) {
                assertEquals(1, edge.count)
                assertTrue(edge.origins.isEmpty())
            } else {
                assertEquals(edge.origins.sumOf { it.count }, edge.count)
            }
            assertTrue(edge.origins.all { it.from != it.to })
        }
        val fileEdge =
            firstResult.edge(
                EdgeKind.FILE_REFERENCES,
                firstResult.fileNode(first.fileUri!!).id,
                firstResult.fileNode(second.fileUri!!).id,
            )
        assertEquals(5, fileEdge.count)
        assertEquals(2, fileEdge.origins.single { it.from == firstResult.fileNode(first.fileUri!!).id }.count)
        assertEquals(3, fileEdge.origins.single { it.from == firstResult.fileNode(second.fileUri!!).id }.count)
    }

    @Test
    fun groupingPolicyBindsOneKindPerGraph() {
        val policy = GroupingPolicy()

        assertEquals(GroupingKind.NAMESPACE, policy.bind(graphId, GroupingKind.NAMESPACE))
        assertEquals(GroupingKind.NAMESPACE, policy.bind(graphId, GroupingKind.NAMESPACE))
        assertEquals(GroupingKind.NAMESPACE, policy.kindFor(graphId))
        assertFailsWith<IllegalArgumentException> {
            policy.bind(graphId, GroupingKind.DIRECTORY)
        }
        assertEquals(GroupingKind.NAMESPACE, policy[graphId])
    }

    @Test
    fun groupingPolicyKeepsDifferentGraphsIndependent() {
        val policy = GroupingPolicy()
        val otherGraph = GraphId("other-graph")

        assertEquals(GroupingKind.NAMESPACE, policy.bind(graphId, GroupingKind.NAMESPACE))
        assertEquals(GroupingKind.DIRECTORY, policy.bind(otherGraph, GroupingKind.DIRECTORY))
        assertEquals(GroupingKind.NAMESPACE, policy.kindFor(graphId))
        assertEquals(GroupingKind.DIRECTORY, policy.kindFor(otherGraph))
    }

    @Test
    fun containmentBuilderRejectsAPerCallKindMismatchForTheSameGraph() {
        val first = entity("src/First.kt", "First")
        val second = entity("src/Second.kt", "Second")
        val expansion = Expansion(graphId, "revision", first, listOf(second), emptyList())
        val locations =
            listOf(
                FileLocation(first.fileUri!!, "src/First.kt", ""),
                FileLocation(second.fileUri!!, "src/Second.kt", ""),
            )
        val policy = GroupingPolicy()

        val result = apply(expansion, GroupingKind.NAMESPACE, locations, policy)

        assertEquals(NodeId("graph/GROUP/NAMESPACE/."), result.fileNode(first.fileUri!!).parent)
        assertFailsWith<IllegalArgumentException> {
            apply(expansion, GroupingKind.DIRECTORY, locations, policy)
        }
        assertEquals(GroupingKind.NAMESPACE, policy.kindFor(graphId))
    }

    private fun apply(
        expansion: Expansion,
        groupingKind: GroupingKind,
        locations: Iterable<FileLocation>,
        policy: GroupingPolicy = GroupingPolicy(),
    ): Expansion = ContainmentBuilder.apply(expansion, groupingKind, locations, policy)

    private fun entity(
        fileUri: String,
        qualifiedName: String,
    ): Node {
        val label = qualifiedName.substringAfterLast('.')
        return Node(
            id = NodeId("${graphId.value}/$fileUri/$qualifiedName/FUNCTION/fun $label()"),
            kind = NodeKind.ENTITY,
            label = label,
            qualifiedName = qualifiedName,
            fileUri = fileUri,
            signatureFingerprint = "fun $label()",
        )
    }

    private fun references(
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

    private fun Expansion.nodes(): List<Node> = listOf(node) + neighbours

    private fun Expansion.fileNode(fileUri: String): Node = neighbours.single { it.kind == NodeKind.FILE && it.fileUri == fileUri }

    private fun Expansion.groupNode(label: String): Node = neighbours.single { it.kind == NodeKind.GROUP && it.label == label }

    private fun Expansion.edge(
        kind: EdgeKind,
        first: NodeId,
        second: NodeId,
    ): Edge = edges.single { it.key == EdgeKey.of(kind, first, second) }

    private fun node(
        expansion: Expansion,
        id: NodeId,
    ): Node = (sequenceOf(expansion.node) + expansion.neighbours.asSequence()).single { it.id == id }

    private fun assertEdge(
        expansion: Expansion,
        kind: EdgeKind,
        first: NodeId,
        second: NodeId,
        origins: Map<Pair<NodeId, NodeId>, Int>,
    ) {
        val edge = expansion.edge(kind, first, second)
        assertEquals(origins, edge.origins.associate { (it.from to it.to) to it.count })
        assertEquals(origins.values.sum(), edge.count)
    }

    private companion object {
        val EDGE_ORDER: Comparator<Edge> =
            compareBy<Edge> { it.kind.name }
                .thenBy { it.from.value }
                .thenBy { it.to.value }
    }
}
