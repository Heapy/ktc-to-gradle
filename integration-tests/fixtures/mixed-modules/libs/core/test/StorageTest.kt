package example.mixed.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StorageTest {
    @Test
    fun storesTextInABuffer() {
        assertEquals("hello", store("hello").readUtf8())
    }
}
