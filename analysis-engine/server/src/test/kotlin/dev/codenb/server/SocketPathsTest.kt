package dev.codenb.server

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SocketPathsTest {
    @Test
    fun derivesStablePathFromRootAndLsp() {
        val root = Path.of("/tmp/workspace")
        val first = SocketPaths.derive(root, "kotlin", "/run/user/1000", "1000")
        val second = SocketPaths.derive(root, "kotlin", "/run/user/1000", "1000")
        assertEquals(first, second)
        assertEquals("/run/user/1000/codenb-1000", first.parent.toString())
        assertTrue(first.fileName.toString().endsWith(".sock"))
        assertTrue(first.toString().toByteArray().size < 100)
    }

    @Test
    fun derivesDifferentPathsForDifferentRootsAndLsps() {
        val root = Path.of("/tmp/workspace")
        assertNotEquals(
            SocketPaths.derive(root, "kotlin", "/tmp", "1000"),
            SocketPaths.derive(Path.of("/tmp/other"), "kotlin", "/tmp", "1000"),
        )
        assertNotEquals(
            SocketPaths.derive(root, "kotlin", "/tmp", "1000"),
            SocketPaths.derive(root, "typescript", "/tmp", "1000"),
        )
    }

    @Test
    fun fallsBackToTmpWithoutRuntimeDirectory() {
        val path = SocketPaths.derive(Path.of("/tmp/workspace"), "kotlin", null, "1000")
        assertEquals(Path.of("/tmp/codenb-1000"), path.parent)
    }

    @Test
    fun rejectsPathsLongerThanTheUnixLimit() {
        val runtimeDirectory = "/tmp/" + "x".repeat(120)
        val exception =
            assertFailsWith<ConfigException> {
                SocketPaths.derive(Path.of("/tmp/workspace"), "kotlin", runtimeDirectory, "1000")
            }
        assertTrue(exception.message.orEmpty().contains("too long"))
    }

    @Test
    fun currentUidIsNotBlank() {
        assertTrue(SocketPaths.currentUid().isNotBlank())
    }
}
