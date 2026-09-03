package io.heapy.ktctogradle

import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversionIntegrationTest {
    private val androidFixtures = setOf("android-app", "kmp-android", "android-junit-none")

    @Test
    fun fixturesBuildWithTheToolchainAndWithGradleAfterConversion() {
        for (fixture in listOf(
            "jvm-single",
            "jvm-multi",
            "mixed-modules",
            "kmp-library",
            "junit-none",
            "test-release",
            "compiler-plugin",
            "android-app",
            "android-junit-none",
            "kmp-android",
        )) {
            val destination = copyFixture(fixture)
            val androidSdkFound = fixture !in androidFixtures || configureAndroidSdk(destination)

            // The fixture is only evidence about the converter while the Kotlin Toolchain itself
            // builds it and runs its tests, so that is the first step and the reference set.
            val toolchainTests = if (androidSdkFound) buildWithKotlinToolchain(destination, fixture) else emptySet()

            val result = Converter().convert(destination.absolutePathString().toPath())
            assertTrue(result.writtenFiles.contains("settings.gradle.kts"))
            if (!isWindows) assertTrue(Files.isExecutable(destination.resolve("gradlew")))
            assertEquals(Versions.GRADLE, wrapperVersion(destination))

            if (!androidSdkFound) {
                println("Skipping the builds for '$fixture': no Android SDK found (set ANDROID_HOME).")
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
            // A generated build that compiles is not enough: the same flag written twice still
            // compiles, and warns once per compilation of every build the user ever runs.
            assertFalse(
                "is passed multiple times" in output,
                "Converted fixture '$fixture' passed a compiler argument twice:\n$output",
            )
            assertGradleRanEveryToolchainTest(destination, fixture, toolchainTests)
            if (fixture == "kmp-library") {
                assertJvmRelease(destination, expectedMajorVersion = 61)
            }
            if (fixture == "test-release") {
                // The test sources read a JDK 24 API the module's own release of 21 hides, so the
                // build only compiles when test-settings.jvm.release reached both test compilations.
                assertClassFileVersion(
                    destination.resolve("jvm-lib/build/classes/kotlin/main/example/testrelease/GreetingKt.class"),
                    65,
                )
                assertClassFileVersion(
                    destination.resolve("jvm-lib/build/classes/kotlin/test/example/testrelease/NewApiTest.class"),
                    69,
                )
            }
            if (fixture == "kmp-android") {
                assertTargetsAgreeOnBytecodeLevel(destination)
            }
        }
    }

    @Test
    fun aPublishingModuleStillPublishesAfterConversion() {
        val destination = convertedPublishingFixture()

        val output = gradle(destination, ":kmp-lib:publish", ":jvm-lib:generatePomFileForMavenPublication")
        assertEquals(0, output.exitCode, "Converted fixture 'publishing' failed to publish:\n${output.text}")

        // `artifactId` is the base name: the Kotlin Gradle Plugin appends the platform to it.
        val multiplatform = destination.resolve(
            "kmp-lib/build/repo/example/publishing/published-multiplatform-jvm/1.2.3",
        )
        assertTrue(
            Files.isRegularFile(multiplatform.resolve("published-multiplatform-jvm-1.2.3.jar")),
            "The multiplatform jvm artifact was never published under its base artifact id",
        )
        assertTrue(
            Files.notExists(destination.resolve("kmp-lib/build/repo/example/publishing/kmp-lib-jvm")),
            "The publication kept the Gradle project name instead of the declared artifactId",
        )
        // publishSources: false, against a plugin that publishes one sources jar per target.
        assertTrue(
            Files.notExists(multiplatform.resolve("published-multiplatform-jvm-1.2.3-sources.jar")),
            "publishSources: false did not stop the multiplatform sources jar",
        )

        val pom = Files.readString(destination.resolve("jvm-lib/build/publications/maven/pom-default.xml"))
        for (expected in listOf(
            "<artifactId>published-library</artifactId>",
            "<name>published-library</name>",
            "<description>A converted library that still publishes</description>",
            "<url>https://example.invalid/published-library</url>",
            "<name>The Apache License, Version 2.0</name>",
            "<id>example</id>",
            "<email>developer@example.invalid</email>",
            "<organization>Example Org</organization>",
            "<connection>scm:git:https://example.invalid/published-library.git</connection>",
        )) {
            assertTrue(expected in pom, "The generated POM is missing $expected:\n$pom")
        }
    }

    @Test
    fun signArtifactsWithoutAKeyFailsThePublishInsteadOfPublishingUnsigned() {
        val destination = convertedPublishingFixture()

        val output = gradle(destination, ":jvm-lib:publish")

        assertTrue(output.exitCode != 0, "The keyless publish succeeded:\n${output.text}")
        assertTrue(
            "signatory" in output.text,
            "The failure did not name the missing signing key:\n${output.text}",
        )
    }

    private fun convertedPublishingFixture(): Path {
        val destination = copyFixture("publishing")

        val toolchain = runKotlinToolchain(destination, "build")
        assertEquals(
            0,
            toolchain.exitCode,
            "Fixture 'publishing' does not build with the Kotlin Toolchain:\n${toolchain.text}",
        )

        val result = Converter().convert(destination.absolutePathString().toPath())

        assertEquals(
            listOf(
                "kmp-lib: settings.publishing.checksums md5, sha1, sha256 was dropped; Gradle writes " +
                    "its own set next to every artifact and offers no way to choose one",
            ),
            result.diagnostics.map(Diagnostic::message),
        )
        return destination
    }

    private data class GradleRun(val exitCode: Int, val text: String)

    private fun gradle(directory: Path, vararg tasks: String): GradleRun {
        val command = gradleCommand(directory).dropLast(1) + tasks
        val processBuilder = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
        processBuilder.environment()["JAVA_HOME"] = System.getProperty("java.home")
        processBuilder.environment().remove("KOTLIN_TOOLCHAIN_SIGNING_KEY")
        val process = processBuilder.start()
        val text = process.inputStream.bufferedReader().readText()
        return GradleRun(process.waitFor(), text)
    }

    @Test
    fun aProjectWithAPluginModuleConvertsAndBuildsWithoutIt() {
        val destination = copyFixture("plugin-module")
        val toolchainTests = buildWithKotlinToolchain(destination, "plugin-module")

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
        assertGradleRanEveryToolchainTest(destination, "plugin-module", toolchainTests)
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

    @Test
    fun aMainSourceReadingAnApiNewerThanItsReleaseFailsToCompile() {
        val destination = Files.createTempDirectory("ktc-to-gradle-main-release-")
        Files.writeString(
            destination.resolve("module.yaml"),
            """
            product: jvm/lib

            settings:
              kotlin:
                version: 2.4.10
              jvm:
                jdk:
                  version: 25
                release: 21
            """.trimIndent(),
        )
        destination.resolve("src").createDirectories()
        Files.writeString(
            destination.resolve("src/NewApi.kt"),
            """
            package example.mainrelease

            // java.lang.classfile is a JDK 24 API, and the module publishes class-file 65.
            fun classFile(): Any = java.lang.classfile.ClassFile.of()
            """.trimIndent(),
        )
        Converter().convert(destination.absolutePathString().toPath())

        val output = gradle(destination, "compileKotlin")

        assertTrue(
            output.exitCode != 0,
            "The main compilation accepted a JDK 24 API at release 21:\n${output.text}",
        )
        assertTrue(
            "Unresolved reference" in output.text,
            "The main compilation failed for some other reason than the API being out of reach:\n${output.text}",
        )
    }

    private fun assertJvmRelease(directory: Path, expectedMajorVersion: Int) {
        val classes = directory.resolve("build/classes/kotlin/jvm/main")
        val classFile = Files.walk(classes).use { paths ->
            paths.filter { it.fileName.toString() == "GreetingKt.class" }.findFirst().orElseThrow()
        }
        assertClassFileVersion(classFile, expectedMajorVersion)
    }

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

    private fun wrapperVersion(directory: Path): String {
        val properties = Files.readString(directory.resolve("gradle/wrapper/gradle-wrapper.properties"))
        return Regex("gradle-([0-9.]+)-bin\\.zip").find(properties)!!.groupValues[1]
    }
}
