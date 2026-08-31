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

internal object JvmInterpreter {
    fun interpret(index: ModuleIndex, module: ToolchainModule, diagnostics: DiagnosticCollector): JvmBuild {
        val model = module.model
        // Read first so qualified-section diagnostics precede the missing-main-class warning.
        val qualified = QualifiedSettings.singlePlatform(module, "jvm", diagnostics)
        model.raiseDeferred(Region.SETTINGS)
        val jdk = model.settings.jvm?.jdkVersion ?: Defaults.JVM_JDK
        val release = model.settings.jvm?.release ?: jdk
        val serialization = Serialization.of(model)
        val declared = Dependencies.of(index, module, test = false, qualifiers = QUALIFIERS)
        val testFramework = testFramework(model)
        warnAboutJunitNone(module, testFramework, diagnostics)
        warnAboutJunitPlatformVersion(module, diagnostics)
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
            testSettings = testSettings(model) + qualified.testSettings,
            mainClass = mainClass(module, diagnostics),
        )
    }

    fun testFramework(model: ToolchainModel): TestFramework = when (model.settings.junit ?: Defaults.JUNIT) {
        "junit-5" -> TestFramework.JUNIT_5
        "junit-4" -> TestFramework.JUNIT_4
        "none" -> TestFramework.NONE
        else -> throw ConversionException("settings.junit must be junit-5, junit-4, or none")
    }

    /**
     * Reports the unavoidable `junit: none` mismatch: Toolchain supplies platform engines, while a
     * Gradle `Test` task can use only engines on the module's runtime classpath.
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
     * Drops `junitPlatformVersion`: pinning Gradle's launcher would force the module's entire JUnit
     * family through the BOM and could change the declared major version.
     */
    fun warnAboutJunitPlatformVersion(module: ToolchainModule, diagnostics: DiagnosticCollector) {
        val version = module.model.settings.jvm?.testJunitPlatformVersion ?: return
        diagnostics.warn(
            "${module.displayName}: settings.jvm.test.junitPlatformVersion '$version' has no Gradle " +
                "equivalent and was dropped; the generated build runs the JUnit platform its test " +
                "dependencies resolve to",
        )
    }

    /** Adds the launcher only for `junit: none`; the JUnit 5 adapter already supplies one. */
    fun platformLauncher(framework: TestFramework): List<Dependency> = when (framework) {
        TestFramework.NONE -> listOf(
            Dependency(DependencyTarget.Maven(Defaults.JUNIT_PLATFORM_LAUNCHER), scope = Scope.RUNTIME_ONLY),
        )
        else -> emptyList()
    }

    /** Leaves disabled flags unset so a qualified section can explicitly override inheritance. */
    fun compilerOptions(kotlin: KotlinSettings?, jvmTarget: String? = null): CompilerOptions = CompilerOptions(
        languageVersion = kotlin?.languageVersion,
        apiVersion = kotlin?.apiVersion,
        jvmTarget = jvmTarget,
        allWarningsAsErrors = if (kotlin?.allWarningsAsErrors == true) true else null,
        progressiveMode = if (kotlin?.progressiveMode == true) true else null,
        freeArgs = kotlin?.freeCompilerArgs.orEmpty(),
        optIns = kotlin?.optIns.orEmpty(),
    )

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

    /** Raises deferred test-setting failures because the module has a `Test` task to consume them. */
    fun testSettings(model: ToolchainModel): JvmTestSettings {
        model.raiseDeferred(Region.JVM_TEST_SETTINGS)
        return declaredTestSettings(model)
    }

    /** Reads declarations without raising; `test-settings` overrides `settings.jvm.test` by key. */
    fun declaredTestSettings(model: ToolchainModel): JvmTestSettings = declaredTestSettings(model.settings)

    fun declaredTestSettings(settings: Settings): JvmTestSettings = JvmTestSettings(
        freeJvmArgs = settings.jvm?.testFreeJvmArgs.orEmpty(),
        systemProperties = settings.jvm?.testSystemProperties.orEmpty(),
        environment = settings.jvm?.testExtraEnvironment.orEmpty(),
    ) + JvmTestSettings(
        freeJvmArgs = settings.test?.freeJvmArgs.orEmpty(),
        systemProperties = settings.test?.systemProperties.orEmpty(),
        environment = settings.test?.extraEnvironment.orEmpty(),
    )

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

    private val QUALIFIERS = listOf("", "jvm")
}
