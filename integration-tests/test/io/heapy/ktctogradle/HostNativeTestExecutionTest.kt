package io.heapy.ktctogradle

import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals

class HostNativeTestExecutionTest {
    @Test
    fun commonTestsPassOnTheHostNativeTarget() {
        val hostTarget = hostNativeTarget() ?: return
        val destination = copyFixture("kmp-library")

        val moduleYaml = destination.resolve("module.yaml")
        Files.writeString(
            moduleYaml,
            Files.readString(moduleYaml).replace("platforms: [jvm, linuxX64]", "platforms: [jvm, $hostTarget]"),
        )

        val toolchainTests = buildWithKotlinToolchain(destination, "kmp-library@$hostTarget")

        Converter().convert(destination.absolutePathString().toPath())

        val processBuilder = ProcessBuilder("sh", destination.resolve("gradlew").toString(), "--no-daemon", "build")
            .directory(destination.toFile())
            .redirectErrorStream(true)
        processBuilder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "Host native tests for '$hostTarget' failed:\n$output")
        assertGradleRanEveryToolchainTest(destination, "kmp-library@$hostTarget", toolchainTests)
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
}
