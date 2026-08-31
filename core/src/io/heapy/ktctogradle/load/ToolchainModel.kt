package io.heapy.ktctogradle.load

import io.heapy.ktctogradle.ConversionException

/**
 * Typed module YAML with no Gradle defaults or decisions. Binding records regional failures in
 * [errors] instead of throwing, so they surface when the corresponding value is consumed.
 */
internal data class ToolchainModel(
    val product: ProductSpec,
    val layout: Layout,
    val description: String? = null,
    val aliases: Map<String, Set<String>>,
    /** `""` holds the unqualified dependency section. */
    val dependencies: Map<String, List<RawDependency>>,
    val testDependencies: Map<String, List<RawDependency>>,
    val repositories: List<RawRepository>,
    val settings: Settings,
    val qualifiedSections: List<QualifiedSection>,
    val unsupported: List<String>,
    /**
     * Keys the binder never consumes, in declaration order. Unlike fixed [unsupported] sections,
     * these become warnings; `ModuleSchema.kt` defines the consumed surface.
     */
    val unknownKeys: List<String>,
    /** Regional failures; a failed region binds as absent and consumers raise it explicitly. */
    val errors: Map<String, String>,
)

internal object Region {
    const val PRODUCT = "product"
    const val ALIASES = "aliases"
    const val REPOSITORIES = "repositories"
    const val SETTINGS = "settings"
    const val SERIALIZATION = "settings.kotlin.serialization"

    /** Separate because products without JVM-backed tests must not raise this region. */
    const val JVM_TEST_SETTINGS = "settings.jvm.test"

    /** Prefixes reader-only failures so load-stage dependency matching does not raise them. */
    fun dependencyContent(key: String): String = "content:$key"
}

internal fun ToolchainModel.raiseDeferred(region: String) {
    errors[region]?.let { message -> throw ConversionException(message) }
}

/** A qualified section in declaration order, which is also diagnostic order. */
internal data class QualifiedSection(
    val key: String,
    val qualifier: String,
    /** `test-settings@` contributions sort after `settings@` for the same qualifier. */
    val test: Boolean,
    val settings: Settings?,
    /** Dropped keys recorded for later diagnostics. */
    val unsupportedKeys: List<UnsupportedKey>,
    /**
     * Declared-but-malformed compiler options, kept distinct from absent values so a narrower broken
     * declaration still suppresses the broader value it replaces.
     */
    val malformedOptions: Set<String>,
)

/** Shared option keys used by binding and qualified-section merging. */
internal object QualifiedOption {
    const val LANGUAGE_VERSION = "languageVersion"
    const val API_VERSION = "apiVersion"
    const val ALL_WARNINGS_AS_ERRORS = "allWarningsAsErrors"
    const val PROGRESSIVE_MODE = "progressiveMode"
    const val FREE_COMPILER_ARGS = "freeCompilerArgs"
    const val OPT_INS = "optIns"

    const val KOTLIN = "kotlin"

    val ALL = setOf(
        LANGUAGE_VERSION,
        API_VERSION,
        ALL_WARNINGS_AS_ERRORS,
        PROGRESSIVE_MODE,
        FREE_COMPILER_ARGS,
        OPT_INS,
    )
}

internal data class UnsupportedKey(
    /** Display-only leaf path; [options] carries structure because YAML keys may contain dots. */
    val path: String,
    val reason: String,
    /** Compiler options represented by this dropped key; empty for unrelated keys. */
    val options: Set<String> = emptySet(),
) {
    companion object {
        const val UNSUPPORTED = "is not supported by the converter"
    }
}

internal enum class Layout { AMPER, MAVEN_LIKE }

internal data class ProductSpec(val type: String, val platforms: List<String>)

internal object ProductType {
    const val JVM_APP = "jvm/app"
    const val JVM_LIB = "jvm/lib"
    const val JVM_AMPER_PLUGIN = "jvm/amper-plugin"
    const val ANDROID_APP = "android/app"
    const val IOS_APP = "ios/app"
    const val KMP_LIB = "kmp/lib"
    const val JS_APP = "js/app"
    const val WASM_JS_APP = "wasm-js/app"
    const val WASM_WASI_APP = "wasm-wasi/app"
    const val LINUX_APP = "linux/app"
    const val MACOS_APP = "macos/app"
    const val WINDOWS_APP = "windows/app"

    val MULTIPLATFORM = setOf(KMP_LIB, JS_APP, WASM_JS_APP, WASM_WASI_APP, LINUX_APP, MACOS_APP, WINDOWS_APP)
}

internal data class RawDependency(
    val notation: String,
    val scope: String = "all",
    val exported: Boolean = false,
    val bom: Boolean = false,
)

internal data class RawRepository(
    val id: String?,
    val url: String,
    val resolve: Boolean = true,
    val publish: Boolean = false,
    val credentials: RawCredentials? = null,
)

internal data class RawCredentials(
    val file: String,
    val usernameKey: String,
    val passwordKey: String,
)

internal data class Settings(
    val kotlin: KotlinSettings? = null,
    val jvm: JvmSettings? = null,
    val android: AndroidSettings? = null,
    val native: NativeSettings? = null,
    val junit: String? = null,
    val ktor: KtorSettings? = null,
    val publishing: PublishingSettings? = null,
    val test: TestSettings? = null,
) {
    companion object {
        val EMPTY = Settings()
    }
}

internal data class KotlinSettings(
    val version: String? = null,
    val languageVersion: String? = null,
    val apiVersion: String? = null,
    val allWarningsAsErrors: Boolean? = null,
    val progressiveMode: Boolean? = null,
    val freeCompilerArgs: List<String> = emptyList(),
    val optIns: List<String> = emptyList(),
    val serialization: SerializationSpec? = null,
    val compilerPlugins: List<CompilerPluginSpec> = emptyList(),
)

internal data class CompilerPluginSpec(
    val id: String,
    val dependency: String,
    val options: Map<String, String> = emptyMap(),
)

internal data class JvmSettings(
    val jdkVersion: String? = null,
    val release: String? = null,
    val mainClass: String? = null,
    val testFreeJvmArgs: List<String> = emptyList(),
    val testSystemProperties: Map<String, String> = emptyMap(),
    val testExtraEnvironment: Map<String, String> = emptyMap(),
    /** Bound only so the interpreter can report that Gradle cannot honor it. */
    val testJunitPlatformVersion: String? = null,
)

internal data class AndroidSettings(
    val namespace: String? = null,
    val compileSdk: String? = null,
    val minSdk: String? = null,
    val targetSdk: String? = null,
    val applicationId: String? = null,
    val versionCode: String? = null,
    val versionName: String? = null,
)

internal data class NativeSettings(val entryPoint: String? = null)

internal data class KtorSettings(val enabled: Boolean? = null, val version: String? = null)

internal data class SerializationSpec(val version: String? = null, val format: String? = null)

internal data class TestSettings(
    val freeJvmArgs: List<String> = emptyList(),
    val systemProperties: Map<String, String> = emptyMap(),
    val extraEnvironment: Map<String, String> = emptyMap(),
    /** Separate from the published release so tests may compile against newer JDK APIs. */
    val release: String? = null,
)

internal data class PublishingSettings(
    val enabled: Boolean? = null,
    val group: String? = null,
    val artifactId: String? = null,
    val version: String? = null,
    val publishSources: Boolean? = null,
    val signArtifacts: Boolean? = null,
    /** Which checksums to publish. The Toolchain default is `[md5, sha1]`. */
    val checksums: List<String> = emptyList(),
    val mavenCentral: MavenCentralSpec? = null,
    val pom: PomSpec? = null,
)

internal data class MavenCentralSpec(
    val enabled: Boolean? = null,
    val publishingMode: String? = null,
)

internal data class PomSpec(
    val name: String? = null,
    val description: String? = null,
    val url: String? = null,
    val licenses: List<PomLicense> = emptyList(),
    val developers: List<PomDeveloper> = emptyList(),
    val scm: PomScm? = null,
)

internal data class PomLicense(val name: String? = null, val url: String? = null)

internal data class PomDeveloper(
    val id: String? = null,
    val name: String? = null,
    val url: String? = null,
    val email: String? = null,
    val organization: String? = null,
    val organizationUrl: String? = null,
)

/** Scalar `scm` expands both connection strings to `scm:git:<url>`. */
internal data class PomScm(
    val url: String? = null,
    val connection: String? = null,
    val developerConnection: String? = null,
)
