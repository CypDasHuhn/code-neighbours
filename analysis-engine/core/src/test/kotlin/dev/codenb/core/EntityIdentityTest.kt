package dev.codenb.core

import dev.codenb.api.GraphId
import dev.codenb.api.NodeId
import dev.codenb.api.NodeKind
import dev.codenb.api.SourceSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntityIdentityTest {
    private val graphId = GraphId("graph")
    private val span = SourceSpan(2, 0, 4, 1)

    private fun descriptor(
        signatureFingerprint: String = "class Widget",
        unstable: Boolean = false,
    ) = EntityDescriptor(
        graphId = graphId,
        fileUri = "file:///src/App.kt",
        label = "Widget",
        qualifiedName = "app.Widget",
        symbolKind = EntitySymbolKind.CLASS,
        signatureFingerprint = signatureFingerprint,
        span = span,
        unstable = unstable,
    )

    @Test
    fun buildsIdFromTheIdentityTuple() {
        val id = EntityIdentity.id(descriptor())

        assertEquals(
            NodeId("graph/file:///src/App.kt/app.Widget/CLASS/class Widget"),
            id,
        )
    }

    @Test
    fun buildsEntityNodeFromDescriptor() {
        val node = EntityIdentity.node(descriptor(unstable = true))

        assertEquals(NodeKind.ENTITY, node.kind)
        assertEquals("Widget", node.label)
        assertEquals("app.Widget", node.qualifiedName)
        assertEquals("file:///src/App.kt", node.fileUri)
        assertEquals("class Widget", node.signatureFingerprint)
        assertEquals(span, node.span)
        assertEquals(null, node.parent)
        assertTrue(node.unstable)
    }

    @Test
    fun keepsDistinctDescriptorsDistinct() {
        val nodes =
            EntityIdentity.nodes(
                listOf(
                    descriptor(),
                    descriptor(signatureFingerprint = "class Widget(val name: String)"),
                ),
            )

        assertEquals(2, nodes.size)
        assertEquals(2, nodes.map { it.id }.distinct().size)
        assertTrue(nodes.none { it.unstable })
    }

    @Test
    fun marksDuplicateIdsUnstableAndDisambiguatesThem() {
        val nodes = EntityIdentity.nodes(listOf(descriptor(), descriptor()))

        assertEquals(2, nodes.size)
        assertEquals(2, nodes.map { it.id }.distinct().size)
        assertEquals(listOf("class Widget", "class Widget#0"), nodes.map { it.signatureFingerprint })
        assertTrue(nodes.all { it.unstable })
    }

    @Test
    fun disambiguationSkipsSuffixesThatCollideWithOtherDescriptors() {
        val nodes =
            EntityIdentity.nodes(
                listOf(
                    descriptor(signatureFingerprint = "x"),
                    descriptor(signatureFingerprint = "x"),
                    descriptor(signatureFingerprint = "x#0"),
                ),
            )

        assertEquals(3, nodes.size)
        assertEquals(3, nodes.map { it.id }.distinct().size)
        assertEquals(listOf("x", "x#1", "x#0"), nodes.map { it.signatureFingerprint })
        assertTrue(nodes[0].unstable)
        assertTrue(nodes[1].unstable)
        assertTrue(!nodes[2].unstable)
    }
}
