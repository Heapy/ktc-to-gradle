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

    @Test
    fun anUnknownModuleUnderABomIsLeftToTheStageThatReadsIt() {
        val app = module("app", "product: jvm/lib\ndependencies:\n  - bom: ./missing\n")

        validateLocalDependencies(listOf(app))
    }

    @Test
    fun anUnknownModuleIsFoundEvenWhenTheSameEntryHasAnUnknownScope() {
        val app = module("app", "product: jvm/lib\ndependencies@js:\n  - //libs/missing: bogus\n")

        assertEquals(
            "app depends on unknown module '//libs/missing'",
            assertFailsWith<ConversionException> { validateLocalDependencies(listOf(app)) }.message,
        )
    }

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
