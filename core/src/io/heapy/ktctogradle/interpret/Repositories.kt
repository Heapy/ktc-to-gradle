package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.load.RawRepository
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.ToolchainModel
import io.heapy.ktctogradle.load.raiseDeferred
import io.heapy.ktctogradle.model.GradleModule
import io.heapy.ktctogradle.model.Repository
import io.heapy.ktctogradle.model.RepositoryCredentials
import io.heapy.ktctogradle.model.RepositoryShorthand
import okio.Path

/** Applies Kotlin Toolchain's implied repositories and explicit disable/override semantics. */
internal object Repositories {
    fun of(model: ToolchainModel): List<Repository> {
        model.raiseDeferred(Region.REPOSITORIES)
        val configured = model.repositories.map { raw -> raw to identify(raw) }
        // Identify before filtering so disabling a default does not let the implied copy return.
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
     * Unions module repositories for `pluginManagement`, deduplicating by full value rather than id
     * so conflicting id-to-URL mappings are not silently discarded. Credential paths are rebased
     * from each module to the root settings file.
     */
    fun forPlugins(root: Path, modules: List<GradleModule>): List<Repository> = modules
        .flatMap { module -> module.repositories.map { rebaseCredentials(root, module, it) } }
        .distinct()

    private fun rebaseCredentials(root: Path, module: GradleModule, repository: Repository): Repository {
        val credentials = repository.credentials ?: return repository
        // Join okio segments explicitly so generated paths always use `/`.
        val prefix = module.directory.relativeTo(root).segments.filter { it != "." }
        if (prefix.isEmpty()) return repository
        return repository.copy(
            credentials = credentials.copy(file = (prefix + credentials.file).joinToString("/")),
        )
    }

    /** Counts declared repositories because publish-only credentials still need the import. */
    fun requiresCredentialsImport(model: ToolchainModel): Boolean {
        model.raiseDeferred(Region.REPOSITORIES)
        return model.repositories.any { it.credentials != null }
    }

    fun forPublishing(model: ToolchainModel): List<Repository> {
        model.raiseDeferred(Region.REPOSITORIES)
        return model.repositories.filter { it.publish }.map { raw -> repository(raw, identify(raw)) }
    }

    private fun identify(raw: RawRepository): String =
        raw.id ?: defaultRepositoryId(raw.url) ?: raw.url

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

    private const val MAVEN_CENTRAL_ID = "mavenCentral"

    private const val MAVEN_GOOGLE_ID = "mavenGoogle"

    private const val MAVEN_LOCAL_ID = "mavenLocal"
}
