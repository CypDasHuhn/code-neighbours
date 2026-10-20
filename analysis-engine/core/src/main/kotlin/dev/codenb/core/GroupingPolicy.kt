package dev.codenb.core

import dev.codenb.api.GraphId
import dev.codenb.api.GroupingKind

class GroupingPolicy(
    initial: Map<GraphId, GroupingKind> = emptyMap(),
) {
    private val lock = Any()
    private val bindings = initial.toMutableMap()

    fun bind(
        graphId: GraphId,
        kind: GroupingKind,
    ): GroupingKind =
        synchronized(lock) {
            val existing = bindings[graphId]
            if (existing == null) {
                bindings[graphId] = kind
                kind
            } else {
                require(existing == kind) {
                    "Graph ${graphId.value} is already bound to grouping ${existing.name}, not ${kind.name}"
                }
                existing
            }
        }

    fun kindFor(graphId: GraphId): GroupingKind? = synchronized(lock) { bindings[graphId] }

    fun groupingKind(graphId: GraphId): GroupingKind? = kindFor(graphId)

    fun requireKind(
        graphId: GraphId,
        kind: GroupingKind,
    ): GroupingKind = bind(graphId, kind)

    operator fun get(graphId: GraphId): GroupingKind? = kindFor(graphId)

    fun clear() {
        synchronized(lock) {
            bindings.clear()
        }
    }
}
