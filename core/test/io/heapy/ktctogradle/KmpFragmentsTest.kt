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
