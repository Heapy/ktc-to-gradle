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

/**
 * Spells a module out as `build.gradle.kts`.
 *
 * The renderer decides nothing: every default and every choice already reached it as model data, so
 * this is the one `when` that has to stay exhaustive when a product family is added.
 */
internal fun renderModule(module: GradleModule): String = when (val build = module.build) {
    null -> renderRootShell(module)
    is JvmBuild -> renderJvmModule(module, build)
    is AndroidBuild -> renderAndroidModule(module, build)
    is MultiplatformBuild -> renderMultiplatformModule(module, build)
}

/**
 * The root of a project that has no module of its own.
 *
 * `base` gives the root the lifecycle tasks a build is expected to answer to; everything else it
 * declares is a plugin its subprojects apply.
 */
private fun renderRootShell(module: GradleModule): String = KtsWriter().apply {
    line(StaticAssets.header())
    appendPluginBlock(module.plugins)
}.build()

/**
 * [JvmBuild.qualifiedCompilerOptions] stays apart from the module-wide ones because it is emitted
 * after them rather than merged into them, which is how a qualified section overrides what it
 * restates.
 */
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
        appendCompilerOptions(build.compilerOptions, build.qualifiedCompilerOptions, module.compilerPlugins)
    }
    blank()
    block("java") {
        line("sourceCompatibility = JavaVersion.toVersion(${quote(build.release)})")
        line("targetCompatibility = JavaVersion.toVersion(${quote(build.release)})")
        module.publication?.let { publication ->
            // Maven Central refuses a publication without a sources jar, and the Toolchain builds it
            // from the same switch, so it is declared here rather than as a task of its own.
            if (publication.publishSources) line("withSourcesJar()")
            // The Toolchain adds an empty javadoc jar to every publication, because Maven Central
            // refuses one without it and Kotlin has no javadoc to generate.
            line("withJavadocJar()")
        }
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
    build.mainClass?.let { mainClass ->
        blank()
        block("application") {
            line("mainClass.set(${quote(mainClass)})")
        }
    }
    module.publication?.let {
        appendPublishing(it)
        appendSigning(it)
    }
}.build()

/**
 * Spells an `android/app` module out as `build.gradle.kts`.
 *
 * The Android Gradle Plugin owns the source layout of the module, so the source-set directories are
 * fixed text rather than model data.
 */
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
        // The Android Gradle Plugin runs unit tests on JUnit 4 unless it is told otherwise, so a
        // suite that needs the JUnit platform compiles and is then never discovered. The same block
        // carries the JVM test settings, because `unitTests.all` is the only handle AGP offers on
        // the unit-test task.
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

/**
 * Spells a multiplatform module out as `build.gradle.kts`.
 *
 * Source sets are emitted in the order the interpret stage put them in, which has every parent
 * before its children: `dependsOn(getByName(...))` resolves a name that must already exist.
 */
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
        // The Kotlin Gradle Plugin publishes a sources jar per target unless it is told not to,
        // while `publishSources` defaults to off, so the multiplatform half of the switch is the
        // `false` case and not the `true` one.
        module.publication?.takeIf { !it.publishSources }?.let { line("withSourcesJar(publish = false)") }
        appendCompilerOptions(build.compilerOptions, build.qualifiedCompilerOptions, module.compilerPlugins)
        block("sourceSets") {
            for (sourceSet in build.sourceSets) appendSourceSet(sourceSet)
        }
    }
    if (module.compilerPlugins.isNotEmpty()) {
        blank()
        // A multiplatform module declares its dependencies per source set, so the plugin classpath
        // is the one thing it needs a module-wide `dependencies { }` block for.
        block("dependencies") {
            appendCompilerPluginClasspath(
                module.compilerPlugins,
                native = build.targets.any { it.kind == TargetKind.Native },
            )
        }
    }
    // Every JVM-backed target of the module runs its tests through a Gradle `Test` task, and each
    // of them keeps Gradle's JUnit 4 runner unless told otherwise. There is no per-target DSL that
    // covers both `jvm()` and `androidLibrary`, so they are configured together.
    val junitPlatform = build.testFramework.runsOnTheJUnitPlatform
    if ((junitPlatform || !build.testSettings.isEmpty) && build.targets.any { it.kind.runsOnAJdk }) {
        blank()
        block("tasks.withType<Test>().configureEach") {
            if (junitPlatform) line("useJUnitPlatform()")
            appendJvmTestSettings(build.testSettings)
        }
    }
    module.publication?.let {
        appendPublishing(it)
        appendSigning(it)
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
private fun KtsWriter.appendAndroidLibraryTarget(target: AndroidLibraryTarget, qualifiedOptions: CompilerOptions) {
    block("androidLibrary") {
        line("namespace = ${quote(target.namespace)}")
        line("compileSdk = ${target.compileSdk}")
        line("minSdk = ${target.minSdk}")
        line("withHostTestBuilder {}.configure {}")
        block("compilerOptions") {
            line("jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(${quote(target.release)}))")
            line("freeCompilerArgs.add(${quote("-Xjdk-release=${target.release}")})")
            appendCompilerOptionLines(qualifiedOptions)
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

/** The only place that knows how a [GradlePlugin] is spelled in the Gradle Kotlin DSL. */
internal fun GradlePlugin.dsl(): String = when (this) {
    is GradlePlugin.Kotlin -> "kotlin(${quote(shortName)})"
    // `maven-publish` is not a Kotlin identifier, and the accessor Gradle generates for it has to be
    // escaped. Every other builtin the converter applies is a plain word and stays one.
    is GradlePlugin.Builtin -> if (id.all { it.isLetterOrDigit() }) id else "`$id`"
    is GradlePlugin.Android,
    is GradlePlugin.Other,
    -> "id(${quote(id)})"
}

/**
 * A `compilerOptions { }` block, or nothing at all when there is nothing to say.
 *
 * [extra] is emitted after [options] rather than merged into it: Gradle applies the later
 * statement, so that is how a platform-qualified section overrides what it restates.
 */
private fun KtsWriter.appendCompilerOptions(
    options: CompilerOptions,
    extra: CompilerOptions = CompilerOptions.EMPTY,
    compilerPlugins: List<CompilerPlugin> = emptyList(),
) {
    val pluginArgs = compilerPlugins.map(CompilerPlugin::optionArguments).filter(List<String>::isNotEmpty)
    if (options.isEmpty && extra.isEmpty && pluginArgs.isEmpty()) return
    block("compilerOptions") {
        appendCompilerOptionLines(options)
        appendCompilerOptionLines(extra)
        for (arguments in pluginArgs) line("freeCompilerArgs.addAll(${arguments.joinToString(prefix = "listOf(", postfix = ")", transform = ::quote)})")
    }
}

/**
 * The `-P plugin:<id>:<key>=<value>` pair the compiler takes for every option of one plugin.
 *
 * `-P` and its value are two arguments, not one: the compiler reads the value from the next
 * argument, and a single joined string is passed through as an unknown flag.
 */
private fun CompilerPlugin.optionArguments(): List<String> =
    options.flatMap { (key, value) -> listOf("-P", "plugin:$id:$key=$value") }

/**
 * The configurations a third-party compiler plugin has to be on to reach the compiler.
 *
 * Kotlin/Native runs a compiler of its own and reads a second configuration, so a module with a
 * native target names the artifact twice.
 */
private fun KtsWriter.appendCompilerPluginClasspath(compilerPlugins: List<CompilerPlugin>, native: Boolean) {
    for (plugin in compilerPlugins) {
        line("kotlinCompilerPluginClasspath(${plugin.dependency.expression()})")
        if (native) line("kotlinNativeCompilerPluginClasspath(${plugin.dependency.expression()})")
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

/**
 * [receiver] prefixes every call, for the blocks whose lambda takes the `Test` task as an argument
 * instead of as `this`: the Android Gradle Plugin's `unitTests.all { }` is one of those.
 */
private fun KtsWriter.appendJvmTestSettings(settings: JvmTestSettings, receiver: String = "") {
    if (settings.freeJvmArgs.isNotEmpty()) {
        line("${receiver}jvmArgs(${settings.freeJvmArgs.joinToString(transform = ::quote)})")
    }
    for ((key, value) in settings.systemProperties) line("${receiver}systemProperty(${quote(key)}, ${quote(value)})")
    for ((key, value) in settings.environment) line("${receiver}environment(${quote(key)}, ${quote(value)})")
}

/**
 * [sourceSet] switches to the Kotlin Multiplatform source-set DSL, which names its configurations
 * without the `test` prefix because the source set already says which compilation it belongs to.
 */
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
