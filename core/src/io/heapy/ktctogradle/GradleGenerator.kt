package io.heapy.ktctogradle

import io.heapy.ktctogradle.interpret.PluginResolution
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
import io.heapy.ktctogradle.render.appendPluginBlock
import io.heapy.ktctogradle.render.mappingStrings
import io.heapy.ktctogradle.render.quote

internal class GradleGenerator {
    fun generate(project: ToolchainProject): GenerationResult {
        val diagnostics = DiagnosticCollector()
        val files = mutableListOf<GeneratedFile>()
        files += GeneratedFile(project.root / "settings.gradle.kts", renderSettings(project))
        val rootModule = project.modules.firstOrNull { it.path.isRoot }
        val subprojects = project.modules.filterNot { it.path.isRoot }
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
                renderModule(project, module, plugins + inherited, diagnostics)
            } ?: rootBuildFile(inherited),
        )
        for (module in subprojects) {
            val plugins = PluginResolution.declarationsFor(module.model, versions, declareVersions = false)
            files += GeneratedFile(module.directory / "build.gradle.kts", renderModule(project, module, plugins, diagnostics))
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
        project: ToolchainProject,
        module: ToolchainModule,
        plugins: List<PluginDecl>,
        diagnostics: DiagnosticCollector,
    ): String {
        rejectUnsupported(module)
        val product = product(module.config)
        return when (product.type) {
            "jvm/app", "jvm/lib" -> renderJvmModule(project, module, product, plugins, diagnostics)
            "android/app" -> renderAndroidModule(project, module, plugins, diagnostics)
            "kmp/lib", "js/app", "wasm-js/app", "wasm-wasi/app",
            "linux/app", "macos/app", "windows/app" -> renderMultiplatformModule(project, module, product, plugins, diagnostics)
            "ios/app" -> throw ConversionException(
                "${module.displayName}: ios/app contains an Xcode/Swift application and cannot be represented by a standalone Gradle module",
            )
            "jvm/amper-plugin" -> throw ConversionException(
                "${module.displayName}: Kotlin Toolchain build plugins have no automatic Gradle equivalent",
            )
            else -> throw ConversionException("${module.displayName}: unsupported product '${product.type}'")
        }
    }

    private fun renderJvmModule(
        project: ToolchainProject,
        module: ToolchainModule,
        product: Product,
        plugins: List<PluginDecl>,
        diagnostics: DiagnosticCollector,
    ): String {
        val config = module.config
        val jdk = config.string("settings.jvm.jdk.version") ?: "25"
        val release = config.string("settings.jvm.release") ?: jdk
        val serialization = Serialization.settings(module.model)
        val dependencies = dependenciesFor(module, listOf("dependencies", "dependencies@jvm"))
        val tests = dependenciesFor(module, listOf("test-dependencies", "test-dependencies@jvm"))
        val mainClass = config.string("settings.jvm.mainClass") ?: module.layout.detectedMainClass
        return writeKts {
            line(StaticAssets.header())
            appendRepositoryCredentialsImport(config)
            appendPluginBlock(plugins)
            blank()
            appendRepositories(config)
            blank()
            block("kotlin") {
                line("jvmToolchain($jdk)")
                appendCompilerOptions(
                    config,
                    jvmTarget = release,
                    extraLines = singlePlatformQualifiedLines(module, "jvm", diagnostics),
                )
            }
            blank()
            block("java") {
                line("sourceCompatibility = JavaVersion.toVersion(${quote(release)})")
                line("targetCompatibility = JavaVersion.toVersion(${quote(release)})")
            }
            if (config.string("layout") != "maven-like") {
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
                appendDependencies(project, module, dependencies, test = false)
                appendSerializationDependencies(serialization)
                appendBuiltInDependencies(config)
                line("testImplementation(kotlin(${quote(testLibrary(config))}))")
                appendDependencies(project, module, tests, test = true)
            }
            blank()
            block("tasks.test") {
                if (testLibrary(config) == "test-junit5") line("useJUnitPlatform()")
                appendJvmTestSettings(config)
            }
            if (product.type == "jvm/app") {
                if (mainClass == null) {
                    diagnostics.warn(
                        "${module.displayName}: could not infer a main class; set settings.jvm.mainClass or application.mainClass",
                    )
                } else {
                    blank()
                    block("application") {
                        line("mainClass.set(${quote(mainClass)})")
                    }
                }
            }
        }
    }

    private fun renderAndroidModule(
        project: ToolchainProject,
        module: ToolchainModule,
        plugins: List<PluginDecl>,
        diagnostics: DiagnosticCollector,
    ): String {
        val config = module.config
        val pinnedKotlinVersion = config.string("settings.kotlin.version")
        if (pinnedKotlinVersion != null) {
            diagnostics.warn(
                "${module.displayName}: settings.kotlin.version '$pinnedKotlinVersion' does not select the Kotlin " +
                    "compiler for an Android module; the Android Gradle Plugin ${Versions.ANDROID_GRADLE_PLUGIN} " +
                    "supplies its own Kotlin",
            )
        }
        val serialization = Serialization.settings(module.model)
        val release = config.string("settings.jvm.release") ?: "17"
        val namespace = config.string("settings.android.namespace") ?: "org.example.namespace"
        val compileSdk = config.string("settings.android.compileSdk") ?: config.string("settings.android.compileSdk.apiLevel") ?: "37"
        val minSdk = config.string("settings.android.minSdk") ?: "24"
        val targetSdk = config.string("settings.android.targetSdk") ?: compileSdk
        return writeKts {
            line(StaticAssets.header())
            appendRepositoryCredentialsImport(config)
            appendPluginBlock(plugins)
            blank()
            appendRepositories(config)
            blank()
            block("android") {
                line("namespace = ${quote(namespace)}")
                line("compileSdk = $compileSdk")
                block("defaultConfig") {
                    line("applicationId = ${quote(config.string("settings.android.applicationId") ?: namespace)}")
                    line("minSdk = $minSdk")
                    line("targetSdk = $targetSdk")
                    line("versionCode = ${config.string("settings.android.versionCode") ?: "1"}")
                    line("versionName = ${quote(config.string("settings.android.versionName") ?: "unspecified")}")
                }
                block("compileOptions") {
                    line("sourceCompatibility = JavaVersion.toVersion(${quote(release)})")
                    line("targetCompatibility = JavaVersion.toVersion(${quote(release)})")
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
                appendCompilerOptions(
                    config,
                    jvmTarget = release,
                    extraLines = singlePlatformQualifiedLines(module, "android", diagnostics),
                )
            }
            blank()
            block("dependencies") {
                appendDependencies(project, module, dependenciesFor(module, listOf("dependencies", "dependencies@android")), false)
                appendSerializationDependencies(serialization)
                appendBuiltInDependencies(config)
                line("testImplementation(kotlin(${quote(testLibrary(config))}))")
                appendDependencies(project, module, dependenciesFor(module, listOf("test-dependencies", "test-dependencies@android")), true)
            }
        }
    }

    private fun renderMultiplatformModule(
        project: ToolchainProject,
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
            appendRepositoryCredentialsImport(config)
            appendPluginBlock(plugins)
            blank()
            appendRepositories(config)
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
                    line("jvmToolchain(${config.string("settings.jvm.jdk.version") ?: "25"})")
                }
                appendCompilerOptions(config, extraLines = qualifiedCompilerOptionLines(qualified.common))
                block("sourceSets") {
                    block("commonMain") {
                        line("kotlin.srcDir(\"src\")")
                        line("resources.srcDir(\"resources\")")
                        block("dependencies") {
                            appendDependencies(project, module, dependenciesFor(module, listOf("dependencies")), false, sourceSet = true)
                            appendSerializationDependencies(serialization)
                            appendBuiltInDependencies(config)
                        }
                    }
                    block("commonTest") {
                        line("kotlin.srcDir(\"test\")")
                        line("resources.srcDir(\"testResources\")")
                        block("dependencies") {
                            line("implementation(kotlin(\"test\"))")
                            appendDependencies(project, module, dependenciesFor(module, listOf("test-dependencies")), true, sourceSet = true)
                        }
                    }
                    for (fragment in fragments.filterNot { it.name == "common" }) {
                        appendQualifiedSourceSet(project, module, fragment, false)
                        appendQualifiedSourceSet(project, module, fragment, true)
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
                    ?: "25"
                block("jvm") {
                    block("compilerOptions") {
                        line("jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(${quote(release)}))")
                        line("freeCompilerArgs.add(${quote("-Xjdk-release=$release")})")
                        for (option in qualifiedOptions) line(option)
                    }
                }
            }
            "android" -> appendAndroidLibraryTarget(config, module, qualifiedOptions, diagnostics)
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

    private fun KtsWriter.appendCompilerOptionsBlock(lines: List<String>) {
        if (lines.isEmpty()) return
        block("compilerOptions") {
            for (option in lines) line(option)
        }
    }

    private fun KtsWriter.appendAndroidLibraryTarget(
        config: Value.Mapping,
        module: ToolchainModule,
        qualifiedOptions: List<String>,
        diagnostics: DiagnosticCollector,
    ) {
        val namespace = config.string("settings.android.namespace") ?: derivedAndroidNamespace(module).also {
            diagnostics.warn("${module.displayName}: settings.android.namespace is not set; using '$it'")
        }
        block("androidLibrary") {
            line("namespace = ${quote(namespace)}")
            line("compileSdk = ${config.string("settings.android.compileSdk") ?: "37"}")
            line("minSdk = ${config.string("settings.android.minSdk") ?: "24"}")
            line("withHostTestBuilder {}.configure {}")
            appendCompilerOptionsBlock(qualifiedOptions)
        }
    }

    /**
     * The Toolchain synthesizes an internal package when a module leaves the namespace out,
     * but the Android Gradle Plugin insists on a real one, so derive a stable package here.
     */
    private fun derivedAndroidNamespace(module: ToolchainModule): String {
        val segments = module.path.segments.ifEmpty { listOf(module.displayName) }
        val packageSegments = segments.map { segment ->
            val sanitized = segment.lowercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
            if (sanitized.firstOrNull()?.isDigit() != false) "_$sanitized" else sanitized
        }
        return (listOf("ktc", "generated") + packageSegments).joinToString(".")
    }

    private fun KtsWriter.appendQualifiedSourceSet(
        project: ToolchainProject,
        module: ToolchainModule,
        fragment: KmpFragment,
        test: Boolean,
    ) {
        val qualifier = fragment.name
        val prefix = if (test) "test" else "src"
        val resources = if (test) "testResources" else "resources"
        val dependencyKey = if (test) "test-dependencies@$qualifier" else "dependencies@$qualifier"
        val deps = dependenciesFor(module, listOf(dependencyKey))
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
                    appendDependencies(project, module, deps, test, sourceSet = true)
                }
            }
        }
    }

    private fun KtsWriter.appendRepositories(config: Value.Mapping) {
        block("repositories") {
            for ((index, repository) in resolutionRepositories(config).withIndex()) {
                when {
                    repository.url == "mavenLocal" -> line("mavenLocal()")
                    repository.id == "mavenCentral" && repository.url == MAVEN_CENTRAL_URL && repository.credentials == null -> {
                        line("mavenCentral()")
                    }
                    repository.id == "mavenGoogle" && repository.url == GOOGLE_MAVEN_URL && repository.credentials == null -> {
                        line("google()")
                    }
                    else -> block("maven") {
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
     * A repository written as a plain URL has no id of its own, so a module that repeats a default
     * repository would otherwise be treated as a separate one and emitted next to it.
     */
    private fun defaultRepositoryId(url: String): String? = when (url.trimEnd('/')) {
        MAVEN_CENTRAL_URL -> "mavenCentral"
        GOOGLE_MAVEN_URL -> "mavenGoogle"
        else -> null
    }

    private fun resolutionRepositories(config: Value.Mapping): List<Repository> {
        val configured = config.value("repositories").asSequence("repositories").mapIndexed { index, value ->
            when (value) {
                is Value.Scalar -> Repository(id = defaultRepositoryId(value.text) ?: value.text, url = value.text)
                is Value.Mapping -> {
                    val url = value.string("url")
                        ?: throw ConversionException("repositories[$index].url is required")
                    val credentials = (value.value("credentials") as? Value.Mapping)?.let { credentialValues ->
                        RepositoryCredentials(
                            file = credentialValues.string("file")
                                ?: throw ConversionException("repositories[$index].credentials.file is required"),
                            usernameKey = credentialValues.string("usernameKey")
                                ?: throw ConversionException("repositories[$index].credentials.usernameKey is required"),
                            passwordKey = credentialValues.string("passwordKey")
                                ?: throw ConversionException("repositories[$index].credentials.passwordKey is required"),
                        )
                    }
                    Repository(
                        id = value.string("id") ?: defaultRepositoryId(url) ?: url,
                        url = url,
                        resolve = value.boolean("resolve") ?: true,
                        credentials = credentials,
                    )
                }
                else -> throw ConversionException("repositories[$index] must be a URL string or object")
            }
        }
        val configuredIds = configured.mapTo(mutableSetOf(), Repository::id)
        val defaults = listOf(
            Repository(id = "mavenCentral", url = MAVEN_CENTRAL_URL),
            Repository(id = "mavenGoogle", url = GOOGLE_MAVEN_URL),
        ).filterNot { it.id in configuredIds }
        return defaults + configured.filter(Repository::resolve).asReversed().distinctBy(Repository::id).asReversed()
    }

    private fun KtsWriter.appendRepositoryCredentialsImport(config: Value.Mapping) {
        val hasCredentials = config.value("repositories").asSequence("repositories").any { repository ->
            (repository as? Value.Mapping)?.value("credentials") is Value.Mapping
        }
        if (hasCredentials) {
            blank()
            line("import java.util.Properties")
            blank()
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

    private fun KtsWriter.appendJvmTestSettings(config: Value.Mapping) {
        val args = config.strings("settings.jvm.test.freeJvmArgs") + config.strings("test-settings.jvm.freeJvmArgs")
        if (args.isNotEmpty()) line("jvmArgs(${args.joinToString(transform = ::quote)})")
        val systemProperties = mappingStrings(config.value("settings.jvm.test.systemProperties")) +
            mappingStrings(config.value("test-settings.jvm.systemProperties"))
        for ((key, value) in systemProperties) {
            line("systemProperty(${quote(key)}, ${quote(value)})")
        }
        val environment = mappingStrings(config.value("settings.jvm.test.extraEnvironment")) +
            mappingStrings(config.value("test-settings.jvm.extraEnvironment"))
        for ((key, value) in environment) {
            line("environment(${quote(key)}, ${quote(value)})")
        }
    }

    private fun KtsWriter.appendDependencies(
        project: ToolchainProject,
        module: ToolchainModule,
        dependencies: List<Dependency>,
        test: Boolean,
        sourceSet: Boolean = false,
    ) {
        for (dependency in dependencies) {
            val configuration = when {
                sourceSet && dependency.scope == "compile-only" -> "compileOnly"
                sourceSet && dependency.scope == "runtime-only" -> "runtimeOnly"
                sourceSet && dependency.exported && !test -> "api"
                sourceSet -> "implementation"
                dependency.scope == "compile-only" -> if (test) "testCompileOnly" else "compileOnly"
                dependency.scope == "runtime-only" -> if (test) "testRuntimeOnly" else "runtimeOnly"
                dependency.exported && !test -> "api"
                test -> "testImplementation"
                else -> "implementation"
            }
            val expression = dependencyExpression(project, module, dependency)
            line("$configuration($expression)")
        }
    }

    private fun dependencyExpression(project: ToolchainProject, module: ToolchainModule, dependency: Dependency): String {
        var notation = dependency.notation
        val expression = when {
            notation.startsWith("//") -> {
                val path = ModulePath.parse(notation)
                val target = project.modules.firstOrNull { it.path == path }
                    ?: throw ConversionException("${module.displayName}: unknown module '$notation'")
                "project(${quote(target.gradlePath)})"
            }
            notation.startsWith("./") || notation.startsWith("../") -> {
                val targetDirectory = (module.directory / notation).normalized()
                // The plain comparison misses when the module directory is reached through a symlink,
                // so fall back to the directories the load stage already canonicalized.
                val canonicalTargetDirectory = (module.canonicalDirectory / notation).normalized()
                val target = project.modules.firstOrNull { it.directory == targetDirectory }
                    ?: project.modules.firstOrNull { it.canonicalDirectory == canonicalTargetDirectory }
                    ?: throw ConversionException("${module.displayName}: unknown module '$notation'")
                "project(${quote(target.gradlePath)})"
            }
            notation.startsWith("\$libs.") -> notation.removePrefix("\$")
            notation.startsWith("\$kotlin.") -> kotlinCatalogExpression(module, notation.removePrefix("\$kotlin."))
            notation.startsWith("\$") -> throw ConversionException(
                "${module.displayName}: built-in catalog dependency '$notation' needs technology-specific manual conversion",
            )
            else -> quote(notation)
        }
        return if (dependency.bom) "platform($expression)" else expression
    }

    private fun kotlinCatalogExpression(module: ToolchainModule, key: String): String = when (key) {
        "reflect" -> "kotlin(\"reflect\")"
        "test", "test.common" -> "kotlin(\"test\")"
        "test.junit", "test.junit5" -> "kotlin(\"test-junit5\")"
        else -> {
            val serializationKey = key.removePrefix("serialization.")
            if (serializationKey == key) {
                throw ConversionException("${module.displayName}: unsupported Kotlin catalog alias '\$kotlin.$key'")
            }
            val serialization = Serialization.settings(module.model)
                ?: throw ConversionException(
                    "${module.displayName}: '\$kotlin.$key' requires settings.kotlin.serialization to be enabled",
                )
            quote(Serialization.coordinate(serializationKey, serialization.version))
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
            val version = config.string("settings.ktor.version") ?: "3.5.2"
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

    private fun testLibrary(config: Value.Mapping): String = when (config.string("settings.junit") ?: "junit-5") {
        "junit-5" -> "test-junit5"
        "junit-4" -> "test-junit"
        "none" -> "test"
        else -> throw ConversionException("settings.junit must be junit-5, junit-4, or none")
    }

    private fun dependenciesFor(module: ToolchainModule, keys: List<String>): List<Dependency> = keys.flatMap { key ->
        module.config.value(key).asSequence("${module.displayName}.$key").map(::parseDependency)
    }

    private fun parseDependency(value: Value): Dependency = when (value) {
        is Value.Scalar -> {
            val match = Regex("^(.*):\\s+(all|compile-only|runtime-only|exported)$").matchEntire(value.text)
            val suffix = match?.groupValues?.get(2)
            Dependency(
                notation = match?.groupValues?.get(1) ?: value.text,
                scope = if (suffix == "exported") "all" else suffix ?: "all",
                exported = suffix == "exported",
            )
        }
        is Value.Mapping -> {
            if (value.entries.size != 1) throw ConversionException("Dependency objects must have one coordinate")
            val (key, details) = value.entries.entries.single()
            if (key == "bom") return Dependency(details.scalarOrNull() ?: throw ConversionException("bom must be a coordinate"), bom = true)
            details.scalarOrNull()?.let { return shorthandDependency(key, it) }
            val detailMap = details as? Value.Mapping
            Dependency(
                notation = key,
                scope = detailMap?.string("scope") ?: "all",
                exported = detailMap?.boolean("exported") ?: false,
            )
        }
        else -> throw ConversionException("Dependencies must be strings or objects")
    }

    private fun shorthandDependency(notation: String, shorthand: String): Dependency = when (shorthand) {
        "all" -> Dependency(notation)
        "compile-only", "runtime-only" -> Dependency(notation, scope = shorthand)
        "exported" -> Dependency(notation, exported = true)
        else -> throw ConversionException("Dependency '$notation' has unknown scope '$shorthand'")
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

    private data class Dependency(
        val notation: String,
        val scope: String = "all",
        val exported: Boolean = false,
        val bom: Boolean = false,
    )

    private data class RepositoryCredentials(
        val file: String,
        val usernameKey: String,
        val passwordKey: String,
    )

    private data class Repository(
        val id: String,
        val url: String,
        val resolve: Boolean = true,
        val credentials: RepositoryCredentials? = null,
    )

    private data class KmpFragment(
        val name: String,
        val platforms: Set<String>,
        val natural: Boolean,
        val parents: List<String> = emptyList(),
    )

    companion object {
        private const val MAVEN_CENTRAL_URL = "https://repo1.maven.org/maven2"
        private const val GOOGLE_MAVEN_URL = "https://maven.google.com"

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
