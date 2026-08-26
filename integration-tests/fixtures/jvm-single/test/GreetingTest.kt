package example.single

import kotlin.test.Test
import kotlin.test.assertEquals

class GreetingTest {
    @Test
    fun greetsGradle() {
        assertEquals("Hello, Gradle!", greeting("Gradle"))
    }
}

