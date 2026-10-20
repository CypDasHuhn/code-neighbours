package dev.codenb.frontend

import dev.codenb.lsp.DefinitionResult
import dev.codenb.lsp.LspSession
import dev.codenb.lsp.OpenDocument
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.jsonrpc.messages.Either

class FakeLspSession(
    override var semanticTokensLegend: SemanticTokensLegend? =
        SemanticTokensLegend(TOKEN_TYPES, TOKEN_MODIFIERS),
) : LspSession {
    val documents = linkedMapOf<String, OpenDocument>()
    val opened = mutableListOf<String>()
    val closed = mutableListOf<String>()
    val symbols = mutableMapOf<String, List<DocumentSymbol>>()
    val referenceResults = mutableMapOf<CallKey, List<Location>>()
    val definitionResults = mutableMapOf<CallKey, DefinitionResult>()
    val tokenResults = mutableMapOf<String, SemanticTokens?>()
    var semanticTokensFailure: Throwable? = null

    fun addSymbols(
        uri: String,
        symbols: List<DocumentSymbol>,
    ) {
        this.symbols[uri] = symbols
    }

    fun addReferences(
        uri: String,
        position: Position,
        locations: List<Location>,
    ) {
        referenceResults[CallKey(uri, position)] = locations
    }

    fun addDefinition(
        uri: String,
        position: Position,
        locations: List<Location>,
        locationLinks: List<LocationLink> = emptyList(),
    ) {
        definitionResults[CallKey(uri, position)] = DefinitionResult(locations, locationLinks)
    }

    fun addTokens(
        uri: String,
        tokens: SemanticTokens?,
    ) {
        tokenResults[uri] = tokens
    }

    override fun didOpen(
        uri: String,
        languageId: String,
        text: String,
        version: Int,
    ) {
        documents[uri] = OpenDocument(uri, languageId, version, text)
        opened.add(uri)
    }

    override fun didClose(uri: String) {
        documents.remove(uri)
        closed.add(uri)
    }

    override fun openDocument(uri: String): OpenDocument? = documents[uri]

    override fun documentSymbol(uri: String): List<Either<SymbolInformation, DocumentSymbol>> =
        symbols[uri].orEmpty().map { Either.forRight<SymbolInformation, DocumentSymbol>(it) }

    override fun references(
        uri: String,
        position: Position,
        includeDeclaration: Boolean,
    ): List<Location> = referenceResults[CallKey(uri, position)].orEmpty()

    override fun definition(
        uri: String,
        position: Position,
    ): DefinitionResult = definitionResults[CallKey(uri, position)] ?: DefinitionResult(emptyList(), emptyList())

    override fun semanticTokensFull(uri: String): SemanticTokens? {
        semanticTokensFailure?.let { throw it }
        return if (tokenResults.containsKey(uri)) tokenResults[uri] else SemanticTokens(emptyList())
    }

    data class CallKey(
        val uri: String,
        val position: Position,
    )

    companion object {
        val TOKEN_TYPES =
            listOf(
                "function",
                "variable",
                "class",
                "keyword",
                "comment",
                "string",
                "number",
                "regexp",
                "operator",
                "modifier",
                "property",
                "parameter",
                "typeParameter",
            )
        val TOKEN_MODIFIERS = listOf("declaration", "definition")
    }
}
