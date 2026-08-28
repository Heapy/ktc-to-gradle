package io.heapy.ktctogradle.load

import okio.FileSystem
import okio.Path

/**
 * The filesystem facts a module contributes to the generated build.
 *
 * Probing happens once, in the load stage, so that interpretation and rendering never touch a
 * [FileSystem]: they read these fields as plain data instead.
 */
internal data class ModuleLayout(
    /** Names of the source directories that exist directly under the module, e.g. `src`, `src@jvm`. */
    val existingSourceDirs: Set<String>,
    /** Fully qualified name of the class generated from `src/main.kt` or `src@jvm/main.kt`. */
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

    /**
     * The first `main.kt` under [directory], depth first.
     *
     * The walk stops at it rather than collecting every `.kt` path to pick one out afterwards: a
     * module with a few thousand sources otherwise stats and keeps all of them on every run to use
     * a single entry.
     *
     * Each directory is still visited in name order, which is what makes the answer independent of
     * the order the file system happens to list a directory in.
     *
     * Stopping early moves one boundary, deliberately: a directory the walk can no longer read is
     * only reached when nothing before it matched. A module whose `main.kt` sorts first now
     * converts even when some unrelated directory further down became unreadable, where collecting
     * everything first would have failed the run.
     */
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
