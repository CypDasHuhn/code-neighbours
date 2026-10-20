package dev.codenb.server

import dev.codenb.lsp.DefinitionResult
import dev.codenb.lsp.OpenDocument
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class LspCall(
    val uri: String,
    val position: Position,
)

class FakeGraphLsp : GraphLsp {
    override var semanticTokensLegend: SemanticTokensLegend? =
        SemanticTokensLegend(
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
            ),
            listOf("declaration", "definition"),
        )
    val documents = ConcurrentHashMap<String, OpenDocument>()
    val symbols = ConcurrentHashMap<String, List<DocumentSymbol>>()
    val referencesByCall = ConcurrentHashMap<LspCall, List<Location>>()
    val definitionsByCall = ConcurrentHashMap<LspCall, DefinitionResult>()
    val tokens = ConcurrentHashMap<String, SemanticTokens?>()
    val initializeCount = AtomicInteger()
    val queryCount = AtomicInteger()
    val activeCalls = AtomicInteger()
    val maxConcurrentCalls = AtomicInteger()
    val factoryCalls = AtomicInteger()

    @Volatile var closed: Boolean = false

    @Volatile var callDelayMillis: Long = 0

    @Volatile var queryFailure: Throwable? = null

    @Volatile var documentSymbolFailure: Throwable? = null

    @Volatile var referencesFailure: Throwable? = null

    @Volatile var referencesBlock: (() -> Unit)? = null

    override fun initialize(root: Path) {
        initializeCount.incrementAndGet()
        queryFailure?.let { throw it }
    }

    override fun didOpen(
        uri: String,
        languageId: String,
        text: String,
        version: Int,
    ) {
        queryFailure?.let { throw it }
        documents[uri] = OpenDocument(uri, languageId, version, text)
    }

    override fun didClose(uri: String) {
        documents.remove(uri)
    }

    override fun openDocument(uri: String): OpenDocument? = documents[uri]

    override fun documentSymbol(uri: String): List<Either<SymbolInformation, DocumentSymbol>> =
        call {
            documentSymbolFailure?.let { throw it }
            symbols[uri].orEmpty().map { symbol -> Either.forRight<SymbolInformation, DocumentSymbol>(symbol) }
        }

    override fun references(
        uri: String,
        position: Position,
        includeDeclaration: Boolean,
    ): List<Location> =
        call {
            referencesFailure?.let { throw it }
            referencesBlock?.invoke()
            referencesByCall[LspCall(uri, position)].orEmpty()
        }

    override fun definition(
        uri: String,
        position: Position,
    ): DefinitionResult =
        call {
            definitionsByCall[LspCall(uri, position)] ?: DefinitionResult(emptyList(), emptyList())
        }

    override fun semanticTokensFull(uri: String): SemanticTokens? =
        call {
            if (tokens.containsKey(uri)) tokens[uri] else SemanticTokens(emptyList())
        }

    override fun close() {
        closed = true
    }

    private fun <T> call(block: () -> T): T {
        queryFailure?.let { throw it }
        val active = activeCalls.incrementAndGet()
        maxConcurrentCalls.updateAndGet { previous -> maxOf(previous, active) }
        try {
            queryCount.incrementAndGet()
            if (callDelayMillis > 0) {
                Thread.sleep(callDelayMillis)
            }
            return block()
        } finally {
            activeCalls.decrementAndGet()
        }
    }
}

fun functionSymbol(
    source: String,
    name: String,
    detail: String,
    rangeStart: String,
    fromIndex: Int = 0,
): DocumentSymbol {
    val start = source.indexOf(rangeStart, fromIndex)
    require(start >= 0) { "Missing range start $rangeStart" }
    val end = source.indexOf('\n', start).let { if (it < 0) source.length else it }
    val selectionStart = source.indexOf(name, start)
    require(selectionStart >= 0) { "Missing selection $name" }
    return DocumentSymbol(
        name,
        SymbolKind.Function,
        rangeAt(source, start, end - start),
        rangeAt(source, selectionStart, name.length),
        detail,
        emptyList(),
    )
}

fun positionAt(
    source: String,
    index: Int,
): Position {
    val prefix = source.substring(0, index)
    val line = prefix.count { it == '\n' }
    val lineStart = prefix.lastIndexOf('\n') + 1
    return Position(line, index - lineStart)
}

fun rangeAt(
    source: String,
    index: Int,
    length: Int,
): Range = Range(positionAt(source, index), positionAt(source, index + length))

fun locationAt(
    uri: String,
    source: String,
    index: Int,
    token: String,
): Location = Location(uri, rangeAt(source, index, token.length))
