package io.heapy.ktctogradle

import io.heapy.ktctogradle.interpret.AndroidInterpreter
import io.heapy.ktctogradle.interpret.Defaults
import io.heapy.ktctogradle.interpret.PluginResolution
import io.heapy.ktctogradle.load.ModuleIndex
import io.heapy.ktctogradle.load.ModuleLayout
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.YamlBinder
import io.heapy.ktctogradle.load.parseYaml
import io.heapy.ktctogradle.model.AndroidBuild
import io.heapy.ktctogradle.model.AndroidLibraryTarget
import io.heapy.ktctogradle.model.CompilerOptions
import io.heapy.ktctogradle.model.Dependency
import io.heapy.ktctogradle.model.DependencyTarget
import io.heapy.ktctogradle.model.GradlePlugin
import io.heapy.ktctogradle.model.JvmTestSettings
import io.heapy.ktctogradle.model.TestFramework
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What an `android/app` module.yaml means, asserted as data instead of as generated text.
 *
 * The interpreter is pure, so the first case is a whole [AndroidBuild] compared by equality: a
 * field this stage stops filling in fails the comparison instead of quietly leaving the output.
 */
class AndroidInterpreterTest {
    @Test
    fun anApplicationWithNoSettingsGetsTheConverterDefaults() {
        assertEquals(
            AndroidBuild(
                namespace = "org.example.namespace",
                applicationId = "org.example.namespace",
                compileSdk = "37",
                minSdk = "24",
                targetSdk = "37",
                versionCode = "1",
                versionName = "unspecified",
                release = "17",
                compilerOptions = CompilerOptions(jvmTarget = "17"),
                dependencies = emptyList(),
                testDependencies = emptyList(),
                testFramework = TestFramework.JUNIT_5,
                testSettings = JvmTestSettings(),
            ),
            interpret(module("app", "product: android/app\n")),
        )
    }

    @Test
    fun everyAndroidSettingOverridesItsDefault() {
        val app = module(
            "app",
            """
            product: android/app

            settings:
              jvm:
                release: 21
              android:
                namespace: example.android
                applicationId: example.android.app
                compileSdk: 35
                minSdk: 26
                targetSdk: 34
                versionCode: 7
                versionName: 1.2.3
            """.trimIndent(),
        )

        assertEquals(
            AndroidBuild(
                namespace = "example.android",
                applicationId = "example.android.app",
                compileSdk = "35",
                minSdk = "26",
                targetSdk = "34",
                versionCode = "7",
                versionName = "1.2.3",
                release = "21",
                compilerOptions = CompilerOptions(jvmTarget = "21"),
                dependencies = emptyList(),
                testDependencies = emptyList(),
                testFramework = TestFramework.JUNIT_5,
                testSettings = JvmTestSettings(),
            ),
            interpret(app),
        )
    }

    /** Kotlin Toolchain accepts an object there, and its `apiLevel` is the level the scalar means. */
    @Test
    fun theNestedCompileSdkFormIsReadLikeTheScalarOne() {
        val nested = module(
            "app",
            """
            product: android/app
            settings:
              android:
                compileSdk:
                  apiLevel: 36
            """.trimIndent(),
        )

        assertEquals("36", interpret(nested).compileSdk)
        assertEquals("36", interpret(nested).targetSdk)
    }

    @Test
    fun theTargetSdkFollowsTheCompileSdkTheModuleAskedFor() {
        val app = module(
            "app",
            "product: android/app\nsettings:\n  android:\n    compileSdk: 35\n",
        )

        assertEquals("35", interpret(app).targetSdk)
        assertEquals("37", interpret(module("app", "product: android/app\n")).targetSdk)
    }

    @Test
    fun theApplicationIdFallsBackToTheNamespace() {
        val app = module(
            "app",
            "product: android/app\nsettings:\n  android:\n    namespace: example.android\n",
        )

        assertEquals("example.android", interpret(app).applicationId)
    }

    /**
     * The Android Gradle Plugin ships its own Kotlin, so a pinned version cannot take effect and
     * the module is told so rather than being generated as if it had.
     */
    @Test
    fun aPinnedKotlinVersionIsReportedAsIneffective() {
        val diagnostics = DiagnosticCollector()
        val app = module("app", "product: android/app\nsettings:\n  kotlin:\n    version: 2.4.10\n")
        AndroidInterpreter.interpret(ModuleIndex.of(listOf(app)), app, diagnostics)

        assertEquals(
            listOf(
                Diagnostic(
                    Diagnostic.Severity.WARNING,
                    "app: settings.kotlin.version '2.4.10' does not select the Kotlin compiler for an " +
                        "Android module; the Android Gradle Plugin ${Versions.ANDROID_GRADLE_PLUGIN} supplies " +
                        "its own Kotlin",
                ),
            ),
            diagnostics.collected(),
        )
    }

    /** The Kotlin plugin would fight the one the Android Gradle Plugin already applies. */
    @Test
    fun anAndroidApplicationNeverAppliesTheKotlinPlugin() {
        val app = module("app", "product: android/app\nsettings:\n  kotlin:\n    version: 2.4.10\n")
        val plugins = PluginResolution.pluginsOf(app.model)

        assertEquals(listOf(GradlePlugin.Android.APPLICATION), plugins)
        assertFalse(plugins.any { it is GradlePlugin.Kotlin })
    }

    @Test
    fun serializationContributesItsPluginAndItsRuntime() {
        val app = module(
            "app",
            "product: android/app\nsettings:\n  kotlin:\n    serialization: json\n",
        )

        assertEquals(
            listOf(GradlePlugin.Android.APPLICATION, GradlePlugin.Kotlin.SERIALIZATION),
            PluginResolution.pluginsOf(app.model),
        )
        assertEquals(
            listOf(
                Dependency(DependencyTarget.Maven("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")),
                Dependency(DependencyTarget.Maven("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")),
            ),
            interpret(app).dependencies,
        )
    }

    @Test
    fun theQualifiedAndroidSectionIsReadNextToTheUnqualifiedOne() {
        val app = module(
            "app",
            """
            product: android/app
            dependencies:
              - org.example:common:1.0
            dependencies@android:
              - org.example:android-only:1.0
            test-dependencies@android:
              - org.example:android-harness:1.0
            """.trimIndent(),
        )
        val build = interpret(app)

        assertEquals(
            listOf(
                Dependency(DependencyTarget.Maven("org.example:common:1.0")),
                Dependency(DependencyTarget.Maven("org.example:android-only:1.0")),
            ),
            build.dependencies,
        )
        assertEquals(
            listOf(Dependency(DependencyTarget.Maven("org.example:android-harness:1.0"))),
            build.testDependencies,
        )
    }

    /** A section the binder could not read raises its message here, at the point that reads it. */
    @Test
    fun aMalformedSettingsSectionRaisesItsDeferredMessage() {
        val app = module("app", "product: android/app\nsettings:\n  kotlin:\n    optIns: nope\n")

        assertTrue("settings" in app.model.errors)
        assertEquals(
            "Expected a list at settings.kotlin.optIns",
            assertFailsWith<ConversionException> { interpret(app) }.message,
        )
    }

    @Test
    fun anExplicitNamespaceWinsOverTheDerivedOneOnALibraryTarget() {
        val diagnostics = DiagnosticCollector()
        val library = module(
            "libs/messages",
            "product:\n  type: kmp/lib\n  platforms: [jvm, android]\nsettings:\n  android:\n    namespace: example.messages\n",
        )

        assertEquals(
            AndroidLibraryTarget(namespace = "example.messages", compileSdk = "37", minSdk = "24", release = "25"),
            AndroidInterpreter.libraryTarget(library, diagnostics),
        )
        assertEquals(emptyList<Diagnostic>(), diagnostics.collected())
    }

    @Test
    fun aLibraryTargetWithoutANamespaceDerivesOneFromTheModulePath() {
        val diagnostics = DiagnosticCollector()
        val library = module("libs/messages", "product:\n  type: kmp/lib\n  platforms: [jvm, android]\n")

        assertEquals(
            AndroidLibraryTarget(namespace = "ktc.generated.libs.messages", compileSdk = "37", minSdk = "24", release = "25"),
            AndroidInterpreter.libraryTarget(library, diagnostics),
        )
        assertEquals(
            listOf(
                Diagnostic(
                    Diagnostic.Severity.WARNING,
                    "libs/messages: settings.android.namespace is not set; using 'ktc.generated.libs.messages'",
                ),
            ),
            diagnostics.collected(),
        )
    }

    /**
     * The android target of a `kmp/lib` reads the bytecode level the `jvm()` target reads.
     *
     * A module that publishes both must publish them at the same class-file version, and the
     * Android Gradle Plugin picks its own default when nothing says otherwise.
     */
    @Test
    fun aLibraryTargetTakesTheSameJvmReleaseAsTheJvmTarget() {
        fun releaseOf(jvmSettings: String) = AndroidInterpreter.libraryTarget(
            module(
                "libs/messages",
                "product:\n  type: kmp/lib\n  platforms: [jvm, android]\n" +
                    "settings:\n  android:\n    namespace: example.messages\n$jvmSettings",
            ),
            DiagnosticCollector(),
        ).release

        assertEquals("21", releaseOf("  jvm:\n    release: 21\n    jdk:\n      version: 25\n"))
        assertEquals("21", releaseOf("  jvm:\n    jdk:\n      version: 21\n"))
        assertEquals(Defaults.JVM_JDK, releaseOf(""))
    }

    /** A package segment is a Kotlin identifier, which a directory name is under no obligation to be. */
    @Test
    fun aDerivedNamespaceSanitisesEverySegmentIntoAnIdentifier() {
        assertEquals(
            "ktc.generated.my_app._2nd_ui",
            AndroidInterpreter.derivedNamespace(module("My-App/2nd.ui", "product: jvm/lib\n")),
        )
    }

    @Test
    fun aLibraryTargetOverridesTheSdkLevelsItDeclares() {
        val library = module(
            "libs/messages",
            """
            product:
              type: kmp/lib
              platforms: [jvm, android]
            settings:
              android:
                namespace: example.messages
                compileSdk: 35
                minSdk: 26
            """.trimIndent(),
        )

        assertEquals(
            AndroidLibraryTarget(namespace = "example.messages", compileSdk = "35", minSdk = "26", release = "25"),
            AndroidInterpreter.libraryTarget(library, DiagnosticCollector()),
        )
    }

    /**
     * `compileSdk` also has a nested `apiLevel` form, and a library target reads it.
     *
     * A deliberate departure from the pre-pipeline converter, which honoured the nested form only
     * for `android/app` and silently fell back to the default for a `kmp/lib` android target.
     */
    @Test
    fun aLibraryTargetReadsTheNestedCompileSdkForm() {
        val library = module(
            "libs/messages",
            """
            product:
              type: kmp/lib
              platforms: [jvm, android]
            settings:
              android:
                namespace: example.messages
                compileSdk:
                  apiLevel: 34
            """.trimIndent(),
        )

        assertEquals(
            AndroidLibraryTarget(namespace = "example.messages", compileSdk = "34", minSdk = "24", release = "25"),
            AndroidInterpreter.libraryTarget(library, DiagnosticCollector()),
        )
    }

    /**
     * `compileSdk` is emitted unquoted, so a level that is not an integer would be emitted as a
     * build script that does not parse. The failure is raised at conversion time and names the key.
     */
    @Test
    fun aCompileSdkThatIsNotAnIntegerIsReported() {
        val app = module(
            "app",
            """
            product: android/app
            settings:
              android:
                namespace: example.app
                compileSdk: android-36
            """.trimIndent(),
        )

        assertEquals(
            "settings.android.compileSdk must be an integer, but was 'android-36'",
            assertFailsWith<ConversionException> { interpret(app) }.message,
        )
    }

    /** `settings.jvm.test` now reaches the unit-test task, so a malformed value is raised here. */
    @Test
    fun aMalformedJvmTestArgumentListFailsAnAndroidModule() {
        val app = module("app", "product: android/app\nsettings:\n  jvm:\n    test:\n      freeJvmArgs: nope\n")

        assertEquals(
            "Expected a list at settings.jvm.test.freeJvmArgs",
            assertFailsWith<ConversionException> { interpret(app) }.message,
        )
    }

    /** `test-settings:` overrides `settings.jvm.test` key by key, and the rest of the base survives. */
    @Test
    fun jvmTestSettingsMergeTheTestSpecificSectionOverTheBaseOne() {
        val app = module(
            "app",
            """
            product: android/app
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
            interpret(app).testSettings,
        )
    }

    private fun interpret(module: ToolchainModule, vararg others: ToolchainModule): AndroidBuild =
        AndroidInterpreter.interpret(ModuleIndex.of(listOf(module) + others), module, DiagnosticCollector())

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
