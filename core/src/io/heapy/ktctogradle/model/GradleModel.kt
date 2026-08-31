package io.heapy.ktctogradle.model

import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.Path

internal data class GeneratedFile(val path: Path, val content: FileContent)

/** Distinguishes marker-bearing text from binary content whose ownership follows a companion. */
internal sealed interface FileContent {
    val bytes: ByteString

    data class Text(val value: String) : FileContent {
        override val bytes: ByteString get() = value.encodeUtf8()
    }

    /**
     * [ownershipFollows] must be a bare sibling name. The type does not prevent absolute paths or
     * `..`, which okio would resolve outside the binary's directory.
     */
    data class Binary(override val bytes: ByteString, val ownershipFollows: String) : FileContent
}

internal enum class PluginFamily(val displayName: String) {
    KOTLIN("kotlin"),
    ANDROID("android"),
}

/** Plugin identity without Gradle DSL spelling, which remains renderer-owned. */
internal sealed interface GradlePlugin {
    val id: String

    val family: PluginFamily?

    enum class Kotlin(val shortName: String) : GradlePlugin {
        JVM("jvm"),
        MULTIPLATFORM("multiplatform"),
        SERIALIZATION("plugin.serialization"),
        ;

        override val id: String get() = "org.jetbrains.kotlin.$shortName"
        override val family: PluginFamily get() = PluginFamily.KOTLIN
    }

    enum class Android(override val id: String) : GradlePlugin {
        APPLICATION("com.android.application"),
        KMP_LIBRARY("com.android.kotlin.multiplatform.library"),
        ;

        override val family: PluginFamily get() = PluginFamily.ANDROID
    }

    enum class Builtin(override val id: String) : GradlePlugin {
        APPLICATION("application"),
        BASE("base"),
        MAVEN_PUBLISH("maven-publish"),
        SIGNING("signing"),
        ;

        override val family: PluginFamily? get() = null
    }

    data class Other(
        override val id: String,
        override val family: PluginFamily?,
    ) : GradlePlugin
}

internal data class PluginDecl(
    val plugin: GradlePlugin,
    val version: String?,
    val apply: Boolean = true,
)

internal data class GradleProject(
    val root: Path,
    val name: String,
    val catalog: Path?,
    val modules: List<GradleModule>,
    /** Project-wide plugin repositories, excluding Gradle's always-emitted plugin portal. */
    val pluginRepositories: List<Repository>,
)

internal data class GradleModule(
    val gradlePath: String,
    val directory: Path,
    val plugins: List<PluginDecl>,
    val repositories: List<Repository>,
    val requiresCredentialsImport: Boolean,
    val compilerPlugins: List<CompilerPlugin> = emptyList(),
    val publication: Publication? = null,
    val build: ModuleBuild?,
)

internal data class Publication(
    val group: String?,
    val version: String?,
    /** Base artifact id; KMP rendering replaces only KGP's project-name prefix. */
    val artifactId: String?,
    /** Toolchain defaults false; KGP must be explicitly told to disable per-target source jars. */
    val publishSources: Boolean,
    val signArtifacts: Boolean,
    val pom: Pom?,
    val perTarget: Boolean,
    val projectName: String,
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

/** Third-party compiler plugin identity; classpath and `-P` syntax remain renderer-owned. */
internal data class CompilerPlugin(
    val id: String,
    val dependency: DependencyTarget,
    val options: Map<String, String> = emptyMap(),
)

internal enum class Layout { AMPER, MAVEN_LIKE }

internal enum class Scope { ALL, COMPILE_ONLY, RUNTIME_ONLY }

/** `NONE` still runs the JUnit platform; it means no Kotlin test adapter, not the JUnit 4 runner. */
internal enum class TestFramework(val library: String) {
    JUNIT_5("test-junit5"),
    JUNIT_4("test-junit"),
    NONE("test"),
    ;

    val runsOnTheJUnitPlatform: Boolean
        get() = this != JUNIT_4
}

/** Null options are omitted so target blocks override only values they restate. */
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
    val bom: Boolean = false,
)

internal sealed interface DependencyTarget {
    data class Maven(val coordinates: String) : DependencyTarget

    data class Project(val gradlePath: String) : DependencyTarget

    /** Kotlin-ready accessor text; keyword segments are already backticked. */
    data class Catalog(val accessor: String) : DependencyTarget

    data class KotlinBuiltin(val name: String) : DependencyTarget
}

internal data class JvmTestSettings(
    val freeJvmArgs: List<String> = emptyList(),
    val systemProperties: Map<String, String> = emptyMap(),
    val environment: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = freeJvmArgs.isEmpty() && systemProperties.isEmpty() && environment.isEmpty()

    /** Appends narrower arguments while narrower named values replace broader ones. */
    operator fun plus(other: JvmTestSettings): JvmTestSettings = JvmTestSettings(
        freeJvmArgs = freeJvmArgs + other.freeJvmArgs,
        systemProperties = systemProperties + other.systemProperties,
        environment = environment + other.environment,
    )

    companion object {
        val EMPTY = JvmTestSettings()
    }
}

internal enum class RepositoryShorthand { MAVEN_LOCAL, MAVEN_CENTRAL, GOOGLE }

internal data class Repository(
    val id: String,
    val url: String,
    val credentials: RepositoryCredentials? = null,
    val shorthand: RepositoryShorthand? = null,
)

internal data class RepositoryCredentials(
    /** Relative to the script that consumes it. */
    val file: String,
    val usernameKey: String,
    val passwordKey: String,
)

internal sealed interface ModuleBuild

internal data class JvmBuild(
    val jdk: String,
    val release: String,
    /** Independent because unpublished tests may target newer JDK APIs than main code. */
    val testRelease: String? = null,
    val compilerOptions: CompilerOptions,
    /** Emitted after [compilerOptions] so qualified values override inherited ones. */
    val qualifiedCompilerOptions: CompilerOptions = CompilerOptions.EMPTY,
    val layout: Layout,
    val dependencies: List<Dependency>,
    val testDependencies: List<Dependency>,
    val testFramework: TestFramework,
    val testSettings: JvmTestSettings,
    val mainClass: String?,
) : ModuleBuild

internal data class AndroidBuild(
    val namespace: String,
    val applicationId: String,
    val compileSdk: String,
    val minSdk: String,
    val targetSdk: String,
    val versionCode: String,
    val versionName: String,
    val release: String,
    val compilerOptions: CompilerOptions,
    val qualifiedCompilerOptions: CompilerOptions = CompilerOptions.EMPTY,
    val dependencies: List<Dependency>,
    val testDependencies: List<Dependency>,
    val testFramework: TestFramework,
    val testSettings: JvmTestSettings,
) : ModuleBuild

internal data class AndroidLibraryTarget(
    val namespace: String,
    val compileSdk: String,
    val minSdk: String,
    /** Explicit so Android and JVM targets publish the same class-file level. */
    val release: String,
    val testRelease: String? = null,
    val testSettings: JvmTestSettings = JvmTestSettings.EMPTY,
)

/** Qualified compiler options stay separate because later Gradle statements perform the override. */
internal data class MultiplatformBuild(
    val targets: List<KmpTarget>,
    val jvmToolchain: String?,
    val compilerOptions: CompilerOptions,
    val qualifiedCompilerOptions: CompilerOptions,
    /** Parents precede children, which is what lets the renderer emit them in one pass. */
    val sourceSets: List<KmpSourceSet>,
    val testFramework: TestFramework,
    val testSettings: JvmTestSettings,
) : ModuleBuild

internal data class KmpTarget(
    val name: String,
    val kind: TargetKind,
    val executable: Boolean,
    val entryPoint: String?,
    val compilerOptions: CompilerOptions,
)

/** Closed target families keep renderer branching exhaustive. */
internal sealed interface TargetKind {
    /** Abstract so every new target family must state whether it owns a Gradle `Test` task. */
    val runsOnAJdk: Boolean

    /** Main and test releases remain separate because tests may use newer JDK APIs. */
    data class Jvm(
        val release: String,
        val testRelease: String? = null,
        val testSettings: JvmTestSettings = JvmTestSettings.EMPTY,
    ) : TargetKind {
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
    val name: String,
    val parents: List<String>,
    val test: Boolean,
    /** Built-in source sets are configured by name instead of created. */
    val builtIn: Boolean,
    val sourceDirs: List<String>,
    val resourceDirs: List<String>,
    val dependencies: List<Dependency>,
)
