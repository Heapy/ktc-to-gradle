package io.heapy.ktctogradle.fixture

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the source-set name: Gradle runs android unit tests from androidHostTest,
 * so a test parked in androidTest would silently never run.
 */
class AndroidOnlyTest {
    @Test
    fun runsOnAndroid() {
        assertEquals("android", platformName())
    }
}
