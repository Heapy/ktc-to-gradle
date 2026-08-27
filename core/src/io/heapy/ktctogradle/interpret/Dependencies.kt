package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.load.ModuleIndex
import io.heapy.ktctogradle.load.RawDependency
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.isLocalNotation
import io.heapy.ktctogradle.model.Dependency
import io.heapy.ktctogradle.model.DependencyTarget
import io.heapy.ktctogradle.model.Scope

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
     * A section the binder could not read raises its deferred message here too. The load stage has
     * already raised it for every declared section, so this is the second gate rather than the only
     * one; it keeps the failure attached to the qualifier that reads it when a build is interpreted
     * without going through [io.heapy.ktctogradle.load.validateLocalDependencies].
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
