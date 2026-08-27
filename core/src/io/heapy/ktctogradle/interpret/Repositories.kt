package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.load.RawRepository
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.ToolchainModel
import io.heapy.ktctogradle.load.raiseDeferred
import io.heapy.ktctogradle.model.Repository
import io.heapy.ktctogradle.model.RepositoryCredentials
import io.heapy.ktctogradle.model.RepositoryShorthand

/**
 * Which repositories a module resolves its dependencies from.
 *
 * Kotlin Toolchain implies Maven Central and Google unless a module names them itself, and a module
 * may turn a repository off rather than replace it, so the effective list is a decision and not a
 * copy of the YAML.
 */
internal object Repositories {
    fun of(model: ToolchainModel): List<Repository> {
        model.raiseDeferred(Region.REPOSITORIES)
        val configured = model.repositories.map { raw -> raw to identify(raw) }
        // Ids are settled before the resolve filter, so disabling a default by its URL still keeps
        // the implied one from coming back.
        val configuredIds = configured.mapTo(mutableSetOf()) { (_, id) -> id }
        val defaults = listOf(
            Repository(
                id = MAVEN_CENTRAL_ID,
                url = Defaults.MAVEN_CENTRAL_URL,
                shorthand = RepositoryShorthand.MAVEN_CENTRAL,
            ),
            Repository(
                id = MAVEN_GOOGLE_ID,
                url = Defaults.GOOGLE_MAVEN_URL,
                shorthand = RepositoryShorthand.GOOGLE,
            ),
        ).filterNot { it.id in configuredIds }
        val enabled = configured.filter { (raw, _) -> raw.resolve }
            .asReversed()
            .distinctBy { (_, id) -> id }
            .asReversed()
            .map { (raw, id) -> repository(raw, id) }
        return defaults + enabled
    }

    /**
     * Whether the generated script has to import `java.util.Properties`.
     *
     * Declared repositories are counted, not resolved ones: a repository that is only published to
     * still needs its credentials read.
     */
    fun requiresCredentialsImport(model: ToolchainModel): Boolean {
        model.raiseDeferred(Region.REPOSITORIES)
        return model.repositories.any { it.credentials != null }
    }

    private fun identify(raw: RawRepository): String =
        raw.id ?: defaultRepositoryId(raw.url) ?: raw.url

    /**
     * A repository written as a plain URL has no id of its own, so a module that repeats a default
     * repository would otherwise be treated as a separate one and emitted next to it.
     */
    private fun defaultRepositoryId(url: String): String? = when (url.trimEnd('/')) {
        Defaults.MAVEN_CENTRAL_URL -> MAVEN_CENTRAL_ID
        Defaults.GOOGLE_MAVEN_URL -> MAVEN_GOOGLE_ID
        else -> null
    }

    private fun repository(raw: RawRepository, id: String): Repository {
        val url = raw.url
        val credentials = raw.credentials?.let {
            RepositoryCredentials(file = it.file, usernameKey = it.usernameKey, passwordKey = it.passwordKey)
        }
        return Repository(
            id = id,
            url = url,
            credentials = credentials,
            // A mirror behind credentials is not the public repository the shorthand stands for,
            // even when it carries its id.
            shorthand = when {
                url == MAVEN_LOCAL_ID -> RepositoryShorthand.MAVEN_LOCAL
                id == MAVEN_CENTRAL_ID && url == Defaults.MAVEN_CENTRAL_URL && credentials == null ->
                    RepositoryShorthand.MAVEN_CENTRAL
                id == MAVEN_GOOGLE_ID && url == Defaults.GOOGLE_MAVEN_URL && credentials == null ->
                    RepositoryShorthand.GOOGLE
                else -> null
            },
        )
    }

    /** The ids Kotlin Toolchain gives the repositories it implies; a module names them to turn one off. */
    private const val MAVEN_CENTRAL_ID = "mavenCentral"

    private const val MAVEN_GOOGLE_ID = "mavenGoogle"

    /** Written in the `url` position rather than as an id, which is how the Toolchain spells it. */
    private const val MAVEN_LOCAL_ID = "mavenLocal"
}
