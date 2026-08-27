package io.heapy.ktctogradle

import io.heapy.ktctogradle.interpret.ProjectInterpreter
import io.heapy.ktctogradle.load.ModuleLayout
import io.heapy.ktctogradle.load.ProjectLoader
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.ToolchainProject
import io.heapy.ktctogradle.load.YamlBinder
import io.heapy.ktctogradle.load.parseYaml
import io.heapy.ktctogradle.model.Dependency
import io.heapy.ktctogradle.model.DependencyTarget
import io.heapy.ktctogradle.model.JvmBuild
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import okio.Path as OkioPath

/**
 * How a `./` or `../` dependency finds the module it points at.
 *
 * Module directories are compared as written, with nothing canonicalized at resolution time. That
 * is sound because [ProjectLoader] canonicalizes the path the conversion starts from and okio never
 * descends into a symlinked directory, so every recorded directory is already a real one — but it
 * also means a symlink written into the notation itself is not resolved. Both halves are pinned
 * here.
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
        assertEquals(
            listOf(Dependency(DependencyTarget.Project(":libs:shared"))),
            dependenciesOf(project, "app"),
        )
    }

    /**
     * A notation that walks through a symlinked directory is not resolved, and the conversion says
     * so instead of quietly picking the module the link points at. This is the load stage's rule and
     * it has not changed: the pre-pipeline converter rejected the same input at the same point.
     */
    @Test
    fun aDotDotDependencyThroughASymlinkedDirectoryIsRejected() {
        val base = Files.createTempDirectory("ktc-to-gradle-notation-symlink-")
        write(base.resolve("project.yaml"), "modules:\n  - app\n  - real/shared\n")
        write(base.resolve("app/module.yaml"), "product: jvm/app\ndependencies:\n  - ./../links/shared\n")
        write(base.resolve("real/shared/module.yaml"), "product: jvm/lib\n")
        base.resolve("links").createDirectories()
        Files.createSymbolicLink(base.resolve("links/shared"), base.resolve("real/shared"))

        val failure = runCatching { ProjectLoader(FileSystem.SYSTEM).load(base.toString().toPath()) }
            .exceptionOrNull()

        assertEquals(
            "app depends on unknown module './../links/shared'",
            (failure as? ConversionException)?.message,
        )
    }

    @Test
    fun aDotDotDependencyWithNoMatchingDirectoryIsRejected() {
        val root = "/workspace".toPath()
        val project = ToolchainProject(
            root = root,
            name = "workspace",
            modules = listOf(
                module("app", root / "app", "product: jvm/lib\ndependencies:\n  - ./../real/shared\n"),
                module("links/shared", root / "links" / "shared", "product: jvm/lib\n"),
            ),
            catalogPath = null,
        )

        try {
            dependenciesOf(project, "app")
            fail("Expected an unknown-module failure")
        } catch (error: ConversionException) {
            assertEquals("app: unknown module './../real/shared'", error.message)
        }
    }

    private fun module(
        notation: String,
        directory: OkioPath,
        yaml: String,
    ): ToolchainModule {
        val config = parseYaml(yaml, "$notation/module.yaml")
        return ToolchainModule(
            path = ModulePath.parse(notation),
            directory = directory,
            model = YamlBinder.bind(config, notation),
            layout = ModuleLayout(existingSourceDirs = emptySet(), detectedMainClass = null),
        )
    }

    private fun dependenciesOf(project: ToolchainProject, notation: String): List<Dependency> {
        val directory = project.modules.first { it.path.notation == notation }.directory
        val gradle = ProjectInterpreter.interpret(project, DiagnosticCollector())
        return (gradle.modules.first { it.directory == directory }.build as JvmBuild).dependencies
    }

    private fun write(path: Path, content: String) {
        path.parent.createDirectories()
        path.writeText(content)
    }
}
