package io.heapy.ktctogradle.interpret

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

    /** Prefix of the namespace the converter derives from a module path when the module names none. */
    val ANDROID_NAMESPACE_PREFIX = listOf("ktc", "generated")
}
