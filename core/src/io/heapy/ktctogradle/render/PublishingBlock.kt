package io.heapy.ktctogradle.render

import io.heapy.ktctogradle.model.Pom
import io.heapy.ktctogradle.model.Publication

/** Configures JVM publications directly and KGP's lazily created KMP publications as a collection. */
internal fun KtsWriter.appendPublishing(publication: Publication) {
    blank()
    block("publishing") {
        if (publication.perTarget) {
            val artifactId = publication.artifactId?.takeUnless { it == publication.projectName }
            if (artifactId != null || publication.pom != null) {
                block("publications.withType<MavenPublication>().configureEach") {
                    // Replace KGP's project-name prefix while preserving platform suffixes.
                    if (artifactId != null) {
                        line(
                            "artifactId = ${quote(artifactId)} + " +
                                "artifactId.removePrefix(${quote(publication.projectName)})",
                        )
                    }
                    publication.pom?.let { appendPom(it) }
                }
            }
        } else {
            block("publications") {
                block("create<MavenPublication>(\"maven\")") {
                    line("from(components[\"java\"])")
                    publication.artifactId?.let { line("artifactId = ${quote(it)}") }
                    publication.pom?.let { appendPom(it) }
                }
            }
        }
        if (publication.repositories.isNotEmpty()) {
            block("repositories") {
                appendRepositoryEntries(publication.repositories)
            }
        }
    }
}

/**
 * Reads Toolchain's signing variables through Gradle providers. The `sign` call remains outside the
 * key guard so a requested signed publication fails instead of silently publishing unsigned.
 */
internal fun KtsWriter.appendSigning(publication: Publication) {
    if (!publication.signArtifacts) return
    blank()
    block("signing") {
        line("val signingKey = providers.environmentVariable(${quote(SIGNING_KEY_VARIABLE)}).orNull")
        block("if (signingKey != null)") {
            // The overload requires a non-null passphrase; an unencrypted key uses the empty string.
            line(
                "useInMemoryPgpKeys(signingKey, providers.environmentVariable(" +
                    "${quote(SIGNING_PASSPHRASE_VARIABLE)}).getOrElse(\"\"))",
            )
        }
        line("sign(publishing.publications)")
    }
}

internal fun KtsWriter.appendPublicationCoordinates(publication: Publication) {
    if (publication.group == null && publication.version == null) return
    blank()
    publication.group?.let { line("group = ${quote(it)}") }
    publication.version?.let { line("version = ${quote(it)}") }
}

private fun KtsWriter.appendPom(pom: Pom) {
    block("pom") {
        pom.name?.let { line("name.set(${quote(it)})") }
        pom.description?.let { line("description.set(${quote(it)})") }
        pom.url?.let { line("url.set(${quote(it)})") }
        if (pom.licenses.isNotEmpty()) {
            block("licenses") {
                for (license in pom.licenses) {
                    block("license") {
                        license.name?.let { line("name.set(${quote(it)})") }
                        license.url?.let { line("url.set(${quote(it)})") }
                    }
                }
            }
        }
        if (pom.developers.isNotEmpty()) {
            block("developers") {
                for (developer in pom.developers) {
                    block("developer") {
                        developer.id?.let { line("id.set(${quote(it)})") }
                        developer.name?.let { line("name.set(${quote(it)})") }
                        developer.url?.let { line("url.set(${quote(it)})") }
                        developer.email?.let { line("email.set(${quote(it)})") }
                        developer.organization?.let { line("organization.set(${quote(it)})") }
                        developer.organizationUrl?.let { line("organizationUrl.set(${quote(it)})") }
                    }
                }
            }
        }
        pom.scm?.let { scm ->
            block("scm") {
                scm.url?.let { line("url.set(${quote(it)})") }
                scm.connection?.let { line("connection.set(${quote(it)})") }
                scm.developerConnection?.let { line("developerConnection.set(${quote(it)})") }
            }
        }
    }
}

private const val SIGNING_KEY_VARIABLE = "KOTLIN_TOOLCHAIN_SIGNING_KEY"

private const val SIGNING_PASSPHRASE_VARIABLE = "KOTLIN_TOOLCHAIN_SIGNING_KEY_PASSPHRASE"
