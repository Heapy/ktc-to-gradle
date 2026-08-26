package example.messages

import kotlin.test.Test
import kotlin.test.assertEquals

class MessagesTest {
    @Test
    fun createsMessage() {
        assertEquals("Welcome, converter", messageFor("converter"))
    }
}

