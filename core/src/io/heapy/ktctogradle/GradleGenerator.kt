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
        val rootModule = project.modules.firstOrNull { it.path.isEmpty() }
        files += GeneratedFile(
            project.root / "build.gradle.kts",
            rootModule?.let { renderModule(project, it) } ?: rootBuildFile(),
        )
        for (module in project.modules.filterNot { it.path.isEmpty() }) {
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
        for (module in project.modules.filterNot { it.path.isEmpty() }) {
            appendLine()
            appendLine("include(${quote(module.gradlePath)})")
            appendLine("project(${quote(module.gradlePath)}).projectDir = file(${quote(module.path)})")
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
        val serialization = serializationFormat(config)
        val dependencies = dependenciesFor(module, listOf("dependencies", "dependencies@jvm"))
        val tests = dependenciesFor(module, listOf("test-dependencies", "test-dependencies@jvm"))
        val mainClass = config.string("settings.jvm.mainClass") ?: detectMainClass(module)
        return buildString {
            appendLine(header())
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
            appendSerializationDependency(serialization, kotlinVersion, "    ")
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
        val kotlinVersion = config.string("settings.kotlin.version") ?: Versions.KOTLIN
        val serialization = serializationFormat(config)
        val namespace = config.string("settings.android.namespace") ?: "org.example.namespace"
        val compileSdk = config.string("settings.android.compileSdk") ?: config.string("settings.android.compileSdk.apiLevel") ?: "37"
        val minSdk = config.string("settings.android.minSdk") ?: "24"
        val targetSdk = config.string("settings.android.targetSdk") ?: compileSdk
        return buildString {
            appendLine(header())
            appendLine("plugins {")
            appendLine("    id(\"com.android.application\") version ${quote(Versions.ANDROID_GRADLE_PLUGIN)}")
            appendLine("    kotlin(\"android\") version ${quote(kotlinVersion)}")
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
            appendLine("    sourceSets.named(\"main\") {")
            appendLine("        java.srcDirs(\"src\", \"src@android\")")
            appendLine("        resources.srcDirs(\"resources\", \"resources@android\")")
            appendLine("        manifest.srcFile(\"src/AndroidManifest.xml\")")
            appendLine("    }")
            appendLine("    sourceSets.named(\"test\") {")
            appendLine("        java.srcDirs(\"test\", \"test@android\")")
            appendLine("        resources.srcDirs(\"testResources\", \"testResources@android\")")
            appendLine("    }")
            appendLine("}")
            appendLine()
            appendLine("kotlin {")
            appendCompilerOptions(config, "    ", jvmTarget = config.string("settings.jvm.release") ?: "17")
            appendLine("}")
            appendLine()
            appendLine("dependencies {")
            appendDependencies(project, module, dependenciesFor(module, listOf("dependencies", "dependencies@android")), false, "    ")
            appendSerializationDependency(serialization, kotlinVersion, "    ")
            appendBuiltInDependencies(config, "    ")
            appendLine("    testImplementation(kotlin(${quote(testLibrary(config))}))")
            appendDependencies(project, module, dependenciesFor(module, listOf("test-dependencies", "test-dependencies@android")), true, "    ")
            appendLine("}")
        }
    }

    private fun renderMultiplatformModule(project: ToolchainProject, module: ToolchainModule, product: Product): String {
        val config = module.config
        val kotlinVersion = config.string("settings.kotlin.version") ?: Versions.KOTLIN
        val serialization = serializationFormat(config)
        return buildString {
            appendLine(header())
            appendLine("plugins {")
            appendLine("    kotlin(\"multiplatform\") version ${quote(kotlinVersion)}")
            if (serialization != null) appendLine("    kotlin(\"plugin.serialization\") version ${quote(kotlinVersion)}")
            appendLine("}")
            appendLine()
            appendRepositories(config)
            appendLine()
            appendLine("kotlin {")
            for (platform in product.platforms) appendTarget(platform, product.type, config)
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
            appendSerializationDependency(serialization, kotlinVersion, "                ")
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
            for (platform in product.platforms) {
                appendQualifiedSourceSet(project, module, platform, false)
                appendQualifiedSourceSet(project, module, platform, true)
            }
            appendLine("    }")
            appendLine("}")
        }
    }

    private fun StringBuilder.appendTarget(platform: String, productType: String, config: Value.Mapping) {
        val executable = productType.endsWith("/app")
        when (platform) {
            "jvm" -> appendLine("    jvm()")
            "android" -> throw ConversionException("Android targets inside kmp/lib are not supported yet; convert that module manually")
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

    private fun StringBuilder.appendQualifiedSourceSet(
        project: ToolchainProject,
        module: ToolchainModule,
        platform: String,
        test: Boolean,
    ) {
        val prefix = if (test) "test" else "src"
        val resources = if (test) "testResources" else "resources"
        val dependencyKey = if (test) "test-dependencies@$platform" else "dependencies@$platform"
        val deps = dependenciesFor(module, listOf(dependencyKey))
        val sourceExists = fileSystem.exists(module.directory / "$prefix@$platform")
        val resourcesExist = fileSystem.exists(module.directory / "$resources@$platform")
        if (!sourceExists && !resourcesExist && deps.isEmpty()) return
        val sourceSet = "$platform${if (test) "Test" else "Main"}"
        appendLine("        named(${quote(sourceSet)}) {")
        if (sourceExists) appendLine("            kotlin.srcDir(${quote("$prefix@$platform")})")
        if (resourcesExist) appendLine("            resources.srcDir(${quote("$resources@$platform")})")
        if (deps.isNotEmpty()) {
            appendLine("            dependencies {")
            appendDependencies(project, module, deps, test, "                ", sourceSet = true)
            appendLine("            }")
        }
        appendLine("        }")
    }

    private fun StringBuilder.appendRepositories(config: Value.Mapping) {
        appendLine("repositories {")
        appendLine("    mavenCentral()")
        appendLine("    google()")
        val repositories = config.value("repositories").asSequence("repositories")
        for (repository in repositories) {
            val url = when (repository) {
                is Value.Scalar -> repository.text
                is Value.Mapping -> repository.string("url")
                else -> null
            } ?: continue
            if (url == "mavenLocal") appendLine("    mavenLocal()")
            else if (url !in defaultRepositoryUrls) appendLine("    maven(${quote(url)})")
        }
        appendLine("}")
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
        val args = config.strings("test-settings.jvm.freeJvmArgs") + config.strings("settings.jvm.test.freeJvmArgs")
        if (args.isNotEmpty()) appendLine("${indent}jvmArgs(${args.joinToString(transform = ::quote)})")
        for ((key, value) in mappingStrings(config.value("settings.jvm.test.systemProperties"))) {
            appendLine("${indent}systemProperty(${quote(key)}, ${quote(value)})")
        }
        for ((key, value) in mappingStrings(config.value("settings.jvm.test.extraEnvironment"))) {
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
                val path = notation.removePrefix("//").trimEnd('/')
                val target = project.modules.firstOrNull { it.path == path }
                    ?: throw ConversionException("${module.displayName}: unknown module '$notation'")
                "project(${quote(target.gradlePath)})"
            }
            notation.startsWith("./") || notation.startsWith("../") -> {
                val targetDir = (module.directory / notation).toString()
                val target = project.modules.firstOrNull { it.directory.toString() == targetDir }
                    ?: project.modules.firstOrNull { fileSystem.canonicalize(it.directory) == fileSystem.canonicalize(module.directory / notation) }
                    ?: throw ConversionException("${module.displayName}: unknown module '$notation'")
                "project(${quote(target.gradlePath)})"
            }
            notation.startsWith("\$libs.") -> notation.removePrefix("\$")
            notation.startsWith("\$kotlin.") -> kotlinCatalogExpression(notation.removePrefix("\$kotlin."))
            notation.startsWith("\$") -> throw ConversionException(
                "${module.displayName}: built-in catalog dependency '$notation' needs technology-specific manual conversion",
            )
            else -> quote(notation)
        }
        return if (dependency.bom) "platform($expression)" else expression
    }

    private fun kotlinCatalogExpression(key: String): String = when (key) {
        "reflect" -> "kotlin(\"reflect\")"
        "test", "test.common" -> "kotlin(\"test\")"
        "test.junit", "test.junit5" -> "kotlin(\"test-junit5\")"
        else -> quote("org.jetbrains.kotlin:kotlin-${key.replace('.', '-')}:${Versions.KOTLIN}")
    }

    private fun StringBuilder.appendSerializationDependency(format: String?, kotlinVersion: String, indent: String) {
        if (format == null) return
        val artifact = when (format) {
            "json", "json-io", "json-okio" -> "kotlinx-serialization-json"
            "protobuf" -> "kotlinx-serialization-protobuf"
            "cbor" -> "kotlinx-serialization-cbor"
            "properties" -> "kotlinx-serialization-properties"
            "hocon" -> "kotlinx-serialization-hocon"
            else -> throw ConversionException("Unknown Kotlin serialization format '$format'")
        }
        appendLine("${indent}implementation(\"org.jetbrains.kotlinx:$artifact:${serializationRuntimeVersion(kotlinVersion)}\")")
    }

    private fun StringBuilder.appendBuiltInDependencies(config: Value.Mapping, indent: String) {
        if (config.boolean("settings.ktor") == true || config.boolean("settings.ktor.enabled") == true) {
            val version = config.string("settings.ktor.version") ?: "3.5.2"
            appendLine("${indent}implementation(platform(\"io.ktor:ktor-bom:$version\"))")
        }
    }

    private fun serializationRuntimeVersion(kotlinVersion: String): String =
        if (kotlinVersion.substringBeforeLast('.') >= "2.4") "1.11.0" else "1.9.0"

    private fun serializationFormat(config: Value.Mapping): String? {
        val node = config.value("settings.kotlin.serialization") ?: return null
        return when (node) {
            is Value.Scalar -> if (node.text in setOf("disabled", "false")) null else node.text.let { if (it == "enabled") "json" else it }
            is Value.Mapping -> if (node.boolean("enabled") == false) null else node.string("format") ?: "json"
            else -> null
        }
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
            val detailMap = details as? Value.Mapping
            Dependency(
                notation = key,
                scope = detailMap?.string("scope") ?: "all",
                exported = detailMap?.boolean("exported") ?: false,
            )
        }
        else -> throw ConversionException("Dependencies must be strings or objects")
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
        for (child in fileSystem.list(directory)) {
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
        kotlin.code.style=official
    """.trimIndent() + "\n"

    private fun wrapperProperties(): String = """
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
        setlocal
        set "GRADLE_VERSION=$${Versions.GRADLE}"
        if "%GRADLE_USER_HOME%"=="" set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
        set "INSTALL_DIR=%GRADLE_USER_HOME%\ktc-to-gradle\gradle-%GRADLE_VERSION%"
        set "ARCHIVE=%GRADLE_USER_HOME%\ktc-to-gradle\gradle-%GRADLE_VERSION%-bin.zip"
        if not exist "%INSTALL_DIR%\bin\gradle.bat" (
          if not exist "%GRADLE_USER_HOME%\ktc-to-gradle" mkdir "%GRADLE_USER_HOME%\ktc-to-gradle"
          powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ARCHIVE%'; if ((Get-FileHash '%ARCHIVE%' -Algorithm SHA256).Hash.ToLower() -ne '$${Versions.GRADLE_SHA256}') { throw 'Gradle SHA-256 checksum mismatch' }; Expand-Archive -Force '%ARCHIVE%' '%GRADLE_USER_HOME%\ktc-to-gradle'"
          if errorlevel 1 exit /b %errorlevel%
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

    companion object {
        private val nativeTargets = setOf(
            "linuxX64", "linuxArm64", "macosX64", "macosArm64", "mingwX64",
            "iosX64", "iosArm64", "iosSimulatorArm64", "watchosArm64", "watchosSimulatorArm64",
            "tvosArm64", "tvosSimulatorArm64", "androidNativeArm32", "androidNativeArm64",
            "androidNativeX86", "androidNativeX64",
        )
        private val defaultRepositoryUrls = setOf(
            "https://repo1.maven.org/maven2", "https://maven.google.com", "mavenCentral", "mavenGoogle",
        )
    }
}
