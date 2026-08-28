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
import kotlin.test.assertTrue
import kotlin.test.fail

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

    /**
     * A `kmp/lib` android target that declares the nested `compileSdk: { apiLevel: }` form.
     *
     * The pre-pipeline converter read that form on the `android/app` path only and silently emitted
     * the default `37` here; this case pins the level the module actually asked for. It is the one
     * intentional output difference the pipeline refactor introduced.
     */
    @Test fun kmpAndroidCompileSdk() = assertCase("kmp-android-compile-sdk")

    @Test fun androidApp() = assertCase("android-app")

    /**
     * An `android/app` that asks for JUnit 4, which is what the Android Gradle Plugin runs anyway.
     *
     * The JUnit 5 case emits `testOptions.unitTests.all { useJUnitPlatform() }`; this one pins that
     * the block stays out when the module did not ask for the platform.
     */
    @Test fun androidJunit4() = assertCase("android-junit4")

    /**
     * A `kmp/lib` whose only platform is `android`.
     *
     * Its android target still carries a `-Xjdk-release`, so the build still needs the
     * `jvmToolchain` that used to appear only next to a `jvm()` target.
     */
    @Test fun kmpAndroidOnly() = assertCase("kmp-android-only")

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

    /**
     * A module that loads a third-party Kotlin compiler plugin.
     *
     * Gradle has no DSL for one, so this pins the two-part spelling the renderer invents: the
     * artifact on the plugin classpath, and one `-P` argument pair per option.
     */
    @Test fun compilerPlugin() = assertCase("compiler-plugin")

    /**
     * The same setting on a multiplatform module, which spells it differently.
     *
     * A multiplatform module declares its dependencies per source set, so the plugin classpath needs
     * a module-wide `dependencies { }` block of its own, and a native target reads a second
     * configuration because Kotlin/Native runs a compiler of its own.
     */
    @Test fun compilerPluginKmp() = assertCase("compiler-plugin-kmp")

    /**
     * A project with a `jvm/amper-plugin` module and a module that enables it.
     *
     * The plugin module is left out of the build and the module that used it is still converted, so
     * this case pins both halves of the degraded conversion: the files that survive and the errors
     * that name what did not.
     */
    @Test fun pluginModule() = assertCase("plugin-module")

    /**
     * An `android/app` carrying `settings.jvm.test` and `test-settings.jvm`, on JUnit 4.
     *
     * `unitTests.all { }` is the only handle the Android Gradle Plugin offers on the unit-test task,
     * so the settings reach it through the same block `useJUnitPlatform()` uses. JUnit 4 on purpose:
     * the block has to open for the settings alone, and `android-junit4` pins that it stays shut
     * when there are none.
     */
    @Test fun androidTestSettings() = assertCase("android-test-settings")

    /**
     * The same two sections on a `kmp/lib`, which has one `Test` task per JVM-backed target.
     *
     * They are configured together through `tasks.withType<Test>()`, so this pins that the settings
     * join `useJUnitPlatform()` in that block rather than in a target-specific one. The other half
     * of the guard the Android case leaves open.
     */
    @Test fun kmpTestSettings() = assertCase("kmp-test-settings")

    /**
     * A subproject mirroring Maven Central behind credentials, next to one that mirrors nothing.
     *
     * `pluginManagement` is written once for the whole build, so this pins what folding per-module
     * repositories into it has to get right: every module's list reaches it, nothing a module
     * declared is dropped for sharing an id with another module's, the credentials path is rebased
     * onto the root because `file(...)` means the module in `build.gradle.kts` and the settings
     * directory in `settings.gradle.kts`, and the plugin portal is written last.
     *
     * `google()` and the public `mavenCentral()` stay even though `libs/core` replaced one and
     * turned the other off, because `app` still resolves from both. A repository leaves plugin
     * resolution when no module reaches it, which is what the single-module `repo-credentials`
     * case pins.
     */
    @Test fun pluginRepositories() = assertCase("plugin-repositories")

    @Test fun staticAssets() = assertCase("static-assets")

    private fun assertCase(case: String) {
        val trigger = Snapshots.activeUpdateTrigger()
        if (trigger != null) deleteRecursively(Snapshots.goldenRoot().resolve(case).resolve("expected"))

        val destination = convertedCopyOf(case)
        try {
            val root = FileSystem.SYSTEM.canonicalize(destination.absolutePathString().toPath())
            val result = Converter().generateFiles(root)

            // Grouped rather than mapped: two modules writing to one path would otherwise collapse
            // into a single entry and the regression would be invisible to every case.
            val byPath = result.files.groupBy { file ->
                file.path.relativeTo(root).toString().replace('\\', '/')
            }
            val duplicates = byPath.filterValues { it.size > 1 }.keys
            assertTrue(duplicates.isEmpty(), "Case '$case' generated more than one file for: $duplicates")

            val paths = byPath.mapValues { (_, files) -> files.single().content }
            for ((path, content) in paths.toSortedMap()) assertSnapshot(case, path, content)
            assertSnapshot(case, "files.txt", paths.keys.sorted().joinToString("") { "$it\n" })
            assertSnapshot(
                case,
                "diagnostics.txt",
                result.diagnostics.joinToString("") { "${it.severity}: ${it.message}\n" },
            )
        } finally {
            deleteRecursively(destination.parent)
        }

        // A run that rewrote the baselines compared nothing, so it must never be read as a pass.
        if (trigger != null) {
            fail(
                "Baselines for '$case' were REWRITTEN, not compared, because $trigger asked for it.\n" +
                    "Read the resulting diff line by line, then re-run without the switch to verify.",
            )
        }
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
