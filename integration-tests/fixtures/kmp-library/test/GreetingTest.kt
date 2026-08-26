package example.multiplatform

import kotlin.test.Test
import kotlin.test.assertEquals

class GreetingTest {
    @Test
    fun greetsFromCommonCode() {
        assertEquals("Hello from ${platformName()}, Gradle", platformGreeting("Gradle"))
    }
}
