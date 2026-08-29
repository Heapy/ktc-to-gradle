package io.heapy.ktctogradle

import io.heapy.ktctogradle.interpret.JvmInterpreter
import io.heapy.ktctogradle.load.ModuleIndex
import io.heapy.ktctogradle.load.ModuleLayout
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.YamlBinder
import io.heapy.ktctogradle.load.parseYaml
import io.heapy.ktctogradle.load.validateLocalDependencies
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
            assertFailsWith<ConversionException> { validateLocalDependencies(listOf(app)) }.message,
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

    /**
     * `settings.junit: none` takes the JUnit platform launcher, and the other two do not.
     *
     * `kotlin-test-junit5` and `kotlin-test-junit` bring their own runner. `none` adds no adapter at
     * all, so the launcher Gradle needs before it will run `useJUnitPlatform()` is named directly.
     */
    @Test
    fun onlyJunitNoneNamesThePlatformLauncher() {
        assertEquals(emptyList(), interpret(junit("junit-5")).testDependencies)
        assertEquals(emptyList(), interpret(junit("junit-4")).testDependencies)
        assertEquals(
            listOf(
                Dependency(
                    DependencyTarget.Maven("org.junit.platform:junit-platform-launcher"),
                    scope = Scope.RUNTIME_ONLY,
                ),
            ),
            interpret(junit("none")).testDependencies,
        )
    }

    /**
     * `settings.junit: none` is converted with one named deviation, and the deviation is reported.
     *
     * The Kotlin Toolchain runs its tests through `junit-platform-console-standalone`, which carries
     * the Jupiter and Vintage engines, so `none` upstream still runs a module that only compiles
     * against `junit-jupiter-api`. Gradle gives a `Test` task nothing the module did not put on its
     * own runtime classpath, so that module runs no tests after conversion.
     */
    @Test
    fun junitNoneReportsTheEngineTheToolchainSuppliesAndGradleDoesNot() {
        val expected = "app: settings.junit: none keeps the JUnit platform but adds no engine, and the " +
            "Kotlin Toolchain supplies one of its own; declare a JUnit platform engine in the test " +
            "dependencies of every JVM-backed platform"

        for (setting in listOf("junit-5", "junit-4")) {
            val diagnostics = DiagnosticCollector()
            val app = junit(setting)
            JvmInterpreter.interpret(ModuleIndex.of(listOf(app)), app, diagnostics)
            assertEquals(emptyList(), diagnostics.collected(), "for $setting")
        }

        val diagnostics = DiagnosticCollector()
        val app = junit("none")
        JvmInterpreter.interpret(ModuleIndex.of(listOf(app)), app, diagnostics)
        assertEquals(
            listOf(Diagnostic(Diagnostic.Severity.WARNING, expected)),
            diagnostics.collected(),
        )
    }

    /**
     * `test-settings.jvm.release` reaches the build on its own, separately from the main release.
     *
     * The test classes are never published, so nothing ties them to the level the module ships. A
     * module that compiles its tests against a newer JDK API than it publishes is the case this
     * exists for, and the two values have to stay apart all the way to the renderer.
     */
    @Test
    fun theTestReleaseIsCarriedSeparatelyFromTheMainOne() {
        val build = interpret(
            module(
                "library",
                """
                product: jvm/lib

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

        assertEquals("21", build.release)
        assertEquals("25", build.testRelease)
        assertEquals(null, interpret(module("library", "product: jvm/lib\n")).testRelease)
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
            diagnostics.collected(),
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

    /**
     * The `${'$'}kotlin.` aliases that become a `kotlin("...")` dependency, and the artifact each names.
     *
     * `test.junit` is the JUnit 4 adapter and `test.junit5` the JUnit 5 one; the catalog of Toolchain
     * 0.12 defines those two and `test` and nothing else under `kotlin.test`.
     */
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
              - ${'$'}kotlin.test.junit5
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
                Dependency(DependencyTarget.KotlinBuiltin("test-junit")),
                Dependency(DependencyTarget.KotlinBuiltin("test-junit5")),
            ),
            build.testDependencies,
        )
    }

    /**
     * `${'$'}kotlin.test.common` is not in the catalog, and the Toolchain answers it with
     * "No catalog value for the key `kotlin.test.common`".
     *
     * The converter used to accept it as `kotlin("test")`, which made a build the Toolchain refuses
     * to read convert without a word.
     */
    @Test
    fun aKotlinCatalogAliasTheToolchainDoesNotDefineIsRejected() {
        val app = module("app", "product: jvm/lib\ntest-dependencies:\n  - ${'$'}kotlin.test.common\n")

        assertEquals(
            "app: unsupported Kotlin catalog alias '\$kotlin.test.common'",
            assertFailsWith<ConversionException> { interpret(app) }.message,
        )
    }

    /**
     * `jvmToolchain(...)` takes a bare integer, so a jdk version that is not one would be emitted as
     * a build script that does not parse. The failure is raised at conversion time and names the key.
     */
    @Test
    fun aJdkVersionThatIsNotAnIntegerIsReported() {
        val app = module(
            "app",
            """
            product: jvm/lib
            settings:
              jvm:
                jdk:
                  version: "21.0.2"
            """.trimIndent(),
        )

        assertEquals(
            "settings.jvm.jdk.version must be an integer, but was '21.0.2'",
            assertFailsWith<ConversionException> { interpret(app) }.message,
        )
    }

    /**
     * Every `${'$'}libs.` shape the Toolchain reads, spelled as the Gradle accessor it becomes.
     *
     * The Toolchain resolves the key against the accessor path Gradle generates rather than against
     * the raw `libs.versions.toml` alias, so the two spellings coincide and the key is passed
     * through. Measured on 0.12.0 with `kotlin show modules`: `junit-jupiter-api` is read as
     * `${'$'}libs.junit.jupiter.api`, `my_lib` as `${'$'}libs.my.lib`, and `ktorClient` as `${'$'}libs.ktorClient`;
     * the dashed and underscored spellings of the same aliases are each answered with
     * "No catalog value for the key". So `-` and `_` are alias separators that never reach an
     * accessor segment, and a segment is one word, in whatever case the alias spelled it.
     */
    @Test
    fun everyCatalogAccessorShapeTheToolchainReadsIsPassedThrough() {
        val app = module(
            "app",
            """
            product: jvm/lib
            dependencies:
              - ${'$'}libs.okio
              - ${'$'}libs.junit.jupiter.api
              - ${'$'}libs.ktorClient
              - ${'$'}libs.junit5
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                Dependency(DependencyTarget.Catalog("libs.okio")),
                Dependency(DependencyTarget.Catalog("libs.junit.jupiter.api")),
                Dependency(DependencyTarget.Catalog("libs.ktorClient")),
                Dependency(DependencyTarget.Catalog("libs.junit5")),
            ),
            interpret(app).dependencies,
        )
    }

    /**
     * A dashed alias nests into a segment that is a Kotlin keyword, and the Toolchain accepts it:
     * `aws-object-store` is read as `${'$'}libs.aws.object.store`. Emitted bare it produced
     * `implementation(libs.aws.object.store)`, which Gradle 9.7.1 answers with
     * "Expecting a class body"; backticked it configures and resolves the alias.
     */
    @Test
    fun aCatalogAccessorSegmentThatIsAKotlinKeywordIsBackticked() {
        val app = module("app", "product: jvm/lib\ndependencies:\n  - ${'$'}libs.aws.object.store\n")

        assertEquals(
            listOf(Dependency(DependencyTarget.Catalog("libs.aws.`object`.store"))),
            interpret(app).dependencies,
        )
    }

    /**
     * A key that cannot be spelled as a Kotlin accessor used to be emitted as one: `${'$'}libs.1bad`
     * produced `implementation(libs.1bad)` and a conversion that reported success, while the failure
     * only surfaced on the first `./gradlew` run with nothing pointing back at the notation.
     *
     * Existence is not checked — the converter never reads `libs.versions.toml` — so only the shape
     * is. Each of these is refused by the Toolchain too.
     */
    @Test
    fun aCatalogKeyThatCannotBecomeAKotlinAccessorIsReported() {
        for (notation in listOf("${'$'}libs.1bad", "${'$'}libs.", "${'$'}libs.junit-jupiter-api", "${'$'}libs.a..b", "${'$'}libs.bad key")) {
            val app = module("app", "product: jvm/lib\ndependencies:\n  - '$notation'\n")

            assertEquals(
                "app: catalog dependency '$notation' is not a version catalog accessor; " +
                    "'\$libs.' takes the dot-separated accessor Gradle generates for the alias, so a " +
                    "'ktor-client-core' alias is written '\$libs.ktor.client.core'",
                assertFailsWith<ConversionException> { interpret(app) }.message,
                "for $notation",
            )
        }
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

        validateLocalDependencies(listOf(app))
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

    /** The accessor is only spellable when the module turned serialization on; it is not implied. */
    @Test
    fun aSerializationAccessorWithoutTheSettingIsRejected() {
        val app = module(
            "app",
            "product: jvm/lib\ndependencies:\n  - ${'$'}kotlin.serialization.json\n",
        )

        assertEquals(
            "app: '${'$'}kotlin.serialization.json' requires settings.kotlin.serialization to be enabled",
            assertFailsWith<ConversionException> { interpret(app) }.message,
        )
    }

    /** A format kotlinx-serialization does not publish has no coordinate to guess at. */
    @Test
    fun anUnknownSerializationAliasIsRejected() {
        val app = module(
            "app",
            """
            product: jvm/lib

            settings:
              kotlin:
                serialization: enabled

            dependencies:
              - ${'$'}kotlin.serialization.yaml
            """.trimIndent(),
        )

        assertEquals(
            "Unknown Kotlin serialization catalog alias '${'$'}kotlin.serialization.yaml'",
            assertFailsWith<ConversionException> { interpret(app) }.message,
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

    /**
     * A narrower section that declares an option and gets it wrong still overrides the broader one.
     *
     * Declaring a key is what overriding is, and a value the converter cannot read is still a
     * declaration: `settings@jvm` says the module-wide `settings@common` language version does not
     * apply to the JVM, and nothing it says afterwards brings that version back.
     */
    @Test
    fun aMalformedKotlinNodeSuppressesTheBroaderSectionInsteadOfInheritingIt() {
        val app = module(
            "app",
            """
            product: jvm/app

            settings@common:
              kotlin:
                languageVersion: "2.0"

            settings@jvm:
              kotlin: nonsense
            """.trimIndent(),
        )

        assertEquals(CompilerOptions.EMPTY, interpret(app).qualifiedCompilerOptions)
    }

    /** The same for one key of an otherwise readable section: a list that is not a list clears it. */
    @Test
    fun aMalformedFreeCompilerArgListSuppressesTheBroaderOne() {
        val app = module(
            "app",
            """
            product: jvm/app

            settings@common:
              kotlin:
                freeCompilerArgs: [-Xcontext-receivers]
                progressiveMode: true

            settings@jvm:
              kotlin:
                freeCompilerArgs: notalist
            """.trimIndent(),
        )

        // progressiveMode is untouched by the malformed key, so only the arguments are cleared.
        assertEquals(CompilerOptions(progressiveMode = true), interpret(app).qualifiedCompilerOptions)
    }

    /**
     * The reach of the dependency checks, from the reading end: a `jvm/app` reads `dependencies` and
     * `dependencies@jvm` only, so a scope it cannot spell under `@js` is nobody's business.
     */
    @Test
    fun anUnknownScopeUnderAQualifierTheProductNeverReadsIsIgnored() {
        val app = module("app", "product: jvm/app\ndependencies@js:\n  - com.example:lib:1.0: bogus\n")

        validateLocalDependencies(listOf(app))
        assertEquals(emptyList(), interpret(app).dependencies)
    }

    @Test
    fun anUnknownScopeUnderAQualifierTheProductReadsIsRejected() {
        val app = module("app", "product: jvm/app\ndependencies@jvm:\n  - com.example:lib:1.0: bogus\n")

        assertEquals(
            "Dependency 'com.example:lib:1.0' has unknown scope 'bogus'",
            assertFailsWith<ConversionException> { interpret(app) }.message,
        )
    }

    /** A `bom` names no module to the load stage, so the module it holds is resolved here. */
    @Test
    fun anUnknownModuleUnderABomIsReportedByTheStageThatReadsIt() {
        val app = module("app", "product: jvm/lib\ndependencies:\n  - bom: ./missing\n")

        validateLocalDependencies(listOf(app))
        assertEquals(
            "app: unknown module './missing'",
            assertFailsWith<ConversionException> { interpret(app) }.message,
        )
    }

    @Test
    fun aMalformedTestArgumentListIsRejectedByTheModuleThatRendersATestTask() {
        val app = module("app", "product: jvm/lib\nsettings:\n  jvm:\n    test:\n      freeJvmArgs: nope\n")

        assertEquals(
            "Expected a list at settings.jvm.test.freeJvmArgs",
            assertFailsWith<ConversionException> { interpret(app) }.message,
        )
    }

    @Test
    fun aMalformedTestSettingsArgumentListIsRejectedTheSameWay() {
        val app = module("app", "product: jvm/lib\ntest-settings:\n  jvm:\n    freeJvmArgs: nope\n")

        assertEquals(
            "Expected a list at test-settings.jvm.freeJvmArgs",
            assertFailsWith<ConversionException> { interpret(app) }.message,
        )
    }

    private fun junit(value: String): ToolchainModule =
        module("app", "product: jvm/lib\nsettings:\n  junit: $value\n")

    /**
     * A `jvm/lib` has exactly one `Test` task, so a qualified section reaches the same one.
     *
     * It is still read last, because declaring a key under a qualifier is how a module overrides
     * what it said for every platform at once.
     */
    @Test
    fun aQualifiedJvmTestSettingJoinsTheOneTestTaskOfAJvmModule() {
        val build = interpret(
            module(
                "app",
                """
                product: jvm/lib
                settings:
                  jvm:
                    test:
                      systemProperties:
                        mode: module
                        kept: module
                settings@jvm:
                  jvm:
                    test:
                      systemProperties:
                        mode: qualified
                test-settings@jvm:
                  jvm:
                    freeJvmArgs: [-Xmx512m]
                """.trimIndent(),
            ),
        )

        assertEquals(
            JvmTestSettings(
                freeJvmArgs = listOf("-Xmx512m"),
                systemProperties = mapOf("mode" to "qualified", "kept" to "module"),
            ),
            build.testSettings,
        )
    }

    /**
     * `test-settings@q` is applied on top of `settings@q` even when the module wrote it first.
     *
     * The unqualified pair has that order fixed by the two fields it binds to; the qualified pair is
     * two sections of one map, so the order is a decision the merge has to make on its own.
     */
    @Test
    fun aQualifiedTestSettingsSectionIsAppliedLastWhicheverOrderItWasWrittenIn() {
        val build = interpret(
            module(
                "app",
                """
                product: jvm/lib
                test-settings@jvm:
                  jvm:
                    systemProperties:
                      mode: test-settings
                settings@jvm:
                  jvm:
                    test:
                      systemProperties:
                        mode: settings
                """.trimIndent(),
            ),
        )

        assertEquals(
            JvmTestSettings(systemProperties = mapOf("mode" to "test-settings")),
            build.testSettings,
        )
    }

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
            model = YamlBinder.bind(config, notation),
            layout = layout,
        )
    }

    private companion object {
        private val ROOT: Path = "/workspace".toPath()
    }
}
