package io.heapy.ktctogradle

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Self-checks for the golden harness: a snapshot net that cannot fail proves nothing. */
class SnapshotSupportTest {
    @Test
    fun theRepositoryRootHoldsTheProjectFile() {
        val root = Snapshots.repositoryRoot()

        assertTrue(root.resolve("project.yaml").exists(), "No project.yaml in the located repository root $root")
        assertTrue(root.resolve("core").resolve("module.yaml").exists(), "$root does not look like this repository")
    }

    /**
     * The safety net can switch itself off: three separate triggers put the suite into rewrite mode,
     * and a rewriting run deletes each `expected/` directory before regenerating it. Nothing else in
     * the suite would notice, so this is the test that does.
     */
    @Test
    fun aNormalRunComparesTheBaselinesInsteadOfRewritingThem() {
        val trigger = Snapshots.activeUpdateTrigger()

        assertNull(
            trigger,
            "The golden suite is in update mode because $trigger asked for it, so all 20 baselines " +
                "are being rewritten instead of compared. Clear it and re-run.",
        )
    }

    @Test
    fun aMismatchFails() {
        val goldenRoot = Files.createTempDirectory("ktc-to-gradle-snapshot-self-")
        writeGolden(goldenRoot, "expected line\n")

        val failure = assertFailsWith<AssertionError> {
            assertSnapshot(goldenRoot, "case", "build.gradle.kts", "actual line\n", update = false)
        }

        assertTrue("-expected line" in failure.message!!, failure.message!!)
        assertTrue("+actual line" in failure.message!!, failure.message!!)
    }

    @Test
    fun aMatchPasses() {
        val goldenRoot = Files.createTempDirectory("ktc-to-gradle-snapshot-match-")
        writeGolden(goldenRoot, "same\n")

        assertSnapshot(goldenRoot, "case", "build.gradle.kts", "same\n", update = false)
    }

    @Test
    fun aMissingBaselineFails() {
        val goldenRoot = Files.createTempDirectory("ktc-to-gradle-snapshot-missing-")

        val failure = assertFailsWith<AssertionError> {
            assertSnapshot(goldenRoot, "case", "build.gradle.kts", "anything\n", update = false)
        }

        assertTrue("Missing golden baseline" in failure.message!!, failure.message!!)
    }

    @Test
    fun theUpdateSwitchRewritesTheBaseline() {
        val goldenRoot = Files.createTempDirectory("ktc-to-gradle-snapshot-update-")
        writeGolden(goldenRoot, "stale\n")

        assertSnapshot(goldenRoot, "case", "build.gradle.kts", "fresh\n", update = true)

        assertEquals("fresh\n", goldenRoot.resolve("case/expected/build.gradle.kts").readText())
    }

    /** `gradlew.bat` is generated with CRLF endings, so the harness must not normalise them away. */
    @Test
    fun carriageReturnsAreASnapshotDifference() {
        val goldenRoot = Files.createTempDirectory("ktc-to-gradle-snapshot-crlf-")
        writeGolden(goldenRoot, "@echo off\r\n")

        assertSnapshot(goldenRoot, "case", "gradlew.bat", "@echo off\r\n", update = false)
        assertFailsWith<AssertionError> {
            assertSnapshot(goldenRoot, "case", "gradlew.bat", "@echo off\n", update = false)
        }
    }

    @Test
    fun theDiffKeepsUnchangedContextAroundAChange() {
        val diff = unifiedDiff("a\nb\nc\n", "a\nB\nc\n")

        assertTrue(" a" in diff, diff)
        assertTrue("-b" in diff, diff)
        assertTrue("+B" in diff, diff)
    }

    private fun writeGolden(goldenRoot: java.nio.file.Path, content: String) {
        val golden = goldenRoot.resolve("case/expected/build.gradle.kts")
        golden.parent.createDirectories()
        golden.writeText(content)
        val batch = goldenRoot.resolve("case/expected/gradlew.bat")
        if (content.contains("\r")) batch.writeText(content)
    }
}
