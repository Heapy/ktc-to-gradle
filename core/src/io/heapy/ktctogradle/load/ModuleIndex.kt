package io.heapy.ktctogradle.load

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.ModulePath
import okio.Path

internal fun isLocalNotation(notation: String): Boolean =
    notation.startsWith("//") || notation.startsWith("./") || notation.startsWith("../")

/**
 * Resolves modules by Toolchain path or loaded directory. Directory lookup is lexical: the loader
 * canonicalizes discovered modules, but symlinks written inside dependency notations are not followed.
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
 * Validates every declared dependency section's shape and local module references. Scope and BOM
 * content remain deferred to products that actually consume the section.
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

private fun isDependencySection(key: String): Boolean = DEPENDENCY_PREFIXES.any(key::startsWith)

private val DEPENDENCY_PREFIXES = listOf("dependencies", "test-dependencies")
