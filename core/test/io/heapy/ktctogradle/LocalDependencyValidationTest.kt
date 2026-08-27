package io.heapy.ktctogradle

import io.heapy.ktctogradle.load.ModuleLayout
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.YamlBinder
import io.heapy.ktctogradle.load.parseYaml
import io.heapy.ktctogradle.load.validateLocalDependencies
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What the load stage checks about dependency sections before anything is interpreted.
 *
 * The reach of the two checks is not the same, and both halves are pinned here. The *shape* of a
 * section — a list of strings or one-coordinate objects — and the modules its notations point at are
 * checked for every declared section, not only the ones the module's product will read, so a typo
 * fails the conversion instead of being dropped on the floor. What only a reader of a section
 * decides — an unknown scope shorthand, a malformed `bom` coordinate, the module a `bom` points at —
 * is left to [io.heapy.ktctogradle.interpret.Dependencies], so an ignored qualifier cannot fail a
 * conversion over it.
 */
class LocalDependencyValidationTest {
    @Test
    fun aLocalNotationThatNamesNoModuleIsRejected() {
        val app = module("app", "product: jvm/lib\ndependencies:\n  - //libs/missing\n")

        assertEquals(
            "app depends on unknown module '//libs/missing'",
            assertFailsWith<ConversionException> { validateLocalDependencies(listOf(app)) }.message,
        )
    }

    @Test
    fun aValidProjectPassesBothLookups() {
        val app = module("app", "product: jvm/lib\ndependencies:\n  - //libs/shared\n  - ./../libs/shared\n")
        val shared = module("libs/shared", "product: jvm/lib\n")

        validateLocalDependencies(listOf(app, shared))
    }

    /**
     * A `jvm/lib` never reads `dependencies@js`, so nothing downstream would ever raise the failure
     * the binder deferred for it. The load stage raises it for every declared section instead.
     */
    @Test
    fun aMalformedSectionIsRejectedEvenWhenTheProductNeverReadsThatQualifier() {
        val app = module("app", "product: jvm/lib\ndependencies@js: not-a-list\n")

        assertEquals(
            "Expected a list at app.dependencies@js",
            assertFailsWith<ConversionException> { validateLocalDependencies(listOf(app)) }.message,
        )
    }

    @Test
    fun aMalformedTestSectionIsRejectedTheSameWay() {
        val app = module("app", "product: jvm/lib\ntest-dependencies@native:\n  - a: 1\n    b: 2\n")

        assertEquals(
            "A dependency object must have exactly one coordinate",
            assertFailsWith<ConversionException> { validateLocalDependencies(listOf(app)) }.message,
        )
    }

    /**
     * A scope shorthand is only ever read by the stage that renders the section, so a `jvm/app` that
     * misspells one under `@js` still converts. The load stage never read them either.
     */
    @Test
    fun anUnknownScopeUnderAnUnreadQualifierIsLeftToTheStageThatReadsIt() {
        val app = module("app", "product: jvm/app\ndependencies@js:\n  - com.example:lib:1.0: bogus\n")

        validateLocalDependencies(listOf(app))
    }

    @Test
    fun aMalformedBomCoordinateIsLeftToTheStageThatReadsIt() {
        val app = module("app", "product: jvm/app\ndependencies@js:\n  - bom: [not-a-scalar]\n")

        validateLocalDependencies(listOf(app))
    }

    /** A `bom` names the coordinate `bom` to this stage, so the module it holds is never resolved. */
    @Test
    fun anUnknownModuleUnderABomIsLeftToTheStageThatReadsIt() {
        val app = module("app", "product: jvm/lib\ndependencies:\n  - bom: ./missing\n")

        validateLocalDependencies(listOf(app))
    }

    /**
     * The unknown-module scan does not depend on the entry being readable: an entry whose scope this
     * stage cannot judge still names a module, and that module still has to exist.
     */
    @Test
    fun anUnknownModuleIsFoundEvenWhenTheSameEntryHasAnUnknownScope() {
        val app = module("app", "product: jvm/lib\ndependencies@js:\n  - //libs/missing: bogus\n")

        assertEquals(
            "app depends on unknown module '//libs/missing'",
            assertFailsWith<ConversionException> { validateLocalDependencies(listOf(app)) }.message,
        )
    }

    /** No product reads `dependencies-dev`, but a section named like one is still a section. */
    @Test
    fun aKeyThatOnlyStartsWithASectionNameIsCheckedToo() {
        val app = module("app", "product: jvm/lib\ndependencies-dev:\n  - //libs/missing\n")

        assertEquals(
            "app depends on unknown module '//libs/missing'",
            assertFailsWith<ConversionException> { validateLocalDependencies(listOf(app)) }.message,
        )
    }

    @Test
    fun aKeyThatOnlyStartsWithASectionNameIsShapeCheckedToo() {
        val app = module("app", "product: jvm/lib\ntest-dependencies-dev: not-a-list\n")

        assertEquals(
            "Expected a list at app.test-dependencies-dev",
            assertFailsWith<ConversionException> { validateLocalDependencies(listOf(app)) }.message,
        )
    }

    /** Only dependency regions are raised here; `settings` keeps surfacing from its own reader. */
    @Test
    fun aFailureInAnotherRegionIsLeftToTheStageThatReadsIt() {
        val app = module("app", "product: jvm/lib\nsettings:\n  kotlin:\n    optIns: nope\n")

        validateLocalDependencies(listOf(app))
    }

    private fun module(notation: String, yaml: String): ToolchainModule {
        val config = parseYaml(yaml, "$notation/module.yaml")
        return ToolchainModule(
            path = ModulePath.parse(notation),
            directory = notation.split('/').fold(ROOT) { path, segment -> path / segment },
            model = YamlBinder.bind(config, notation),
            layout = ModuleLayout(existingSourceDirs = emptySet(), detectedMainClass = null),
        )
    }

    private companion object {
        private val ROOT: Path = "/workspace".toPath()
    }
}
