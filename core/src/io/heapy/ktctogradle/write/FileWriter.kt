package io.heapy.ktctogradle.write

import io.heapy.ktctogradle.ConversionException
import io.heapy.ktctogradle.makeExecutable
import io.heapy.ktctogradle.model.FileContent
import io.heapy.ktctogradle.model.GeneratedFile
import io.heapy.ktctogradle.render.StaticAssets
import okio.ByteString
import okio.FileSystem
import okio.Path

/** Writes changed generated files while refusing to replace foreign content. */
internal class FileWriter(private val fileSystem: FileSystem) {
    /** [dryRun] performs the same collision checks and reports the same changed paths without writing. */
    fun write(
        root: Path,
        files: List<GeneratedFile>,
        force: Boolean,
        dryRun: Boolean,
    ): List<Path> {
        val paths = files.map(GeneratedFile::path) + files.mapNotNull(::companionOf)
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
            // Restore executable permission even when wrapper bytes are unchanged.
            makeExecutable(root / "gradlew")
        }
        return changed.map { it.path.relativeTo(root) }
    }

    private fun read(path: Path): ByteString = fileSystem.read(path) { readByteString() }

    /** Binary ownership follows its named companion; a missing companion cannot establish ownership. */
    private fun isGenerated(file: GeneratedFile, onDisk: Map<Path, ByteString?>): Boolean? = when (file.content) {
        is FileContent.Text -> onDisk[file.path]?.let(::carriesMarker)
        is FileContent.Binary ->
            if (onDisk[file.path] == null) {
                null
            } else {
                val companion = companionOf(file)?.let(onDisk::get)
                companion != null && carriesMarker(companion)
            }
    }

    private fun companionOf(file: GeneratedFile): Path? =
        (file.content as? FileContent.Binary)?.let { binary -> file.path.parent?.div(binary.ownershipFollows) }

    private fun carriesMarker(content: ByteString): Boolean =
        content.utf8().contains(StaticAssets.GENERATED_MARKER, ignoreCase = true)
}
