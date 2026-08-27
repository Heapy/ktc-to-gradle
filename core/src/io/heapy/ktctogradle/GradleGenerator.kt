package io.heapy.ktctogradle

import okio.FileSystem
import okio.Path

internal data class GeneratedFile(val path: Path, val content: String)

internal class GradleGenerator(private val fileSystem: FileSystem) {
    private val diagnostics = mutableListOf<Diagnostic>()

    fun generate(project: ToolchainProject): Pair<List<GeneratedFile>, List<Diagnostic>> {
        diagnostics.clear()
        val files = mutableListOf<GeneratedFile>()
        files += GeneratedFile(project.root / "settings.gradle.kts", renderSettings(project))
        val rootModule = project.modules.firstOrNull { it.path.isRoot }
        files += GeneratedFile(
            project.root / "build.gradle.kts",
            rootModule?.let { renderModule(project, it) } ?: rootBuildFile(),
        )
        for (module in project.modules.filterNot { it.path.isRoot }) {
            files += GeneratedFile(module.directory / "build.gradle.kts", renderModule(project, module))
        }
        files += GeneratedFile(project.root / "gradlew", unixGradleLauncher())
        files += GeneratedFile(project.root / "gradlew.bat", windowsGradleLauncher())
        files += GeneratedFile(project.root / "gradle" / "wrapper" / "gradle-wrapper.properties", wrapperProperties())
        files += GeneratedFile(project.root / "gradle.properties", generatedGradleProperties())
        return files to diagnostics.toList()
    }

    private fun renderSettings(project: ToolchainProject): String = buildString {
        appendLine(header())
        appendLine("pluginManagement {")
        appendLine("    repositories {")
        appendLine("        gradlePluginPortal()")
        appendLine("        google()")
        appendLine("        mavenCentral()")
        appendLine("    }")
        appendLine("}")
        appendLine()
        appendLine("rootProject.name = ${quote(project.name)}")
        if (project.catalogPath?.parent == project.root) {
            appendLine()
            appendLine("dependencyResolutionManagement {")
            appendLine("    versionCatalogs {")
            appendLine("        create(\"libs\") { from(files(\"libs.versions.toml\")) }")
            appendLine("    }")
            appendLine("}")
        }
        for (module in project.modules.filterNot { it.path.isRoot }) {
            appendLine()
            appendLine("include(${quote(module.gradlePath)})")
            appendLine("project(${quote(module.gradlePath)}).projectDir = file(${quote(module.path.notation)})")
        }
    }

    private fun rootBuildFile(): String = """
        ${header()}
        plugins {
            base
        }
    """.trimIndent() + "\n"

    private fun renderModule(project: ToolchainProject, module: ToolchainModule): String {
        rejectUnsupported(module)
        val product = product(module.config)
        return when (product.type) {
            "jvm/app", "jvm/lib" -> renderJvmModule(project, module, product)
            "android/app" -> renderAndroidModule(project, module)
            "kmp/lib", "js/app", "wasm-js/app", "wasm-wasi/app",
            "linux/app", "macos/app", "windows/app" -> renderMultiplatformModule(project, module, product)
            "ios/app" -> throw ConversionException(
                "${module.displayName}: ios/app contains an Xcode/Swift application and cannot be represented by a standalone Gradle module",
            )
            "jvm/amper-plugin" -> throw ConversionException(
                "${module.displayName}: Kotlin Toolchain build plugins have no automatic Gradle equivalent",
            )
            else -> throw ConversionException("${module.displayName}: unsupported product '${product.type}'")
        }
    }

    private fun renderJvmModule(project: ToolchainProject, module: ToolchainModule, product: Product): String {
        val config = module.config
        val kotlinVersion = config.string("settings.kotlin.version") ?: Versions.KOTLIN
        val jdk = config.string("settings.jvm.jdk.version") ?: "25"
        val release = config.string("settings.jvm.release") ?: jdk
        val serialization = serializationSettings(config)
        val dependencies = dependenciesFor(module, listOf("dependencies", "dependencies@jvm"))
        val tests = dependenciesFor(module, listOf("test-dependencies", "test-dependencies@jvm"))
        val mainClass = config.string("settings.jvm.mainClass") ?: detectMainClass(module)
        return buildString {
            appendLine(header())
            appendRepositoryCredentialsImport(config)
            appendLine("plugins {")
            appendLine("    kotlin(\"jvm\") version ${quote(kotlinVersion)}")
            if (product.type == "jvm/app") appendLine("    application")
            if (serialization != null) appendLine("    kotlin(\"plugin.serialization\") version ${quote(kotlinVersion)}")
            appendLine("}")
            appendLine()
            appendRepositories(config)
            appendLine()
            appendLine("kotlin {")
            appendLine("    jvmToolchain($jdk)")
            appendCompilerOptions(config, "    ", jvmTarget = release)
            appendLine("}")
            appendLine()
            appendLine("java {")
            appendLine("    sourceCompatibility = JavaVersion.toVersion(${quote(release)})")
            appendLine("    targetCompatibility = JavaVersion.toVersion(${quote(release)})")
            appendLine("}")
            if (config.string("layout") != "maven-like") {
                appendLine()
                appendLine("sourceSets {")
                appendLine("    main {")
                appendLine("        kotlin.srcDir(\"src\")")
                appendLine("        resources.srcDir(\"resources\")")
                appendLine("    }")
                appendLine("    test {")
                appendLine("        kotlin.srcDir(\"test\")")
                appendLine("        resources.srcDir(\"testResources\")")
                appendLine("    }")
                appendLine("}")
            }
            appendLine()
            appendLine("dependencies {")
            appendDependencies(project, module, dependencies, test = false, indent = "    ")
            appendSerializationDependencies(serialization, "    ")
            appendBuiltInDependencies(config, "    ")
            appendLine("    testImplementation(kotlin(${quote(testLibrary(config))}))")
            appendDependencies(project, module, tests, test = true, indent = "    ")
            appendLine("}")
            appendLine()
            appendLine("tasks.test {")
            if (testLibrary(config) == "test-junit5") appendLine("    useJUnitPlatform()")
            appendJvmTestSettings(config, "    ")
            appendLine("}")
            if (product.type == "jvm/app") {
                if (mainClass == null) {
                    diagnostics += Diagnostic(
                        Diagnostic.Severity.WARNING,
                        "${module.displayName}: could not infer a main class; set settings.jvm.mainClass or application.mainClass",
                    )
                } else {
                    appendLine()
                    appendLine("application {")
                    appendLine("    mainClass.set(${quote(mainClass)})")
                    appendLine("}")
                }
            }
        }
    }

    private fun renderAndroidModule(project: ToolchainProject, module: ToolchainModule): String {
        val config = module.config
        val pinnedKotlinVersion = config.string("settings.kotlin.version")
        if (pinnedKotlinVersion != null) {
            diagnostics += Diagnostic(
                Diagnostic.Severity.WARNING,
                "${module.displayName}: settings.kotlin.version '$pinnedKotlinVersion' does not select the Kotlin " +
                    "compiler for an Android module; the Android Gradle Plugin ${Versions.ANDROID_GRADLE_PLUGIN} " +
                    "supplies its own Kotlin",
            )
        }
        val kotlinVersion = pinnedKotlinVersion ?: Versions.KOTLIN
        val serialization = serializationSettings(config)
        val release = config.string("settings.jvm.release") ?: "17"
        val namespace = config.string("settings.android.namespace") ?: "org.example.namespace"
        val compileSdk = config.string("settings.android.compileSdk") ?: config.string("settings.android.compileSdk.apiLevel") ?: "37"
        val minSdk = config.string("settings.android.minSdk") ?: "24"
        val targetSdk = config.string("settings.android.targetSdk") ?: compileSdk
        return buildString {
            appendLine(header())
            appendRepositoryCredentialsImport(config)
            appendLine("plugins {")
            appendLine("    id(\"com.android.application\") version ${quote(Versions.ANDROID_GRADLE_PLUGIN)}")
            if (serialization != null) appendLine("    kotlin(\"plugin.serialization\") version ${quote(kotlinVersion)}")
            appendLine("}")
            appendLine()
            appendRepositories(config)
            appendLine()
            appendLine("android {")
            appendLine("    namespace = ${quote(namespace)}")
            appendLine("    compileSdk = $compileSdk")
            appendLine("    defaultConfig {")
            appendLine("        applicationId = ${quote(config.string("settings.android.applicationId") ?: namespace)}")
            appendLine("        minSdk = $minSdk")
            appendLine("        targetSdk = $targetSdk")
            appendLine("        versionCode = ${config.string("settings.android.versionCode") ?: "1"}")
            appendLine("        versionName = ${quote(config.string("settings.android.versionName") ?: "unspecified")}")
            appendLine("    }")
            appendLine("    compileOptions {")
            appendLine("        sourceCompatibility = JavaVersion.toVersion(${quote(release)})")
            appendLine("        targetCompatibility = JavaVersion.toVersion(${quote(release)})")
            appendLine("    }")
            appendLine("    sourceSets.named(\"main\") {")
            appendLine("        kotlin.srcDirs(\"src\", \"src@android\")")
            appendLine("        resources.srcDirs(\"resources\", \"resources@android\")")
            appendLine("        manifest.srcFile(\"src/AndroidManifest.xml\")")
            appendLine("    }")
            appendLine("    sourceSets.named(\"test\") {")
            appendLine("        kotlin.srcDirs(\"test\", \"test@android\")")
            appendLine("        resources.srcDirs(\"testResources\", \"testResources@android\")")
            appendLine("    }")
            appendLine("}")
            appendLine()
            appendLine("kotlin {")
            appendCompilerOptions(config, "    ", jvmTarget = release)
            appendLine("}")
            appendLine()
            appendLine("dependencies {")
            appendDependencies(project, module, dependenciesFor(module, listOf("dependencies", "dependencies@android")), false, "    ")
            appendSerializationDependencies(serialization, "    ")
            appendBuiltInDependencies(config, "    ")
            appendLine("    testImplementation(kotlin(${quote(testLibrary(config))}))")
            appendDependencies(project, module, dependenciesFor(module, listOf("test-dependencies", "test-dependencies@android")), true, "    ")
            appendLine("}")
        }
    }

    private fun renderMultiplatformModule(project: ToolchainProject, module: ToolchainModule, product: Product): String {
        val config = module.config
        val kotlinVersion = config.string("settings.kotlin.version") ?: Versions.KOTLIN
        val serialization = serializationSettings(config)
        val fragments = kmpFragments(module, product)
        return buildString {
            appendLine(header())
            appendRepositoryCredentialsImport(config)
            appendLine("plugins {")
            appendLine("    kotlin(\"multiplatform\") version ${quote(kotlinVersion)}")
            if ("android" in product.platforms) {
                appendLine("    id(\"com.android.kotlin.multiplatform.library\") version ${quote(Versions.ANDROID_GRADLE_PLUGIN)}")
            }
            if (serialization != null) appendLine("    kotlin(\"plugin.serialization\") version ${quote(kotlinVersion)}")
            appendLine("}")
            appendLine()
            appendRepositories(config)
            appendLine()
            appendLine("kotlin {")
            for (platform in product.platforms) appendTarget(platform, product.type, config, module)
            if ("jvm" in product.platforms) {
                appendLine("    jvmToolchain(${config.string("settings.jvm.jdk.version") ?: "25"})")
            }
            appendCompilerOptions(config, "    ")
            appendLine("    sourceSets {")
            appendLine("        commonMain {")
            appendLine("            kotlin.srcDir(\"src\")")
            appendLine("            resources.srcDir(\"resources\")")
            appendLine("            dependencies {")
            appendDependencies(project, module, dependenciesFor(module, listOf("dependencies")), false, "                ", sourceSet = true)
            appendSerializationDependencies(serialization, "                ")
            appendBuiltInDependencies(config, "                ")
            appendLine("            }")
            appendLine("        }")
            appendLine("        commonTest {")
            appendLine("            kotlin.srcDir(\"test\")")
            appendLine("            resources.srcDir(\"testResources\")")
            appendLine("            dependencies {")
            appendLine("                implementation(kotlin(\"test\"))")
            appendDependencies(project, module, dependenciesFor(module, listOf("test-dependencies")), true, "                ", sourceSet = true)
            appendLine("            }")
            appendLine("        }")
            for (fragment in fragments.filterNot { it.name == "common" }) {
                appendQualifiedSourceSet(project, module, fragment, false)
                appendQualifiedSourceSet(project, module, fragment, true)
            }
            appendLine("    }")
            appendLine("}")
        }
    }

    private fun StringBuilder.appendTarget(
        platform: String,
        productType: String,
        config: Value.Mapping,
        module: ToolchainModule,
    ) {
        val executable = productType.endsWith("/app")
        when (platform) {
            "jvm" -> {
                val release = config.string("settings.jvm.release")
                    ?: config.string("settings.jvm.jdk.version")
                    ?: "25"
                appendLine("    jvm {")
                appendLine("        compilerOptions {")
                appendLine("            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(${quote(release)}))")
                appendLine("            freeCompilerArgs.add(${quote("-Xjdk-release=$release")})")
                appendLine("        }")
                appendLine("    }")
            }
            "android" -> appendAndroidLibraryTarget(config, module)
            "js" -> appendLine("    js(IR) { ${if (executable) "binaries.executable(); " else ""}browser() }")
            "wasmJs" -> appendLine("    wasmJs { ${if (executable) "binaries.executable(); " else ""}browser() }")
            "wasmWasi" -> appendLine("    wasmWasi { ${if (executable) "binaries.executable()" else ""} }")
            in nativeTargets -> {
                appendLine("    $platform {")
                if (executable) {
                    appendLine("        binaries.executable {")
                    config.string("settings.native.entryPoint")?.let { appendLine("            entryPoint = ${quote(it)}") }
                    appendLine("        }")
                }
                appendLine("    }")
            }
            else -> throw ConversionException("Unsupported Kotlin platform '$platform'")
        }
    }

    private fun StringBuilder.appendAndroidLibraryTarget(config: Value.Mapping, module: ToolchainModule) {
        val namespace = config.string("settings.android.namespace") ?: derivedAndroidNamespace(module).also {
            diagnostics += Diagnostic(
                Diagnostic.Severity.WARNING,
                "${module.displayName}: settings.android.namespace is not set; using '$it'",
            )
        }
        appendLine("    androidLibrary {")
        appendLine("        namespace = ${quote(namespace)}")
        appendLine("        compileSdk = ${config.string("settings.android.compileSdk") ?: "37"}")
        appendLine("        minSdk = ${config.string("settings.android.minSdk") ?: "24"}")
        appendLine("        withHostTestBuilder {}.configure {}")
        appendLine("    }")
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

    private fun StringBuilder.appendQualifiedSourceSet(
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
        val sourceExists = fileSystem.exists(module.directory / "$prefix@$qualifier")
        val resourcesExist = fileSystem.exists(module.directory / "$resources@$qualifier")
        val suffix = if (test) "Test" else "Main"
        // The Android Gradle Plugin calls the unit-test source set androidHostTest;
        // androidTest is its on-device suite, so tests placed there never run.
        val sourceSet = if (qualifier == "android" && test) "androidHostTest" else "$qualifier$suffix"
        appendLine("        maybeCreate(${quote(sourceSet)}).apply {")
        for (parent in fragment.parents) {
            appendLine("            dependsOn(getByName(${quote("${parent}$suffix")}))")
        }
        if (sourceExists) appendLine("            kotlin.srcDir(${quote("$prefix@$qualifier")})")
        if (resourcesExist) appendLine("            resources.srcDir(${quote("$resources@$qualifier")})")
        if (deps.isNotEmpty()) {
            appendLine("            dependencies {")
            appendDependencies(project, module, deps, test, "                ", sourceSet = true)
            appendLine("            }")
        }
        appendLine("        }")
    }

    private fun StringBuilder.appendRepositories(config: Value.Mapping) {
        appendLine("repositories {")
        for ((index, repository) in resolutionRepositories(config).withIndex()) {
            when {
                repository.url == "mavenLocal" -> appendLine("    mavenLocal()")
                repository.id == "mavenCentral" && repository.url == MAVEN_CENTRAL_URL && repository.credentials == null -> {
                    appendLine("    mavenCentral()")
                }
                repository.id == "mavenGoogle" && repository.url == GOOGLE_MAVEN_URL && repository.credentials == null -> {
                    appendLine("    google()")
                }
                else -> {
                    appendLine("    maven {")
                    appendLine("        name = ${quote(repository.id)}")
                    appendLine("        url = uri(${quote(repository.url)})")
                    repository.credentials?.let { credentials ->
                        val variable = "repositoryCredentials$index"
                        appendLine("        val $variable = Properties()")
                        appendLine("        file(${quote(credentials.file)}).inputStream().use($variable::load)")
                        appendLine("        credentials {")
                        appendLine("            username = $variable.getProperty(${quote(credentials.usernameKey)})")
                        appendLine("            password = $variable.getProperty(${quote(credentials.passwordKey)})")
                        appendLine("        }")
                    }
                    appendLine("    }")
                }
            }
        }
        appendLine("}")
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

    private fun StringBuilder.appendRepositoryCredentialsImport(config: Value.Mapping) {
        val hasCredentials = config.value("repositories").asSequence("repositories").any { repository ->
            (repository as? Value.Mapping)?.value("credentials") is Value.Mapping
        }
        if (hasCredentials) {
            appendLine()
            appendLine("import java.util.Properties")
            appendLine()
        }
    }

    private fun StringBuilder.appendCompilerOptions(config: Value.Mapping, indent: String, jvmTarget: String? = null) {
        val language = config.string("settings.kotlin.languageVersion")
        val api = config.string("settings.kotlin.apiVersion")
        val freeArgs = config.strings("settings.kotlin.freeCompilerArgs")
        val optIns = config.strings("settings.kotlin.optIns")
        if (language == null && api == null && freeArgs.isEmpty() && optIns.isEmpty() &&
            config.boolean("settings.kotlin.allWarningsAsErrors") != true && config.boolean("settings.kotlin.progressiveMode") != true && jvmTarget == null
        ) return
        appendLine("${indent}compilerOptions {")
        language?.let { appendLine("${indent}    languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.fromVersion(${quote(it)}))") }
        api?.let { appendLine("${indent}    apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.fromVersion(${quote(it)}))") }
        jvmTarget?.let { appendLine("${indent}    this.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(${quote(it)}))") }
        if (config.boolean("settings.kotlin.allWarningsAsErrors") == true) appendLine("${indent}    allWarningsAsErrors.set(true)")
        if (config.boolean("settings.kotlin.progressiveMode") == true) appendLine("${indent}    progressiveMode.set(true)")
        if (freeArgs.isNotEmpty()) appendLine("${indent}    freeCompilerArgs.addAll(${freeArgs.joinToString(prefix = "listOf(", postfix = ")", transform = ::quote)})")
        if (optIns.isNotEmpty()) appendLine("${indent}    optIn.addAll(${optIns.joinToString(prefix = "listOf(", postfix = ")", transform = ::quote)})")
        appendLine("${indent}}")
    }

    private fun StringBuilder.appendJvmTestSettings(config: Value.Mapping, indent: String) {
        val args = config.strings("settings.jvm.test.freeJvmArgs") + config.strings("test-settings.jvm.freeJvmArgs")
        if (args.isNotEmpty()) appendLine("${indent}jvmArgs(${args.joinToString(transform = ::quote)})")
        val systemProperties = mappingStrings(config.value("settings.jvm.test.systemProperties")) +
            mappingStrings(config.value("test-settings.jvm.systemProperties"))
        for ((key, value) in systemProperties) {
            appendLine("${indent}systemProperty(${quote(key)}, ${quote(value)})")
        }
        val environment = mappingStrings(config.value("settings.jvm.test.extraEnvironment")) +
            mappingStrings(config.value("test-settings.jvm.extraEnvironment"))
        for ((key, value) in environment) {
            appendLine("${indent}environment(${quote(key)}, ${quote(value)})")
        }
    }

    private fun StringBuilder.appendDependencies(
        project: ToolchainProject,
        module: ToolchainModule,
        dependencies: List<Dependency>,
        test: Boolean,
        indent: String,
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
            appendLine("$indent$configuration($expression)")
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
                val target = project.modules.firstOrNull { it.directory == targetDirectory }
                    ?: project.modules.firstOrNull { fileSystem.canonicalize(it.directory) == fileSystem.canonicalize(targetDirectory) }
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
            val serialization = serializationSettings(module.config)
                ?: throw ConversionException(
                    "${module.displayName}: '\$kotlin.$key' requires settings.kotlin.serialization to be enabled",
                )
            quote(serializationCoordinate(serializationKey, serialization.version))
        }
    }

    private fun StringBuilder.appendSerializationDependencies(serialization: SerializationSettings?, indent: String) {
        if (serialization == null) return
        appendLine("${indent}implementation(${quote(serializationCoordinate("core", serialization.version))})")
        serialization.format?.let { format ->
            appendLine("${indent}implementation(${quote(serializationCoordinate(format, serialization.version))})")
        }
    }

    private fun StringBuilder.appendBuiltInDependencies(config: Value.Mapping, indent: String) {
        if (config.boolean("settings.ktor") == true || config.boolean("settings.ktor.enabled") == true) {
            val version = config.string("settings.ktor.version") ?: "3.5.2"
            appendLine("${indent}implementation(platform(\"io.ktor:ktor-bom:$version\"))")
        }
    }

    private fun serializationSettings(config: Value.Mapping): SerializationSettings? {
        val node = config.value("settings.kotlin.serialization") ?: return null
        return when (node) {
            is Value.Scalar -> when (node.text) {
                "disabled", "false" -> null
                "enabled", "true" -> SerializationSettings(DEFAULT_SERIALIZATION_VERSION)
                else -> SerializationSettings(DEFAULT_SERIALIZATION_VERSION, node.text)
            }
            is Value.Mapping -> if (node.boolean("enabled") == false) {
                null
            } else {
                SerializationSettings(
                    version = node.string("version") ?: DEFAULT_SERIALIZATION_VERSION,
                    format = node.string("format"),
                )
            }
            else -> throw ConversionException("settings.kotlin.serialization must be a string or object")
        }
    }

    private fun serializationCoordinate(key: String, version: String): String {
        val artifact = serializationArtifacts[key]
            ?: throw ConversionException("Unknown Kotlin serialization catalog alias '\$kotlin.serialization.$key'")
        return "org.jetbrains.kotlinx:$artifact:$version"
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

    private fun detectMainClass(module: ToolchainModule): String? {
        val sourceRoots = listOf(module.directory / "src", module.directory / "src@jvm")
        for (root in sourceRoots.filter(fileSystem::exists)) {
            val files = mutableListOf<Path>()
            collectKotlinFiles(root, files)
            val main = files.firstOrNull { it.name.equals("main.kt", ignoreCase = true) } ?: continue
            val text = fileSystem.read(main) { readUtf8() }
            if (!Regex("\\bfun\\s+main\\s*\\(").containsMatchIn(text)) continue
            val packageName = Regex("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)").find(text)?.groupValues?.get(1)
            val className = main.name.removeSuffix(".kt").replaceFirstChar { it.uppercase() } + "Kt"
            return if (packageName == null) className else "$packageName.$className"
        }
        return null
    }

    private fun collectKotlinFiles(directory: Path, destination: MutableList<Path>) {
        for (child in fileSystem.list(directory).sortedBy(Path::name)) {
            if (fileSystem.metadata(child).isDirectory) collectKotlinFiles(child, destination)
            else if (child.name.endsWith(".kt", ignoreCase = true)) destination += child
        }
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

    private fun generatedGradleProperties(): String = """
        # Generated by ktc-to-gradle. Safe to regenerate.
        org.gradle.configuration-cache=true
        org.gradle.caching=true
        org.gradle.jvmargs=-Xmx3g
        kotlin.daemon.jvmargs=-Xmx4g
        kotlin.code.style=official
    """.trimIndent() + "\n"

    private fun wrapperProperties(): String = """
        # Generated by ktc-to-gradle. Safe to regenerate.
        distributionBase=GRADLE_USER_HOME
        distributionPath=wrapper/dists
        distributionUrl=https\://services.gradle.org/distributions/gradle-${Versions.GRADLE}-bin.zip
        distributionSha256Sum=${Versions.GRADLE_SHA256}
        networkTimeout=10000
        validateDistributionUrl=true
        zipStoreBase=GRADLE_USER_HOME
        zipStorePath=wrapper/dists
    """.trimIndent() + "\n"

    private fun unixGradleLauncher(): String = $$"""
        #!/usr/bin/env sh
        # Generated by ktc-to-gradle. This thin wrapper downloads Gradle automatically.
        set -eu
        GRADLE_VERSION="$${Versions.GRADLE}"
        GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
        INSTALL_DIR="$GRADLE_USER_HOME/ktc-to-gradle/gradle-$GRADLE_VERSION"
        ARCHIVE="$GRADLE_USER_HOME/ktc-to-gradle/gradle-$GRADLE_VERSION-bin.zip"
        if [ ! -x "$INSTALL_DIR/bin/gradle" ]; then
          mkdir -p "$(dirname "$ARCHIVE")"
          URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
          if command -v curl >/dev/null 2>&1; then curl -fL --retry 3 -o "$ARCHIVE" "$URL"
          elif command -v wget >/dev/null 2>&1; then wget -O "$ARCHIVE" "$URL"
          else echo "ktc-to-gradle: curl or wget is required" >&2; exit 1; fi
          if command -v sha256sum >/dev/null 2>&1; then
            printf '%s  %s\n' '$${Versions.GRADLE_SHA256}' "$ARCHIVE" | sha256sum -c -
          else
            printf '%s  %s\n' '$${Versions.GRADLE_SHA256}' "$ARCHIVE" | shasum -a 256 -c -
          fi
          TMP="$INSTALL_DIR.tmp.$$"
          rm -rf "$TMP"
          mkdir -p "$TMP"
          unzip -q "$ARCHIVE" -d "$TMP"
          rm -rf "$INSTALL_DIR"
          mv "$TMP/gradle-$GRADLE_VERSION" "$INSTALL_DIR"
          rm -rf "$TMP"
        fi
        exec "$INSTALL_DIR/bin/gradle" "$@"
    """.trimIndent() + "\n"

    private fun windowsGradleLauncher(): String = $$"""
        @echo off
        rem Generated by ktc-to-gradle. This thin wrapper downloads Gradle automatically.
        setlocal
        set "GRADLE_VERSION=$${Versions.GRADLE}"
        if "%GRADLE_USER_HOME%"=="" set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
        set "INSTALL_DIR=%GRADLE_USER_HOME%\ktc-to-gradle\gradle-%GRADLE_VERSION%"
        set "ARCHIVE=%GRADLE_USER_HOME%\ktc-to-gradle\gradle-%GRADLE_VERSION%-bin.zip"
        if not exist "%INSTALL_DIR%\bin\gradle.bat" (
          if not exist "%GRADLE_USER_HOME%\ktc-to-gradle" mkdir "%GRADLE_USER_HOME%\ktc-to-gradle"
          powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ARCHIVE%'; if ((Get-FileHash '%ARCHIVE%' -Algorithm SHA256).Hash.ToLower() -ne '$${Versions.GRADLE_SHA256}') { throw 'Gradle SHA-256 checksum mismatch' }; Expand-Archive -Force '%ARCHIVE%' '%GRADLE_USER_HOME%\ktc-to-gradle'"
          if errorlevel 1 exit /b 1
        )
        call "%INSTALL_DIR%\bin\gradle.bat" %*
    """.trimIndent() + "\r\n"

    private fun header(): String = "// Generated by ktc-to-gradle ${Versions.CONVERTER}. Safe to regenerate."

    private fun quote(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")}\""

    private fun mappingStrings(value: Value?): Map<String, String> =
        (value as? Value.Mapping)?.entries?.mapValues { (_, item) -> item.scalarOrNull().orEmpty() }.orEmpty()

    private data class Dependency(
        val notation: String,
        val scope: String = "all",
        val exported: Boolean = false,
        val bom: Boolean = false,
    )

    private data class SerializationSettings(
        val version: String,
        val format: String? = null,
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
        private const val DEFAULT_SERIALIZATION_VERSION = "1.11.0"
        private const val MAVEN_CENTRAL_URL = "https://repo1.maven.org/maven2"
        private const val GOOGLE_MAVEN_URL = "https://maven.google.com"

        private val serializationArtifacts = mapOf(
            "core" to "kotlinx-serialization-core",
            "cbor" to "kotlinx-serialization-cbor",
            "hocon" to "kotlinx-serialization-hocon",
            "json" to "kotlinx-serialization-json",
            "json-io" to "kotlinx-serialization-json-io",
            "json-okio" to "kotlinx-serialization-json-okio",
            "properties" to "kotlinx-serialization-properties",
            "protobuf" to "kotlinx-serialization-protobuf",
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
