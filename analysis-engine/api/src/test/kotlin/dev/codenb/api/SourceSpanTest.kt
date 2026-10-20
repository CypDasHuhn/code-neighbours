package dev.codenb.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourceSpanTest {
    @Test
    fun acceptsOrderedNonNegativeCoordinates() {
        assertEquals(SourceSpan(0, 0, 0, 0), SourceSpan(0, 0, 0, 0))
        assertEquals(SourceSpan(1, 2, 3, 4), SourceSpan(1, 2, 3, 4))
        assertEquals(SourceSpan(1, 4, 1, 4), SourceSpan(1, 4, 1, 4))
    }

    @Test
    fun rejectsNegativeCoordinates() {
        assertFailsWith<IllegalArgumentException> {
            SourceSpan(-1, 0, 0, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SourceSpan(0, -1, 0, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SourceSpan(0, 0, -1, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SourceSpan(0, 0, 0, -1)
        }
    }

    @Test
    fun rejectsEndBeforeStart() {
        assertFailsWith<IllegalArgumentException> {
            SourceSpan(2, 0, 1, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            SourceSpan(1, 5, 1, 4)
        }
    }
}
