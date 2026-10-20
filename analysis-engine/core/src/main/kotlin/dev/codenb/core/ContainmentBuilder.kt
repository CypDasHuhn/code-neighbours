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

object ContainmentBuilder {
    fun apply(
        expansion: Expansion,
        groupingKind: GroupingKind,
        locations: Iterable<FileLocation>,
        policy: GroupingPolicy,
    ): Expansion {
        policy.bind(expansion.graphId, groupingKind)
        val locationsByUri = locations.associateBy { it.fileUri }
        val entityNodes = orderedEntityNodes(expansion)
        val fileNodes = linkedMapOf<NodeId, Node>()
        val groupNodes = linkedMapOf<NodeId, Node>()
        val fileForEntity = mutableMapOf<NodeId, NodeId>()
        val groupForFile = mutableMapOf<NodeId, NodeId>()

        entityNodes.values.forEach { entity ->
            val fileUri = requireNotNull(entity.fileUri) { "Entity ${entity.id.value} has no file URI" }
            val location =
                locationsByUri[fileUri]
                    ?: throw IllegalArgumentException("Missing file location for entity file $fileUri")
            val relativePath = FileLocation.normalizePath(location.relativePath)
            val group = groupNode(expansion.graphId, groupingKind, location, relativePath)
            groupNodes[group.id] = group
            val file = fileNode(expansion.graphId, location, relativePath, group.id)
            fileNodes[file.id] = file
            groupForFile[file.id] = group.id
            fileForEntity[entity.id] = file.id
        }

        val references = expansion.edges.filter { it.kind == EdgeKind.REFERENCES }
        val fileOrigins = mutableMapOf<Directed, Int>()
        references.forEach { edge ->
            edge.origins.forEach { origin ->
                val fromFile =
                    fileForEntity[origin.from]
                        ?: throw IllegalArgumentException("Missing entity node ${origin.from.value} in expansion")
                val toFile =
                    fileForEntity[origin.to]
                        ?: throw IllegalArgumentException("Missing entity node ${origin.to.value} in expansion")
                if (fromFile != toFile) {
                    fileOrigins.merge(Directed(fromFile, toFile), origin.count, Int::plus)
                }
            }
        }

        val groupOrigins = mutableMapOf<Directed, Int>()
        fileOrigins.forEach { (directed, count) ->
            val fromGroup =
                groupForFile[directed.from]
                    ?: throw IllegalArgumentException("Missing file node ${directed.from.value} in expansion")
            val toGroup =
                groupForFile[directed.to]
                    ?: throw IllegalArgumentException("Missing file node ${directed.to.value} in expansion")
            if (fromGroup != toGroup) {
                groupOrigins.merge(Directed(fromGroup, toGroup), count, Int::plus)
            }
        }

        val containedNodes = linkedMapOf<NodeId, Node>()
        entityNodes.forEach { (id, node) ->
            val fileId = fileForEntity.getValue(id)
            containedNodes[id] = node.copy(parent = fileId)
        }
        fileNodes.forEach { (id, node) -> containedNodes[id] = node }
        groupNodes.forEach { (id, node) -> containedNodes[id] = node }

        val seed = containedNodes.getValue(expansion.node.id)
        val neighbours =
            containedNodes.values
                .filter { it.id != seed.id }
                .sortedBy { it.id.value }
        val containmentEdges =
            entityNodes.keys.map { entityId ->
                Edge.of(EdgeKind.CONTAINS, entityId, fileForEntity.getValue(entityId), 1)
            } +
                fileNodes.keys.map { fileId ->
                    Edge.of(EdgeKind.CONTAINS, fileId, groupForFile.getValue(fileId), 1)
                }
        val edges =
            (
                expansion.edges +
                    containmentEdges +
                    referenceEdges(EdgeKind.FILE_REFERENCES, fileOrigins) +
                    referenceEdges(EdgeKind.GROUP_REFERENCES, groupOrigins)
            ).sortedWith(EDGE_ORDER)
        return Expansion(
            graphId = expansion.graphId,
            revision = expansion.revision,
            node = seed,
            neighbours = neighbours,
            edges = edges,
        )
    }

    fun apply(
        expansion: Expansion,
        groupingKind: GroupingKind,
        policy: GroupingPolicy,
        locations: Iterable<FileLocation>,
    ): Expansion = apply(expansion, groupingKind, locations, policy)

    private fun orderedEntityNodes(expansion: Expansion): Map<NodeId, Node> {
        val result = linkedMapOf<NodeId, Node>()
        sequenceOf(expansion.node)
            .plus(expansion.neighbours.asSequence())
            .filter { it.kind == NodeKind.ENTITY }
            .forEach { result[it.id] = it }
        return result
    }

    private fun fileNode(
        graphId: GraphId,
        location: FileLocation,
        relativePath: String,
        groupId: NodeId,
    ): Node =
        Node(
            id = NodeId("${graphId.value}/FILE/$relativePath"),
            kind = NodeKind.FILE,
            label = relativePath.substringAfterLast('/'),
            fileUri = location.fileUri,
            parent = groupId,
        )

    private fun groupNode(
        graphId: GraphId,
        groupingKind: GroupingKind,
        location: FileLocation,
        relativePath: String,
    ): Node {
        val namespace = location.namespace.trim()
        val directory = relativePath.substringBeforeLast('/', "")
        val key =
            when (groupingKind) {
                GroupingKind.NAMESPACE -> namespace.ifBlank { ROOT_KEY }
                GroupingKind.DIRECTORY -> directory.ifBlank { ROOT_KEY }
            }
        val label =
            when {
                groupingKind == GroupingKind.NAMESPACE && namespace.isNotBlank() -> namespace
                key == ROOT_KEY -> ROOT_LABEL
                else -> key.substringAfterLast('/')
            }
        return Node(
            id = NodeId("${graphId.value}/GROUP/${groupingKind.name}/$key"),
            kind = NodeKind.GROUP,
            label = label,
        )
    }

    private fun referenceEdges(
        kind: EdgeKind,
        directedCounts: Map<Directed, Int>,
    ): List<Edge> =
        directedCounts.entries
            .groupBy { (directed, _) -> EdgeKey.of(kind, directed.from, directed.to) }
            .map { (key, entries) ->
                val origins =
                    entries
                        .map { (directed, count) -> EdgeOrigin(directed.from, directed.to, count) }
                        .sortedWith(ORIGIN_ORDER)
                Edge.of(kind, key.from, key.to, origins.sumOf { it.count }, origins)
            }.sortedWith(EDGE_ORDER)

    private data class Directed(
        val from: NodeId,
        val to: NodeId,
    )

    private const val ROOT_KEY = "."
    private const val ROOT_LABEL = "(root)"

    private val ORIGIN_ORDER: Comparator<EdgeOrigin> =
        compareBy<EdgeOrigin> { it.from.value }
            .thenBy { it.to.value }

    private val EDGE_ORDER: Comparator<Edge> =
        compareBy<Edge> { it.kind.name }
            .thenBy { it.from.value }
            .thenBy { it.to.value }
}
