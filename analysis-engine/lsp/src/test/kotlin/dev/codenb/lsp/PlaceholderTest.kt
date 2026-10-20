package dev.codenb.lsp

import org.eclipse.lsp4j.Position
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {
    @Test
    fun createsPosition() {
        assertEquals(3, Position(3, 7).line)
    }
}
