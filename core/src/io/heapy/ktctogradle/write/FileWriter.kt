package io.heapy.ktctogradle.write

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.makeExecutable
import io.heapy.ktctogradle.model.FileContent
import io.heapy.ktctogradle.model.GeneratedFile
import io.heapy.ktctogradle.render.StaticAssets
import okio.ByteString
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
        // The companion of a binary file is read too, because that is where its ownership is
        // recorded, and it is not always one of the files being written.
        val paths = files.map(GeneratedFile::path) +
            files.mapNotNull { (it.content as? FileContent.Binary)?.ownershipFollows }
        val onDisk = paths.distinct().associateWith { path ->
            if (fileSystem.exists(path)) read(path) else null
        }
        val changed = files.filter { file -> onDisk[file.path] != file.content.bytes }
        val collisions = changed.filter { file -> isGenerated(file, onDisk) == false }
        if (collisions.isNotEmpty() && !force) {
            throw ConversionException(
                "Refusing to overwrite existing files: ${collisions.joinToString { it.path.relativeTo(root).toString() }}. " +
                    "Re-run with --force after reviewing them.",
            )
        }
        if (!dryRun) {
            for (file in changed) {
                file.path.parent?.let(fileSystem::createDirectories)
                fileSystem.write(file.path) { write(file.content.bytes) }
            }
            // Set every run: a wrapper script that was already up to date can still have lost its
            // executable bit on the way into the checkout.
            makeExecutable(root / "gradlew")
        }
        return changed.map { it.path.relativeTo(root) }
    }

    private fun read(path: Path): ByteString = fileSystem.read(path) { readByteString() }

    /**
     * Whether the file on disk is one this converter wrote, and may therefore be replaced.
     *
     * `null` means there is nothing on disk to overwrite. A binary file carries no marker of its
     * own, so it answers with the file it named: `gradle-wrapper.jar` belongs to whoever wrote the
     * `gradle-wrapper.properties` beside it, and a companion that is not on disk cannot vouch for
     * anything.
     */
    private fun isGenerated(file: GeneratedFile, onDisk: Map<Path, ByteString?>): Boolean? = when (val content = file.content) {
        is FileContent.Text -> onDisk[file.path]?.let(::carriesMarker)
        is FileContent.Binary ->
            if (onDisk[file.path] == null) null else carriesMarker(onDisk[content.ownershipFollows] ?: return false)
    }

    private fun carriesMarker(content: ByteString): Boolean =
        content.utf8().contains(StaticAssets.GENERATED_MARKER, ignoreCase = true)
}
