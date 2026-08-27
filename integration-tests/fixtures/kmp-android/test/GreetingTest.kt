package io.heapy.ktctogradle.fixture

import kotlin.test.Test
import kotlin.test.assertTrue

class GreetingTest {
    @Test
    fun greets() {
        assertTrue(greeting().startsWith("hello from"))
    }
}
