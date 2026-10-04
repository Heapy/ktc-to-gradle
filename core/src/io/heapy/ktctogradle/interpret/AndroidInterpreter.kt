package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.DiagnosticCollector
import io.heapy.ktctogradle.Versions
import io.heapy.ktctogradle.load.ModuleIndex
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.raiseDeferred
import io.heapy.ktctogradle.model.AndroidBuild
import io.heapy.ktctogradle.model.AndroidLibraryTarget
import io.heapy.ktctogradle.model.JvmTestSettings
import kotlin.math.absoluteValue

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
        val namespace = android?.namespace?.takeIf(String::isNotBlank) ?: throw ConversionException(
            "${module.displayName}: settings.android.namespace is required for android/app",
        )
        val compileSdk = android.compileSdk ?: Defaults.ANDROID_COMPILE_SDK
        val dependencies = Dependencies.of(index, module, test = false, qualifiers = QUALIFIERS) +
            JvmInterpreter.implied(model, serialization)
        val testFramework = JvmInterpreter.testFramework(model)
        JvmInterpreter.warnAboutJunitNone(module, testFramework, diagnostics)
        JvmInterpreter.warnAboutJunitPlatformVersion(module, diagnostics)
        val testDependencies = Dependencies.of(index, module, test = true, qualifiers = QUALIFIERS) +
            JvmInterpreter.platformLauncher(testFramework)
        // Read last to keep the pinned-version warning ahead of qualified-section diagnostics.
        val qualified = QualifiedSettings.singlePlatform(module, "android", diagnostics)
        return AndroidBuild(
            namespace = namespace,
            // Kotlin Toolchain defaults an omitted application id to the namespace.
            applicationId = android.applicationId ?: namespace,
            compileSdk = compileSdk,
            minSdk = android.minSdk ?: Defaults.ANDROID_MIN_SDK,
            // Kotlin Toolchain defaults the target SDK to the compile SDK.
            targetSdk = android.targetSdk ?: compileSdk,
            versionCode = android.versionCode ?: Defaults.ANDROID_VERSION_CODE,
            versionName = android.versionName ?: Defaults.ANDROID_VERSION_NAME,
            release = release,
            compilerOptions = JvmInterpreter.compilerOptions(model.settings.kotlin, jvmTarget = release),
            qualifiedCompilerOptions = qualified.options,
            dependencies = dependencies,
            testDependencies = testDependencies,
            testFramework = testFramework,
            testSettings = JvmInterpreter.testSettings(model) + qualified.testSettings,
        )
    }

    fun libraryTarget(
        module: ToolchainModule,
        testSettings: JvmTestSettings,
        diagnostics: DiagnosticCollector,
    ): AndroidLibraryTarget {
        val model = module.model
        model.raiseDeferred(Region.SETTINGS)
        val android = model.settings.android
        val namespace = android?.namespace ?: derivedNamespace(module)
        return AndroidLibraryTarget(
            namespace = namespace,
            compileSdk = android?.compileSdk ?: Defaults.ANDROID_COMPILE_SDK,
            minSdk = android?.minSdk ?: Defaults.ANDROID_MIN_SDK,
            release = jvmRelease(model),
            testRelease = model.settings.test?.release,
            testSettings = testSettings,
        )
    }

    /** Mirrors AndroidSettings.effectiveNamespace in Kotlin Toolchain 0.13.0. */
    fun derivedNamespace(module: ToolchainModule): String {
        val publishing = module.model.settings.publishing
        if (publishing?.group != null) {
            val artifactId = (publishing.artifactId ?: module.directory.name).replace('-', '_')
            val identifier = if (artifactId.firstOrNull()?.let { it == '_' || it == '$' || it.isLetter() } == true) {
                artifactId
            } else {
                "_$artifactId"
            }
            return "${publishing.group}.$identifier"
        }
        return "org.jetbrains.ktc.mangled.p${module.directory.name.hashCode().absoluteValue}"
    }

    private val QUALIFIERS = listOf("", "android")
}
