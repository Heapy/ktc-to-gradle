package io.heapy.ktctogradle

import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The conversion carries no diagnostics between runs.
 *
 * Diagnostics used to live in a field that `generate()` cleared on entry; they are now collected
 * per run, so two runs in the same process must report each project on its own.
 */
class DiagnosticIsolationTest {
    @Test
    fun oneRunDoesNotCarryDiagnosticsIntoTheNext() {
        val warning = projectAt(
            "warning",
            """
            product: jvm/app
            """.trimIndent(),
        )
        val clean = projectAt(
            "clean",
            """
            product: jvm/lib
            """.trimIndent(),
        )

        val first = generateBuild(warning)
        val second = generateBuild(clean)

        assertTrue(
            first.diagnostics.any { "could not infer a main class" in it.message },
            "Expected the first project to report a missing main class, got ${first.diagnostics}",
        )
        assertEquals(emptyList<Diagnostic>(), second.diagnostics, "The second run must not see the first run's diagnostics")
    }

    @Test
    fun repeatingTheSameRunDoesNotAccumulateDiagnostics() {
        val project = projectAt(
            "repeat",
            """
            product: jvm/app
            """.trimIndent(),
        )

        val first = generateBuild(project)
        val second = generateBuild(project)

        assertTrue(first.diagnostics.isNotEmpty())
        assertEquals(first.diagnostics, second.diagnostics)
    }

    private fun projectAt(name: String, moduleYaml: String): ToolchainProject {
        val root = Files.createTempDirectory("ktc-to-gradle-diagnostics-").resolve(name)
        root.createDirectories()
        write(root.resolve("module.yaml"), moduleYaml)
        return ProjectLoader(FileSystem.SYSTEM).load(root.toString().toPath())
    }

    private fun write(path: Path, content: String) {
        path.parent.createDirectories()
        path.writeText(content)
    }
}
