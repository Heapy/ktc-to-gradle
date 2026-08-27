package io.heapy.ktctogradle.write

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.makeExecutable
import io.heapy.ktctogradle.model.GeneratedFile
import io.heapy.ktctogradle.render.StaticAssets
import okio.FileSystem
import okio.Path

/**
 * Stage 4: puts the rendered build on disk.
 *
 * The only stage that writes anything, and the only one that has an opinion about what is already
 * there: a file whose content did not change is left alone so its timestamp survives, and a file
 * this converter did not write is never silently replaced.
 */
internal class FileWriter(private val fileSystem: FileSystem) {
    /**
     * Writes what changed and returns those files, relative to [root].
     *
     * [dryRun] still runs every check, so `--dry-run` reports exactly the files a real run would
     * write and refuses exactly the ones a real run would refuse.
     */
    fun write(
        root: Path,
        files: List<GeneratedFile>,
        force: Boolean,
        dryRun: Boolean,
    ): List<Path> {
        // Read once per path: the changed check and the collision check both need the old content.
        val onDisk = files.associate { file ->
            file.path to if (fileSystem.exists(file.path)) read(file.path) else null
        }
        val changed = files.filter { file -> onDisk[file.path] != file.content }
        val collisions = changed.filter { file -> onDisk[file.path]?.let(::isGenerated) == false }
        if (collisions.isNotEmpty() && !force) {
            throw ConversionException(
                "Refusing to overwrite existing files: ${collisions.joinToString { it.path.relativeTo(root).toString() }}. " +
                    "Re-run with --force after reviewing them.",
            )
        }
        if (!dryRun) {
            for (file in changed) {
                file.path.parent?.let(fileSystem::createDirectories)
                fileSystem.write(file.path) { writeUtf8(file.content) }
            }
            // Set every run: a wrapper script that was already up to date can still have lost its
            // executable bit on the way into the checkout.
            makeExecutable(root / "gradlew")
        }
        return changed.map { it.path.relativeTo(root) }
    }

    private fun read(path: Path): String = fileSystem.read(path) { readUtf8() }

    /** Whether the file on disk is one this converter wrote, and may therefore be replaced. */
    private fun isGenerated(content: String): Boolean =
        content.contains(StaticAssets.GENERATED_MARKER, ignoreCase = true)
}
