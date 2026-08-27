package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.DiagnosticCollector
import io.heapy.ktctogradle.load.ModuleIndex
import io.heapy.ktctogradle.load.ProductSpec
import io.heapy.ktctogradle.load.ProductType
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.ToolchainProject
import io.heapy.ktctogradle.load.YamlBinder
import io.heapy.ktctogradle.load.raiseDeferred
import io.heapy.ktctogradle.model.GradleModule
import io.heapy.ktctogradle.model.GradlePlugin
import io.heapy.ktctogradle.model.GradleProject
import io.heapy.ktctogradle.model.ModuleBuild
import io.heapy.ktctogradle.model.PluginDecl

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
        val product = requireProduct(module)
        // Repositories are read before the build is interpreted for every product family, so a module
        // that carries more than one deferred failure always reports the same one. That is a
        // deliberate change: the pre-pipeline generator read the repositories from inside the build
        // it was already assembling, so which of two failures a module reported depended on its
        // product.
        val repositories = Repositories.of(module.model)
        val requiresCredentialsImport = Repositories.requiresCredentialsImport(module.model)
        val build: ModuleBuild = when (product.type) {
            ProductType.JVM_APP, ProductType.JVM_LIB -> JvmInterpreter.interpret(index, module, diagnostics)
            ProductType.ANDROID_APP -> AndroidInterpreter.interpret(index, module, diagnostics)
            in ProductType.MULTIPLATFORM -> MultiplatformInterpreter.interpret(index, module, diagnostics)
            ProductType.IOS_APP -> throw ConversionException(
                "${module.displayName}: ios/app contains an Xcode/Swift application and cannot be represented by a standalone Gradle module",
            )
            ProductType.JVM_AMPER_PLUGIN -> throw ConversionException(
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
        val rejected = module.model.unsupported.firstOrNull() ?: return
        val reason = if (rejected in TOP_LEVEL_KEYS) "cannot be converted automatically" else "is not supported yet"
        throw ConversionException("${module.displayName}: '$rejected' $reason")
    }

    /**
     * Reads the product the module declares, raising the failure the binder deferred for it first.
     *
     * The binder never throws, so a module whose `product:` is missing or malformed binds to an
     * empty product and carries the message instead. It surfaces here, which is where the product
     * is first actually needed.
     */
    private fun requireProduct(module: ToolchainModule): ProductSpec {
        module.model.raiseDeferred(Region.PRODUCT)
        return module.model.product
    }

    /** The rejected keys that are not `settings.` paths; those are phrased differently. */
    private val TOP_LEVEL_KEYS = YamlBinder.UNSUPPORTED_KEYS.toSet()
}
