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

/**
 * Stage 2: turns the loaded Toolchain project into the Gradle build it stands for.
 *
 * Every semantic decision of the conversion happens here or in an interpreter this dispatches to,
 * so the render stage below has nothing left to decide. No file system is touched: whatever the
 * conversion needs to know about the tree already reached it as [ToolchainModule] data.
 *
 * The order the modules are visited in is the order the diagnostics come out in, so it is fixed:
 * the project-wide plugin versions first, then the root module, then the subprojects. The dangling
 * references left behind by a skipped module are reported last, because they can only be known once
 * every module has been visited.
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

    /**
     * The root of a project that has no module of its own.
     *
     * It builds nothing, and exists only to hold the plugins its subprojects inherit. A root module
     * the conversion skipped lands here too: the build still needs a root to declare the plugin
     * versions its subprojects inherit.
     */
    private fun rootShell(project: ToolchainProject, inherited: List<PluginDecl>): GradleModule = GradleModule(
        gradlePath = ":",
        directory = project.root,
        plugins = listOf(PluginDecl(GradlePlugin.Builtin.BASE, version = null)) + inherited,
        repositories = emptyList(),
        requiresCredentialsImport = false,
        build = null,
    )

    /**
     * Interprets one module, or returns `null` for one the conversion has to leave out.
     *
     * A skipped module is appended to [skipped] so the caller can report what still points at it.
     */
    private fun interpretModule(
        index: ModuleIndex,
        module: ToolchainModule,
        plugins: List<PluginDecl>,
        diagnostics: DiagnosticCollector,
        skipped: MutableList<ToolchainModule>,
    ): GradleModule? {
        reportUnsupportedKeys(module, diagnostics)
        val product = requireProduct(module)
        // The product is dispatched on before anything else about the module is read, because the
        // refusals below are a documented promise: a user converting an ios/app is told iOS is out
        // of scope, never that some other section of a module the converter was never going to
        // produce is malformed. Picking the interpreter here rather than calling it keeps that
        // refusal ahead of the repositories without moving the repositories behind the build.
        val interpreter: (ModuleIndex, ToolchainModule, DiagnosticCollector) -> ModuleBuild = when (product.type) {
            ProductType.JVM_APP, ProductType.JVM_LIB -> JvmInterpreter::interpret
            ProductType.ANDROID_APP -> AndroidInterpreter::interpret
            in ProductType.MULTIPLATFORM -> MultiplatformInterpreter::interpret
            ProductType.IOS_APP -> throw ConversionException(
                "${module.displayName}: ios/app contains an Xcode/Swift application and cannot be represented by a standalone Gradle module",
            )
            // A build plugin is the one product the rest of the project can be converted without,
            // so it is skipped rather than refused: every other module still gets its files.
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
        // Repositories are read before the build is interpreted for every supported product family,
        // so a module that carries more than one deferred failure always reports the same one. That
        // is a deliberate change: the pre-pipeline generator read the repositories from inside the
        // build it was already assembling, so which of two failures a module reported depended on
        // its product.
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
     * The sections the converter reads and produces nothing from.
     *
     * A key it refuses is already reported, and a key it cannot parse already fails. This is the
     * third case, and the one that used to be invisible: a section that binds, is understood, and
     * reaches no line of the generated build. Silence there is worse than either of the other two,
     * because the conversion looks complete.
     *
     * `settings.publishing` is reported by `Publishing` itself, per key, because most of it is now
     * carried and only the rest has to be named.
     *
     * `ToolchainModel.unknownKeys` is the fourth case and the last silent one: a key nothing in the
     * converter ever asks for. It is reported here rather than beside `plugins:` so the promise
     * `interpretModule` makes still holds — a product the converter refuses is refused before any
     * other section of that module is discussed.
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
     * Whether the module has a Kotlin compilation the test release can be set on.
     *
     * A `jvm/lib` and a `jvm/app` always have one. A multiplatform module has one per JVM-backed
     * target: `jvm()` names its test compilation `test`, and `androidLibrary` names its host-test
     * one `hostTest`. Everything else compiles to something that is not JVM bytecode.
     *
     * An `android/app` is the one JVM-backed product left out. The Android Gradle Plugin builds its
     * unit tests as variants of the application rather than as a Kotlin compilation of their own,
     * and it offers no handle on the compiler of one variant.
     */
    private fun carriesTestRelease(module: ToolchainModule): Boolean = when (module.model.product.type) {
        ProductType.JVM_LIB, ProductType.JVM_APP -> true
        in ProductType.MULTIPLATFORM -> module.model.product.platforms.any { it in JVM_BACKED_PLATFORMS }
        else -> false
    }

    /**
     * The third-party Kotlin compiler plugins the module declares.
     *
     * Read here rather than inside a product interpreter because the declaration is module-wide: the
     * Toolchain applies a compiler plugin to the module, not to one of its targets.
     */
    private fun compilerPluginsOf(module: ToolchainModule): List<CompilerPlugin> =
        module.model.settings.kotlin?.compilerPlugins.orEmpty().map { spec ->
            CompilerPlugin(
                id = spec.id,
                dependency = compilerPluginDependency(module, spec.dependency),
                options = spec.options,
            )
        }

    /**
     * Where a compiler plugin is loaded from.
     *
     * The Toolchain takes an external dependency here, so a catalog alias is as valid as a
     * coordinate — and a local module never is.
     */
    private fun compilerPluginDependency(module: ToolchainModule, notation: String): DependencyTarget = when {
        notation.startsWith("\$libs.") -> Dependencies.catalogTarget(module, notation)
        notation.startsWith("\$") || isLocalNotation(notation) -> throw ConversionException(
            "${module.displayName}: settings.kotlin.compilerPlugins dependency '$notation' must be a Maven " +
                "coordinate or a \$libs catalog alias",
        )
        else -> DependencyTarget.Maven(notation)
    }

    /**
     * Reports the keys the converter has no Gradle equivalent for.
     *
     * `plugins:` and `mavenPlugins:` name build plugins, which the module compiles without: the
     * section is dropped, an error is recorded, and the module is still converted. Everything else
     * is a `settings.` subtree the generated build would silently disagree with, so it still stops
     * the conversion.
     *
     * Runs before the product type is read, so a module that declares `plugins:` and an unsupported
     * product at the same time reports `plugins:` first — the key it can actually do something
     * about — and only then refuses the product.
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

    /**
     * Reports every `project(...)` reference left pointing at a module the conversion skipped.
     *
     * The generated `settings.gradle.kts` cannot include a module that was never rendered, so such a
     * reference would fail the Gradle build. Naming it here keeps the run honest about what the user
     * has to fix by hand.
     *
     * It reads the interpreted build rather than the declared sections, because only the interpreted
     * build says what the module actually emits: a `bom:` entry becomes a reference too, and a
     * qualified section this product never reads becomes nothing at all.
     */
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

    /** Every module a build points at with `project(...)`, in the order it emits them. */
    private fun projectDependenciesOf(build: ModuleBuild): List<String> = when (build) {
        is JvmBuild -> build.dependencies + build.testDependencies
        is AndroidBuild -> build.dependencies + build.testDependencies
        is MultiplatformBuild -> build.sourceSets.flatMap(KmpSourceSet::dependencies)
    }.mapNotNull { dependency -> (dependency.target as? DependencyTarget.Project)?.gradlePath }

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

    /** The rejected keys that name build plugins; the rest are `settings.` paths the run stops on. */
    private val TOP_LEVEL_KEYS = YamlBinder.UNSUPPORTED_KEYS.toSet()

    /** The multiplatform platforms whose target compiles Kotlin to JVM bytecode. */
    private val JVM_BACKED_PLATFORMS = setOf("jvm", "android")
}
