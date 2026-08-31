package io.heapy.ktctogradle

import io.heapy.ktctogradle.interpret.Repositories
import io.heapy.ktctogradle.load.ToolchainModel
import io.heapy.ktctogradle.load.YamlBinder
import io.heapy.ktctogradle.load.parseYaml
import io.heapy.ktctogradle.model.GradleModule
import io.heapy.ktctogradle.model.Repository
import io.heapy.ktctogradle.model.RepositoryCredentials
import io.heapy.ktctogradle.model.RepositoryShorthand
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RepositoriesTest {
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

    @Test
    fun everyRepositoryAnyModuleResolvesFromReachesPluginResolution() {
        val mirror = Repository("mavenCentral", "https://mirror.example/maven")
        val plain = Repository("mavenCentral", MAVEN_CENTRAL, shorthand = RepositoryShorthand.MAVEN_CENTRAL)
        val google = Repository("mavenGoogle", GOOGLE, shorthand = RepositoryShorthand.GOOGLE)

        assertEquals(
            listOf(plain, google, mirror),
            Repositories.forPlugins(
                ROOT,
                listOf(module(":api", ROOT / "api", plain, google), module(":core", ROOT / "core", mirror)),
            ),
        )
    }

    @Test
    fun oneIdMappedToTwoUrlsKeepsBoth() {
        val first = Repository("internal", "https://repo.example/first")
        val second = Repository("internal", "https://repo.example/second")

        assertEquals(
            listOf(first, second),
            Repositories.forPlugins(
                ROOT,
                listOf(module(":api", ROOT / "api", first), module(":core", ROOT / "core", second)),
            ),
        )
    }

    @Test
    fun aRepositoryDeclaredByTwoModulesIsOfferedOnce() {
        val mirror = Repository("mavenCentral", "https://mirror.example/maven")

        assertEquals(
            listOf(mirror),
            Repositories.forPlugins(
                ROOT,
                listOf(module(":api", ROOT / "api", mirror), module(":core", ROOT / "core", mirror)),
            ),
        )
    }

    @Test
    fun onlyWhatAModuleResolvesFromReachesPluginResolution() {
        val mirror = Repository("mavenCentral", "https://mirror.example/maven")

        assertEquals(
            listOf(mirror),
            Repositories.forPlugins(ROOT, listOf(module(":core", ROOT / "core", mirror))),
        )
    }

    @Test
    fun aCredentialsFileIsRebasedOntoTheRootOfTheBuild() {
        val credentials = RepositoryCredentials("secrets.properties", "user", "password")
        val mirror = Repository("internal", "https://repo.example/internal", credentials = credentials)

        assertEquals(
            listOf(mirror.copy(credentials = credentials.copy(file = "libs/core/secrets.properties"))),
            Repositories.forPlugins(ROOT, listOf(module(":libs:core", ROOT / "libs" / "core", mirror))),
        )
    }

    @Test
    fun aCredentialsFileDeclaredByTheRootModuleIsLeftAsItStands() {
        val credentials = RepositoryCredentials("secrets.properties", "user", "password")
        val mirror = Repository("internal", "https://repo.example/internal", credentials = credentials)

        assertEquals(listOf(mirror), Repositories.forPlugins(ROOT, listOf(module(":", ROOT, mirror))))
    }

    private fun module(gradlePath: String, directory: Path, vararg repositories: Repository): GradleModule =
        GradleModule(
            gradlePath = gradlePath,
            directory = directory,
            plugins = emptyList(),
            repositories = repositories.toList(),
            requiresCredentialsImport = repositories.any { it.credentials != null },
            build = null,
        )

    private fun model(yaml: String): ToolchainModel =
        YamlBinder.bind(parseYaml(yaml, "app/module.yaml"), "app")

    private companion object {
        private const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
        private const val GOOGLE = "https://maven.google.com"
        private val ROOT: Path = "/workspace".toPath()
    }
}
