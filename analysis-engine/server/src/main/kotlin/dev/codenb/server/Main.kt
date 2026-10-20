package dev.codenb.server

import dev.codenb.frontend.FrontendRegistry
import dev.codenb.frontend.LanguageFrontend
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val status =
        try {
            run(args)
            0
        } catch (exception: ConfigException) {
            System.err.println("codenb: ${exception.message}")
            1
        } catch (exception: CliException) {
            System.err.println("codenb: ${exception.message}")
            1
        } catch (exception: Exception) {
            System.err.println("codenb: ${exception.message ?: exception::class.simpleName}")
            1
        }
    if (status != 0) {
        exitProcess(status)
    }
}

private fun run(args: Array<String>) {
    val options = CliParser.parse(args, Path.of(System.getProperty("user.dir")))
    if (options.help) {
        println(CliParser.usage())
        return
    }
    if (!Files.isDirectory(options.root)) {
        throw ConfigException("Workspace root is not a directory: ${options.root}")
    }
    val (config, frontend) = loadConfig(options)
    val socketPath = options.socket ?: SocketPaths.derive(options.root, frontend.id)
    val service = RealGraphService(options.root, frontend, config)
    val server = EngineSocketServer(socketPath, service)
    val hook =
        Thread(
            {
                server.close()
                if (!server.awaitTermination(8, TimeUnit.SECONDS)) {
                    val failure = server.closeFailure()
                    System.err.println(
                        "codenb: shutdown did not complete${failure?.let { exception -> ": ${exception.message}" } ?: ""}",
                    )
                }
            },
            "codenb-shutdown",
        )
    Runtime.getRuntime().addShutdownHook(hook)
    try {
        server.run()
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }
}

private fun loadConfig(options: CliOptions): Pair<EngineConfig, LanguageFrontend> {
    val loaded = options.config?.let { EngineConfigLoader.load(it) } ?: EngineConfigLoader.defaults()
    val lspId = options.lspId ?: loaded.lsp.id
    val frontend =
        FrontendRegistry.byId(lspId)
            ?: throw ConfigException(
                "Unknown LSP id '$lspId'; known: ${FrontendRegistry.all.joinToString { it.id }}",
            )
    val config =
        if (loaded.lsp.id == lspId) {
            loaded
        } else {
            loaded.copy(
                lsp = LspConfig(lspId, frontend.defaultCommand, frontend.rootMarkers),
            )
        }
    return config to frontend
}
