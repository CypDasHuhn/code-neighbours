package dev.codenb.server

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonProcessTest {
    @Test
    fun sigtermRemovesTheSocket() {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "codenb-process")
        val socket = directory.resolve("engine.sock")
        val classpath =
            System.getProperty("codenb.testClasspath")
                ?: error("codenb.testClasspath is not set")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process =
            ProcessBuilder(
                java,
                "-cp",
                classpath,
                "dev.codenb.server.MainKt",
                "--root",
                directory.toString(),
                "--socket",
                socket.toString(),
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        try {
            awaitSocket(socket)
            process.destroy()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertFalse(Files.exists(socket))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            deleteTree(directory)
        }
    }
}
