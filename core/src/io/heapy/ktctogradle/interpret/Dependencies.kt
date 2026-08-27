package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.ModulePath
import io.heapy.ktctogradle.load.RawDependency
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.model.Dependency
import io.heapy.ktctogradle.model.DependencyTarget
import io.heapy.ktctogradle.model.Scope
import okio.Path

/** True for the notations that name another module of the same project rather than a coordinate. */
internal fun isLocalNotation(notation: String): Boolean =
    notation.startsWith("//") || notation.startsWith("./") || notation.startsWith("../")

/**
 * Finds the module a local dependency notation points at.
 *
 * A module is reachable by its Toolchain path and by its directory, and a directory can be reached
 * through a symlink, so the canonical directory the load stage recorded is a third key. The index
 * exists once per project because the same lookup answers both the load stage's validation and the
 * interpret stage's resolution.
 */
internal class ModuleIndex private constructor(
    private val byPath: Map<ModulePath, ToolchainModule>,
    private val byDirectory: Map<Path, ToolchainModule>,
    private val byCanonicalDirectory: Map<Path, ToolchainModule>,
) {
    fun resolve(module: ToolchainModule, notation: String): ToolchainModule? = if (notation.startsWith("//")) {
        byPath[ModulePath.parse(notation)]
    } else {
        // The plain comparison misses when the module directory is reached through a symlink, so
        // fall back to the directories the load stage already canonicalized.
        byDirectory[(module.directory / notation).normalized()]
            ?: byCanonicalDirectory[(module.canonicalDirectory / notation).normalized()]
    }

    companion object {
        fun of(modules: List<ToolchainModule>): ModuleIndex = ModuleIndex(
            byPath = firstWins(modules, ToolchainModule::path),
            byDirectory = firstWins(modules, ToolchainModule::directory),
            byCanonicalDirectory = firstWins(modules, ToolchainModule::canonicalDirectory),
        )

        /** Keeps the earliest module under a key, matching the `firstOrNull` scan it replaces. */
        private fun <K> firstWins(
            modules: List<ToolchainModule>,
            key: (ToolchainModule) -> K,
        ): Map<K, ToolchainModule> = buildMap {
            for (module in modules) {
                val moduleKey = key(module)
                if (moduleKey !in this) put(moduleKey, module)
            }
        }
    }
}

/**
 * Turns declared dependency notations into resolved [Dependency] targets.
 *
 * The notation rules live here only: the load stage validates local references through the same
 * [ModuleIndex] instead of parsing notations a second time with rules of its own.
 */
internal object Dependencies {
    /**
     * The dependencies of one module, reading [qualifiers] in order with `""` standing for the
     * unqualified section.
     *
     * A section the binder could not read raises its deferred message here, at the point that reads
     * it, so a module with a bad `dependencies@js` still converts when nothing asks for that
     * qualifier.
     */
    fun of(
        index: ModuleIndex,
        module: ToolchainModule,
        test: Boolean,
        qualifiers: List<String>,
    ): List<Dependency> {
        val model = module.model
        val sections = if (test) model.testDependencies else model.dependencies
        val prefix = if (test) "test-dependencies" else "dependencies"
        return qualifiers.flatMap { qualifier ->
            val key = if (qualifier.isEmpty()) prefix else "$prefix@$qualifier"
            model.errors[key]?.let { message -> throw ConversionException(message) }
            sections[qualifier].orEmpty()
        }.map { raw -> resolve(index, module, raw) }
    }

    /**
     * Fails when a module names a local dependency no module of the project provides.
     *
     * Reported from the load stage, before anything is generated, so a typo in a module nothing
     * renders is still caught. A section the binder could not read is skipped: its own message is
     * raised by [of] when something consumes it.
     */
    fun validateLocal(modules: List<ToolchainModule>) {
        val index = ModuleIndex.of(modules)
        for (module in modules) {
            val declared = module.model.dependencies.values + module.model.testDependencies.values
            for (raw in declared.flatten()) {
                if (!isLocalNotation(raw.notation)) continue
                if (index.resolve(module, raw.notation) == null) {
                    throw ConversionException("${module.displayName} depends on unknown module '${raw.notation}'")
                }
            }
        }
    }

    private fun resolve(index: ModuleIndex, module: ToolchainModule, raw: RawDependency): Dependency = Dependency(
        target = target(index, module, raw.notation),
        scope = scopeOf(raw.scope),
        exported = raw.exported,
        bom = raw.bom,
    )

    /** An unknown scope keeps the behaviour it has today and lands on the default configuration. */
    private fun scopeOf(scope: String): Scope = when (scope) {
        "compile-only" -> Scope.COMPILE_ONLY
        "runtime-only" -> Scope.RUNTIME_ONLY
        else -> Scope.ALL
    }

    private fun target(index: ModuleIndex, module: ToolchainModule, notation: String): DependencyTarget = when {
        isLocalNotation(notation) -> {
            val target = index.resolve(module, notation)
                ?: throw ConversionException("${module.displayName}: unknown module '$notation'")
            DependencyTarget.Project(target.gradlePath)
        }
        notation.startsWith("\$libs.") -> DependencyTarget.Catalog(notation.removePrefix("\$"))
        notation.startsWith("\$kotlin.") -> kotlinCatalogTarget(module, notation.removePrefix("\$kotlin."))
        notation.startsWith("\$") -> throw ConversionException(
            "${module.displayName}: built-in catalog dependency '$notation' needs technology-specific manual conversion",
        )
        else -> DependencyTarget.Maven(notation)
    }

    private fun kotlinCatalogTarget(module: ToolchainModule, key: String): DependencyTarget = when (key) {
        "reflect" -> DependencyTarget.KotlinBuiltin("reflect")
        "test", "test.common" -> DependencyTarget.KotlinBuiltin("test")
        "test.junit", "test.junit5" -> DependencyTarget.KotlinBuiltin("test-junit5")
        else -> {
            val serializationKey = key.removePrefix("serialization.")
            if (serializationKey == key) {
                throw ConversionException("${module.displayName}: unsupported Kotlin catalog alias '\$kotlin.$key'")
            }
            val serialization = Serialization.settings(module.model)
                ?: throw ConversionException(
                    "${module.displayName}: '\$kotlin.$key' requires settings.kotlin.serialization to be enabled",
                )
            DependencyTarget.Maven(Serialization.coordinate(serializationKey, serialization.version))
        }
    }
}
