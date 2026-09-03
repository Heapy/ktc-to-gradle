package example.mixed

import kotlin.test.Test
import kotlin.test.assertEquals

class RootTest {
    @Test
    fun rootModuleCompilesAndRuns() {
        assertEquals("mixed-modules", PROJECT_NAME)
    }
}
