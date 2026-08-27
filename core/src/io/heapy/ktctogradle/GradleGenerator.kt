package io.heapy.ktctogradle

import io.heapy.ktctogradle.interpret.AndroidInterpreter
import io.heapy.ktctogradle.interpret.Defaults
import io.heapy.ktctogradle.interpret.Dependencies
import io.heapy.ktctogradle.interpret.JvmInterpreter
import io.heapy.ktctogradle.interpret.ModuleIndex
import io.heapy.ktctogradle.interpret.PluginResolution
import io.heapy.ktctogradle.interpret.Repositories
import io.heapy.ktctogradle.interpret.Serialization
import io.heapy.ktctogradle.interpret.SerializationSettings
import io.heapy.ktctogradle.load.Value
import io.heapy.ktctogradle.load.asSequence
import io.heapy.ktctogradle.load.boolean
import io.heapy.ktctogradle.load.mergeValues
import io.heapy.ktctogradle.load.scalarOrNull
import io.heapy.ktctogradle.load.string
import io.heapy.ktctogradle.load.strings
import io.heapy.ktctogradle.load.value
import io.heapy.ktctogradle.model.GeneratedFile
import io.heapy.ktctogradle.model.GradlePlugin
import io.heapy.ktctogradle.model.PluginDecl
import io.heapy.ktctogradle.render.KtsWriter
import io.heapy.ktctogradle.render.StaticAssets
import io.heapy.ktctogradle.render.appendAndroidLibraryTarget
import io.heapy.ktctogradle.render.appendCompilerOptionsBlock
import io.heapy.ktctogradle.render.appendCredentialsImport
import io.heapy.ktctogradle.render.appendDependencies
import io.heapy.ktctogradle.render.appendPluginBlock
import io.heapy.ktctogradle.render.appendRepositories
import io.heapy.ktctogradle.render.quote
import io.heapy.ktctogradle.render.renderAndroidModule
import io.heapy.ktctogradle.render.renderJvmModule

internal class GradleGenerator {
    fun generate(project: ToolchainProject): GenerationResult {
        val diagnostics = DiagnosticCollector()
        val files = mutableListOf<GeneratedFile>()
        files += GeneratedFile(project.root / "settings.gradle.kts", renderSettings(project))
        val rootModule = project.modules.firstOrNull { it.path.isRoot }
        val subprojects = project.modules.filterNot { it.path.isRoot }
        val index = ModuleIndex.of(project.modules)
        val versions = PluginResolution.resolveVersions(project.modules.map(ToolchainModule::model), diagnostics)
        val inherited = PluginResolution.inheritedDeclarations(
            root = rootModule?.model,
            subprojects = subprojects.map(ToolchainModule::model),
            versions = versions,
        )
        files += GeneratedFile(
            project.root / "build.gradle.kts",
            rootModule?.let { module ->
                val plugins = PluginResolution.declarationsFor(module.model, versions, declareVersions = true)
                renderModule(index, module, plugins + inherited, diagnostics)
            } ?: rootBuildFile(inherited),
        )
        for (module in subprojects) {
            val plugins = PluginResolution.declarationsFor(module.model, versions, declareVersions = false)
            files += GeneratedFile(module.directory / "build.gradle.kts", renderModule(index, module, plugins, diagnostics))
        }
        files += GeneratedFile(project.root / "gradlew", StaticAssets.unixGradleLauncher())
        files += GeneratedFile(project.root / "gradlew.bat", StaticAssets.windowsGradleLauncher())
        files += GeneratedFile(project.root / "gradle" / "wrapper" / "gradle-wrapper.properties", StaticAssets.wrapperProperties())
        files += GeneratedFile(project.root / "gradle.properties", StaticAssets.generatedGradleProperties())
        return GenerationResult(files, diagnostics.drain())
    }

    private fun renderSettings(project: ToolchainProject): String = writeKts {
        line(StaticAssets.header())
        block("pluginManagement") {
            block("repositories") {
                line("gradlePluginPortal()")
                line("google()")
                line("mavenCentral()")
            }
        }
        blank()
        line("rootProject.name = ${quote(project.name)}")
        if (project.catalogPath?.parent == project.root) {
            blank()
            block("dependencyResolutionManagement") {
                block("versionCatalogs") {
                    line("create(\"libs\") { from(files(\"libs.versions.toml\")) }")
                }
            }
        }
        for (module in project.modules.filterNot { it.path.isRoot }) {
            blank()
            line("include(${quote(module.gradlePath)})")
            line("project(${quote(module.gradlePath)}).projectDir = file(${quote(module.path.notation)})")
        }
    }

    private fun rootBuildFile(inherited: List<PluginDecl>): String = writeKts {
        line(StaticAssets.header())
        appendPluginBlock(listOf(PluginDecl(GradlePlugin.Builtin.BASE, version = null)) + inherited)
    }

    private fun writeKts(body: KtsWriter.() -> Unit): String = KtsWriter().apply(body).build()

    private fun renderModule(
        index: ModuleIndex,
        module: ToolchainModule,
        plugins: List<PluginDecl>,
        diagnostics: DiagnosticCollector,
    ): String {
        rejectUnsupported(module)
        val product = product(module.config)
        return when (product.type) {
            "jvm/app", "jvm/lib" -> renderJvm(index, module, plugins, diagnostics)
            "android/app" -> renderAndroid(index, module, plugins, diagnostics)
            "kmp/lib", "js/app", "wasm-js/app", "wasm-wasi/app",
            "linux/app", "macos/app", "windows/app" -> renderMultiplatformModule(index, module, product, plugins, diagnostics)
            "ios/app" -> throw ConversionException(
                "${module.displayName}: ios/app contains an Xcode/Swift application and cannot be represented by a standalone Gradle module",
            )
            "jvm/amper-plugin" -> throw ConversionException(
                "${module.displayName}: Kotlin Toolchain build plugins have no automatic Gradle equivalent",
            )
            else -> throw ConversionException("${module.displayName}: unsupported product '${product.type}'")
        }
    }

    /**
     * The qualified compiler options are resolved before the module is interpreted, because they
     * report their own diagnostics and those are ordered ahead of the missing-main-class warning.
     */
    private fun renderJvm(
        index: ModuleIndex,
        module: ToolchainModule,
        plugins: List<PluginDecl>,
        diagnostics: DiagnosticCollector,
    ): String {
        val qualifiedOptions = singlePlatformQualifiedLines(module, "jvm", diagnostics)
        return renderJvmModule(
            plugins = plugins,
            repositories = Repositories.resolution(module.model),
            credentialsImport = Repositories.requiresCredentialsImport(module.model),
            build = JvmInterpreter.interpret(index, module, diagnostics),
            qualifiedCompilerOptions = qualifiedOptions,
        )
    }

    /**
     * The pinned-Kotlin-version warning is reported before the qualified sections are read, so the
     * module's own diagnostic keeps its place ahead of the dropped-key ones.
     */
    private fun renderAndroid(
        index: ModuleIndex,
        module: ToolchainModule,
        plugins: List<PluginDecl>,
        diagnostics: DiagnosticCollector,
    ): String {
        val repositories = Repositories.resolution(module.model)
        val credentialsImport = Repositories.requiresCredentialsImport(module.model)
        val build = AndroidInterpreter.interpret(index, module, diagnostics)
        return renderAndroidModule(
            plugins = plugins,
            repositories = repositories,
            credentialsImport = credentialsImport,
            build = build,
            qualifiedCompilerOptions = singlePlatformQualifiedLines(module, "android", diagnostics),
        )
    }

    private fun renderMultiplatformModule(
        index: ModuleIndex,
        module: ToolchainModule,
        product: Product,
        plugins: List<PluginDecl>,
        diagnostics: DiagnosticCollector,
    ): String {
        val config = module.config
        val serialization = Serialization.settings(module.model)
        val fragments = kmpFragments(module, product)
        return writeKts {
            line(StaticAssets.header())
            appendCredentialsImport(Repositories.requiresCredentialsImport(module.model))
            appendPluginBlock(plugins)
            blank()
            appendRepositories(Repositories.resolution(module.model))
            blank()
            block("kotlin") {
                val qualified = qualifiedSettings(module, fragments.map { it.name to it.platforms }, diagnostics)
                for (platform in product.platforms) {
                    appendTarget(
                        platform,
                        product.type,
                        config,
                        module,
                        qualifiedCompilerOptionLines(qualified.byPlatform[platform]),
                        diagnostics,
                    )
                }
                if ("jvm" in product.platforms) {
                    line("jvmToolchain(${config.string("settings.jvm.jdk.version") ?: Defaults.JVM_JDK})")
                }
                appendCompilerOptions(config, extraLines = qualifiedCompilerOptionLines(qualified.common))
                block("sourceSets") {
                    block("commonMain") {
                        line("kotlin.srcDir(\"src\")")
                        line("resources.srcDir(\"resources\")")
                        block("dependencies") {
                            appendDependencies(Dependencies.of(index, module, false, commonQualifiers), false, sourceSet = true)
                            appendSerializationDependencies(serialization)
                            appendBuiltInDependencies(config)
                        }
                    }
                    block("commonTest") {
                        line("kotlin.srcDir(\"test\")")
                        line("resources.srcDir(\"testResources\")")
                        block("dependencies") {
                            line("implementation(kotlin(\"test\"))")
                            appendDependencies(Dependencies.of(index, module, true, commonQualifiers), true, sourceSet = true)
                        }
                    }
                    for (fragment in fragments.filterNot { it.name == "common" }) {
                        appendQualifiedSourceSet(index, module, fragment, false)
                        appendQualifiedSourceSet(index, module, fragment, true)
                    }
                }
            }
        }
    }

    private fun KtsWriter.appendTarget(
        platform: String,
        productType: String,
        config: Value.Mapping,
        module: ToolchainModule,
        qualifiedOptions: List<String>,
        diagnostics: DiagnosticCollector,
    ) {
        val executable = productType.endsWith("/app")
        when (platform) {
            "jvm" -> {
                val release = config.string("settings.jvm.release")
                    ?: config.string("settings.jvm.jdk.version")
                    ?: Defaults.JVM_JDK
                block("jvm") {
                    block("compilerOptions") {
                        line("jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(${quote(release)}))")
                        line("freeCompilerArgs.add(${quote("-Xjdk-release=$release")})")
                        for (option in qualifiedOptions) line(option)
                    }
                }
            }
            "android" -> appendAndroidLibraryTarget(
                AndroidInterpreter.libraryTarget(module, diagnostics),
                qualifiedOptions,
            )
            "js" -> appendBrowserTarget("js(IR)", executable, qualifiedOptions)
            "wasmJs" -> appendBrowserTarget("wasmJs", executable, qualifiedOptions)
            "wasmWasi" -> {
                if (qualifiedOptions.isEmpty()) {
                    line("wasmWasi { ${if (executable) "binaries.executable()" else ""} }")
                } else {
                    block("wasmWasi") {
                        if (executable) line("binaries.executable()")
                        // The wasmWasi target DSL carries no compilerOptions of its own, so the options
                        // have to reach the compile tasks through its compilations.
                        block("compilations.configureEach") {
                            block("compileTaskProvider.configure") {
                                appendCompilerOptionsBlock(qualifiedOptions)
                            }
                        }
                    }
                }
            }
            in nativeTargets -> {
                block(platform) {
                    if (executable) {
                        block("binaries.executable") {
                            config.string("settings.native.entryPoint")?.let { line("entryPoint = ${quote(it)}") }
                        }
                    }
                    appendCompilerOptionsBlock(qualifiedOptions)
                }
            }
            else -> throw ConversionException("Unsupported Kotlin platform '$platform'")
        }
    }

    private fun KtsWriter.appendBrowserTarget(target: String, executable: Boolean, qualifiedOptions: List<String>) {
        if (qualifiedOptions.isEmpty()) {
            line("$target { ${if (executable) "binaries.executable(); " else ""}browser() }")
            return
        }
        block(target) {
            if (executable) line("binaries.executable()")
            line("browser()")
            appendCompilerOptionsBlock(qualifiedOptions)
        }
    }

    private fun KtsWriter.appendQualifiedSourceSet(
        index: ModuleIndex,
        module: ToolchainModule,
        fragment: KmpFragment,
        test: Boolean,
    ) {
        val qualifier = fragment.name
        val prefix = if (test) "test" else "src"
        val resources = if (test) "testResources" else "resources"
        val deps = Dependencies.of(index, module, test, listOf(qualifier))
        val sourceExists = "$prefix@$qualifier" in module.layout.existingSourceDirs
        val resourcesExist = "$resources@$qualifier" in module.layout.existingSourceDirs
        val suffix = if (test) "Test" else "Main"
        // The Android Gradle Plugin calls the unit-test source set androidHostTest;
        // androidTest is its on-device suite, so tests placed there never run.
        val sourceSet = if (qualifier == "android" && test) "androidHostTest" else "$qualifier$suffix"
        block("maybeCreate(${quote(sourceSet)}).apply") {
            for (parent in fragment.parents) {
                line("dependsOn(getByName(${quote("${parent}$suffix")}))")
            }
            if (sourceExists) line("kotlin.srcDir(${quote("$prefix@$qualifier")})")
            if (resourcesExist) line("resources.srcDir(${quote("$resources@$qualifier")})")
            if (deps.isNotEmpty()) {
                block("dependencies") {
                    appendDependencies(deps, test, sourceSet = true)
                }
            }
        }
    }
    private fun KtsWriter.appendCompilerOptions(
        config: Value.Mapping,
        jvmTarget: String? = null,
        extraLines: List<String> = emptyList(),
    ) {
        val language = config.string("settings.kotlin.languageVersion")
        val api = config.string("settings.kotlin.apiVersion")
        val freeArgs = config.strings("settings.kotlin.freeCompilerArgs")
        val optIns = config.strings("settings.kotlin.optIns")
        if (language == null && api == null && freeArgs.isEmpty() && optIns.isEmpty() && extraLines.isEmpty() &&
            config.boolean("settings.kotlin.allWarningsAsErrors") != true && config.boolean("settings.kotlin.progressiveMode") != true && jvmTarget == null
        ) return
        block("compilerOptions") {
            language?.let { line("languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.fromVersion(${quote(it)}))") }
            api?.let { line("apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.fromVersion(${quote(it)}))") }
            jvmTarget?.let { line("this.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(${quote(it)}))") }
            if (config.boolean("settings.kotlin.allWarningsAsErrors") == true) line("allWarningsAsErrors.set(true)")
            if (config.boolean("settings.kotlin.progressiveMode") == true) line("progressiveMode.set(true)")
            if (freeArgs.isNotEmpty()) line("freeCompilerArgs.addAll(${freeArgs.joinToString(prefix = "listOf(", postfix = ")", transform = ::quote)})")
            if (optIns.isNotEmpty()) line("optIn.addAll(${optIns.joinToString(prefix = "listOf(", postfix = ")", transform = ::quote)})")
            for (extra in extraLines) line(extra)
        }
    }

    private fun KtsWriter.appendSerializationDependencies(serialization: SerializationSettings?) {
        if (serialization == null) return
        line("implementation(${quote(Serialization.coordinate("core", serialization.version))})")
        serialization.format?.let { format ->
            line("implementation(${quote(Serialization.coordinate(format, serialization.version))})")
        }
    }

    private fun KtsWriter.appendBuiltInDependencies(config: Value.Mapping) {
        if (config.boolean("settings.ktor") == true || config.boolean("settings.ktor.enabled") == true) {
            val version = config.string("settings.ktor.version") ?: Defaults.KTOR
            line("implementation(platform(\"io.ktor:ktor-bom:$version\"))")
        }
    }

    private fun kmpFragments(module: ToolchainModule, product: Product): List<KmpFragment> {
        val declaredPlatforms = product.platforms.toSet()
        val aliases = aliases(module.config)
        for ((alias, platforms) in aliases) {
            val unknown = platforms - declaredPlatforms
            if (unknown.isNotEmpty()) {
                throw ConversionException(
                    "${module.displayName}: alias '$alias' contains undeclared platforms ${unknown.sorted().joinToString()}",
                )
            }
            if (alias == "common" || alias in naturalPlatformParents) {
                throw ConversionException("${module.displayName}: alias '$alias' conflicts with the default platform hierarchy")
            }
        }

        val naturalNames = buildSet {
            add("common")
            for (platform in declaredPlatforms) {
                var current: String? = platform
                while (current != null) {
                    add(current)
                    current = naturalPlatformParents[current]
                }
            }
        }
        val fragments = buildList {
            for (name in naturalNames) {
                add(
                    KmpFragment(
                        name = name,
                        platforms = declaredPlatforms.filterTo(linkedSetOf()) { leaf ->
                            leaf == name || isNaturalAncestor(name, leaf)
                        },
                        natural = true,
                    ),
                )
            }
            for ((name, platforms) in aliases) {
                add(KmpFragment(name, platforms, natural = false))
            }
        }
        val withParents = fragments.map { fragment ->
            val candidates = fragments.filter { candidate -> candidate !== fragment && isBroader(candidate, fragment) }
            val directParents = candidates.filter { candidate ->
                candidates.none { other -> other !== candidate && isBroader(candidate, other) }
            }.map(KmpFragment::name)
            fragment.copy(parents = directParents)
        }

        val ordered = mutableListOf<KmpFragment>()
        val remaining = withParents.toMutableList()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { fragment -> fragment.parents.all { parent -> ordered.any { it.name == parent } } }
            if (ready.isEmpty()) throw ConversionException("${module.displayName}: platform aliases form an invalid hierarchy")
            ordered += ready.sortedBy(KmpFragment::name)
            remaining.removeAll(ready.toSet())
        }
        return ordered
    }

    private fun aliases(config: Value.Mapping): Map<String, Set<String>> {
        val node = config.value("aliases") ?: return emptyMap()
        val entries = when (node) {
            is Value.Mapping -> node.entries.entries.toList()
            is Value.Sequence -> node.items.flatMapIndexed { index, item ->
                val mapping = item as? Value.Mapping
                    ?: throw ConversionException("aliases[$index] must be an object")
                if (mapping.entries.size != 1) throw ConversionException("aliases[$index] must define exactly one alias")
                mapping.entries.entries.toList()
            }
            else -> throw ConversionException("aliases must be an object or list")
        }
        return buildMap {
            for ((name, value) in entries) {
                if (name in this) throw ConversionException("Alias '$name' is declared more than once")
                val platforms = value.asSequence("aliases.$name").mapIndexed { index, platform ->
                    platform.scalarOrNull()
                        ?: throw ConversionException("aliases.$name[$index] must be a platform name")
                }.toSet()
                if (platforms.isEmpty()) throw ConversionException("Alias '$name' must contain at least one platform")
                put(name, platforms)
            }
        }
    }

    private fun isBroader(candidate: KmpFragment, fragment: KmpFragment): Boolean {
        if (candidate.name == "common" && fragment.name != "common") return true
        if (candidate.natural && fragment.natural && isNaturalAncestor(candidate.name, fragment.name)) return true
        return candidate.platforms.size > fragment.platforms.size && candidate.platforms.containsAll(fragment.platforms)
    }

    private fun isNaturalAncestor(ancestor: String, descendant: String): Boolean {
        var current = naturalPlatformParents[descendant]
        while (current != null) {
            if (current == ancestor) return true
            current = naturalPlatformParents[current]
        }
        return false
    }

    private data class QualifiedSettings(val common: Value.Mapping?, val byPlatform: Map<String, Value.Mapping>)

    /**
     * Splits the `settings@<qualifier>` sections of a module into the part that applies to every
     * platform and the part that applies to single ones. [fragmentOrder] lists the qualifiers the
     * module accepts, broadest first, so a narrower section overrides a broader one even when both
     * happen to cover the same leaves.
     */
    private fun qualifiedSettings(
        module: ToolchainModule,
        fragmentOrder: List<Pair<String, Set<String>>>,
        diagnostics: DiagnosticCollector,
    ): QualifiedSettings {
        val rank = fragmentOrder.withIndex().associate { (index, entry) -> entry.first to index }
        val platformsOf = fragmentOrder.toMap()
        val sections = mutableListOf<Triple<Int, Set<String>, Value.Mapping>>()
        for ((key, value) in module.config.entries) {
            if (key.startsWith("test-settings@")) {
                diagnostics.warn("${module.displayName}: '$key' is not supported by the converter and was dropped")
                continue
            }
            if (!key.startsWith("settings@")) continue
            val qualifier = key.removePrefix("settings@")
            val platforms = platformsOf[qualifier]
            if (platforms == null) {
                diagnostics.warn("${module.displayName}: '$key' names no platform of this module and was dropped")
                continue
            }
            if (value !is Value.Mapping) {
                diagnostics.warn("${module.displayName}: '$key' must be an object and was dropped")
                continue
            }
            validateQualifiedSettings(module, qualifier, value, diagnostics)
            sections += Triple(rank.getValue(qualifier), platforms, value)
        }
        sections.sortBy { (index, _, _) -> index }

        var common: Value.Mapping? = null
        val byPlatform = mutableMapOf<String, Value.Mapping>()
        for ((index, platforms, settings) in sections) {
            if (fragmentOrder[index].first == "common") {
                common = common?.let { mergeValues(it, settings) as Value.Mapping } ?: settings
                continue
            }
            for (platform in platforms) {
                byPlatform[platform] = byPlatform[platform]?.let { mergeValues(it, settings) as Value.Mapping } ?: settings
            }
        }
        return QualifiedSettings(common, byPlatform)
    }

    /** Reports every key of a qualified section the converter cannot carry into the Gradle build. */
    private fun validateQualifiedSettings(
        module: ToolchainModule,
        qualifier: String,
        settings: Value.Mapping,
        diagnostics: DiagnosticCollector,
    ) {
        fun drop(path: String, reason: String = "is not supported by the converter") {
            diagnostics.warn("${module.displayName}: 'settings@$qualifier.$path' $reason and was dropped")
        }
        for ((section, value) in settings.entries) {
            if (section != "kotlin") {
                for (path in leafPaths(section, value)) drop(path)
                continue
            }
            val kotlin = value as? Value.Mapping ?: run {
                drop(section, "must be an object")
                continue
            }
            for ((key, option) in kotlin.entries) {
                when (key) {
                    "languageVersion", "apiVersion" ->
                        if (option.scalarOrNull() == null) drop("kotlin.$key", "must be a string")
                    "allWarningsAsErrors", "progressiveMode" ->
                        if (kotlin.boolean(key) == null) drop("kotlin.$key", "must be true or false")
                    "freeCompilerArgs", "optIns" ->
                        if (option !is Value.Sequence) drop("kotlin.$key", "must be a list")
                    else -> for (path in leafPaths("kotlin.$key", option)) drop(path)
                }
            }
        }
    }

    private fun leafPaths(prefix: String, value: Value): List<String> = when (value) {
        is Value.Mapping -> value.entries.flatMap { (key, child) -> leafPaths("$prefix.$key", child) }
        else -> listOf(prefix)
    }

    /**
     * The compilerOptions body a qualified section contributes. Only the keys the section declares
     * are emitted: a Gradle target inherits the module-wide options and overrides what it restates,
     * so a section that turns a flag off has to say so explicitly. Malformed values were already
     * reported by [validateQualifiedSettings] and are skipped here.
     */
    private fun qualifiedCompilerOptionLines(settings: Value.Mapping?): List<String> {
        val kotlin = settings?.entries?.get("kotlin") as? Value.Mapping ?: return emptyList()
        return buildList {
            kotlin.string("languageVersion")?.let {
                add("languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.fromVersion(${quote(it)}))")
            }
            kotlin.string("apiVersion")?.let {
                add("apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.fromVersion(${quote(it)}))")
            }
            kotlin.boolean("allWarningsAsErrors")?.let { add("allWarningsAsErrors.set($it)") }
            kotlin.boolean("progressiveMode")?.let { add("progressiveMode.set($it)") }
            addAll(listOption(kotlin, "freeCompilerArgs", "freeCompilerArgs"))
            addAll(listOption(kotlin, "optIns", "optIn"))
        }
    }

    private fun listOption(kotlin: Value.Mapping, key: String, property: String): List<String> {
        val items = (kotlin.entries[key] as? Value.Sequence)?.items?.mapNotNull(Value::scalarOrNull).orEmpty()
        if (items.isEmpty()) return emptyList()
        return listOf("$property.addAll(${items.joinToString(prefix = "listOf(", postfix = ")", transform = ::quote)})")
    }

    /** The compilerOptions lines of a single-platform product, module-wide and qualified together. */
    private fun singlePlatformQualifiedLines(
        module: ToolchainModule,
        platform: String,
        diagnostics: DiagnosticCollector,
    ): List<String> {
        val qualified = qualifiedSettings(module, listOf("common" to setOf(platform), platform to setOf(platform)), diagnostics)
        val merged = listOfNotNull(qualified.common, qualified.byPlatform[platform])
            .reduceOrNull { lower, higher -> mergeValues(lower, higher) as Value.Mapping }
        return qualifiedCompilerOptionLines(merged)
    }

    private fun rejectUnsupported(module: ToolchainModule) {
        val unsupportedKeys = listOf("plugins", "mavenPlugins")
        for (key in unsupportedKeys) if (module.config.value(key) != null) {
            throw ConversionException("${module.displayName}: '$key' cannot be converted automatically")
        }
        val unsupportedSettings = listOf(
            "settings.compose", "settings.springBoot", "settings.lombok", "settings.kotlin.ksp",
            "settings.kotlin.rpc", "settings.kotlin.dataframe", "settings.kotlin.compilerPlugins",
        )
        for (path in unsupportedSettings) if (module.config.value(path) != null) {
            throw ConversionException("${module.displayName}: '$path' is not supported yet")
        }
    }

    private data class KmpFragment(
        val name: String,
        val platforms: Set<String>,
        val natural: Boolean,
        val parents: List<String> = emptyList(),
    )

    companion object {
        /** Only the unqualified section: a multiplatform module reads its qualified ones per fragment. */
        private val commonQualifiers = listOf("")

        private val qualifiedKotlinOptionKeys = setOf(
            "languageVersion", "apiVersion", "allWarningsAsErrors", "progressiveMode", "freeCompilerArgs", "optIns",
        )

        private val naturalPlatformParents = mapOf(
            "jvm" to "common",
            "android" to "common",
            "web" to "common",
            "js" to "web",
            "wasmJs" to "web",
            "wasmWasi" to "common",
            "native" to "common",
            "linux" to "native",
            "linuxX64" to "linux",
            "linuxArm64" to "linux",
            "mingw" to "native",
            "mingwX64" to "mingw",
            "apple" to "native",
            "macos" to "apple",
            "macosX64" to "macos",
            "macosArm64" to "macos",
            "ios" to "apple",
            "iosArm64" to "ios",
            "iosSimulatorArm64" to "ios",
            "iosX64" to "ios",
            "watchos" to "apple",
            "watchosArm32" to "watchos",
            "watchosArm64" to "watchos",
            "watchosDeviceArm64" to "watchos",
            "watchosSimulatorArm64" to "watchos",
            "tvos" to "apple",
            "tvosArm64" to "tvos",
            "tvosSimulatorArm64" to "tvos",
            "tvosX64" to "tvos",
            "androidNative" to "native",
            "androidNativeArm32" to "androidNative",
            "androidNativeArm64" to "androidNative",
            "androidNativeX86" to "androidNative",
            "androidNativeX64" to "androidNative",
        )

        private val nativeTargets = setOf(
            "linuxX64", "linuxArm64", "macosX64", "macosArm64", "mingwX64", "iosX64", "iosArm64",
            "iosSimulatorArm64", "watchosArm32", "watchosArm64", "watchosDeviceArm64",
            "watchosSimulatorArm64", "tvosArm64", "tvosSimulatorArm64", "tvosX64", "androidNativeArm32",
            "androidNativeArm64", "androidNativeX86", "androidNativeX64",
        )
    }
}
