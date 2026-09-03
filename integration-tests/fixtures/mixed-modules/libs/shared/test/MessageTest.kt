package example.mixed.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageTest {
    @Test
    fun serializesAndReadsBackAMessage() {
        assertEquals(Message("hi"), decode(encode(Message("hi"))))
    }

    @Test
    fun theAliasSourceSetReachesEveryPlatform() {
        assertEquals("desktop", DESKTOP_MARKER)
        assertTrue(platformName.isNotEmpty())
    }
}
