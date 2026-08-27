package io.heapy.ktctogradle.model

import okio.Path

internal data class GeneratedFile(val path: Path, val content: String)

/**
 * A set of plugins Gradle has to load at one single version for the whole build.
 *
 * [displayName] is the name the multi-version warning quotes, so it is part of the user-visible
 * output and not a label.
 */
internal enum class PluginFamily(val displayName: String) {
    KOTLIN("kotlin"),
    ANDROID("android"),
}

/**
 * A Gradle plugin the converter can apply, as identity rather than as DSL text.
 *
 * Keeping the set closed is what keeps Gradle DSL syntax out of the model: only
 * `render/ModuleRenderer.kt` knows how a plugin is spelled, and its `when` is exhaustive.
 */
internal sealed interface GradlePlugin {
    /** Fully qualified plugin id. Identity for deduplication and version resolution. */
    val id: String

    /** `null` = unversioned: never version-resolved, never declared in the root. */
    val family: PluginFamily?

    /** Applied through the Kotlin plugin shorthand, by [shortName]. */
    enum class Kotlin(val shortName: String) : GradlePlugin {
        JVM("jvm"),
        MULTIPLATFORM("multiplatform"),
        SERIALIZATION("plugin.serialization"),
        ;

        override val id: String get() = "org.jetbrains.kotlin.$shortName"
        override val family: PluginFamily get() = PluginFamily.KOTLIN
    }

    /** Applied by plugin [id]. */
    enum class Android(override val id: String) : GradlePlugin {
        APPLICATION("com.android.application"),
        KMP_LIBRARY("com.android.kotlin.multiplatform.library"),
        ;

        override val family: PluginFamily get() = PluginFamily.ANDROID
    }

    /** Gradle's own plugins. Applied bare, with no version and no root declaration. */
    enum class Builtin(override val id: String) : GradlePlugin {
        APPLICATION("application"),
        BASE("base"),
        ;

        override val family: PluginFamily? get() = null
    }

    /** Escape hatch for a plugin this converter does not model yet. */
    data class Other(
        override val id: String,
        override val family: PluginFamily?,
    ) : GradlePlugin
}

/**
 * One line of a `plugins { }` block, after the project-wide version has been chosen.
 *
 * Whether the user pinned the version belongs to the request and not to the declaration, so it
 * lives in `PluginResolution` and never reaches the model.
 */
internal data class PluginDecl(
    val plugin: GradlePlugin,
    /** `null` = do not print a version. */
    val version: String?,
    /** `false` = an inherited plugin the root declares for its subprojects. */
    val apply: Boolean = true,
)

/**
 * The whole Gradle build, as the interpret stage decided it and before a single line of DSL exists.
 *
 * Nothing here knows Gradle syntax: rendering turns this into text and makes no decision of its own.
 */
internal data class GradleProject(
    val root: Path,
    val name: String,
    /** `libs.versions.toml`, wherever the project keeps it, or `null` when it has none. */
    val catalog: Path?,
    val modules: List<GradleModule>,
)

internal data class GradleModule(
    val gradlePath: String,
    val directory: Path,
    val plugins: List<PluginDecl>,
    val repositories: List<Repository>,
    /** `null` = the root of a project that has no module of its own; it renders `plugins { base }`. */
    val build: ModuleBuild?,
)

/** Where a module keeps its sources: Toolchain's own `src`/`test` or Maven's `src/main/kotlin`. */
internal enum class Layout { AMPER, MAVEN_LIKE }

internal enum class Scope { ALL, COMPILE_ONLY, RUNTIME_ONLY }

/** [library] is the `kotlin("...")` artifact the framework is pulled in by. */
internal enum class TestFramework(val library: String) {
    JUNIT_5("test-junit5"),
    JUNIT_4("test-junit"),
    NONE("test"),
}

/**
 * A `compilerOptions { }` body.
 *
 * A `null` flag means "say nothing", not "false": a Gradle target inherits the module-wide options
 * and only overrides what it restates.
 */
internal data class CompilerOptions(
    val languageVersion: String? = null,
    val apiVersion: String? = null,
    val jvmTarget: String? = null,
    val allWarningsAsErrors: Boolean? = null,
    val progressiveMode: Boolean? = null,
    val freeArgs: List<String> = emptyList(),
    val optIns: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = languageVersion == null && apiVersion == null && jvmTarget == null &&
            allWarningsAsErrors == null && progressiveMode == null && freeArgs.isEmpty() && optIns.isEmpty()

    companion object {
        val EMPTY = CompilerOptions()
    }
}

internal data class Dependency(
    val target: DependencyTarget,
    val scope: Scope = Scope.ALL,
    val exported: Boolean = false,
    /** Declared as a platform, so the coordinate contributes constraints instead of an artifact. */
    val bom: Boolean = false,
)

/** What a dependency points at, already resolved: the renderer never parses a notation again. */
internal sealed interface DependencyTarget {
    data class Maven(val coordinates: String) : DependencyTarget

    data class Project(val gradlePath: String) : DependencyTarget

    /** A version-catalog accessor such as `libs.serialization.json`, written without the `$`. */
    data class Catalog(val accessor: String) : DependencyTarget

    /** Applied as `kotlin("<name>")`: `reflect`, `test`, `test-junit5`. */
    data class KotlinBuiltin(val name: String) : DependencyTarget
}

internal data class JvmTestSettings(
    val freeJvmArgs: List<String> = emptyList(),
    val systemProperties: Map<String, String> = emptyMap(),
    val environment: Map<String, String> = emptyMap(),
)

/** A repository Gradle spells with a shorthand instead of a `maven { }` block. */
internal enum class RepositoryShorthand { MAVEN_LOCAL, MAVEN_CENTRAL, GOOGLE }

internal data class Repository(
    val id: String,
    val url: String,
    val credentials: RepositoryCredentials? = null,
    /** `null` = spell the repository out as a `maven { }` block. */
    val shorthand: RepositoryShorthand? = null,
)

internal data class RepositoryCredentials(
    /** Path to the properties file holding the credentials, relative to the consuming module. */
    val file: String,
    val usernameKey: String,
    val passwordKey: String,
)

/** What one module builds. One subtype per product family, so every renderer branch is exhaustive. */
internal sealed interface ModuleBuild

internal data class JvmBuild(
    /** The toolchain JDK, as written: `jvmToolchain(25)` takes a number and not a string. */
    val jdk: String,
    val release: String,
    val compilerOptions: CompilerOptions,
    val layout: Layout,
    val dependencies: List<Dependency>,
    val testDependencies: List<Dependency>,
    val testFramework: TestFramework,
    val testSettings: JvmTestSettings,
    /** `null` = no `application { }` block: either a library, or an app with no main class found. */
    val mainClass: String?,
) : ModuleBuild

/**
 * An `android/app` module: an Android application built by the Android Gradle Plugin.
 *
 * The source directories are fixed by the Toolchain layout and are spelled out by the renderer, so
 * unlike [JvmBuild] there is nothing to decide about them here.
 */
internal data class AndroidBuild(
    val namespace: String,
    val applicationId: String,
    /** SDK levels are emitted unquoted, so they are integers as written and never quoted strings. */
    val compileSdk: String,
    val minSdk: String,
    val targetSdk: String,
    val versionCode: String,
    val versionName: String,
    val release: String,
    val compilerOptions: CompilerOptions,
    val dependencies: List<Dependency>,
    val testDependencies: List<Dependency>,
    val testFramework: TestFramework,
) : ModuleBuild

/**
 * The `androidLibrary { }` target of a multiplatform module.
 *
 * It is not a [ModuleBuild]: an Android target is one platform of a multiplatform build and reaches
 * the renderer as part of it.
 */
internal data class AndroidLibraryTarget(
    val namespace: String,
    val compileSdk: String,
    val minSdk: String,
)

/**
 * A Kotlin Multiplatform module: a set of targets and the source-set hierarchy that feeds them.
 *
 * [compilerOptions] and [qualifiedCompilerOptions] stay apart because they are emitted one after
 * the other rather than merged: a `settings@common` section overrides the module-wide options by
 * restating them, and Gradle applies the later statement.
 */
internal data class MultiplatformBuild(
    val targets: List<KmpTarget>,
    /** The `jvmToolchain(...)` level, or `null` when the module declares no JVM platform. */
    val jvmToolchain: String?,
    val compilerOptions: CompilerOptions,
    val qualifiedCompilerOptions: CompilerOptions,
    /** Parents precede children, which is what lets the renderer emit them in one pass. */
    val sourceSets: List<KmpSourceSet>,
) : ModuleBuild

internal data class KmpTarget(
    /** The Kotlin platform name: `jvm`, `linuxX64`, `js`, `wasmJs`, `android`. */
    val name: String,
    val kind: TargetKind,
    /** The product builds a program rather than a library, so the target needs a binary. */
    val executable: Boolean,
    /** `settings.native.entryPoint`, which only a native binary has. */
    val entryPoint: String?,
    /** What the platform-qualified sections contribute to this target, and nothing else. */
    val compilerOptions: CompilerOptions,
)

/**
 * What kind of target a platform is, with the data only that kind needs.
 *
 * Every Kotlin target family is configured by a DSL of its own, so the renderer branches on this
 * rather than on the platform name, and a new family cannot be forgotten.
 */
internal sealed interface TargetKind {
    /** [release] is both the bytecode target and the `-Xjdk-release` the compiler is given. */
    data class Jvm(val release: String) : TargetKind

    data class Android(val library: AndroidLibraryTarget) : TargetKind

    data object Js : TargetKind

    data object WasmJs : TargetKind

    data object WasmWasi : TargetKind

    data object Native : TargetKind
}

internal data class KmpSourceSet(
    /** The Gradle source-set name: `commonMain`, `linuxX64Test`, `androidHostTest`. */
    val name: String,
    val parents: List<String>,
    val test: Boolean,
    /**
     * `true` = the Kotlin plugin creates the source set itself, so it is configured by name and
     * always carries a `dependencies { }` block, empty or not.
     */
    val builtIn: Boolean,
    val sourceDirs: List<String>,
    val resourceDirs: List<String>,
    val dependencies: List<Dependency>,
)
