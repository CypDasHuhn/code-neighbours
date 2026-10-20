package dev.codenb.core

import dev.codenb.api.Edge
import dev.codenb.api.EdgeKey
import dev.codenb.api.EdgeOrigin
import dev.codenb.api.Expansion
import dev.codenb.api.GraphId
import dev.codenb.api.Node
import dev.codenb.api.NodeId

data class GraphSnapshot(
    val graphId: GraphId,
    val nodes: List<Node>,
    val edges: List<Edge>,
)

class GraphStore(
    val cache: ExpansionCache = InMemoryExpansionCache(DEFAULT_MAX_CACHE_ENTRIES),
) {
    private val lock = Any()
    private val graphs = mutableMapOf<GraphId, MutableGraph>()

    fun merge(expansion: Expansion): GraphSnapshot =
        synchronized(lock) {
            val graph = graphs.getOrPut(expansion.graphId) { MutableGraph() }
            graph.merge(expansion)
            graph.snapshot(expansion.graphId)
        }

    fun expand(
        graphId: GraphId,
        nodeId: NodeId,
        revision: String,
        compute: () -> Expansion,
    ): Expansion {
        val key = CacheKey(graphId, nodeId, revision)
        val cached = cache.get(key)
        if (cached != null) {
            validate(cached, graphId, nodeId, revision)
            merge(cached)
            return cached
        }
        val expansion = compute()
        validate(expansion, graphId, nodeId, revision)
        merge(expansion)
        cache.put(key, expansion)
        return expansion
    }

    private fun validate(
        expansion: Expansion,
        graphId: GraphId,
        nodeId: NodeId,
        revision: String,
    ) {
        require(expansion.graphId == graphId) {
            "Expansion graph id ${expansion.graphId.value} does not match requested graph ${graphId.value}"
        }
        require(expansion.node.id == nodeId) {
            "Expansion node id ${expansion.node.id.value} does not match requested node ${nodeId.value}"
        }
        require(expansion.revision == revision) {
            "Expansion revision ${expansion.revision} does not match requested revision $revision"
        }
    }

    fun snapshot(graphId: GraphId): GraphSnapshot =
        synchronized(lock) {
            graphs[graphId]?.snapshot(graphId) ?: GraphSnapshot(graphId, emptyList(), emptyList())
        }

    fun graph(graphId: GraphId): GraphSnapshot = snapshot(graphId)

    fun graphIds(): Set<GraphId> = synchronized(lock) { graphs.keys.toSet() }

    fun snapshots(): List<GraphSnapshot> =
        synchronized(lock) {
            graphs.keys
                .sortedBy { it.value }
                .map { graphId -> graphs.getValue(graphId).snapshot(graphId) }
        }

    private class MutableGraph {
        private var nodes = mutableMapOf<NodeId, Node>()
        private var edges = mutableMapOf<EdgeKey, Edge>()

        fun merge(expansion: Expansion) {
            val updatedNodes = nodes.toMutableMap()
            addNode(updatedNodes, expansion.node)
            expansion.neighbours.forEach { node -> addNode(updatedNodes, node) }
            expansion.edges.forEach { edge ->
                require(updatedNodes.containsKey(edge.from) && updatedNodes.containsKey(edge.to)) {
                    "Edge ${edge.key} references a node that is not present"
                }
            }
            val updatedEdges = edges.toMutableMap()
            expansion.edges.forEach { edge ->
                val existing = updatedEdges[edge.key]
                updatedEdges[edge.key] = if (existing == null) edge else mergeEdge(existing, edge)
            }
            nodes = updatedNodes
            edges = updatedEdges
        }

        fun snapshot(graphId: GraphId): GraphSnapshot =
            GraphSnapshot(
                graphId = graphId,
                nodes = nodes.values.sortedBy { it.id.value },
                edges = edges.values.sortedWith(EDGE_ORDER),
            )

        private fun addNode(
            target: MutableMap<NodeId, Node>,
            node: Node,
        ) {
            val existing = target[node.id]
            if (existing == null) {
                target[node.id] = node
            } else {
                require(sameIdentity(existing, node)) {
                    "Node ${node.id.value} already exists with different identity"
                }
                target[node.id] = mergeMetadata(existing, node)
            }
        }

        private fun sameIdentity(
            existing: Node,
            incoming: Node,
        ): Boolean =
            existing.id == incoming.id &&
                existing.kind == incoming.kind &&
                existing.fileUri == incoming.fileUri &&
                existing.qualifiedName == incoming.qualifiedName &&
                existing.signatureFingerprint == incoming.signatureFingerprint

        private fun mergeMetadata(
            existing: Node,
            incoming: Node,
        ): Node = incoming.copy(parent = incoming.parent ?: existing.parent)

        private fun mergeEdge(
            existing: Edge,
            incoming: Edge,
        ): Edge {
            if (existing.origins.isEmpty() && incoming.origins.isEmpty()) {
                return Edge.of(
                    existing.kind,
                    existing.from,
                    existing.to,
                    maxOf(existing.count, incoming.count),
                )
            }
            if (existing.origins.isEmpty() || incoming.origins.isEmpty()) {
                return Edge.of(
                    existing.kind,
                    existing.from,
                    existing.to,
                    maxOf(existing.count, incoming.count),
                )
            }
            val origins = mutableMapOf<Pair<NodeId, NodeId>, Int>()
            existing.origins.forEach { origin ->
                origins.merge(origin.from to origin.to, origin.count) { first, second -> maxOf(first, second) }
            }
            incoming.origins.forEach { origin ->
                origins.merge(origin.from to origin.to, origin.count) { first, second -> maxOf(first, second) }
            }
            val mergedOrigins =
                origins.entries
                    .map { (pair, count) -> EdgeOrigin(pair.first, pair.second, count) }
                    .sortedWith(compareBy({ it.from.value }, { it.to.value }))
            return Edge.of(
                existing.kind,
                existing.from,
                existing.to,
                mergedOrigins.sumOf { it.count },
                mergedOrigins,
            )
        }

        private companion object {
            val EDGE_ORDER: Comparator<Edge> =
                compareBy<Edge> { it.kind.name }
                    .thenBy { it.from.value }
                    .thenBy { it.to.value }
        }
    }

    private companion object {
        const val DEFAULT_MAX_CACHE_ENTRIES = 1024
    }
}
