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
import io.heapy.ktctogradle.load.isLocalNotation
import io.heapy.ktctogradle.load.raiseDeferred
import io.heapy.ktctogradle.model.AndroidBuild
import io.heapy.ktctogradle.model.CompilerPlugin
import io.heapy.ktctogradle.model.DependencyTarget
import io.heapy.ktctogradle.model.GradleModule
import io.heapy.ktctogradle.model.GradlePlugin
import io.heapy.ktctogradle.model.GradleProject
import io.heapy.ktctogradle.model.JvmBuild
import io.heapy.ktctogradle.model.KmpSourceSet
import io.heapy.ktctogradle.model.ModuleBuild
import io.heapy.ktctogradle.model.MultiplatformBuild
import io.heapy.ktctogradle.model.PluginDecl

/** Pure interpretation; module order fixes diagnostic order, with dangling references reported last. */
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
        val skipped = mutableListOf<ToolchainModule>()
        val modules = buildList {
            val interpretedRoot = rootModule?.let { module ->
                val plugins = PluginResolution.declarationsFor(module.model, versions, declareVersions = true)
                interpretModule(index, module, plugins + inherited, diagnostics, skipped)
            }
            add(interpretedRoot ?: rootShell(project, inherited))
            for (module in subprojects) {
                val plugins = PluginResolution.declarationsFor(module.model, versions, declareVersions = false)
                interpretModule(index, module, plugins, diagnostics, skipped)?.let(::add)
            }
        }
        reportDanglingDependencies(modules, project.modules, skipped, diagnostics)
        return GradleProject(
            root = project.root,
            name = project.name,
            catalog = project.catalogPath,
            modules = modules,
            pluginRepositories = Repositories.forPlugins(project.root, modules),
        )
    }

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
        skipped: MutableList<ToolchainModule>,
    ): GradleModule? {
        reportUnsupportedKeys(module, diagnostics)
        val product = requireProduct(module)
        // Product refusal precedes malformed sections of a module that will never be generated.
        val interpreter: (ModuleIndex, ToolchainModule, DiagnosticCollector) -> ModuleBuild = when (product.type) {
            ProductType.JVM_APP, ProductType.JVM_LIB -> JvmInterpreter::interpret
            ProductType.ANDROID_APP -> AndroidInterpreter::interpret
            in ProductType.MULTIPLATFORM -> MultiplatformInterpreter::interpret
            ProductType.IOS_APP -> throw ConversionException(
                "${module.displayName}: ios/app contains an Xcode/Swift application and cannot be represented by a standalone Gradle module",
            )
            // Build-plugin modules are skipped so independent modules can still be converted.
            ProductType.JVM_AMPER_PLUGIN -> {
                skipped += module
                diagnostics.error(
                    "${module.displayName}: Kotlin Toolchain build plugins have no automatic Gradle equivalent; " +
                        "the module was left out of the generated build",
                )
                return null
            }
            else -> throw ConversionException("${module.displayName}: unsupported product '${product.type}'")
        }
        // Read repositories first so deferred failures have consistent ordering across products.
        val repositories = Repositories.of(module.model)
        val requiresCredentialsImport = Repositories.requiresCredentialsImport(module.model)
        val build: ModuleBuild = interpreter(index, module, diagnostics)
        reportDroppedSections(module, diagnostics)
        return GradleModule(
            gradlePath = module.gradlePath,
            directory = module.directory,
            plugins = plugins,
            repositories = repositories,
            requiresCredentialsImport = requiresCredentialsImport,
            compilerPlugins = compilerPluginsOf(module),
            publication = Publishing.of(module, diagnostics),
            build = build,
        )
    }

    /**
     * Reports sections that bind but emit nothing, plus keys the binder never consumes. Publishing
     * reports its own partially supported subtree per key.
     */
    private fun reportDroppedSections(module: ToolchainModule, diagnostics: DiagnosticCollector) {
        val release = module.model.settings.test?.release
        if (release != null && !carriesTestRelease(module)) {
            diagnostics.warn(
                "${module.displayName}: test-settings.jvm.release '$release' was dropped; this module " +
                    "has no Kotlin JVM test compilation to carry it",
            )
        }
        for (key in module.model.unknownKeys) {
            diagnostics.warn("${module.displayName}: '$key' is not read by the converter and was dropped")
        }
    }

    /**
     * Android applications are excluded because AGP exposes unit tests as variants, not a Kotlin
     * compilation whose release can be configured independently.
     */
    private fun carriesTestRelease(module: ToolchainModule): Boolean = when (module.model.product.type) {
        ProductType.JVM_LIB, ProductType.JVM_APP -> true
        in ProductType.MULTIPLATFORM -> module.model.product.platforms.any { it in JVM_BACKED_PLATFORMS }
        else -> false
    }

    private fun compilerPluginsOf(module: ToolchainModule): List<CompilerPlugin> =
        module.model.settings.kotlin?.compilerPlugins.orEmpty().map { spec ->
            CompilerPlugin(
                id = spec.id,
                dependency = compilerPluginDependency(module, spec.dependency),
                options = spec.options,
            )
        }

    /** Compiler plugins accept coordinates and catalog aliases, but not local modules. */
    private fun compilerPluginDependency(module: ToolchainModule, notation: String): DependencyTarget = when {
        notation.startsWith("\$libs.") -> Dependencies.catalogTarget(module, notation)
        notation.startsWith("\$") || isLocalNotation(notation) -> throw ConversionException(
            "${module.displayName}: settings.kotlin.compilerPlugins dependency '$notation' must be a Maven " +
                "coordinate or a \$libs catalog alias",
        )
        else -> DependencyTarget.Maven(notation)
    }

    /**
     * Build-plugin sections are dropped with errors so the module can continue; unsupported
     * `settings` subtrees stop conversion. This runs before product dispatch to preserve ordering.
     */
    private fun reportUnsupportedKeys(module: ToolchainModule, diagnostics: DiagnosticCollector) {
        for (rejected in module.model.unsupported) {
            if (rejected in TOP_LEVEL_KEYS) {
                diagnostics.error(
                    "${module.displayName}: '$rejected' cannot be converted automatically; " +
                        "the section was dropped and needs a hand-written Gradle equivalent",
                )
            } else {
                throw ConversionException("${module.displayName}: '$rejected' is not supported yet")
            }
        }
    }

    /** Reports emitted project dependencies that point to skipped modules, including BOM references. */
    private fun reportDanglingDependencies(
        modules: List<GradleModule>,
        declared: List<ToolchainModule>,
        skipped: List<ToolchainModule>,
        diagnostics: DiagnosticCollector,
    ) {
        if (skipped.isEmpty()) return
        val names = declared.associate { it.gradlePath to it.displayName }
        val skippedNames = skipped.associate { it.gradlePath to it.displayName }
        for (module in modules) {
            val build = module.build ?: continue
            for (gradlePath in projectDependenciesOf(build).distinct()) {
                val skippedName = skippedNames[gradlePath] ?: continue
                diagnostics.error(
                    "${names[module.gradlePath]} depends on '$skippedName', " +
                        "which was left out of the generated build",
                )
            }
        }
    }

    private fun projectDependenciesOf(build: ModuleBuild): List<String> = when (build) {
        is JvmBuild -> build.dependencies + build.testDependencies
        is AndroidBuild -> build.dependencies + build.testDependencies
        is MultiplatformBuild -> build.sourceSets.flatMap(KmpSourceSet::dependencies)
    }.mapNotNull { dependency -> (dependency.target as? DependencyTarget.Project)?.gradlePath }

    /** Raises the deferred product failure at its first semantic use. */
    private fun requireProduct(module: ToolchainModule): ProductSpec {
        module.model.raiseDeferred(Region.PRODUCT)
        return module.model.product
    }

    private val TOP_LEVEL_KEYS = YamlBinder.UNSUPPORTED_KEYS.toSet()

    private val JVM_BACKED_PLATFORMS = setOf("jvm", "android")
}
