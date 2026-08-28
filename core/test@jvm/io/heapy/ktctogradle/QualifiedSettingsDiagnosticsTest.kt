package io.heapy.ktctogradle

import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A malformed `settings@<qualifier>` section is reported and dropped, never thrown.
 *
 * The `qualified-settings` golden pins three of these messages; this pins the rest, above all the
 * section that is not an object at all. It is the guard on the load stage staying lenient: a binder
 * that validated eagerly would turn any of these into a `ConversionException` and lose the wording.
 */
class QualifiedSettingsDiagnosticsTest {
    @Test
    fun malformedQualifiedSectionsAreReportedWordForWordAndDropped() {
        val root = Files.createTempDirectory("ktc-to-gradle-qualified-").resolve("shared")
        root.createDirectories()
        root.resolve("module.yaml").writeText(
            """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64]

                settings@linuxX64: plain

                settings@macosArm64:
                  kotlin:
                    allWarningsAsErrors: true

                settings@jvm:
                  kotlin:
                    languageVersion: [2.2]
                    freeCompilerArgs: nonsense
                    unknown: true
                  jvm:
                    release: 21

                test-settings@jvm:
                  kotlin:
                    allWarningsAsErrors: true
            """.trimIndent(),
        )

        val diagnostics = Converter(FileSystem.SYSTEM).generateFiles(root.toString().toPath()).diagnostics

        assertEquals(
            listOf(
                "shared: 'settings@linuxX64' must be an object and was dropped",
                "shared: 'settings@macosArm64' names no platform of this module and was dropped",
                "shared: 'settings@jvm.kotlin.languageVersion' must be a string and was dropped",
                "shared: 'settings@jvm.kotlin.freeCompilerArgs' must be a list and was dropped",
                "shared: 'settings@jvm.kotlin.unknown' is not supported by the converter and was dropped",
                "shared: 'settings@jvm.jvm.release' is not supported by the converter and was dropped",
                "shared: 'test-settings@jvm.kotlin.allWarningsAsErrors' is not supported by the converter " +
                    "and was dropped",
            ),
            diagnostics.map { it.message },
        )
    }
}
