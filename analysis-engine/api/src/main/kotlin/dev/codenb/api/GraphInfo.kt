package dev.codenb.api

import kotlinx.serialization.Serializable

@Serializable
data class GraphInfo(
    val graphId: GraphId,
    val root: String,
    val lspId: String,
    val groupingKind: GroupingKind,
    val nodeKinds: List<NodeKind>,
    val edgeKinds: List<EdgeKind>,
    val protocolVersion: Int,
) {
    init {
        require(nodeKinds == NodeKind.entries.filter { it in nodeKinds }) {
            "Node kinds must be unique and in enum order"
        }
        require(edgeKinds == EdgeKind.entries.filter { it in edgeKinds }) {
            "Edge kinds must be unique and in enum order"
        }
        require(protocolVersion == PROTOCOL_VERSION) { "Protocol version must match the supported version" }
    }
}
