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
        block("repositories") {
            line("gradlePluginPortal()")
            line("google()")
            line("mavenCentral()")
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
