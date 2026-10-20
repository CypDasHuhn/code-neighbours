package dev.codenb.renderer.server

import dev.codenb.renderer.shared.TransportPlaceholder
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {
    @Test
    fun placeholder() {
        assertEquals(1, TransportPlaceholder(1).protocolVersion)
    }
}
