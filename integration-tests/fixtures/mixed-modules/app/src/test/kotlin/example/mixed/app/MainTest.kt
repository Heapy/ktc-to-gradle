package example.mixed.app

import kotlin.test.Test
import kotlin.test.assertEquals

class MainTest {
    @Test
    fun readsAnExportedDependencyOfAnotherModule() {
        assertEquals("""{"text":"mixed"}""", report())
        assertEquals("""{"text":"mixed"}""", buffered().readUtf8())
    }
}
