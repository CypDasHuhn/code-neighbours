package dev.codenb.lsp

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LspProcessTest {
    @Test
    fun startsRealStdioProcessAndCleansItUp() {
        val root = Files.createTempDirectory("lsp-process")
        val client = LspClient.start(fakeServerCommand(), root, Duration.ofSeconds(10))
        try {
            client.initialize(root)
            val uri = root.resolve("Process.kt").toUri().toString()
            val symbols = client.documentSymbol(uri)
            assertTrue(symbols.isNotEmpty())
            assertTrue(client.isProcessAlive())
            assertNotNull(client.processId())
        } finally {
            client.close()
            root.toFile().deleteRecursively()
        }
        assertTrue(!client.isProcessAlive())
    }

    @Test
    fun detectsServerExitWhileRequestIsPending() {
        val root = Files.createTempDirectory("lsp-process-exit")
        val client = LspClient.start(fakeServerCommand("--exit-on-document-symbol"), root, Duration.ofSeconds(10))
        try {
            client.initialize(root)
            val uri = root.resolve("Exit.kt").toUri().toString()
            val started = System.nanoTime()
            assertFailsWith<LspTransportException> {
                client.documentSymbol(uri)
            }
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertTrue(elapsed < Duration.ofSeconds(2), "transport failure took $elapsed")
        } finally {
            client.close()
            root.toFile().deleteRecursively()
        }
        assertTrue(!client.isProcessAlive())
    }

    @Test
    fun interruptedCloseReleasesTheGateAndAllowsAnotherClose() {
        val root = Files.createTempDirectory("lsp-interrupted-close")
        val client =
            LspClient.start(
                listOf("/bin/sh", "-c", "trap '' TERM; exec sleep 10"),
                root,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
            )
        try {
            val closer = Thread { client.close() }
            closer.start()
            Thread.sleep(200)
            closer.interrupt()
            closer.join(3_000)
            assertFalse(closer.isAlive)
            val started = System.nanoTime()
            client.close()
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertTrue(elapsed < Duration.ofSeconds(2), "second close took $elapsed")
        } finally {
            client.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun reportsFailedProcessStartAsTransportFailure() {
        val root = Files.createTempDirectory("lsp-process-failure")
        try {
            assertFailsWith<LspTransportException> {
                LspClient.start(listOf(root.resolve("missing-lsp").toString()), root)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun fakeServerCommand(vararg arguments: String): List<String> {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath =
            System.getProperty("codenb.testClasspath")
                ?: System.getProperty("java.class.path")
                ?: error("Test classpath is unavailable")
        return listOf(java, "-cp", classpath, "dev.codenb.lsp.FakeLspServerMainKt") + arguments
    }
}
