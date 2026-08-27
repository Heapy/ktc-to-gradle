package io.heapy.ktctogradle.render

import io.heapy.ktctogradle.model.AndroidBuild
import io.heapy.ktctogradle.model.AndroidLibraryTarget
import io.heapy.ktctogradle.model.CompilerOptions
import io.heapy.ktctogradle.model.Dependency
import io.heapy.ktctogradle.model.DependencyTarget
import io.heapy.ktctogradle.model.GradlePlugin
import io.heapy.ktctogradle.model.JvmBuild
import io.heapy.ktctogradle.model.JvmTestSettings
import io.heapy.ktctogradle.model.KmpSourceSet
import io.heapy.ktctogradle.model.KmpTarget
import io.heapy.ktctogradle.model.Layout
import io.heapy.ktctogradle.model.MultiplatformBuild
import io.heapy.ktctogradle.model.PluginDecl
import io.heapy.ktctogradle.model.Repository
import io.heapy.ktctogradle.model.RepositoryShorthand
import io.heapy.ktctogradle.model.Scope
import io.heapy.ktctogradle.model.TargetKind
import io.heapy.ktctogradle.model.TestFramework

/**
 * Spells a module out as `build.gradle.kts`.
 *
 * The renderer decides nothing: every default and every choice already reached it as model data.
 * [qualifiedCompilerOptions] stays apart from the module-wide ones because it is emitted after them
 * rather than merged into them, which is how a qualified section overrides what it restates.
 */
internal fun renderJvmModule(
    plugins: List<PluginDecl>,
    repositories: List<Repository>,
    credentialsImport: Boolean,
    build: JvmBuild,
    qualifiedCompilerOptions: CompilerOptions,
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

/**
 * Spells an `android/app` module out as `build.gradle.kts`.
 *
 * The Android Gradle Plugin owns the source layout of the module, so the source-set directories are
 * fixed text rather than model data.
 */
internal fun renderAndroidModule(
    plugins: List<PluginDecl>,
    repositories: List<Repository>,
    credentialsImport: Boolean,
    build: AndroidBuild,
    qualifiedCompilerOptions: CompilerOptions,
): String = KtsWriter().apply {
    line(StaticAssets.header())
    appendCredentialsImport(credentialsImport)
    appendPluginBlock(plugins)
    blank()
    appendRepositories(repositories)
    blank()
    block("android") {
        line("namespace = ${quote(build.namespace)}")
        line("compileSdk = ${build.compileSdk}")
        block("defaultConfig") {
            line("applicationId = ${quote(build.applicationId)}")
            line("minSdk = ${build.minSdk}")
            line("targetSdk = ${build.targetSdk}")
            line("versionCode = ${build.versionCode}")
            line("versionName = ${quote(build.versionName)}")
        }
        block("compileOptions") {
            line("sourceCompatibility = JavaVersion.toVersion(${quote(build.release)})")
            line("targetCompatibility = JavaVersion.toVersion(${quote(build.release)})")
        }
        block("sourceSets.named(\"main\")") {
            line("kotlin.srcDirs(\"src\", \"src@android\")")
            line("resources.srcDirs(\"resources\", \"resources@android\")")
            line("manifest.srcFile(\"src/AndroidManifest.xml\")")
        }
        block("sourceSets.named(\"test\")") {
            line("kotlin.srcDirs(\"test\", \"test@android\")")
            line("resources.srcDirs(\"testResources\", \"testResources@android\")")
        }
    }
    blank()
    block("kotlin") {
        appendCompilerOptions(build.compilerOptions, qualifiedCompilerOptions)
    }
    blank()
    block("dependencies") {
        appendDependencies(build.dependencies, test = false)
        line("testImplementation(kotlin(${quote(build.testFramework.library)}))")
        appendDependencies(build.testDependencies, test = true)
    }
}.build()

/**
 * Spells a multiplatform module out as `build.gradle.kts`.
 *
 * Source sets are emitted in the order the interpret stage put them in, which has every parent
 * before its children: `dependsOn(getByName(...))` resolves a name that must already exist.
 */
internal fun renderMultiplatformModule(
    plugins: List<PluginDecl>,
    repositories: List<Repository>,
    credentialsImport: Boolean,
    build: MultiplatformBuild,
): String = KtsWriter().apply {
    line(StaticAssets.header())
    appendCredentialsImport(credentialsImport)
    appendPluginBlock(plugins)
    blank()
    appendRepositories(repositories)
    blank()
    block("kotlin") {
        for (target in build.targets) appendTarget(target)
        build.jvmToolchain?.let { line("jvmToolchain($it)") }
        appendCompilerOptions(build.compilerOptions, build.qualifiedCompilerOptions)
        block("sourceSets") {
            for (sourceSet in build.sourceSets) appendSourceSet(sourceSet)
        }
    }
}.build()

private fun KtsWriter.appendTarget(target: KmpTarget) {
    when (val kind = target.kind) {
        is TargetKind.Jvm -> block("jvm") {
            block("compilerOptions") {
                line("jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(${quote(kind.release)}))")
                line("freeCompilerArgs.add(${quote("-Xjdk-release=${kind.release}")})")
                appendCompilerOptionLines(target.compilerOptions)
            }
        }
        is TargetKind.Android -> appendAndroidLibraryTarget(kind.library, target.compilerOptions)
        TargetKind.Js -> appendBrowserTarget("js(IR)", target)
        TargetKind.WasmJs -> appendBrowserTarget("wasmJs", target)
        TargetKind.WasmWasi -> appendWasmWasiTarget(target)
        TargetKind.Native -> block(target.name) {
            if (target.executable) {
                block("binaries.executable") {
                    target.entryPoint?.let { line("entryPoint = ${quote(it)}") }
                }
            }
            appendCompilerOptions(target.compilerOptions)
        }
    }
}

/** A target that runs in a browser fits on one line unless it carries compiler options of its own. */
private fun KtsWriter.appendBrowserTarget(dsl: String, target: KmpTarget) {
    if (target.compilerOptions.isEmpty) {
        line("$dsl { ${if (target.executable) "binaries.executable(); " else ""}browser() }")
        return
    }
    block(dsl) {
        if (target.executable) line("binaries.executable()")
        line("browser()")
        appendCompilerOptions(target.compilerOptions)
    }
}

private fun KtsWriter.appendWasmWasiTarget(target: KmpTarget) {
    if (target.compilerOptions.isEmpty) {
        line("wasmWasi { ${if (target.executable) "binaries.executable()" else ""} }")
        return
    }
    block("wasmWasi") {
        if (target.executable) line("binaries.executable()")
        // The wasmWasi target DSL carries no compilerOptions of its own, so the options
        // have to reach the compile tasks through its compilations.
        block("compilations.configureEach") {
            block("compileTaskProvider.configure") {
                appendCompilerOptions(target.compilerOptions)
            }
        }
    }
}

private fun KtsWriter.appendSourceSet(sourceSet: KmpSourceSet) {
    val header = if (sourceSet.builtIn) sourceSet.name else "maybeCreate(${quote(sourceSet.name)}).apply"
    block(header) {
        for (parent in sourceSet.parents) line("dependsOn(getByName(${quote(parent)}))")
        for (directory in sourceSet.sourceDirs) line("kotlin.srcDir(${quote(directory)})")
        for (directory in sourceSet.resourceDirs) line("resources.srcDir(${quote(directory)})")
        if (sourceSet.builtIn || sourceSet.dependencies.isNotEmpty()) {
            block("dependencies") {
                appendDependencies(sourceSet.dependencies, sourceSet.test, sourceSet = true)
            }
        }
    }
}

/**
 * The `androidLibrary { }` target of a multiplatform module.
 *
 * `withHostTestBuilder {}` is what registers the unit-test compilation; without it the
 * `androidHostTest` source set the fragments create has nothing to compile into.
 */
internal fun KtsWriter.appendAndroidLibraryTarget(target: AndroidLibraryTarget, qualifiedOptions: CompilerOptions) {
    block("androidLibrary") {
        line("namespace = ${quote(target.namespace)}")
        line("compileSdk = ${target.compileSdk}")
        line("minSdk = ${target.minSdk}")
        line("withHostTestBuilder {}.configure {}")
        appendCompilerOptions(qualifiedOptions)
    }
}

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

/**
 * A `compilerOptions { }` block, or nothing at all when there is nothing to say.
 *
 * [extra] is emitted after [options] rather than merged into it: Gradle applies the later
 * statement, so that is how a platform-qualified section overrides what it restates.
 */
internal fun KtsWriter.appendCompilerOptions(options: CompilerOptions, extra: CompilerOptions = CompilerOptions.EMPTY) {
    if (options.isEmpty && extra.isEmpty) return
    block("compilerOptions") {
        appendCompilerOptionLines(options)
        appendCompilerOptionLines(extra)
    }
}

/** The body of a `compilerOptions { }` block, for the targets that open the block themselves. */
private fun KtsWriter.appendCompilerOptionLines(options: CompilerOptions) {
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
