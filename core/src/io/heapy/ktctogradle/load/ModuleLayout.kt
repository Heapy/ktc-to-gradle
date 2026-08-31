package io.heapy.ktctogradle.load

import okio.FileSystem
import okio.Path

/** Filesystem facts captured during load for use by pure later stages. */
internal data class ModuleLayout(
    val existingSourceDirs: Set<String>,
    val detectedMainClass: String?,
)

internal class ModuleLayoutProbe(private val fileSystem: FileSystem) {
    fun probe(directory: Path): ModuleLayout = ModuleLayout(
        existingSourceDirs = existingSourceDirs(directory),
        detectedMainClass = detectMainClass(directory),
    )

    private fun existingSourceDirs(directory: Path): Set<String> =
        fileSystem.list(directory)
            .map(Path::name)
            .filterTo(mutableSetOf(), sourceDirectoryName::matches)

    private fun detectMainClass(directory: Path): String? {
        val sourceRoots = listOf(directory / "src", directory / "src@jvm")
        for (root in sourceRoots.filter(fileSystem::exists)) {
            val main = firstMainKt(root) ?: continue
            val text = fileSystem.read(main) { readUtf8() }
            if (!Regex("\\bfun\\s+main\\s*\\(").containsMatchIn(text)) continue
            val packageName = Regex("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)").find(text)?.groupValues?.get(1)
            val className = main.name.removeSuffix(".kt").replaceFirstChar { it.uppercase() } + "Kt"
            return if (packageName == null) className else "$packageName.$className"
        }
        return null
    }

    /** Stops at the first name-sorted depth-first match to avoid scanning every source file. */
    private fun firstMainKt(directory: Path): Path? {
        for (child in fileSystem.list(directory).sortedBy(Path::name)) {
            if (fileSystem.metadata(child).isDirectory) {
                firstMainKt(child)?.let { return it }
            } else if (child.name.equals("main.kt", ignoreCase = true)) {
                return child
            }
        }
        return null
    }

    private companion object {
        private val sourceDirectoryName = Regex("^(src|test|resources|testResources)(@.+)?$")
    }
}
