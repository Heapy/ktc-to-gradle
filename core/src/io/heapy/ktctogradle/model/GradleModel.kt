package io.heapy.ktctogradle.model

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.Path

internal data class GeneratedFile(val path: Path, val content: FileContent)

/**
 * What one generated file holds.
 *
 * Text and bytes are told apart here rather than guessed at the write stage, because the ownership
 * marker `write/FileWriter` keys on can only be written into text. `gradle-wrapper.jar` is the one
 * file the converter lays down that cannot carry it, so it names the file whose ownership it
 * shares instead — a decision, and therefore not the write stage's to make.
 */
internal sealed interface FileContent {
    val bytes: ByteString

    data class Text(val value: String) : FileContent {
        override val bytes: ByteString get() = value.encodeUtf8()
    }

    data class Binary(override val bytes: ByteString, val ownershipFollows: Path) : FileContent
}

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
        MAVEN_PUBLISH("maven-publish"),
        SIGNING("signing"),
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
    /**
     * Where Gradle resolves the build's *plugins* from, as opposed to its dependencies.
     *
     * `pluginManagement` is settled once for the whole build while a repository is declared per
     * module, so this is a decision of its own and not a view over [modules]. It excludes the plugin
     * portal, which no module can configure and which the settings file always declares.
     */
    val pluginRepositories: List<Repository>,
)

internal data class GradleModule(
    val gradlePath: String,
    val directory: Path,
    val plugins: List<PluginDecl>,
    val repositories: List<Repository>,
    /**
     * The script has to read a properties file, so it needs an import of its own.
     *
     * Counted over the repositories the module declares and not over [repositories]: a repository
     * that is only published to still has its credentials read, and never reaches resolution.
     */
    val requiresCredentialsImport: Boolean,
    /**
     * Third-party Kotlin compiler plugins the module loads.
     *
     * Module-level rather than part of [build]: the Toolchain declares them once for the module, and
     * Gradle spells them the same way whatever the product is.
     */
    val compilerPlugins: List<CompilerPlugin> = emptyList(),
    /**
     * What the module publishes, or `null` when it publishes nothing.
     *
     * Module-level for the same reason as [compilerPlugins]: `settings.publishing` is declared once
     * for the module, and `maven-publish` spells the coordinate and the POM the same way whatever
     * the product underneath is.
     */
    val publication: Publication? = null,
    /** `null` = the root of a project that has no module of its own; it renders `plugins { base }`. */
    val build: ModuleBuild?,
)

/**
 * What `maven-publish` is told to publish.
 *
 * [artifactId] is `null` on a multiplatform module: the Kotlin Gradle Plugin creates one publication
 * per target and names each after the target, so a single name has nowhere to go. The interpret
 * stage reports that rather than inventing a renaming scheme.
 */
internal data class Publication(
    val group: String?,
    val version: String?,
    /**
     * The base artifact id.
     *
     * A JVM module publishes it as written. A multiplatform module publishes one artifact per
     * target, and the Kotlin Gradle Plugin names each of them after the Gradle project, so the base
     * replaces that prefix and the plugin's platform suffix survives — which is the same shape the
     * Toolchain publishes.
     */
    val artifactId: String?,
    /**
     * Whether a sources jar is published. The Toolchain's default is `false` for both products.
     *
     * A JVM module builds none unless one is asked for, so `false` there is silence. The Kotlin
     * Gradle Plugin builds one per target unless it is told not to, so `false` on a multiplatform
     * module is a line the build has to carry.
     */
    val publishSources: Boolean,
    val signArtifacts: Boolean,
    val pom: Pom?,
    /**
     * The Kotlin Gradle Plugin already created the publications, so the build configures them all.
     *
     * [projectName] is the prefix it named them with, which is what makes [artifactId] applicable.
     */
    val perTarget: Boolean,
    val projectName: String,
    /** The repositories the module publishes to, which are not the ones it resolves from. */
    val repositories: List<Repository> = emptyList(),
)

internal data class Pom(
    val name: String? = null,
    val description: String? = null,
    val url: String? = null,
    val licenses: List<PomLicense> = emptyList(),
    val developers: List<PomDeveloper> = emptyList(),
    val scm: PomScm? = null,
) {
    val isEmpty: Boolean
        get() = name == null && description == null && url == null &&
            licenses.isEmpty() && developers.isEmpty() && scm == null
}

internal data class PomLicense(val name: String?, val url: String?)

internal data class PomDeveloper(
    val id: String?,
    val name: String?,
    val url: String? = null,
    val email: String? = null,
    val organization: String? = null,
    val organizationUrl: String? = null,
)

internal data class PomScm(val url: String?, val connection: String?, val developerConnection: String?)

/**
 * A third-party Kotlin compiler plugin, loaded from [coordinates] and configured through [id].
 *
 * Gradle has no DSL for this: a plugin reaches the compiler as an artifact on a plugin classpath
 * configuration plus one `-P plugin:<id>:<key>=<value>` compiler argument per option. That spelling
 * is the renderer's business; what a plugin *is* is this.
 */
internal data class CompilerPlugin(
    val id: String,
    /** Resolved like any other dependency, so a `${'$'}libs.` alias reaches the catalog accessor. */
    val dependency: DependencyTarget,
    val options: Map<String, String> = emptyMap(),
)

/** Where a module keeps its sources: Toolchain's own `src`/`test` or Maven's `src/main/kotlin`. */
internal enum class Layout { AMPER, MAVEN_LIKE }

internal enum class Scope { ALL, COMPILE_ONLY, RUNTIME_ONLY }

/**
 * [library] is the `kotlin("...")` artifact the framework is pulled in by.
 *
 * [runsOnTheJUnitPlatform] says whether the module's `Test` tasks need `useJUnitPlatform()`. It is
 * true for [NONE] as well as for [JUNIT_5]: the Kotlin Toolchain reads `junit: none` as "add no
 * JUnit adapter", not as "do not run the JUnit platform", and still discovers the tests through its
 * own platform launcher. Only [JUNIT_4] keeps Gradle's own JUnit 4 runner.
 */
internal enum class TestFramework(val library: String) {
    JUNIT_5("test-junit5"),
    JUNIT_4("test-junit"),
    NONE("test"),
    ;

    val runsOnTheJUnitPlatform: Boolean
        get() = this != JUNIT_4
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

    /** Applied as `kotlin("<name>")`: `reflect`, `test`, `test-junit`, `test-junit5`. */
    data class KotlinBuiltin(val name: String) : DependencyTarget
}

internal data class JvmTestSettings(
    val freeJvmArgs: List<String> = emptyList(),
    val systemProperties: Map<String, String> = emptyMap(),
    val environment: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = freeJvmArgs.isEmpty() && systemProperties.isEmpty() && environment.isEmpty()

    companion object {
        val EMPTY = JvmTestSettings()
    }
}

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
    /**
     * What the test compilation targets, or `null` when the module named nothing.
     *
     * Separate from [release] because a module may compile its tests against a newer JDK API than
     * the bytecode it publishes; the test classes are never published, so nothing constrains them
     * to the same level.
     */
    val testRelease: String? = null,
    val compilerOptions: CompilerOptions,
    /** What the platform-qualified sections add, emitted after [compilerOptions] rather than merged. */
    val qualifiedCompilerOptions: CompilerOptions = CompilerOptions.EMPTY,
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
    /** What the platform-qualified sections add, emitted after [compilerOptions] rather than merged. */
    val qualifiedCompilerOptions: CompilerOptions = CompilerOptions.EMPTY,
    val dependencies: List<Dependency>,
    val testDependencies: List<Dependency>,
    val testFramework: TestFramework,
    val testSettings: JvmTestSettings,
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
    /**
     * The bytecode level the target compiles to, resolved exactly like the `jvm()` target's.
     *
     * Without it the Android Gradle Plugin picks its own, and the two JVM targets of one module can
     * disagree on the class-file version the module publishes.
     */
    val release: String,
    /** What the target's `hostTest` compilation targets, or `null` when the module named nothing. */
    val testRelease: String? = null,
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
    /**
     * What the JVM-backed test tasks run on.
     *
     * `commonTest` keeps the plain `kotlin("test")` whatever this says — that artifact resolves per
     * platform, and only the JVM-flavoured targets have a framework to choose.
     */
    val testFramework: TestFramework,
    /**
     * What the JVM-backed test tasks are given: arguments, system properties and environment.
     *
     * Empty when the module declares no JVM-backed target, because there is then no `Test` task to
     * carry them and the interpret stage reports the drop instead.
     */
    val testSettings: JvmTestSettings,
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
    /**
     * True when the target's tests run on a JDK, and therefore through a Gradle `Test` task.
     *
     * Abstract rather than a `when` over the subtypes, so a target family added later has to answer
     * the question instead of silently inheriting `false`.
     */
    val runsOnAJdk: Boolean

    /**
     * [release] is both the bytecode target and the `-Xjdk-release` the compiler is given.
     *
     * [testRelease] is the same pair for the target's test compilation, or `null` when the module
     * named none. The two are separate because a module may compile its tests against a newer JDK
     * API than the bytecode it publishes — which is the whole point of `test-settings.jvm.release`.
     */
    data class Jvm(val release: String, val testRelease: String? = null) : TargetKind {
        override val runsOnAJdk = true
    }

    data class Android(val library: AndroidLibraryTarget) : TargetKind {
        override val runsOnAJdk = true
    }

    data object Js : TargetKind {
        override val runsOnAJdk = false
    }

    data object WasmJs : TargetKind {
        override val runsOnAJdk = false
    }

    data object WasmWasi : TargetKind {
        override val runsOnAJdk = false
    }

    data object Native : TargetKind {
        override val runsOnAJdk = false
    }
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
