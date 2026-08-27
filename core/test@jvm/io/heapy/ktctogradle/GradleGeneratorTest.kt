package io.heapy.ktctogradle

import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path as OkioPath
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GradleGeneratorTest {
    @Test
    fun androidUsesAgpBuiltInKotlin() {
        val build = generate(
            """
            product: android/app
            settings:
              android:
                namespace: example.android
              jvm:
                release: 17
              kotlin:
                serialization: json
            """.trimIndent(),
        ).buildFile()

        assertFalse("kotlin(\"android\")" in build)
        assertTrue("kotlin(\"plugin.serialization\")" in build)
        assertTrue("kotlin.srcDirs(\"src\", \"src@android\")" in build)
        assertTrue("sourceCompatibility = JavaVersion.toVersion(\"17\")" in build)
    }

    @Test
    fun kmpWiresNaturalAndCustomHierarchyAndJvmRelease() {
        val build = generate(
            """
            product:
              type: kmp/lib
              platforms: [jvm, linuxX64]
            aliases:
              - desktop: [jvm, linuxX64]
            dependencies@native:
              - org.example:native-only:1.0
            dependencies@desktop:
              - org.example:desktop:1.0
            settings:
              jvm:
                jdk:
                  version: 25
                release: 17
            """.trimIndent(),
            "src@native/Native.kt" to "package example\nval nativeSource = true\n",
            "resources@linux/platform.txt" to "linux\n",
            "src@desktop/Desktop.kt" to "package example\nval desktopSource = true\n",
        ).buildFile()

        assertTrue("jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(\"17\"))" in build)
        assertTrue("freeCompilerArgs.add(\"-Xjdk-release=17\")" in build)
        assertTrue("maybeCreate(\"nativeMain\")" in build)
        assertTrue("kotlin.srcDir(\"src@native\")" in build)
        assertTrue("maybeCreate(\"linuxMain\")" in build)
        assertTrue("dependsOn(getByName(\"nativeMain\"))" in build)
        assertTrue("maybeCreate(\"desktopMain\")" in build)
        assertTrue("kotlin.srcDir(\"src@desktop\")" in build)
        assertTrue("implementation(\"org.example:native-only:1.0\")" in build)
        assertTrue("implementation(\"org.example:desktop:1.0\")" in build)
    }

    @Test
    fun serializationUsesConfiguredRuntimeAndExactArtifacts() {
        val build = generate(
            """
            product: jvm/lib
            settings:
              kotlin:
                serialization:
                  enabled: true
                  version: 1.9.0
                  format: json-io
            dependencies:
              - ${'$'}kotlin.serialization.json-okio
            """.trimIndent(),
        ).buildFile()

        assertTrue("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0" in build)
        assertTrue("org.jetbrains.kotlinx:kotlinx-serialization-json-io:1.9.0" in build)
        assertTrue("org.jetbrains.kotlinx:kotlinx-serialization-json-okio:1.9.0" in build)
        assertFalse("org.jetbrains.kotlin:kotlin-serialization" in build)
    }

    @Test
    fun enabledSerializationAddsCoreWithoutGuessingAFormat() {
        val build = generate(
            """
            product: jvm/lib
            settings:
              kotlin:
                serialization: enabled
            """.trimIndent(),
        ).buildFile()

        assertTrue("kotlinx-serialization-core:1.11.0" in build)
        assertFalse("kotlinx-serialization-json:1.11.0" in build)
    }

    @Test
    fun repositoriesHonorOverridesDisablementAndCredentialOrigin() {
        val root = Files.createTempDirectory("ktc-to-gradle-repositories-")
        write(root.resolve("project.yaml"), "modules: [app]\n")
        write(
            root.resolve("templates/private.module-template.yaml"),
            """
            repositories:
              - id: mavenCentral
                url: https://mirror.example/maven
                credentials:
                  file: credentials.properties
                  usernameKey: mirror.username
                  passwordKey: mirror.password
              - id: mavenGoogle
                url: https://maven.google.com
                resolve: false
            """.trimIndent(),
        )
        write(root.resolve("templates/credentials.properties"), "mirror.username=user\nmirror.password=secret\n")
        write(
            root.resolve("app/module.yaml"),
            """
            product: jvm/lib
            apply:
              - //templates/private.module-template.yaml
            """.trimIndent(),
        )

        val build = generate(root).first { it.path.name == "build.gradle.kts" && it.path.parent?.name == "app" }.content
        assertFalse("mavenCentral()" in build)
        assertFalse("google()" in build)
        assertTrue("name = \"mavenCentral\"" in build)
        assertTrue("url = uri(\"https://mirror.example/maven\")" in build)
        assertTrue("file(\"../templates/credentials.properties\")" in build)
        assertTrue("getProperty(\"mirror.username\")" in build)
        assertTrue("getProperty(\"mirror.password\")" in build)
    }

    @Test
    fun mainClassDetectionDoesNotDependOnDirectoryOrder() {
        val root = Files.createTempDirectory("ktc-to-gradle-main-class-")
        write(root.resolve("module.yaml"), "product: jvm/app\n")
        write(root.resolve("src/zzz/main.kt"), "package zzz\n\nfun main() {}\n")
        write(root.resolve("src/aaa/main.kt"), "package aaa\n\nfun main() {}\n")
        write(root.resolve("src/mmm/main.kt"), "package mmm\n\nfun main() {}\n")

        for (fileSystem in listOf(FileSystem.SYSTEM, ReversedListingFileSystem(FileSystem.SYSTEM))) {
            val project = ProjectLoader(fileSystem).load(root.toString().toPath())
            val build = GradleGenerator().generate(project).files.buildFile()
            assertTrue(
                "mainClass.set(\"aaa.MainKt\")" in build,
                "Main class depends on the directory listing order:\n$build",
            )
        }
    }

    private class ReversedListingFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
        override fun list(dir: OkioPath): List<OkioPath> = super.list(dir).reversed()
    }

    @Test
    fun androidReportsAnIgnoredKotlinVersionPin() {
        val diagnostics = generateAll(
            """
            product: android/app
            settings:
              android:
                namespace: example.android
              kotlin:
                version: 2.4.10
            """.trimIndent(),
        ).second

        assertTrue(
            diagnostics.any { "settings.kotlin.version" in it.message },
            "Expected a diagnostic about the ignored Kotlin version pin, got $diagnostics",
        )
    }

    @Test
    fun jvmModulesDoNotReportAnIgnoredKotlinVersionPin() {
        val diagnostics = generateAll(
            """
            product: jvm/lib
            settings:
              kotlin:
                version: 2.4.10
            """.trimIndent(),
        ).second

        assertFalse(diagnostics.any { "settings.kotlin.version" in it.message })
    }

    @Test
    fun defaultRepositoriesWrittenAsUrlsAreNotDuplicated() {
        val build = generate(
            """
            product: jvm/lib
            repositories:
              - https://repo1.maven.org/maven2
              - url: https://maven.google.com
            """.trimIndent(),
        ).buildFile()

        assertTrue("mavenCentral()" in build)
        assertTrue("google()" in build)
        assertFalse("url = uri(\"https://repo1.maven.org/maven2\")" in build)
        assertFalse("url = uri(\"https://maven.google.com\")" in build)
    }

    @Test
    fun aDefaultRepositoryIsDisabledByItsUrlToo() {
        val build = generate(
            """
            product: jvm/lib
            repositories:
              - url: https://repo1.maven.org/maven2
                resolve: false
            """.trimIndent(),
        ).buildFile()

        assertFalse("mavenCentral()" in build)
        assertTrue("google()" in build)
    }

    @Test
    fun testSettingsOverrideBaseJvmTestSettings() {
        val build = generate(
            """
            product: jvm/lib
            settings:
              jvm:
                test:
                  freeJvmArgs: [-Dbase=true]
                  systemProperties:
                    shared: base
                    baseOnly: present
                  extraEnvironment:
                    SHARED: base
            test-settings:
              jvm:
                freeJvmArgs: [-Dtest=true]
                systemProperties:
                  shared: test
                  testOnly: present
                extraEnvironment:
                  SHARED: test
                  TEST_ONLY: present
            """.trimIndent(),
        ).buildFile()

        assertTrue("jvmArgs(\"-Dbase=true\", \"-Dtest=true\")" in build)
        assertTrue("systemProperty(\"shared\", \"test\")" in build)
        assertTrue("systemProperty(\"baseOnly\", \"present\")" in build)
        assertTrue("systemProperty(\"testOnly\", \"present\")" in build)
        assertTrue("environment(\"SHARED\", \"test\")" in build)
        assertTrue("environment(\"TEST_ONLY\", \"present\")" in build)
    }

    @Test
    fun scalarDependencyScopesSurviveTheSequenceForm() {
        val build = generate(
            """
            product: jvm/lib
            dependencies:
              - com.squareup.okio:okio:3.17.0: exported
              - org.example:compile:1.0: compile-only
              - org.example:runtime:1.0: runtime-only
              - org.example:plain:1.0: all
            test-dependencies:
              - org.example:test-exported:1.0: exported
            """.trimIndent(),
        ).buildFile()

        assertTrue("api(\"com.squareup.okio:okio:3.17.0\")" in build)
        assertTrue("compileOnly(\"org.example:compile:1.0\")" in build)
        assertTrue("runtimeOnly(\"org.example:runtime:1.0\")" in build)
        assertTrue("implementation(\"org.example:plain:1.0\")" in build)
        assertTrue("testImplementation(\"org.example:test-exported:1.0\")" in build)
    }

    @Test
    fun windowsLauncherPropagatesADownloadFailure() {
        val launcher = generate("product: jvm/lib\n").first { it.path.name == "gradlew.bat" }.content

        assertTrue("if errorlevel 1" in launcher)
        assertFalse(
            "%errorlevel%" in launcher,
            "cmd.exe expands %errorlevel% when it parses the whole if-block, so it always reads 0 there",
        )
    }

    @Test
    fun gradlePropertiesReserveHeapForNativeLinking() {
        val properties = generate("product: jvm/lib\n")
            .first { it.path.name == "gradle.properties" }
            .content

        assertTrue("org.gradle.jvmargs=-Xmx3g" in properties)
        assertTrue("kotlin.daemon.jvmargs=-Xmx4g" in properties)
    }

    @Test
    fun kmpAndroidUsesTheAgpMultiplatformLibraryPlugin() {
        val build = generate(
            """
            product:
              type: kmp/lib
              platforms: [jvm, android]

            settings:
              android:
                namespace: example.lib
                minSdk: 26
            """.trimIndent(),
            "test@android/AndroidOnlyTest.kt" to "class AndroidOnlyTest\n",
            "src@android/Platform.android.kt" to "// actual\n",
        ).buildFile()

        assertTrue("id(\"com.android.kotlin.multiplatform.library\")" in build)
        assertFalse("id(\"com.android.library\")" in build)
        assertTrue("androidLibrary {" in build)
        assertTrue("namespace = \"example.lib\"" in build)
        assertTrue("minSdk = 26" in build)
        assertTrue("withHostTestBuilder {}.configure {}" in build)
        assertTrue("maybeCreate(\"androidHostTest\")" in build)
        assertFalse("maybeCreate(\"androidTest\")" in build)
    }

    @Test
    fun aMissingAndroidNamespaceIsDerivedInsteadOfFailing() {
        val build = generate(
            """
            product:
              type: kmp/lib
              platforms: [jvm, android]
            """.trimIndent(),
        ).buildFile()

        assertTrue("namespace = \"ktc.generated." in build)
    }

    @Test
    fun everyGeneratedFileHasOwnershipMarker() {
        val files = generate("product: jvm/lib\n")
        for (file in files) {
            assertTrue(
                file.content.contains("Generated by ktc-to-gradle", ignoreCase = true),
                "${file.path} lacks the generated-file marker",
            )
        }
    }

    @Test
    fun relativeLocalDependenciesResolveToTheirGradleProject() {
        val root = Files.createTempDirectory("ktc-to-gradle-relative-dep-")
        write(root.resolve("project.yaml"), "modules: [app, libs/messages]\n")
        write(
            root.resolve("app/module.yaml"),
            """
            product: jvm/lib
            dependencies:
              - ./../libs/messages
            """.trimIndent(),
        )
        write(root.resolve("libs/messages/module.yaml"), "product: jvm/lib\n")

        val build = generate(root).first { it.path.name == "build.gradle.kts" && it.path.parent?.name == "app" }.content

        assertTrue("implementation(project(\":libs:messages\"))" in build)
    }

    @Test
    fun subprojectPluginsCarryNoVersionAndTheRootDeclaresThemOnce() {
        val root = Files.createTempDirectory("ktc-to-gradle-plugins-")
        write(root.resolve("project.yaml"), "modules:\n  - lib\n  - jsmod\n")
        write(
            root.resolve("lib/module.yaml"),
            """
            product: jvm/lib
            settings:
              kotlin:
                version: 2.4.10
            """.trimIndent(),
        )
        write(
            root.resolve("jsmod/module.yaml"),
            """
            product:
              type: kmp/lib
              platforms: [jvm, js]
            settings:
              kotlin:
                version: 2.4.10
                serialization: json
            """.trimIndent(),
        )
        val files = generate(root)

        val rootBuild = files.buildFile()
        val rootPlugins = rootBuild.pluginBlock()
        assertEquals(
            listOf(
                "base",
                "kotlin(\"multiplatform\") version \"2.4.10\" apply false",
                "kotlin(\"plugin.serialization\") version \"2.4.10\" apply false",
                "kotlin(\"jvm\") version \"2.4.10\" apply false",
            ),
            rootPlugins,
            rootBuild,
        )

        assertEquals(listOf("kotlin(\"jvm\")"), files.moduleBuildFile("lib").pluginBlock())
        assertEquals(
            listOf("kotlin(\"multiplatform\")", "kotlin(\"plugin.serialization\")"),
            files.moduleBuildFile("jsmod").pluginBlock(),
        )
    }

    @Test
    fun aChildAskingForThePluginTheRootAlreadyAppliesGetsNoSecondDeclaration() {
        val root = Files.createTempDirectory("ktc-to-gradle-same-plugin-")
        write(root.resolve("project.yaml"), "modules:\n  - .\n  - child\n")
        val moduleYaml = """
            product: jvm/lib
            settings:
              kotlin:
                version: 2.4.10
        """.trimIndent()
        write(root.resolve("module.yaml"), moduleYaml)
        write(root.resolve("child/module.yaml"), moduleYaml)
        val files = generate(root)

        assertEquals(listOf("kotlin(\"jvm\") version \"2.4.10\""), files.buildFile().pluginBlock())
        assertEquals(listOf("kotlin(\"jvm\")"), files.moduleBuildFile("child").pluginBlock())
    }

    @Test
    fun aStableReleaseOutranksAPreReleaseCarryingTheSameNumbers() {
        val root = Files.createTempDirectory("ktc-to-gradle-prerelease-")
        write(root.resolve("project.yaml"), "modules:\n  - stable\n  - beta\n")
        write(
            root.resolve("beta/module.yaml"),
            "product: jvm/lib\nsettings:\n  kotlin:\n    version: 2.4.10-Beta2\n",
        )
        write(
            root.resolve("stable/module.yaml"),
            "product: jvm/lib\nsettings:\n  kotlin:\n    version: 2.4.10\n",
        )
        val files = generate(root)

        assertEquals(listOf("base", "kotlin(\"jvm\") version \"2.4.10\" apply false"), files.buildFile().pluginBlock())
    }

    @Test
    fun anAndroidKotlinPinDoesNotDecideTheKotlinVersionOfTheOtherModules() {
        val root = Files.createTempDirectory("ktc-to-gradle-android-pin-")
        write(root.resolve("project.yaml"), "modules:\n  - app\n  - lib\n")
        write(
            root.resolve("app/module.yaml"),
            """
            product: android/app
            settings:
              android:
                namespace: example.android
              kotlin:
                version: 2.3.20
                serialization: json
            """.trimIndent(),
        )
        write(root.resolve("lib/module.yaml"), "product: jvm/lib\n")
        val files = generate(root)

        assertEquals(
            listOf(
                "base",
                "id(\"com.android.application\") version \"9.0.0\" apply false",
                "kotlin(\"plugin.serialization\") version \"2.4.10\" apply false",
                "kotlin(\"jvm\") version \"2.4.10\" apply false",
            ),
            files.buildFile().pluginBlock(),
        )
    }

    @Test
    fun conflictingKotlinVersionsResolveToThePinnedOneWithAWarning() {
        val root = Files.createTempDirectory("ktc-to-gradle-plugin-conflict-")
        write(root.resolve("project.yaml"), "modules:\n  - pinned\n  - defaulted\n")
        write(
            root.resolve("pinned/module.yaml"),
            """
            product: jvm/lib
            settings:
              kotlin:
                version: 2.3.20
            """.trimIndent(),
        )
        write(root.resolve("defaulted/module.yaml"), "product: jvm/lib\n")
        val (files, diagnostics) = generateAll(root)

        assertTrue("kotlin(\"jvm\") version \"2.3.20\" apply false" in files.buildFile(), files.buildFile())
        assertTrue(
            diagnostics.any { it.severity == Diagnostic.Severity.WARNING && "2.3.20" in it.message && "2.4.10" in it.message },
            "Expected a diagnostic about the conflicting Kotlin versions, got $diagnostics",
        )
    }

    @Test
    fun aRootOnlyModuleStillDeclaresItsOwnPluginVersion() {
        val build = generate(
            """
            product: jvm/lib
            settings:
              kotlin:
                version: 2.4.10
            """.trimIndent(),
        ).buildFile()

        assertTrue("kotlin(\"jvm\") version \"2.4.10\"" in build, build)
        assertFalse("apply false" in build, build)
    }

    @Test
    fun aRootModuleAppliesItsOwnPluginsAndDeclaresTheSubprojectOnes() {
        val root = Files.createTempDirectory("ktc-to-gradle-root-module-")
        write(root.resolve("project.yaml"), "modules:\n  - .\n  - jsmod\n")
        write(
            root.resolve("module.yaml"),
            """
            product: jvm/lib
            settings:
              kotlin:
                version: 2.4.10
            """.trimIndent(),
        )
        write(
            root.resolve("jsmod/module.yaml"),
            """
            product:
              type: kmp/lib
              platforms: [jvm, js]
            settings:
              kotlin:
                version: 2.4.10
            """.trimIndent(),
        )
        val files = generate(root)

        val rootBuild = files.buildFile()
        assertTrue("kotlin(\"jvm\") version \"2.4.10\"\n" in rootBuild, rootBuild)
        assertTrue("kotlin(\"multiplatform\") version \"2.4.10\" apply false" in rootBuild, rootBuild)
        assertFalse("kotlin(\"jvm\") version \"2.4.10\" apply false" in rootBuild, rootBuild)
    }

    @Test
    fun platformQualifiedSettingsReachTheMatchingTargets() {
        val build = generate(
            """
            product:
              type: kmp/lib
              platforms: [jvm, android, linuxX64]
            aliases:
              - jvmAndAndroid: [jvm, android]
            settings:
              android:
                namespace: example.qualified
            settings@jvmAndAndroid:
              kotlin:
                allWarningsAsErrors: true
            settings@linuxX64:
              kotlin:
                progressiveMode: true
                optIns: [kotlin.ExperimentalStdlibApi]
            """.trimIndent(),
        ).buildFile()

        assertEquals(
            listOf("allWarningsAsErrors.set(true)"),
            build.targetCompilerOptions("jvm").filterNot { it.startsWith("jvmTarget") || it.startsWith("freeCompilerArgs") },
            build,
        )
        assertEquals(listOf("allWarningsAsErrors.set(true)"), build.targetCompilerOptions("androidLibrary"), build)
        assertEquals(
            listOf("progressiveMode.set(true)", "optIn.addAll(listOf(\"kotlin.ExperimentalStdlibApi\"))"),
            build.targetCompilerOptions("linuxX64"),
            build,
        )
    }

    @Test
    fun aQualifiedSectionCanTurnAModuleWideFlagBackOff() {
        val build = generate(
            """
            product:
              type: kmp/lib
              platforms: [jvm, linuxX64]
            settings:
              kotlin:
                allWarningsAsErrors: true
            settings@linuxX64:
              kotlin:
                allWarningsAsErrors: false
            """.trimIndent(),
        ).buildFile()

        assertTrue("allWarningsAsErrors.set(true)" in build, build)
        assertEquals(listOf("allWarningsAsErrors.set(false)"), build.targetCompilerOptions("linuxX64"), build)
    }

    @Test
    fun aQualifiedSettingOnASinglePlatformProductReachesTheModuleOptions() {
        val build = generate(
            """
            product: jvm/lib
            settings@jvm:
              kotlin:
                allWarningsAsErrors: true
            """.trimIndent(),
        ).buildFile()

        assertTrue("allWarningsAsErrors.set(true)" in build, build)
    }

    @Test
    fun aQualifierNamingNoPlatformIsReportedInsteadOfDropped() {
        val (_, diagnostics) = generateAll(
            """
            product:
              type: kmp/lib
              platforms: [jvm, linuxX64]
            settings@macosArm64:
              kotlin:
                allWarningsAsErrors: true
            """.trimIndent(),
        )

        assertTrue(
            diagnostics.any { it.severity == Diagnostic.Severity.WARNING && "settings@macosArm64" in it.message },
            "Expected a diagnostic about the unknown qualifier, got $diagnostics",
        )
    }

    @Test
    fun aQualifiedSettingWithNoGradleEquivalentIsReportedInsteadOfDropped() {
        val (_, diagnostics) = generateAll(
            """
            product:
              type: kmp/lib
              platforms: [jvm, linuxX64]
            settings@jvm:
              jvm:
                release: 21
              kotlin:
                ksp: enabled
            """.trimIndent(),
        )

        assertTrue(
            diagnostics.any { "settings@jvm.jvm.release" in it.message },
            "Expected a diagnostic naming the dropped section, got $diagnostics",
        )
        assertTrue(
            diagnostics.any { "settings@jvm.kotlin.ksp" in it.message },
            "Expected a diagnostic naming the dropped key, got $diagnostics",
        )
    }

    @Test
    fun aNarrowerQualifierWinsOverABroaderOneCoveringTheSameLeaf() {
        val build = generate(
            """
            product:
              type: kmp/lib
              platforms: [jvm, iosArm64]
            settings@iosArm64:
              kotlin:
                allWarningsAsErrors: false
            settings@ios:
              kotlin:
                allWarningsAsErrors: true
            """.trimIndent(),
        ).buildFile()

        assertEquals(listOf("allWarningsAsErrors.set(false)"), build.targetCompilerOptions("iosArm64"), build)
    }

    @Test
    fun settingsAtCommonLandOnTheModuleWideOptionsNotOnEveryTarget() {
        val build = generate(
            """
            product:
              type: kmp/lib
              platforms: [jvm, linuxX64]
            settings@common:
              kotlin:
                allWarningsAsErrors: true
            """.trimIndent(),
        ).buildFile()

        assertEquals(1, Regex("allWarningsAsErrors").findAll(build).count(), build)
        assertEquals(emptyList(), build.targetCompilerOptions("linuxX64"), build)
    }

    @Test
    fun theWasmWasiTargetCarriesQualifiedOptionsThroughItsCompilations() {
        val build = generate(
            """
            product:
              type: kmp/lib
              platforms: [jvm, wasmWasi]
            settings@wasmWasi:
              kotlin:
                allWarningsAsErrors: true
            """.trimIndent(),
        ).buildFile()

        assertTrue("compilations.configureEach {" in build, build)
        assertTrue("compileTaskProvider.configure {" in build, build)
        assertFalse("wasmWasi {\n        compilerOptions" in build, build)
    }

    @Test
    fun malformedQualifiedValuesAreReportedInsteadOfDropped() {
        val (files, diagnostics) = generateAll(
            """
            product:
              type: kmp/lib
              platforms: [jvm, linuxX64]
            settings@jvm:
              kotlin:
                allWarningsAsErrors: yes
                freeCompilerArgs: -Xfoo
            settings@linuxX64: plain
            test-settings@jvm:
              kotlin:
                allWarningsAsErrors: true
            """.trimIndent(),
        )

        val messages = diagnostics.map { it.message }
        assertTrue(messages.any { "settings@jvm.kotlin.allWarningsAsErrors" in it }, "$messages")
        assertTrue(messages.any { "settings@jvm.kotlin.freeCompilerArgs" in it }, "$messages")
        assertTrue(messages.any { "'settings@linuxX64' must be an object" in it }, "$messages")
        assertTrue(messages.any { "test-settings@jvm" in it }, "$messages")
        assertFalse("allWarningsAsErrors" in files.buildFile(), files.buildFile())
    }

    private fun generate(moduleYaml: String, vararg files: Pair<String, String>): List<GeneratedFile> =
        generateAll(moduleYaml, *files).first

    private fun generateAll(
        moduleYaml: String,
        vararg files: Pair<String, String>,
    ): Pair<List<GeneratedFile>, List<Diagnostic>> {
        val root = Files.createTempDirectory("ktc-to-gradle-generator-")
        write(root.resolve("module.yaml"), moduleYaml)
        for ((path, content) in files) write(root.resolve(path), content)
        return generateAll(root)
    }

    private fun generate(root: Path): List<GeneratedFile> = generateAll(root).first

    private fun generateAll(root: Path): Pair<List<GeneratedFile>, List<Diagnostic>> {
        val project = ProjectLoader(FileSystem.SYSTEM).load(root.toString().toPath())
        val result = GradleGenerator().generate(project)
        return result.files to result.diagnostics
    }

    private fun List<GeneratedFile>.buildFile(): String = first { it.path.name == "build.gradle.kts" }.content

    /** The compilerOptions lines a generated `kotlin { }` target block carries. */
    private fun String.targetCompilerOptions(target: String): List<String> {
        val opener = "    $target {"
        val start = indexOf(opener)
        require(start >= 0) { "No '$target' target in the generated file:\n$this" }
        val block = substring(start + opener.length).substringBefore("\n    }")
        if ("compilerOptions {" !in block) return emptyList()
        return block.substringAfter("compilerOptions {").substringBefore("}").lines()
            .map(String::trim).filter(String::isNotEmpty)
    }

    private fun String.pluginBlock(): List<String> =
        substringAfter("plugins {").substringBefore("\n}").lines().map(String::trim).filter(String::isNotEmpty)

    private fun List<GeneratedFile>.moduleBuildFile(directory: String): String =
        first { it.path.name == "build.gradle.kts" && it.path.parent?.name == directory }.content

    private fun write(path: Path, content: String) {
        path.parent.createDirectories()
        path.writeText(content)
    }
}
