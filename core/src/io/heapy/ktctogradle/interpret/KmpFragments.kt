package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.ToolchainModel
import io.heapy.ktctogradle.load.raiseDeferred

/** A source-set fragment before it is split into main and test source sets. */
internal data class KmpFragment(
    val name: String,
    val platforms: Set<String>,
    val natural: Boolean,
    val parents: List<String> = emptyList(),
)

/** Combines Kotlin's default platform hierarchy with module-defined aliases. */
internal object KmpFragments {
    /**
     * Returns a stable topological order with parents before children and same-level ties sorted by
     * name. `QualifiedSettings` also uses this order as precedence, so narrower sections win; an
     * incomparable overlap is settled by depth and then name where its contributions still combine,
     * and refused by [settingsLeaves] where they disagree on one value.
     */
    fun of(model: ToolchainModel, displayName: String): List<KmpFragment> {
        model.raiseDeferred(Region.ALIASES)
        val declaredPlatforms = model.product.platforms.toSet()
        val aliases = model.aliases
        for ((alias, platforms) in aliases) {
            val unknown = platforms - declaredPlatforms
            if (unknown.isNotEmpty()) {
                throw ConversionException(
                    "$displayName: alias '$alias' contains undeclared platforms ${unknown.sorted().joinToString()}",
                )
            }
            if (alias == COMMON || alias in NATURAL_PLATFORM_PARENTS) {
                throw ConversionException("$displayName: alias '$alias' conflicts with the default platform hierarchy")
            }
        }

        val naturalNames = buildSet {
            add(COMMON)
            for (platform in declaredPlatforms) {
                var current: String? = platform
                while (current != null) {
                    add(current)
                    current = NATURAL_PLATFORM_PARENTS[current]
                }
            }
        }
        val fragments = buildList {
            for (name in naturalNames) {
                add(
                    KmpFragment(
                        name = name,
                        platforms = declaredPlatforms.filterTo(linkedSetOf()) { leaf ->
                            leaf == name || isNaturalAncestor(name, leaf)
                        },
                        natural = true,
                    ),
                )
            }
            for ((name, platforms) in aliases) {
                add(KmpFragment(name, platforms, natural = false))
            }
        }
        val withParents = fragments.map { fragment ->
            val candidates = fragments.filter { candidate -> candidate !== fragment && isBroader(candidate, fragment) }
            val directParents = candidates.filter { candidate ->
                candidates.none { other -> other !== candidate && isBroader(candidate, other) }
            }.map(KmpFragment::name)
            fragment.copy(parents = directParents)
        }

        val ordered = mutableListOf<KmpFragment>()
        val remaining = withParents.toMutableList()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { fragment -> fragment.parents.all { parent -> ordered.any { it.name == parent } } }
            if (ready.isEmpty()) throw ConversionException("$displayName: platform aliases form an invalid hierarchy")
            ordered += ready.sortedBy(KmpFragment::name)
            remaining.removeAll(ready.toSet())
        }
        return ordered
    }

    /** Qualifiers accepted by a single-platform product, broadest first. */
    fun singlePlatform(platform: String): List<KmpFragment> =
        listOf(
            KmpFragment(COMMON, setOf(platform), natural = true),
            KmpFragment(platform, setOf(platform), natural = true, parents = listOf(COMMON)),
        )

    /**
     * The leaves each qualifier covers in the complete platform hierarchy, which is the set Kotlin
     * Toolchain compares to decide whether one qualified section refines another. Unlike
     * [KmpFragment.platforms] a natural qualifier is not narrowed to the module's declared
     * platforms: `linux` stays broader than an alias naming only `linuxX64` even in a module that
     * declares no other Linux platform, which is how the Toolchain reads the same pair.
     */
    fun settingsLeaves(fragments: List<KmpFragment>): Map<String, Set<String>> =
        fragments.associate { fragment ->
            fragment.name to if (fragment.natural) naturalLeaves(fragment.name) else fragment.platforms
        }

    private fun naturalLeaves(name: String): Set<String> =
        LEAF_PLATFORMS.filterTo(linkedSetOf()) { leaf -> leaf == name || isNaturalAncestor(name, leaf) }

    private fun isNaturalAncestor(ancestor: String, descendant: String): Boolean {
        var current = NATURAL_PLATFORM_PARENTS[descendant]
        while (current != null) {
            if (current == ancestor) return true
            current = NATURAL_PLATFORM_PARENTS[current]
        }
        return false
    }

    /**
     * A single-platform alias is the only equal-size parent: it must sit above its leaf for its
     * sources to reach a compilation, without re-nesting natural grouping fragments.
     */
    private fun isBroader(candidate: KmpFragment, fragment: KmpFragment): Boolean {
        if (candidate.name == COMMON && fragment.name != COMMON) return true
        if (candidate.natural && fragment.natural && isNaturalAncestor(candidate.name, fragment.name)) return true
        if (!candidate.platforms.containsAll(fragment.platforms)) return false
        if (candidate.platforms.size > fragment.platforms.size) return true
        return !candidate.natural && isLeafPlatform(fragment)
    }

    private fun isLeafPlatform(fragment: KmpFragment): Boolean =
        fragment.natural && fragment.platforms.singleOrNull() == fragment.name

    const val COMMON = "common"

    private val NATURAL_PLATFORM_PARENTS = mapOf(
        "jvm" to "common",
        "android" to "common",
        "web" to "common",
        "js" to "web",
        "wasmJs" to "web",
        "wasmWasi" to "common",
        "native" to "common",
        "linux" to "native",
        "linuxX64" to "linux",
        "linuxArm64" to "linux",
        "mingw" to "native",
        "mingwX64" to "mingw",
        "apple" to "native",
        "macos" to "apple",
        "macosX64" to "macos",
        "macosArm64" to "macos",
        "ios" to "apple",
        "iosArm64" to "ios",
        "iosSimulatorArm64" to "ios",
        "iosX64" to "ios",
        "watchos" to "apple",
        "watchosArm32" to "watchos",
        "watchosArm64" to "watchos",
        "watchosDeviceArm64" to "watchos",
        "watchosSimulatorArm64" to "watchos",
        "tvos" to "apple",
        "tvosArm64" to "tvos",
        "tvosSimulatorArm64" to "tvos",
        "tvosX64" to "tvos",
        "androidNative" to "native",
        "androidNativeArm32" to "androidNative",
        "androidNativeArm64" to "androidNative",
        "androidNativeX86" to "androidNative",
        "androidNativeX64" to "androidNative",
    )

    /** The platforms nothing refines: every other natural qualifier is a grouping above them. */
    private val LEAF_PLATFORMS = NATURAL_PLATFORM_PARENTS.keys - NATURAL_PLATFORM_PARENTS.values.toSet()

    /** Platforms that use the Kotlin/Native target DSL. */
    val NATIVE_TARGETS = setOf(
        "linuxX64", "linuxArm64", "macosX64", "macosArm64", "mingwX64", "iosX64", "iosArm64",
        "iosSimulatorArm64", "watchosArm32", "watchosArm64", "watchosDeviceArm64",
        "watchosSimulatorArm64", "tvosArm64", "tvosSimulatorArm64", "tvosX64", "androidNativeArm32",
        "androidNativeArm64", "androidNativeX86", "androidNativeX64",
    )
}
