package dev.codenb.frontend

import dev.codenb.api.GraphId
import dev.codenb.api.Node
import dev.codenb.api.SourceSpan
import dev.codenb.core.EntityDescriptor
import dev.codenb.core.EntityIdentity
import dev.codenb.core.EntitySymbolKind
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import java.security.MessageDigest

object KotlinEntityDiscovery {
    fun discover(
        graphId: GraphId,
        fileUri: String,
        source: String,
        symbols: List<DocumentSymbol>,
    ): List<Node> {
        val masked = KotlinSourceText.mask(source)
        val withoutComments = KotlinSourceText.maskComments(source)
        val packageName = KotlinSourceText.packageName(source)
        val candidates = mutableListOf<Candidate>()
        symbols.forEach { symbol ->
            collect(
                symbol = symbol,
                parentQualifiedName = packageName,
                parentSymbol = null,
                insideType = false,
                source = source,
                masked = masked,
                withoutComments = withoutComments,
                candidates = candidates,
            )
        }
        val descriptors =
            candidates.map { candidate ->
                val signature = candidate.signature ?: fallbackFingerprint(candidate.fallbackText)
                EntityDescriptor(
                    graphId = graphId,
                    fileUri = fileUri,
                    label = candidate.label,
                    qualifiedName = candidate.qualifiedName,
                    symbolKind = candidate.symbolKind,
                    signatureFingerprint = signature,
                    span = sourceSpan(candidate.range),
                    unstable = candidate.signature == null,
                )
            }
        return EntityIdentity.nodes(descriptors)
    }

    private fun collect(
        symbol: DocumentSymbol,
        parentQualifiedName: String,
        parentSymbol: DocumentSymbol?,
        insideType: Boolean,
        source: String,
        masked: String,
        withoutComments: String,
        candidates: MutableList<Candidate>,
    ) {
        if (symbol.kind == SymbolKind.Constructor) {
            val constructor =
                constructorCandidate(
                    symbol = symbol,
                    parentSymbol = parentSymbol,
                    parentQualifiedName = parentQualifiedName,
                    source = source,
                    masked = masked,
                    withoutComments = withoutComments,
                )
            if (constructor != null) {
                candidates.add(constructor)
            }
            return
        }
        val label = symbol.name?.trim().orEmpty()
        val start = offset(source, symbol.range.start)
        val end = offset(source, symbol.range.end)
        if (!isNamedEntity(label, source, start, end)) {
            return
        }
        val baseKind = baseKind(symbol, insideType) ?: return
        val preliminaryEnd = KotlinSourceText.headerEnd(masked, start, end, stopAtEquals = false)
        val keyword = KotlinSourceText.declarationKeyword(masked, start, preliminaryEnd, declarationKeywords(baseKind))
        val isTypeAlias = baseKind == EntitySymbolKind.CLASS && keyword?.text == "typealias"
        val symbolKind = if (isTypeAlias) EntitySymbolKind.TYPE_ALIAS else baseKind
        val headerEnd =
            KotlinSourceText.headerEnd(
                masked,
                start,
                end,
                stopAtEquals = symbolKind in EQUALS_HEADER_KINDS,
            )
        val keywordStart = keyword?.start ?: start
        if (KotlinSourceText.hasPrivateModifier(masked, start, keywordStart)) {
            return
        }
        val signature = signature(symbol, withoutComments, headerEnd, keywordStart)
        val qualifiedName = qualify(parentQualifiedName, label)
        candidates.add(
            Candidate(
                label = label,
                qualifiedName = qualifiedName,
                symbolKind = symbolKind,
                signature = signature,
                fallbackText = fallbackText(source, start, end, headerEnd, keywordStart),
                range = symbol.range,
            ),
        )
        if (!isContainer(symbolKind)) {
            return
        }
        symbol.children.orEmpty().forEach { child ->
            collect(
                symbol = child,
                parentQualifiedName = qualifiedName,
                parentSymbol = symbol,
                insideType = true,
                source = source,
                masked = masked,
                withoutComments = withoutComments,
                candidates = candidates,
            )
        }
    }

    private fun constructorCandidate(
        symbol: DocumentSymbol,
        parentSymbol: DocumentSymbol?,
        parentQualifiedName: String,
        source: String,
        masked: String,
        withoutComments: String,
    ): Candidate? {
        val name = symbol.name?.trim().orEmpty()
        if (parentSymbol == null) {
            return null
        }
        val start = offset(source, symbol.range.start)
        val end = offset(source, symbol.range.end)
        if (isInitBlock(name, masked, start, end)) {
            return null
        }
        val parentStart = offset(source, parentSymbol.range.start)
        val parentEnd = offset(source, parentSymbol.range.end)
        val constructorKeyword = KotlinSourceText.keywordOffset(masked, start, end, CONSTRUCTOR)
        val parameters =
            if (constructorKeyword != null) {
                KotlinSourceText.firstParentheses(masked, constructorKeyword + "constructor".length, end)
            } else {
                KotlinSourceText.firstParentheses(masked, start, maxOf(end, parentEnd))
                    ?: KotlinSourceText.firstParentheses(masked, parentStart, parentEnd)
            }
        val headerStart = if (constructorKeyword != null) start else minOf(start, parentStart)
        val visibilityEnd = parameters?.first ?: end
        if (KotlinSourceText.hasPrivateModifier(masked, headerStart, visibilityEnd)) {
            return null
        }
        val signature =
            if (!symbol.detail.isNullOrBlank()) {
                KotlinSourceText.normalize(symbol.detail)
            } else {
                parameters?.let {
                    val text = KotlinSourceText.normalize(withoutComments.substring(it.first, it.last))
                    if (text.isBlank()) null else "constructor$text"
                }
            }
        return Candidate(
            label = "constructor",
            qualifiedName = qualify(parentQualifiedName, "constructor"),
            symbolKind = EntitySymbolKind.CONSTRUCTOR,
            signature = signature,
            fallbackText = source.substring(headerStart.coerceAtMost(end), end),
            range = symbol.range,
        )
    }

    private fun baseKind(
        symbol: DocumentSymbol,
        insideType: Boolean,
    ): EntitySymbolKind? =
        when (symbol.kind) {
            SymbolKind.Class, SymbolKind.Struct -> EntitySymbolKind.CLASS
            SymbolKind.Interface -> EntitySymbolKind.INTERFACE
            SymbolKind.Object -> EntitySymbolKind.OBJECT
            SymbolKind.Enum -> EntitySymbolKind.ENUM
            SymbolKind.EnumMember -> EntitySymbolKind.ENUM_MEMBER
            SymbolKind.Function, SymbolKind.Operator ->
                if (insideType) EntitySymbolKind.METHOD else EntitySymbolKind.FUNCTION
            SymbolKind.Method -> EntitySymbolKind.METHOD
            SymbolKind.Property, SymbolKind.Field, SymbolKind.Variable, SymbolKind.Constant -> EntitySymbolKind.PROPERTY
            else -> null
        }

    private fun declarationKeywords(kind: EntitySymbolKind): Set<String> =
        when (kind) {
            EntitySymbolKind.CLASS -> setOf("class", "typealias")
            EntitySymbolKind.INTERFACE -> setOf("interface")
            EntitySymbolKind.OBJECT -> setOf("object")
            EntitySymbolKind.ENUM -> setOf("enum", "class")
            EntitySymbolKind.TYPE_ALIAS -> setOf("typealias")
            EntitySymbolKind.FUNCTION, EntitySymbolKind.METHOD -> setOf("fun")
            EntitySymbolKind.PROPERTY -> setOf("val", "var")
            EntitySymbolKind.CONSTRUCTOR -> setOf("constructor")
            EntitySymbolKind.ENUM_MEMBER -> emptySet()
        }

    private fun signature(
        symbol: DocumentSymbol,
        withoutComments: String,
        headerEnd: Int,
        keywordStart: Int,
    ): String? {
        val detail = symbol.detail
        if (!detail.isNullOrBlank()) {
            return KotlinSourceText.normalize(detail).takeIf { it.isNotBlank() }
        }
        if (headerEnd <= keywordStart) {
            return null
        }
        return KotlinSourceText.normalize(withoutComments.substring(keywordStart, headerEnd)).takeIf { it.isNotBlank() }
    }

    private fun fallbackText(
        source: String,
        start: Int,
        end: Int,
        headerEnd: Int,
        keywordStart: Int,
    ): String {
        val headerStart = minOf(keywordStart, headerEnd)
        val header = source.substring(headerStart, headerEnd)
        if (header.isNotBlank()) {
            return header
        }
        return source.substring(start, end)
    }

    private fun fallbackFingerprint(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(KotlinSourceText.normalize(text).toByteArray(Charsets.UTF_8))
        val hash =
            digest
                .take(8)
                .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
        return "unstable-$hash"
    }

    private fun isContainer(kind: EntitySymbolKind): Boolean =
        when (kind) {
            EntitySymbolKind.CLASS,
            EntitySymbolKind.INTERFACE,
            EntitySymbolKind.OBJECT,
            EntitySymbolKind.ENUM,
            -> true
            else -> false
        }

    private fun isNamedEntity(
        name: String,
        source: String,
        start: Int,
        end: Int,
    ): Boolean {
        if (name.isBlank()) {
            return false
        }
        if (isBackticked(source, start, end, name)) {
            return true
        }
        return !name.startsWith('<') && !name.contains('$')
    }

    private fun isBackticked(
        source: String,
        start: Int,
        end: Int,
        name: String,
    ): Boolean {
        if (start !in 0..source.length || end !in start..source.length) {
            return false
        }
        val plainName = name.removeSurrounding("`")
        return source.substring(start, end).contains("`$plainName`")
    }

    private fun isInitBlock(
        name: String,
        masked: String,
        start: Int,
        end: Int,
    ): Boolean {
        if (name == "<class initializer>" || name.startsWith("<class initializer>")) {
            return true
        }
        return KotlinSourceText.startsWithInitBlock(masked, start, end)
    }

    private fun qualify(
        parent: String,
        name: String,
    ): String = if (parent.isBlank()) name else "$parent.$name"

    private fun offset(
        source: String,
        position: Position,
    ): Int {
        val lineStarts = lineStarts(source)
        val line = position.line
        if (line <= 0) {
            return position.character.coerceIn(0, lineEnd(source, lineStarts, 0))
        }
        if (line >= lineStarts.size) {
            return source.length
        }
        val start = lineStarts[line]
        return (start + position.character).coerceIn(start, lineEnd(source, lineStarts, line))
    }

    private fun lineEnd(
        source: String,
        lineStarts: List<Int>,
        line: Int,
    ): Int {
        if (line < 0 || line >= lineStarts.size) {
            return source.length
        }
        val nextLine = lineStarts.getOrNull(line + 1) ?: return source.length
        var end = nextLine
        if (end > lineStarts[line] && source[end - 1] == '\n') {
            end--
        }
        if (end > lineStarts[line] && source[end - 1] == '\r') {
            end--
        }
        return end
    }

    private fun lineStarts(source: String): List<Int> {
        val starts = mutableListOf(0)
        source.forEachIndexed { index, character ->
            if (character == '\n') {
                starts.add(index + 1)
            }
        }
        return starts
    }

    private fun sourceSpan(range: Range): SourceSpan =
        SourceSpan(
            startLine = range.start.line,
            startCharacter = range.start.character,
            endLine = range.end.line,
            endCharacter = range.end.character,
        )

    private data class Candidate(
        val label: String,
        val qualifiedName: String,
        val symbolKind: EntitySymbolKind,
        val signature: String?,
        val fallbackText: String,
        val range: Range,
    )

    private val CONSTRUCTOR = setOf("constructor")
    private val EQUALS_HEADER_KINDS =
        setOf(
            EntitySymbolKind.FUNCTION,
            EntitySymbolKind.METHOD,
            EntitySymbolKind.PROPERTY,
        )
}
