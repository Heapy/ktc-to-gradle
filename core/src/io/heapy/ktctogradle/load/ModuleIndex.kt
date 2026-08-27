package io.heapy.ktctogradle.load

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.ModulePath
import okio.Path

/** True for the notations that name another module of the same project rather than a coordinate. */
internal fun isLocalNotation(notation: String): Boolean =
    notation.startsWith("//") || notation.startsWith("./") || notation.startsWith("../")

/**
 * Finds the module a local dependency notation points at.
 *
 * A module is reachable by its Toolchain path and by its directory. Directories are compared as
 * written, with no canonicalization: [ProjectLoader] canonicalizes the path the conversion starts
 * from, and okio does not follow symlinks while listing, so every directory the load stage records
 * is already a real one. A symlink that appears inside the notation itself is not resolved, and a
 * notation that does not land on a declared module directory is an unknown module.
 *
 * The index lives in the load stage because the same lookup answers both this stage's validation
 * and the interpret stage's resolution, and the load stage may not depend on the interpret stage.
 */
internal class ModuleIndex private constructor(
    private val byPath: Map<ModulePath, ToolchainModule>,
    private val byDirectory: Map<Path, ToolchainModule>,
) {
    fun resolve(module: ToolchainModule, notation: String): ToolchainModule? = if (notation.startsWith("//")) {
        byPath[ModulePath.parse(notation)]
    } else {
        byDirectory[(module.directory / notation).normalized()]
    }

    companion object {
        fun of(modules: List<ToolchainModule>): ModuleIndex = ModuleIndex(
            byPath = firstWins(modules, ToolchainModule::path),
            byDirectory = firstWins(modules, ToolchainModule::directory),
        )

        /** Keeps the earliest module under a key: two modules may claim one, and the first declared wins. */
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
 * Validates every dependency section of every module, before anything is generated.
 *
 * Two things are checked: a section whose *shape* the binder could not read raises its deferred
 * message here rather than waiting for a consumer that may never come, and a local notation that
 * names no module of the project is reported as unknown. Both run for every declared section, so a
 * typo under a qualifier this product never reads — or in a module the render stage never reaches —
 * still fails the conversion.
 *
 * What this stage deliberately does *not* check is anything only a reader of a section decides: an
 * unknown scope shorthand and a malformed `bom` coordinate belong to
 * [io.heapy.ktctogradle.interpret.Dependencies], and a `bom` names no module here at all, so an
 * unknown module under a `bom:` is reported by that reader too. A section no product reads must not
 * be able to fail a conversion over either.
 */
internal fun validateLocalDependencies(modules: List<ToolchainModule>) {
    val index = ModuleIndex.of(modules)
    for (module in modules) {
        raiseDependencySectionFailures(module)
        val declared = module.model.dependencies.values + module.model.testDependencies.values
        for (raw in declared.flatten()) {
            if (raw.bom || !isLocalNotation(raw.notation)) continue
            if (index.resolve(module, raw.notation) == null) {
                throw ConversionException("${module.displayName} depends on unknown module '${raw.notation}'")
            }
        }
    }
}

private fun raiseDependencySectionFailures(module: ToolchainModule) {
    for ((key, message) in module.model.errors) {
        if (isDependencySection(key)) throw ConversionException(message)
    }
}

/**
 * Every key that names a dependency section, including oddballs such as `dependencies-dev` that no
 * product reads but that are still checked. [Region.dependencyContent] keys are excluded by being
 * prefixed rather than suffixed.
 */
private fun isDependencySection(key: String): Boolean = DEPENDENCY_PREFIXES.any(key::startsWith)

private val DEPENDENCY_PREFIXES = listOf("dependencies", "test-dependencies")
