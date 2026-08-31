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
 * Converts `settings.publishing` without inventing task behavior. Presence alone does not enable
 * publication, and every unsupported key is reported instead of silently dropped.
 */
internal object Publishing {
    /** Uses [declared] so project-wide plugin resolution and module interpretation cannot disagree. */
    fun pluginsOf(model: ToolchainModel): List<GradlePlugin> {
        val settings = declared(model) ?: return emptyList()
        return buildList {
            add(GradlePlugin.Builtin.MAVEN_PUBLISH)
            if (settings.signArtifacts == true) add(GradlePlugin.Builtin.SIGNING)
        }
    }

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
            // A publication without a coordinate is unusable, not merely degraded.
            diagnostics.error(
                "${module.displayName}: settings.publishing is enabled without " +
                    missing.joinToString(" and ") { "settings.publishing.$it" } +
                    "; the module was left unpublished",
            )
            return null
        }

        val pom = pom(module, settings)
        if (settings.mavenCentral?.enabled == true) {
            val mode = settings.mavenCentral.publishingMode?.let { " (publishingMode '$it' included)" }.orEmpty()
            diagnostics.warn(
                "${module.displayName}: settings.publishing.mavenCentral has no Gradle equivalent$mode; " +
                    "the generated build publishes to the repositories it declares and uploads no " +
                    "Central Portal bundle",
            )
            reportCentralRequirements(module, settings, pom, product, diagnostics)
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
            pom = pom,
            perTarget = product in ProductType.MULTIPLATFORM,
            projectName = projectName(module),
            repositories = Repositories.forPublishing(model),
        )
    }

    /** Reports Central requirements that Toolchain validates before upload but Gradle cannot enforce. */
    private fun reportCentralRequirements(
        module: ToolchainModule,
        settings: PublishingSettings,
        pom: Pom?,
        product: String,
        diagnostics: DiagnosticCollector,
    ) {
        val missing = listOfNotNull(
            "settings.publishing.signArtifacts".takeIf { settings.signArtifacts != true },
            "settings.publishing.publishSources".takeIf { settings.publishSources != true },
            "settings.publishing.pom.description".takeIf { pom?.description == null },
            "settings.publishing.pom.url".takeIf { pom?.url == null },
            "settings.publishing.pom.licenses".takeIf { pom?.licenses.isNullOrEmpty() },
            "settings.publishing.pom.developers".takeIf { pom?.developers.isNullOrEmpty() },
            "settings.publishing.pom.scm".takeIf { pom?.scm == null },
        )
        if (missing.isNotEmpty()) {
            diagnostics.warn(
                "${module.displayName}: settings.publishing.mavenCentral is enabled, and Maven Central " +
                    "refuses a publication missing ${missing.joinToString()}; the Kotlin Toolchain " +
                    "checks the same requirements before it uploads",
            )
        }
        // KGP provides no per-target javadoc jar for multiplatform publications.
        if (product in ProductType.MULTIPLATFORM) {
            diagnostics.warn(
                "${module.displayName}: settings.publishing.mavenCentral is enabled, and Maven Central " +
                    "refuses a publication without a javadoc jar; the generated build has none, because " +
                    "the Kotlin Gradle Plugin builds no javadoc per target and the 'withJavadocJar()' a " +
                    "jvm/lib gets has no multiplatform equivalent",
            )
        }
    }

    /** Applies Toolchain's module-name and description fallbacks; omits a still-empty POM. */
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

    private fun projectName(module: ToolchainModule): String =
        if (module.path.isRoot) module.directory.name else module.gradlePath.substringAfterLast(':')

    private val PUBLISHABLE = setOf(ProductType.JVM_LIB, ProductType.KMP_LIB)
}
