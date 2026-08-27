package io.heapy.ktctogradle.model

import okio.Path

internal data class GeneratedFile(val path: Path, val content: String)

/**
 * A set of plugins Gradle has to load at one single version for the whole build.
 *
 * [displayName] is the name the multi-version warning quotes, so it is part of the user-visible
 * output and not a label.
 */
internal enum class PluginFamily(val displayName: String) {
    KOTLIN("kotlin"),
    ANDROID("android"),
}

/**
 * A Gradle plugin the converter can apply, as identity rather than as DSL text.
 *
 * Keeping the set closed is what keeps Gradle DSL syntax out of the model: only
 * `render/ModuleRenderer.kt` knows how a plugin is spelled, and its `when` is exhaustive.
 */
internal sealed interface GradlePlugin {
    /** Fully qualified plugin id. Identity for deduplication and version resolution. */
    val id: String

    /** `null` = unversioned: never version-resolved, never declared in the root. */
    val family: PluginFamily?

    /** Applied through the Kotlin plugin shorthand, by [shortName]. */
    enum class Kotlin(val shortName: String) : GradlePlugin {
        JVM("jvm"),
        MULTIPLATFORM("multiplatform"),
        SERIALIZATION("plugin.serialization"),
        ;

        override val id: String get() = "org.jetbrains.kotlin.$shortName"
        override val family: PluginFamily get() = PluginFamily.KOTLIN
    }

    /** Applied by plugin [id]. */
    enum class Android(override val id: String) : GradlePlugin {
        APPLICATION("com.android.application"),
        KMP_LIBRARY("com.android.kotlin.multiplatform.library"),
        ;

        override val family: PluginFamily get() = PluginFamily.ANDROID
    }

    /** Gradle's own plugins. Applied bare, with no version and no root declaration. */
    enum class Builtin(override val id: String) : GradlePlugin {
        APPLICATION("application"),
        BASE("base"),
        ;

        override val family: PluginFamily? get() = null
    }

    /** Escape hatch for a plugin this converter does not model yet. */
    data class Other(
        override val id: String,
        override val family: PluginFamily?,
    ) : GradlePlugin
}

/**
 * One line of a `plugins { }` block, after the project-wide version has been chosen.
 *
 * Whether the user pinned the version belongs to the request and not to the declaration, so it
 * lives in `PluginResolution` and never reaches the model.
 */
internal data class PluginDecl(
    val plugin: GradlePlugin,
    /** `null` = do not print a version. */
    val version: String?,
    /** `false` = an inherited plugin the root declares for its subprojects. */
    val apply: Boolean = true,
)
