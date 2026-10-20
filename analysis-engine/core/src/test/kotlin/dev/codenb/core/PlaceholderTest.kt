package dev.codenb.core

import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceholderTest {
    @Test
    fun loadsYaml() {
        val value = Yaml().load<Map<String, String>>("key: value")
        assertEquals("value", value["key"])
    }
}
