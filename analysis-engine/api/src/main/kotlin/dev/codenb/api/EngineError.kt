package dev.codenb.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class EngineErrorCode {
    @SerialName("unsupported-capability")
    UNSUPPORTED_CAPABILITY,

    @SerialName("lsp-failure")
    LSP_FAILURE,

    @SerialName("workspace-error")
    WORKSPACE_ERROR,

    @SerialName("invalid-request")
    INVALID_REQUEST,

    @SerialName("unknown-node")
    UNKNOWN_NODE,

    @SerialName("invalid-graph")
    INVALID_GRAPH,

    @SerialName("internal")
    INTERNAL,
}

@Serializable
data class EngineError(
    val code: EngineErrorCode,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)
