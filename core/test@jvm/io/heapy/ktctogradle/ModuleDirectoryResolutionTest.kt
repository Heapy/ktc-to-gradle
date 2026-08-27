package io.heapy.ktctogradle

import io.heapy.ktctogradle.load.ModuleLayout
import okio.FileSystem
import okio.Path as OkioPath
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `./` and `../` dependencies are resolved against module directories, and a module directory can be
 * reached through a symlink. The load stage records [ToolchainModule.canonicalDirectory] so that
 * resolution keeps working without a [FileSystem].
 */
class ModuleDirectoryResolutionTest {
    @Test
    fun aDotDotDependencyResolvesWhenTheProjectIsReachedThroughASymlink() {
        val base = Files.createTempDirectory("ktc-to-gradle-symlink-")
        val real = base.resolve("real")
        write(real.resolve("project.yaml"), "modules:\n  - app\n  - libs/shared\n")
        write(real.resolve("app/module.yaml"), "product: jvm/app\ndependencies:\n  - ./../libs/shared\n")
        write(real.resolve("libs/shared/module.yaml"), "product: jvm/lib\n")
        Files.createSymbolicLink(base.resolve("link"), real)

        val project = ProjectLoader(FileSystem.SYSTEM).load(base.resolve("link").toString().toPath())

        // The load stage canonicalizes the start path, so every module directory it records is real.
        assertEquals(FileSystem.SYSTEM.canonicalize(real.toString().toPath()), project.root)
        assertEquals(listOf("app", "libs/shared"), project.modules.map { it.path.notation })
        assertTrue(project.modules.all { it.directory == it.canonicalDirectory })
        val build = buildFileOf(project, "app")
        assertTrue("project(\":libs:shared\")" in build, "The ./.. dependency did not resolve:\n$build")
    }

    @Test
    fun aDotDotDependencyResolvesByCanonicalDirectoryWhenThePlainPathMisses() {
        val root = "/workspace".toPath()
        val project = ToolchainProject(
            root = root,
            name = "workspace",
            modules = listOf(
                module("app", root / "app", root / "app", "product: jvm/lib\ndependencies:\n  - ./../real/shared\n"),
                // Listed under links/, but the directory itself is a symlink into real/.
                module("links/shared", root / "links" / "shared", root / "real" / "shared", "product: jvm/lib\n"),
            ),
            catalogPath = null,
        )

        assertTrue("project(\":links:shared\")" in buildFileOf(project, "app"))
    }

    @Test
    fun aDotDotDependencyWithNoMatchingDirectoryIsRejected() {
        val root = "/workspace".toPath()
        val project = ToolchainProject(
            root = root,
            name = "workspace",
            modules = listOf(
                module("app", root / "app", root / "app", "product: jvm/lib\ndependencies:\n  - ./../real/shared\n"),
                module("links/shared", root / "links" / "shared", root / "links" / "shared", "product: jvm/lib\n"),
            ),
            catalogPath = null,
        )

        try {
            buildFileOf(project, "app")
            fail("Expected an unknown-module failure")
        } catch (error: ConversionException) {
            assertEquals("app: unknown module './../real/shared'", error.message)
        }
    }

    private fun module(
        notation: String,
        directory: OkioPath,
        canonicalDirectory: OkioPath,
        yaml: String,
    ) = ToolchainModule(
        path = ModulePath.parse(notation),
        directory = directory,
        canonicalDirectory = canonicalDirectory,
        config = parseYaml(yaml, "$notation/module.yaml"),
        layout = ModuleLayout(existingSourceDirs = emptySet(), detectedMainClass = null),
    )

    private fun buildFileOf(project: ToolchainProject, notation: String): String {
        val directory = project.modules.first { it.path.notation == notation }.directory
        return GradleGenerator().generate(project).first
            .first { it.path == directory / "build.gradle.kts" }
            .content
    }

    private fun write(path: Path, content: String) {
        path.parent.createDirectories()
        path.writeText(content)
    }
}
