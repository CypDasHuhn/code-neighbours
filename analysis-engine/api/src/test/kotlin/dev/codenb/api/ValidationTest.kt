package dev.codenb.api

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ValidationTest {
    @Test
    fun rejectsBlankGraphIds() {
        assertFailsWith<IllegalArgumentException> {
            GraphId("")
        }
        assertFailsWith<IllegalArgumentException> {
            GraphId("   ")
        }
    }

    @Test
    fun rejectsBlankNodeIds() {
        assertFailsWith<IllegalArgumentException> {
            NodeId("")
        }
        assertFailsWith<IllegalArgumentException> {
            NodeId("\t")
        }
    }
}
