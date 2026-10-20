package dev.codenb.renderer.shared

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class TransportPlaceholderTest {
    @Test
    fun roundTrip() {
        val value = TransportPlaceholder(1)
        val encoded = Json.encodeToString(TransportPlaceholder.serializer(), value)
        val decoded = Json.decodeFromString(TransportPlaceholder.serializer(), encoded)
        assertEquals(value, decoded)
    }
}
