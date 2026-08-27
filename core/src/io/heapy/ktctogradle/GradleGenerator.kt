package io.heapy.ktctogradle

import io.heapy.ktctogradle.interpret.AndroidInterpreter
import io.heapy.ktctogradle.interpret.JvmInterpreter
import io.heapy.ktctogradle.interpret.ModuleIndex
import io.heapy.ktctogradle.interpret.MultiplatformInterpreter
import io.heapy.ktctogradle.interpret.PluginResolution
import io.heapy.ktctogradle.interpret.QualifiedSettings
import io.heapy.ktctogradle.interpret.Repositories
import io.heapy.ktctogradle.load.value
import io.heapy.ktctogradle.model.GeneratedFile
import io.heapy.ktctogradle.model.GradlePlugin
import io.heapy.ktctogradle.model.PluginDecl
import io.heapy.ktctogradle.render.KtsWriter
import io.heapy.ktctogradle.render.StaticAssets
import io.heapy.ktctogradle.render.appendPluginBlock
import io.heapy.ktctogradle.render.quote
import io.heapy.ktctogradle.render.renderAndroidModule
import io.heapy.ktctogradle.render.renderJvmModule
import io.heapy.ktctogradle.render.renderMultiplatformModule

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
            "linux/app", "macos/app", "windows/app" -> renderMultiplatform(index, module, plugins, diagnostics)
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
        val qualifiedOptions = QualifiedSettings.singlePlatform(module, "jvm", diagnostics)
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
            qualifiedCompilerOptions = QualifiedSettings.singlePlatform(module, "android", diagnostics),
        )
    }

    private fun renderMultiplatform(
        index: ModuleIndex,
        module: ToolchainModule,
        plugins: List<PluginDecl>,
        diagnostics: DiagnosticCollector,
    ): String {
        val credentialsImport = Repositories.requiresCredentialsImport(module.model)
        val repositories = Repositories.resolution(module.model)
        return renderMultiplatformModule(
            plugins = plugins,
            repositories = repositories,
            credentialsImport = credentialsImport,
            build = MultiplatformInterpreter.interpret(index, module, diagnostics),
        )
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

    companion object {
        @Suppress("unused")
        private val qualifiedKotlinOptionKeys = setOf(
            "languageVersion", "apiVersion", "allWarningsAsErrors", "progressiveMode", "freeCompilerArgs", "optIns",
        )
    }
}
