package dev.codenb.server

import dev.codenb.api.EngineError
import dev.codenb.api.EngineErrorCode
import dev.codenb.api.Expansion
import dev.codenb.api.GraphId
import dev.codenb.api.GraphInfo
import dev.codenb.api.NodeId
import dev.codenb.api.NodeKind

sealed interface Seed {
    data class Selector(
        val file: String,
        val qualifiedName: String,
        val signatureFingerprint: String? = null,
    ) : Seed

    data class ByNodeId(
        val nodeId: NodeId,
    ) : Seed
}

data class GraphStatus(
    val open: Boolean,
    val revision: String?,
)

interface GraphService : AutoCloseable {
    fun info(): GraphInfo

    fun status(): GraphStatus

    fun expandableNodeKinds(): List<NodeKind>

    fun open(seed: Seed): Expansion

    fun expand(
        graphId: GraphId,
        nodeId: NodeId,
        revision: String,
    ): Expansion

    fun node(
        graphId: GraphId,
        nodeId: NodeId,
    ): Expansion

    fun dot(graphId: GraphId): String

    override fun close()
}

class EngineException(
    val error: EngineError,
    cause: Throwable? = null,
) : RuntimeException(error.message, cause)

fun engineException(
    code: EngineErrorCode,
    message: String,
    vararg details: Pair<String, String>,
): EngineException = EngineException(EngineError(code, message, mapOf(*details)))
