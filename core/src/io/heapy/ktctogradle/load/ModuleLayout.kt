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
            val files = mutableListOf<Path>()
            collectKotlinFiles(root, files)
            val main = files.firstOrNull { it.name.equals("main.kt", ignoreCase = true) } ?: continue
            val text = fileSystem.read(main) { readUtf8() }
            if (!Regex("\\bfun\\s+main\\s*\\(").containsMatchIn(text)) continue
            val packageName = Regex("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)").find(text)?.groupValues?.get(1)
            val className = main.name.removeSuffix(".kt").replaceFirstChar { it.uppercase() } + "Kt"
            return if (packageName == null) className else "$packageName.$className"
        }
        return null
    }

    private fun collectKotlinFiles(directory: Path, destination: MutableList<Path>) {
        for (child in fileSystem.list(directory).sortedBy(Path::name)) {
            if (fileSystem.metadata(child).isDirectory) collectKotlinFiles(child, destination)
            else if (child.name.endsWith(".kt", ignoreCase = true)) destination += child
        }
    }

    private companion object {
        private val sourceDirectoryName = Regex("^(src|test|resources|testResources)(@.+)?$")
    }
}
