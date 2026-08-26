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
import kotlin.test.assertTrue

class ConversionIntegrationTest {
    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    @Test
    fun convertedJvmFixturesBuildWithPinnedGradle() {
        for (fixture in listOf("jvm-single", "jvm-multi", "kmp-library")) {
            val source = projectRoot().resolve("integration-tests/fixtures/$fixture")
            val destination = Files.createTempDirectory("ktc-to-gradle-$fixture-")
            copyRecursively(source, destination)

            val result = Converter().convert(destination.absolutePathString().toPath())
            assertTrue(result.writtenFiles.contains("settings.gradle.kts"))
            if (!isWindows) assertTrue(Files.isExecutable(destination.resolve("gradlew")))
            assertEquals(Versions.GRADLE, wrapperVersion(destination))

            val processBuilder = ProcessBuilder(gradleCommand(destination))
                .directory(destination.toFile())
                .redirectErrorStream(true)
            processBuilder.environment()["JAVA_HOME"] = System.getProperty("java.home")
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            assertEquals(0, exitCode, "Converted fixture '$fixture' failed:\n$output")
        }
    }

    private fun gradleCommand(directory: Path): List<String> =
        if (isWindows) {
            listOf("cmd", "/c", directory.resolve("gradlew.bat").toString(), "--no-daemon", "--stacktrace", "build")
        } else {
            listOf("sh", directory.resolve("gradlew").toString(), "--no-daemon", "--stacktrace", "build")
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

    private fun wrapperVersion(directory: Path): String {
        val properties = Files.readString(directory.resolve("gradle/wrapper/gradle-wrapper.properties"))
        return Regex("gradle-([0-9.]+)-bin\\.zip").find(properties)!!.groupValues[1]
    }

    private fun copyRecursively(source: Path, destination: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val relative = source.relativize(path)
                val target = destination.resolve(relative.toString())
                if (path.isDirectory()) target.createDirectories()
                else {
                    target.parent.createDirectories()
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
