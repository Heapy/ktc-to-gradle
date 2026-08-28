package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.load.ToolchainModel

/**
 * Every value the converter supplies when a module.yaml leaves one out.
 *
 * They live together because they are decisions, not constants of the format: the interpret stage
 * owns them, and neither the load stage nor the renderers may apply one.
 */
internal object Defaults {
    const val JVM_JDK = "25"
    const val ANDROID_RELEASE = "17"
    const val ANDROID_COMPILE_SDK = "37"
    const val ANDROID_MIN_SDK = "24"
    const val ANDROID_NAMESPACE_FALLBACK = "org.example.namespace"
    const val ANDROID_VERSION_CODE = "1"
    const val ANDROID_VERSION_NAME = "unspecified"
    const val KTOR = "3.5.2"
    const val SERIALIZATION = "1.11.0"
    const val JUNIT = "junit-5"
    const val MAVEN_CENTRAL_URL = "https://repo1.maven.org/maven2"
    const val GOOGLE_MAVEN_URL = "https://maven.google.com"

    /**
     * Written without a version on purpose.
     *
     * Every JUnit artifact imports `org.junit:junit-bom` through its Gradle module metadata, so a
     * module that brought an engine has already constrained the launcher to that engine's platform
     * version. The versionless coordinate picks it up and the two always match.
     *
     * A version of our own would not. It is the BOM that aligns the family, so pinning the launcher
     * pulls every other JUnit module to the pinned release: preferring `6.0.1` next to a declared
     * `junit-jupiter:5.14.1` resolves the whole graph to `6.0.1`, and the module's tests then compile
     * against a JUnit major it did not ask for.
     *
     * The cost is that a JVM-backed test classpath carrying no JUnit artifact at all constrains
     * nothing, and Gradle fails it with `Could not find org.junit.platform:junit-platform-launcher:`.
     * That is a module whose tests `settings.junit: none` cannot run either way, and the warning the
     * interpreters raise for `none` names the dependency it is missing.
     */
    const val JUNIT_PLATFORM_LAUNCHER = "org.junit.platform:junit-platform-launcher"

    /** Prefix of the namespace the converter derives from a module path when the module names none. */
    val ANDROID_NAMESPACE_PREFIX = listOf("ktc", "generated")
}

/**
 * The bytecode level a JVM compilation of [model] targets.
 *
 * Shared by every JVM-flavoured target of a multiplatform module, because a module that publishes
 * two of them must publish both at the same class-file version.
 */
internal fun jvmRelease(model: ToolchainModel): String =
    model.settings.jvm?.release ?: model.settings.jvm?.jdkVersion ?: Defaults.JVM_JDK
