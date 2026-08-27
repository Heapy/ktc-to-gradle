package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.DiagnosticCollector
import io.heapy.ktctogradle.ToolchainModule
import io.heapy.ktctogradle.ToolchainProject
import io.heapy.ktctogradle.load.value
import io.heapy.ktctogradle.model.GradleModule
import io.heapy.ktctogradle.model.GradlePlugin
import io.heapy.ktctogradle.model.GradleProject
import io.heapy.ktctogradle.model.ModuleBuild
import io.heapy.ktctogradle.model.PluginDecl
import io.heapy.ktctogradle.product

/**
 * Stage 2: turns the loaded Toolchain project into the Gradle build it stands for.
 *
 * Every semantic decision of the conversion happens here or in an interpreter this dispatches to,
 * so the render stage below has nothing left to decide. No file system is touched: whatever the
 * conversion needs to know about the tree already reached it as [ToolchainModule] data.
 *
 * The order the modules are visited in is the order the diagnostics come out in, so it is fixed:
 * the project-wide plugin versions first, then the root module, then the subprojects.
 */
internal object ProjectInterpreter {
    fun interpret(project: ToolchainProject, diagnostics: DiagnosticCollector): GradleProject {
        val rootModule = project.modules.firstOrNull { it.path.isRoot }
        val subprojects = project.modules.filterNot { it.path.isRoot }
        val index = ModuleIndex.of(project.modules)
        val versions = PluginResolution.resolveVersions(project.modules.map(ToolchainModule::model), diagnostics)
        val inherited = PluginResolution.inheritedDeclarations(
            root = rootModule?.model,
            subprojects = subprojects.map(ToolchainModule::model),
            versions = versions,
        )
        val modules = buildList {
            add(
                rootModule?.let { module ->
                    val plugins = PluginResolution.declarationsFor(module.model, versions, declareVersions = true)
                    interpretModule(index, module, plugins + inherited, diagnostics)
                } ?: rootShell(project, inherited),
            )
            for (module in subprojects) {
                val plugins = PluginResolution.declarationsFor(module.model, versions, declareVersions = false)
                add(interpretModule(index, module, plugins, diagnostics))
            }
        }
        return GradleProject(
            root = project.root,
            name = project.name,
            catalog = project.catalogPath,
            modules = modules,
        )
    }

    /**
     * The root of a project that has no module of its own.
     *
     * It builds nothing, and exists only to hold the plugins its subprojects inherit.
     */
    private fun rootShell(project: ToolchainProject, inherited: List<PluginDecl>): GradleModule = GradleModule(
        gradlePath = ":",
        directory = project.root,
        plugins = listOf(PluginDecl(GradlePlugin.Builtin.BASE, version = null)) + inherited,
        repositories = emptyList(),
        requiresCredentialsImport = false,
        build = null,
    )

    private fun interpretModule(
        index: ModuleIndex,
        module: ToolchainModule,
        plugins: List<PluginDecl>,
        diagnostics: DiagnosticCollector,
    ): GradleModule {
        rejectUnsupported(module)
        val product = product(module.config)
        // Repositories are read before the build is interpreted for every product family, so a module
        // that carries more than one deferred failure always reports the same one.
        val repositories = Repositories.resolution(module.model)
        val requiresCredentialsImport = Repositories.requiresCredentialsImport(module.model)
        val build: ModuleBuild = when (product.type) {
            "jvm/app", "jvm/lib" -> JvmInterpreter.interpret(index, module, diagnostics)
            "android/app" -> AndroidInterpreter.interpret(index, module, diagnostics)
            "kmp/lib", "js/app", "wasm-js/app", "wasm-wasi/app",
            "linux/app", "macos/app", "windows/app",
            -> MultiplatformInterpreter.interpret(index, module, diagnostics)
            "ios/app" -> throw ConversionException(
                "${module.displayName}: ios/app contains an Xcode/Swift application and cannot be represented by a standalone Gradle module",
            )
            "jvm/amper-plugin" -> throw ConversionException(
                "${module.displayName}: Kotlin Toolchain build plugins have no automatic Gradle equivalent",
            )
            else -> throw ConversionException("${module.displayName}: unsupported product '${product.type}'")
        }
        return GradleModule(
            gradlePath = module.gradlePath,
            directory = module.directory,
            plugins = plugins,
            repositories = repositories,
            requiresCredentialsImport = requiresCredentialsImport,
            build = build,
        )
    }

    /**
     * Refuses the keys the converter has no Gradle equivalent for.
     *
     * Runs before the product type is read, so a module that declares `plugins:` and an unsupported
     * product at the same time is told about `plugins:` — the key it can actually do something about.
     */
    private fun rejectUnsupported(module: ToolchainModule) {
        for (key in UNSUPPORTED_KEYS) if (module.config.value(key) != null) {
            throw ConversionException("${module.displayName}: '$key' cannot be converted automatically")
        }
        for (path in UNSUPPORTED_SETTINGS) if (module.config.value(path) != null) {
            throw ConversionException("${module.displayName}: '$path' is not supported yet")
        }
    }

    private val UNSUPPORTED_KEYS = listOf("plugins", "mavenPlugins")

    private val UNSUPPORTED_SETTINGS = listOf(
        "settings.compose", "settings.springBoot", "settings.lombok", "settings.kotlin.ksp",
        "settings.kotlin.rpc", "settings.kotlin.dataframe", "settings.kotlin.compilerPlugins",
    )
}
