package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.load.ModuleIndex
import io.heapy.ktctogradle.load.RawDependency
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.isLocalNotation
import io.heapy.ktctogradle.load.raiseDeferred
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
     * A section the binder could not read raises its deferred message here. The load stage has
     * already raised the *shape* failures for every declared section, so for those this is the
     * second gate rather than the only one; an unknown scope shorthand and a malformed `bom`
     * coordinate are only ever raised here, which is why declaring one under a qualifier this
     * product never reads has never failed a conversion.
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
            model.raiseDeferred(key)
            model.raiseDeferred(Region.dependencyContent(key))
            sections[qualifier].orEmpty()
        }.map { raw -> resolve(index, module, raw) }
    }

    private fun resolve(index: ModuleIndex, module: ToolchainModule, raw: RawDependency): Dependency = Dependency(
        target = target(index, module, raw.notation),
        scope = scopeOf(raw.scope),
        exported = raw.exported,
        bom = raw.bom,
    )

    /** Anything but a narrowing scope lands on the default configuration. */
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

    /**
     * One entry of the Toolchain's built-in `$kotlin.` catalog.
     *
     * The three test aliases are the ones the catalog defines, and each names a different artifact:
     * `test.junit` is the JUnit 4 adapter and `test.junit5` the JUnit 5 one. Nothing else is
     * accepted, because an alias the Toolchain rejects must not convert to a working build that
     * pulls the wrong test framework in.
     */
    private fun kotlinCatalogTarget(module: ToolchainModule, key: String): DependencyTarget = when (key) {
        "reflect" -> DependencyTarget.KotlinBuiltin("reflect")
        "test" -> DependencyTarget.KotlinBuiltin("test")
        "test.junit" -> DependencyTarget.KotlinBuiltin("test-junit")
        "test.junit5" -> DependencyTarget.KotlinBuiltin("test-junit5")
        else -> {
            val serializationKey = key.removePrefix("serialization.")
            if (serializationKey == key) {
                throw ConversionException("${module.displayName}: unsupported Kotlin catalog alias '\$kotlin.$key'")
            }
            val serialization = Serialization.of(module.model)
                ?: throw ConversionException(
                    "${module.displayName}: '\$kotlin.$key' requires settings.kotlin.serialization to be enabled",
                )
            DependencyTarget.Maven(Serialization.coordinate(serializationKey, serialization.version))
        }
    }
}
