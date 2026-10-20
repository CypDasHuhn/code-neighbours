package dev.codenb.server

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliTest {
    private val cwd = Path.of("/tmp/workspace")

    @Test
    fun defaultsToCurrentDirectory() {
        val options = CliParser.parse(emptyArray(), cwd)
        assertEquals(cwd.toAbsolutePath().normalize(), options.root)
        assertNull(options.lspId)
        assertNull(options.socket)
        assertNull(options.config)
    }

    @Test
    fun parsesFlagsInBothForms() {
        val options =
            CliParser.parse(
                arrayOf(
                    "--root",
                    "src",
                    "--lsp=kotlin",
                    "--socket=/tmp/engine.sock",
                    "--config",
                    "engine.yml",
                ),
                cwd,
            )
        assertEquals(cwd.resolve("src").toAbsolutePath().normalize(), options.root)
        assertEquals("kotlin", options.lspId)
        assertEquals(Path.of("/tmp/engine.sock"), options.socket)
        assertEquals(cwd.resolve("engine.yml").toAbsolutePath().normalize(), options.config)
    }

    @Test
    fun helpIsRecognised() {
        assertTrue(CliParser.parse(arrayOf("--help"), cwd).help)
    }

    @Test
    fun rejectsUnknownArguments() {
        assertFailsWith<CliException> { CliParser.parse(arrayOf("--nope"), cwd) }
    }

    @Test
    fun rejectsMissingValues() {
        assertFailsWith<CliException> { CliParser.parse(arrayOf("--root"), cwd) }
        assertFailsWith<CliException> { CliParser.parse(arrayOf("--root", "--lsp", "kotlin"), cwd) }
        assertFailsWith<CliException> { CliParser.parse(arrayOf("--config="), cwd) }
    }
}
