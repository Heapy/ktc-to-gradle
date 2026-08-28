package example.multiplatform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// A JUnit 5 annotation, so this class is discovered only when the jvm test task runs the JUnit
// platform. The kotlin.test classes beside it run under JUnit 4 just as happily.
class JupiterOnlyTest {
    @Test
    fun runsOnTheJUnitPlatform() {
        assertEquals("Hello from JVM, Gradle", platformGreeting("Gradle"))
    }
}
