package dev.codenb.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NodeContractTest {
    private val entityId = NodeId("graph/entities/app.Main")
    private val fileId = NodeId("graph/files/app/Main.kt")
    private val groupId = NodeId("graph/groups/app")

    @Test
    fun entityRequiresFileUriQualifiedNameAndFingerprint() {
        assertFailsWith<IllegalArgumentException> {
            Node(
                id = entityId,
                kind = NodeKind.ENTITY,
                label = "Main",
                qualifiedName = "app.Main",
                signatureFingerprint = "fun Main()",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Node(
                id = entityId,
                kind = NodeKind.ENTITY,
                label = "Main",
                fileUri = "app/Main.kt",
                signatureFingerprint = "fun Main()",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Node(
                id = entityId,
                kind = NodeKind.ENTITY,
                label = "Main",
                qualifiedName = "app.Main",
                fileUri = "app/Main.kt",
            )
        }
    }

    @Test
    fun acceptsCompleteEntity() {
        val node =
            Node(
                id = entityId,
                kind = NodeKind.ENTITY,
                label = "Main",
                qualifiedName = "app.Main",
                fileUri = "app/Main.kt",
                signatureFingerprint = "fun Main()",
                parent = fileId,
            )

        assertEquals("app/Main.kt", node.fileUri)
        assertEquals("fun Main()", node.signatureFingerprint)
    }

    @Test
    fun fileRequiresFileUri() {
        assertFailsWith<IllegalArgumentException> {
            Node(fileId, NodeKind.FILE, "Main.kt")
        }
        assertEquals("app/Main.kt", Node(fileId, NodeKind.FILE, "Main.kt", fileUri = "app/Main.kt").fileUri)
    }

    @Test
    fun groupMayOmitFileAndFingerprint() {
        val node = Node(groupId, NodeKind.GROUP, "app")

        assertEquals(null, node.fileUri)
        assertEquals(null, node.signatureFingerprint)
    }

    @Test
    fun rejectsSelfParent() {
        assertFailsWith<IllegalArgumentException> {
            Node(
                id = entityId,
                kind = NodeKind.ENTITY,
                label = "Main",
                qualifiedName = "app.Main",
                fileUri = "app/Main.kt",
                signatureFingerprint = "fun Main()",
                parent = entityId,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            Node(fileId, NodeKind.FILE, "Main.kt", fileUri = "app/Main.kt", parent = fileId)
        }
        assertFailsWith<IllegalArgumentException> {
            Node(groupId, NodeKind.GROUP, "app", parent = groupId)
        }
    }
}
