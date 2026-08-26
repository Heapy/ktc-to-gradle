package io.heapy.ktctogradle

import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectRootTest {
    @Test
    fun anUnrelatedProjectYamlAboveDoesNotBecomeTheRoot() {
        val outer = Files.createTempDirectory("ktc-to-gradle-outer-")
        write(outer.resolve("project.yaml"), "modules: [other]\n")
        write(outer.resolve("other/module.yaml"), "product: jvm/lib\n")
        val module = outer.resolve("work/mylib")
        write(module.resolve("module.yaml"), "product: jvm/lib\n")

        assertEquals(canonical(module), load(module).root)
    }

    @Test
    fun aProjectYamlListingTheModuleStaysTheRoot() {
        val outer = Files.createTempDirectory("ktc-to-gradle-listed-")
        write(outer.resolve("project.yaml"), "modules: [core]\n")
        val module = outer.resolve("core")
        write(module.resolve("module.yaml"), "product: jvm/lib\n")

        assertEquals(canonical(outer), load(module).root)
    }

    private fun load(start: Path) = ProjectLoader(FileSystem.SYSTEM).load(start.toString().toPath())

    private fun canonical(path: Path) = FileSystem.SYSTEM.canonicalize(path.toString().toPath())

    private fun write(path: Path, content: String) {
        path.parent.createDirectories()
        path.writeText(content)
    }
}
