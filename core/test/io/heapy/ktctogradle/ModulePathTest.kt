package io.heapy.ktctogradle

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModulePathTest {
    @Test
    fun windowsDirectoriesStillRenderWithSlashesAndColons() {
        val path = ModulePath.relativize("C:\\proj".toPath(), "C:\\proj\\libs\\messages".toPath())

        assertEquals(listOf("libs", "messages"), path?.segments)
        assertEquals("libs/messages", path?.notation)
        assertEquals(":libs:messages", path?.gradlePath)
    }

    @Test
    fun aDirectoryEqualToTheRootIsTheRootModule() {
        val path = ModulePath.relativize("/proj".toPath(), "/proj".toPath())

        assertTrue(path!!.isRoot)
        assertEquals("", path.notation)
        assertEquals(":", path.gradlePath)
    }

    @Test
    fun aDirectoryOutsideTheRootHasNoModulePath() {
        assertNull(ModulePath.relativize("/proj".toPath(), "/other/libs".toPath()))
    }

    @Test
    fun parseAcceptsToolchainNotationAndMatchesTheRelativizedPath() {
        val relativized = ModulePath.relativize("/proj".toPath(), "/proj/libs/messages".toPath())

        assertEquals(relativized, ModulePath.parse("//libs/messages"))
        assertEquals(relativized, ModulePath.parse("./libs/messages/"))
        assertEquals(relativized, ModulePath.parse("libs/messages"))
    }
}
