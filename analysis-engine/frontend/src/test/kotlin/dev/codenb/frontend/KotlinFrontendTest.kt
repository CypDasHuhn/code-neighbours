package dev.codenb.frontend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinFrontendTest {
    @Test
    fun describesKotlinFilesAndCommand() {
        val kotlin = KotlinFrontend.descriptor
        assertEquals("kotlin", kotlin.id)
        assertEquals("kotlin", kotlin.languageId)
        assertEquals(setOf(".kt", ".kts"), kotlin.extensions)
        assertEquals(listOf("kotlin-lsp", "--stdio"), kotlin.defaultCommand)
        assertEquals(GroupingMode.NAMESPACE, kotlin.grouping)
        assertTrue(kotlin.handles("Main.kt"))
        assertTrue(kotlin.handles("build.gradle.kts"))
        assertFalse(kotlin.handles("Main.java"))
        assertEquals(kotlin, FrontendRegistry.byId("kotlin"))
        assertEquals(kotlin, FrontendRegistry.forFileName("Main.kt"))
        assertNull(FrontendRegistry.byId("unknown"))
        assertNull(FrontendRegistry.forFileName("Main.java"))
    }
}
