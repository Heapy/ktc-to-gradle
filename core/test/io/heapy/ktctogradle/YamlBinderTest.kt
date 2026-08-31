package io.heapy.ktctogradle

import io.heapy.ktctogradle.load.AndroidSettings
import io.heapy.ktctogradle.load.CompilerPluginSpec
import io.heapy.ktctogradle.load.JvmSettings
import io.heapy.ktctogradle.load.KotlinSettings
import io.heapy.ktctogradle.load.KtorSettings
import io.heapy.ktctogradle.load.Layout
import io.heapy.ktctogradle.load.MavenCentralSpec
import io.heapy.ktctogradle.load.NativeSettings
import io.heapy.ktctogradle.load.PomDeveloper
import io.heapy.ktctogradle.load.PomLicense
import io.heapy.ktctogradle.load.PomScm
import io.heapy.ktctogradle.load.PomSpec
import io.heapy.ktctogradle.load.ProductSpec
import io.heapy.ktctogradle.load.PublishingSettings
import io.heapy.ktctogradle.load.QualifiedOption
import io.heapy.ktctogradle.load.QualifiedSection
import io.heapy.ktctogradle.load.RawCredentials
import io.heapy.ktctogradle.load.RawDependency
import io.heapy.ktctogradle.load.RawRepository
import io.heapy.ktctogradle.load.Region
import io.heapy.ktctogradle.load.SerializationSpec
import io.heapy.ktctogradle.load.Settings
import io.heapy.ktctogradle.load.TestSettings
import io.heapy.ktctogradle.load.ToolchainModel
import io.heapy.ktctogradle.load.UnsupportedKey
import io.heapy.ktctogradle.load.YamlBinder
import io.heapy.ktctogradle.load.parseYaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YamlBinderTest {
    @Test
    fun bindsTheScalarProductForm() {
        assertEquals(ProductSpec("jvm/app", listOf("jvm")), bind("product: jvm/app").product)
    }

    @Test
    fun bindsTheMappingProductForm() {
        val model = bind(
            """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64]
            """.trimIndent(),
        )
        assertEquals(ProductSpec("kmp/lib", listOf("jvm", "linuxX64")), model.product)
    }

    @Test
    fun defaultsThePlatformsOfEveryProductTypeThatHasThem() {
        assertEquals(listOf("android"), bind("product: android/app").product.platforms)
        assertEquals(listOf("js"), bind("product: js/app").product.platforms)
        assertEquals(listOf("wasmJs"), bind("product: wasm-js/app").product.platforms)
        assertEquals(listOf("wasmWasi"), bind("product: wasm-wasi/app").product.platforms)
        assertEquals(listOf("linuxX64", "linuxArm64"), bind("product: linux/app").product.platforms)
        assertEquals(listOf("macosArm64"), bind("product: macos/app").product.platforms)
        assertEquals(listOf("mingwX64"), bind("product: windows/app").product.platforms)
    }

    @Test
    fun defersTheFailureOfAProductThatIsNeitherStringNorObject() {
        val model = bind("product: [jvm/app]")

        assertEquals(ProductSpec("", emptyList()), model.product)
        assertEquals("product must be a string or object", model.errors["product"])
    }

    @Test
    fun defersTheFailureOfAMissingProductAndOfAProductWithoutType() {
        assertEquals("Every module must declare product", bind("layout: maven-like").errors["product"])
        assertEquals("product.type is required", bind("product:\n  platforms: [jvm]\n").errors["product"])
    }

    @Test
    fun defersTheFailureOfAKmpLibraryWithoutPlatforms() {
        val model = bind("product: kmp/lib")

        assertEquals("kmp/lib requires product.platforms", model.errors["product"])
        assertEquals(ProductSpec("", emptyList()), model.product)
    }

    @Test
    fun bindsTheLayoutKeyAndTreatsEveryOtherValueAsTheDefault() {
        assertEquals(Layout.MAVEN_LIKE, bind("product: jvm/lib\nlayout: maven-like\n").layout)
        assertEquals(Layout.AMPER, bind("product: jvm/lib").layout)
        assertEquals(Layout.AMPER, bind("product: jvm/lib\nlayout: nonsense\n").layout)
    }

    @Test
    fun bindsDependencyScopesFromScalarSuffixesAndFromObjects() {
        val model = bind(
            """
                product: jvm/lib
                dependencies:
                  - com.squareup.okio:okio:3.17.0: exported
                  - org.example:compile:1.0: compile-only
                  - org.example:runtime:1.0: runtime-only
                  - org.example:plain:1.0
                  - org.example:suffix:1.0: all
                  - bom: org.example:platform:1.0
                  - org.example:detailed:1.0:
                      scope: compile-only
                      exported: true
                test-dependencies:
                  - ${'$'}kotlin.test
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                RawDependency("com.squareup.okio:okio:3.17.0", exported = true),
                RawDependency("org.example:compile:1.0", scope = "compile-only"),
                RawDependency("org.example:runtime:1.0", scope = "runtime-only"),
                RawDependency("org.example:plain:1.0"),
                RawDependency("org.example:suffix:1.0"),
                RawDependency("org.example:platform:1.0", bom = true),
                RawDependency("org.example:detailed:1.0", scope = "compile-only", exported = true),
            ),
            model.dependencies.getValue(""),
        )
        assertEquals(listOf(RawDependency("\$kotlin.test")), model.testDependencies.getValue(""))
        assertEquals(emptyMap(), model.errors)
    }

    @Test
    fun defersTheFailureOfADependencyObjectWithTwoCoordinates() {
        val model = bind(
            """
                product: jvm/lib
                dependencies:
                  - org.example:one:1.0: all
                    org.example:two:1.0: all
            """.trimIndent(),
        )

        assertEquals("A dependency object must have exactly one coordinate", model.errors["dependencies"])
        assertEquals(emptyList(), model.dependencies.getValue(""))
    }

    @Test
    fun defersTheFailureOfAnUnknownDependencyScopeUnderTheContentKey() {
        val model = bind("product: jvm/lib\ndependencies:\n  - org.example:one:1.0: sometimes\n")

        assertEquals(null, model.errors["dependencies"])
        assertEquals(
            "Dependency 'org.example:one:1.0' has unknown scope 'sometimes'",
            model.errors[Region.dependencyContent("dependencies")],
        )
        assertEquals(listOf(RawDependency("org.example:one:1.0")), model.dependencies.getValue(""))
    }

    @Test
    fun defersDependencyFailuresPerQualifiedSection() {
        val model = bind(
            """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64]
                dependencies:
                  - org.example:common:1.0
                dependencies@jvm: not-a-list
            """.trimIndent(),
        )

        assertEquals(listOf(RawDependency("org.example:common:1.0")), model.dependencies.getValue(""))
        assertEquals(emptyList(), model.dependencies.getValue("jvm"))
        assertEquals("Expected a list at shared.dependencies@jvm", model.errors["dependencies@jvm"])
    }

    @Test
    fun keysQualifiedDependencySectionsByTheirQualifier() {
        val model = bind(
            """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64]
                dependencies:
                  - org.example:common:1.0
                dependencies@jvm:
                  - org.example:jvm:1.0
                test-dependencies@linuxX64:
                  - org.example:linux-test:1.0
            """.trimIndent(),
        )

        assertEquals(setOf("", "jvm"), model.dependencies.keys)
        assertEquals(listOf(RawDependency("org.example:jvm:1.0")), model.dependencies.getValue("jvm"))
        assertEquals(setOf("linuxX64"), model.testDependencies.keys)
        assertEquals(listOf(RawDependency("org.example:linux-test:1.0")), model.testDependencies.getValue("linuxX64"))
    }

    @Test
    fun bindsAliasesToPlatformSetsInDeclarationOrder() {
        val model = bind(
            """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64, macosArm64]
                aliases:
                  - desktop: [jvm, linuxX64]
                  - posix: [linuxX64, macosArm64]
            """.trimIndent(),
        )

        assertEquals(
            mapOf("desktop" to setOf("jvm", "linuxX64"), "posix" to setOf("linuxX64", "macosArm64")),
            model.aliases,
        )
        assertEquals(listOf("desktop", "posix"), model.aliases.keys.toList())
    }

    @Test
    fun bindsTheMappingAliasForm() {
        val model = bind(
            """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64]
                aliases:
                  desktop: [jvm, linuxX64]
            """.trimIndent(),
        )

        assertEquals(mapOf("desktop" to setOf("jvm", "linuxX64")), model.aliases)
    }

    @Test
    fun defersTheFailureOfAnAliasPlatformThatIsNotAName() {
        val model = bind(
            """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64]
                aliases:
                  - desktop:
                      - [jvm]
            """.trimIndent(),
        )

        assertEquals("aliases.desktop[0] must be a platform name", model.errors["aliases"])
        assertEquals(emptyMap(), model.aliases)
    }

    @Test
    fun defersTheStructuralFailuresOfAnAliasSection() {
        assertEquals(
            "aliases must be an object or list",
            bind("product: jvm/lib\naliases: nonsense\n").errors["aliases"],
        )
        assertEquals(
            "Alias 'desktop' must contain at least one platform",
            bind("product: jvm/lib\naliases:\n  - desktop: []\n").errors["aliases"],
        )
        assertEquals(
            "aliases[0] must define exactly one alias",
            bind("product: jvm/lib\naliases:\n  - one: [jvm]\n    two: [jvm]\n").errors["aliases"],
        )
    }

    @Test
    fun bindsThePublishingSectionIncludingBothShorthands() {
        val nested = bind(
            """
                product: jvm/lib
                settings:
                  publishing:
                    enabled: true
                    group: io.heapy
                    artifactId: krogu-time
                    version: 0.1.0
                    publishSources: true
                    signArtifacts: true
                    checksums: [md5, sha1, sha256]
                    mavenCentral:
                      enabled: true
                      publishingMode: manual
                    pom:
                      name: krogu-time
                      description: A port
                      url: https://example.invalid/krogu-time
                      licenses:
                        - name: Apache-2.0
                          url: https://www.apache.org/licenses/LICENSE-2.0.txt
                      developers:
                        - id: heapy
                          name: Example Developer
                          url: https://example.invalid/heapy
                          email: developer@example.invalid
                          organization: Example Org
                          organizationUrl: https://example.invalid
                      scm:
                        url: https://example.invalid/krogu-time
                        connection: scm:git:https://example.invalid/krogu-time.git
                        developerConnection: scm:git:ssh://git@example.invalid/krogu-time.git
            """.trimIndent(),
        )

        assertEquals(
            PublishingSettings(
                enabled = true,
                group = "io.heapy",
                artifactId = "krogu-time",
                version = "0.1.0",
                publishSources = true,
                signArtifacts = true,
                checksums = listOf("md5", "sha1", "sha256"),
                mavenCentral = MavenCentralSpec(enabled = true, publishingMode = "manual"),
                pom = PomSpec(
                    name = "krogu-time",
                    description = "A port",
                    url = "https://example.invalid/krogu-time",
                    licenses = listOf(
                        PomLicense(name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0.txt"),
                    ),
                    developers = listOf(
                        PomDeveloper(
                            id = "heapy",
                            name = "Example Developer",
                            url = "https://example.invalid/heapy",
                            email = "developer@example.invalid",
                            organization = "Example Org",
                            organizationUrl = "https://example.invalid",
                        ),
                    ),
                    scm = PomScm(
                        url = "https://example.invalid/krogu-time",
                        connection = "scm:git:https://example.invalid/krogu-time.git",
                        developerConnection = "scm:git:ssh://git@example.invalid/krogu-time.git",
                    ),
                ),
            ),
            nested.settings.publishing,
        )

        val shorthand = bind(
            """
                product: jvm/lib
                settings:
                  publishing:
                    mavenCentral: enabled
                    pom:
                      scm: https://example.invalid/krogu-time.git
            """.trimIndent(),
        )

        assertEquals(
            PublishingSettings(
                mavenCentral = MavenCentralSpec(enabled = true),
                pom = PomSpec(
                    scm = PomScm(
                        url = "https://example.invalid/krogu-time.git",
                        connection = "scm:git:https://example.invalid/krogu-time.git",
                        developerConnection = "scm:git:https://example.invalid/krogu-time.git",
                    ),
                ),
            ),
            shorthand.settings.publishing,
        )

        assertNull(bind("product: jvm/lib\nsettings:\n  kotlin:\n    version: 2.4.10\n").settings.publishing)
    }

    @Test
    fun bindsTheJunitPlatformVersion() {
        assertEquals(
            "1.11.4",
            bind("product: jvm/lib\nsettings:\n  jvm:\n    test:\n      junitPlatformVersion: 1.11.4\n")
                .settings.jvm?.testJunitPlatformVersion,
        )
        assertNull(
            bind("product: jvm/lib\nsettings:\n  jvm:\n    release: 21\n").settings.jvm?.testJunitPlatformVersion,
        )
    }

    @Test
    fun bindsTheTestSettingsRelease() {
        assertEquals(
            "25",
            bind("product: jvm/lib\ntest-settings:\n  jvm:\n    release: 25\n").settings.test?.release,
        )
    }

    @Test
    fun bindsRepositoriesIncludingResolvePublishAndCredentials() {
        val model = bind(
            """
                product: jvm/lib
                repositories:
                  - mavenLocal
                  - id: internal
                    url: https://repo.example/internal
                    publish: true
                    credentials:
                      file: credentials.properties
                      usernameKey: mirror.username
                      passwordKey: mirror.password
                  - url: https://maven.google.com
                    resolve: false
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                RawRepository(id = null, url = "mavenLocal"),
                RawRepository(
                    id = "internal",
                    url = "https://repo.example/internal",
                    publish = true,
                    credentials = RawCredentials("credentials.properties", "mirror.username", "mirror.password"),
                ),
                RawRepository(id = null, url = "https://maven.google.com", resolve = false),
            ),
            model.repositories,
        )
    }

    @Test
    fun defersTheFailureOfARepositoryWithoutUrlAndOfIncompleteCredentials() {
        assertEquals(
            "repositories[0].url is required",
            bind("product: jvm/lib\nrepositories:\n  - id: internal\n").errors["repositories"],
        )
        assertEquals(
            "repositories[0].credentials.usernameKey is required",
            bind(
                """
                    product: jvm/lib
                    repositories:
                      - url: https://repo.example/internal
                        credentials:
                          file: credentials.properties
                """.trimIndent(),
            ).errors["repositories"],
        )
    }

    @Test
    fun bindsEverySettingsSectionTheRenderersRead() {
        val model = bind(
            """
                product: android/app
                settings:
                  junit: junit-4
                  kotlin:
                    version: 2.4.10
                    languageVersion: 2.2
                    apiVersion: 2.1
                    allWarningsAsErrors: true
                    progressiveMode: false
                    freeCompilerArgs: [-Xcontext-parameters]
                    optIns: [kotlin.ExperimentalStdlibApi]
                    serialization: json
                  jvm:
                    jdk:
                      version: 25
                    release: 17
                    mainClass: example.MainKt
                    test:
                      freeJvmArgs: [-Dbase=true]
                      systemProperties:
                        shared: base
                      extraEnvironment:
                        SHARED: base
                  android:
                    namespace: org.example.app
                    compileSdk: 36
                    minSdk: 24
                    targetSdk: 35
                    applicationId: org.example.application
                    versionCode: 7
                    versionName: 1.2.3
                  native:
                    entryPoint: example.main
                  ktor:
                    enabled: true
                    version: 3.4.0
                test-settings:
                  jvm:
                    freeJvmArgs: [-Dtest=true]
                    systemProperties:
                      shared: test
                    extraEnvironment:
                      TEST_ONLY: present
            """.trimIndent(),
        )

        assertEquals(
            Settings(
                kotlin = KotlinSettings(
                    version = "2.4.10",
                    languageVersion = "2.2",
                    apiVersion = "2.1",
                    allWarningsAsErrors = true,
                    progressiveMode = false,
                    freeCompilerArgs = listOf("-Xcontext-parameters"),
                    optIns = listOf("kotlin.ExperimentalStdlibApi"),
                    serialization = SerializationSpec(format = "json"),
                ),
                jvm = JvmSettings(
                    jdkVersion = "25",
                    release = "17",
                    mainClass = "example.MainKt",
                    testFreeJvmArgs = listOf("-Dbase=true"),
                    testSystemProperties = mapOf("shared" to "base"),
                    testExtraEnvironment = mapOf("SHARED" to "base"),
                ),
                android = AndroidSettings(
                    namespace = "org.example.app",
                    compileSdk = "36",
                    minSdk = "24",
                    targetSdk = "35",
                    applicationId = "org.example.application",
                    versionCode = "7",
                    versionName = "1.2.3",
                ),
                native = NativeSettings(entryPoint = "example.main"),
                junit = "junit-4",
                ktor = KtorSettings(enabled = true, version = "3.4.0"),
                test = TestSettings(
                    freeJvmArgs = listOf("-Dtest=true"),
                    systemProperties = mapOf("shared" to "test"),
                    extraEnvironment = mapOf("TEST_ONLY" to "present"),
                ),
            ),
            model.settings,
        )
    }

    @Test
    fun bindsTheNestedCompileSdkApiLevelForm() {
        val model = bind(
            """
                product: android/app
                settings:
                  android:
                    compileSdk:
                      apiLevel: 37
            """.trimIndent(),
        )

        assertEquals("37", model.settings.android?.compileSdk)
    }

    @Test
    fun bindsTheScalarKtorAndSerializationForms() {
        val ktor = bind("product: jvm/app\nsettings:\n  ktor: enabled\n").settings.ktor
        assertEquals(KtorSettings(enabled = true, version = null), ktor)

        val kotlin = bind("product: jvm/lib\nsettings:\n  kotlin:\n    serialization: enabled\n").settings.kotlin
        assertEquals(SerializationSpec(), kotlin?.serialization)

        val disabled = bind("product: jvm/lib\nsettings:\n  kotlin:\n    serialization: disabled\n").settings.kotlin
        assertNull(disabled?.serialization)

        val versioned = bind(
            """
                product: jvm/lib
                settings:
                  kotlin:
                    serialization:
                      version: 1.9.0
                      format: cbor
            """.trimIndent(),
        ).settings.kotlin
        assertEquals(SerializationSpec(version = "1.9.0", format = "cbor"), versioned?.serialization)
    }

    @Test
    fun defersTheFailureOfASerializationSectionThatIsNeitherStringNorObject() {
        val model = bind("product: jvm/lib\nsettings:\n  kotlin:\n    serialization: [json]\n")

        assertEquals(
            "settings.kotlin.serialization must be a string or object",
            model.errors["settings.kotlin.serialization"],
        )
        assertNull(model.settings.kotlin?.serialization)
    }

    @Test
    fun bindsEveryCompilerPluginWithItsOptions() {
        val kotlin = bind(
            """
                product: jvm/lib
                settings:
                  kotlin:
                    compilerPlugins:
                      - id: org.example.first
                        dependency: org.example:first-compiler:1.0
                        options:
                          moduleId: shared
                          mode: strict
                      - id: org.example.second
                        dependency: org.example:second-compiler:2.0
            """.trimIndent(),
        ).settings.kotlin

        assertEquals(
            listOf(
                CompilerPluginSpec(
                    id = "org.example.first",
                    dependency = "org.example:first-compiler:1.0",
                    options = mapOf("moduleId" to "shared", "mode" to "strict"),
                ),
                CompilerPluginSpec(id = "org.example.second", dependency = "org.example:second-compiler:2.0"),
            ),
            kotlin?.compilerPlugins,
        )
    }

    @Test
    fun defersTheFailureOfACompilerPluginEntryThatNamesNoDependency() {
        val model = bind(
            """
                product: jvm/lib
                settings:
                  kotlin:
                    compilerPlugins:
                      - id: org.example.first
                        dependency: org.example:first-compiler:1.0
                      - id: org.example.second
            """.trimIndent(),
        )

        assertEquals(
            "settings.kotlin.compilerPlugins[1].dependency is required",
            model.errors["settings"],
        )
        assertNull(model.settings.kotlin)
    }

    @Test
    fun defersTheFailureOfCompilerPluginOptionsThatAreNotStrings() {
        val notAMap = bind(
            """
                product: jvm/lib
                settings:
                  kotlin:
                    compilerPlugins:
                      - id: org.example.plugin
                        dependency: org.example:compiler:1.0
                        options: [moduleId]
            """.trimIndent(),
        )
        assertEquals("settings.kotlin.compilerPlugins[0].options must be an object", notAMap.errors["settings"])

        val nestedValue = bind(
            """
                product: jvm/lib
                settings:
                  kotlin:
                    compilerPlugins:
                      - id: org.example.plugin
                        dependency: org.example:compiler:1.0
                        options:
                          moduleId:
                            name: shared
            """.trimIndent(),
        )
        assertEquals(
            "settings.kotlin.compilerPlugins[0].options.moduleId must be a string",
            nestedValue.errors["settings"],
        )
    }

    @Test
    fun defersTheFailureOfACompilerPluginsSectionThatIsNotAList() {
        val model = bind("product: jvm/lib\nsettings:\n  kotlin:\n    compilerPlugins: enabled\n")

        assertEquals("settings.kotlin.compilerPlugins must be a list", model.errors["settings"])
    }

    @Test
    fun keepsQualifiedSettingsSectionsInDeclarationOrder() {
        val model = bind(
            """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64]
                settings:
                  kotlin:
                    allWarningsAsErrors: true
                settings@jvm:
                  kotlin:
                    languageVersion: 2.2
                    optIns: [kotlin.ExperimentalStdlibApi]
                settings@linuxX64:
                  kotlin:
                    allWarningsAsErrors: false
                    progressiveMode: true
                test-settings@jvm:
                  kotlin:
                    allWarningsAsErrors: true
            """.trimIndent(),
        )

        assertEquals(
            listOf("settings@jvm", "settings@linuxX64", "test-settings@jvm"),
            model.qualifiedSections.map(QualifiedSection::key),
        )
        assertEquals(
            KotlinSettings(languageVersion = "2.2", optIns = listOf("kotlin.ExperimentalStdlibApi")),
            model.qualifiedSections.section("settings@jvm").settings?.kotlin,
        )
        assertEquals(
            KotlinSettings(allWarningsAsErrors = false, progressiveMode = true),
            model.qualifiedSections.section("settings@linuxX64").settings?.kotlin,
        )
        assertEquals(
            QualifiedSection(
                key = "test-settings@jvm",
                qualifier = "jvm",
                test = true,
                settings = Settings(test = TestSettings()),
                unsupportedKeys = listOf(UnsupportedKey("kotlin.allWarningsAsErrors", UnsupportedKey.UNSUPPORTED)),
                malformedOptions = emptySet(),
            ),
            model.qualifiedSections.section("test-settings@jvm"),
        )
    }

    @Test
    fun bindsTheJvmTestKeysOfBothQualifiedSpellings() {
        val model = bind(
            """
                product:
                  type: kmp/lib
                  platforms: [jvm, android]
                settings@jvm:
                  jvm:
                    test:
                      freeJvmArgs: [-Xmx512m]
                      systemProperties:
                        mode: jvm
                      extraEnvironment:
                        HOME_DIR: /tmp/jvm
                test-settings@android:
                  jvm:
                    freeJvmArgs: [-XX:+UseZGC]
                    systemProperties:
                      mode: android
                    extraEnvironment:
                      HOME_DIR: /tmp/android
            """.trimIndent(),
        )

        assertEquals(
            JvmSettings(
                testFreeJvmArgs = listOf("-Xmx512m"),
                testSystemProperties = mapOf("mode" to "jvm"),
                testExtraEnvironment = mapOf("HOME_DIR" to "/tmp/jvm"),
            ),
            model.qualifiedSections.section("settings@jvm").settings?.jvm,
        )
        assertEquals(
            TestSettings(
                freeJvmArgs = listOf("-XX:+UseZGC"),
                systemProperties = mapOf("mode" to "android"),
                extraEnvironment = mapOf("HOME_DIR" to "/tmp/android"),
            ),
            model.qualifiedSections.section("test-settings@android").settings?.test,
        )
        assertEquals(emptyList(), model.qualifiedSections.section("settings@jvm").unsupportedKeys)
        assertEquals(emptyList(), model.qualifiedSections.section("test-settings@android").unsupportedKeys)
    }

    @Test
    fun reportsADroppedKeyThatWasWrittenAsAnEmptyObject() {
        val model = bind(
            """
                product:
                  type: kmp/lib
                  platforms: [jvm, android]
                settings@jvm:
                  kotlin:
                    unknown: {}
                test-settings@android:
                  jvm:
                    release: {}
            """.trimIndent(),
        )

        assertEquals(
            listOf(UnsupportedKey("kotlin.unknown", UnsupportedKey.UNSUPPORTED)),
            model.qualifiedSections.section("settings@jvm").unsupportedKeys,
        )
        assertEquals(
            listOf(UnsupportedKey("jvm.release", UnsupportedKey.UNSUPPORTED)),
            model.qualifiedSections.section("test-settings@android").unsupportedKeys,
        )
    }

    @Test
    fun namesTheShapeAMalformedJvmTestKeyGotWrong() {
        val model = bind(
            """
                product:
                  type: kmp/lib
                  platforms: [jvm, android]
                settings@jvm:
                  jvm:
                    test:
                      freeJvmArgs: nonsense
                      systemProperties: nonsense
                test-settings@android:
                  jvm:
                    extraEnvironment: [nonsense]
                    unknown: true
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                UnsupportedKey("jvm.test.freeJvmArgs", "must be a list"),
                UnsupportedKey("jvm.test.systemProperties", "must be an object"),
            ),
            model.qualifiedSections.section("settings@jvm").unsupportedKeys,
        )
        assertEquals(
            listOf(
                UnsupportedKey("jvm.extraEnvironment", "must be an object"),
                UnsupportedKey("jvm.unknown", UnsupportedKey.UNSUPPORTED),
            ),
            model.qualifiedSections.section("test-settings@android").unsupportedKeys,
        )
    }

    @Test
    fun reportsANonScalarElementOfAnUnqualifiedJvmTestList() {
        val model = bind(
            """
                product: jvm/lib
                settings:
                  jvm:
                    test:
                      freeJvmArgs:
                        - -ea
                        - bad: value
            """.trimIndent(),
        )

        assertEquals(
            mapOf(Region.JVM_TEST_SETTINGS to "Expected a string at settings.jvm.test.freeJvmArgs[1]"),
            model.errors,
        )
        assertEquals(emptyList(), model.settings.jvm?.testFreeJvmArgs)
    }

    @Test
    fun reportsANonScalarElementOfAnUnqualifiedTestSettingsList() {
        val model = bind(
            """
                product: jvm/lib
                test-settings:
                  jvm:
                    freeJvmArgs:
                      - -ea
                      - bad: value
            """.trimIndent(),
        )

        assertEquals(
            mapOf(Region.JVM_TEST_SETTINGS to "Expected a string at test-settings.jvm.freeJvmArgs[1]"),
            model.errors,
        )
        assertEquals(emptyList(), model.settings.test?.freeJvmArgs)
    }

    @Test
    fun reportsANonScalarValueOfAnUnqualifiedJvmTestMap() {
        val model = bind(
            """
                product: jvm/lib
                settings:
                  jvm:
                    test:
                      systemProperties:
                        mode:
                          nested: value
            """.trimIndent(),
        )

        assertEquals(
            mapOf(Region.JVM_TEST_SETTINGS to "Expected a string at settings.jvm.test.systemProperties.mode"),
            model.errors,
        )
        assertEquals(emptyMap(), model.settings.jvm?.testSystemProperties)
    }

    @Test
    fun reportsANonScalarValueOfAnUnqualifiedTestSettingsMap() {
        val model = bind(
            """
                product: jvm/lib
                test-settings:
                  jvm:
                    extraEnvironment:
                      HOME_DIR:
                        nested: value
            """.trimIndent(),
        )

        assertEquals(
            mapOf(Region.JVM_TEST_SETTINGS to "Expected a string at test-settings.jvm.extraEnvironment.HOME_DIR"),
            model.errors,
        )
        assertEquals(emptyMap(), model.settings.test?.extraEnvironment)
    }

    @Test
    fun namesTheNonScalarEntriesAQualifiedJvmTestKeyCarries() {
        val model = bind(
            """
                product:
                  type: kmp/lib
                  platforms: [jvm, android]
                settings@jvm:
                  jvm:
                    test:
                      freeJvmArgs:
                        - -ea
                        - bad: value
                      systemProperties:
                        mode:
                          nested: value
                        kept: plain
                test-settings@android:
                  jvm:
                    extraEnvironment:
                      HOME_DIR:
                        nested: value
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                UnsupportedKey("jvm.test.freeJvmArgs[1]", "must be a string"),
                UnsupportedKey("jvm.test.systemProperties.mode", "must be a string"),
            ),
            model.qualifiedSections.section("settings@jvm").unsupportedKeys,
        )
        assertEquals(
            listOf(UnsupportedKey("jvm.extraEnvironment.HOME_DIR", "must be a string")),
            model.qualifiedSections.section("test-settings@android").unsupportedKeys,
        )
        assertEquals(
            JvmSettings(testFreeJvmArgs = listOf("-ea"), testSystemProperties = mapOf("kept" to "plain")),
            model.qualifiedSections.section("settings@jvm").settings?.jvm,
        )
        assertEquals(
            TestSettings(),
            model.qualifiedSections.section("test-settings@android").settings?.test,
        )
        assertEquals(emptyMap(), model.errors)
    }

    @Test
    fun dropsMalformedQualifiedSectionsWithoutFailingOrDeferring() {
        val model = bind(
            """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64]
                settings@linuxX64: plain
                settings@jvm:
                  kotlin:
                    languageVersion: [2.2]
                    freeCompilerArgs: nonsense
                  jvm:
                    release: 21
            """.trimIndent(),
        )

        assertNull(model.qualifiedSections.section("settings@linuxX64").settings)
        val kotlin = model.qualifiedSections.section("settings@jvm").settings?.kotlin
        assertNull(kotlin?.languageVersion)
        assertEquals(emptyList(), kotlin?.freeCompilerArgs)
        assertEquals(emptyMap(), model.errors)
    }

    @Test
    fun recordsEveryDroppedKeyOfAQualifiedSectionInDeclarationOrder() {
        val model = bind(
            """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64]
                settings@jvm:
                  kotlin:
                    languageVersion: [2.2]
                    allWarningsAsErrors: yes
                    freeCompilerArgs: nonsense
                    unknown: true
                    ksp: enabled
                  jvm:
                    release: 21
                    test:
                      freeJvmArgs: [-Xmx1g]
                settings@linuxX64:
                  kotlin: plain
                test-settings@jvm:
                  jvm:
                    release: 21
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                UnsupportedKey("kotlin.languageVersion", "must be a string", setOf("languageVersion")),
                UnsupportedKey("kotlin.allWarningsAsErrors", "must be true or false", setOf("allWarningsAsErrors")),
                UnsupportedKey("kotlin.freeCompilerArgs", "must be a list", setOf("freeCompilerArgs")),
                UnsupportedKey("kotlin.unknown", UnsupportedKey.UNSUPPORTED),
                UnsupportedKey("kotlin.ksp", UnsupportedKey.UNSUPPORTED),
                UnsupportedKey("jvm.release", UnsupportedKey.UNSUPPORTED),
            ),
            model.qualifiedSections.section("settings@jvm").unsupportedKeys,
        )
        assertEquals(
            listOf(UnsupportedKey("kotlin", "must be an object", QualifiedOption.ALL)),
            model.qualifiedSections.section("settings@linuxX64").unsupportedKeys,
        )
        assertEquals(
            listOf(UnsupportedKey("jvm.release", UnsupportedKey.UNSUPPORTED)),
            model.qualifiedSections.section("test-settings@jvm").unsupportedKeys,
        )

        assertEquals(
            setOf("languageVersion", "allWarningsAsErrors", "freeCompilerArgs"),
            model.qualifiedSections.section("settings@jvm").malformedOptions,
        )
        assertEquals(
            QualifiedOption.ALL,
            model.qualifiedSections.section("settings@linuxX64").malformedOptions,
        )
        assertEquals(emptySet(), model.qualifiedSections.section("test-settings@jvm").malformedOptions)
    }

    @Test
    fun aLiteralDottedKeyIsDroppedWithoutSuppressingTheOptionItSpellsOut() {
        val model = bind(
            """
                product: jvm/lib
                settings@common:
                  kotlin:
                    languageVersion: "2.1"
                settings@jvm:
                  "kotlin.languageVersion": ignored
            """.trimIndent(),
        )

        assertEquals(
            KotlinSettings(languageVersion = "2.1"),
            model.qualifiedSections.section("settings@common").settings?.kotlin,
        )
        assertEquals(
            QualifiedSection(
                key = "settings@jvm",
                qualifier = "jvm",
                test = false,
                settings = Settings.EMPTY,
                unsupportedKeys = listOf(UnsupportedKey("kotlin.languageVersion", UnsupportedKey.UNSUPPORTED)),
                malformedOptions = emptySet(),
            ),
            model.qualifiedSections.section("settings@jvm"),
        )
    }

    @Test
    fun recordsEveryUnsupportedKeyInTheOrderRejectUnsupportedReportsThem() {
        val model = bind(
            """
                product: jvm/lib
                mavenPlugins:
                  - org.example:plugin:1.0
                plugins:
                  - id: org.example.plugin
                settings:
                  lombok: enabled
                  springBoot: enabled
                  compose: enabled
                  kotlin:
                    dataframe: enabled
                    rpc: enabled
                    ksp:
                      processors: []
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "plugins",
                "mavenPlugins",
                "settings.compose",
                "settings.springBoot",
                "settings.lombok",
                "settings.kotlin.ksp",
                "settings.kotlin.rpc",
                "settings.kotlin.dataframe",
            ),
            model.unsupported,
        )
    }

    @Test
    fun recordsAnUnsupportedKeyThatCarriesNoValue() {
        assertEquals(listOf("plugins"), bind("product: jvm/lib\nplugins:\n").unsupported)
    }

    @Test
    fun recordsAKeyNothingReads() {
        val model = bind(
            """
                product: jvm/lib
                repositories:
                  - url: https://repo.example.com
                    credentials:
                      file: local.properties
                      usernameKey: user
                      passwordKey: password
                      passwordKy: password
                settings:
                  junti: 5
                  publishing:
                    enabled: true
                    group: org.example
                    version: 1.0.0
                    publishSource: true
                    pom:
                      developers:
                        - id: jane
                          organisation: Example
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "repositories[0].credentials.passwordKy",
                "settings.junti",
                "settings.publishing.publishSource",
                "settings.publishing.pom.developers[0].organisation",
            ),
            model.unknownKeys,
        )
    }

    @Test
    fun recordsAnUnreadKeyWithoutDescendingIntoIt() {
        val model = bind(
            """
                product: jvm/lib
                setings:
                  jvm:
                    release: 17
            """.trimIndent(),
        )

        assertEquals(listOf("setings"), model.unknownKeys)
    }

    @Test
    fun readsNothingUnreadOutOfAModuleThatDeclaresEverySectionTheBinderTakes() {
        val model = bind(
            """
                product:
                  type: kmp/lib
                  platforms: [jvm, android]
                layout: maven-like
                description: A module
                aliases:
                  jvmAndAndroid: [jvm, android]
                dependencies:
                  - org.example:library:1.0
                dependencies-dev:
                  - org.example:dev:1.0
                test-dependencies@jvm:
                  - org.example:testing:1.0
                plugins:
                  - id: org.example.plugin
                repositories:
                  - https://repo.example.com
                  - id: internal
                    url: https://internal.example.com
                    resolve: true
                    publish: true
                    credentials:
                      file: local.properties
                      usernameKey: user
                      passwordKey: password
                settings@jvm:
                  kotlin:
                    languageVersion: 2.0
                test-settings@jvm:
                  jvm:
                    freeJvmArgs: [-Xmx1g]
                settings:
                  junit: junit-5
                  ktor:
                    enabled: true
                    version: 3.5.0
                  native:
                    entryPoint: main
                  android:
                    namespace: org.example
                    compileSdk:
                      apiLevel: 36
                    minSdk: 24
                    targetSdk: 36
                    applicationId: org.example
                    versionCode: 1
                    versionName: "1.0"
                  jvm:
                    jdk:
                      version: 21
                    release: 17
                    mainClass: org.example.MainKt
                    test:
                      freeJvmArgs: [-Xmx1g]
                      systemProperties:
                        anything: goes
                      extraEnvironment:
                        ANYTHING: goes
                      junitPlatformVersion: 1.13.0
                  kotlin:
                    version: 2.4.10
                    languageVersion: 2.0
                    apiVersion: 2.0
                    allWarningsAsErrors: true
                    progressiveMode: true
                    freeCompilerArgs: [-Xcontext-parameters]
                    optIns: [kotlin.time.ExperimentalTime]
                    serialization:
                      enabled: true
                      version: 1.9.0
                      format: json
                    compilerPlugins:
                      - id: org.example.plugin
                        dependency: org.example:plugin:1.0
                        options:
                          anything: goes
                  publishing:
                    enabled: true
                    group: org.example
                    artifactId: library
                    version: 1.0.0
                    publishSources: true
                    signArtifacts: true
                    checksums: [sha256]
                    mavenCentral:
                      enabled: true
                      publishingMode: manual
                    pom:
                      name: Library
                      description: A library
                      url: https://example.com
                      licenses:
                        - name: Apache-2.0
                          url: https://example.com/license
                      developers:
                        - id: jane
                          name: Jane
                          url: https://example.com/jane
                          email: jane@example.com
                          organization: Example
                          organizationUrl: https://example.com
                      scm:
                        url: https://example.com/repo
                        connection: scm:git:https://example.com/repo
                        developerConnection: scm:git:ssh://example.com/repo
                test-settings:
                  jvm:
                    freeJvmArgs: [-Xmx2g]
                    systemProperties:
                      anything: goes
                    extraEnvironment:
                      ANYTHING: goes
                    release: 17
            """.trimIndent(),
        )

        assertEquals(emptyList(), model.unknownKeys)
    }

    @Test
    fun bindsAnEmptyModuleToAModelWithNoSections() {
        val model = bind("product: jvm/lib")

        assertEquals(
            ToolchainModel(
                product = ProductSpec("jvm/lib", listOf("jvm")),
                layout = Layout.AMPER,
                aliases = emptyMap(),
                dependencies = emptyMap(),
                testDependencies = emptyMap(),
                repositories = emptyList(),
                settings = Settings.EMPTY,
                qualifiedSections = emptyList(),
                unsupported = emptyList(),
                unknownKeys = emptyList(),
                errors = emptyMap(),
            ),
            model,
        )
    }

    @Test
    fun neverThrows() {
        val broken = listOf(
            "product: [1, 2]",
            "product: jvm/lib\ndependencies: nonsense\n",
            "product: jvm/lib\naliases: 7\n",
            "product: jvm/lib\nrepositories:\n  - []\n",
            "product: jvm/lib\nsettings:\n  kotlin:\n    optIns: nonsense\n",
            "product: jvm/lib\nsettings: plain\n",
            "product: jvm/lib\ntest-settings: plain\n",
        )

        for (yaml in broken) {
            val model = bind(yaml)
            assertTrue(model.unsupported.isEmpty(), "Unexpected unsupported keys for:\n$yaml")
        }
    }

    @Test
    fun defersTheFailureOfAJdkVersionThatIsNotAnInteger() {
        val model = bind(
            """
            product: jvm/lib
            settings:
              jvm:
                jdk:
                  version: "21.0.2"
            """.trimIndent(),
        )

        assertEquals("settings.jvm.jdk.version must be an integer, but was '21.0.2'", model.errors["settings"])
    }

    @Test
    fun defersTheFailureOfACompileSdkThatIsNotAnInteger() {
        val model = bind(
            """
            product: android/app
            settings:
              android:
                compileSdk: android-36
            """.trimIndent(),
        )

        assertEquals("settings.android.compileSdk must be an integer, but was 'android-36'", model.errors["settings"])
    }

    @Test
    fun defersTheFailureOfANestedCompileSdkApiLevelThatIsNotAnInteger() {
        val model = bind(
            """
            product: android/app
            settings:
              android:
                compileSdk:
                  apiLevel: android-36
            """.trimIndent(),
        )

        assertEquals(
            "settings.android.compileSdk.apiLevel must be an integer, but was 'android-36'",
            model.errors["settings"],
        )
    }

    @Test
    fun bindsANumericSettingAsTheLiteralKotlinSpells() {
        val model = bind(
            """
            product: android/app
            settings:
              android:
                compileSdk: 036
                versionCode: +7
            """.trimIndent(),
        )

        assertEquals("36", model.settings.android?.compileSdk)
        assertEquals("7", model.settings.android?.versionCode)
    }

    @Test
    fun defersTheFailureOfANumericSettingThatIsNotAScalar() {
        val model = bind(
            """
            product: android/app
            settings:
              android:
                minSdk: [26]
            """.trimIndent(),
        )

        assertEquals("settings.android.minSdk must be an integer", model.errors["settings"])
    }

    @Test
    fun defersTheFailureOfAJdkVersionBelowTheSupportedFloor() {
        val model = bind(
            """
            product: jvm/lib
            settings:
              jvm:
                jdk:
                  version: 11
            """.trimIndent(),
        )

        assertEquals("settings.jvm.jdk.version must be at least 17, but was 11", model.errors["settings"])
    }

    @Test
    fun bindsTheLowestSupportedJdkVersion() {
        val model = bind(
            """
            product: jvm/lib
            settings:
              jvm:
                jdk:
                  version: 17
            """.trimIndent(),
        )

        assertEquals("17", model.settings.jvm?.jdkVersion)
    }

    @Test
    fun defersTheFailureOfACompileSdkBelowTheSupportedFloor() {
        val model = bind(
            """
            product: android/app
            settings:
              android:
                compileSdk: 20
            """.trimIndent(),
        )

        assertEquals("settings.android.compileSdk must be at least 21, but was 20", model.errors["settings"])
    }

    @Test
    fun defersTheFailureOfANestedCompileSdkApiLevelBelowTheSupportedFloor() {
        val model = bind(
            """
            product: android/app
            settings:
              android:
                compileSdk:
                  apiLevel: 20
            """.trimIndent(),
        )

        assertEquals(
            "settings.android.compileSdk.apiLevel must be at least 21, but was 20",
            model.errors["settings"],
        )
    }

    @Test
    fun defersTheFailureOfAMinSdkBelowTheSupportedFloor() {
        val model = bind(
            """
            product: android/app
            settings:
              android:
                minSdk: -1
            """.trimIndent(),
        )

        assertEquals("settings.android.minSdk must be at least 21, but was -1", model.errors["settings"])
    }

    @Test
    fun defersTheFailureOfATargetSdkBelowTheSupportedFloor() {
        val model = bind(
            """
            product: android/app
            settings:
              android:
                targetSdk: 0
            """.trimIndent(),
        )

        assertEquals("settings.android.targetSdk must be at least 21, but was 0", model.errors["settings"])
    }

    @Test
    fun bindsTheLowestSupportedAndroidLevel() {
        val model = bind(
            """
            product: android/app
            settings:
              android:
                compileSdk: 21
                minSdk: 21
                targetSdk: 21
            """.trimIndent(),
        )

        assertEquals("21", model.settings.android?.compileSdk)
        assertEquals("21", model.settings.android?.minSdk)
        assertEquals("21", model.settings.android?.targetSdk)
    }

    @Test
    fun bindsAReleaseAndAVersionCodeThatTheToolchainAccepts() {
        val model = bind(
            """
            product: android/app
            settings:
              jvm:
                release: 0
              android:
                versionCode: -1
            """.trimIndent(),
        )

        assertEquals("0", model.settings.jvm?.release)
        assertEquals("-1", model.settings.android?.versionCode)
    }

    private fun bind(yaml: String): ToolchainModel =
        YamlBinder.bind(parseYaml(yaml, "shared/module.yaml"), "shared")

    private fun List<QualifiedSection>.section(key: String): QualifiedSection = single { it.key == key }
}
