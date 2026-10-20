package dev.codenb.lsp

import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.CancelParams
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.launch.LSPLauncher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LspClientTest {
    @Test
    fun initializesNegotiatesCapabilitiesAndQueriesEveryMethod() {
        val root = Files.createTempDirectory("lsp-client")
        val peer = FakePeer(FakeServerOptions(publishDiagnosticsOnOpen = true))
        try {
            val result = peer.client.initialize(root)
            val params = assertNotNull(peer.server.initializeParams)
            assertEquals(
                root
                    .toAbsolutePath()
                    .normalize()
                    .toUri()
                    .toString(),
                params.rootUri,
            )
            assertEquals(1, params.workspaceFolders.size)
            assertEquals(true, params.capabilities.workspace.workspaceFolders)
            assertNotNull(params.capabilities.workspace.symbol)
            assertNotNull(params.capabilities.textDocument.documentSymbol)
            assertEquals(true, params.capabilities.textDocument.documentSymbol.hierarchicalDocumentSymbolSupport)
            assertNotNull(params.capabilities.textDocument.references)
            assertNotNull(params.capabilities.textDocument.definition)
            assertNotNull(params.capabilities.textDocument.semanticTokens)
            assertEquals(false, params.capabilities.textDocument.semanticTokens.serverCancelSupport)
            assertNotNull(result.capabilities.documentSymbolProvider)
            assertTrue(peer.client.supportsSemanticTokensFull())
            assertEquals(peer.server.legend.tokenTypes, peer.client.semanticTokensLegend?.tokenTypes)
            assertEquals(peer.server.legend.tokenModifiers, peer.client.semanticTokensLegend?.tokenModifiers)
            eventually { peer.server.initializedCalled }

            val uri = root.resolve("Thing.kt").toUri().toString()
            peer.client.didOpen(uri, "kotlin", "class Thing")
            eventually { peer.server.documentVersion(uri) == 1 }
            eventually { peer.client.diagnostics(uri).size == 1 }

            val symbols = peer.client.documentSymbol(uri)
            assertEquals("Thing", symbols.single().right.name)
            assertEquals(uri, assertNotNull(peer.server.lastDocumentSymbol).textDocument.uri)

            val references = peer.client.references(uri, Position(3, 4), includeDeclaration = false)
            assertEquals(1, references.size)
            assertEquals(uri, references.single().uri)
            val referenceParams = assertNotNull(peer.server.lastReferences)
            assertEquals(uri, referenceParams.textDocument.uri)
            assertEquals(3, referenceParams.position.line)
            assertEquals(4, referenceParams.position.character)
            assertEquals(false, referenceParams.context.isIncludeDeclaration)

            val definition = peer.client.definition(uri, Position(5, 6))
            assertEquals(1, definition.locations.size)
            assertTrue(definition.locationLinks.isEmpty())
            val definitionParams = assertNotNull(peer.server.lastDefinition)
            assertEquals(uri, definitionParams.textDocument.uri)
            assertEquals(5, definitionParams.position.line)
            assertEquals(6, definitionParams.position.character)

            val tokens = assertNotNull(peer.client.semanticTokensFull(uri))
            assertEquals(10, tokens.data.size)
            assertEquals(uri, assertNotNull(peer.server.lastSemanticTokens).textDocument.uri)

            val workspaceSymbols = peer.client.workspaceSymbol("Thing")
            assertEquals("Thing", workspaceSymbols.workspaceSymbols.single().name)
            assertTrue(workspaceSymbols.symbols.isEmpty())
            assertEquals("Thing", assertNotNull(peer.server.lastWorkspaceSymbol).query)
        } finally {
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun duplicateInitializeDoesNotCloseAnInitializedClient() {
        val root = Files.createTempDirectory("lsp-duplicate-initialize")
        val peer = FakePeer()
        try {
            peer.client.initialize(root)
            assertFailsWith<IllegalStateException> {
                peer.client.initialize(root)
            }
            assertEquals(
                "Thing",
                peer.client
                    .workspaceSymbol("Thing")
                    .workspaceSymbols
                    .single()
                    .name,
            )
            val uri = root.resolve("StillOpen.kt").toUri().toString()
            peer.client.didOpen(uri, "kotlin", "class StillOpen")
            eventually { peer.server.documentVersion(uri) == 1 }
        } finally {
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentInitializeDoesNotAbortTheWinningInitialization() {
        val root = Files.createTempDirectory("lsp-concurrent-initialize-duplicate")
        val blocker = CountDownLatch(1)
        val peer = FakePeer(FakeServerOptions(initializeBlocker = blocker))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val winner = executor.submit<InitializeResult> { peer.client.initialize(root) }
            eventually { peer.server.initializeParams != null }
            assertFailsWith<IllegalStateException> {
                peer.client.initialize(root)
            }
            blocker.countDown()
            assertNotNull(winner.get(5, TimeUnit.SECONDS))
            assertEquals(
                "Thing",
                peer.client
                    .workspaceSymbol("Thing")
                    .workspaceSymbols
                    .single()
                    .name,
            )
        } finally {
            blocker.countDown()
            executor.shutdownNow()
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun tracksOpenedDocumentVersionsAndText() {
        val root = Files.createTempDirectory("lsp-documents")
        val peer = FakePeer()
        try {
            peer.client.initialize(root)
            val uri = root.resolve("Versioned.kt").toUri().toString()

            peer.client.didOpen(uri, "kotlin", "class Versioned", version = 7)
            eventually { peer.server.documentVersion(uri) == 7 }
            assertEquals(7, peer.client.documentVersion(uri))
            assertEquals("class Versioned", peer.client.openDocument(uri)?.text)

            peer.client.didChange(uri, "class VersionedChanged")
            eventually { peer.server.documentVersion(uri) == 8 }
            eventually { peer.server.documentText(uri) == "class VersionedChanged" }
            assertEquals(8, peer.client.documentVersion(uri))
            assertEquals("class VersionedChanged", peer.client.openDocument(uri)?.text)
            assertEquals(listOf(7, 8), peer.server.documentVersions(uri))

            peer.client.didClose(uri)
            eventually { peer.server.isClosed(uri) }
            assertNull(peer.client.openDocument(uri))
            assertTrue(peer.client.openDocuments().isEmpty())
        } finally {
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun timesOutRequestsAndRemainsClosable() {
        val root = Files.createTempDirectory("lsp-timeout")
        val peer = FakePeer(FakeServerOptions(ignoreDocumentSymbol = true), Duration.ofMillis(150))
        try {
            peer.client.initialize(root)
            val uri = root.resolve("Timeout.kt").toUri().toString()
            val started = System.nanoTime()
            assertFailsWith<LspTimeoutException> {
                peer.client.documentSymbol(uri)
            }
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertTrue(elapsed < Duration.ofSeconds(2), "timeout took $elapsed")
            assertEquals(
                "Thing",
                peer.client
                    .workspaceSymbol("Thing")
                    .workspaceSymbols
                    .single()
                    .name,
            )
        } finally {
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsSemanticTokensWhenServerDoesNotSupportThem() {
        val root = Files.createTempDirectory("lsp-capability")
        val peer = FakePeer(FakeServerOptions(semanticTokens = false))
        try {
            peer.client.initialize(root)
            val uri = root.resolve("NoTokens.kt").toUri().toString()
            assertFalse(peer.client.supportsSemanticTokensFull())
            assertFailsWith<LspUnsupportedCapabilityException> {
                peer.client.semanticTokensFull(uri)
            }
        } finally {
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun normalizesNullResultsForEveryQuery() {
        val root = Files.createTempDirectory("lsp-null-results")
        val peer = FakePeer(FakeServerOptions(nullResults = FakeResult.entries.toSet()))
        try {
            peer.client.initialize(root)
            val uri = root.resolve("Empty.kt").toUri().toString()

            assertTrue(peer.client.documentSymbol(uri).isEmpty())
            assertTrue(peer.client.references(uri, Position(0, 0)).isEmpty())
            val definition = peer.client.definition(uri, Position(0, 0))
            assertTrue(definition.locations.isEmpty())
            assertTrue(definition.locationLinks.isEmpty())
            assertNull(peer.client.semanticTokensFull(uri))
            val symbols = peer.client.workspaceSymbol("Missing")
            assertTrue(symbols.symbols.isEmpty())
            assertTrue(symbols.workspaceSymbols.isEmpty())
        } finally {
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun mapsLspErrorResponsesToTypedExceptions() {
        val root = Files.createTempDirectory("lsp-error")
        val peer = FakePeer(FakeServerOptions(errorResults = setOf(FakeResult.DOCUMENT_SYMBOL)))
        try {
            peer.client.initialize(root)
            val uri = root.resolve("Error.kt").toUri().toString()
            val exception =
                assertFailsWith<LspResponseException> {
                    peer.client.documentSymbol(uri)
                }
            assertTrue(assertNotNull(exception.message).contains("fake documentSymbol error"))
            assertEquals(
                "Thing",
                peer.client
                    .workspaceSymbol("Thing")
                    .workspaceSymbols
                    .single()
                    .name,
            )
        } finally {
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsQueriesAfterShutdown() {
        val root = Files.createTempDirectory("lsp-shutdown")
        val peer = FakePeer()
        try {
            peer.client.initialize(root)
            val uri = root.resolve("Shutdown.kt").toUri().toString()
            peer.client.shutdown()
            assertFailsWith<IllegalStateException> { peer.client.documentSymbol(uri) }
            assertFailsWith<IllegalStateException> { peer.client.references(uri, Position(0, 0)) }
            assertFailsWith<IllegalStateException> { peer.client.didOpen(uri, "kotlin", "class Shutdown") }
            peer.client.exit()
        } finally {
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun requiresShutdownBeforeExit() {
        val root = Files.createTempDirectory("lsp-exit")
        val peer = FakePeer()
        try {
            peer.client.initialize(root)
            assertFailsWith<IllegalStateException> { peer.client.exit() }
        } finally {
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun closeIsIdempotentUnderConcurrency() {
        val root = Files.createTempDirectory("lsp-concurrent-close")
        val peer = FakePeer()
        try {
            peer.client.initialize(root)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(8)
            try {
                val tasks =
                    (1..8).map {
                        executor.submit {
                            start.await()
                            peer.client.close()
                        }
                    }
                start.countDown()
                tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
            assertEquals(1, peer.server.shutdownCount())
            eventually { peer.server.exitCount() == 1 }
            assertTrue(peer.input.closed)
            assertTrue(peer.output.closed)
        } finally {
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun closeClosesOpenDocumentsAndClearsDocumentState() {
        val root = Files.createTempDirectory("lsp-close-documents")
        val peer = FakePeer(FakeServerOptions(publishDiagnosticsOnOpen = true))
        try {
            peer.client.initialize(root)
            val first = root.resolve("First.kt").toUri().toString()
            val second = root.resolve("Second.kt").toUri().toString()
            peer.client.didOpen(first, "kotlin", "class First")
            peer.client.didOpen(second, "kotlin", "class Second")
            eventually { peer.client.diagnostics(first).isNotEmpty() }
            eventually { peer.client.diagnostics(second).isNotEmpty() }

            peer.client.close()
            eventually { peer.server.isClosed(first) }
            eventually { peer.server.isClosed(second) }
            assertTrue(peer.client.openDocuments().isEmpty())
            assertTrue(peer.client.allDiagnostics().isEmpty())
            assertTrue(peer.client.diagnostics(first).isEmpty())
        } finally {
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun closeUsesCleanupTimeoutForUnresponsiveShutdown() {
        val root = Files.createTempDirectory("lsp-close-timeout")
        val peer = FakePeer(FakeServerOptions(ignoreShutdown = true), Duration.ofSeconds(5), Duration.ofMillis(200))
        try {
            peer.client.initialize(root)
            val started = System.nanoTime()
            peer.client.close()
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertTrue(elapsed < Duration.ofSeconds(2), "close took $elapsed")
            assertEquals(1, peer.server.shutdownCount())
            eventually { peer.server.exitCount() == 1 }
        } finally {
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun serializesConcurrentDocumentChanges() {
        val root = Files.createTempDirectory("lsp-concurrent-documents")
        val peer = FakePeer()
        try {
            peer.client.initialize(root)
            val uri = root.resolve("Concurrent.kt").toUri().toString()
            peer.client.didOpen(uri, "kotlin", "class Concurrent")
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(8)
            try {
                val tasks =
                    (1..20).map { index ->
                        executor.submit {
                            start.await()
                            peer.client.didChange(uri, "text-$index")
                        }
                    }
                start.countDown()
                tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
            eventually { peer.server.documentVersions(uri).size == 21 }
            val versions = peer.server.documentVersions(uri)
            assertEquals((1..21).toList(), versions)
            assertEquals(21, peer.client.documentVersion(uri))
            eventually { peer.server.documentText(uri) == peer.client.openDocument(uri)?.text }
        } finally {
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentInitializeAndClosePreserveOrdering() {
        val root = Files.createTempDirectory("lsp-concurrent-initialize")
        val peer = FakePeer(blockOnMethod = "\"method\":\"initialized\"")
        val executor = Executors.newFixedThreadPool(2)
        try {
            val initializeFuture = executor.submit<InitializeResult> { peer.client.initialize(root) }
            val blocker = assertNotNull(peer.blockingOutput)
            assertTrue(blocker.blocked.await(2, TimeUnit.SECONDS))
            val closeFuture = executor.submit { peer.client.close() }
            Thread.sleep(100)
            assertFalse(closeFuture.isDone)
            blocker.release.countDown()
            assertNotNull(initializeFuture.get(5, TimeUnit.SECONDS))
            closeFuture.get(5, TimeUnit.SECONDS)
            eventually { peer.server.exitCount() == 1 }
            val calls = peer.server.calls()
            assertTrue(calls.indexOf("initialize") < calls.indexOf("initialized"))
            assertTrue(calls.indexOf("initialized") < calls.indexOf("shutdown"))
            assertTrue(calls.indexOf("shutdown") < calls.indexOf("exit"))
        } finally {
            peer.blockingOutput?.release?.countDown()
            executor.shutdownNow()
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentShutdownAndExitPreserveOrdering() {
        val root = Files.createTempDirectory("lsp-concurrent-shutdown")
        val peer = FakePeer(blockOnMethod = "\"method\":\"shutdown\"")
        val executor = Executors.newFixedThreadPool(2)
        try {
            peer.client.initialize(root)
            val shutdownFuture = executor.submit { peer.client.shutdown() }
            val blocker = assertNotNull(peer.blockingOutput)
            assertTrue(blocker.blocked.await(2, TimeUnit.SECONDS))
            val exitFuture = executor.submit { peer.client.exit() }
            Thread.sleep(100)
            assertFalse(exitFuture.isDone)
            blocker.release.countDown()
            shutdownFuture.get(5, TimeUnit.SECONDS)
            exitFuture.get(5, TimeUnit.SECONDS)
            eventually { peer.server.exitCount() == 1 }
            val calls = peer.server.calls()
            assertTrue(calls.indexOf("shutdown") < calls.indexOf("exit"))
        } finally {
            peer.blockingOutput?.release?.countDown()
            executor.shutdownNow()
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun documentMutationCannotCompleteAfterCloseClaimsClient() {
        val root = Files.createTempDirectory("lsp-mutation-close")
        val peer = FakePeer(blockOnMethod = "\"method\":\"textDocument/didOpen\"")
        val executor = Executors.newFixedThreadPool(2)
        try {
            peer.client.initialize(root)
            val uri = root.resolve("Race.kt").toUri().toString()
            val mutationFuture = executor.submit { peer.client.didOpen(uri, "kotlin", "class Race") }
            val blocker = assertNotNull(peer.blockingOutput)
            assertTrue(blocker.blocked.await(2, TimeUnit.SECONDS))
            val closeFuture = executor.submit { peer.client.close() }
            Thread.sleep(100)
            assertFalse(closeFuture.isDone)
            blocker.release.countDown()
            mutationFuture.get(5, TimeUnit.SECONDS)
            closeFuture.get(5, TimeUnit.SECONDS)
            assertTrue(peer.client.openDocuments().isEmpty())
            eventually { peer.server.isClosed(uri) }
            val calls = peer.server.calls()
            assertTrue(calls.indexOf("didOpen") < calls.indexOf("didClose"))
            assertTrue(calls.indexOf("didClose") < calls.indexOf("shutdown"))
        } finally {
            peer.blockingOutput?.release?.countDown()
            executor.shutdownNow()
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun shutdownClosesOpenDocumentsBeforeClose() {
        val root = Files.createTempDirectory("lsp-shutdown-documents")
        val peer = FakePeer()
        try {
            peer.client.initialize(root)
            val uri = root.resolve("ShutdownOpen.kt").toUri().toString()
            peer.client.didOpen(uri, "kotlin", "class ShutdownOpen")
            eventually { peer.server.documentVersion(uri) == 1 }

            peer.client.shutdown()
            eventually { peer.server.isClosed(uri) }
            assertTrue(peer.client.openDocuments().isEmpty())
            val calls = peer.server.calls()
            assertTrue(calls.indexOf("didClose") < calls.indexOf("shutdown"))

            peer.client.close()
            eventually { peer.server.exitCount() == 1 }
        } finally {
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun concurrentQueriesHaveIndependentIdsAndCancelOnlyTimedOutRequest() {
        val root = Files.createTempDirectory("lsp-concurrent-queries")
        val peer = FakePeer(FakeServerOptions(ignoreDocumentSymbol = true), Duration.ofMillis(300))
        val executor = Executors.newFixedThreadPool(2)
        try {
            peer.client.initialize(root)
            val uri = root.resolve("Queries.kt").toUri().toString()
            val start = CountDownLatch(1)
            val delayed =
                executor.submit<LspTimeoutException> {
                    start.await()
                    assertFailsWith<LspTimeoutException> {
                        peer.client.documentSymbol(uri)
                    }
                }
            val quick =
                executor.submit<WorkspaceSymbolResult> {
                    start.await()
                    peer.client.workspaceSymbol("Thing")
                }
            start.countDown()
            assertEquals(
                "Thing",
                quick
                    .get(2, TimeUnit.SECONDS)
                    .workspaceSymbols
                    .single()
                    .name,
            )
            assertEquals("textDocument/documentSymbol", delayed.get(2, TimeUnit.SECONDS).method)
            eventually { peer.server.cancellations().isNotEmpty() }

            val requests =
                peer.server.requests().filter {
                    it.second == "textDocument/documentSymbol" || it.second == "workspace/symbol"
                }
            assertEquals(2, requests.size)
            assertEquals(2, requests.map { it.first }.toSet().size)
            val documentRequest = requests.first { it.second == "textDocument/documentSymbol" }
            val workspaceRequest = requests.first { it.second == "workspace/symbol" }
            assertTrue(documentRequest.first != workspaceRequest.first)
            assertTrue(documentRequest.first in peer.server.cancellations())
        } finally {
            executor.shutdownNow()
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun closeIsBoundedWhenAQueryHoldsTheGate() {
        val root = Files.createTempDirectory("lsp-bounded-close")
        val peer = FakePeer(FakeServerOptions(ignoreDocumentSymbol = true), Duration.ofSeconds(5), Duration.ofMillis(200))
        val executor = Executors.newSingleThreadExecutor()
        try {
            peer.client.initialize(root)
            val uri = root.resolve("Pending.kt").toUri().toString()
            val queryFuture =
                executor.submit<Throwable?> {
                    try {
                        peer.client.documentSymbol(uri)
                        null
                    } catch (exception: Throwable) {
                        exception
                    }
                }
            eventually { peer.server.lastDocumentSymbol != null }
            val started = System.nanoTime()
            peer.client.close()
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertTrue(elapsed < Duration.ofSeconds(2), "bounded close took $elapsed")
            val queryFailure = queryFuture.get(2, TimeUnit.SECONDS)
            assertTrue(queryFailure is LspTransportException, "unexpected query failure: $queryFailure")
            assertFailsWith<IllegalStateException> { peer.client.workspaceSymbol("Thing") }
        } finally {
            executor.shutdownNow()
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun ignoresDiagnosticsPublishedDuringClose() {
        val root = Files.createTempDirectory("lsp-close-diagnostics")
        val peer = FakePeer(FakeServerOptions(publishDiagnosticsOnShutdown = true))
        try {
            peer.client.initialize(root)
            peer.client.close()
            Thread.sleep(100)
            assertTrue(peer.client.allDiagnostics().isEmpty())
            assertTrue(peer.client.diagnostics("file:///fake/close.kt").isEmpty())
        } finally {
            peer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun malformedResponsesFailAsTransportExceptions() {
        val root = Files.createTempDirectory("lsp-malformed")
        val client =
            LspClient.connect(
                ByteArrayInputStream("Content-Length: 4\r\n\r\nnope".toByteArray()),
                ByteArrayOutputStream(),
                Duration.ofSeconds(5),
            )
        val logger = Logger.getLogger("org.eclipse.lsp4j.jsonrpc.json.StreamMessageProducer")
        val previousLevel = logger.level
        logger.level = Level.OFF
        try {
            assertFailsWith<LspTransportException> {
                client.initialize(root)
            }
        } finally {
            logger.level = previousLevel
            client.close()
            root.toFile().deleteRecursively()
        }
    }

    private class FakePeer(
        options: FakeServerOptions = FakeServerOptions(),
        requestTimeout: Duration = Duration.ofSeconds(2),
        cleanupTimeout: Duration = Duration.ofSeconds(1),
        blockOnMethod: String? = null,
    ) : AutoCloseable {
        val server = FakeLspServer(options)
        val input: TrackingInputStream
        val output: TrackingOutputStream
        val blockingOutput: BlockingOutputStream?
        val client: LspClient
        private val clientToServerInput: InputStream
        private val serverToClientOutput: OutputStream
        private val serverListening: Future<*>
        private val serverExecutor =
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "fake-lsp-server").apply { isDaemon = true }
            }

        init {
            val clientToServer = Pipe.open()
            val serverToClient = Pipe.open()
            clientToServerInput = Channels.newInputStream(clientToServer.source())
            val clientToServerOutput = Channels.newOutputStream(clientToServer.sink())
            serverToClientOutput = Channels.newOutputStream(serverToClient.sink())
            val serverToClientInput = Channels.newInputStream(serverToClient.source())
            blockingOutput = blockOnMethod?.let { BlockingOutputStream(clientToServerOutput, it) }
            val launcher =
                LSPLauncher.createServerLauncher(
                    server,
                    clientToServerInput,
                    serverToClientOutput,
                    serverExecutor,
                ) { consumer ->
                    MessageConsumer { message ->
                        when (message) {
                            is RequestMessage -> server.recordRequest(message.id, message.method)
                            is NotificationMessage -> {
                                if (message.method == "$/cancelRequest") {
                                    val params = message.params
                                    if (params is CancelParams) {
                                        server.recordCancellation(params.id)
                                    }
                                }
                            }
                        }
                        consumer.consume(message)
                    }
                }
            server.connect(launcher.remoteProxy)
            serverListening = launcher.startListening()
            input = TrackingInputStream(serverToClientInput)
            output = TrackingOutputStream(blockingOutput ?: clientToServerOutput)
            client = LspClient.connect(input, output, requestTimeout, cleanupTimeout)
        }

        override fun close() {
            runCatching { client.close() }
            runCatching { serverListening.get(1, TimeUnit.SECONDS) }
            runCatching { clientToServerInput.close() }
            runCatching { serverToClientOutput.close() }
            serverExecutor.shutdownNow()
        }
    }

    private class BlockingOutputStream(
        private val delegate: OutputStream,
        private val marker: String,
    ) : OutputStream() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val blockedOnce = AtomicBoolean(false)

        override fun write(value: Int) {
            delegate.write(value)
        }

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            maybeBlock(buffer.copyOfRange(offset, offset + length))
            delegate.write(buffer, offset, length)
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }

        private fun maybeBlock(bytes: ByteArray) {
            if (!blockedOnce.get() && String(bytes, Charsets.UTF_8).contains(marker)) {
                if (blockedOnce.compareAndSet(false, true)) {
                    blocked.countDown()
                    try {
                        release.await()
                    } catch (exception: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
    }

    private class TrackingInputStream(
        private val delegate: InputStream,
    ) : InputStream() {
        @Volatile
        var closed: Boolean = false
            private set

        override fun read(): Int = delegate.read()

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = delegate.read(buffer, offset, length)

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private class TrackingOutputStream(
        private val delegate: OutputStream,
    ) : OutputStream() {
        @Volatile
        var closed: Boolean = false
            private set

        override fun write(value: Int) {
            delegate.write(value)
        }

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            delegate.write(buffer, offset, length)
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private fun eventually(
        timeout: Duration = Duration.ofSeconds(2),
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition was not met within $timeout")
    }
}
