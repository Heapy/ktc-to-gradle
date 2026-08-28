package example.android

import kotlin.test.Test
import kotlin.test.assertEquals

// A JUnit 5 test in an android/app module: without testOptions.unitTests.all { useJUnitPlatform() }
// the Android Gradle Plugin discovers nothing here.
class PayloadTest {
    @Test
    fun theEncodedPayloadKeepsItsMessage() {
        assertEquals("""{"message":"converted"}""", encodedPayload)
    }
}
