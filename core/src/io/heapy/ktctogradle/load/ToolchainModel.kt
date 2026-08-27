package io.heapy.ktctogradle.load

/**
 * A module.yaml after template merging, as typed data.
 *
 * The model mirrors the YAML; it makes no Gradle decision and applies no default. Everything the
 * converter has to decide — default versions, default platforms, repository ids, plugin choice —
 * belongs to the interpret stage.
 *
 * Binding never fails. Every region that would raise a [io.heapy.ktctogradle.ConversionException]
 * today records its message in [errors] and binds to an empty value instead, so a failure keeps
 * surfacing where it surfaces today, with the same text and in the same order. See [errors].
 */
internal data class ToolchainModel(
    val product: ProductSpec,
    val layout: Layout,
    val aliases: Map<String, Set<String>>,
    /** Declared dependencies by qualifier; `""` holds the unqualified `dependencies:` section. */
    val dependencies: Map<String, List<RawDependency>>,
    val testDependencies: Map<String, List<RawDependency>>,
    val repositories: List<RawRepository>,
    val settings: Settings,
    /** `settings@<qualifier>` sections, keyed by qualifier. */
    val qualifiedSettings: Map<String, Settings>,
    /** `test-settings@<qualifier>` sections, keyed by qualifier. */
    val qualifiedTestSettings: Map<String, Settings>,
    /** Rejected keys in the order `rejectUnsupported` reports them. */
    val unsupported: List<String>,
    /**
     * The message of the failure a region defers, keyed by the region that defers it: `product`,
     * `aliases`, `repositories`, `settings`, `settings.kotlin.serialization`, or a dependency
     * section key such as `dependencies` or `test-dependencies@jvm`.
     *
     * A region that failed is bound as if it were absent, so a consumer must raise its message
     * before reading it. This is what keeps the binder lenient: `pluginsOf` swallows the failures
     * of `product` and of `settings.kotlin.serialization`, and `rejectUnsupported` reports
     * `plugins:` before any of them is consulted.
     */
    val errors: Map<String, String>,
)

internal enum class Layout { AMPER, MAVEN_LIKE }

internal data class ProductSpec(val type: String, val platforms: List<String>)

internal data class RawDependency(
    val notation: String,
    /** `all`, `compile-only` or `runtime-only`, as written. */
    val scope: String = "all",
    val exported: Boolean = false,
    val bom: Boolean = false,
)

internal data class RawRepository(
    /** `null` when the repository was written as a bare URL and has no id of its own. */
    val id: String?,
    val url: String?,
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
    /** The sibling `test-settings:` section, which only ever carries `jvm` keys. */
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
)

internal data class JvmSettings(
    /** `settings.jvm.jdk.version`, which is nested and not a `jdkVersion` key. */
    val jdkVersion: String? = null,
    val release: String? = null,
    val mainClass: String? = null,
    val testFreeJvmArgs: List<String> = emptyList(),
    val testSystemProperties: Map<String, String> = emptyMap(),
    val testExtraEnvironment: Map<String, String> = emptyMap(),
)

internal data class AndroidSettings(
    val namespace: String? = null,
    /** Accepts both `compileSdk: 37` and the nested `compileSdk.apiLevel: 37` form. */
    val compileSdk: String? = null,
    val minSdk: String? = null,
    val targetSdk: String? = null,
    val applicationId: String? = null,
    val versionCode: String? = null,
    val versionName: String? = null,
)

internal data class NativeSettings(val entryPoint: String? = null)

/** `enabled` is separate from presence: `ktor: enabled` and `ktor: { enabled: false }` both parse. */
internal data class KtorSettings(val enabled: Boolean? = null, val version: String? = null)

/** `null` version means the section named no version and the interpret stage picks the default. */
internal data class SerializationSpec(val version: String? = null, val format: String? = null)

internal data class TestSettings(
    val freeJvmArgs: List<String> = emptyList(),
    val systemProperties: Map<String, String> = emptyMap(),
    val extraEnvironment: Map<String, String> = emptyMap(),
)
