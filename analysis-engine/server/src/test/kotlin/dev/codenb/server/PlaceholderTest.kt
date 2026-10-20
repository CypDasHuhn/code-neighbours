package dev.codenb.server

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {
    @Test
    fun parsesJson() {
        val element = Json.parseToJsonElement("""{"key":"value"}""")
        assertEquals("""{"key":"value"}""", element.toString())
    }
}
