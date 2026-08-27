package io.heapy.ktctogradle

import okio.Path

/**
 * Where a module sits inside the project, kept as segments instead of a string.
 *
 * A [Path] renders itself with the host separator, so `toString()` produces backslashes on Windows.
 * Every consumer of a module location wants a different separator anyway: Kotlin Toolchain notation
 * and project.yaml globs use `/`, Gradle project paths use `:`. Holding the segments keeps each
 * rendering explicit and independent of the host.
 */
internal class ModulePath private constructor(
    val segments: List<String>,
) : Comparable<ModulePath> {
    val isRoot: Boolean
        get() = segments.isEmpty()

    /** Toolchain notation without the leading `//`, and the text project.yaml globs match against. */
    val notation: String = segments.joinToString("/")

    /** Gradle project path; `:` for the root project. */
    val gradlePath: String = if (segments.isEmpty()) ":" else segments.joinToString(":", prefix = ":")

    override fun toString(): String = notation

    override fun compareTo(other: ModulePath): Int = notation.compareTo(other.notation)

    override fun equals(other: Any?): Boolean = other is ModulePath && segments == other.segments

    override fun hashCode(): Int = segments.hashCode()

    companion object {
        /** Reads `//libs/messages`, `./libs/messages` or `libs/messages` as written in YAML. */
        fun parse(notation: String): ModulePath =
            ModulePath(notation.removePrefix("//").removePrefix("./").split('/').filter(String::isNotEmpty))

        /** The path of [directory] inside [root], or null when [directory] is not inside [root]. */
        fun relativize(root: Path, directory: Path): ModulePath? {
            val segments = directory.relativeTo(root).segments.filter { it != "." }
            if (segments.any { it == ".." }) return null
            return ModulePath(segments)
        }
    }
}
