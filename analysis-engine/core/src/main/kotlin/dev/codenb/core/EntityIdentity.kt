package dev.codenb.core

import dev.codenb.api.Node
import dev.codenb.api.NodeId
import dev.codenb.api.NodeKind

object EntityIdentity {
    fun id(descriptor: EntityDescriptor): NodeId = buildId(descriptor)

    fun node(descriptor: EntityDescriptor): Node =
        Node(
            id = buildId(descriptor),
            kind = NodeKind.ENTITY,
            label = descriptor.label,
            qualifiedName = descriptor.qualifiedName,
            fileUri = descriptor.fileUri,
            signatureFingerprint = descriptor.signatureFingerprint,
            span = descriptor.span,
            parent = null,
            unstable = descriptor.unstable,
        )

    fun nodes(descriptors: List<EntityDescriptor>): List<Node> {
        val baseIds = descriptors.map(::buildId)
        val counts = baseIds.groupingBy { it }.eachCount()
        val used = baseIds.filterIndexed { index, id -> counts.getValue(id) == 1 }.toMutableSet()
        return descriptors.mapIndexed { index, descriptor ->
            val baseId = baseIds[index]
            if (counts.getValue(baseId) == 1) {
                return@mapIndexed node(descriptor)
            }
            var candidate = descriptor.copy(unstable = true)
            var candidateId = baseId
            var suffix = 0
            while (candidateId in used) {
                candidate = descriptor.copy(signatureFingerprint = "${descriptor.signatureFingerprint}#$suffix", unstable = true)
                candidateId = buildId(candidate)
                suffix++
            }
            used.add(candidateId)
            node(candidate)
        }
    }

    private fun buildId(descriptor: EntityDescriptor): NodeId =
        NodeId(
            listOf(
                descriptor.graphId.value,
                descriptor.fileUri,
                descriptor.qualifiedName,
                descriptor.symbolKind.name,
                descriptor.signatureFingerprint,
            ).joinToString("/"),
        )
}
