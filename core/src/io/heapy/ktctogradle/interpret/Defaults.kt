package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.load.ToolchainModel

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
     * Deliberately versionless: the module's JUnit metadata constrains the launcher through the
     * JUnit BOM. Pinning it here could move the module to a different JUnit major.
     */
    const val JUNIT_PLATFORM_LAUNCHER = "org.junit.platform:junit-platform-launcher"

    val ANDROID_NAMESPACE_PREFIX = listOf("ktc", "generated")
}

internal fun jvmRelease(model: ToolchainModel): String =
    model.settings.jvm?.release ?: model.settings.jvm?.jdkVersion ?: Defaults.JVM_JDK
