package io.heapy.ktctogradle

import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.test.Test

/**
 * Whole-output golden tests: the contract the pipeline refactor must not break.
 *
 * Every case is converted from a pristine copy of its input tree and every generated file is
 * compared byte for byte against `core/testResources@jvm/golden/<case>/expected/`.
 */
class GeneratedOutputSnapshotTest {
    @Test fun jvmSingle() = assertCase("jvm-single")

    @Test fun jvmMulti() = assertCase("jvm-multi")

    @Test fun kmpLibrary() = assertCase("kmp-library")

    @Test fun kmpAndroid() = assertCase("kmp-android")

    @Test fun androidApp() = assertCase("android-app")

    @Test fun repoCredentials() = assertCase("repo-credentials")

    @Test fun customRepositories() = assertCase("custom-repositories")

    @Test fun rootCatalog() = assertCase("root-catalog")

    @Test fun mavenLikeLayout() = assertCase("maven-like-layout")

    @Test fun kmpAliases() = assertCase("kmp-aliases")

    @Test fun qualifiedSettings() = assertCase("qualified-settings")

    @Test fun jsApp() = assertCase("js-app")

    @Test fun wasmJsApp() = assertCase("wasm-js-app")

    @Test fun wasmWasiApp() = assertCase("wasm-wasi-app")

    @Test fun nativeApp() = assertCase("native-app")

    @Test fun junit4() = assertCase("junit4")

    @Test fun ktorBom() = assertCase("ktor-bom")

    @Test fun dependencyScopes() = assertCase("dependency-scopes")

    @Test fun rootModuleWithSubprojects() = assertCase("root-module-with-subprojects")

    @Test fun staticAssets() = assertCase("static-assets")

    private fun assertCase(case: String) {
        val update = Snapshots.updateSnapshots()
        if (update) deleteRecursively(Snapshots.goldenRoot().resolve(case).resolve("expected"))

        val destination = convertedCopyOf(case)
        val root = FileSystem.SYSTEM.canonicalize(destination.absolutePathString().toPath())
        val result = Converter().generateFiles(root)

        val paths = result.files.associate { file -> file.path.relativeTo(root).toString().replace('\\', '/') to file.content }
        for ((path, content) in paths.toSortedMap()) assertSnapshot(case, path, content)
        assertSnapshot(case, "files.txt", paths.keys.sorted().joinToString("") { "$it\n" })
        assertSnapshot(
            case,
            "diagnostics.txt",
            result.diagnostics.joinToString("") { "${it.severity}: ${it.message}\n" },
        )
    }

    /**
     * The converted tree keeps the case name, because `rootProject.name` is taken from the
     * directory a project is converted in.
     */
    private fun convertedCopyOf(case: String): Path {
        val source = inputOf(case)
        val destination = Files.createTempDirectory("ktc-to-gradle-golden-").resolve(case)
        destination.createDirectories()
        copyRecursively(source, destination)
        return destination
    }

    private fun inputOf(case: String): Path {
        val synthetic = Snapshots.goldenRoot().resolve(case).resolve("input")
        if (synthetic.isDirectory()) return synthetic
        val fixture = Snapshots.fixtureRoot().resolve(case)
        if (fixture.isDirectory()) return fixture
        error("Golden case '$case' has neither a synthetic input tree nor an integration fixture")
    }

    private fun copyRecursively(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            for (path in paths) {
                val target = destination.resolve(source.relativize(path).toString())
                if (path.isDirectory()) {
                    target.createDirectories()
                } else {
                    target.parent.createDirectories()
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun deleteRecursively(directory: Path) {
        if (!directory.exists()) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
