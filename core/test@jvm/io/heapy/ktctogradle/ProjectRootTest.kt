package io.heapy.ktctogradle

import io.heapy.ktctogradle.load.ProjectLoader
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okio.Path as OkioPath

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

    @Test
    fun aGlobInModulesSelectsEveryMatchingModule() {
        val outer = Files.createTempDirectory("ktc-to-gradle-glob-")
        write(outer.resolve("project.yaml"), "modules: [libs/*]\n")
        write(outer.resolve("libs/one/module.yaml"), "product: jvm/lib\n")
        write(outer.resolve("libs/two/module.yaml"), "product: jvm/lib\n")
        write(outer.resolve("tools/skipped/module.yaml"), "product: jvm/lib\n")

        assertEquals(listOf("libs/one", "libs/two"), load(outer).modules.map { it.path.notation }.sorted())
    }

    @Test
    fun aRecursiveModuleGlobIsRejected() {
        val outer = Files.createTempDirectory("ktc-to-gradle-recursive-")
        write(outer.resolve("project.yaml"), "modules: [//libs/**/*]\n")
        write(outer.resolve("libs/one/module.yaml"), "product: jvm/lib\n")

        val error = assertFailsWith<ConversionException> { load(outer) }

        assertEquals(
            "project.yaml module glob 'libs/**/*' uses unsupported recursive ** syntax",
            error.message,
        )
    }

    @Test
    fun aProjectYamlThatSelectsNoModuleIsRejected() {
        val outer = Files.createTempDirectory("ktc-to-gradle-empty-")
        write(outer.resolve("project.yaml"), "modules: [absent]\n")
        write(outer.resolve("libs/one/module.yaml"), "product: jvm/lib\n")

        val error = assertFailsWith<ConversionException> { load(outer) }

        assertEquals(
            "No modules found in ${canonical(outer)}: it has no module.yaml, and project.yaml selects none",
            error.message,
        )
    }

    @Test
    fun aProjectYamlListingNoModulesNamesTheRootItLookedIn() {
        val outer = Files.createTempDirectory("ktc-to-gradle-no-modules-")
        write(outer.resolve("project.yaml"), "modules: []\n")

        val error = assertFailsWith<ConversionException> { load(outer) }

        assertEquals(
            "No modules found in ${canonical(outer)}: it has no module.yaml, and project.yaml selects none",
            error.message,
        )
    }

    @Test
    fun aModuleYamlThatIsNotARegularFileIsRejectedByName() {
        val outer = Files.createTempDirectory("ktc-to-gradle-module-dir-")
        outer.resolve("module.yaml").createDirectories()

        val error = assertFailsWith<ConversionException> { load(outer) }

        assertEquals(
            "Module file ${canonical(outer) / "module.yaml"} is not a regular file",
            error.message,
        )
    }

    @Test
    fun aSymlinkedModuleYamlStillLoads() {
        val outer = Files.createTempDirectory("ktc-to-gradle-module-link-")
        write(outer.resolve("elsewhere/module.yaml"), "product: jvm/lib\n")
        Files.createSymbolicLink(outer.resolve("module.yaml"), outer.resolve("elsewhere/module.yaml"))

        assertEquals(listOf(""), load(outer).modules.map { it.path.notation })
    }

    @Test
    fun withoutAProjectYamlOnlyTheRootModuleIsLoaded() {
        val outer = Files.createTempDirectory("ktc-to-gradle-no-project-")
        write(outer.resolve("module.yaml"), "product: jvm/lib\n")
        write(outer.resolve("samples/demo/module.yaml"), "product: jvm/app\n")
        write(outer.resolve("samples/demo/src/main.kt"), "fun main() = Unit\n")

        val project = load(outer)

        assertEquals(listOf(""), project.modules.map { it.path.notation })
    }

    @Test
    fun withoutAProjectYamlANestedModuleConvertsAsItsOwnProject() {
        val outer = Files.createTempDirectory("ktc-to-gradle-nested-start-")
        write(outer.resolve("module.yaml"), "product: jvm/lib\n")
        val nested = outer.resolve("samples/demo")
        write(nested.resolve("module.yaml"), "product: jvm/app\n")
        write(nested.resolve("src/main.kt"), "fun main() = Unit\n")

        val project = load(nested)

        assertEquals(canonical(nested), project.root)
        assertEquals(listOf(""), project.modules.map { it.path.notation })
    }

    @Test
    fun withoutAProjectYamlNoDirectoryBelowTheModuleIsListed() {
        val outer = Files.createTempDirectory("ktc-to-gradle-no-walk-")
        write(outer.resolve("module.yaml"), "product: jvm/lib\n")
        write(outer.resolve("samples/demo/module.yaml"), "product: jvm/app\n")
        val root = canonical(outer)
        val listed = mutableListOf<OkioPath>()
        val recording = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun list(dir: OkioPath): List<OkioPath> {
                listed += dir
                return super.list(dir)
            }
        }

        ProjectLoader(recording).load(root)

        assertEquals(emptyList(), listed.filter { it != root })
    }

    @Test
    fun anEmptyProjectYamlLoadsTheRootModule() {
        val outer = Files.createTempDirectory("ktc-to-gradle-empty-doc-")
        write(outer.resolve("project.yaml"), "")
        write(outer.resolve("module.yaml"), "product: jvm/lib\n")
        write(outer.resolve("samples/demo/module.yaml"), "product: jvm/app\n")
        write(outer.resolve("samples/demo/src/main.kt"), "fun main() = Unit\n")

        val project = load(outer)

        assertEquals(canonical(outer), project.root)
        assertEquals(listOf(""), project.modules.map { it.path.notation })
    }

    @Test
    fun aCommentOnlyProjectYamlLoadsTheRootModule() {
        val outer = Files.createTempDirectory("ktc-to-gradle-comment-doc-")
        write(outer.resolve("project.yaml"), "# nothing here yet\n")
        write(outer.resolve("module.yaml"), "product: jvm/lib\n")

        assertEquals(listOf(""), load(outer).modules.map { it.path.notation })
    }

    @Test
    fun anEmptyAncestorProjectYamlDoesNotBecomeTheRoot() {
        val outer = Files.createTempDirectory("ktc-to-gradle-empty-ancestor-")
        write(outer.resolve("project.yaml"), "")
        val module = outer.resolve("work/lib")
        write(module.resolve("module.yaml"), "product: jvm/lib\n")

        val project = load(module)

        assertEquals(canonical(module), project.root)
        assertEquals(listOf(""), project.modules.map { it.path.notation })
    }

    @Test
    fun aNullProjectYamlStillFails() {
        val outer = Files.createTempDirectory("ktc-to-gradle-null-doc-")
        write(outer.resolve("project.yaml"), "null\n")
        write(outer.resolve("module.yaml"), "product: jvm/lib\n")

        val error = assertFailsWith<ConversionException> { load(outer) }

        assertTrue(error.message!!.startsWith("Expected an object at "), error.message)
    }

    @Test
    fun anUnparseableProjectYamlStillFails() {
        val outer = Files.createTempDirectory("ktc-to-gradle-broken-doc-")
        write(outer.resolve("project.yaml"), "modules: [unclosed\n")
        write(outer.resolve("module.yaml"), "product: jvm/lib\n")

        val error = assertFailsWith<ConversionException> { load(outer) }

        assertTrue(error.message!!.startsWith("Cannot parse "), error.message)
    }

    private fun load(start: Path) = ProjectLoader(FileSystem.SYSTEM).load(start.toString().toPath())

    private fun canonical(path: Path) = FileSystem.SYSTEM.canonicalize(path.toString().toPath())

    private fun write(path: Path, content: String) {
        path.parent.createDirectories()
        path.writeText(content)
    }
}
