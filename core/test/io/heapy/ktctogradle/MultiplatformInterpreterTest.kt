package io.heapy.ktctogradle

import io.heapy.ktctogradle.load.ModuleIndex
import io.heapy.ktctogradle.interpret.MultiplatformInterpreter
import io.heapy.ktctogradle.load.ModuleLayout
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.YamlBinder
import io.heapy.ktctogradle.load.parseYaml
import io.heapy.ktctogradle.model.CompilerOptions
import io.heapy.ktctogradle.model.Dependency
import io.heapy.ktctogradle.model.DependencyTarget
import io.heapy.ktctogradle.model.KmpSourceSet
import io.heapy.ktctogradle.model.KmpTarget
import io.heapy.ktctogradle.model.MultiplatformBuild
import io.heapy.ktctogradle.model.TargetKind
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What a multiplatform module.yaml means, asserted as data instead of as generated text.
 *
 * The first case is a whole [MultiplatformBuild] compared by equality, so a field this stage stops
 * filling in fails the comparison instead of quietly leaving the generated script.
 */
class MultiplatformInterpreterTest {
    @Test
    fun aBrowserApplicationBecomesOneTargetAndItsWebHierarchy() {
        assertEquals(
            MultiplatformBuild(
                targets = listOf(
                    KmpTarget(
                        name = "js",
                        kind = TargetKind.Js,
                        executable = true,
                        entryPoint = null,
                        compilerOptions = CompilerOptions.EMPTY,
                    ),
                ),
                jvmToolchain = null,
                compilerOptions = CompilerOptions.EMPTY,
                qualifiedCompilerOptions = CompilerOptions.EMPTY,
                sourceSets = listOf(
                    KmpSourceSet(
                        name = "commonMain",
                        parents = emptyList(),
                        test = false,
                        builtIn = true,
                        sourceDirs = listOf("src"),
                        resourceDirs = listOf("resources"),
                        dependencies = emptyList(),
                    ),
                    KmpSourceSet(
                        name = "commonTest",
                        parents = emptyList(),
                        test = true,
                        builtIn = true,
                        sourceDirs = listOf("test"),
                        resourceDirs = listOf("testResources"),
                        dependencies = listOf(Dependency(DependencyTarget.KotlinBuiltin("test"))),
                    ),
                    sourceSet("webMain", "commonMain"),
                    sourceSet("webTest", "commonTest", test = true),
                    sourceSet("jsMain", "webMain"),
                    sourceSet("jsTest", "webTest", test = true),
                ),
            ),
            interpret(module("app", "product: js/app\n")),
        )
    }

    @Test
    fun aNativeApplicationCarriesItsEntryPointAndTheJvmOneItsRelease() {
        val native = interpret(
            module("app", "product: linux/app\nsettings:\n  native:\n    entryPoint: example.main\n"),
        )

        assertEquals(listOf("linuxX64", "linuxArm64"), native.targets.map(KmpTarget::name))
        assertEquals(listOf(TargetKind.Native, TargetKind.Native), native.targets.map(KmpTarget::kind))
        assertEquals(listOf("example.main", "example.main"), native.targets.map(KmpTarget::entryPoint))

        val jvm = interpret(
            module("lib", "product:\n  type: kmp/lib\n  platforms: [jvm]\nsettings:\n  jvm:\n    release: 17\n"),
        )

        assertEquals(listOf(TargetKind.Jvm(release = "17")), jvm.targets.map(KmpTarget::kind))
        assertEquals("25", jvm.jvmToolchain)
    }

    /** A target inherits the module-wide options, so only the section that names it may add to them. */
    @Test
    fun aQualifiedSectionReachesItsOwnTargetAndNoOther() {
        val build = interpret(
            module(
                "lib",
                """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64]
                settings@jvm:
                  kotlin:
                    languageVersion: 2.2
                    allWarningsAsErrors: true
                """.trimIndent(),
            ),
        )

        assertEquals(
            CompilerOptions(languageVersion = "2.2", allWarningsAsErrors = true),
            build.targets.single { it.name == "jvm" }.compilerOptions,
        )
        assertEquals(CompilerOptions.EMPTY, build.targets.single { it.name == "linuxX64" }.compilerOptions)
        assertEquals(CompilerOptions.EMPTY, build.qualifiedCompilerOptions)
    }

    /** `settings@common` covers every platform, so it belongs to the module and not to a target. */
    @Test
    fun theCommonQualifierLandsOnTheModuleWideOptions() {
        val build = interpret(
            module(
                "lib",
                """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64]
                settings@common:
                  kotlin:
                    progressiveMode: true
                """.trimIndent(),
            ),
        )

        assertEquals(CompilerOptions(progressiveMode = true), build.qualifiedCompilerOptions)
        assertEquals(CompilerOptions.EMPTY, build.compilerOptions)
        for (target in build.targets) assertEquals(CompilerOptions.EMPTY, target.compilerOptions, target.name)
    }

    /** A narrower section is applied last, so it overrides the broader one covering the same leaf. */
    @Test
    fun aNarrowerQualifierOverridesABroaderOneCoveringTheSameLeaf() {
        val build = interpret(
            module(
                "lib",
                """
                product:
                  type: kmp/lib
                  platforms: [jvm, iosArm64]
                settings@iosArm64:
                  kotlin:
                    allWarningsAsErrors: false
                settings@ios:
                  kotlin:
                    allWarningsAsErrors: true
                    optIns: [kotlin.ExperimentalStdlibApi]
                """.trimIndent(),
            ),
        )

        assertEquals(
            CompilerOptions(allWarningsAsErrors = false, optIns = listOf("kotlin.ExperimentalStdlibApi")),
            build.targets.single { it.name == "iosArm64" }.compilerOptions,
        )
    }

    /**
     * A qualified section the converter cannot carry is reported key by key and dropped, never
     * raised: the module still converts, and the user is told exactly what did not survive.
     */
    @Test
    fun everyQualifiedKeyWithNoGradleEquivalentIsReportedAndDropped() {
        val diagnostics = DiagnosticCollector()
        val library = module(
            "shared",
            """
            product:
              type: kmp/lib
              platforms: [jvm, linuxX64]
            settings@jvm:
              kotlin:
                unknown: true
              jvm:
                release: 21
            settings@macosArm64:
              kotlin:
                allWarningsAsErrors: true
            settings@linuxX64: plain
            test-settings@jvm:
              kotlin:
                allWarningsAsErrors: true
            """.trimIndent(),
        )
        MultiplatformInterpreter.interpret(ModuleIndex.of(listOf(library)), library, diagnostics)

        assertEquals(
            listOf(
                "shared: 'settings@jvm.kotlin.unknown' is not supported by the converter and was dropped",
                "shared: 'settings@jvm.jvm.release' is not supported by the converter and was dropped",
                "shared: 'settings@macosArm64' names no platform of this module and was dropped",
                "shared: 'settings@linuxX64' must be an object and was dropped",
                "shared: 'test-settings@jvm' is not supported by the converter and was dropped",
            ),
            diagnostics.drain().map(Diagnostic::message),
        )
    }

    /**
     * The Android Gradle Plugin calls the unit-test source set of a multiplatform module
     * `androidHostTest`; `androidTest` is its on-device suite, so tests placed there never run.
     */
    @Test
    fun theAndroidUnitTestSourceSetIsNamedAndroidHostTest() {
        val build = interpret(
            module(
                "shared",
                "product:\n  type: kmp/lib\n  platforms: [jvm, android]\nsettings:\n  android:\n    namespace: example.shared\n",
                layout = ModuleLayout(setOf("src@android", "test@android"), detectedMainClass = null),
            ),
        )

        assertEquals(
            listOf("commonMain", "commonTest", "androidMain", "androidHostTest", "jvmMain", "jvmTest"),
            build.sourceSets.map(KmpSourceSet::name),
        )
        assertEquals(
            KmpSourceSet(
                name = "androidHostTest",
                parents = listOf("commonTest"),
                test = true,
                builtIn = false,
                sourceDirs = listOf("test@android"),
                resourceDirs = emptyList(),
                dependencies = emptyList(),
            ),
            build.sourceSets.single { it.name == "androidHostTest" },
        )
    }

    /** Only the directories a module actually has are declared, so Gradle is never given a missing one. */
    @Test
    fun onlyTheSourceDirectoriesThatExistReachTheSourceSets() {
        val build = interpret(
            module(
                "shared",
                "product:\n  type: kmp/lib\n  platforms: [jvm, linuxX64]\n",
                layout = ModuleLayout(setOf("src@jvm", "resources@linux"), detectedMainClass = null),
            ),
        )

        assertEquals(listOf("src@jvm"), build.sourceSets.single { it.name == "jvmMain" }.sourceDirs)
        assertEquals(emptyList(), build.sourceSets.single { it.name == "jvmTest" }.sourceDirs)
        assertEquals(listOf("resources@linux"), build.sourceSets.single { it.name == "linuxMain" }.resourceDirs)
    }

    @Test
    fun aQualifiedDependencySectionLandsOnItsOwnSourceSet() {
        val build = interpret(
            module(
                "shared",
                """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64]
                dependencies:
                  - org.example:common:1.0
                dependencies@native:
                  - org.example:native-only:1.0
                test-dependencies@jvm:
                  - org.example:jvm-test:1.0
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf(Dependency(DependencyTarget.Maven("org.example:common:1.0"))),
            build.sourceSets.single { it.name == "commonMain" }.dependencies,
        )
        assertEquals(
            listOf(Dependency(DependencyTarget.Maven("org.example:native-only:1.0"))),
            build.sourceSets.single { it.name == "nativeMain" }.dependencies,
        )
        assertEquals(
            listOf(Dependency(DependencyTarget.Maven("org.example:jvm-test:1.0"))),
            build.sourceSets.single { it.name == "jvmTest" }.dependencies,
        )
    }

    @Test
    fun aPlatformNoKotlinTargetExistsForIsRejected() {
        assertEquals(
            "Unsupported Kotlin platform 'linux'",
            assertFailsWith<ConversionException> {
                interpret(module("lib", "product:\n  type: kmp/lib\n  platforms: [linux]\n"))
            }.message,
        )
    }

    /** A section the binder could not read raises its message here, at the point that reads it. */
    @Test
    fun aMalformedSettingsSectionRaisesItsDeferredMessage() {
        assertEquals(
            "Expected a list at settings.kotlin.optIns",
            assertFailsWith<ConversionException> {
                interpret(
                    module(
                        "lib",
                        "product:\n  type: kmp/lib\n  platforms: [jvm]\nsettings:\n  kotlin:\n    optIns: nope\n",
                    ),
                )
            }.message,
        )
    }

    /**
     * An alias qualifier names a set of platforms, so the section it labels reaches every target in
     * that set — the Android one included — and no target outside it.
     */
    @Test
    fun anAliasQualifiedSectionReachesEveryTargetItCovers() {
        val build = interpret(
            module(
                "lib",
                """
                product:
                  type: kmp/lib
                  platforms: [jvm, android, linuxX64]
                aliases:
                  - jvmAndAndroid: [jvm, android]
                settings:
                  android:
                    namespace: example.qualified
                settings@jvmAndAndroid:
                  kotlin:
                    allWarningsAsErrors: true
                """.trimIndent(),
            ),
        )

        assertEquals(
            CompilerOptions(allWarningsAsErrors = true),
            build.targets.single { it.name == "jvm" }.compilerOptions,
        )
        assertEquals(
            CompilerOptions(allWarningsAsErrors = true),
            build.targets.single { it.name == "android" }.compilerOptions,
        )
        assertEquals(CompilerOptions.EMPTY, build.targets.single { it.name == "linuxX64" }.compilerOptions)
    }

    private fun sourceSet(name: String, parent: String, test: Boolean = false): KmpSourceSet = KmpSourceSet(
        name = name,
        parents = listOf(parent),
        test = test,
        builtIn = false,
        sourceDirs = emptyList(),
        resourceDirs = emptyList(),
        dependencies = emptyList(),
    )

    /** `settings.jvm.test` reaches no multiplatform target, so a malformed value is not read here. */
    @Test
    fun aMalformedJvmTestArgumentListDoesNotFailAMultiplatformModule() {
        val shared = module(
            "shared",
            """
            product:
              type: kmp/lib
              platforms: [jvm, linuxX64]
            settings:
              jvm:
                test:
                  freeJvmArgs: nope
            """.trimIndent(),
        )

        assertEquals(listOf("jvm", "linuxX64"), interpret(shared).targets.map(KmpTarget::name))
    }

    private fun interpret(module: ToolchainModule): MultiplatformBuild =
        MultiplatformInterpreter.interpret(ModuleIndex.of(listOf(module)), module, DiagnosticCollector())

    private fun module(
        notation: String,
        yaml: String,
        layout: ModuleLayout = ModuleLayout(existingSourceDirs = emptySet(), detectedMainClass = null),
    ): ToolchainModule {
        val config = parseYaml(yaml, "$notation/module.yaml")
        val directory = notation.split('/').fold(ROOT) { path, segment -> path / segment }
        return ToolchainModule(
            path = ModulePath.parse(notation),
            directory = directory,
            model = YamlBinder.bind(config, notation),
            layout = layout,
        )
    }

    private companion object {
        private val ROOT: Path = "/workspace".toPath()
    }
}
