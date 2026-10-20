package dev.codenb.api

import kotlinx.serialization.Serializable

@Serializable
data class Node(
    val id: NodeId,
    val kind: NodeKind,
    val label: String,
    val qualifiedName: String? = null,
    val fileUri: String? = null,
    val signatureFingerprint: String? = null,
    val span: SourceSpan? = null,
    val parent: NodeId? = null,
    val unstable: Boolean = false,
) {
    init {
        require(parent != id) { "Node must not be its own parent" }
        when (kind) {
            NodeKind.ENTITY -> {
                require(fileUri != null) { "Entity nodes require a file URI" }
                require(qualifiedName != null) { "Entity nodes require a qualified name" }
                require(signatureFingerprint != null) { "Entity nodes require a signature fingerprint" }
            }
            NodeKind.FILE -> require(fileUri != null) { "File nodes require a file URI" }
            NodeKind.GROUP -> Unit
        }
    }
}
