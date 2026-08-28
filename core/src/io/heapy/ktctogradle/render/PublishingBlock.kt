package io.heapy.ktctogradle.render

import io.heapy.ktctogradle.model.Pom
import io.heapy.ktctogradle.model.Publication

/**
 * The `maven-publish` and `signing` bodies of a module that publishes.
 *
 * Two shapes, because the Kotlin Gradle Plugin decides how many publications there are. A JVM module
 * has none until the build creates one. A multiplatform module gets a root publication plus one per
 * target from the plugin, and it creates some of them lazily, so the build configures the collection
 * — `withType<MavenPublication>().configureEach` reaches the ones that do not exist yet — instead of
 * adding to it or naming them one by one.
 */
internal fun KtsWriter.appendPublishing(publication: Publication) {
    blank()
    block("publishing") {
        if (publication.perTarget) {
            // A base that already is the Gradle project name is the name the plugin picked anyway,
            // so restating it would be a line that rewrites every artifact id into itself.
            val artifactId = publication.artifactId?.takeUnless { it == publication.projectName }
            if (artifactId != null || publication.pom != null) {
                block("publications.withType<MavenPublication>().configureEach") {
                    // The plugin named every publication after the Gradle project and appended the
                    // platform to all but the root one. Replacing that prefix keeps the suffixes and
                    // reproduces the Toolchain's base-plus-platform naming.
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
 * `signing`, wired to the two environment variables the Kotlin Toolchain reads.
 *
 * Only the key lookup is guarded, not the `sign` call. `signArtifacts: true` is a requirement rather
 * than a preference: the Toolchain refuses to publish at all when the variable is unset, and a
 * `sign` call with no signatory configured fails the publish task the same way. Guarding the whole
 * block instead would turn "sign these artifacts" into "sign them if a key happens to be around",
 * and a build that quietly published unsigned artifacts is the failure this converter must not ship.
 *
 * Only a publish task reaches the signing tasks, so a plain `build` still runs where there is no key.
 *
 * Read through `providers` rather than `System.getenv`, so the value is a configuration input Gradle
 * tracks and the configuration cache is invalidated when it changes.
 */
internal fun KtsWriter.appendSigning(publication: Publication) {
    if (!publication.signArtifacts) return
    blank()
    block("signing") {
        line("val signingKey = providers.environmentVariable(${quote(SIGNING_KEY_VARIABLE)}).orNull")
        block("if (signingKey != null)") {
            // The passphrase overload takes a non-null String: a key with no passphrase is unlocked
            // by the empty one, and passing null leaves the build with no signatory at all.
            line(
                "useInMemoryPgpKeys(signingKey, providers.environmentVariable(" +
                    "${quote(SIGNING_PASSPHRASE_VARIABLE)}).getOrElse(\"\"))",
            )
        }
        // Outside the guard on purpose. `signArtifacts: true` is a requirement, and the Toolchain
        // refuses to publish without the key rather than publishing unsigned; a `sign` call with no
        // signatory configured fails the publish task the same way. Only `publish` reaches it, so a
        // plain `build` still runs on a machine that has no key.
        line("sign(publishing.publications)")
    }
}

/** The project coordinate, which `maven-publish` reads off the project rather than the publication. */
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
