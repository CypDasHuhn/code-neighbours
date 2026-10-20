package dev.codenb.api

import kotlinx.serialization.Serializable

@Serializable
data class Expansion(
    val graphId: GraphId,
    val revision: String,
    val node: Node,
    val neighbours: List<Node>,
    val edges: List<Edge>,
)
