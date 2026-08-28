package example.testrelease.multiplatform

import kotlin.test.Test
import kotlin.test.assertNotNull

class NewApiTest {
    @Test
    fun readsAnApiNewerThanThePublishedBytecode() {
        assertNotNull(java.lang.classfile.ClassFile.of())
        assertNotNull(greeting())
    }
}
