package io.heapy.ktctogradle.render

import io.heapy.ktctogradle.model.Repository
import io.heapy.ktctogradle.model.RepositoryShorthand

/**
 * A `repositories { }` block.
 *
 * Shared by `build.gradle.kts` and the `pluginManagement` block of `settings.gradle.kts`, because
 * a mirror that dependency resolution reaches has to be spelled the same way plugin resolution
 * reaches it — including its credentials.
 */
internal fun KtsWriter.appendRepositories(repositories: List<Repository>) {
    block("repositories") {
        appendRepositoryEntries(repositories)
    }
}

/**
 * The entries alone, for a block that already has a line of its own.
 *
 * `pluginManagement` always declares the plugin portal first, and Gradle takes a repeated
 * `repositories { }` block, but a build file that says it twice reads like an accident.
 */
internal fun KtsWriter.appendRepositoryEntries(
    repositories: List<Repository>,
    /**
     * How `java.util.Properties` is spelled here.
     *
     * Gradle compiles the `pluginManagement { }` block of a settings script on its own, ahead of the
     * rest of the file and without its import list, so a bare `Properties()` inside it is an
     * unresolved reference and the build fails before it starts. A build script imports the class
     * and names it plainly, which is what the baselines pin.
     */
    propertiesType: String = "Properties",
) {
    for ((index, repository) in repositories.withIndex()) {
        when (repository.shorthand) {
            RepositoryShorthand.MAVEN_LOCAL -> line("mavenLocal()")
            RepositoryShorthand.MAVEN_CENTRAL -> line("mavenCentral()")
            RepositoryShorthand.GOOGLE -> line("google()")
            null -> block("maven") {
                line("name = ${quote(repository.id)}")
                line("url = uri(${quote(repository.url)})")
                repository.credentials?.let { credentials ->
                    val variable = "repositoryCredentials$index"
                    line("val $variable = $propertiesType()")
                    line("file(${quote(credentials.file)}).inputStream().use($variable::load)")
                    block("credentials") {
                        line("username = $variable.getProperty(${quote(credentials.usernameKey)})")
                        line("password = $variable.getProperty(${quote(credentials.passwordKey)})")
                    }
                }
            }
        }
    }
}

/** Credentials are read from a properties file, which is the only import a script ever needs. */
internal fun KtsWriter.appendCredentialsImport(required: Boolean) {
    if (!required) return
    blank()
    line("import java.util.Properties")
    blank()
}
