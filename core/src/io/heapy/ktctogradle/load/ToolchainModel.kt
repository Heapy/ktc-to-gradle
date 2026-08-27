package io.heapy.ktctogradle.load

import io.heapy.ktctogradle.ConversionException

/**
 * A module.yaml after template merging, as typed data.
 *
 * The model mirrors the YAML; it makes no Gradle decision and applies no default. Everything the
 * converter has to decide — default versions, default platforms, repository ids, plugin choice —
 * belongs to the interpret stage.
 *
 * Binding never fails. Every region that would raise a [ConversionException] today records its
 * message in [errors] and binds to an empty value instead, so a failure keeps surfacing from a stage
 * that reads the region, with the same text. Which failure a module with two independent defects
 * reports first is not guaranteed to match the pre-pipeline converter. See [errors].
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
    /** `settings@<qualifier>` and `test-settings@<qualifier>` sections, in declaration order. */
    val qualifiedSections: List<QualifiedSection>,
    /** Rejected keys in the order `rejectUnsupported` reports them. */
    val unsupported: List<String>,
    /**
     * The message of the failure a region defers, keyed by the region that defers it: `product`,
     * `aliases`, `repositories`, `settings`, `settings.jvm.test`, `settings.kotlin.serialization`,
     * a dependency section key such as `dependencies` or `test-dependencies@jvm`, or the
     * [Region.dependencyContent] form of such a key.
     *
     * A region that failed is bound as if it were absent, so a consumer must raise its message
     * before reading it. This is what keeps the binder lenient: `pluginsOf` swallows the failures
     * of `product` and of `settings.kotlin.serialization`, and `rejectUnsupported` reports
     * `plugins:` before any of them is consulted.
     */
    val errors: Map<String, String>,
)

/**
 * The keys [ToolchainModel.errors] is keyed by, spelled once.
 *
 * A raise site that misspells its region silently swallows a user-facing failure, and the same key
 * is read from as many as three files, so the strings live here rather than as a private const per
 * consumer. A dependency section has no const of its own: its key is the qualifier as written.
 */
internal object Region {
    const val PRODUCT = "product"
    const val ALIASES = "aliases"
    const val REPOSITORIES = "repositories"
    const val SETTINGS = "settings"
    const val SERIALIZATION = "settings.kotlin.serialization"

    /**
     * The JVM test task's argument list, which only a `jvm/app` or `jvm/lib` ever renders.
     *
     * Separate from [SETTINGS] because an Android or multiplatform module never reads it, and a
     * malformed value there has never failed such a conversion.
     */
    const val JVM_TEST_SETTINGS = "settings.jvm.test"

    /**
     * Where a dependency section defers what only a reader of that section decides: an unknown scope
     * shorthand, or a `bom` that is not a coordinate.
     *
     * The section's own key holds the failures the load stage gates on instead. The marker is a
     * *prefix* on purpose — a suffix would still satisfy the `startsWith` test that recognises a
     * dependency section, and the load stage would go back to failing qualifiers nobody reads.
     */
    fun dependencyContent(key: String): String = "content:$key"
}

/** Raises the failure [region] deferred, if it deferred one. */
internal fun ToolchainModel.raiseDeferred(region: String) {
    errors[region]?.let { message -> throw ConversionException(message) }
}

/**
 * One `settings@<qualifier>` or `test-settings@<qualifier>` section, as written.
 *
 * The sections keep the order they are declared in, because the diagnostics a malformed one
 * produces are reported in that order and their sequence is part of the converter's output.
 */
internal data class QualifiedSection(
    /** The key as written; a diagnostic quotes it verbatim. */
    val key: String,
    val qualifier: String,
    /** `true` for a `test-settings@` section, which the converter cannot carry at all. */
    val test: Boolean,
    /** `null` = the section is not an object, so it carries no settings. */
    val settings: Settings?,
    /**
     * The keys of the section the converter cannot carry into the Gradle build.
     *
     * Recorded here because a key such as `settings@jvm.foo.bar` has no field to bind to, and the
     * stage that reports it may not walk the YAML itself. Empty for a `test-settings@` section,
     * which is dropped whole and never inspected key by key.
     */
    val unsupportedKeys: List<UnsupportedKey>,
    /**
     * The [QualifiedOption] keys the section declares but got wrong, so [settings] binds them to
     * nothing.
     *
     * A malformed key is still a key the section *declared*, and declaring a key is how a narrower
     * section overrides a broader one. Without this the two are indistinguishable — both bind to
     * `null` — and a broken `settings@jvm` would silently let `settings@common`'s value through.
     * A `kotlin` node that is not an object is recorded as every option key at once, because it
     * replaces the whole node the broader section contributed.
     */
    val malformedOptions: Set<String>,
)

/**
 * The `settings@<qualifier>.kotlin` keys that reach a Gradle `compilerOptions { }` block.
 *
 * Named here rather than in the interpreter because the binder decides which of them a section got
 * wrong ([QualifiedSection.malformedOptions]) and the interpreter decides what a wrong one does to
 * the merge, and the two must agree on the spelling.
 */
internal object QualifiedOption {
    const val LANGUAGE_VERSION = "languageVersion"
    const val API_VERSION = "apiVersion"
    const val ALL_WARNINGS_AS_ERRORS = "allWarningsAsErrors"
    const val PROGRESSIVE_MODE = "progressiveMode"
    const val FREE_COMPILER_ARGS = "freeCompilerArgs"
    const val OPT_INS = "optIns"

    /** The node the six live under; a section that gets *it* wrong loses all six. */
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

/** A key of a qualified section that was dropped, and the wording the diagnostic uses to say so. */
internal data class UnsupportedKey(
    /** Leaf path inside the section, such as `jvm.release` or `kotlin.unknown`. */
    val path: String,
    val reason: String,
) {
    companion object {
        const val UNSUPPORTED = "is not supported by the converter"
    }
}

internal enum class Layout { AMPER, MAVEN_LIKE }

internal data class ProductSpec(val type: String, val platforms: List<String>)

/**
 * The `product:` types the Toolchain format defines, spelled once.
 *
 * Three `when` blocks branch on them — the default platforms of a type here in the load stage, the
 * build to interpret in `ProjectInterpreter`, and the plugins to apply in `PluginResolution` — and
 * a type missing from one of them falls into that block's `else`. Sharing the strings does not make
 * a new type reach all three, but it does keep a typo from silently routing a module to `else`.
 */
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

    /** The types a Kotlin Multiplatform build is generated for, whatever platforms they name. */
    val MULTIPLATFORM = setOf(KMP_LIB, JS_APP, WASM_JS_APP, WASM_WASI_APP, LINUX_APP, MACOS_APP, WINDOWS_APP)
}

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
    val url: String,
    val resolve: Boolean = true,
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
    val compilerPlugins: List<CompilerPluginSpec> = emptyList(),
)

/**
 * One `settings.kotlin.compilerPlugins` entry: a third-party Kotlin compiler plugin.
 *
 * [dependency] is the Maven coordinate the plugin is loaded from, and [id] is the plugin id its
 * [options] are addressed by. The Toolchain form has no fourth key.
 */
internal data class CompilerPluginSpec(
    val id: String,
    val dependency: String,
    val options: Map<String, String> = emptyMap(),
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
