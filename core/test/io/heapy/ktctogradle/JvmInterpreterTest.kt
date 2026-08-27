package io.heapy.ktctogradle

import io.heapy.ktctogradle.interpret.Dependencies
import io.heapy.ktctogradle.interpret.JvmInterpreter
import io.heapy.ktctogradle.interpret.ModuleIndex
import io.heapy.ktctogradle.load.ModuleLayout
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.YamlBinder
import io.heapy.ktctogradle.load.parseYaml
import io.heapy.ktctogradle.model.CompilerOptions
import io.heapy.ktctogradle.model.Dependency
import io.heapy.ktctogradle.model.DependencyTarget
import io.heapy.ktctogradle.model.JvmBuild
import io.heapy.ktctogradle.model.JvmTestSettings
import io.heapy.ktctogradle.model.Layout
import io.heapy.ktctogradle.model.Scope
import io.heapy.ktctogradle.model.TestFramework
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a `jvm/lib` or `jvm/app` module.yaml means, asserted as data instead of as generated text.
 *
 * The interpreter is pure, so every case here is a whole [JvmBuild] compared by equality: a field
 * this stage stops filling in fails the comparison instead of quietly disappearing from the output.
 */
class JvmInterpreterTest {
    @Test
    fun aLibraryWithNoSettingsGetsTheConverterDefaults() {
        assertEquals(
            JvmBuild(
                jdk = "25",
                release = "25",
                compilerOptions = CompilerOptions(jvmTarget = "25"),
                layout = Layout.AMPER,
                dependencies = emptyList(),
                testDependencies = emptyList(),
                testFramework = TestFramework.JUNIT_5,
                testSettings = JvmTestSettings(),
                mainClass = null,
            ),
            interpret(module("app", "product: jvm/lib\n")),
        )
    }

    @Test
    fun anApplicationCarriesItsJdkReleaseDependenciesAndMainClass() {
        val app = module(
            "app",
            """
            product: jvm/app

            settings:
              jvm:
                jdk:
                  version: 21
                release: 17
                mainClass: example.MainKt

            dependencies:
              - org.example:library:1.0

            test-dependencies:
              - org.example:harness:1.0
            """.trimIndent(),
        )

        assertEquals(
            JvmBuild(
                jdk = "21",
                release = "17",
                compilerOptions = CompilerOptions(jvmTarget = "17"),
                layout = Layout.AMPER,
                dependencies = listOf(Dependency(DependencyTarget.Maven("org.example:library:1.0"))),
                testDependencies = listOf(Dependency(DependencyTarget.Maven("org.example:harness:1.0"))),
                testFramework = TestFramework.JUNIT_5,
                testSettings = JvmTestSettings(),
                mainClass = "example.MainKt",
            ),
            interpret(app),
        )
    }

    @Test
    fun everyLocalNotationResolvesToTheSameGradleProject() {
        val app = module(
            "app",
            """
            product: jvm/lib
            dependencies:
              - //libs/messages
              - ./../libs/messages
              - ../libs/messages
            """.trimIndent(),
        )
        val messages = module("libs/messages", "product: jvm/lib\n")

        assertEquals(
            List(3) { Dependency(DependencyTarget.Project(":libs:messages")) },
            interpret(app, messages).dependencies,
        )
    }

    @Test
    fun aLocalDependencyNoModuleProvidesIsRejectedByBothLookups() {
        val app = module("app", "product: jvm/lib\ndependencies:\n  - //libs/missing\n")

        assertEquals(
            "app depends on unknown module '//libs/missing'",
            assertFailsWith<ConversionException> { Dependencies.validateLocal(listOf(app)) }.message,
        )
        assertEquals(
            "app: unknown module '//libs/missing'",
            assertFailsWith<ConversionException> { interpret(app) }.message,
        )
    }

    @Test
    fun theTestFrameworkFollowsTheJunitSetting() {
        assertEquals(TestFramework.JUNIT_5, interpret(module("app", "product: jvm/lib\n")).testFramework)
        assertEquals(TestFramework.JUNIT_5, interpret(junit("junit-5")).testFramework)
        assertEquals(TestFramework.JUNIT_4, interpret(junit("junit-4")).testFramework)
        assertEquals(TestFramework.NONE, interpret(junit("none")).testFramework)
        assertEquals(
            "settings.junit must be junit-5, junit-4, or none",
            assertFailsWith<ConversionException> { interpret(junit("testng")) }.message,
        )
    }

    @Test
    fun aMavenLikeModuleKeepsTheGradleSourceLayout() {
        assertEquals(Layout.MAVEN_LIKE, interpret(module("app", "product: jvm/lib\nlayout: maven-like\n")).layout)
        assertEquals(Layout.AMPER, interpret(module("app", "product: jvm/lib\nlayout: default\n")).layout)
    }

    @Test
    fun aDeclaredMainClassWinsOverTheDetectedOne() {
        val detected = ModuleLayout(existingSourceDirs = setOf("src"), detectedMainClass = "detected.MainKt")

        assertEquals(
            "detected.MainKt",
            interpret(module("app", "product: jvm/app\n", detected)).mainClass,
        )
        assertEquals(
            "declared.MainKt",
            interpret(
                module(
                    "app",
                    "product: jvm/app\nsettings:\n  jvm:\n    mainClass: declared.MainKt\n",
                    detected,
                ),
            ).mainClass,
        )
    }

    @Test
    fun aLibraryNeverGetsAMainClassAndAnApplicationWithoutOneIsReported() {
        val detected = ModuleLayout(existingSourceDirs = setOf("src"), detectedMainClass = "detected.MainKt")
        assertEquals(null, interpret(module("app", "product: jvm/lib\n", detected)).mainClass)

        val diagnostics = DiagnosticCollector()
        val app = module("app", "product: jvm/app\n")
        assertEquals(null, JvmInterpreter.interpret(ModuleIndex.of(listOf(app)), app, diagnostics).mainClass)
        assertEquals(
            listOf(
                Diagnostic(
                    Diagnostic.Severity.WARNING,
                    "app: could not infer a main class; set settings.jvm.mainClass or application.mainClass",
                ),
            ),
            diagnostics.drain(),
        )
    }

    @Test
    fun compilerOptionsAreBuiltFromTheKotlinSettings() {
        val app = module(
            "app",
            """
            product: jvm/lib

            settings:
              kotlin:
                languageVersion: 2.2
                apiVersion: 2.1
                allWarningsAsErrors: true
                progressiveMode: true
                freeCompilerArgs: [-Xcontext-parameters]
                optIns: [kotlin.ExperimentalStdlibApi]
              jvm:
                release: 21
            """.trimIndent(),
        )

        assertEquals(
            CompilerOptions(
                languageVersion = "2.2",
                apiVersion = "2.1",
                jvmTarget = "21",
                allWarningsAsErrors = true,
                progressiveMode = true,
                freeArgs = listOf("-Xcontext-parameters"),
                optIns = listOf("kotlin.ExperimentalStdlibApi"),
            ),
            interpret(app).compilerOptions,
        )
    }

    /** A flag that is off says nothing, so a target is free to inherit and then override it. */
    @Test
    fun aFlagTurnedOffIsLeftUnsetRatherThanSetToFalse() {
        val app = module(
            "app",
            """
            product: jvm/lib
            settings:
              kotlin:
                allWarningsAsErrors: false
                progressiveMode: false
            """.trimIndent(),
        )

        assertEquals(CompilerOptions(jvmTarget = "25"), interpret(app).compilerOptions)
    }

    @Test
    fun scopeSuffixesMapOntoScopeExportedAndBom() {
        val app = module(
            "app",
            """
            product: jvm/lib
            dependencies:
              - org.example:plain:1.0
              - org.example:api:1.0: exported
              - org.example:compile:1.0: compile-only
              - org.example:runtime:1.0: runtime-only
              - bom: org.example:platform:1.0
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                Dependency(DependencyTarget.Maven("org.example:plain:1.0")),
                Dependency(DependencyTarget.Maven("org.example:api:1.0"), exported = true),
                Dependency(DependencyTarget.Maven("org.example:compile:1.0"), scope = Scope.COMPILE_ONLY),
                Dependency(DependencyTarget.Maven("org.example:runtime:1.0"), scope = Scope.RUNTIME_ONLY),
                Dependency(DependencyTarget.Maven("org.example:platform:1.0"), bom = true),
            ),
            interpret(app).dependencies,
        )
    }

    @Test
    fun catalogAccessorsAndKotlinBuiltInsBecomeTheirOwnTargets() {
        val app = module(
            "app",
            """
            product: jvm/lib
            dependencies:
              - ${'$'}libs.serialization.json
              - ${'$'}kotlin.reflect
            test-dependencies:
              - ${'$'}kotlin.test
              - ${'$'}kotlin.test.junit
            """.trimIndent(),
        )
        val build = interpret(app)

        assertEquals(
            listOf(
                Dependency(DependencyTarget.Catalog("libs.serialization.json")),
                Dependency(DependencyTarget.KotlinBuiltin("reflect")),
            ),
            build.dependencies,
        )
        assertEquals(
            listOf(
                Dependency(DependencyTarget.KotlinBuiltin("test")),
                Dependency(DependencyTarget.KotlinBuiltin("test-junit5")),
            ),
            build.testDependencies,
        )
    }

    @Test
    fun aBuiltInCatalogTheConverterCannotMapIsReported() {
        val app = module("app", "product: jvm/lib\ndependencies:\n  - ${'$'}compose.foundation\n")

        assertEquals(
            "app: built-in catalog dependency '\$compose.foundation' needs technology-specific manual conversion",
            assertFailsWith<ConversionException> { interpret(app) }.message,
        )
    }

    @Test
    fun serializationAndKtorContributeTheirOwnDependencies() {
        val app = module(
            "app",
            """
            product: jvm/lib

            settings:
              kotlin:
                serialization: json
              ktor:
                enabled: true
                version: 3.4.0
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                Dependency(DependencyTarget.Maven("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")),
                Dependency(DependencyTarget.Maven("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")),
                Dependency(DependencyTarget.Maven("io.ktor:ktor-bom:3.4.0"), bom = true),
            ),
            interpret(app).dependencies,
        )
    }

    @Test
    fun testSettingsAreAppliedOnTopOfTheBaseJvmTestSection() {
        val app = module(
            "app",
            """
            product: jvm/lib

            settings:
              jvm:
                test:
                  freeJvmArgs: [-Dbase=true]
                  systemProperties:
                    shared: base
                    baseOnly: present
                  extraEnvironment:
                    SHARED: base

            test-settings:
              jvm:
                freeJvmArgs: [-Dtest=true]
                systemProperties:
                  shared: test
                extraEnvironment:
                  SHARED: test
                  TEST_ONLY: present
            """.trimIndent(),
        )

        assertEquals(
            JvmTestSettings(
                freeJvmArgs = listOf("-Dbase=true", "-Dtest=true"),
                systemProperties = mapOf("shared" to "test", "baseOnly" to "present"),
                environment = mapOf("SHARED" to "test", "TEST_ONLY" to "present"),
            ),
            interpret(app).testSettings,
        )
    }

    /** A section the binder could not read raises its message here, at the point that reads it. */
    @Test
    fun aMalformedDependencySectionRaisesItsDeferredMessage() {
        val app = module("app", "product: jvm/lib\ndependencies:\n  - org.example:one:1.0: sometimes\n")

        assertTrue("dependencies" in app.model.errors)
        assertEquals(
            "Dependency 'org.example:one:1.0' has unknown scope 'sometimes'",
            assertFailsWith<ConversionException> { interpret(app) }.message,
        )
    }

    @Test
    fun theQualifiedJvmSectionIsReadNextToTheUnqualifiedOne() {
        val app = module(
            "app",
            """
            product: jvm/lib
            dependencies:
              - org.example:common:1.0
            dependencies@jvm:
              - org.example:jvm-only:1.0
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                Dependency(DependencyTarget.Maven("org.example:common:1.0")),
                Dependency(DependencyTarget.Maven("org.example:jvm-only:1.0")),
            ),
            interpret(app).dependencies,
        )
    }

    /**
     * The pinned version reaches every serialization artifact at once: the implied core, the runtime
     * of the declared format, and the `$kotlin.serialization.` accessor a dependency spells out.
     */
    @Test
    fun aPinnedSerializationVersionReachesEveryArtifactItContributes() {
        val app = module(
            "app",
            """
            product: jvm/lib

            settings:
              kotlin:
                serialization:
                  enabled: true
                  version: 1.9.0
                  format: json-io

            dependencies:
              - ${'$'}kotlin.serialization.json-okio
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                Dependency(DependencyTarget.Maven("org.jetbrains.kotlinx:kotlinx-serialization-json-okio:1.9.0")),
                Dependency(DependencyTarget.Maven("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")),
                Dependency(DependencyTarget.Maven("org.jetbrains.kotlinx:kotlinx-serialization-json-io:1.9.0")),
            ),
            interpret(app).dependencies,
        )
    }

    /** Serialization without a format gets its core runtime and no guess at which format to add. */
    @Test
    fun serializationWithoutAFormatContributesOnlyItsCore() {
        val app = module("app", "product: jvm/lib\nsettings:\n  kotlin:\n    serialization: enabled\n")

        assertEquals(
            listOf(Dependency(DependencyTarget.Maven("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0"))),
            interpret(app).dependencies,
        )
    }

    /** A single-platform product has one platform qualifier, and `settings@jvm` is carried by it. */
    @Test
    fun theQualifiedJvmSettingsSectionReachesTheModuleOptions() {
        val app = module(
            "app",
            """
            product: jvm/lib

            settings@jvm:
              kotlin:
                allWarningsAsErrors: true
            """.trimIndent(),
        )

        assertEquals(CompilerOptions(allWarningsAsErrors = true), interpret(app).qualifiedCompilerOptions)
    }

    private fun junit(value: String): ToolchainModule =
        module("app", "product: jvm/lib\nsettings:\n  junit: $value\n")

    private fun interpret(module: ToolchainModule, vararg others: ToolchainModule): JvmBuild =
        JvmInterpreter.interpret(ModuleIndex.of(listOf(module) + others), module, DiagnosticCollector())

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
            canonicalDirectory = directory,
            model = YamlBinder.bind(config, notation),
            layout = layout,
        )
    }

    private companion object {
        private val ROOT: Path = "/workspace".toPath()
    }
}
