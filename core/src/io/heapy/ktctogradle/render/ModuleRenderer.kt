package io.heapy.ktctogradle.render

import io.heapy.ktctogradle.model.CompilerOptions
import io.heapy.ktctogradle.model.Dependency
import io.heapy.ktctogradle.model.DependencyTarget
import io.heapy.ktctogradle.model.GradlePlugin
import io.heapy.ktctogradle.model.JvmBuild
import io.heapy.ktctogradle.model.JvmTestSettings
import io.heapy.ktctogradle.model.Layout
import io.heapy.ktctogradle.model.PluginDecl
import io.heapy.ktctogradle.model.Repository
import io.heapy.ktctogradle.model.RepositoryShorthand
import io.heapy.ktctogradle.model.Scope
import io.heapy.ktctogradle.model.TestFramework

/**
 * Spells a module out as `build.gradle.kts`.
 *
 * The renderer decides nothing: every default and every choice already reached it as model data.
 * [qualifiedCompilerOptions] is the one exception and is transitional — platform-qualified settings
 * are still interpreted by the caller and arrive as ready-made lines.
 */
internal fun renderJvmModule(
    plugins: List<PluginDecl>,
    repositories: List<Repository>,
    credentialsImport: Boolean,
    build: JvmBuild,
    qualifiedCompilerOptions: List<String>,
): String = KtsWriter().apply {
    line(StaticAssets.header())
    appendCredentialsImport(credentialsImport)
    appendPluginBlock(plugins)
    blank()
    appendRepositories(repositories)
    blank()
    block("kotlin") {
        line("jvmToolchain(${build.jdk})")
        appendCompilerOptions(build.compilerOptions, qualifiedCompilerOptions)
    }
    blank()
    block("java") {
        line("sourceCompatibility = JavaVersion.toVersion(${quote(build.release)})")
        line("targetCompatibility = JavaVersion.toVersion(${quote(build.release)})")
    }
    if (build.layout != Layout.MAVEN_LIKE) {
        blank()
        block("sourceSets") {
            block("main") {
                line("kotlin.srcDir(\"src\")")
                line("resources.srcDir(\"resources\")")
            }
            block("test") {
                line("kotlin.srcDir(\"test\")")
                line("resources.srcDir(\"testResources\")")
            }
        }
    }
    blank()
    block("dependencies") {
        appendDependencies(build.dependencies, test = false)
        line("testImplementation(kotlin(${quote(build.testFramework.library)}))")
        appendDependencies(build.testDependencies, test = true)
    }
    blank()
    block("tasks.test") {
        if (build.testFramework == TestFramework.JUNIT_5) line("useJUnitPlatform()")
        appendJvmTestSettings(build.testSettings)
    }
    build.mainClass?.let { mainClass ->
        blank()
        block("application") {
            line("mainClass.set(${quote(mainClass)})")
        }
    }
}.build()

internal fun KtsWriter.appendPluginBlock(plugins: List<PluginDecl>) {
    block("plugins") {
        for (plugin in plugins) line(plugin.declaration())
    }
}

internal fun PluginDecl.declaration(): String = buildString {
    append(plugin.dsl())
    if (version != null) append(" version ${quote(version)}")
    if (!apply) append(" apply false")
}

/** The only place that knows how a [GradlePlugin] is spelled in the Gradle Kotlin DSL. */
internal fun GradlePlugin.dsl(): String = when (this) {
    is GradlePlugin.Kotlin -> "kotlin(${quote(shortName)})"
    is GradlePlugin.Builtin -> id
    is GradlePlugin.Android,
    is GradlePlugin.Other,
    -> "id(${quote(id)})"
}

/** Credentials are read from a properties file, which is the only import a script ever needs. */
internal fun KtsWriter.appendCredentialsImport(required: Boolean) {
    if (!required) return
    blank()
    line("import java.util.Properties")
    blank()
}

internal fun KtsWriter.appendRepositories(repositories: List<Repository>) {
    block("repositories") {
        for ((index, repository) in repositories.withIndex()) {
            when (repository.shorthand) {
                RepositoryShorthand.MAVEN_LOCAL -> line("mavenLocal()")
                RepositoryShorthand.MAVEN_CENTRAL -> line("mavenCentral()")
                RepositoryShorthand.GOOGLE -> line("google()")
                null -> block("maven") {
                    line("name = ${quote(repository.id)}")
                    line("url = uri(${quote(repository.url)})")
                    repository.credentials?.let { credentials ->
                        val variable = "repositoryCredentials$index"
                        line("val $variable = Properties()")
                        line("file(${quote(credentials.file)}).inputStream().use($variable::load)")
                        block("credentials") {
                            line("username = $variable.getProperty(${quote(credentials.usernameKey)})")
                            line("password = $variable.getProperty(${quote(credentials.passwordKey)})")
                        }
                    }
                }
            }
        }
    }
}

/** [extraLines] are appended last so a qualified section overrides the module-wide options. */
internal fun KtsWriter.appendCompilerOptions(options: CompilerOptions, extraLines: List<String> = emptyList()) {
    if (options.isEmpty && extraLines.isEmpty()) return
    block("compilerOptions") {
        options.languageVersion?.let {
            line("languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.fromVersion(${quote(it)}))")
        }
        options.apiVersion?.let {
            line("apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.fromVersion(${quote(it)}))")
        }
        options.jvmTarget?.let {
            line("this.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(${quote(it)}))")
        }
        options.allWarningsAsErrors?.let { line("allWarningsAsErrors.set($it)") }
        options.progressiveMode?.let { line("progressiveMode.set($it)") }
        if (options.freeArgs.isNotEmpty()) {
            line("freeCompilerArgs.addAll(${options.freeArgs.joinToString(prefix = "listOf(", postfix = ")", transform = ::quote)})")
        }
        if (options.optIns.isNotEmpty()) {
            line("optIn.addAll(${options.optIns.joinToString(prefix = "listOf(", postfix = ")", transform = ::quote)})")
        }
        for (extra in extraLines) line(extra)
    }
}

internal fun KtsWriter.appendJvmTestSettings(settings: JvmTestSettings) {
    if (settings.freeJvmArgs.isNotEmpty()) line("jvmArgs(${settings.freeJvmArgs.joinToString(transform = ::quote)})")
    for ((key, value) in settings.systemProperties) line("systemProperty(${quote(key)}, ${quote(value)})")
    for ((key, value) in settings.environment) line("environment(${quote(key)}, ${quote(value)})")
}

/**
 * [sourceSet] switches to the Kotlin Multiplatform source-set DSL, which names its configurations
 * without the `test` prefix because the source set already says which compilation it belongs to.
 */
internal fun KtsWriter.appendDependencies(
    dependencies: List<Dependency>,
    test: Boolean,
    sourceSet: Boolean = false,
) {
    for (dependency in dependencies) {
        line("${dependency.configuration(test, sourceSet)}(${dependency.expression()})")
    }
}

private fun Dependency.configuration(test: Boolean, sourceSet: Boolean): String = when {
    sourceSet && scope == Scope.COMPILE_ONLY -> "compileOnly"
    sourceSet && scope == Scope.RUNTIME_ONLY -> "runtimeOnly"
    sourceSet && exported && !test -> "api"
    sourceSet -> "implementation"
    scope == Scope.COMPILE_ONLY -> if (test) "testCompileOnly" else "compileOnly"
    scope == Scope.RUNTIME_ONLY -> if (test) "testRuntimeOnly" else "runtimeOnly"
    exported && !test -> "api"
    test -> "testImplementation"
    else -> "implementation"
}

private fun Dependency.expression(): String {
    val expression = when (val target = target) {
        is DependencyTarget.Maven -> quote(target.coordinates)
        is DependencyTarget.Project -> "project(${quote(target.gradlePath)})"
        is DependencyTarget.Catalog -> target.accessor
        is DependencyTarget.KotlinBuiltin -> "kotlin(${quote(target.name)})"
    }
    return if (bom) "platform($expression)" else expression
}
