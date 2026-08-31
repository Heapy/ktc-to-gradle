package io.heapy.ktctogradle.render

import io.heapy.ktctogradle.model.GradleModule
import io.heapy.ktctogradle.model.GradleProject

/** Declares only root catalogs; Gradle auto-imports `gradle/libs.versions.toml`. */
internal fun renderSettings(project: GradleProject): String = KtsWriter().apply {
    line(StaticAssets.header())
    block("pluginManagement") {
        // Keep the portal last so configured mirrors get the first chance to resolve every plugin.
        block("repositories") {
            appendRepositoryEntries(project.pluginRepositories, propertiesType = "java.util.Properties")
            line("gradlePluginPortal()")
        }
    }
    blank()
    line("rootProject.name = ${quote(project.name)}")
    if (project.catalog?.parent == project.root) {
        blank()
        block("dependencyResolutionManagement") {
            block("versionCatalogs") {
                line("create(\"libs\") { from(files(\"libs.versions.toml\")) }")
            }
        }
    }
    for (module in project.modules) {
        if (module.gradlePath == ROOT_PATH) continue
        blank()
        line("include(${quote(module.gradlePath)})")
        line("project(${quote(module.gradlePath)}).projectDir = file(${quote(project.projectDirOf(module))})")
    }
}.build()

/** Joins path segments explicitly so settings always use `/`, independent of the host. */
private fun GradleProject.projectDirOf(module: GradleModule): String =
    module.directory.relativeTo(root).segments.filter { it != "." }.joinToString("/")

private const val ROOT_PATH = ":"
