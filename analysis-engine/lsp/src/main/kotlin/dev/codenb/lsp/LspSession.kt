package dev.codenb.lsp

import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.jsonrpc.messages.Either

interface LspSession {
    val semanticTokensLegend: SemanticTokensLegend?

    fun didOpen(
        uri: String,
        languageId: String,
        text: String,
        version: Int = 1,
    )

    fun didClose(uri: String)

    fun openDocument(uri: String): OpenDocument?

    fun documentSymbol(uri: String): List<Either<SymbolInformation, DocumentSymbol>>

    fun references(
        uri: String,
        position: Position,
        includeDeclaration: Boolean = true,
    ): List<Location>

    fun definition(
        uri: String,
        position: Position,
    ): DefinitionResult

    fun semanticTokensFull(uri: String): SemanticTokens?
}
