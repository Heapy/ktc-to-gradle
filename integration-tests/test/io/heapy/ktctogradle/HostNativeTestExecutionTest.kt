package io.heapy.ktctogradle

import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The kmp-library fixture declares linuxX64, so only a Linux host executes its native tests.
 * On macOS this test re-points the fixture at the host native target, which keeps commonTest
 * assertions honest for every developer instead of only for CI.
 */
class HostNativeTestExecutionTest {
    @Test
    fun commonTestsPassOnTheHostNativeTarget() {
        val hostTarget = hostNativeTarget() ?: return
        val source = projectRoot().resolve("integration-tests/fixtures/kmp-library")
        val destination = Files.createTempDirectory("ktc-to-gradle-host-native-")
        copyRecursively(source, destination)

        val moduleYaml = destination.resolve("module.yaml")
        Files.writeString(
            moduleYaml,
            Files.readString(moduleYaml).replace("platforms: [jvm, linuxX64]", "platforms: [jvm, $hostTarget]"),
        )

        Converter().convert(destination.absolutePathString().toPath())

        val processBuilder = ProcessBuilder("sh", destination.resolve("gradlew").toString(), "--no-daemon", "build")
            .directory(destination.toFile())
            .redirectErrorStream(true)
        processBuilder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "Host native tests for '$hostTarget' failed:\n$output")
    }

    private fun hostNativeTarget(): String? {
        val os = System.getProperty("os.name").orEmpty()
        val arch = System.getProperty("os.arch").orEmpty()
        return when {
            !os.startsWith("Mac", ignoreCase = true) -> null
            arch == "aarch64" -> "macosArm64"
            else -> "macosX64"
        }
    }

    private fun projectRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (true) {
            if (
                Files.isRegularFile(current.resolve("project.yaml")) &&
                Files.isDirectory(current.resolve("integration-tests/fixtures"))
            ) {
                return current
            }
            current = current.parent
                ?: error("Could not locate the ktc-to-gradle project root from ${Path.of("").toAbsolutePath()}")
        }
    }

    private fun copyRecursively(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val target = destination.resolve(source.relativize(path).toString())
                if (path.isDirectory()) target.createDirectories()
                else {
                    target.parent.createDirectories()
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
