package example.junit5

import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinTestTest {
    @Test
    fun mustBeDiscovered() {
        assertEquals("hello", greeting())
    }
}
