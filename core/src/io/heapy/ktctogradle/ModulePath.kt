package io.heapy.ktctogradle

import okio.Path

/** A host-independent module location; Toolchain and Gradle render its segments differently. */
internal class ModulePath private constructor(
    val segments: List<String>,
) : Comparable<ModulePath> {
    val isRoot: Boolean
        get() = segments.isEmpty()

    val notation: String = segments.joinToString("/")

    val gradlePath: String = if (segments.isEmpty()) ":" else segments.joinToString(":", prefix = ":")

    override fun toString(): String = notation

    override fun compareTo(other: ModulePath): Int = notation.compareTo(other.notation)

    override fun equals(other: Any?): Boolean = other is ModulePath && segments == other.segments

    override fun hashCode(): Int = segments.hashCode()

    companion object {
        fun parse(notation: String): ModulePath =
            ModulePath(notation.removePrefix("//").removePrefix("./").split('/').filter(String::isNotEmpty))

        fun relativize(root: Path, directory: Path): ModulePath? {
            val segments = directory.relativeTo(root).segments.filter { it != "." }
            if (segments.any { it == ".." }) return null
            return ModulePath(segments)
        }
    }
}
