package dev.codenb.lsp

import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.TextDocumentSyncOptions
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

enum class FakeResult {
    DOCUMENT_SYMBOL,
    REFERENCES,
    DEFINITION,
    SEMANTIC_TOKENS,
    WORKSPACE_SYMBOL,
}

data class FakeServerOptions(
    val semanticTokens: Boolean = true,
    val ignoreDocumentSymbol: Boolean = false,
    val ignoreShutdown: Boolean = false,
    val publishDiagnosticsOnOpen: Boolean = false,
    val publishDiagnosticsOnShutdown: Boolean = false,
    val initializeBlocker: CountDownLatch? = null,
    val exitOnExit: Boolean = false,
    val exitOnDocumentSymbol: Boolean = false,
    val nullResults: Set<FakeResult> = emptySet(),
    val errorResults: Set<FakeResult> = emptySet(),
)

class FakeLspServer(
    private val options: FakeServerOptions = FakeServerOptions(),
) : LanguageServer,
    TextDocumentService,
    WorkspaceService {
    val legend = SemanticTokensLegend(listOf("class", "function", "property"), listOf("declaration"))

    private val methodCalls = CopyOnWriteArrayList<String>()
    private val openedDocuments = ConcurrentHashMap<String, Int>()
    private val documentTexts = ConcurrentHashMap<String, String>()
    private val documentVersions = ConcurrentHashMap<String, CopyOnWriteArrayList<Int>>()
    private val closedDocuments = CopyOnWriteArrayList<String>()
    private val recordedRequests = CopyOnWriteArrayList<Pair<String, String>>()
    private val recordedCancellations = CopyOnWriteArrayList<String>()
    private val shutdownCalls = AtomicInteger()
    private val exitCalls = AtomicInteger()

    @Volatile
    private var client: LanguageClient? = null

    @Volatile
    var initializeParams: InitializeParams? = null
        private set

    @Volatile
    var initializedCalled: Boolean = false
        private set

    @Volatile
    var shutdownCalled: Boolean = false
        private set

    @Volatile
    var exitCalled: Boolean = false
        private set

    @Volatile
    var lastDocumentSymbol: DocumentSymbolParams? = null
        private set

    @Volatile
    var lastReferences: ReferenceParams? = null
        private set

    @Volatile
    var lastDefinition: DefinitionParams? = null
        private set

    @Volatile
    var lastSemanticTokens: SemanticTokensParams? = null
        private set

    @Volatile
    var lastWorkspaceSymbol: WorkspaceSymbolParams? = null
        private set

    fun connect(remote: LanguageClient) {
        client = remote
    }

    fun calls(): List<String> = methodCalls.toList()

    fun documentVersion(uri: String): Int? = openedDocuments[uri]

    fun documentText(uri: String): String? = documentTexts[uri]

    fun documentVersions(uri: String): List<Int> = documentVersions[uri]?.toList() ?: emptyList()

    fun isClosed(uri: String): Boolean = uri in closedDocuments

    fun shutdownCount(): Int = shutdownCalls.get()

    fun exitCount(): Int = exitCalls.get()

    fun recordRequest(
        id: String,
        method: String,
    ) {
        recordedRequests += id to method
    }

    fun requests(): List<Pair<String, String>> = recordedRequests.toList()

    fun recordCancellation(id: String) {
        recordedCancellations += id
    }

    fun cancellations(): List<String> = recordedCancellations.toList()

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        methodCalls += "initialize"
        initializeParams = params
        options.initializeBlocker?.await()
        val capabilities =
            ServerCapabilities().apply {
                setTextDocumentSync(
                    TextDocumentSyncOptions().apply {
                        openClose = true
                        change = TextDocumentSyncKind.Full
                    },
                )
                setDocumentSymbolProvider(true)
                setReferencesProvider(true)
                setDefinitionProvider(true)
                setWorkspaceSymbolProvider(true)
                if (options.semanticTokens) {
                    setSemanticTokensProvider(SemanticTokensWithRegistrationOptions(legend, true))
                }
            }
        return CompletableFuture.completedFuture(InitializeResult(capabilities))
    }

    override fun initialized(params: InitializedParams) {
        methodCalls += "initialized"
        initializedCalled = true
    }

    override fun shutdown(): CompletableFuture<Any> {
        methodCalls += "shutdown"
        shutdownCalls.incrementAndGet()
        shutdownCalled = true
        if (options.publishDiagnosticsOnShutdown) {
            val range = Range(Position(0, 0), Position(0, 1))
            client?.publishDiagnostics(
                PublishDiagnosticsParams(
                    "file:///fake/close.kt",
                    listOf(Diagnostic(range, "close diagnostic", DiagnosticSeverity.Warning, "fake")),
                ),
            )
        }
        if (options.ignoreShutdown) {
            return CompletableFuture()
        }
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        methodCalls += "exit"
        exitCalls.incrementAndGet()
        exitCalled = true
        if (options.exitOnExit) {
            System.exit(0)
        }
    }

    override fun getTextDocumentService(): TextDocumentService = this

    override fun getWorkspaceService(): WorkspaceService = this

    override fun didOpen(params: DidOpenTextDocumentParams) {
        methodCalls += "didOpen"
        val document = params.textDocument
        openedDocuments[document.uri] = document.version
        documentTexts[document.uri] = document.text
        documentVersions.computeIfAbsent(document.uri) { CopyOnWriteArrayList() } += document.version
        if (options.publishDiagnosticsOnOpen) {
            val range = Range(Position(0, 0), Position(0, 1))
            client?.publishDiagnostics(
                PublishDiagnosticsParams(
                    document.uri,
                    listOf(Diagnostic(range, "fake diagnostic", DiagnosticSeverity.Warning, "fake")),
                    document.version,
                ),
            )
        }
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        methodCalls += "didChange"
        val document = params.textDocument
        val version = document.version ?: 0
        openedDocuments[document.uri] = version
        documentVersions.computeIfAbsent(document.uri) { CopyOnWriteArrayList() } += version
        params.contentChanges.lastOrNull()?.let { documentTexts[document.uri] = it.text }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        methodCalls += "didClose"
        closedDocuments += params.textDocument.uri
        openedDocuments.remove(params.textDocument.uri)
        documentTexts.remove(params.textDocument.uri)
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
    }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        methodCalls += "documentSymbol"
        lastDocumentSymbol = params
        errorResult<List<Either<SymbolInformation, DocumentSymbol>>>("documentSymbol", FakeResult.DOCUMENT_SYMBOL)?.let { return it }
        nullResult<List<Either<SymbolInformation, DocumentSymbol>>>(FakeResult.DOCUMENT_SYMBOL)?.let { return it }
        if (options.exitOnDocumentSymbol) {
            System.exit(0)
        }
        if (options.ignoreDocumentSymbol) {
            return CompletableFuture()
        }
        val uri = params.textDocument.uri
        val range = Range(Position(0, 0), Position(0, 5))
        val symbol = DocumentSymbol("Thing", SymbolKind.Class, range, range, "class Thing")
        return CompletableFuture.completedFuture(listOf(Either.forRight(symbol)))
    }

    override fun references(params: ReferenceParams): CompletableFuture<List<Location>> {
        methodCalls += "references"
        lastReferences = params
        errorResult<List<Location>>("references", FakeResult.REFERENCES)?.let { return it }
        nullResult<List<Location>>(FakeResult.REFERENCES)?.let { return it }
        val range = Range(Position(2, 4), Position(2, 9))
        return CompletableFuture.completedFuture(listOf(Location(params.textDocument.uri, range)))
    }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<List<Location>, List<LocationLink>>> {
        methodCalls += "definition"
        lastDefinition = params
        errorResult<Either<List<Location>, List<LocationLink>>>("definition", FakeResult.DEFINITION)?.let { return it }
        nullResult<Either<List<Location>, List<LocationLink>>>(FakeResult.DEFINITION)?.let { return it }
        val range = Range(Position(1, 0), Position(1, 5))
        return CompletableFuture.completedFuture(Either.forLeft(listOf(Location(params.textDocument.uri, range))))
    }

    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> {
        methodCalls += "semanticTokensFull"
        lastSemanticTokens = params
        errorResult<SemanticTokens>("semanticTokensFull", FakeResult.SEMANTIC_TOKENS)?.let { return it }
        nullResult<SemanticTokens>(FakeResult.SEMANTIC_TOKENS)?.let { return it }
        return CompletableFuture.completedFuture(
            SemanticTokens(listOf(0, 0, 5, 1, 0, 1, 0, 5, 2, 0)),
        )
    }

    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        methodCalls += "workspaceSymbol"
        lastWorkspaceSymbol = params
        errorResult<Either<List<SymbolInformation>, List<WorkspaceSymbol>>>(
            "workspaceSymbol",
            FakeResult.WORKSPACE_SYMBOL,
        )?.let { return it }
        nullResult<Either<List<SymbolInformation>, List<WorkspaceSymbol>>>(FakeResult.WORKSPACE_SYMBOL)?.let { return it }
        val range = Range(Position(0, 0), Position(0, 5))
        val symbol =
            WorkspaceSymbol(
                "Thing",
                SymbolKind.Class,
                Either.forLeft(Location("file:///fake/Thing.kt", range)),
                "fake",
            )
        return CompletableFuture.completedFuture(Either.forRight(listOf(symbol)))
    }

    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
    }

    private fun <T> errorResult(
        method: String,
        result: FakeResult,
    ): CompletableFuture<T>? {
        if (result !in options.errorResults) {
            return null
        }
        val error = ResponseError(ResponseErrorCode.InternalError, "fake $method error", null)
        return CompletableFuture.failedFuture(ResponseErrorException(error))
    }

    private fun <T> nullResult(result: FakeResult): CompletableFuture<T>? {
        if (result !in options.nullResults) {
            return null
        }
        val future = CompletableFuture<T>()
        future.complete(null)
        return future
    }
}
