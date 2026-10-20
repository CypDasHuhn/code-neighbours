package dev.codenb.api

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class GraphId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Graph id must not be blank" }
    }
}

@Serializable
@JvmInline
value class NodeId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Node id must not be blank" }
    }
}
