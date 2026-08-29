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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversionIntegrationTest {
    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private val androidFixtures = setOf("android-app", "kmp-android", "android-junit-none")

    @Test
    fun convertedJvmFixturesBuildWithPinnedGradle() {
        for (fixture in listOf(
            "jvm-single",
            "jvm-multi",
            "kmp-library",
            "junit-none",
            "test-release",
            "compiler-plugin",
            "android-app",
            "android-junit-none",
            "kmp-android",
        )) {
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
            // A generated build that compiles is not enough: the same flag written twice still
            // compiles, and warns once per compilation of every build the user ever runs.
            assertFalse(
                "is passed multiple times" in output,
                "Converted fixture '$fixture' passed a compiler argument twice:\n$output",
            )
            if (fixture == "kmp-library") {
                assertJvmRelease(destination, expectedMajorVersion = 61)
                assertUnitTestsRan(destination, "jvmTest", "example.multiplatform.JupiterOnlyTest")
            }
            if (fixture == "android-app") {
                assertUnitTestsRan(destination, "testDebugUnitTest", "example.android.PayloadTest")
            }
            // `settings.junit: none` adds no JUnit adapter and still runs the JUnit platform, so
            // both modules discover a suite written against the engine they brought themselves.
            if (fixture == "junit-none") {
                assertUnitTestsRan(destination.resolve("jvm-lib"), "test", "example.junitnone.JupiterOnlyTest")
                assertUnitTestsRan(
                    destination.resolve("kmp-lib"),
                    "jvmTest",
                    "example.junitnone.multiplatform.JupiterOnlyTest",
                )
                // The junit-5 module of the same project: one root gradle.properties serves both,
                // and the adapter still reaches the module that did not bring an engine of its own.
                assertUnitTestsRan(destination.resolve("junit5-lib"), "test", "example.junit5.KotlinTestTest")
            }
            // The test sources read a JDK 24 API the module's own release of 21 hides, so the build
            // only compiles when test-settings.jvm.release reached both test compilations.
            if (fixture == "test-release") {
                assertUnitTestsRan(
                    destination.resolve("jvm-lib"),
                    "test",
                    "example.testrelease.NewApiTest",
                )
                assertUnitTestsRan(
                    destination.resolve("kmp-lib"),
                    "jvmTest",
                    "example.testrelease.multiplatform.NewApiTest",
                )
                assertClassFileVersion(
                    destination.resolve("jvm-lib/build/classes/kotlin/main/example/testrelease/GreetingKt.class"),
                    65,
                )
                assertClassFileVersion(
                    destination.resolve("jvm-lib/build/classes/kotlin/test/example/testrelease/NewApiTest.class"),
                    69,
                )
            }
            if (fixture == "android-junit-none") {
                assertUnitTestsRan(destination, "testDebugUnitTest", "example.androidjunitnone.JupiterOnlyTest")
            }
            if (fixture == "kmp-android") {
                assertAndroidUnitTestsRan(destination)
                assertTargetsAgreeOnBytecodeLevel(destination)
            }
        }
    }

    /**
     * `settings.publishing` reaches the generated build, and what has no Gradle equivalent is named.
     *
     * The converted project publishes to a repository inside its own build directory, so the chain
     * is asserted from the artifacts rather than from the script: the POM Gradle wrote is the only
     * proof that every field of `settings.publishing.pom` survived, and the published file names are
     * the only proof that `artifactId` reached a multiplatform module's per-target publications.
     */
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

    /**
     * `signArtifacts: true` with no key in the environment fails the publish rather than publishing
     * unsigned, which is what the Kotlin Toolchain does.
     *
     * The `signing { }` block guards only the key lookup, so the `sign` call is reached either way
     * and Gradle refuses the task for want of a signatory. A build that quietly shipped unsigned
     * artifacts after the module asked for signatures is the failure this pins shut.
     */
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
        val source = projectRoot().resolve("integration-tests/fixtures/publishing")
        val destination = Files.createTempDirectory("ktc-to-gradle-publishing-")
        copyRecursively(source, destination)

        val result = Converter().convert(destination.absolutePathString().toPath())

        assertEquals(
            listOf(
                "kmp-lib: settings.publishing.mavenCentral has no Gradle equivalent (publishingMode " +
                    "'manual' included); the generated build publishes to the repositories it declares " +
                    "and uploads no Central Portal bundle",
                "kmp-lib: settings.publishing.mavenCentral is enabled, and Maven Central refuses a " +
                    "publication missing settings.publishing.signArtifacts, " +
                    "settings.publishing.publishSources, settings.publishing.pom.description, " +
                    "settings.publishing.pom.licenses, settings.publishing.pom.developers; the Kotlin " +
                    "Toolchain checks the same requirements before it uploads",
                "kmp-lib: settings.publishing.mavenCentral is enabled, and Maven Central refuses a " +
                    "publication without a javadoc jar; the generated build has none, because the " +
                    "Kotlin Gradle Plugin builds no javadoc per target and the 'withJavadocJar()' a " +
                    "jvm/lib gets has no multiplatform equivalent",
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

    /**
     * `settings.jvm.release` has to reach the main compilation as a `--release`, not only as a
     * bytecode level.
     *
     * A JDK 25 toolchain told to emit class-file 65 still resolves the whole JDK 25 API unless the
     * compiler is given `-Xjdk-release`, so a module could call an API that is not there at run time
     * and publish an artifact labelled Java 21 that fails on a real Java 21. The only source here
     * reads a JDK 24 API, so the build has to refuse it — and refuse it for that reason and not
     * another, which is what the second assertion is for.
     *
     * The module names no `test-settings.jvm.release`, so this is the module-wide spelling of the
     * flag; the `test-release` fixture covers the per-compilation one.
     */
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
    private fun assertAndroidUnitTestsRan(directory: Path) {
        assertUnitTestsRan(directory, "testAndroidHostTest", "io.heapy.ktctogradle.fixture.AndroidOnlyTest")
        // Annotated with Jupiter rather than kotlin.test, so it is discovered only when the task
        // actually runs the JUnit platform. A kotlin.test class runs under JUnit 4 just as happily.
        assertUnitTestsRan(directory, "testAndroidHostTest", "io.heapy.ktctogradle.fixture.JupiterOnlyTest")
    }

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
