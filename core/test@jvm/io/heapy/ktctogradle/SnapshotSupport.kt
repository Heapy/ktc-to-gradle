package io.heapy.ktctogradle

import io.heapy.ktctogradle.model.FileContent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.fail

/** Uses source-tree baselines so update mode can rewrite them. */
internal object Snapshots {
    const val UPDATE_ENV = "UPDATE_SNAPSHOTS"
    const val UPDATE_PROPERTY = "ktc.updateSnapshots"
    const val UPDATE_MARKER = ".update-snapshots"

    /** Falls back to test code-source location because the runner does not guarantee a working directory. */
    fun repositoryRoot(): Path {
        val candidates = listOfNotNull(
            System.getProperty("user.dir")?.let(Paths::get),
            codeSourceDirectory(),
        )
        for (candidate in candidates) {
            var current: Path? = candidate.toAbsolutePath().normalize()
            while (current != null) {
                if (current.resolve("project.yaml").exists() && current.resolve("core").isDirectory()) return current
                current = current.parent
            }
        }
        fail("Could not locate the repository root (a directory holding project.yaml and core/) above any of $candidates")
    }

    fun goldenRoot(): Path = repositoryRoot().resolve("core").resolve("testResources@jvm").resolve("golden")

    fun fixtureRoot(): Path = repositoryRoot().resolve("integration-tests").resolve("fixtures")

    /** Supports three triggers because runners may drop environment variables or JVM properties. */
    fun updateSnapshots(): Boolean = activeUpdateTrigger() != null

    /** Names the active trigger so a stale marker file is diagnosable. */
    fun activeUpdateTrigger(): String? = when {
        isEnabled(System.getenv(UPDATE_ENV)) -> "the $UPDATE_ENV environment variable"
        isEnabled(System.getProperty(UPDATE_PROPERTY)) -> "the -D$UPDATE_PROPERTY system property"
        goldenRoot().resolve(UPDATE_MARKER).exists() -> "the ${goldenRoot().resolve(UPDATE_MARKER)} marker file"
        else -> null
    }

    private fun isEnabled(value: String?): Boolean =
        value != null && value.isNotBlank() && value != "0" && !value.equals("false", ignoreCase = true)

    private fun codeSourceDirectory(): Path? =
        runCatching { Paths.get(Snapshots::class.java.protectionDomain.codeSource.location.toURI()) }.getOrNull()
}

/** Compares bytes verbatim, using readable text diffs and binary summaries without normalizing CRLF. */
internal fun assertSnapshot(case: String, fileName: String, actual: FileContent) {
    when (actual) {
        is FileContent.Text -> assertSnapshot(case, fileName, actual.value)
        is FileContent.Binary -> assertBinarySnapshot(
            Snapshots.goldenRoot(),
            case,
            fileName,
            actual.bytes.toByteArray(),
            update = Snapshots.updateSnapshots(),
        )
    }
}

internal fun assertSnapshot(case: String, fileName: String, actual: String) {
    assertSnapshot(Snapshots.goldenRoot(), case, fileName, actual, update = Snapshots.updateSnapshots())
}

internal fun assertBinarySnapshot(
    goldenRoot: Path,
    case: String,
    fileName: String,
    actual: ByteArray,
    update: Boolean,
) {
    val golden = goldenRoot.resolve(case).resolve("expected").resolve(fileName)
    if (update) {
        Files.createDirectories(golden.parent)
        Files.write(golden, actual)
        return
    }
    if (!golden.exists()) {
        fail(
            "Missing golden baseline '$case/expected/$fileName'.\n" +
                "Regenerate the baselines with ${Snapshots.UPDATE_ENV}=1 ./kotlin test -m core -p jvm",
        )
    }
    val expected = Files.readAllBytes(golden)
    if (expected.contentEquals(actual)) return
    fail(
        "Generated output changed for '$case/$fileName'.\n" +
            "This is the refactor invariant: fix the code, do not regenerate the baseline.\n" +
            "expected ${expected.size} bytes, sha256 ${sha256(expected)}\n" +
            "actual   ${actual.size} bytes, sha256 ${sha256(actual)}",
    )
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

internal fun assertSnapshot(
    goldenRoot: Path,
    case: String,
    fileName: String,
    actual: String,
    update: Boolean,
) {
    val golden = goldenRoot.resolve(case).resolve("expected").resolve(fileName)
    if (update) {
        Files.createDirectories(golden.parent)
        golden.writeText(actual)
        return
    }
    if (!golden.exists()) {
        fail(
            "Missing golden baseline '$case/expected/$fileName'.\n" +
                "Regenerate the baselines with ${Snapshots.UPDATE_ENV}=1 ./kotlin test -m core -p jvm",
        )
    }
    val expected = golden.readText()
    if (expected == actual) return
    fail(
        "Generated output changed for '$case/$fileName'.\n" +
            "This is the refactor invariant: fix the code, do not regenerate the baseline.\n" +
            unifiedDiff(expected, actual),
    )
}

/** A minimal unified diff, kept in Kotlin so the failure message reads the same on every host. */
internal fun unifiedDiff(expected: String, actual: String, context: Int = 3): String {
    val expectedLines = expected.split("\n")
    val actualLines = actual.split("\n")
    val common = longestCommonSubsequence(expectedLines, actualLines)

    val edits = mutableListOf<Pair<Char, String>>()
    var expectedIndex = 0
    var actualIndex = 0
    for (line in common) {
        while (expectedIndex < expectedLines.size && expectedLines[expectedIndex] != line) {
            edits += '-' to expectedLines[expectedIndex++]
        }
        while (actualIndex < actualLines.size && actualLines[actualIndex] != line) {
            edits += '+' to actualLines[actualIndex++]
        }
        edits += ' ' to line
        expectedIndex++
        actualIndex++
    }
    while (expectedIndex < expectedLines.size) edits += '-' to expectedLines[expectedIndex++]
    while (actualIndex < actualLines.size) edits += '+' to actualLines[actualIndex++]

    val keep = BooleanArray(edits.size)
    for ((index, edit) in edits.withIndex()) {
        if (edit.first == ' ') continue
        for (near in (index - context).coerceAtLeast(0)..(index + context).coerceAtMost(edits.size - 1)) {
            keep[near] = true
        }
    }
    return buildString {
        appendLine("--- expected")
        appendLine("+++ actual")
        var skipping = false
        for ((index, edit) in edits.withIndex()) {
            if (!keep[index]) {
                if (!skipping) appendLine("@@")
                skipping = true
                continue
            }
            skipping = false
            appendLine("${edit.first}${visible(edit.second)}")
        }
    }
}

/** Line endings are part of the contract, so a stray carriage return must be visible in the diff. */
private fun visible(line: String): String = line.replace("\r", "\\r")

private fun longestCommonSubsequence(left: List<String>, right: List<String>): List<String> {
    val lengths = Array(left.size + 1) { IntArray(right.size + 1) }
    for (i in left.indices.reversed()) {
        for (j in right.indices.reversed()) {
            lengths[i][j] = if (left[i] == right[j]) {
                lengths[i + 1][j + 1] + 1
            } else {
                maxOf(lengths[i + 1][j], lengths[i][j + 1])
            }
        }
    }
    val result = mutableListOf<String>()
    var i = 0
    var j = 0
    while (i < left.size && j < right.size) {
        when {
            left[i] == right[j] -> {
                result += left[i]
                i++
                j++
            }
            lengths[i + 1][j] >= lengths[i][j + 1] -> i++
            else -> j++
        }
    }
    return result
}
