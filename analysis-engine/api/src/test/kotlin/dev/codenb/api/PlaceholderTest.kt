package dev.codenb.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {
    @Test
    fun roundTrip() {
        val value = Placeholder("api")
        val encoded = Json.encodeToString(Placeholder.serializer(), value)
        val decoded = Json.decodeFromString(Placeholder.serializer(), encoded)
        assertEquals(value, decoded)
    }
}
