package io.heapy.ktctogradle.fixture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// A JUnit 5 annotation, so this class is discovered only when the test task runs the JUnit platform.
class JupiterOnlyTest {
    @Test
    fun runsOnTheJUnitPlatform() {
        assertEquals("android", platformName())
    }
}
