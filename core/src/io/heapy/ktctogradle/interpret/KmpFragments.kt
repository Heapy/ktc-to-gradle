package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.ToolchainModel
import io.heapy.ktctogradle.load.raiseDeferred

/**
 * One source-set fragment of a multiplatform module, before it is split into a main and a test one.
 *
 * [platforms] is what makes a fragment comparable to another: a fragment is a parent of a second
 * one when it covers more leaf platforms, or — for a declared alias naming exactly one platform —
 * the same single leaf. That is how an alias finds its place in the default hierarchy without the
 * module having to say so.
 */
internal data class KmpFragment(
    val name: String,
    val platforms: Set<String>,
    /** `true` = part of Kotlin's default platform hierarchy rather than a declared alias. */
    val natural: Boolean,
    val parents: List<String> = emptyList(),
)

/**
 * The fragment hierarchy of a multiplatform module.
 *
 * Kotlin gives every platform a chain of natural ancestors (`linuxX64` -> `linux` -> `native` ->
 * `common`), and a module may add aliases of its own. Both kinds live in one hierarchy here, so a
 * source set only ever declares its direct parents and the ordering guarantees they already exist.
 */
internal object KmpFragments {
    /**
     * Every fragment of the module, parents before children.
     *
     * The order is a topological one, with the fragments of one level sorted by name, so the same
     * module always produces the same source-set order.
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

    /**
     * The qualifiers a single-platform product accepts, broadest first.
     *
     * A `jvm/app` has no fragment hierarchy of its own, but it still reads `settings@common` and
     * `settings@jvm`, and it reads them in the same order a multiplatform module would.
     */
    fun singlePlatform(platform: String): List<Pair<String, Set<String>>> =
        listOf(COMMON to setOf(platform), platform to setOf(platform))

    /** Whether [ancestor] is reached by following [descendant]'s natural parent chain upwards. */
    private fun isNaturalAncestor(ancestor: String, descendant: String): Boolean {
        var current = NATURAL_PLATFORM_PARENTS[descendant]
        while (current != null) {
            if (current == ancestor) return true
            current = NATURAL_PLATFORM_PARENTS[current]
        }
        return false
    }

    /**
     * Whether [candidate] is a parent of [fragment], directly or through others.
     *
     * `common` is above everything, a natural fragment is above its natural descendants, and any
     * fragment covering strictly more platforms is above the ones it contains — which is what
     * places an alias inside the default hierarchy.
     *
     * Size alone leaves an alias naming exactly one platform with nothing under it, because the
     * leaf it names covers exactly as much as it does. Such an alias goes above that leaf instead,
     * which is the only placement where the sources and dependencies it qualifies reach a
     * compilation. It is deliberately the only equal-size case: an alias covering the same
     * platforms as a grouping fragment such as `native` must not re-nest the natural hierarchy,
     * and one covering every declared platform must not end up mutually broader with `common`.
     */
    private fun isBroader(candidate: KmpFragment, fragment: KmpFragment): Boolean {
        if (candidate.name == COMMON && fragment.name != COMMON) return true
        if (candidate.natural && fragment.natural && isNaturalAncestor(candidate.name, fragment.name)) return true
        if (!candidate.platforms.containsAll(fragment.platforms)) return false
        if (candidate.platforms.size > fragment.platforms.size) return true
        return !candidate.natural && isLeafPlatform(fragment)
    }

    /** Whether [fragment] is a declared platform's own fragment, the bottom of the hierarchy. */
    private fun isLeafPlatform(fragment: KmpFragment): Boolean =
        fragment.natural && fragment.platforms.singleOrNull() == fragment.name

    const val COMMON = "common"

    /** Kotlin's default platform hierarchy, as a child-to-parent map. */
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

    /** The platforms Kotlin/Native compiles to a binary, which is what gives them their target DSL. */
    val NATIVE_TARGETS = setOf(
        "linuxX64", "linuxArm64", "macosX64", "macosArm64", "mingwX64", "iosX64", "iosArm64",
        "iosSimulatorArm64", "watchosArm32", "watchosArm64", "watchosDeviceArm64",
        "watchosSimulatorArm64", "tvosArm64", "tvosSimulatorArm64", "tvosX64", "androidNativeArm32",
        "androidNativeArm64", "androidNativeX86", "androidNativeX64",
    )
}
