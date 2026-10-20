package dev.codenb.server

import dev.codenb.api.GroupingKind
import dev.codenb.frontend.KotlinFrontend
import dev.codenb.frontend.LanguageFrontend
import dev.codenb.frontend.toGroupingKind
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

data class LspConfig(
    val id: String,
    val command: List<String>,
    val rootMarkers: List<String>,
)

data class WorkspaceConfig(
    val include: List<String>,
    val exclude: List<String>,
)

data class GraphConfig(
    val grouping: GroupingKind,
    val followSymlinks: Boolean = false,
    val cacheDir: String? = null,
)

data class EngineConfig(
    val lsp: LspConfig,
    val workspace: WorkspaceConfig,
    val graph: GraphConfig,
)

class ConfigException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

object EngineConfigLoader {
    fun defaults(frontend: LanguageFrontend = KotlinFrontend.descriptor): EngineConfig =
        EngineConfig(
            lsp =
                LspConfig(
                    id = frontend.id,
                    command = frontend.defaultCommand,
                    rootMarkers = frontend.rootMarkers,
                ),
            workspace =
                WorkspaceConfig(
                    include = frontend.include,
                    exclude = frontend.exclude,
                ),
            graph = GraphConfig(grouping = frontend.grouping.toGroupingKind()),
        )

    fun load(
        path: Path,
        frontend: LanguageFrontend = KotlinFrontend.descriptor,
    ): EngineConfig {
        if (!Files.isRegularFile(path)) {
            throw ConfigException("Engine config not found: $path")
        }
        val document =
            try {
                Files.newInputStream(path).use { stream -> Yaml().load<Any?>(stream) }
            } catch (exception: YAMLException) {
                throw ConfigException("Invalid engine config $path: ${exception.message}", exception)
            } catch (exception: IOException) {
                throw ConfigException("Cannot read engine config $path: ${exception.message}", exception)
            }
        if (document == null) {
            return defaults(frontend)
        }
        val root =
            document as? Map<*, *>
                ?: throw ConfigException("Engine config must be a mapping: $path")
        val base = defaults(frontend)
        rejectUnknown(root, "config", setOf("lsp", "workspace", "graph"))
        val lsp = mapping(root, "lsp")
        val workspace = mapping(root, "workspace")
        val graph = mapping(root, "graph")
        rejectUnknown(lsp, "lsp", setOf("id", "command", "rootMarkers"))
        rejectUnknown(workspace, "workspace", setOf("include", "exclude"))
        rejectUnknown(graph, "graph", setOf("grouping", "followSymlinks", "cacheDir"))
        val lspId = string(lsp, "id", base.lsp.id)
        if (lspId.isBlank()) {
            throw ConfigException("lsp.id must not be blank")
        }
        val command = stringList(lsp, "command", base.lsp.command)
        if (command.isEmpty()) {
            throw ConfigException("lsp.command must contain at least one argument")
        }
        return EngineConfig(
            lsp =
                LspConfig(
                    id = lspId,
                    command = command,
                    rootMarkers = stringList(lsp, "rootMarkers", base.lsp.rootMarkers),
                ),
            workspace =
                WorkspaceConfig(
                    include = stringList(workspace, "include", base.workspace.include),
                    exclude = stringList(workspace, "exclude", base.workspace.exclude),
                ),
            graph =
                GraphConfig(
                    grouping = grouping(graph, base.graph.grouping),
                    followSymlinks = boolean(graph, "followSymlinks", base.graph.followSymlinks),
                    cacheDir = optionalString(graph, "cacheDir", base.graph.cacheDir),
                ),
        )
    }

    private fun mapping(
        parent: Map<*, *>,
        name: String,
    ): Map<*, *> {
        val value = parent[name] ?: return emptyMap<Any, Any>()
        return value as? Map<*, *> ?: throw ConfigException("$name must be a mapping")
    }

    private fun rejectUnknown(
        parent: Map<*, *>,
        section: String,
        allowed: Set<String>,
    ) {
        val unknown =
            parent.keys
                .map { key -> key?.toString().orEmpty() }
                .filter { key -> key !in allowed }
                .sorted()
        if (unknown.isNotEmpty()) {
            throw ConfigException("Unknown $section key(s): ${unknown.joinToString(", ")}")
        }
    }

    private fun string(
        parent: Map<*, *>,
        name: String,
        default: String,
    ): String {
        val value = parent[name] ?: return default
        return value as? String ?: throw ConfigException("$name must be a string")
    }

    private fun optionalString(
        parent: Map<*, *>,
        name: String,
        default: String?,
    ): String? {
        if (!parent.containsKey(name)) {
            return default
        }
        val value = parent[name] ?: return null
        return value as? String ?: throw ConfigException("$name must be a string or null")
    }

    private fun stringList(
        parent: Map<*, *>,
        name: String,
        default: List<String>,
    ): List<String> {
        val value = parent[name] ?: return default
        val list = value as? List<*> ?: throw ConfigException("$name must be a list of strings")
        return list.map { entry ->
            entry as? String ?: throw ConfigException("$name must be a list of strings")
        }
    }

    private fun boolean(
        parent: Map<*, *>,
        name: String,
        default: Boolean,
    ): Boolean {
        val value = parent[name] ?: return default
        return value as? Boolean ?: throw ConfigException("$name must be a boolean")
    }

    private fun grouping(
        parent: Map<*, *>,
        default: GroupingKind,
    ): GroupingKind {
        val value = parent["grouping"] ?: return default
        val text = value as? String ?: throw ConfigException("graph.grouping must be a string")
        return when (text.trim().lowercase()) {
            "namespace" -> GroupingKind.NAMESPACE
            "directory" -> GroupingKind.DIRECTORY
            else -> throw ConfigException("graph.grouping must be 'namespace' or 'directory', not '$text'")
        }
    }
}
