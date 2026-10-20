package dev.codenb.frontend

import dev.codenb.api.GroupingKind
import dev.codenb.core.FileLocation
import java.net.URI
import java.nio.file.Path

fun interface NamespaceProvider {
    fun namespace(source: String): String
}

internal object WorkspacePaths {
    fun path(
        workspaceRoot: Path,
        fileUri: String,
    ): Path {
        val parsed =
            runCatching { URI(fileUri) }.getOrNull()
                ?: throw IllegalArgumentException("File URI is not valid: $fileUri")
        if (!"file".equals(parsed.scheme, ignoreCase = true)) {
            throw IllegalArgumentException("File URI must use the file scheme: $fileUri")
        }
        val resolved =
            runCatching { Path.of(parsed) }.getOrNull()
                ?: throw IllegalArgumentException("File URI is not a local path: $fileUri")
        val root = workspaceRoot.toAbsolutePath().normalize()
        val normalized = resolved.toAbsolutePath().normalize()
        if (!normalized.startsWith(root)) {
            throw IllegalArgumentException("File URI is outside the workspace root: $fileUri")
        }
        return normalized
    }

    fun relativePath(
        workspaceRoot: Path,
        path: Path,
    ): String {
        val root = workspaceRoot.toAbsolutePath().normalize()
        val relative = root.relativize(path).toString().replace('\\', '/')
        require(relative.isNotBlank()) { "File URI must point to a file inside the workspace root" }
        return relative
    }
}

object KotlinContainmentProvider : NamespaceProvider {
    override fun namespace(source: String): String = KotlinSourceText.packageName(source)

    fun location(
        workspaceRoot: Path,
        fileUri: String,
        source: String,
    ): FileLocation {
        val path = path(workspaceRoot, fileUri)
        return FileLocation(
            fileUri = fileUri,
            relativePath = WorkspacePaths.relativePath(workspaceRoot, path),
            namespace = namespace(source),
        )
    }

    fun relativePath(
        workspaceRoot: Path,
        fileUri: String,
    ): String = WorkspacePaths.relativePath(workspaceRoot, path(workspaceRoot, fileUri))

    fun groupingKind(mode: GroupingMode): GroupingKind = mode.toGroupingKind()

    fun path(
        workspaceRoot: Path,
        fileUri: String,
    ): Path = WorkspacePaths.path(workspaceRoot, fileUri)
}

fun GroupingMode.toGroupingKind(): GroupingKind =
    when (this) {
        GroupingMode.NAMESPACE -> GroupingKind.NAMESPACE
        GroupingMode.DIRECTORY -> GroupingKind.DIRECTORY
    }
