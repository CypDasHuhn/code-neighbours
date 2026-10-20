package dev.codenb.core

import dev.codenb.api.Edge
import dev.codenb.api.EdgeKey
import dev.codenb.api.EdgeKind
import dev.codenb.api.EdgeOrigin
import dev.codenb.api.Node
import dev.codenb.api.NodeId
import dev.codenb.api.NodeKind

object ReferenceAggregation {
    fun edges(
        nodes: Collection<Node>,
        referenceEdges: Iterable<Edge>,
        fileScope: Set<NodeId>? = null,
    ): List<Edge> {
        val nodesById = nodes.associateBy { it.id }
        val fileOrigins = mutableMapOf<Directed, Int>()
        val groupOrigins = mutableMapOf<Directed, Int>()
        referenceEdges
            .filter { it.kind == EdgeKind.REFERENCES }
            .forEach { edge ->
                edge.origins.forEach { origin ->
                    val from = nodesById[origin.from] ?: return@forEach
                    val to = nodesById[origin.to] ?: return@forEach
                    val fromFile = fileFor(from, nodesById) ?: return@forEach
                    val toFile = fileFor(to, nodesById) ?: return@forEach
                    if (fileScope != null && (fromFile !in fileScope || toFile !in fileScope)) {
                        return@forEach
                    }
                    if (fromFile != toFile) {
                        fileOrigins.merge(Directed(fromFile, toFile), origin.count, Int::plus)
                    }
                    val fromGroup = groupFor(fromFile, nodesById) ?: return@forEach
                    val toGroup = groupFor(toFile, nodesById) ?: return@forEach
                    if (fromGroup != toGroup) {
                        groupOrigins.merge(Directed(fromGroup, toGroup), origin.count, Int::plus)
                    }
                }
            }
        return buildEdges(EdgeKind.FILE_REFERENCES, fileOrigins) +
            buildEdges(EdgeKind.GROUP_REFERENCES, groupOrigins)
    }

    private fun fileFor(
        node: Node,
        nodesById: Map<NodeId, Node>,
    ): NodeId? {
        if (node.kind != NodeKind.ENTITY) {
            return null
        }
        val parent = node.parent?.let { nodesById[it] }
        if (parent?.kind == NodeKind.FILE) {
            return parent.id
        }
        return nodesById.values
            .firstOrNull { candidate -> candidate.kind == NodeKind.FILE && candidate.fileUri == node.fileUri }
            ?.id
    }

    private fun groupFor(
        fileId: NodeId,
        nodesById: Map<NodeId, Node>,
    ): NodeId? {
        val file = nodesById[fileId] ?: return null
        val parent = file.parent ?: return null
        return parent.takeIf { nodesById[it]?.kind == NodeKind.GROUP }
    }

    private fun buildEdges(
        kind: EdgeKind,
        directedCounts: Map<Directed, Int>,
    ): List<Edge> =
        directedCounts.entries
            .groupBy { (directed, _) -> EdgeKey.of(kind, directed.from, directed.to) }
            .map { (key, entries) ->
                val origins =
                    entries
                        .map { (directed, count) -> EdgeOrigin(directed.from, directed.to, count) }
                        .sortedWith(compareBy({ it.from.value }, { it.to.value }))
                Edge.of(kind, key.from, key.to, origins.sumOf { it.count }, origins)
            }.sortedWith(compareBy({ it.from.value }, { it.to.value }))

    private data class Directed(
        val from: NodeId,
        val to: NodeId,
    )
}
