package dev.codenb.frontend

import dev.codenb.api.GraphId
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class NeighbourExpanderFilesTest {
    private val graphId = GraphId("graph")

    @Test
    fun expandWithFilesCarriesOpenDocumentTextAndExpandDelegates() {
        val root = Files.createTempDirectory("neighbour-expander-files")
        try {
            val diskSource = "package disk.app\n\nfun seed(): Int = 1"
            val openSource = "package open.app\n\nfun seed(): Int = 1"
            val path = root.resolve("A.kt")
            Files.writeString(path, diskSource, StandardCharsets.UTF_8)
            val uri = path.toUri().toString()
            val symbols = listOf(documentSymbol(openSource, "seed", SymbolKind.Function))
            val session = FakeLspSession()
            session.addSymbols(uri, symbols)
            session.didOpen(uri, "kotlin", openSource)
            val seed = KotlinEntityDiscovery.discover(graphId, uri, openSource, symbols).single()
            val expander = NeighbourExpander(session)

            val withFiles = expander.expandWithFiles(graphId, seed, root, KotlinFrontend.descriptor)
            val expansion = expander.expand(graphId, seed, root, KotlinFrontend.descriptor)

            assertEquals(openSource, withFiles.fileTexts[uri])
            assertEquals(withFiles.expansion, expansion)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun documentSymbol(
        source: String,
        name: String,
        kind: SymbolKind,
    ): DocumentSymbol {
        val start = source.indexOf(name)
        val end = source.indexOf('\n', start).let { if (it < 0) source.length else it }
        return DocumentSymbol(
            name,
            kind,
            rangeAt(source, start, end - start),
            rangeAt(source, start, name.length),
            null,
            emptyList(),
        )
    }

    private fun rangeAt(
        source: String,
        index: Int,
        length: Int,
    ): Range = Range(positionAt(source, index), positionAt(source, index + length))

    private fun positionAt(
        source: String,
        index: Int,
    ): Position {
        val prefix = source.substring(0, index)
        val line = prefix.count { it == '\n' }
        val lineStart = prefix.lastIndexOf('\n') + 1
        return Position(line, index - lineStart)
    }
}
