package dev.codenb.core

import dev.codenb.api.Edge
import dev.codenb.api.EdgeKind
import dev.codenb.api.Node
import dev.codenb.api.NodeId
import dev.codenb.api.NodeKind
import dev.codenb.core.GraphSnapshot

object DotWriter {
    fun write(snapshot: GraphSnapshot): String {
        val nodes = snapshot.nodes.sortedBy { it.id.value }
        val directEdges =
            snapshot.edges.filter { edge ->
                edge.kind != EdgeKind.FILE_REFERENCES && edge.kind != EdgeKind.GROUP_REFERENCES
            }
        val aggregationEdges =
            ReferenceAggregation.edges(
                snapshot.nodes,
                snapshot.edges.filter { it.kind == EdgeKind.REFERENCES },
            )
        val edges = (directEdges + aggregationEdges).sortedWith(EDGE_ORDER)
        val parents = nodes.mapNotNull { node -> node.parent?.let { node.id to it } }.toMap()
        val groups = nodes.filter { it.kind == NodeKind.GROUP }.sortedBy { it.id.value }
        val assignment = nodes.associate { it.id to groupOf(it, nodes, parents) }
        val output = StringBuilder()
        output.append("graph \"code-neighbours\" {\n")
        output.append("  graph [overlap=false, splines=true];\n")
        output.append("  node [fontname=\"sans-serif\"];\n")
        output.append("  edge [dir=none];\n")
        groups.forEachIndexed { index, group ->
            output.append("  subgraph \"cluster_$index\" {\n")
            output.append("    label=\"${escape(group.label)}\";\n")
            nodes
                .filter { assignment[it.id] == group.id }
                .forEach { node -> appendNode(output, node) }
            output.append("  }\n")
        }
        nodes
            .filter { assignment[it.id] == null }
            .forEach { node -> appendNode(output, node) }
        edges.forEach { edge -> appendEdge(output, edge) }
        output.append("}\n")
        return output.toString()
    }

    private fun appendNode(
        output: StringBuilder,
        node: Node,
    ) {
        output.append("    \"${escape(node.id.value)}\" [label=\"${escape(node.label)}\", shape=\"${shape(node.kind)}\"];\n")
    }

    private fun appendEdge(
        output: StringBuilder,
        edge: Edge,
    ) {
        val label = if (edge.kind == EdgeKind.CONTAINS) "" else ", label=\"${edge.count}\""
        output.append(
            "  \"${escape(edge.from.value)}\" -- \"${escape(edge.to.value)}\" [kind=\"${edge.kind.name}\"$label];\n",
        )
    }

    private fun groupOf(
        node: Node,
        nodes: List<Node>,
        parents: Map<NodeId, NodeId>,
    ): NodeId? {
        if (node.kind == NodeKind.GROUP) {
            return node.id
        }
        val byId = nodes.associateBy { it.id }
        var current = parents[node.id]
        while (current != null) {
            val parent = byId[current] ?: return null
            if (parent.kind == NodeKind.GROUP) {
                return parent.id
            }
            current = parents[parent.id]
        }
        return null
    }

    private fun shape(kind: NodeKind): String =
        when (kind) {
            NodeKind.ENTITY -> "box"
            NodeKind.FILE -> "note"
            NodeKind.GROUP -> "folder"
        }

    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "")
            .replace("\n", "\\n")

    private val EDGE_ORDER: Comparator<Edge> =
        compareBy<Edge> { it.kind.name }
            .thenBy { it.from.value }
            .thenBy { it.to.value }
}
