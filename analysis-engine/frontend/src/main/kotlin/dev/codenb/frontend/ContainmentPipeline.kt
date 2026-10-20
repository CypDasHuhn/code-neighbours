package dev.codenb.frontend

import dev.codenb.api.Expansion
import dev.codenb.api.GroupingKind
import dev.codenb.api.NodeKind
import dev.codenb.core.ContainmentBuilder
import dev.codenb.core.FileLocation
import dev.codenb.core.GroupingPolicy
import java.nio.file.Path

object ContainmentPipeline {
    fun apply(
        expansionWithFiles: ExpansionWithFiles,
        workspaceRoot: Path,
        groupingKind: GroupingKind,
        policy: GroupingPolicy,
        namespaceProvider: NamespaceProvider,
    ): Expansion {
        policy.bind(expansionWithFiles.expansion.graphId, groupingKind)
        return ContainmentBuilder.apply(
            expansionWithFiles.expansion,
            groupingKind,
            locations(expansionWithFiles, workspaceRoot, groupingKind, namespaceProvider),
            policy,
        )
    }

    fun apply(
        expansionWithFiles: ExpansionWithFiles,
        workspaceRoot: Path,
        groupingMode: GroupingMode,
        policy: GroupingPolicy,
        namespaceProvider: NamespaceProvider,
    ): Expansion = apply(expansionWithFiles, workspaceRoot, groupingMode.toGroupingKind(), policy, namespaceProvider)

    fun apply(
        expansionWithFiles: ExpansionWithFiles,
        groupingKind: GroupingKind,
        policy: GroupingPolicy,
        namespaceProvider: NamespaceProvider,
        workspaceRoot: Path,
    ): Expansion = apply(expansionWithFiles, workspaceRoot, groupingKind, policy, namespaceProvider)

    fun apply(
        expansionWithFiles: ExpansionWithFiles,
        groupingMode: GroupingMode,
        policy: GroupingPolicy,
        namespaceProvider: NamespaceProvider,
        workspaceRoot: Path,
    ): Expansion = apply(expansionWithFiles, workspaceRoot, groupingMode.toGroupingKind(), policy, namespaceProvider)

    fun locations(
        expansionWithFiles: ExpansionWithFiles,
        workspaceRoot: Path,
        groupingKind: GroupingKind,
        namespaceProvider: NamespaceProvider,
    ): List<FileLocation> =
        (sequenceOf(expansionWithFiles.expansion.node) + expansionWithFiles.expansion.neighbours.asSequence())
            .filter { it.kind == NodeKind.ENTITY }
            .mapNotNull { it.fileUri }
            .distinct()
            .sorted()
            .map { fileUri ->
                val path = WorkspacePaths.path(workspaceRoot, fileUri)
                val relativePath = WorkspacePaths.relativePath(workspaceRoot, path)
                val namespace =
                    if (groupingKind == GroupingKind.NAMESPACE) {
                        val text =
                            expansionWithFiles.fileTexts[fileUri]
                                ?: throw IllegalArgumentException("Missing document text for $fileUri")
                        namespaceProvider.namespace(text)
                    } else {
                        ""
                    }
                FileLocation(
                    fileUri = fileUri,
                    relativePath = relativePath,
                    namespace = namespace,
                )
            }.toList()
}
