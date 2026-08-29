package io.heapy.ktctogradle

import io.heapy.ktctogradle.interpret.MultiplatformInterpreter
import io.heapy.ktctogradle.load.ModuleIndex
import io.heapy.ktctogradle.load.ModuleLayout
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.YamlBinder
import io.heapy.ktctogradle.load.parseYaml
import io.heapy.ktctogradle.model.CompilerOptions
import io.heapy.ktctogradle.model.Dependency
import io.heapy.ktctogradle.model.DependencyTarget
import io.heapy.ktctogradle.model.JvmTestSettings
import io.heapy.ktctogradle.model.KmpSourceSet
import io.heapy.ktctogradle.model.KmpTarget
import io.heapy.ktctogradle.model.MultiplatformBuild
import io.heapy.ktctogradle.model.Scope
import io.heapy.ktctogradle.model.TargetKind
import io.heapy.ktctogradle.model.TestFramework
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
                testFramework = TestFramework.JUNIT_5,
                testSettings = JvmTestSettings(),
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

    /**
     * Both JVM-flavoured targets compile against a JDK, so both need the toolchain pin.
     *
     * The android target also carries a `-Xjdk-release`, and the JDK Gradle happens to run on may
     * not be able to supply that release at all.
     */
    @Test
    fun theTwoJvmFlavouredTargetsShareTheToolchainAndTheRelease() {
        val declaration = "settings:\n  jvm:\n    jdk:\n      version: 25\n    release: 21\n" +
            "  android:\n    namespace: example.lib\n"

        val both = interpret(module("lib", "product:\n  type: kmp/lib\n  platforms: [jvm, android]\n$declaration"))
        assertEquals("25", both.jvmToolchain)
        assertEquals(
            listOf("21", "21"),
            both.targets.map { target ->
                when (val kind = target.kind) {
                    is TargetKind.Jvm -> kind.release
                    is TargetKind.Android -> kind.library.release
                    else -> "?"
                }
            },
            "A module that publishes both must publish them at one class-file version",
        )

        val androidOnly = interpret(module("lib", "product:\n  type: kmp/lib\n  platforms: [android]\n$declaration"))
        assertEquals("25", androidOnly.jvmToolchain)

        val nativeOnly = interpret(module("lib", "product:\n  type: kmp/lib\n  platforms: [linuxX64]\n$declaration"))
        assertNull(nativeOnly.jvmToolchain, "Nothing on a native-only module compiles against a JDK")
    }

    /**
     * `settings.junit` reaches the build, and `commonTest` keeps the plain Kotlin test library.
     *
     * That artifact resolves per platform, so it is the JVM-backed test tasks — and only them —
     * that have a framework to choose. The renderer decides how to say so.
     */
    @Test
    fun theTestFrameworkReachesTheBuildAndCommonTestKeepsTheKotlinTestLibrary() {
        fun buildOf(junit: String) = interpret(
            module("lib", "product:\n  type: kmp/lib\n  platforms: [jvm, linuxX64]\n$junit"),
        )

        assertEquals(TestFramework.JUNIT_5, buildOf("").testFramework, "junit-5 is the default")
        assertEquals(TestFramework.JUNIT_4, buildOf("settings:\n  junit: junit-4\n").testFramework)
        assertEquals(TestFramework.NONE, buildOf("settings:\n  junit: none\n").testFramework)

        for (junit in listOf("", "settings:\n  junit: junit-4\n", "settings:\n  junit: none\n")) {
            assertEquals(
                listOf(Dependency(DependencyTarget.KotlinBuiltin("test"))),
                buildOf(junit).sourceSets.single { it.name == "commonTest" }.dependencies,
                "for '$junit'",
            )
        }
    }

    /**
     * The JUnit adapter is named on the JVM-backed test source sets, and only on those.
     *
     * `commonTest` cannot name one, because it also compiles for native. The generated
     * `gradle.properties` stops the Kotlin Gradle Plugin from guessing the adapter from the `Test`
     * task, so a module that does not say which one it wants would otherwise get none.
     */
    @Test
    fun theJvmBackedTestSourceSetsNameTheJunitAdapterAndTheOthersDoNot() {
        fun buildOf(junit: String) = interpret(
            module(
                "lib",
                "product:\n  type: kmp/lib\n  platforms: [jvm, android, linuxX64]\n" +
                    "settings:\n  android:\n    namespace: example.lib\n$junit",
            ),
        )

        fun dependenciesOf(junit: String, sourceSet: String) =
            buildOf(junit).sourceSets.single { it.name == sourceSet }.dependencies

        for (sourceSet in listOf("jvmTest", "androidHostTest")) {
            assertEquals(
                listOf(Dependency(DependencyTarget.KotlinBuiltin("test-junit5"))),
                dependenciesOf("", sourceSet),
                "junit-5 is the default, for $sourceSet",
            )
            assertEquals(
                listOf(Dependency(DependencyTarget.KotlinBuiltin("test-junit"))),
                dependenciesOf("  junit: junit-4\n", sourceSet),
                "for $sourceSet",
            )
            // `none` adds no adapter and takes the launcher instead: the module brings its own engine
            // and the JUnit platform still has to be started for it.
            assertEquals(
                listOf(
                    Dependency(
                        DependencyTarget.Maven("org.junit.platform:junit-platform-launcher"),
                        scope = Scope.RUNTIME_ONLY,
                    ),
                ),
                dependenciesOf("  junit: none\n", sourceSet),
                "for $sourceSet",
            )
        }

        for (junit in listOf("", "  junit: junit-4\n", "  junit: none\n")) {
            assertEquals(emptyList(), dependenciesOf(junit, "linuxX64Test"), "for '$junit'")
        }
    }

    /**
     * The `jvm()` target carries the test release; no other target has a JVM compilation to put on.
     *
     * A target's `compilerOptions` reach every compilation it has, so the test one inherits the main
     * release and the renderer has to restate its own. Keeping both on [TargetKind.Jvm] is what lets
     * it do that without looking the module up again.
     */
    @Test
    fun theJvmTargetCarriesTheTestReleaseNextToTheMainOne() {
        val build = interpret(
            module(
                "lib",
                """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64]

                settings:
                  jvm:
                    jdk:
                      version: 25
                    release: 21

                test-settings:
                  jvm:
                    release: 25
                """.trimIndent(),
            ),
        )

        assertEquals(
            TargetKind.Jvm(release = "21", testRelease = "25"),
            build.targets.single { it.name == "jvm" }.kind,
        )
        assertEquals(TargetKind.Native, build.targets.single { it.name == "linuxX64" }.kind)
    }

    /**
     * The `androidLibrary` target carries it too, and it has a JVM compilation of its own.
     *
     * A module on `[jvm, android]` compiles its tests twice. Carrying the release on one target and
     * not the other would compile half of them against the wrong JDK API and say nothing about it.
     */
    @Test
    fun theAndroidTargetCarriesTheTestReleaseAsWell() {
        val build = interpret(
            module(
                "lib",
                """
                product:
                  type: kmp/lib
                  platforms: [android]

                settings:
                  android:
                    namespace: example.lib
                  jvm:
                    jdk:
                      version: 25
                    release: 21

                test-settings:
                  jvm:
                    release: 25
                """.trimIndent(),
            ),
        )

        val android = build.targets.single { it.name == "android" }.kind as TargetKind.Android
        assertEquals("21", android.library.release)
        assertEquals("25", android.library.testRelease)
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

    /**
     * Two sections reaching the same leaf: the narrower one wins, including when what it declares is
     * malformed. `settings@iosArm64` says the `apple` options do not apply to that target, so the
     * target gets none — while its sibling, which the broken section never names, keeps them.
     */
    @Test
    fun aMalformedNarrowerSectionClearsTheBroaderOneForItsOwnTargetOnly() {
        val build = interpret(
            module(
                "lib",
                """
                product:
                  type: kmp/lib
                  platforms: [iosArm64, iosSimulatorArm64]
                settings@apple:
                  kotlin:
                    languageVersion: "2.0"
                    freeCompilerArgs: [-Xexpect-actual-classes]
                settings@iosArm64:
                  kotlin: nonsense
                """.trimIndent(),
            ),
        )

        assertEquals(
            CompilerOptions.EMPTY,
            build.targets.single { it.name == "iosArm64" }.compilerOptions,
        )
        assertEquals(
            CompilerOptions(languageVersion = "2.0", freeArgs = listOf("-Xexpect-actual-classes")),
            build.targets.single { it.name == "iosSimulatorArm64" }.compilerOptions,
        )
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
     * The leaf overrides the alias that names only it, because the alias is now above the leaf.
     *
     * `QualifiedSettings` ranks a section by where its fragment sits in the hierarchy, so placing a
     * single-platform alias above its leaf decides this too: `settings@jvm` is the narrower of the
     * two and wins, the way `settings@iosArm64` wins over `settings@ios`.
     */
    @Test
    fun aPlatformQualifierOverridesTheAliasThatNamesOnlyThatPlatform() {
        val build = interpret(
            module(
                "lib",
                """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64]
                aliases:
                  - server: [jvm]
                settings@jvm:
                  kotlin:
                    allWarningsAsErrors: false
                settings@server:
                  kotlin:
                    allWarningsAsErrors: true
                    optIns: [kotlin.ExperimentalStdlibApi]
                """.trimIndent(),
            ),
        )

        assertEquals(
            CompilerOptions(allWarningsAsErrors = false, optIns = listOf("kotlin.ExperimentalStdlibApi")),
            build.targets.single { it.name == "jvm" }.compilerOptions,
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
                "shared: 'test-settings@jvm.kotlin.allWarningsAsErrors' is not supported by the converter " +
                    "and was dropped",
            ),
            diagnostics.collected().map(Diagnostic::message),
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
                dependencies = listOf(Dependency(DependencyTarget.KotlinBuiltin("test-junit5"))),
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
        // The JUnit adapter the JVM-backed set gets anyway comes first: the module's own qualified
        // dependencies follow it, in the order it declared them.
        assertEquals(
            listOf(
                Dependency(DependencyTarget.KotlinBuiltin("test-junit5")),
                Dependency(DependencyTarget.Maven("org.example:jvm-test:1.0")),
            ),
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

    /** `settings.jvm.test` now reaches the module's `Test` tasks, so a malformed value is raised here. */
    @Test
    fun aMalformedJvmTestArgumentListFailsAMultiplatformModule() {
        assertEquals(
            "Expected a list at settings.jvm.test.freeJvmArgs",
            assertFailsWith<ConversionException> {
                interpret(
                    module(
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
                    ),
                )
            }.message,
        )
    }

    /** A module with no JVM-backed target applies nothing, so a value it got wrong must not fail it. */
    @Test
    fun aMalformedJvmTestArgumentListDoesNotFailAModuleWithoutAJvmBackedTarget() {
        val shared = module(
            "shared",
            """
            product:
              type: kmp/lib
              platforms: [linuxX64]
            settings:
              jvm:
                test:
                  freeJvmArgs: nope
            """.trimIndent(),
        )

        assertEquals(JvmTestSettings.EMPTY, interpret(shared).testSettings)
    }

    /** The JVM test settings of a module with no JVM-backed target reach no task, so they are reported. */
    @Test
    fun jvmTestSettingsWithoutAJvmBackedTargetAreReported() {
        val diagnostics = DiagnosticCollector()
        val shared = module(
            "shared",
            """
            product:
              type: kmp/lib
              platforms: [linuxX64, js]
            settings:
              jvm:
                test:
                  freeJvmArgs: [-Xmx512m]
                  systemProperties:
                    mode: fast
            """.trimIndent(),
        )

        val build = MultiplatformInterpreter.interpret(ModuleIndex.of(listOf(shared)), shared, diagnostics)

        assertEquals(JvmTestSettings.EMPTY, build.testSettings)
        assertEquals(
            listOf(
                Diagnostic(
                    Diagnostic.Severity.WARNING,
                    "shared: the JVM test settings freeJvmArgs, systemProperties name no JVM-backed " +
                        "platform of this module and were dropped",
                ),
            ),
            diagnostics.collected(),
        )
    }

    /** `test-settings:` overrides `settings.jvm.test` key by key, and the rest of the base survives. */
    @Test
    fun jvmTestSettingsMergeTheTestSpecificSectionOverTheBaseOne() {
        val shared = module(
            "shared",
            """
            product:
              type: kmp/lib
              platforms: [jvm, linuxX64]
            settings:
              jvm:
                test:
                  freeJvmArgs: [-Xmx512m]
                  systemProperties:
                    mode: base
                    kept: base
                  extraEnvironment:
                    MODE: base
            test-settings:
              jvm:
                freeJvmArgs: [-XX:+UseZGC]
                systemProperties:
                  mode: test
            """.trimIndent(),
        )

        assertEquals(
            JvmTestSettings(
                freeJvmArgs = listOf("-Xmx512m", "-XX:+UseZGC"),
                systemProperties = mapOf("mode" to "test", "kept" to "base"),
                environment = mapOf("MODE" to "base"),
            ),
            interpret(shared).testSettings,
        )
    }

    /**
     * An alias naming exactly one platform is still a fragment between `common` and that platform.
     *
     * It covers no more leaves than the platform's own source set does, so nothing but the leaf can
     * depend on it: were it left beside the leaf instead of above it, `src@server` and
     * `dependencies@server` would reach no compilation at all.
     */
    @Test
    fun anAliasNamingOnePlatformBecomesThatPlatformsParent() {
        val build = interpret(
            module(
                "shared",
                """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64]
                aliases:
                  - server: [jvm]
                dependencies@server:
                  - org.example:server:1.0
                """.trimIndent(),
                layout = ModuleLayout(setOf("src@server"), detectedMainClass = null),
            ),
        )

        assertEquals(
            listOf(
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
                sourceSet("nativeMain", "commonMain"),
                sourceSet("nativeTest", "commonTest", test = true),
                KmpSourceSet(
                    name = "serverMain",
                    parents = listOf("commonMain"),
                    test = false,
                    builtIn = false,
                    sourceDirs = listOf("src@server"),
                    resourceDirs = emptyList(),
                    dependencies = listOf(Dependency(DependencyTarget.Maven("org.example:server:1.0"))),
                ),
                sourceSet("serverTest", "commonTest", test = true),
                sourceSet("jvmMain", "serverMain"),
                KmpSourceSet(
                    name = "jvmTest",
                    parents = listOf("serverTest"),
                    test = true,
                    builtIn = false,
                    sourceDirs = emptyList(),
                    resourceDirs = emptyList(),
                    dependencies = listOf(Dependency(DependencyTarget.KotlinBuiltin("test-junit5"))),
                ),
                sourceSet("linuxMain", "nativeMain"),
                sourceSet("linuxTest", "nativeTest", test = true),
                sourceSet("linuxX64Main", "linuxMain"),
                sourceSet("linuxX64Test", "linuxTest", test = true),
            ),
            build.sourceSets,
        )
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

    /**
     * A platform-qualified JVM test setting reaches one target's `Test` task and not the others.
     *
     * The module-wide keys stay on the build, because they reach every JVM-backed target; only what
     * a qualifier narrowed rides on the target, where the renderer has a task name to address.
     */
    @Test
    fun aQualifiedJvmTestSettingReachesOneTargetOnly() {
        val build = interpret(
            module(
                "shared",
                """
                product:
                  type: kmp/lib
                  platforms: [jvm, android]
                settings:
                  android:
                    namespace: example.shared
                  jvm:
                    test:
                      systemProperties:
                        mode: module
                settings@jvm:
                  jvm:
                    test:
                      systemProperties:
                        mode: jvm
                test-settings@android:
                  jvm:
                    extraEnvironment:
                      MODE: android
                """.trimIndent(),
            ),
        )

        assertEquals(JvmTestSettings(systemProperties = mapOf("mode" to "module")), build.testSettings)
        assertEquals(
            JvmTestSettings(systemProperties = mapOf("mode" to "jvm")),
            (build.targets.single { it.name == "jvm" }.kind as TargetKind.Jvm).testSettings,
        )
        assertEquals(
            JvmTestSettings(environment = mapOf("MODE" to "android")),
            (build.targets.single { it.name == "android" }.kind as TargetKind.Android).library.testSettings,
        )
    }

    /**
     * An alias is a qualifier like any other, so it reaches every platform it covers.
     *
     * `settings@common` is the one qualifier that does not: it covers every platform, so it joins
     * the module-wide block instead of being repeated on each target.
     */
    @Test
    fun anAliasQualifierReachesEveryPlatformItCovers() {
        val build = interpret(
            module(
                "shared",
                """
                product:
                  type: kmp/lib
                  platforms: [jvm, android]
                aliases:
                  - jvmAndAndroid: [jvm, android]
                settings@common:
                  jvm:
                    test:
                      systemProperties:
                        scope: common
                test-settings@jvmAndAndroid:
                  jvm:
                    freeJvmArgs: [-Xmx512m]
                """.trimIndent(),
            ),
        )

        assertEquals(JvmTestSettings(systemProperties = mapOf("scope" to "common")), build.testSettings)
        assertEquals(
            JvmTestSettings(freeJvmArgs = listOf("-Xmx512m")),
            (build.targets.single { it.name == "jvm" }.kind as TargetKind.Jvm).testSettings,
        )
        assertEquals(
            JvmTestSettings(freeJvmArgs = listOf("-Xmx512m")),
            (build.targets.single { it.name == "android" }.kind as TargetKind.Android).library.testSettings,
        )
    }

    /**
     * A qualifier that names only platforms with no `Test` task has nowhere to put the settings.
     *
     * Reported rather than lost, which is the rule the module-wide keys already follow on a module
     * with no JVM-backed target at all.
     */
    @Test
    fun aQualifiedJvmTestSettingThatReachesNoTestTaskIsReportedAndDropped() {
        val diagnostics = DiagnosticCollector()
        val library = module(
            "shared",
            """
            product:
              type: kmp/lib
              platforms: [jvm, linuxX64]
            test-settings@linuxX64:
              jvm:
                systemProperties:
                  mode: native
            """.trimIndent(),
        )
        val build = MultiplatformInterpreter.interpret(ModuleIndex.of(listOf(library)), library, diagnostics)

        assertEquals(
            listOf(
                "shared: the JVM test settings of 'test-settings@linuxX64' name no JVM-backed platform " +
                    "of this module and were dropped",
            ),
            diagnostics.collected().map(Diagnostic::message),
        )
        assertEquals(JvmTestSettings.EMPTY, build.testSettings)
        assertEquals(
            JvmTestSettings.EMPTY,
            (build.targets.single { it.name == "jvm" }.kind as TargetKind.Jvm).testSettings,
        )
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
