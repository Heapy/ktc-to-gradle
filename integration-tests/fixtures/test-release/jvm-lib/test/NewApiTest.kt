package example.testrelease

import kotlin.test.Test
import kotlin.test.assertNotNull

// java.lang.classfile is a JDK 24 API: it does not resolve when the test compilation inherits the
// module's release of 21, so this file only compiles when test-settings.jvm.release reached it.
class NewApiTest {
    @Test
    fun readsAnApiNewerThanThePublishedBytecode() {
        assertNotNull(java.lang.classfile.ClassFile.of())
        assertNotNull(greeting())
    }
}
