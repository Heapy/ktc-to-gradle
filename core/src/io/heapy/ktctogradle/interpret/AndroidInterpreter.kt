package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.DiagnosticCollector
import io.heapy.ktctogradle.Versions
import io.heapy.ktctogradle.load.ModuleIndex
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.raiseDeferred
import io.heapy.ktctogradle.model.AndroidBuild
import io.heapy.ktctogradle.model.AndroidLibraryTarget

/**
 * Turns an `android/app` module, and the `android` target of a multiplatform module, into the
 * Gradle build they stand for.
 *
 * The Android Gradle Plugin brings its own Kotlin compiler, so `settings.kotlin.version` decides
 * nothing here and saying so is the module's only Android-specific diagnostic.
 */
internal object AndroidInterpreter {
    fun interpret(index: ModuleIndex, module: ToolchainModule, diagnostics: DiagnosticCollector): AndroidBuild {
        val model = module.model
        model.raiseDeferred(Region.SETTINGS)
        model.settings.kotlin?.version?.let { pinned ->
            diagnostics.warn(
                "${module.displayName}: settings.kotlin.version '$pinned' does not select the Kotlin " +
                    "compiler for an Android module; the Android Gradle Plugin ${Versions.ANDROID_GRADLE_PLUGIN} " +
                    "supplies its own Kotlin",
            )
        }
        val serialization = Serialization.of(model)
        val android = model.settings.android
        val release = model.settings.jvm?.release ?: Defaults.ANDROID_RELEASE
        val namespace = android?.namespace ?: Defaults.ANDROID_NAMESPACE_FALLBACK
        val compileSdk = android?.compileSdk ?: Defaults.ANDROID_COMPILE_SDK
        val dependencies = Dependencies.of(index, module, test = false, qualifiers = QUALIFIERS) +
            JvmInterpreter.implied(model, serialization)
        val testFramework = JvmInterpreter.testFramework(model)
        val testDependencies = Dependencies.of(index, module, test = true, qualifiers = QUALIFIERS)
        // Read last: the module's own pinned-Kotlin-version warning keeps its place ahead of the
        // dropped-key ones a qualified section reports.
        val qualified = QualifiedSettings.singlePlatform(module, "android", diagnostics)
        return AndroidBuild(
            namespace = namespace,
            // An application id the module leaves out is the namespace, which is what the Toolchain
            // installs the application under.
            applicationId = android?.applicationId ?: namespace,
            compileSdk = compileSdk,
            minSdk = android?.minSdk ?: Defaults.ANDROID_MIN_SDK,
            // Building against an SDK the application then refuses to target is never what a module
            // meant, so the target level follows the compile level rather than a constant.
            targetSdk = android?.targetSdk ?: compileSdk,
            versionCode = android?.versionCode ?: Defaults.ANDROID_VERSION_CODE,
            versionName = android?.versionName ?: Defaults.ANDROID_VERSION_NAME,
            release = release,
            compilerOptions = JvmInterpreter.compilerOptions(model.settings.kotlin, jvmTarget = release),
            qualifiedCompilerOptions = qualified,
            dependencies = dependencies,
            testDependencies = testDependencies,
            testFramework = testFramework,
        )
    }

    /** The `androidLibrary { }` target of a multiplatform module that declares the `android` platform. */
    fun libraryTarget(module: ToolchainModule, diagnostics: DiagnosticCollector): AndroidLibraryTarget {
        val model = module.model
        model.raiseDeferred(Region.SETTINGS)
        val android = model.settings.android
        val namespace = android?.namespace ?: derivedNamespace(module).also {
            diagnostics.warn("${module.displayName}: settings.android.namespace is not set; using '$it'")
        }
        return AndroidLibraryTarget(
            namespace = namespace,
            compileSdk = android?.compileSdk ?: Defaults.ANDROID_COMPILE_SDK,
            minSdk = android?.minSdk ?: Defaults.ANDROID_MIN_SDK,
            release = jvmRelease(model),
        )
    }

    /**
     * The Toolchain synthesizes an internal package when a module leaves the namespace out,
     * but the Android Gradle Plugin insists on a real one, so derive a stable package here.
     */
    fun derivedNamespace(module: ToolchainModule): String {
        val segments = module.path.segments.ifEmpty { listOf(module.displayName) }
        val packageSegments = segments.map { segment ->
            val sanitized = segment.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
            if (sanitized.firstOrNull()?.isDigit() != false) "_$sanitized" else sanitized
        }
        return (Defaults.ANDROID_NAMESPACE_PREFIX + packageSegments).joinToString(".")
    }

    /** The unqualified section and the `@android` one, in the order the Toolchain applies them. */
    private val QUALIFIERS = listOf("", "android")
}
