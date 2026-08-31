package io.heapy.ktctogradle.render

import io.heapy.ktctogradle.model.Repository
import io.heapy.ktctogradle.model.RepositoryShorthand

internal fun KtsWriter.appendRepositories(repositories: List<Repository>) {
    block("repositories") {
        appendRepositoryEntries(repositories)
    }
}

internal fun KtsWriter.appendRepositoryEntries(
    repositories: List<Repository>,
    /** `pluginManagement` compiles before settings imports and needs the fully qualified type. */
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

internal fun KtsWriter.appendCredentialsImport(required: Boolean) {
    if (!required) return
    blank()
    line("import java.util.Properties")
    blank()
}
