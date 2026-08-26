package example.multiplatform

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmGreetingTest {
    @Test
    fun greetsFromJvmActual() {
        assertEquals("Hello from JVM, Gradle", platformGreeting("Gradle"))
    }
}
