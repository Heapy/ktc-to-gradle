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
    @Test
    fun fixturesBuildWithTheToolchainAndWithGradleAfterConversion() {
        for (fixture in FIXTURES) {
            val name = fixture.name
            val destination = copyFixture(name)
            val androidSdkFound = !fixture.android || configureAndroidSdk(destination)

            // The fixture is only evidence about the converter while the Kotlin Toolchain itself
            // builds it and runs its tests, so that is the first step and the reference set.
            val toolchainTests = if (androidSdkFound) buildWithKotlinToolchain(destination, name) else emptySet()

            val result = Converter().convert(destination.absolutePathString().toPath())
            assertTrue(result.writtenFiles.contains("settings.gradle.kts"))
            if (!isWindows) assertTrue(Files.isExecutable(destination.resolve("gradlew")))
            assertEquals(Versions.GRADLE, wrapperVersion(destination))

            if (!androidSdkFound) {
                println("Skipping the builds for '$name': no Android SDK found (set ANDROID_HOME).")
                continue
            }

            val environment = if (fixture.android) {
                val androidUserHome = destination.resolve(".android").also(Files::createDirectories)
                mapOf(
                    "ANDROID_USER_HOME" to androidUserHome.toString(),
                    "ANDROID_SDK_HOME" to destination.toString(),
                )
            } else {
                emptyMap()
            }
            val build = gradle(destination, "build", environment = environment)
            assertEquals(0, build.exitCode, "Converted fixture '$name' failed:\n${build.text}")
            // A generated build that compiles is not enough: the same flag written twice still
            // compiles, and warns once per compilation of every build the user ever runs.
            assertFalse(
                "is passed multiple times" in build.text,
                "Converted fixture '$name' passed a compiler argument twice:\n${build.text}",
            )
            assertGradleRanEveryToolchainTest(destination, name, toolchainTests)
            assertGradleJarsMatchToolchainJars(
                destination,
                name,
                kotlinModuleNamesDiffer = fixture.kotlinModuleNamesDiffer,
            )
            assertGradleRuntimeClasspathCoversToolchain(
                destination,
                name,
                environment = environment,
                allowedMissingDependencies = fixture.allowedMissingDependencies,
            )
            if (name == "kmp-library") {
                assertJvmRelease(destination, expectedMajorVersion = 61)
            }
            if (name == "test-release") {
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
            if (name == "kmp-android") {
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
    fun theGeneratedPomSaysWhatTheKotlinToolchainPomSays() {
        val destination = copyFixture("publishing")
        // The Kotlin Toolchain ships no transport for a file repository, so `publish` always stops
        // at the deploy step. The POM it prepared before that is the only copy of its publication.
        val prepared = runKotlinToolchain(destination, "publish", "localFile", "-m", "kmp-lib")
        val toolchainPom = destination.resolve(
            "build/tasks/_kmp-lib_prepareMavenPublishables/published-multiplatform-jvm.pom",
        )
        assertTrue(
            Files.isRegularFile(toolchainPom),
            "The Kotlin Toolchain prepared no POM for 'kmp-lib':\n${prepared.text}",
        )

        Converter().convert(destination.absolutePathString().toPath())
        val published = gradle(destination, ":kmp-lib:publish")
        assertEquals(0, published.exitCode, "Converted fixture 'publishing' failed to publish:\n${published.text}")

        val gradlePom = destination.resolve(
            "kmp-lib/build/repo/example/publishing/published-multiplatform-jvm/1.2.3/" +
                "published-multiplatform-jvm-1.2.3.pom",
        )
        assertEquals(
            // The Toolchain records a runtime dependency at `runtime` scope, while the Maven publish
            // plugin writes `compile` for the same dependency of the JVM target.
            canonicalPom(toolchainPom).map { it.replace("<scope>runtime</scope>", "<scope>compile</scope>") },
            canonicalPom(gradlePom),
            "The published POM says something else than the Kotlin Toolchain POM",
        )
    }

    // Both writers wrap and order the `project` attributes their own way and pad the Gradle metadata
    // comment differently, so only the elements below it carry a statement to compare.
    private fun canonicalPom(pom: Path): List<String> =
        Files.readString(pom)
            .replace(XML_DECLARATION, "")
            .replace(XML_COMMENT, "")
            .replace(PROJECT_TAG, "<project>")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()

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

        val build = gradle(destination, "build")
        assertEquals(0, build.exitCode, "Converted fixture 'plugin-module' failed:\n${build.text}")
        assertGradleRanEveryToolchainTest(destination, "plugin-module", toolchainTests)
        // The build plugin module is left out of the generated build on purpose, so the Kotlin
        // Toolchain jar and dependency graph it still has have no Gradle counterpart to match.
        assertGradleJarsMatchToolchainJars(
            destination,
            "plugin-module",
            skippedModules = setOf("greeting"),
            kotlinModuleNamesDiffer = true,
        )
        assertGradleRuntimeClasspathCoversToolchain(
            destination,
            "plugin-module",
            skippedModules = setOf("greeting"),
        )
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

        val help = gradle(destination, "help")
        assertEquals(0, help.exitCode, "Generated credential repository DSL failed:\n${help.text}")
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

    private fun wrapperVersion(directory: Path): String {
        val properties = Files.readString(directory.resolve("gradle/wrapper/gradle-wrapper.properties"))
        return Regex("gradle-([0-9.]+)-bin\\.zip").find(properties)!!.groupValues[1]
    }
}

private val XML_DECLARATION = Regex("""<\?xml[^>]*\?>""")

private val XML_COMMENT = Regex("""<!--[^>]*-->""")

private val PROJECT_TAG = Regex("""<project\b[^>]*>""")

private data class Fixture(
    val name: String,
    val android: Boolean = false,
    // The Kotlin Toolchain names the Kotlin module after the module, Gradle after the project path,
    // so a module that is not the root project writes a differently named `.kotlin_module` entry.
    val kotlinModuleNamesDiffer: Boolean = false,
    val allowedMissingDependencies: Set<String> = emptySet(),
)

// A common test source set asks for the `kotlin-test` annotations that only exist as a metadata
// artifact, and the Kotlin Gradle Plugin resolves them away on a platform runtime classpath.
private const val COMMON_TEST_ANNOTATIONS = "org.jetbrains.kotlin:kotlin-test-annotations-common"

private val FIXTURES = listOf(
    Fixture("jvm-single"),
    Fixture("jvm-multi", kotlinModuleNamesDiffer = true),
    Fixture(
        "mixed-modules",
        kotlinModuleNamesDiffer = true,
        // `libs/core` declares annotations 26.0.2 as compile-only. The Kotlin Toolchain still lets
        // that version win in the runtime graph; a Gradle `compileOnly` constrains nothing there.
        allowedMissingDependencies = setOf(COMMON_TEST_ANNOTATIONS, "org.jetbrains:annotations:26.0.2"),
    ),
    Fixture("kmp-library", allowedMissingDependencies = setOf(COMMON_TEST_ANNOTATIONS)),
    Fixture(
        "junit-none",
        kotlinModuleNamesDiffer = true,
        allowedMissingDependencies = setOf(COMMON_TEST_ANNOTATIONS),
    ),
    Fixture(
        "test-release",
        kotlinModuleNamesDiffer = true,
        allowedMissingDependencies = setOf(COMMON_TEST_ANNOTATIONS),
    ),
    Fixture("compiler-plugin"),
    Fixture("android-app", android = true),
    Fixture("android-junit-none", android = true),
    Fixture(
        "kmp-android",
        android = true,
        // `test-dependencies@android` asks for Jupiter 5.14.1. The Kotlin Toolchain lets that
        // version reach the JVM test scope too; the Gradle targets resolve one classpath each, so
        // the JVM tests keep the 5.10.1 that kotlin-test-junit5 brings.
        allowedMissingDependencies = setOf(
            COMMON_TEST_ANNOTATIONS,
            "org.junit:junit-bom:5.14.1",
            "org.junit.jupiter:junit-jupiter-api:5.14.1",
            "org.junit.jupiter:junit-jupiter-engine:5.14.1",
            "org.junit.platform:junit-platform-commons:1.14.1",
            "org.junit.platform:junit-platform-engine:1.14.1",
            "org.junit.platform:junit-platform-launcher:1.14.1",
        ),
    ),
)
