package dev.codenb.server

import java.nio.file.Path

data class CliOptions(
    val root: Path,
    val lspId: String?,
    val socket: Path?,
    val config: Path?,
    val help: Boolean = false,
)

class CliException(
    message: String,
) : RuntimeException(message)

object CliParser {
    fun parse(
        args: Array<String>,
        workingDirectory: Path,
    ): CliOptions {
        if (args.any { it == "--help" || it == "-h" }) {
            return CliOptions(workingDirectory.toAbsolutePath().normalize(), null, null, null, help = true)
        }
        var rootValue: String? = null
        var lspId: String? = null
        var socketValue: String? = null
        var configValue: String? = null
        var index = 0
        while (index < args.size) {
            val argument = args[index]
            if (!argument.startsWith("--")) {
                throw CliException("Unexpected argument: $argument")
            }
            val separator = argument.indexOf('=')
            val name = if (separator >= 0) argument.substring(0, separator) else argument
            val inline = if (separator >= 0) argument.substring(separator + 1) else null
            if (inline != null && inline.isEmpty()) {
                throw CliException("Missing value for $name")
            }
            val value =
                inline ?: run {
                    if (index + 1 >= args.size || args[index + 1].startsWith("--")) {
                        throw CliException("Missing value for $name")
                    }
                    index++
                    args[index]
                }
            when (name) {
                "--root" -> rootValue = value
                "--lsp" -> lspId = value
                "--socket" -> socketValue = value
                "--config" -> configValue = value
                else -> throw CliException("Unknown argument: $name")
            }
            index++
        }
        val root =
            rootValue
                ?.let { pathValue(it, workingDirectory) }
                ?: workingDirectory.toAbsolutePath().normalize()
        return CliOptions(
            root = root,
            lspId = lspId,
            socket = socketValue?.let { pathValue(it, workingDirectory) },
            config = configValue?.let { pathValue(it, workingDirectory) },
        )
    }

    private fun pathValue(
        value: String,
        workingDirectory: Path,
    ): Path {
        val path = Path.of(value)
        return (if (path.isAbsolute) path else workingDirectory.resolve(path)).toAbsolutePath().normalize()
    }

    fun usage(): String =
        """
        Usage: codenb [options]

        Options:
          --root <path>    Workspace root (default: current directory)
          --lsp <id>       Language frontend id (default: kotlin)
          --socket <path>  Override the derived Unix socket path
          --config <path>  Engine YAML config
          --help           Show this message
        """.trimIndent()
}
