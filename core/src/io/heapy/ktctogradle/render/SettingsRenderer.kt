package io.heapy.ktctogradle.render

import io.heapy.ktctogradle.model.GradleModule
import io.heapy.ktctogradle.model.GradleProject

/**
 * Spells the project out as `settings.gradle.kts`.
 *
 * A version catalog is only declared here when the project keeps it next to `settings.gradle.kts`:
 * Gradle finds `gradle/libs.versions.toml` on its own, and declaring it twice is an error.
 */
internal fun renderSettings(project: GradleProject): String = KtsWriter().apply {
    line(StaticAssets.header())
    block("pluginManagement") {
        // The plugin portal comes last, and not first as `GradleGenerator` wrote it. Gradle asks
        // the repositories in order and stops at the first that answers, so a project that mirrors
        // everything never reaches the portal at all — which is the point on a network where it is
        // unreachable, because an unreachable repository fails resolution rather than being
        // skipped. It is still written, because nothing in the Toolchain model can name it and a
        // build that cannot reach any portal-only plugin has no way to ask for one.
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

/**
 * Where a subproject sits, relative to the root and written with `/` on every host.
 *
 * `Path.toString()` uses the host separator, so the segments are joined by hand: a settings file
 * generated on Windows has to stay readable by a build running anywhere else.
 */
private fun GradleProject.projectDirOf(module: GradleModule): String =
    module.directory.relativeTo(root).segments.filter { it != "." }.joinToString("/")

private const val ROOT_PATH = ":"
