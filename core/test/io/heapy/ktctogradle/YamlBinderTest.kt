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

/**
 * The binder is a pure `String -> ToolchainModel` function, so every case here is a whole-model or
 * whole-section `assertEquals`. Its defining property is that it never throws: a region the YAML
 * gets wrong is bound as absent and its message is deferred into [ToolchainModel.errors].
 */
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

    /** A third form is neither a string nor an object, and fails exactly as `product()` fails today. */
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

    /**
     * A scope is only judged by the stage that reads the section, so its failure defers under the
     * content key and not under the section key the load stage gates on. The section still binds to
     * the notation it named, which is what lets the load stage resolve a local reference either way.
     */
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

    /** One bad section must not take the others down, so each dependency key defers on its own. */
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

    /**
     * `settings.publishing`, in the shape a real published module writes it.
     *
     * The two shorthands are the point of the case: `mavenCentral` is a switch or an object, and
     * `scm` is the URL alone or the object. The Toolchain expands the URL form into both connection
     * strings, so that expansion belongs to the spelling of the section and is done here.
     */
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

    /** `test-settings.jvm.release`, which is bound apart from `settings.jvm.release`. */
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

    /** An option bound to `""` would reach the compiler as a real setting, so it is refused instead. */
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
                settings = Settings(kotlin = KotlinSettings(allWarningsAsErrors = true)),
                unsupportedKeys = emptyList(),
                malformedOptions = emptySet(),
            ),
            model.qualifiedSections.section("test-settings@jvm"),
        )
    }

    /**
     * A malformed qualified section is warned about and dropped by the stage that knows which
     * platforms the module has — the wording of those diagnostics is pinned by the
     * `qualified-settings` golden case. Binding must therefore stay silent about it: no exception
     * and no deferred error, only an absent section.
     */
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

    /**
     * A key of a qualified section has no field to bind to unless the model knows it, so the binder
     * records the ones it had to drop. The stage that reports them may not walk the YAML itself, and
     * the wording and the order of those diagnostics are pinned by the `qualified-settings` golden.
     */
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
                UnsupportedKey("kotlin.languageVersion", "must be a string"),
                UnsupportedKey("kotlin.allWarningsAsErrors", "must be true or false"),
                UnsupportedKey("kotlin.freeCompilerArgs", "must be a list"),
                UnsupportedKey("kotlin.unknown", UnsupportedKey.UNSUPPORTED),
                UnsupportedKey("kotlin.ksp", UnsupportedKey.UNSUPPORTED),
                UnsupportedKey("jvm.release", UnsupportedKey.UNSUPPORTED),
                UnsupportedKey("jvm.test.freeJvmArgs", UnsupportedKey.UNSUPPORTED),
            ),
            model.qualifiedSections.section("settings@jvm").unsupportedKeys,
        )
        assertEquals(
            listOf(UnsupportedKey("kotlin", "must be an object")),
            model.qualifiedSections.section("settings@linuxX64").unsupportedKeys,
        )
        assertEquals(emptyList(), model.qualifiedSections.section("test-settings@jvm").unsupportedKeys)

        // Only the dropped keys that stand for a compiler option override a broader section; a
        // `kotlin` node that is not an object stands for all six of them at once.
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

    /** A bare `plugins:` key parses to null, and is still a declared key that must be rejected. */
    @Test
    fun recordsAnUnsupportedKeyThatCarriesNoValue() {
        assertEquals(listOf("plugins"), bind("product: jvm/lib\nplugins:\n").unsupported)
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
                errors = emptyMap(),
            ),
            model,
        )
    }

    /** Whatever the YAML says, binding is total: nothing here may escape as an exception. */
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

    /**
     * The Gradle DSL takes these as bare integer literals, so a value that is not one would be
     * interpolated into a build script that does not parse. The Toolchain answers `21.0.2` with
     * "Expected: integer", and so does this.
     */
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

    /** The nested form binds to the same field, so its leaf is validated and its parent is not. */
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

    /**
     * The Toolchain reads `036` as `36` and prints it back that way; Kotlin rejects a leading zero
     * with "Leading zeros are not allowed in integer literals", so the value has to be re-spelled.
     */
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

    /**
     * A list is not a missing value: the Toolchain answers it with
     * "Expected `integer`, but got `sequence []`", so it must not be read as absent and defaulted.
     */
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

    private fun bind(yaml: String): ToolchainModel =
        YamlBinder.bind(parseYaml(yaml, "shared/module.yaml"), "shared")

    private fun List<QualifiedSection>.section(key: String): QualifiedSection = single { it.key == key }
}
