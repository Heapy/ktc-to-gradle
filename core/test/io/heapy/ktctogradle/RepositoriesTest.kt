package io.heapy.ktctogradle

import io.heapy.ktctogradle.interpret.Repositories
import io.heapy.ktctogradle.load.ToolchainModel
import io.heapy.ktctogradle.load.YamlBinder
import io.heapy.ktctogradle.load.parseYaml
import io.heapy.ktctogradle.model.Repository
import io.heapy.ktctogradle.model.RepositoryShorthand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Which repositories a module ends up resolving from, asserted on the model and not on the DSL. */
class RepositoriesTest {
    /** Gradle resolves a repository once, so a repeated id must not be emitted twice. */
    @Test
    fun aRepositoryDeclaredTwiceKeepsOnlyItsLastDeclaration() {
        val model = model(
            """
            product: jvm/lib
            repositories:
              - id: internal
                url: https://repo.example.com/first
              - id: internal
                url: https://repo.example.com/second
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                Repository("mavenCentral", MAVEN_CENTRAL, shorthand = RepositoryShorthand.MAVEN_CENTRAL),
                Repository("mavenGoogle", GOOGLE, shorthand = RepositoryShorthand.GOOGLE),
                Repository("internal", "https://repo.example.com/second"),
            ),
            Repositories.of(model),
        )
    }

    /** A URL that differs from a default only by its trailing slash is still that default. */
    @Test
    fun aDefaultRepositoryRepeatedWithATrailingSlashIsNotEmittedTwice() {
        val model = model("product: jvm/lib\nrepositories:\n  - $MAVEN_CENTRAL/\n")

        assertEquals(
            listOf(
                Repository("mavenGoogle", GOOGLE, shorthand = RepositoryShorthand.GOOGLE),
                Repository("mavenCentral", "$MAVEN_CENTRAL/"),
            ),
            Repositories.of(model),
        )
    }

    /** The binder defers a malformed section; the stage that reads it is the one that raises it. */
    @Test
    fun aMalformedRepositoriesSectionRaisesItsDeferredMessage() {
        val model = model("product: jvm/lib\nrepositories:\n  - id: internal\n")

        assertTrue("repositories" in model.errors)
        assertEquals(
            "repositories[0].url is required",
            assertFailsWith<ConversionException> { Repositories.of(model) }.message,
        )
        assertEquals(
            "repositories[0].url is required",
            assertFailsWith<ConversionException> { Repositories.requiresCredentialsImport(model) }.message,
        )
    }

    private fun model(yaml: String): ToolchainModel =
        YamlBinder.bind(parseYaml(yaml, "app/module.yaml"), "app")

    private companion object {
        private const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
        private const val GOOGLE = "https://maven.google.com"
    }
}
