package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.DiagnosticCollector
import io.heapy.ktctogradle.load.KotlinSettings
import io.heapy.ktctogradle.load.ModuleIndex
import io.heapy.ktctogradle.load.ProductType
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.ToolchainModel
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.raiseDeferred
import io.heapy.ktctogradle.model.CompilerOptions
import io.heapy.ktctogradle.model.Dependency
import io.heapy.ktctogradle.model.DependencyTarget
import io.heapy.ktctogradle.model.JvmBuild
import io.heapy.ktctogradle.model.JvmTestSettings
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
        val testDependencies = Dependencies.of(index, module, test = true, qualifiers = QUALIFIERS)
        return JvmBuild(
            jdk = jdk,
            release = release,
            compilerOptions = compilerOptions(model.settings.kotlin, jvmTarget = release),
            qualifiedCompilerOptions = qualified,
            layout = if (model.layout == RawLayout.MAVEN_LIKE) GradleLayout.MAVEN_LIKE else GradleLayout.AMPER,
            dependencies = declared + implied(model, serialization),
            testDependencies = testDependencies,
            testFramework = testFramework,
            testSettings = testSettings(model),
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
     * `test-settings:` is applied on top of `settings.jvm.test`, so a key declared in both keeps the
     * test-specific value while the rest of the base section survives.
     */
    private fun testSettings(model: ToolchainModel): JvmTestSettings {
        // Only this interpreter renders a test task, so this is the one place the argument list a
        // module got wrong can be reported.
        model.raiseDeferred(Region.JVM_TEST_SETTINGS)
        return JvmTestSettings(
            freeJvmArgs = model.settings.jvm?.testFreeJvmArgs.orEmpty() + model.settings.test?.freeJvmArgs.orEmpty(),
            systemProperties = model.settings.jvm?.testSystemProperties.orEmpty() +
                model.settings.test?.systemProperties.orEmpty(),
            environment = model.settings.jvm?.testExtraEnvironment.orEmpty() +
                model.settings.test?.extraEnvironment.orEmpty(),
        )
    }

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
