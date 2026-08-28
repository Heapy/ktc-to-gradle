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
    private val androidFixtures = setOf("android-app", "kmp-android")

    @Test
    fun convertedJvmFixturesBuildWithPinnedGradle() {
        for (fixture in listOf("jvm-single", "jvm-multi", "kmp-library", "compiler-plugin", "android-app", "kmp-android")) {
            val source = projectRoot().resolve("integration-tests/fixtures/$fixture")
            val destination = Files.createTempDirectory("ktc-to-gradle-$fixture-")
            copyRecursively(source, destination)

            val result = Converter().convert(destination.absolutePathString().toPath())
            assertTrue(result.writtenFiles.contains("settings.gradle.kts"))
            if (!isWindows) assertTrue(Files.isExecutable(destination.resolve("gradlew")))
            assertEquals(Versions.GRADLE, wrapperVersion(destination))

            if (fixture in androidFixtures && !configureAndroidSdk(destination)) {
                println("Skipping the Gradle build for '$fixture': no Android SDK found (set ANDROID_HOME).")
                continue
            }

            val processBuilder = ProcessBuilder(gradleCommand(destination))
                .directory(destination.toFile())
                .redirectErrorStream(true)
            processBuilder.environment()["JAVA_HOME"] = System.getProperty("java.home")
            if (fixture in androidFixtures) {
                val androidUserHome = destination.resolve(".android").also(Files::createDirectories)
                processBuilder.environment()["ANDROID_USER_HOME"] = androidUserHome.toString()
                processBuilder.environment()["ANDROID_SDK_HOME"] = destination.toString()
            }
            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            assertEquals(0, exitCode, "Converted fixture '$fixture' failed:\n$output")
            if (fixture == "kmp-library") assertJvmRelease(destination, expectedMajorVersion = 61)
            if (fixture == "android-app") {
                assertUnitTestsRan(destination, "testDebugUnitTest", "example.android.PayloadTest")
            }
            if (fixture == "kmp-android") {
                assertAndroidUnitTestsRan(destination)
                assertTargetsAgreeOnBytecodeLevel(destination)
            }
        }
    }

    /**
     * A `jvm/amper-plugin` module used to abort the whole run, so a project carrying one got no
     * files at all. It is now left out and named, and the rest of the project still builds.
     */
    @Test
    fun aProjectWithAPluginModuleConvertsAndBuildsWithoutIt() {
        val source = projectRoot().resolve("integration-tests/fixtures/plugin-module")
        val destination = Files.createTempDirectory("ktc-to-gradle-plugin-module-")
        copyRecursively(source, destination)

        val result = Converter().convert(destination.absolutePathString().toPath())

        assertEquals(
            listOf(
                "app: 'plugins' cannot be converted automatically; " +
                    "the section was dropped and needs a hand-written Gradle equivalent",
                "build-logic/greeting: Kotlin Toolchain build plugins have no automatic Gradle equivalent; " +
                    "the module was left out of the generated build",
            ),
            result.diagnostics.filter { it.severity == Diagnostic.Severity.ERROR }.map(Diagnostic::message),
        )
        assertTrue(
            Files.notExists(destination.resolve("build-logic/greeting/build.gradle.kts")),
            "The skipped module must not get a build script",
        )
        assertTrue(result.writtenFiles.contains("app/build.gradle.kts"))
        assertTrue(result.writtenFiles.contains("libs/messages/build.gradle.kts"))

        val processBuilder = ProcessBuilder(gradleCommand(destination))
            .directory(destination.toFile())
            .redirectErrorStream(true)
        processBuilder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "Converted fixture 'plugin-module' failed:\n$output")
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
        assertClassFileVersion(classFile, expectedMajorVersion)
    }

    /**
     * `settings.jvm.release: 21` is class-file 65, and the fixture pins it below the toolchain JDK.
     *
     * The `androidLibrary` target used to ignore the setting and fall back to whatever the Android
     * Gradle Plugin defaulted to, so one module published two different bytecode levels.
     */
    private fun assertTargetsAgreeOnBytecodeLevel(directory: Path) {
        val classes = directory.resolve("build/classes/kotlin")
        val fixture = "io/heapy/ktctogradle/fixture"
        assertClassFileVersion(classes.resolve("jvm/main/$fixture/PlatformKt.class"), 65)
        assertClassFileVersion(classes.resolve("android/main/$fixture/PlatformKt.class"), 65)
        assertClassFileVersion(classes.resolve("android/hostTest/$fixture/AndroidOnlyTest.class"), 65)
    }

    private fun assertClassFileVersion(classFile: Path, expectedMajorVersion: Int) {
        assertTrue(Files.isRegularFile(classFile), "No class file at $classFile")
        val bytes = Files.readAllBytes(classFile)
        val majorVersion = (bytes[6].toInt() and 0xff) shl 8 or (bytes[7].toInt() and 0xff)
        assertEquals(expectedMajorVersion, majorVersion, "Unexpected JVM class-file version in $classFile")
    }

    /**
     * Android unit tests run from androidHostTest. A test left in androidTest compiles and
     * the build still passes, so assert the report exists instead of trusting the exit code.
     */
    private fun assertAndroidUnitTestsRan(directory: Path) =
        assertUnitTestsRan(directory, "testAndroidHostTest", "io.heapy.ktctogradle.fixture.AndroidOnlyTest")

    /**
     * The JUnit report of one test class, read where the named task writes it.
     *
     * The counts are what make this an oracle rather than a file check: a suite the runner never
     * discovered still leaves no report, and a report with `tests="0"` is the same silence.
     */
    private fun assertUnitTestsRan(directory: Path, task: String, testClass: String) {
        val report = directory.resolve("build/test-results/$task/TEST-$testClass.xml")
        assertTrue(Files.isRegularFile(report), "$report was never written, so those unit tests never ran")
        val summary = Files.readString(report).substringAfter("<testsuite").substringBefore(">")
        assertTrue(
            Regex("""tests="([1-9]\d*)"""").containsMatchIn(summary),
            "No test of $testClass executed: $summary",
        )
        assertTrue(
            """failures="0"""" in summary && """errors="0"""" in summary,
            "$testClass did not pass: $summary",
        )
    }

    private fun configureAndroidSdk(directory: Path): Boolean {
        val sdk = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            Path.of(System.getProperty("user.home"), "Library/Android/sdk").toString(),
            Path.of(System.getProperty("user.home"), "Android/Sdk").toString(),
        ).map(Path::of).firstOrNull(Files::isDirectory) ?: return false
        Files.writeString(directory.resolve("local.properties"), "sdk.dir=${sdk.toString().replace("\\", "\\\\")}\n")
        return true
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
