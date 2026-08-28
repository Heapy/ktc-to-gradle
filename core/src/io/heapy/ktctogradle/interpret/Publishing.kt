package io.heapy.ktctogradle.interpret

import io.heapy.ktctogradle.DiagnosticCollector
import io.heapy.ktctogradle.load.ProductType
import io.heapy.ktctogradle.load.PublishingSettings
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.ToolchainModel
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.raiseDeferred
import io.heapy.ktctogradle.model.GradlePlugin
import io.heapy.ktctogradle.model.Pom
import io.heapy.ktctogradle.model.PomDeveloper
import io.heapy.ktctogradle.model.PomLicense
import io.heapy.ktctogradle.model.PomScm
import io.heapy.ktctogradle.model.Publication

/**
 * What `settings.publishing` becomes: a `maven-publish` publication, or nothing plus a reason.
 *
 * The Toolchain publishes with a command of its own, `kotlin publish <target>`, which reads the
 * section at run time. Gradle has no such command: everything the section says has to be a task
 * graph the generated build already carries, so the section is read here in full and every key that
 * has no task graph behind it is named rather than dropped.
 *
 * Every default in the section is the Toolchain's own, and each of them is `false` or absent. That
 * matters more here than anywhere else in the converter: a module that merely mentions the section
 * publishes nothing upstream, and a converted build that published it anyway would ship an artifact
 * nobody asked for.
 */
internal object Publishing {
    /**
     * The plugins publishing adds, decided without reading a diagnostic.
     *
     * `PluginResolution` settles the whole project's plugins before any module is assembled, so this
     * answer is needed twice: once there and once in [of]. Both ask [declared], so the plugins block
     * and the publishing block can never disagree about whether the module publishes.
     */
    fun pluginsOf(model: ToolchainModel): List<GradlePlugin> {
        val settings = declared(model) ?: return emptyList()
        return buildList {
            add(GradlePlugin.Builtin.MAVEN_PUBLISH)
            if (settings.signArtifacts == true) add(GradlePlugin.Builtin.SIGNING)
        }
    }

    /**
     * The section, once it is known to ask for a publication this converter produces.
     *
     * `enabled` defaults to `false`, so presence is not the switch: a module that declares the whole
     * section without turning it on publishes nothing. The product and the coordinate are the other
     * two conditions, and [of] is where each of them is reported.
     */
    private fun declared(model: ToolchainModel): PublishingSettings? {
        if (Region.PRODUCT in model.errors || Region.SETTINGS in model.errors) return null
        val settings = model.settings.publishing ?: return null
        if (settings.enabled != true) return null
        if (model.product.type !in PUBLISHABLE) return null
        if (settings.group == null || settings.version == null) return null
        return settings
    }

    fun of(module: ToolchainModule, diagnostics: DiagnosticCollector): Publication? {
        val model = module.model
        model.raiseDeferred(Region.SETTINGS)
        val settings = model.settings.publishing ?: return null
        if (settings.enabled != true) return null

        val product = model.product.type
        if (product !in PUBLISHABLE) {
            diagnostics.warn(
                "${module.displayName}: settings.publishing is not converted for a '$product'; " +
                    "the Kotlin Toolchain publishes jvm/lib and kmp/lib modules only",
            )
            return null
        }
        val missing = listOfNotNull(
            "group".takeIf { settings.group == null },
            "version".takeIf { settings.version == null },
        )
        if (missing.isNotEmpty()) {
            // An artifact with no coordinate is not a degraded publication, it is one nobody can
            // consume, so this is an error the exit code reports rather than a warning beside it.
            diagnostics.error(
                "${module.displayName}: settings.publishing is enabled without " +
                    missing.joinToString(" and ") { "settings.publishing.$it" } +
                    "; the module was left unpublished",
            )
            return null
        }

        if (settings.mavenCentral?.enabled == true) {
            val mode = settings.mavenCentral.publishingMode?.let { " (publishingMode '$it' included)" }.orEmpty()
            diagnostics.warn(
                "${module.displayName}: settings.publishing.mavenCentral has no Gradle equivalent$mode; " +
                    "the generated build publishes to the repositories it declares and uploads no " +
                    "Central Portal bundle",
            )
        }
        if (settings.checksums.isNotEmpty()) {
            diagnostics.warn(
                "${module.displayName}: settings.publishing.checksums ${settings.checksums.joinToString()} " +
                    "was dropped; Gradle writes its own set next to every artifact and offers no way " +
                    "to choose one",
            )
        }

        return Publication(
            group = settings.group,
            version = settings.version,
            artifactId = settings.artifactId,
            publishSources = settings.publishSources == true,
            signArtifacts = settings.signArtifacts == true,
            pom = pom(module, settings),
            perTarget = product in ProductType.MULTIPLATFORM,
            projectName = projectName(module),
            repositories = Repositories.forPublishing(model),
        )
    }

    /**
     * The POM, with the two fields the Toolchain fills in from the module itself.
     *
     * Gradle writes no `<name>` and no `<description>` unless the build sets them, so leaving them
     * out would publish a POM the Toolchain would have filled — and Maven Central requires both. A
     * POM still empty after the defaults is no POM and gets no block.
     */
    private fun pom(module: ToolchainModule, settings: PublishingSettings): Pom? {
        val declared = settings.pom
        val pom = Pom(
            name = declared?.name ?: projectName(module),
            description = declared?.description ?: module.model.description,
            url = declared?.url,
            licenses = declared?.licenses.orEmpty().map { PomLicense(name = it.name, url = it.url) },
            developers = declared?.developers.orEmpty().map {
                PomDeveloper(
                    id = it.id,
                    name = it.name,
                    url = it.url,
                    email = it.email,
                    organization = it.organization,
                    organizationUrl = it.organizationUrl,
                )
            },
            scm = declared?.scm?.let {
                PomScm(url = it.url, connection = it.connection, developerConnection = it.developerConnection)
            },
        )
        return pom.takeUnless { it.isEmpty }
    }

    /**
     * What Gradle calls the module's project, which is what it names an artifact after by default.
     *
     * The root module takes the name `settings.gradle.kts` gives the build; every other module is
     * the last segment of its Gradle path, which is how `include(":libs:messages")` names one.
     */
    private fun projectName(module: ToolchainModule): String =
        if (module.path.isRoot) module.directory.name else module.gradlePath.substringAfterLast(':')

    /** The Toolchain publishes libraries. An application has no consumer to publish it for. */
    private val PUBLISHABLE = setOf(ProductType.JVM_LIB, ProductType.KMP_LIB)
}
