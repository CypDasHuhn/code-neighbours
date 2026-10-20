package dev.codenb.api

import kotlinx.serialization.Serializable

@Serializable
data class EdgeOrigin(
    val from: NodeId,
    val to: NodeId,
    val count: Int,
) {
    init {
        require(from != to) { "Origin must not be a self-edge" }
        require(count > 0) { "Origin count must be positive" }
    }
}

@Serializable
data class EdgeKey(
    val kind: EdgeKind,
    val from: NodeId,
    val to: NodeId,
) {
    init {
        require(from != to) { "Edge key must not be a self-edge" }
        require(from.value <= to.value) { "Edge key endpoints must be canonical" }
    }

    companion object {
        fun of(
            kind: EdgeKind,
            a: NodeId,
            b: NodeId,
        ): EdgeKey =
            if (a.value <= b.value) {
                EdgeKey(kind, a, b)
            } else {
                EdgeKey(kind, b, a)
            }
    }
}

@Serializable
data class Edge(
    val kind: EdgeKind,
    val from: NodeId,
    val to: NodeId,
    val count: Int,
    val origins: List<EdgeOrigin> = emptyList(),
) {
    init {
        require(from != to) { "Edge must not be a self-edge" }
        require(from.value <= to.value) { "Edge endpoints must be canonical" }
        require(count > 0) { "Edge count must be positive" }
        origins.forEach { origin ->
            require(
                (origin.from == from && origin.to == to) ||
                    (origin.from == to && origin.to == from),
            ) { "Origin endpoints must match the edge" }
        }
        val originPairs = origins.map { it.from to it.to }
        require(originPairs.size == originPairs.toSet().size) { "Duplicate origins are not allowed" }
        if (kind != EdgeKind.CONTAINS) {
            require(origins.isNotEmpty()) { "Reference edges require at least one origin" }
        }
        if (origins.isNotEmpty()) {
            require(count == origins.sumOf { it.count }) { "Edge count must equal the origin counts" }
        }
    }

    val key: EdgeKey
        get() = EdgeKey(kind, from, to)

    companion object {
        fun of(
            kind: EdgeKind,
            a: NodeId,
            b: NodeId,
            count: Int,
            origins: List<EdgeOrigin> = emptyList(),
        ): Edge =
            if (a.value <= b.value) {
                Edge(kind, a, b, count, origins)
            } else {
                Edge(kind, b, a, count, origins)
            }
    }
}
