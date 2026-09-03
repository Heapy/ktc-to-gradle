package io.heapy.ktctogradle.render

import io.heapy.ktctogradle.model.AndroidBuild
import io.heapy.ktctogradle.model.AndroidLibraryTarget
import io.heapy.ktctogradle.model.CompilerOptions
import io.heapy.ktctogradle.model.CompilerPlugin
import io.heapy.ktctogradle.model.Dependency
import io.heapy.ktctogradle.model.DependencyTarget
import io.heapy.ktctogradle.model.GradleModule
import io.heapy.ktctogradle.model.GradlePlugin
import io.heapy.ktctogradle.model.JvmBuild
import io.heapy.ktctogradle.model.JvmTestSettings
import io.heapy.ktctogradle.model.KmpSourceSet
import io.heapy.ktctogradle.model.KmpTarget
import io.heapy.ktctogradle.model.Layout
import io.heapy.ktctogradle.model.MultiplatformBuild
import io.heapy.ktctogradle.model.PluginDecl
import io.heapy.ktctogradle.model.Scope
import io.heapy.ktctogradle.model.TargetKind

internal fun renderModule(module: GradleModule): String = when (val build = module.build) {
    null -> renderRootShell(module)
    is JvmBuild -> renderJvmModule(module, build)
    is AndroidBuild -> renderAndroidModule(module, build)
    is MultiplatformBuild -> renderMultiplatformModule(module, build)
}

/** `base` supplies lifecycle tasks for a root that builds no module itself. */
private fun renderRootShell(module: GradleModule): String = KtsWriter().apply {
    line(StaticAssets.header())
    appendPluginBlock(module.plugins)
}.build()

private fun renderJvmModule(module: GradleModule, build: JvmBuild): String = KtsWriter().apply {
    line(StaticAssets.header())
    appendCredentialsImport(module.requiresCredentialsImport)
    appendPluginBlock(module.plugins)
    module.publication?.let { appendPublicationCoordinates(it) }
    blank()
    appendRepositories(module.repositories)
    blank()
    block("kotlin") {
        line("jvmToolchain(${build.jdk})")
        appendCompilerOptions(
            build.compilerOptions,
            build.qualifiedCompilerOptions,
            module.compilerPlugins,
            // A distinct test release requires the main -Xjdk-release to move to compileKotlin.
            jdkRelease = build.release.takeIf { build.testRelease == null },
        )
    }
    blank()
    block("java") {
        line("sourceCompatibility = JavaVersion.toVersion(${quote(build.release)})")
        line("targetCompatibility = JavaVersion.toVersion(${quote(build.release)})")
        module.publication?.let { publication ->
            // Mirrors Toolchain's publication artifact switches.
            if (publication.publishSources) line("withSourcesJar()")
            // Toolchain adds the empty javadoc jar required by Maven Central.
            line("withJavadocJar()")
        }
    }
    appendMainRelease(build)
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
        appendCompilerPluginClasspath(module.compilerPlugins, native = false)
        appendDependencies(build.dependencies, test = false)
        line("testImplementation(kotlin(${quote(build.testFramework.library)}))")
        appendDependencies(build.testDependencies, test = true)
    }
    blank()
    block("tasks.test") {
        if (build.testFramework.runsOnTheJUnitPlatform) line("useJUnitPlatform()")
        appendJvmTestSettings(build.testSettings)
    }
    build.testRelease?.let { appendTestRelease(it) }
    build.mainClass?.let { mainClass ->
        blank()
        block("application") {
            line("mainClass.set(${quote(mainClass)})")
        }
        blank()
        // The Kotlin Toolchain writes a runnable jar; the `application` plugin alone does not.
        block("tasks.jar") {
            block("manifest") {
                line("attributes(mapOf(\"Main-Class\" to ${quote(mainClass)}))")
            }
        }
    }
    module.publication?.let {
        appendPublishing(it)
        appendSigning(it)
    }
}.build()

private fun renderAndroidModule(module: GradleModule, build: AndroidBuild): String = KtsWriter().apply {
    line(StaticAssets.header())
    appendCredentialsImport(module.requiresCredentialsImport)
    appendPluginBlock(module.plugins)
    blank()
    appendRepositories(module.repositories)
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
        // AGP defaults to JUnit 4; unitTests.all is also its handle for task settings.
        if (build.testFramework.runsOnTheJUnitPlatform || !build.testSettings.isEmpty) {
            block("testOptions") {
                block("unitTests.all") {
                    if (build.testFramework.runsOnTheJUnitPlatform) line("it.useJUnitPlatform()")
                    appendJvmTestSettings(build.testSettings, receiver = "it.")
                }
            }
        }
    }
    blank()
    block("kotlin") {
        appendCompilerOptions(build.compilerOptions, build.qualifiedCompilerOptions, module.compilerPlugins)
    }
    blank()
    block("dependencies") {
        appendCompilerPluginClasspath(module.compilerPlugins, native = false)
        appendDependencies(build.dependencies, test = false)
        line("testImplementation(kotlin(${quote(build.testFramework.library)}))")
        appendDependencies(build.testDependencies, test = true)
    }
}.build()

private fun renderMultiplatformModule(module: GradleModule, build: MultiplatformBuild): String = KtsWriter().apply {
    line(StaticAssets.header())
    appendCredentialsImport(module.requiresCredentialsImport)
    appendPluginBlock(module.plugins)
    module.publication?.let { appendPublicationCoordinates(it) }
    blank()
    appendRepositories(module.repositories)
    blank()
    block("kotlin") {
        for (target in build.targets) appendTarget(target)
        build.jvmToolchain?.let { line("jvmToolchain($it)") }
        // KGP defaults source jars on, opposite Toolchain's publishing default.
        module.publication?.takeIf { !it.publishSources }?.let { line("withSourcesJar(publish = false)") }
        appendCompilerOptions(build.compilerOptions, build.qualifiedCompilerOptions, module.compilerPlugins)
        block("sourceSets") {
            for (sourceSet in build.sourceSets) appendSourceSet(sourceSet)
        }
    }
    if (module.compilerPlugins.isNotEmpty()) {
        blank()
        block("dependencies") {
            appendCompilerPluginClasspath(
                module.compilerPlugins,
                native = build.targets.any { it.kind == TargetKind.Native },
            )
        }
    }
    // Configure both JVM and Android host tests through their shared Gradle Test type.
    val junitPlatform = build.testFramework.runsOnTheJUnitPlatform
    if ((junitPlatform || !build.testSettings.isEmpty) && build.targets.any { it.kind.runsOnAJdk }) {
        blank()
        block("tasks.withType<Test>().configureEach") {
            if (junitPlatform) line("useJUnitPlatform()")
            appendJvmTestSettings(build.testSettings)
        }
    }
    // Register target-specific actions last so they override module-wide settings.
    for (target in build.targets) appendTargetTestSettings(target)
    module.publication?.let {
        appendPublishing(it)
        appendSigning(it)
    }
}.build()

/** Uses a lazy task collection because AGP registers `testAndroidHostTest` after script evaluation. */
private fun KtsWriter.appendTargetTestSettings(target: KmpTarget) {
    val (task, settings) = target.testTask() ?: return
    if (settings.isEmpty) return
    blank()
    block("tasks.withType<Test>().matching { it.name == ${quote(task)} }.configureEach") {
        appendJvmTestSettings(settings)
    }
}

private fun KmpTarget.testTask(): Pair<String, JvmTestSettings>? = when (val kind = kind) {
    is TargetKind.Jvm -> "${name}Test" to kind.testSettings
    // AGP names the task after the hostTest compilation.
    is TargetKind.Android ->
        "test${name.replaceFirstChar(Char::uppercaseChar)}HostTest" to kind.library.testSettings
    TargetKind.Js,
    TargetKind.WasmJs,
    TargetKind.WasmWasi,
    TargetKind.Native,
    -> null
}

/**
 * Adds API-level enforcement beyond class-file targets. With a distinct test release, Kotlin's
 * main `-Xjdk-release` is scoped to `compileKotlin` to avoid passing two release flags to tests.
 */
private fun KtsWriter.appendMainRelease(build: JvmBuild) {
    if (build.testRelease != null) {
        blank()
        block("tasks.compileKotlin") {
            block("compilerOptions") {
                line("freeCompilerArgs.add(${quote("-Xjdk-release=${build.release}")})")
            }
        }
    }
    blank()
    block("tasks.compileJava") {
        line("options.release.set(${build.release})")
    }
}

/** Applies the independent test release to both Kotlin and Java test compilation. */
private fun KtsWriter.appendTestRelease(release: String) {
    blank()
    block("tasks.compileTestKotlin") {
        block("compilerOptions") {
            line("jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(${quote(release)}))")
            line("freeCompilerArgs.add(${quote("-Xjdk-release=$release")})")
        }
    }
    blank()
    block("tasks.compileTestJava") {
        line("options.release.set($release)")
    }
}

private fun KtsWriter.appendTarget(target: KmpTarget) {
    when (val kind = target.kind) {
        is TargetKind.Jvm -> block("jvm") {
            block("compilerOptions") {
                line("jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(${quote(kind.release)}))")
                if (kind.testRelease == null) line("freeCompilerArgs.add(${quote("-Xjdk-release=${kind.release}")})")
                appendCompilerOptionLines(target.compilerOptions)
            }
            appendCompilationReleases(kind.release, kind.testRelease, testCompilation = "test")
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
        // wasmWasi exposes compiler options only through its compilations.
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

/** `withHostTestBuilder` registers the compilation consumed by `androidHostTest`. */
private fun KtsWriter.appendAndroidLibraryTarget(target: AndroidLibraryTarget, qualifiedOptions: CompilerOptions) {
    block("androidLibrary") {
        line("namespace = ${quote(target.namespace)}")
        line("compileSdk = ${target.compileSdk}")
        line("minSdk = ${target.minSdk}")
        line("withHostTestBuilder {}.configure {}")
        block("compilerOptions") {
            line("jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(${quote(target.release)}))")
            if (target.testRelease == null) {
                line("freeCompilerArgs.add(${quote("-Xjdk-release=${target.release}")})")
            }
            appendCompilerOptionLines(qualifiedOptions)
        }
        appendCompilationReleases(target.release, target.testRelease, testCompilation = "hostTest")
    }
}

/** Splits release flags by compilation only when tests request a distinct release. */
private fun KtsWriter.appendCompilationReleases(release: String, testRelease: String?, testCompilation: String) {
    if (testRelease == null) return
    appendCompilationRelease("main", release, jvmTarget = null)
    appendCompilationRelease(testCompilation, testRelease, jvmTarget = testRelease)
}

private fun KtsWriter.appendCompilationRelease(compilation: String, release: String, jvmTarget: String?) {
    block("compilations.named(${quote(compilation)}).configure") {
        block("compileTaskProvider.configure") {
            block("compilerOptions") {
                if (jvmTarget != null) {
                    line("jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(${quote(jvmTarget)}))")
                }
                line("freeCompilerArgs.add(${quote("-Xjdk-release=$release")})")
            }
        }
    }
}

private fun KtsWriter.appendPluginBlock(plugins: List<PluginDecl>) {
    block("plugins") {
        for (plugin in plugins) line(plugin.declaration())
    }
}

internal fun PluginDecl.declaration(): String = buildString {
    append(plugin.dsl())
    if (version != null) append(" version ${quote(version)}")
    if (!apply) append(" apply false")
}

/** The only Gradle DSL spelling of a [GradlePlugin]. */
internal fun GradlePlugin.dsl(): String = when (this) {
    is GradlePlugin.Kotlin -> "kotlin(${quote(shortName)})"
    // Hyphenated builtin accessors such as maven-publish require backticks.
    is GradlePlugin.Builtin -> if (id.all { it.isLetterOrDigit() }) id else "`$id`"
    is GradlePlugin.Android,
    is GradlePlugin.Other,
    -> "id(${quote(id)})"
}

/** Emits [extra] last for overrides; [jdkRelease] applies to every compilation covered by the block. */
private fun KtsWriter.appendCompilerOptions(
    options: CompilerOptions,
    extra: CompilerOptions = CompilerOptions.EMPTY,
    compilerPlugins: List<CompilerPlugin> = emptyList(),
    jdkRelease: String? = null,
) {
    val pluginArgs = compilerPlugins.map(CompilerPlugin::optionArguments).filter(List<String>::isNotEmpty)
    if (options.isEmpty && extra.isEmpty && pluginArgs.isEmpty() && jdkRelease == null) return
    block("compilerOptions") {
        appendCompilerOptionLines(options)
        jdkRelease?.let { line("freeCompilerArgs.add(${quote("-Xjdk-release=$it")})") }
        appendCompilerOptionLines(extra)
        for (arguments in pluginArgs) line("freeCompilerArgs.addAll(${arguments.joinToString(prefix = "listOf(", postfix = ")", transform = ::quote)})")
    }
}

/** Emits `-P` and its plugin option as two compiler arguments, as required by kotlinc. */
private fun CompilerPlugin.optionArguments(): List<String> =
    options.flatMap { (key, value) -> listOf("-P", "plugin:$id:$key=$value") }

/** Native targets need the plugin on both Kotlin and Kotlin/Native configurations. */
private fun KtsWriter.appendCompilerPluginClasspath(compilerPlugins: List<CompilerPlugin>, native: Boolean) {
    for (plugin in compilerPlugins) {
        line("kotlinCompilerPluginClasspath(${plugin.dependency.expression()})")
        if (native) line("kotlinNativeCompilerPluginClasspath(${plugin.dependency.expression()})")
    }
}

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

private fun KtsWriter.appendJvmTestSettings(settings: JvmTestSettings, receiver: String = "") {
    if (settings.freeJvmArgs.isNotEmpty()) {
        line("${receiver}jvmArgs(${settings.freeJvmArgs.joinToString(transform = ::quote)})")
    }
    for ((key, value) in settings.systemProperties) line("${receiver}systemProperty(${quote(key)}, ${quote(value)})")
    for ((key, value) in settings.environment) line("${receiver}environment(${quote(key)}, ${quote(value)})")
}

private fun KtsWriter.appendDependencies(
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
    val expression = target.expression()
    return if (bom) "platform($expression)" else expression
}

private fun DependencyTarget.expression(): String = when (this) {
    is DependencyTarget.Maven -> quote(coordinates)
    is DependencyTarget.Project -> "project(${quote(gradlePath)})"
    is DependencyTarget.Catalog -> accessor
    is DependencyTarget.KotlinBuiltin -> "kotlin(${quote(name)})"
}
