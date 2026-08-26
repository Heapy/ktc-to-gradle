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
        for (fixture in listOf("jvm-single", "jvm-multi", "kmp-library", "android-app")) {
            val source = projectRoot().resolve("integration-tests/fixtures/$fixture")
            val destination = Files.createTempDirectory("ktc-to-gradle-$fixture-")
            copyRecursively(source, destination)

            val result = Converter().convert(destination.absolutePathString().toPath())
            assertTrue(result.writtenFiles.contains("settings.gradle.kts"))
            if (!isWindows) assertTrue(Files.isExecutable(destination.resolve("gradlew")))
            assertEquals(Versions.GRADLE, wrapperVersion(destination))

            if (fixture == "android-app") configureAndroidSdk(destination)

            val processBuilder = ProcessBuilder(gradleCommand(destination))
                .directory(destination.toFile())
                .redirectErrorStream(true)
            processBuilder.environment()["JAVA_HOME"] = System.getProperty("java.home")
            if (fixture == "android-app") {
                val androidUserHome = destination.resolve(".android").also(Files::createDirectories)
                processBuilder.environment()["ANDROID_USER_HOME"] = androidUserHome.toString()
                processBuilder.environment()["ANDROID_SDK_HOME"] = destination.toString()
            }
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            assertEquals(0, exitCode, "Converted fixture '$fixture' failed:\n$output")
            if (fixture == "kmp-library") assertJvmRelease(destination, expectedMajorVersion = 61)
        }
    }

    @Test
    fun generatedCredentialRepositoryDslConfigures() {
        val destination = Files.createTempDirectory("ktc-to-gradle-repository-dsl-")
        Files.writeString(
            destination.resolve("module.yaml"),
            """
            product: jvm/lib
            repositories:
              - id: private
                url: https://repo.example.invalid/maven
                credentials:
                  file: credentials.properties
                  usernameKey: repository.username
                  passwordKey: repository.password
            """.trimIndent(),
        )
        Files.writeString(
            destination.resolve("credentials.properties"),
            "repository.username=user\nrepository.password=secret\n",
        )
        Converter().convert(destination.absolutePathString().toPath())

        val processBuilder = ProcessBuilder(gradleCommand(destination, "help"))
            .directory(destination.toFile())
            .redirectErrorStream(true)
        processBuilder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "Generated credential repository DSL failed:\n$output")
    }

    private fun assertJvmRelease(directory: Path, expectedMajorVersion: Int) {
        val classes = directory.resolve("build/classes/kotlin/jvm/main")
        val classFile = Files.walk(classes).use { paths ->
            paths.filter { it.fileName.toString() == "GreetingKt.class" }.findFirst().orElseThrow()
        }
        val bytes = Files.readAllBytes(classFile)
        val majorVersion = (bytes[6].toInt() and 0xff) shl 8 or (bytes[7].toInt() and 0xff)
        assertEquals(expectedMajorVersion, majorVersion, "Unexpected JVM class-file version in $classFile")
    }

    private fun configureAndroidSdk(directory: Path) {
        val sdk = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            Path.of(System.getProperty("user.home"), "Library/Android/sdk").toString(),
            Path.of(System.getProperty("user.home"), "Android/Sdk").toString(),
        ).map(Path::of).firstOrNull(Files::isDirectory) ?: return
        Files.writeString(directory.resolve("local.properties"), "sdk.dir=${sdk.toString().replace("\\", "\\\\")}\n")
    }

    private fun gradleCommand(directory: Path, task: String = "build"): List<String> =
        if (isWindows) {
            listOf("cmd", "/c", directory.resolve("gradlew.bat").toString(), "--no-daemon", "--stacktrace", task)
        } else {
            listOf("sh", directory.resolve("gradlew").toString(), "--no-daemon", "--stacktrace", task)
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
