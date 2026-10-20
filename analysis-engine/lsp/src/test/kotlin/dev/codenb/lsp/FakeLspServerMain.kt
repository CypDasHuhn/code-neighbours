package dev.codenb.lsp

import org.eclipse.lsp4j.launch.LSPLauncher

fun main(args: Array<String>) {
    val options =
        FakeServerOptions(
            exitOnExit = true,
            exitOnDocumentSymbol = args.contains("--exit-on-document-symbol"),
        )
    val server = FakeLspServer(options)
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out)
    server.connect(launcher.remoteProxy)
    launcher.startListening().get()
}
