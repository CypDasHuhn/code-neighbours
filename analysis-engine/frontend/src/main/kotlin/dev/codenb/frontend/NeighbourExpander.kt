package dev.codenb.frontend

import dev.codenb.api.Edge
import dev.codenb.api.EdgeKey
import dev.codenb.api.EdgeKind
import dev.codenb.api.EdgeOrigin
import dev.codenb.api.Expansion
import dev.codenb.api.GraphId
import dev.codenb.api.Node
import dev.codenb.api.NodeId
import dev.codenb.api.NodeKind
import dev.codenb.api.SourceSpan
import dev.codenb.core.EntitySymbolKind
import dev.codenb.frontend.KotlinEntityDiscovery.DiscoveredEntity
import dev.codenb.lsp.DefinitionResult
import dev.codenb.lsp.LspSession
import dev.codenb.lsp.LspUnsupportedCapabilityException
import dev.codenb.lsp.WorkspaceFiles
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class NeighbourExpander(
    private val session: LspSession,
    private val workspaceFiles: Set<Path>? = null,
) {
    fun expand(
        graphId: GraphId,
        seed: Node,
        workspaceRoot: Path,
        frontend: LanguageFrontend,
        revision: String? = null,
    ): Expansion = expandWithFiles(graphId, seed, workspaceRoot, frontend, revision).expansion

    fun expandWithFiles(
        graphId: GraphId,
        seed: Node,
        workspaceRoot: Path,
        frontend: LanguageFrontend,
        revision: String? = null,
    ): ExpansionWithFiles {
        require(seed.kind == NodeKind.ENTITY) { "Seed must be an entity" }
        val seedUri = requireNotNull(seed.fileUri) { "Seed entity requires a file URI" }
        val context = Context(graphId, workspaceRoot, frontend, seed, revision)
        try {
            val seedPath = context.path(seedUri) ?: throw IllegalArgumentException("Seed file URI is not a file URI: $seedUri")
            val seedFile = context.file(seedPath)
            val seedEntity =
                seedFile.entities.firstOrNull { it.node.id == seed.id }
                    ?: throw IllegalStateException("Seed entity is not present in its file")
            session.references(seedFile.uri, seedEntity.referencePosition, includeDeclaration = false).forEach { location ->
                val file = context.fileForLocation(location)
                if (file != null) {
                    val entity = file.resolveIncoming(location.range.start)
                    if (entity != null) {
                        context.observe(entity.node, seed)
                    }
                }
            }
            val tokens = session.semanticTokensFull(seedFile.uri)
            if (tokens == null) {
                if (!seedEntity.fullSpan.isEmpty()) {
                    throw LspUnsupportedCapabilityException("textDocument/semanticTokens/full")
                }
            } else {
                val legend =
                    session.semanticTokensLegend
                        ?: throw LspUnsupportedCapabilityException("textDocument/semanticTokens/full")
                val declarationPositions = seedFile.declarationPositionsInside(seedEntity.fullSpan)
                decodeTokens(tokens, legend)
                    .filter { seedEntity.fullSpan.containsToken(it.start, it.end) }
                    .filter { it.type.isNotBlank() && it.type !in EXCLUDED_TOKEN_TYPES }
                    .filter { it.start !in declarationPositions }
                    .filter { "declaration" !in it.modifiers && "definition" !in it.modifiers }
                    .forEach { token ->
                        definitionTargets(session.definition(seedFile.uri, token.start)).forEach { target ->
                            val file = context.fileForLocation(target.location)
                            if (file != null) {
                                val entity = file.resolveOutgoing(target.location, target.selectionSpan)
                                if (entity != null) {
                                    context.observe(seed, entity.node)
                                }
                            }
                        }
                    }
            }
            return context.expansionWithFiles(seed)
        } finally {
            context.close()
        }
    }

    private inner class Context(
        private val graphId: GraphId,
        workspaceRoot: Path,
        private val frontend: LanguageFrontend,
        private val seed: Node,
        private val revisionOverride: String?,
    ) {
        private val root = workspaceRoot.toAbsolutePath().normalize()
        private val workspacePaths =
            workspaceFiles
                ?.map { it.toAbsolutePath().normalize() }
                ?.toSet()
                ?: WorkspaceFiles
                    .discover(root, include = frontend.include, exclude = frontend.exclude)
                    .map { it.absolutePath.toAbsolutePath().normalize() }
                    .toSet()
        private val files = mutableMapOf<Path, FileFacts>()
        private val opened = mutableListOf<String>()
        private val observations = mutableMapOf<Directed, Int>()
        private val nodes = mutableMapOf(seed.id to seed)

        fun path(uri: String): Path? = filePath(uri)

        fun file(path: Path): FileFacts {
            val normalized = path.toAbsolutePath().normalize()
            require(workspacePaths.contains(normalized)) { "File is outside the workspace or excluded: $normalized" }
            return files.getOrPut(normalized) { load(normalized) }
        }

        fun fileForLocation(location: Location): FileFacts? {
            val path = path(location.uri)?.toAbsolutePath()?.normalize() ?: return null
            if (!workspacePaths.contains(path)) {
                return null
            }
            return file(path)
        }

        fun observe(
            from: Node,
            to: Node,
        ) {
            if (from.id == to.id) {
                return
            }
            observations.merge(Directed(from.id, to.id), 1, Int::plus)
            nodes[from.id] = from
            nodes[to.id] = to
        }

        fun expansionWithFiles(seedNode: Node): ExpansionWithFiles =
            ExpansionWithFiles(
                expansion = expansion(seedNode),
                fileTexts = files.values.associate { it.uri to it.text },
                fileEntities = files.values.associate { file -> file.uri to file.entities.map { it.node } },
            )

        private fun expansion(seedNode: Node): Expansion {
            val edges =
                observations.entries
                    .groupBy { (directed, _) -> EdgeKey.of(EdgeKind.REFERENCES, directed.from, directed.to) }
                    .map { (key, entries) ->
                        val origins =
                            entries
                                .map { (directed, count) -> EdgeOrigin(directed.from, directed.to, count) }
                                .sortedWith(compareBy({ it.from.value }, { it.to.value }))
                        Edge.of(EdgeKind.REFERENCES, key.from, key.to, origins.sumOf { it.count }, origins)
                    }.sortedWith(compareBy({ it.from.value }, { it.to.value }))
            val neighbours =
                edges
                    .flatMap { listOf(it.from, it.to) }
                    .filter { it != seedNode.id }
                    .distinct()
                    .mapNotNull { nodes[it] }
                    .sortedBy { it.id.value }
            return Expansion(graphId, revisionOverride ?: combinedRevision(), seedNode, neighbours, edges)
        }

        fun close() {
            opened.asReversed().forEach { uri ->
                runCatching { session.didClose(uri) }
            }
            opened.clear()
        }

        private fun combinedRevision(): String {
            val hashes = files.values.map { it.revision }.sorted()
            return sha256(hashes.joinToString("\n"))
        }

        private fun load(path: Path): FileFacts {
            val uri = path.toUri().toString()
            val open = session.openDocument(uri)
            val text = open?.text ?: Files.readString(path, StandardCharsets.UTF_8)
            if (open == null) {
                session.didOpen(uri, frontend.languageId, text)
                opened.add(uri)
            }
            val symbols = session.documentSymbol(uri).mapNotNull { it.right }
            val entities = KotlinEntityDiscovery.discoverDetailed(graphId, uri, text, symbols)
            return FileFacts(uri, text, entities)
        }
    }

    private data class FileFacts(
        val uri: String,
        val text: String,
        val entities: List<DiscoveredEntity>,
    ) {
        val revision: String by lazy { sha256(text) }

        fun resolveIncoming(position: Position): DiscoveredEntity? = resolveBySpan(position)

        fun resolveOutgoing(
            location: Location,
            linkSelection: SourceSpan?,
        ): DiscoveredEntity? {
            val range = location.range.toSourceSpan()
            val exact = entities.filter { it.fullSpan == range }
            if (exact.isNotEmpty()) {
                return exact.firstOrNull { it.symbolKind != EntitySymbolKind.CONSTRUCTOR } ?: exact.first()
            }
            val nonConstructors = entities.filter { it.symbolKind != EntitySymbolKind.CONSTRUCTOR }
            val selectionMatch =
                if (linkSelection != null) {
                    nonConstructors.firstOrNull {
                        it.selectionSpan.startPosition == linkSelection.startPosition ||
                            it.referencePosition == linkSelection.startPosition
                    }
                } else {
                    smallestContaining(
                        nonConstructors.filter {
                            range.contains(it.selectionSpan.startPosition) || range.contains(it.referencePosition)
                        },
                        location.range.start,
                    )
                }
            if (selectionMatch != null) {
                return selectionMatch
            }
            val constructors = entities.filter { it.symbolKind == EntitySymbolKind.CONSTRUCTOR }
            val constructorMatch =
                if (linkSelection != null) {
                    constructors.firstOrNull { it.matchesConstructorLink(linkSelection) }
                } else {
                    constructors.firstOrNull { it.matchesConstructorRange(range) }
                }
            if (constructorMatch != null) {
                return constructorMatch
            }
            val containing =
                entities.filter {
                    range.contains(it.selectionSpan.startPosition) || range.contains(it.referencePosition)
                }
            return smallestContaining(containing, location.range.start)
        }

        private fun smallestContaining(
            candidates: List<DiscoveredEntity>,
            position: Position,
        ): DiscoveredEntity? {
            if (candidates.isEmpty()) {
                return null
            }
            val located = candidates.filter { it.fullSpan.contains(position) }
            if (located.isEmpty()) {
                return candidates.maxWithOrNull(compareBy({ it.fullSpan.endLine }, { it.fullSpan.endCharacter }))
            }
            val smallestSpan = located.map { it.fullSpan }.minWithOrNull(SPAN_SIZE_ORDER) ?: return null
            return located.first { SPAN_SIZE_ORDER.compare(it.fullSpan, smallestSpan) == 0 }
        }

        private fun resolveBySpan(position: Position): DiscoveredEntity? {
            val candidates = entities.filter { it.fullSpan.contains(position) }
            if (candidates.isEmpty()) {
                return null
            }
            val smallestSpan = candidates.map { it.fullSpan }.minWithOrNull(SPAN_SIZE_ORDER) ?: return null
            val sameSpan = candidates.filter { SPAN_SIZE_ORDER.compare(it.fullSpan, smallestSpan) == 0 }
            val constructors = sameSpan.filter { it.symbolKind == EntitySymbolKind.CONSTRUCTOR }
            if (constructors.isNotEmpty()) {
                sameSpan
                    .firstOrNull { it.symbolKind != EntitySymbolKind.CONSTRUCTOR && it.referencePosition == position }
                    ?.let { return it }
                return constructors.first()
            }
            return sameSpan.first()
        }

        fun declarationPositionsInside(span: SourceSpan): Set<Position> {
            val result = mutableSetOf<Position>()
            entities.forEach { entity ->
                if (span.contains(entity.fullSpan.startPosition)) {
                    result.add(entity.fullSpan.startPosition)
                }
                if (span.contains(entity.selectionSpan.startPosition)) {
                    result.add(entity.selectionSpan.startPosition)
                }
                if (span.contains(entity.referencePosition)) {
                    result.add(entity.referencePosition)
                }
            }
            return result
        }
    }

    private data class Directed(
        val from: NodeId,
        val to: NodeId,
    )
}

private fun DiscoveredEntity.matchesConstructorRange(range: SourceSpan): Boolean {
    val keyword = constructorKeywordPosition
    if (keyword != null && range.contains(keyword)) {
        return true
    }
    return range == parameterSpan || range == selectionSpan
}

private fun DiscoveredEntity.matchesConstructorLink(selection: SourceSpan): Boolean {
    val keyword = constructorKeywordPosition
    if (keyword != null && keyword == selection.startPosition) {
        return true
    }
    if (selection == selectionSpan) {
        return true
    }
    return parameterSpan == selection
}

private data class DefinitionTarget(
    val location: Location,
    val selectionSpan: SourceSpan?,
)

private data class DecodedToken(
    val start: Position,
    val end: Position,
    val type: String,
    val modifiers: Set<String>,
)

private val EXCLUDED_TOKEN_TYPES =
    setOf(
        "keyword",
        "comment",
        "string",
        "number",
        "regexp",
        "modifier",
    )

private val SPAN_SIZE_ORDER: Comparator<SourceSpan> =
    compareBy<SourceSpan> { it.endLine - it.startLine }
        .thenBy { it.endCharacter - it.startCharacter }

private val SourceSpan.startPosition: Position
    get() = Position(startLine, startCharacter)

private val SourceSpan.endPosition: Position
    get() = Position(endLine, endCharacter)

private fun SourceSpan.contains(position: Position): Boolean =
    comparePositions(position, startPosition) >= 0 && comparePositions(position, endPosition) < 0

private fun SourceSpan.containsToken(
    start: Position,
    end: Position,
): Boolean =
    comparePositions(start, startPosition) >= 0 &&
        comparePositions(end, endPosition) <= 0 &&
        comparePositions(start, end) <= 0

private fun SourceSpan.isEmpty(): Boolean = startLine == endLine && startCharacter == endCharacter

private fun comparePositions(
    first: Position,
    second: Position,
): Int {
    val line = first.line.compareTo(second.line)
    return if (line != 0) line else first.character.compareTo(second.character)
}

private fun Range.toSourceSpan(): SourceSpan =
    SourceSpan(
        startLine = start.line,
        startCharacter = start.character,
        endLine = end.line,
        endCharacter = end.character,
    )

private fun decodeTokens(
    tokens: SemanticTokens,
    legend: SemanticTokensLegend,
): List<DecodedToken> {
    val data = tokens.data ?: return emptyList()
    val result = mutableListOf<DecodedToken>()
    var line = 0
    var character = 0
    var index = 0
    while (index + 4 < data.size) {
        val deltaLine = data[index]
        val deltaStart = data[index + 1]
        val length = data[index + 2]
        val type = legend.tokenTypes.getOrNull(data[index + 3]).orEmpty()
        val modifiers = decodeModifiers(data[index + 4], legend.tokenModifiers)
        line += deltaLine
        character = if (deltaLine == 0) character + deltaStart else deltaStart
        result.add(DecodedToken(Position(line, character), Position(line, character + length), type, modifiers))
        index += 5
    }
    return result
}

private fun decodeModifiers(
    bits: Int,
    names: List<String>?,
): Set<String> {
    if (bits == 0 || names == null) {
        return emptySet()
    }
    val modifiers = mutableSetOf<String>()
    var remaining = bits
    var bit = 0
    while (remaining != 0) {
        if ((remaining and 1) == 1) {
            names.getOrNull(bit)?.let { modifiers.add(it) }
        }
        remaining = remaining ushr 1
        bit++
    }
    return modifiers
}

private fun definitionTargets(result: DefinitionResult): List<DefinitionTarget> {
    val targets = result.locations.map { DefinitionTarget(it, null) }.toMutableList()
    result.locationLinks.forEach { link ->
        val uri = link.targetUri
        val selection = link.targetSelectionRange
        val range = selection ?: link.targetRange
        if (uri != null && range != null) {
            targets.add(DefinitionTarget(Location(uri, range), selection?.toSourceSpan()))
        }
    }
    return targets
}

private fun filePath(uri: String): Path? =
    runCatching {
        val parsed = URI(uri)
        if (!"file".equals(parsed.scheme, ignoreCase = true)) {
            null
        } else {
            Path.of(parsed).toAbsolutePath().normalize()
        }
    }.getOrNull()

private fun sha256(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
