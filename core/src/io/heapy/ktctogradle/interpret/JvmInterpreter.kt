package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.DiagnosticCollector
import io.heapy.ktctogradle.load.KotlinSettings
import io.heapy.ktctogradle.load.ModuleIndex
import io.heapy.ktctogradle.load.ProductType
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.Settings
import io.heapy.ktctogradle.load.ToolchainModel
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.raiseDeferred
import io.heapy.ktctogradle.model.CompilerOptions
import io.heapy.ktctogradle.model.Dependency
import io.heapy.ktctogradle.model.DependencyTarget
import io.heapy.ktctogradle.model.JvmBuild
import io.heapy.ktctogradle.model.JvmTestSettings
import io.heapy.ktctogradle.model.Scope
import io.heapy.ktctogradle.model.TestFramework
import io.heapy.ktctogradle.load.Layout as RawLayout
import io.heapy.ktctogradle.model.Layout as GradleLayout

/**
 * Turns a `jvm/lib` or `jvm/app` module into the Gradle build it stands for.
 *
 * Every default, every implied dependency and every test-framework choice is decided here, so the
 * renderer only spells out what this returns.
 */
internal object JvmInterpreter {
    fun interpret(index: ModuleIndex, module: ToolchainModule, diagnostics: DiagnosticCollector): JvmBuild {
        val model = module.model
        // Read first: the qualified sections report dropped keys, and those are ordered ahead of the
        // missing-main-class warning this interpreter ends with.
        val qualified = QualifiedSettings.singlePlatform(module, "jvm", diagnostics)
        model.raiseDeferred(Region.SETTINGS)
        val jdk = model.settings.jvm?.jdkVersion ?: Defaults.JVM_JDK
        val release = model.settings.jvm?.release ?: jdk
        val serialization = Serialization.of(model)
        val declared = Dependencies.of(index, module, test = false, qualifiers = QUALIFIERS)
        val testFramework = testFramework(model)
        warnAboutJunitNone(module, testFramework, diagnostics)
        val testDependencies = Dependencies.of(index, module, test = true, qualifiers = QUALIFIERS) +
            platformLauncher(testFramework)
        return JvmBuild(
            jdk = jdk,
            release = release,
            testRelease = model.settings.test?.release,
            compilerOptions = compilerOptions(model.settings.kotlin, jvmTarget = release),
            qualifiedCompilerOptions = qualified.options,
            layout = if (model.layout == RawLayout.MAVEN_LIKE) GradleLayout.MAVEN_LIKE else GradleLayout.AMPER,
            dependencies = declared + implied(model, serialization),
            testDependencies = testDependencies,
            testFramework = testFramework,
            // One `Test` task, so a section that names the module's only platform reaches the same
            // task the module-wide keys do, and overrides them by being read last.
            testSettings = testSettings(model) + qualified.testSettings,
            mainClass = mainClass(module, diagnostics),
        )
    }

    /** The Kotlin test artifact and the JUnit platform wiring a module asks for. */
    fun testFramework(model: ToolchainModel): TestFramework = when (model.settings.junit ?: Defaults.JUNIT) {
        "junit-5" -> TestFramework.JUNIT_5
        "junit-4" -> TestFramework.JUNIT_4
        "none" -> TestFramework.NONE
        else -> throw ConversionException("settings.junit must be junit-5, junit-4, or none")
    }

    /**
     * The one place `settings.junit: none` is not converted faithfully, said out loud.
     *
     * The Kotlin Toolchain runs every JVM test through `junit-platform-console-standalone`, and that
     * artifact carries the Jupiter and Vintage engines. So `none` upstream means "no `kotlin-test`
     * JUnit adapter" while an engine is there regardless, and a module testing against
     * `junit-jupiter-api` alone still runs.
     *
     * Gradle has no equivalent: a `Test` task discovers nothing an engine on its own runtime
     * classpath does not find. The converted build therefore takes the engine from the module, and
     * a JVM-backed platform the module named none for runs no tests where the Toolchain ran them.
     *
     * Raised once per module rather than per platform, because the module is where the setting is.
     */
    fun warnAboutJunitNone(module: ToolchainModule, framework: TestFramework, diagnostics: DiagnosticCollector) {
        if (framework != TestFramework.NONE) return
        diagnostics.warn(
            "${module.displayName}: settings.junit: none keeps the JUnit platform but adds no engine, " +
                "and the Kotlin Toolchain supplies one of its own; declare a JUnit platform engine in " +
                "the test dependencies of every JVM-backed platform",
        )
    }

    /**
     * The JUnit platform launcher, for the one framework whose adapter does not bring it.
     *
     * `kotlin-test-junit5` depends on the launcher, so a `junit-5` module already has one.
     * `settings.junit: none` adds no adapter at all, and Gradle refuses to run `useJUnitPlatform()`
     * without a launcher on the test runtime classpath, so that module gets it named directly.
     */
    fun platformLauncher(framework: TestFramework): List<Dependency> = when (framework) {
        TestFramework.NONE -> listOf(
            Dependency(DependencyTarget.Maven(Defaults.JUNIT_PLATFORM_LAUNCHER), scope = Scope.RUNTIME_ONLY),
        )
        else -> emptyList()
    }

    /**
     * The module-wide `compilerOptions` body.
     *
     * A flag that is off is left unset rather than set to false: the module-wide block is the
     * baseline every target inherits, and turning a flag off is what a qualified section is for.
     */
    fun compilerOptions(kotlin: KotlinSettings?, jvmTarget: String? = null): CompilerOptions = CompilerOptions(
        languageVersion = kotlin?.languageVersion,
        apiVersion = kotlin?.apiVersion,
        jvmTarget = jvmTarget,
        allWarningsAsErrors = if (kotlin?.allWarningsAsErrors == true) true else null,
        progressiveMode = if (kotlin?.progressiveMode == true) true else null,
        freeArgs = kotlin?.freeCompilerArgs.orEmpty(),
        optIns = kotlin?.optIns.orEmpty(),
    )

    /** The runtimes a `settings:` section pulls in without naming them as dependencies. */
    fun implied(model: ToolchainModel, serialization: SerializationSettings?): List<Dependency> = buildList {
        if (serialization != null) {
            add(Dependency(DependencyTarget.Maven(Serialization.coordinate("core", serialization.version))))
            serialization.format?.let { format ->
                add(Dependency(DependencyTarget.Maven(Serialization.coordinate(format, serialization.version))))
            }
        }
        val ktor = model.settings.ktor
        if (ktor?.enabled == true) {
            add(Dependency(DependencyTarget.Maven("io.ktor:ktor-bom:${ktor.version ?: Defaults.KTOR}"), bom = true))
        }
    }

    /**
     * The test settings of a module that renders a `Test` task, which is what makes an argument list
     * the module got wrong a failure rather than a value nobody reads.
     */
    fun testSettings(model: ToolchainModel): JvmTestSettings {
        model.raiseDeferred(Region.JVM_TEST_SETTINGS)
        return declaredTestSettings(model)
    }

    /**
     * The same two sections, read without raising: what a module declared, whether or not it has a
     * task to apply it to.
     *
     * `test-settings:` is applied on top of `settings.jvm.test`, so a key declared in both keeps the
     * test-specific value while the rest of the base section survives.
     */
    fun declaredTestSettings(model: ToolchainModel): JvmTestSettings = declaredTestSettings(model.settings)

    /**
     * The same two sections of any one `settings:` body, which a qualified section is too.
     *
     * `settings@jvm.jvm.test` and `test-settings@jvm.jvm` are the platform-qualified spellings of
     * the same pair, and they bind to the same two fields, so they are read by the same rule.
     */
    fun declaredTestSettings(settings: Settings): JvmTestSettings = JvmTestSettings(
        freeJvmArgs = settings.jvm?.testFreeJvmArgs.orEmpty(),
        systemProperties = settings.jvm?.testSystemProperties.orEmpty(),
        environment = settings.jvm?.testExtraEnvironment.orEmpty(),
    ) + JvmTestSettings(
        freeJvmArgs = settings.test?.freeJvmArgs.orEmpty(),
        systemProperties = settings.test?.systemProperties.orEmpty(),
        environment = settings.test?.extraEnvironment.orEmpty(),
    )

    /**
     * The entry point of a `jvm/app`, or `null` when the module builds no application.
     *
     * A library never gets an `application { }` block, even when it happens to contain a `main.kt`.
     */
    private fun mainClass(module: ToolchainModule, diagnostics: DiagnosticCollector): String? {
        if (module.model.product.type != ProductType.JVM_APP) return null
        val mainClass = module.model.settings.jvm?.mainClass ?: module.layout.detectedMainClass
        if (mainClass == null) {
            diagnostics.warn(
                "${module.displayName}: could not infer a main class; set settings.jvm.mainClass or application.mainClass",
            )
        }
        return mainClass
    }

    /** The unqualified section and the `@jvm` one, in the order the Toolchain applies them. */
    private val QUALIFIERS = listOf("", "jvm")
}
