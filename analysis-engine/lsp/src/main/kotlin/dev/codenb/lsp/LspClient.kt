package dev.codenb.lsp

import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ClientInfo
import org.eclipse.lsp4j.DefinitionCapabilities
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolCapabilities
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.ReferencesCapabilities
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensCapabilities
import org.eclipse.lsp4j.SemanticTokensClientCapabilitiesRequests
import org.eclipse.lsp4j.SemanticTokensClientCapabilitiesRequestsFull
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokensWorkspaceCapabilities
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.SymbolCapabilities
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKindCapabilities
import org.eclipse.lsp4j.SynchronizationCapabilities
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock

data class OpenDocument(
    val uri: String,
    val languageId: String,
    val version: Int,
    val text: String,
)

data class DefinitionResult(
    val locations: List<Location>,
    val locationLinks: List<LocationLink>,
)

data class WorkspaceSymbolResult(
    val symbols: List<SymbolInformation>,
    val workspaceSymbols: List<WorkspaceSymbol>,
)

class LspClient private constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val process: Process?,
    private val requestTimeout: Duration,
    private val cleanupTimeout: Duration,
) : LanguageClient,
    LspSession,
    AutoCloseable {
    private enum class Lifecycle {
        NEW,
        INITIALIZING,
        INITIALIZED,
        SHUTDOWN,
        CLOSED,
    }

    private val gate = ReentrantReadWriteLock(true)
    private val documents = ConcurrentHashMap<String, OpenDocument>()
    private val documentLocks = ConcurrentHashMap<String, Any>()
    private val diagnosticsByUri = ConcurrentHashMap<String, List<Diagnostic>>()
    private val messages = ConcurrentHashMap<String, MessageParams>()
    private val executorService: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "lsp-client").apply { isDaemon = true }
        }
    private val launcher: Launcher<LanguageServer>
    private val server: LanguageServer
    private val listening: Future<Void>

    @Volatile
    private var lifecycle = Lifecycle.NEW

    private val stateLock = Any()
    private val closeClaimed = AtomicBoolean(false)
    private val exitSent = AtomicBoolean(false)

    private var initializedNotificationSent = false

    @Volatile
    private var serverCapabilities: ServerCapabilities? = null

    @Volatile
    override var semanticTokensLegend: SemanticTokensLegend? = null
        private set

    @Volatile
    private var workspaceFolderList: List<WorkspaceFolder> = emptyList()

    init {
        launcher = LSPLauncher.createClientLauncher(this, input, output, executorService) { consumer -> consumer }
        server = launcher.remoteProxy
        listening = launcher.startListening()
    }

    fun initialize(
        root: Path,
        workspaceFolders: List<Path> = listOf(root),
        initializationOptions: Any? = null,
    ): InitializeResult {
        var transitioned = false
        val params =
            try {
                val write = gate.writeLock()
                write.lock()
                try {
                    check(!closeClaimed.get()) { "LSP client is closing or closed" }
                    check(lifecycle == Lifecycle.NEW) { "LSP client is already initialized" }
                    lifecycle = Lifecycle.INITIALIZING
                    transitioned = true
                    val normalizedRoot = root.toAbsolutePath().normalize()
                    val folders =
                        workspaceFolders.map { folder ->
                            val normalized = folder.toAbsolutePath().normalize()
                            WorkspaceFolder(
                                normalized.toUri().toString(),
                                normalized.fileName?.toString() ?: normalized.toString(),
                            )
                        }
                    workspaceFolderList = folders
                    InitializeParams().apply {
                        processId = ProcessHandle.current().pid().toInt()
                        rootUri = normalizedRoot.toUri().toString()
                        rootPath = normalizedRoot.toString()
                        capabilities = clientCapabilities()
                        this.workspaceFolders = folders
                        clientInfo = ClientInfo("code-neighbours", "0.1.0")
                        this.initializationOptions = initializationOptions
                    }
                } finally {
                    write.unlock()
                }
            } catch (exception: Throwable) {
                if (transitioned) {
                    close()
                }
                throw exception
            }
        val result =
            try {
                await("initialize", server.initialize(params))
                    ?: throw LspTransportException("initialize returned no result")
            } catch (exception: Throwable) {
                close()
                throw exception
            }
        val write = gate.writeLock()
        write.lock()
        try {
            if (closeClaimed.get()) {
                throw LspTransportException("LSP client closed during initialization")
            }
            serverCapabilities = result.capabilities
            semanticTokensLegend = result.capabilities?.semanticTokensProvider?.legend
            server.initialized(InitializedParams())
            synchronized(stateLock) {
                if (closeClaimed.get()) {
                    throw LspTransportException("LSP client closed during initialization")
                }
                initializedNotificationSent = true
                lifecycle = Lifecycle.INITIALIZED
            }
            return result
        } catch (exception: Throwable) {
            close()
            throw exception
        } finally {
            write.unlock()
        }
    }

    fun initialized() {
        val write = gate.writeLock()
        write.lock()
        try {
            requireInitializedLocked()
            if (!initializedNotificationSent) {
                server.initialized(InitializedParams())
                initializedNotificationSent = true
            }
        } finally {
            write.unlock()
        }
    }

    override fun didOpen(
        uri: String,
        languageId: String,
        text: String,
        version: Int,
    ) {
        val read = gate.readLock()
        read.lock()
        try {
            val lock = documentLock(uri)
            synchronized(lock) {
                requireDocumentMutationLocked()
                check(documents[uri] == null) { "Document is already open: $uri" }
                val item = TextDocumentItem(uri, languageId, version, text)
                server.textDocumentService.didOpen(DidOpenTextDocumentParams(item))
                synchronized(stateLock) {
                    check(!closeClaimed.get()) { "LSP client is closing or closed" }
                    documents[uri] = OpenDocument(uri, languageId, version, text)
                }
            }
        } finally {
            read.unlock()
        }
    }

    fun didChange(
        uri: String,
        text: String,
    ) {
        val read = gate.readLock()
        read.lock()
        try {
            val lock = documentLock(uri)
            synchronized(lock) {
                requireDocumentMutationLocked()
                val current = documents[uri] ?: throw IllegalStateException("Document is not open: $uri")
                val version = current.version + 1
                val identifier = VersionedTextDocumentIdentifier(uri, version)
                val params = DidChangeTextDocumentParams(identifier, listOf(TextDocumentContentChangeEvent(text)))
                server.textDocumentService.didChange(params)
                synchronized(stateLock) {
                    check(!closeClaimed.get()) { "LSP client is closing or closed" }
                    documents[uri] = current.copy(version = version, text = text)
                }
            }
        } finally {
            read.unlock()
        }
    }

    override fun didClose(uri: String) {
        val read = gate.readLock()
        read.lock()
        try {
            val lock = documentLock(uri)
            synchronized(lock) {
                requireDocumentMutationLocked()
                synchronized(stateLock) {
                    check(!closeClaimed.get()) { "LSP client is closing or closed" }
                    check(documents.remove(uri) != null) { "Document is not open: $uri" }
                }
                server.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
                synchronized(stateLock) {
                    diagnosticsByUri.remove(uri)
                }
            }
        } finally {
            read.unlock()
        }
    }

    override fun openDocument(uri: String): OpenDocument? = synchronized(stateLock) { documents[uri] }

    fun documentVersion(uri: String): Int? = synchronized(stateLock) { documents[uri]?.version }

    fun openDocuments(): List<OpenDocument> = synchronized(stateLock) { documents.values.sortedBy { it.uri } }

    fun diagnostics(uri: String): List<Diagnostic> = synchronized(stateLock) { diagnosticsByUri[uri] ?: emptyList() }

    fun allDiagnostics(): Map<String, List<Diagnostic>> = synchronized(stateLock) { diagnosticsByUri.toMap() }

    override fun documentSymbol(uri: String): List<Either<SymbolInformation, DocumentSymbol>> {
        val read = gate.readLock()
        read.lock()
        try {
            requireInitializedLocked()
            val params = DocumentSymbolParams(TextDocumentIdentifier(uri))
            return await("textDocument/documentSymbol", server.textDocumentService.documentSymbol(params)) ?: emptyList()
        } finally {
            read.unlock()
        }
    }

    override fun references(
        uri: String,
        position: Position,
        includeDeclaration: Boolean,
    ): List<Location> {
        val read = gate.readLock()
        read.lock()
        try {
            requireInitializedLocked()
            val params = ReferenceParams(TextDocumentIdentifier(uri), position, ReferenceContext(includeDeclaration))
            return await("textDocument/references", server.textDocumentService.references(params))?.toList() ?: emptyList()
        } finally {
            read.unlock()
        }
    }

    override fun definition(
        uri: String,
        position: Position,
    ): DefinitionResult {
        val read = gate.readLock()
        read.lock()
        try {
            requireInitializedLocked()
            val params = DefinitionParams(TextDocumentIdentifier(uri), position)
            val result = await("textDocument/definition", server.textDocumentService.definition(params))
            return DefinitionResult(
                locations = if (result != null && result.isLeft) result.left?.map { it } ?: emptyList() else emptyList(),
                locationLinks = if (result != null && result.isRight) result.right?.map { it } ?: emptyList() else emptyList(),
            )
        } finally {
            read.unlock()
        }
    }

    override fun semanticTokensFull(uri: String): SemanticTokens? {
        val read = gate.readLock()
        read.lock()
        try {
            requireInitializedLocked()
            requireSemanticTokensFull()
            val params = SemanticTokensParams(TextDocumentIdentifier(uri))
            return await("textDocument/semanticTokens/full", server.textDocumentService.semanticTokensFull(params))
        } finally {
            read.unlock()
        }
    }

    fun workspaceSymbol(query: String): WorkspaceSymbolResult {
        val read = gate.readLock()
        read.lock()
        try {
            requireInitializedLocked()
            val result = await("workspace/symbol", server.workspaceService.symbol(WorkspaceSymbolParams(query)))
            return WorkspaceSymbolResult(
                symbols = if (result != null && result.isLeft) result.left?.map { it } ?: emptyList() else emptyList(),
                workspaceSymbols = if (result != null && result.isRight) result.right?.map { it } ?: emptyList() else emptyList(),
            )
        } finally {
            read.unlock()
        }
    }

    fun supportsSemanticTokensFull(): Boolean {
        val provider = serverCapabilities?.semanticTokensProvider ?: return false
        val full = provider.full ?: return false
        return !(full.isLeft && full.left == false)
    }

    fun shutdown() {
        var shutdownFuture: CompletableFuture<Any>? = null
        val write = gate.writeLock()
        write.lock()
        try {
            check(!closeClaimed.get()) { "LSP client is closing or closed" }
            when (lifecycle) {
                Lifecycle.INITIALIZED -> shutdownFuture = prepareShutdownLocked()
                Lifecycle.SHUTDOWN -> Unit
                else -> throw IllegalStateException("LSP client is not initialized")
            }
        } finally {
            write.unlock()
        }
        if (shutdownFuture != null) {
            await("shutdown", shutdownFuture, requestTimeout)
        }
    }

    fun exit() {
        val write = gate.writeLock()
        write.lock()
        try {
            check(!closeClaimed.get()) { "LSP client is closing or closed" }
            check(lifecycle == Lifecycle.SHUTDOWN) { "shutdown must be called before exit" }
            sendExit()
        } finally {
            write.unlock()
        }
    }

    fun isProcessAlive(): Boolean = process?.isAlive == true

    fun processId(): Long? = process?.pid()

    override fun close() {
        val write = gate.writeLock()
        var locked = false
        try {
            locked =
                try {
                    write.tryLock(cleanupTimeout.toMillis(), TimeUnit.MILLISECONDS)
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
            if (!locked) {
                if (closeClaimed.compareAndSet(false, true)) {
                    teardownResources(force = true)
                }
                return
            }
            if (!closeClaimed.compareAndSet(false, true)) {
                return
            }
            var shutdownFuture: CompletableFuture<Any>? = null
            try {
                if (lifecycle == Lifecycle.INITIALIZED) {
                    shutdownFuture = prepareShutdownLocked()
                }
            } catch (exception: Throwable) {
                shutdownFuture = null
            }
            write.unlock()
            locked = false
            if (shutdownFuture != null) {
                runCatching { await("shutdown", shutdownFuture, cleanupTimeout) }
            }
            if (lifecycle == Lifecycle.SHUTDOWN) {
                sendExit()
            }
            teardownResources(force = false)
        } finally {
            if (locked) {
                write.unlock()
            }
        }
    }

    override fun telemetryEvent(obj: Any?) {
    }

    override fun publishDiagnostics(params: PublishDiagnosticsParams) {
        synchronized(stateLock) {
            if (!closeClaimed.get()) {
                diagnosticsByUri[params.uri] = params.diagnostics?.toList() ?: emptyList()
            }
        }
    }

    override fun showMessage(params: MessageParams) {
        synchronized(stateLock) {
            if (!closeClaimed.get()) {
                messages[params.message] = params
            }
        }
    }

    override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
        CompletableFuture.completedFuture(null)

    override fun logMessage(params: MessageParams) {
        synchronized(stateLock) {
            if (!closeClaimed.get()) {
                messages[params.message] = params
            }
        }
    }

    override fun workspaceFolders(): CompletableFuture<List<WorkspaceFolder>> = CompletableFuture.completedFuture(workspaceFolderList)

    override fun toString(): String = "LspClient(process=${process?.pid()})"

    private fun requireInitializedLocked() {
        check(!closeClaimed.get()) { "LSP client is closing or closed" }
        check(lifecycle == Lifecycle.INITIALIZED) { "LSP client is not initialized or has been shut down" }
    }

    private fun requireDocumentMutationLocked() {
        check(!closeClaimed.get()) { "LSP client is closing or closed" }
        check(lifecycle == Lifecycle.INITIALIZED) { "LSP client is not initialized or has been shut down" }
    }

    private fun documentLock(uri: String): Any = documentLocks.computeIfAbsent(uri) { Any() }

    private fun prepareShutdownLocked(): CompletableFuture<Any>? {
        if (lifecycle != Lifecycle.INITIALIZED) {
            return null
        }
        closeOpenDocumentsLocked()
        val future = server.shutdown()
        synchronized(stateLock) {
            if (lifecycle != Lifecycle.CLOSED) {
                lifecycle = Lifecycle.SHUTDOWN
            }
        }
        return future
    }

    private fun closeOpenDocumentsLocked() {
        documents.keys.toList().forEach { uri ->
            if (documents.remove(uri) != null) {
                runCatching {
                    server.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
                }
                diagnosticsByUri.remove(uri)
                documentLocks.remove(uri)
            }
        }
    }

    private fun requireSemanticTokensFull() {
        if (!supportsSemanticTokensFull()) {
            throw LspUnsupportedCapabilityException("textDocument/semanticTokens/full")
        }
    }

    private fun sendExit() {
        val started = process
        if (started != null && !started.isAlive) {
            return
        }
        if (exitSent.compareAndSet(false, true)) {
            runCatching { server.exit() }
        }
    }

    private fun teardownResources(force: Boolean) {
        synchronized(stateLock) {
            lifecycle = Lifecycle.CLOSED
            documents.clear()
            documentLocks.clear()
            diagnosticsByUri.clear()
            messages.clear()
        }
        runCatching { listening.cancel(true) }
        runCatching { executorService.shutdownNow() }
        closeQuietly(input)
        closeQuietly(output)
        destroyProcess(force)
    }

    private fun destroyProcess(force: Boolean) {
        val started = process ?: return
        runCatching { started.descendants().forEach { it.destroy() } }
        runCatching { started.destroy() }
        if (force) {
            runCatching { started.descendants().forEach { it.destroyForcibly() } }
            started.destroyForcibly()
            return
        }
        try {
            if (!started.waitFor(cleanupTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                runCatching { started.descendants().forEach { it.destroyForcibly() } }
                started.destroyForcibly()
            }
        } catch (exception: InterruptedException) {
            runCatching { started.descendants().forEach { it.destroyForcibly() } }
            started.destroyForcibly()
            Thread.currentThread().interrupt()
        }
    }

    private fun <T> await(
        method: String,
        future: CompletableFuture<T>,
        timeout: Duration = requestTimeout,
    ): T? {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) {
                future.cancel(true)
                throw LspTimeoutException(method, timeout)
            }
            try {
                return future.get(minOf(remaining, TRANSPORT_POLL_NANOS), TimeUnit.NANOSECONDS)
            } catch (exception: TimeoutException) {
                if (future.isDone) {
                    continue
                }
                if (listening.isDone) {
                    throw LspTransportException("LSP listener stopped while waiting for $method")
                }
                val started = process
                if (started != null && !started.isAlive) {
                    throw LspTransportException("LSP process exited while waiting for $method")
                }
            } catch (exception: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw LspTransportException("Interrupted while waiting for $method", exception)
            } catch (exception: CancellationException) {
                throw LspTransportException("Request $method was cancelled", exception)
            } catch (exception: ExecutionException) {
                val cause = exception.cause
                when (cause) {
                    is ResponseErrorException -> throw LspResponseException(method, cause.responseError)
                    is LspException -> throw cause
                    else -> throw LspTransportException("Request $method failed: ${cause?.message}", cause)
                }
            } catch (exception: RuntimeException) {
                throw LspTransportException("Request $method failed", exception)
            }
        }
    }

    private fun clientCapabilities(): ClientCapabilities {
        val workspace =
            WorkspaceClientCapabilities().apply {
                workspaceFolders = true
                symbol = SymbolCapabilities(false)
                semanticTokens = SemanticTokensWorkspaceCapabilities(false)
            }
        val textDocument =
            TextDocumentClientCapabilities().apply {
                synchronization = SynchronizationCapabilities(false, false, false)
                documentSymbol = DocumentSymbolCapabilities(SymbolKindCapabilities(), false, true)
                references = ReferencesCapabilities(false)
                definition = DefinitionCapabilities(false, true)
                semanticTokens =
                    SemanticTokensCapabilities(
                        SemanticTokensClientCapabilitiesRequests(SemanticTokensClientCapabilitiesRequestsFull(false)),
                        SEMANTIC_TOKEN_TYPES,
                        SEMANTIC_TOKEN_MODIFIERS,
                        listOf("relative"),
                    ).apply {
                        overlappingTokenSupport = false
                        multilineTokenSupport = false
                        serverCancelSupport = false
                    }
            }
        return ClientCapabilities(workspace, textDocument, null)
    }

    private fun closeQuietly(stream: AutoCloseable) {
        runCatching { stream.close() }
    }

    companion object {
        fun start(
            command: List<String>,
            workingDirectory: Path,
            requestTimeout: Duration = Duration.ofSeconds(30),
            cleanupTimeout: Duration = Duration.ofSeconds(1),
            environment: Map<String, String> = emptyMap(),
        ): LspClient {
            require(command.isNotEmpty()) { "LSP command must not be empty" }
            val builder = ProcessBuilder(command).directory(workingDirectory.toFile())
            builder.environment().putAll(environment)
            val process =
                try {
                    builder.start()
                } catch (exception: IOException) {
                    throw LspTransportException("Failed to start LSP process ${command.first()}", exception)
                }
            Thread(
                {
                    runCatching { process.errorStream.use { it.readAllBytes() } }
                },
                "lsp-client-stderr",
            ).apply { isDaemon = true }.start()
            return try {
                create(process.inputStream, process.outputStream, process, requestTimeout, cleanupTimeout)
            } catch (exception: Throwable) {
                process.destroyForcibly()
                if (exception is LspException) {
                    throw exception
                }
                throw LspTransportException("Failed to start LSP client", exception)
            }
        }

        fun connect(
            input: InputStream,
            output: OutputStream,
            requestTimeout: Duration = Duration.ofSeconds(30),
            cleanupTimeout: Duration = Duration.ofSeconds(1),
        ): LspClient = create(input, output, null, requestTimeout, cleanupTimeout)

        private fun create(
            input: InputStream,
            output: OutputStream,
            process: Process?,
            requestTimeout: Duration,
            cleanupTimeout: Duration,
        ): LspClient {
            require(!requestTimeout.isZero && !requestTimeout.isNegative) { "Request timeout must be positive" }
            require(!cleanupTimeout.isZero && !cleanupTimeout.isNegative) { "Cleanup timeout must be positive" }
            return try {
                LspClient(input, output, process, requestTimeout, cleanupTimeout)
            } catch (exception: RuntimeException) {
                closeQuietly(input)
                closeQuietly(output)
                throw LspTransportException("Failed to create LSP client", exception)
            }
        }

        private const val TRANSPORT_POLL_NANOS = 25_000_000L

        private val SEMANTIC_TOKEN_TYPES =
            listOf(
                "namespace",
                "type",
                "class",
                "enum",
                "interface",
                "struct",
                "typeParameter",
                "parameter",
                "variable",
                "property",
                "enumMember",
                "event",
                "function",
                "method",
                "macro",
                "keyword",
                "modifier",
                "comment",
                "string",
                "number",
                "regexp",
                "operator",
                "decorator",
            )

        private val SEMANTIC_TOKEN_MODIFIERS =
            listOf(
                "declaration",
                "definition",
                "readonly",
                "static",
                "deprecated",
                "abstract",
                "async",
                "modification",
                "documentation",
                "defaultLibrary",
            )

        private fun closeQuietly(stream: AutoCloseable) {
            runCatching { stream.close() }
        }
    }
}
