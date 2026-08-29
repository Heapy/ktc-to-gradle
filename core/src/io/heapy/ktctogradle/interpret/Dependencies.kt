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
    /** One segment of a Gradle version catalog accessor: a Kotlin identifier that needs no backticks. */
    private val ACCESSOR_SEGMENT = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** The Kotlin hard keywords, which a catalog accessor segment has to be backticked to spell. */
    private val KOTLIN_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    )

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
        notation.startsWith("\$libs.") -> catalogTarget(module, notation)
        notation.startsWith("\$kotlin.") -> kotlinCatalogTarget(module, notation.removePrefix("\$kotlin."))
        notation.startsWith("\$") -> throw ConversionException(
            "${module.displayName}: built-in catalog dependency '$notation' needs technology-specific manual conversion",
        )
        else -> DependencyTarget.Maven(notation)
    }

    /**
     * A `$libs.` reference, spelled as the Gradle accessor it becomes.
     *
     * The key is passed through rather than translated, because the Toolchain already resolves it
     * against the accessor path Gradle generates and not against the raw `libs.versions.toml` alias.
     * Measured on 0.12.0 with `kotlin show modules`: the alias `junit-jupiter-api` is read as
     * `$libs.junit.jupiter.api` and refused as `$libs.junit-jupiter-api`, `my_lib` is read as
     * `$libs.my.lib`, and `ktorClient` is read as `$libs.ktorClient` and refused as
     * `$libs.ktor.client`. The two spellings coincide, so there is nothing to translate.
     *
     * What is checked is that the key can be spelled as Kotlin at all. The renderer emits it as a
     * bare expression, so `$libs.1bad` used to produce `implementation(libs.1bad)` — a build script
     * that does not parse, reported as a successful conversion. Every shape refused here is one the
     * Toolchain refuses too, so this is parity rather than a new rule.
     *
     * Existence is deliberately not checked: the converter locates `libs.versions.toml` but never
     * reads its entries, and an unknown key that is well formed fails at Gradle configuration time
     * with an unresolved reference that already names the accessor and the line.
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
