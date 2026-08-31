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

internal object Dependencies {
    private val ACCESSOR_SEGMENT = Regex("[A-Za-z_][A-Za-z0-9_]*")

    private val KOTLIN_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    /**
     * Reads [qualifiers] in order (`""` is unqualified). Shape failures are validated during load;
     * content failures surface only when a product actually reads the section.
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
        notation.startsWith("\$libs.") -> catalogTarget(module, notation)
        notation.startsWith("\$kotlin.") -> kotlinCatalogTarget(module, notation.removePrefix("\$kotlin."))
        notation.startsWith("\$") -> throw ConversionException(
            "${module.displayName}: built-in catalog dependency '$notation' needs technology-specific manual conversion",
        )
        else -> DependencyTarget.Maven(notation)
    }

    /**
     * Preserves Kotlin Toolchain's resolved accessor spelling and rejects shapes that are invalid
     * Kotlin expressions. Existence is left to Gradle because the converter does not read catalog
     * entries.
     */
    internal fun catalogTarget(module: ToolchainModule, notation: String): DependencyTarget {
        val segments = notation.removePrefix("\$libs.").split(".")
        if (segments.any { !ACCESSOR_SEGMENT.matches(it) }) {
            throw ConversionException(
                "${module.displayName}: catalog dependency '$notation' is not a version catalog accessor; " +
                    "'\$libs.' takes the dot-separated accessor Gradle generates for the alias, so a " +
                    "'ktor-client-core' alias is written '\$libs.ktor.client.core'",
            )
        }
        // A dashed alias such as `aws-object-store` nests into a segment that is a Kotlin keyword,
        // and the Toolchain accepts it. Backticks are what makes `libs.aws.`object`.store` parse.
        return DependencyTarget.Catalog(
            segments.joinToString(separator = ".", prefix = "libs.") { segment ->
                if (segment in KOTLIN_KEYWORDS) "`$segment`" else segment
            },
        )
    }

    /** Resolves only aliases defined by Kotlin Toolchain's built-in `$kotlin.` catalog. */
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
