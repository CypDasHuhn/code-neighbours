package dev.codenb.core

import dev.codenb.api.GraphId
import dev.codenb.api.SourceSpan

data class EntityDescriptor(
    val graphId: GraphId,
    val fileUri: String,
    val label: String,
    val qualifiedName: String,
    val symbolKind: EntitySymbolKind,
    val signatureFingerprint: String,
    val span: SourceSpan? = null,
    val unstable: Boolean = false,
) {
    init {
        require(fileUri.isNotBlank()) { "Entity file URI must not be blank" }
        require(label.isNotBlank()) { "Entity label must not be blank" }
        require(qualifiedName.isNotBlank()) { "Entity qualified name must not be blank" }
        require(signatureFingerprint.isNotBlank()) { "Entity signature fingerprint must not be blank" }
    }
}
