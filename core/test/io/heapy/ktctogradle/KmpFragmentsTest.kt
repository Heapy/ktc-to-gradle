package io.heapy.ktctogradle

import io.heapy.ktctogradle.interpret.KmpFragment
import io.heapy.ktctogradle.interpret.KmpFragments
import io.heapy.ktctogradle.load.ToolchainModel
import io.heapy.ktctogradle.load.YamlBinder
import io.heapy.ktctogradle.load.parseYaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The fragment hierarchy of a multiplatform module, asserted as data.
 *
 * The generated source sets are emitted in exactly this order and declare exactly these parents, so
 * a whole-list `assertEquals` is what pins the hierarchy rather than a spot check on one fragment.
 */
class KmpFragmentsTest {
    @Test
    fun aDeclaredPlatformBringsItsWholeNaturalAncestry() {
        assertEquals(
            listOf(
                KmpFragment("common", setOf("jvm", "linuxX64"), natural = true, parents = emptyList()),
                KmpFragment("jvm", setOf("jvm"), natural = true, parents = listOf("common")),
                KmpFragment("native", setOf("linuxX64"), natural = true, parents = listOf("common")),
                KmpFragment("linux", setOf("linuxX64"), natural = true, parents = listOf("native")),
                KmpFragment("linuxX64", setOf("linuxX64"), natural = true, parents = listOf("linux")),
            ),
            fragments("[jvm, linuxX64]"),
        )
    }

    /**
     * An alias is placed by the platforms it covers, so it needs no parent of its own: `desktop`
     * hangs off `common`, and the leaves it covers gain it as a second parent.
     */
    @Test
    fun anAliasFindsItsPlaceByThePlatformsItCovers() {
        val fragments = fragments(
            "[jvm, linuxX64, macosArm64]",
            "aliases:\n  - desktop: [jvm, linuxX64]\n  - posix: [linuxX64, macosArm64]\n",
        ).associateBy(KmpFragment::name)

        assertEquals(
            KmpFragment("desktop", setOf("jvm", "linuxX64"), natural = false, parents = listOf("common")),
            fragments.getValue("desktop"),
        )
        assertEquals(
            KmpFragment("posix", setOf("linuxX64", "macosArm64"), natural = false, parents = listOf("common")),
            fragments.getValue("posix"),
        )
        assertEquals(listOf("desktop"), fragments.getValue("jvm").parents)
        assertEquals(listOf("native", "desktop", "posix"), fragments.getValue("linux").parents)
        assertEquals(listOf("native", "posix"), fragments.getValue("apple").parents)
    }

    /**
     * An alias naming exactly one platform sits between that platform and its natural parent.
     *
     * It covers no more leaves than the platform's own fragment, so the size comparison alone would
     * leave it beside the leaf with nothing depending on it. A native leaf keeps its natural chain
     * and gains the alias as a second parent; the grouping fragments above it are untouched.
     */
    @Test
    fun anAliasNamingOnePlatformIsThatPlatformsParent() {
        val jvmAlias = fragments("[jvm, linuxX64]", "aliases:\n  - server: [jvm]\n").associateBy(KmpFragment::name)

        assertEquals(
            KmpFragment("server", setOf("jvm"), natural = false, parents = listOf("common")),
            jvmAlias.getValue("server"),
        )
        assertEquals(listOf("server"), jvmAlias.getValue("jvm").parents)

        val nativeAlias = fragments("[jvm, linuxX64]", "aliases:\n  - box: [linuxX64]\n").associateBy(KmpFragment::name)

        assertEquals(
            KmpFragment("box", setOf("linuxX64"), natural = false, parents = listOf("common")),
            nativeAlias.getValue("box"),
        )
        assertEquals(listOf("linux", "box"), nativeAlias.getValue("linuxX64").parents)
        assertEquals(listOf("common"), nativeAlias.getValue("native").parents)
        assertEquals(listOf("native"), nativeAlias.getValue("linux").parents)
    }

    /**
     * An alias covering every declared platform stays under `common` rather than beside it.
     *
     * `common` is above everything by name, so an alias that covers exactly as much must not be
     * made broader than `common` in turn: the two would then be mutually broader, each would drop
     * the other from the leaves' direct parents, and the leaves would be left with no parent.
     */
    @Test
    fun anAliasCoveringEveryDeclaredPlatformStaysUnderCommon() {
        assertEquals(
            listOf(
                KmpFragment("common", setOf("jvm", "linuxX64"), natural = true, parents = emptyList()),
                KmpFragment("both", setOf("jvm", "linuxX64"), natural = false, parents = listOf("common")),
                KmpFragment("jvm", setOf("jvm"), natural = true, parents = listOf("both")),
                KmpFragment("native", setOf("linuxX64"), natural = true, parents = listOf("both")),
                KmpFragment("linux", setOf("linuxX64"), natural = true, parents = listOf("native")),
                KmpFragment("linuxX64", setOf("linuxX64"), natural = true, parents = listOf("linux")),
            ),
            fragments("[jvm, linuxX64]", "aliases:\n  - both: [jvm, linuxX64]\n"),
        )
    }

    /**
     * The order is topological — every parent precedes its children — and total: fragments that
     * become available together are sorted by name, so the same module never reorders its output.
     */
    @Test
    fun theOrderIsTopologicalAndTotal() {
        val aliases = "aliases:\n  - desktop: [jvm, linuxX64]\n  - posix: [linuxX64, macosArm64]\n"

        assertEquals(
            listOf("common", "desktop", "native", "posix", "apple", "jvm", "linux", "linuxX64", "macos", "macosArm64"),
            fragments("[jvm, linuxX64, macosArm64]", aliases).map(KmpFragment::name),
        )
        for (fragment in fragments("[jvm, linuxX64, macosArm64]", aliases)) {
            val seen = fragments("[jvm, linuxX64, macosArm64]", aliases)
                .takeWhile { it.name != fragment.name }
                .map(KmpFragment::name)
            assertEquals(emptyList(), fragment.parents - seen.toSet(), "${fragment.name} precedes a parent")
        }
    }

    @Test
    fun theSameModuleAlwaysProducesTheSameHierarchy() {
        val yaml = "aliases:\n  - posix: [linuxX64, macosArm64]\n  - desktop: [jvm, linuxX64]\n"

        assertEquals(fragments("[jvm, linuxX64, macosArm64]", yaml), fragments("[jvm, linuxX64, macosArm64]", yaml))
    }

    @Test
    fun anAliasNamingAnUndeclaredPlatformIsRejected() {
        assertEquals(
            "shared: alias 'desktop' contains undeclared platforms macosArm64, mingwX64",
            assertFailsWith<ConversionException> {
                fragments("[jvm, linuxX64]", "aliases:\n  - desktop: [jvm, mingwX64, macosArm64]\n")
            }.message,
        )
    }

    @Test
    fun anAliasCannotRenameAFragmentTheHierarchyAlreadyHas() {
        assertEquals(
            "shared: alias 'native' conflicts with the default platform hierarchy",
            assertFailsWith<ConversionException> {
                fragments("[jvm, linuxX64]", "aliases:\n  - native: [linuxX64]\n")
            }.message,
        )
    }

    /** The binder defers a malformed `aliases:` section, so its message is raised here. */
    @Test
    fun aMalformedAliasesSectionRaisesItsDeferredMessage() {
        assertEquals(
            "aliases must be an object or list",
            assertFailsWith<ConversionException> { fragments("[jvm, linuxX64]", "aliases: 7\n") }.message,
        )
    }

    /** A single-platform product has no hierarchy, but it still reads both of its qualifiers. */
    @Test
    fun aSinglePlatformProductAcceptsCommonAndItsOwnQualifier() {
        assertEquals(
            listOf("common" to setOf("jvm"), "jvm" to setOf("jvm")),
            KmpFragments.singlePlatform("jvm"),
        )
    }

    private fun fragments(platforms: String, extra: String = ""): List<KmpFragment> =
        KmpFragments.of(model(platforms, extra), "shared")

    private fun model(platforms: String, extra: String): ToolchainModel {
        val yaml = "product:\n  type: kmp/lib\n  platforms: $platforms\n$extra"
        return YamlBinder.bind(parseYaml(yaml, "shared/module.yaml"), "shared")
    }
}
