package io.heapy.ktctogradle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

internal data class ProcessRun(val exitCode: Int, val text: String)

internal fun projectRoot(): Path {
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

internal fun copyFixture(fixture: String): Path {
    val source = projectRoot().resolve("integration-tests/fixtures/$fixture")
    val destination = Files.createTempDirectory("ktc-to-gradle-$fixture-")
    Files.walk(source).use { paths ->
        paths.forEach { path ->
            val target = destination.resolve(source.relativize(path).toString())
            if (path.isDirectory()) {
                target.createDirectories()
            } else {
                target.parent.createDirectories()
                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
    return destination
}

internal fun runKotlinToolchain(project: Path, vararg arguments: String): ProcessRun {
    val root = projectRoot()
    val command = if (isWindows) {
        listOf("cmd", "/c", root.resolve("kotlin.bat").toString())
    } else {
        listOf("sh", root.resolve("kotlin").toString())
    } + arguments + listOf("--project-dir", project.toString())
    // The wrapper picks its distribution from the wrapper script beside the project.yaml it finds
    // above the working directory, so run it from the checkout that owns it, not from the copy.
    val builder = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true)
    builder.environment()["KOTLIN_CLI_NO_WELCOME_BANNER"] = "1"
    val process = builder.start()
    val text = process.inputStream.bufferedReader().readText()
    return ProcessRun(process.waitFor(), text)
}

internal fun buildWithKotlinToolchain(project: Path, fixture: String): Set<String> {
    val run = runKotlinToolchain(project, "test")
    assertEquals(0, run.exitCode, "Fixture '$fixture' does not build with the Kotlin Toolchain:\n${run.text}")
    // The Toolchain writes JUnit XML for its JVM and Android tests only, so a native or web test it
    // ran is covered by the exit code above and not by the returned set.
    return testClassesIn(project.resolve("build/reports"))
}

internal fun assertGradleRanEveryToolchainTest(project: Path, fixture: String, toolchainTests: Set<String>) {
    assertTrue(
        toolchainTests.isNotEmpty(),
        "The Kotlin Toolchain ran no test of fixture '$fixture', so nothing constrains the Gradle build",
    )
    val missing = toolchainTests - gradleTestClasses(project)
    assertEquals(
        emptySet(),
        missing,
        "The converted fixture '$fixture' never ran tests the Kotlin Toolchain runs: $missing",
    )
}

private fun gradleTestClasses(project: Path): Set<String> =
    Files.walk(project).use { paths ->
        paths.filter { it.isDirectory() && it.fileName.toString() == "test-results" }
            .toList()
    }.flatMapTo(mutableSetOf(), ::testClassesIn)

private fun testClassesIn(reports: Path): Set<String> {
    if (!Files.isDirectory(reports)) return emptySet()
    val files = Files.walk(reports).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().matches(REPORT_FILE) }.toList()
    }
    return files.flatMapTo(mutableSetOf()) { file ->
        CLASS_NAME.findAll(Files.readString(file)).map { it.groupValues[1] }
    }
}

private val REPORT_FILE = Regex("""TEST-.+\.xml""")

private val CLASS_NAME = Regex("""classname="([^"]+)"""")
