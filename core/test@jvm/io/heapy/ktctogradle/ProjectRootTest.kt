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

    @Test
    fun dotSlashModulePathsSelectTheirModules() {
        val outer = Files.createTempDirectory("ktc-to-gradle-dotslash-")
        write(outer.resolve("project.yaml"), "modules:\n  - ./app\n  - ./libs/shared\n")
        write(outer.resolve("app/module.yaml"), "product: jvm/app\ndependencies:\n  - ./../libs/shared\n")
        write(outer.resolve("app/src/main.kt"), "fun main() = Unit\n")
        write(outer.resolve("libs/shared/module.yaml"), "product: jvm/lib\n")

        val project = load(outer)

        assertEquals(listOf("app", "libs/shared"), project.modules.map { it.path.notation }.sorted())
    }

    @Test
    fun aTrailingSlashInAModulePathStillSelects() {
        val outer = Files.createTempDirectory("ktc-to-gradle-trailing-")
        write(outer.resolve("project.yaml"), "modules: [./core/]\n")
        write(outer.resolve("core/module.yaml"), "product: jvm/lib\n")

        assertEquals(listOf("core"), load(outer).modules.map { it.path.notation })
    }

    private fun load(start: Path) = ProjectLoader(FileSystem.SYSTEM).load(start.toString().toPath())

    private fun canonical(path: Path) = FileSystem.SYSTEM.canonicalize(path.toString().toPath())

    private fun write(path: Path, content: String) {
        path.parent.createDirectories()
        path.writeText(content)
    }
}
