package dev.codenb.server

import dev.codenb.api.GroupingKind
import dev.codenb.frontend.KotlinFrontend
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EngineConfigTest {
    @Test
    fun defaultsMatchTheKotlinFrontend() {
        val config = EngineConfigLoader.defaults()
        assertEquals("kotlin", config.lsp.id)
        assertEquals(KotlinFrontend.descriptor.defaultCommand, config.lsp.command)
        assertEquals(KotlinFrontend.descriptor.rootMarkers, config.lsp.rootMarkers)
        assertEquals(KotlinFrontend.descriptor.include, config.workspace.include)
        assertEquals(GroupingKind.NAMESPACE, config.graph.grouping)
        assertEquals(false, config.graph.followSymlinks)
    }

    @Test
    fun loadsYamlConfig() {
        val path =
            writeConfig(
                """
                lsp:
                  id: kotlin
                  command: ["custom-lsp", "--stdio"]
                  rootMarkers: [".git"]
                workspace:
                  include: ["**/*.kt"]
                  exclude: ["build/**"]
                graph:
                  grouping: directory
                  followSymlinks: true
                  cacheDir: /tmp/cache
                """.trimIndent(),
            )
        try {
            val config = EngineConfigLoader.load(path)
            assertEquals(listOf("custom-lsp", "--stdio"), config.lsp.command)
            assertEquals(listOf(".git"), config.lsp.rootMarkers)
            assertEquals(listOf("**/*.kt"), config.workspace.include)
            assertEquals(listOf("build/**"), config.workspace.exclude)
            assertEquals(GroupingKind.DIRECTORY, config.graph.grouping)
            assertTrue(config.graph.followSymlinks)
            assertEquals("/tmp/cache", config.graph.cacheDir)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun missingFileIsRejected() {
        val exception = assertFailsWith<ConfigException> { EngineConfigLoader.load(Path.of("/tmp/codenb-missing-config.yml")) }
        assertTrue(exception.message.orEmpty().contains("not found"))
    }

    @Test
    fun malformedYamlIsRejected() {
        val path = writeConfig("lsp: [")
        try {
            val exception = assertFailsWith<ConfigException> { EngineConfigLoader.load(path) }
            assertTrue(exception.message.orEmpty().contains("Invalid engine config"))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun unknownGroupingIsRejected() {
        val path = writeConfig("graph:\n  grouping: module\n")
        try {
            val exception = assertFailsWith<ConfigException> { EngineConfigLoader.load(path) }
            assertTrue(exception.message.orEmpty().contains("namespace"))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun emptyCommandIsRejected() {
        val path = writeConfig("lsp:\n  command: []\n")
        try {
            val exception = assertFailsWith<ConfigException> { EngineConfigLoader.load(path) }
            assertTrue(exception.message.orEmpty().contains("lsp.command"))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun unknownSectionIsRejected() {
        val path = writeConfig("workspac:\n  exclude: []\n")
        try {
            val exception = assertFailsWith<ConfigException> { EngineConfigLoader.load(path) }
            assertTrue(exception.message.orEmpty().contains("Unknown config key"))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun unknownPropertyIsRejected() {
        val path = writeConfig("workspace:\n  exlude: []\n")
        try {
            val exception = assertFailsWith<ConfigException> { EngineConfigLoader.load(path) }
            assertTrue(exception.message.orEmpty().contains("Unknown workspace key"))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun writeConfig(text: String): Path {
        val path = Files.createTempFile(Path.of("/tmp"), "codenb-config", ".yml")
        Files.writeString(path, text)
        return path
    }
}
