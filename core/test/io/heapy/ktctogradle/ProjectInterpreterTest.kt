package io.heapy.ktctogradle

import io.heapy.ktctogradle.interpret.ProjectInterpreter
import io.heapy.ktctogradle.load.ModuleLayout
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.ToolchainProject
import io.heapy.ktctogradle.load.YamlBinder
import io.heapy.ktctogradle.load.parseYaml
import io.heapy.ktctogradle.model.CompilerPlugin
import io.heapy.ktctogradle.model.DependencyTarget
import io.heapy.ktctogradle.model.GradleModule
import io.heapy.ktctogradle.model.GradlePlugin
import io.heapy.ktctogradle.model.JvmBuild
import io.heapy.ktctogradle.model.MultiplatformBuild
import io.heapy.ktctogradle.model.PluginDecl
import io.heapy.ktctogradle.model.Pom
import io.heapy.ktctogradle.model.PomDeveloper
import io.heapy.ktctogradle.model.PomLicense
import io.heapy.ktctogradle.model.PomScm
import io.heapy.ktctogradle.model.Publication
import io.heapy.ktctogradle.model.Repository
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a whole project means, asserted on the Gradle model rather than on generated text.
 *
 * The stage is pure, so every case here builds its [ToolchainProject] by hand: no temp directory
 * and no file system are involved, which is why this suite is common and not JVM-only.
 */
class ProjectInterpreterTest {
    @Test
    fun aProjectWithNoRootModuleGetsARootThatOnlyHoldsTheInheritedPlugins() {
        val project = interpret(
            project(
                module("app", "product: jvm/app\nsettings:\n  jvm:\n    mainClass: app.MainKt\n"),
            ),
        )

        val root = project.modules.first()
        assertEquals(":", root.gradlePath)
        assertEquals(ROOT, root.directory)
        assertNull(root.build, "A root that has no module of its own builds nothing")
        assertEquals(emptyList(), root.repositories)
        assertEquals(
            listOf(
                PluginDecl(GradlePlugin.Builtin.BASE, version = null),
                PluginDecl(GradlePlugin.Kotlin.JVM, version = Versions.KOTLIN, apply = false),
            ),
            root.plugins,
            "The root declares the plugins its subprojects apply, and applies none of them itself",
        )
    }

    @Test
    fun aRootModuleKeepsItsOwnBuildAndDeclaresTheVersionsForEveryone() {
        val project = interpret(
            project(
                module("", "product: jvm/lib\n"),
                module("app", "product: jvm/app\nsettings:\n  jvm:\n    mainClass: app.MainKt\n"),
            ),
        )

        val root = project.modules.first()
        assertTrue(root.build is JvmBuild, "The root module builds its own jvm/lib")
        assertEquals(
            listOf(PluginDecl(GradlePlugin.Kotlin.JVM, version = Versions.KOTLIN)),
            root.plugins,
            "kotlin(\"jvm\") is already declared with a version by the root itself, so it is not repeated, " +
                "and the unversioned application plugin is never inherited",
        )
        assertEquals(
            listOf(
                PluginDecl(GradlePlugin.Kotlin.JVM, version = null),
                PluginDecl(GradlePlugin.Builtin.APPLICATION, version = null),
            ),
            project.modules[1].plugins,
            "A subproject inherits the version the root declared",
        )
    }

    @Test
    fun theRootComesFirstAndSubprojectsFollowInPathOrder() {
        val project = interpret(
            project(
                module("app", "product: jvm/lib\n"),
                module("libs/shared", "product: jvm/lib\n"),
            ),
        )

        assertEquals(listOf(":", ":app", ":libs:shared"), project.modules.map(GradleModule::gradlePath))
    }

    @Test
    fun everySubprojectKeepsTheDirectoryItWasLoadedFrom() {
        val project = interpret(
            project(
                module("app", "product: jvm/lib\n"),
                module("libs/shared", "product: jvm/lib\n"),
            ),
        )

        assertEquals(
            listOf(
                ":" to ROOT,
                ":app" to ROOT / "app",
                ":libs:shared" to ROOT / "libs" / "shared",
            ),
            project.modules.map { module -> module.gradlePath to module.directory },
        )
    }

    /**
     * Where the catalog sits is a fact about the project, so the model carries the path as loaded.
     * Whether Gradle has to be told about it is a rendering decision and is pinned by
     * `render/SettingsRendererTest`.
     */
    @Test
    fun theVersionCatalogReachesTheModelWhereverTheProjectKeepsIt() {
        val modules = arrayOf(module("app", "product: jvm/lib\n"))

        assertEquals(
            ROOT / "libs.versions.toml",
            interpret(project(*modules, catalog = ROOT / "libs.versions.toml")).catalog,
        )
        assertEquals(
            ROOT / "gradle" / "libs.versions.toml",
            interpret(project(*modules, catalog = ROOT / "gradle" / "libs.versions.toml")).catalog,
        )
        assertNull(interpret(project(*modules)).catalog)
    }

    @Test
    fun anIosApplicationIsRefusedWithItsOwnExplanation() {
        val failure = assertFailsWith<ConversionException> {
            interpret(project(module("app", "product: ios/app\n")))
        }

        assertEquals(
            "app: ios/app contains an Xcode/Swift application and cannot be represented by a standalone Gradle module",
            failure.message,
        )
    }

    /**
     * A build plugin is the one product the rest of the project can be converted without, so it is
     * left out instead of failing the run: every other module still gets its build script.
     */
    @Test
    fun aToolchainBuildPluginIsLeftOutAndEveryOtherModuleIsStillConverted() {
        val diagnostics = DiagnosticCollector()
        val project = ProjectInterpreter.interpret(
            project(
                module("app", "product: jvm/app\nsettings:\n  jvm:\n    mainClass: app.MainKt\n"),
                module("plugin", "product: jvm/amper-plugin\n"),
            ),
            diagnostics,
        )

        assertEquals(listOf(":", ":app"), project.modules.map(GradleModule::gradlePath))
        assertEquals(
            listOf(
                Diagnostic(
                    Diagnostic.Severity.ERROR,
                    "plugin: Kotlin Toolchain build plugins have no automatic Gradle equivalent; " +
                        "the module was left out of the generated build",
                ),
            ),
            diagnostics.collected(),
        )
    }

    /** The root module is skipped like any other, and the build keeps the root the plugins need. */
    @Test
    fun aToolchainBuildPluginAtTheRootLeavesTheRootShellBehind() {
        val diagnostics = DiagnosticCollector()
        val project = ProjectInterpreter.interpret(
            project(
                module("", "product: jvm/amper-plugin\n"),
                module("app", "product: jvm/lib\n"),
            ),
            diagnostics,
        )

        val root = project.modules.first()
        assertEquals(listOf(":", ":app"), project.modules.map(GradleModule::gradlePath))
        assertNull(root.build, "A skipped root module builds nothing")
        assertEquals(
            listOf(
                PluginDecl(GradlePlugin.Builtin.BASE, version = null),
                PluginDecl(GradlePlugin.Kotlin.JVM, version = Versions.KOTLIN, apply = false),
            ),
            root.plugins,
            "The root still declares the plugin versions its subprojects inherit",
        )
        assertEquals(
            listOf(
                Diagnostic(
                    Diagnostic.Severity.ERROR,
                    "workspace: Kotlin Toolchain build plugins have no automatic Gradle equivalent; " +
                        "the module was left out of the generated build",
                ),
            ),
            diagnostics.collected(),
        )
    }

    /**
     * The generated `settings.gradle.kts` cannot include a module that was never rendered, so a
     * dependency still pointing at one is named rather than left to fail the Gradle build.
     *
     * A `bom:` entry counts: it renders as `platform(project(...))` and breaks the build the same
     * way.
     */
    @Test
    fun aDependencyOnASkippedModuleIsReported() {
        val diagnostics = DiagnosticCollector()
        ProjectInterpreter.interpret(
            project(
                module(
                    "app",
                    "product: jvm/lib\ndependencies:\n  - //plugin\n  - bom: //plugin\n" +
                        "test-dependencies:\n  - //plugin\n",
                ),
                module("plugin", "product: jvm/amper-plugin\n"),
            ),
            diagnostics,
        )

        assertEquals(
            listOf(
                Diagnostic(
                    Diagnostic.Severity.ERROR,
                    "plugin: Kotlin Toolchain build plugins have no automatic Gradle equivalent; " +
                        "the module was left out of the generated build",
                ),
                Diagnostic(
                    Diagnostic.Severity.ERROR,
                    "app depends on 'plugin', which was left out of the generated build",
                ),
            ),
            diagnostics.collected(),
            "One reference is reported once, however many sections repeat it",
        )
    }

    /**
     * The report reads the interpreted build, not the declared sections, so a qualifier this
     * product never reads contributes no reference and is not reported as one.
     */
    @Test
    fun aDependencyASkippedModuleOnlyAppearsUnderAnUnreadQualifierIsNotReported() {
        val diagnostics = DiagnosticCollector()
        ProjectInterpreter.interpret(
            project(
                module("app", "product: jvm/lib\ndependencies@js:\n  - //plugin\n"),
                module("plugin", "product: jvm/amper-plugin\n"),
            ),
            diagnostics,
        )

        assertEquals(
            listOf(
                Diagnostic(
                    Diagnostic.Severity.ERROR,
                    "plugin: Kotlin Toolchain build plugins have no automatic Gradle equivalent; " +
                        "the module was left out of the generated build",
                ),
            ),
            diagnostics.collected(),
        )
    }

    @Test
    fun anUnknownProductTypeIsNamedInTheFailure() {
        val failure = assertFailsWith<ConversionException> {
            interpret(project(module("app", "product:\n  type: fortran/app\n  platforms: [jvm]\n")))
        }

        assertEquals("app: unsupported product 'fortran/app'", failure.message)
    }

    /**
     * The product refusals are a documented, user-facing contract: someone converting an iOS
     * application has to be told iOS is out of scope, not that a section of a module the converter
     * was never going to produce is malformed. So the product is dispatched on before anything else
     * about the module is read, repositories included.
     *
     * The first assertion is what makes the rest non-vacuous: under a supported product the very
     * same section really does fail the conversion, and with a different message.
     */
    @Test
    fun anUnsupportedProductIsRefusedBeforeItsRepositoriesAreRead() {
        fun failureOf(product: String) = assertFailsWith<ConversionException> {
            interpret(project(module("app", "$product\n$MALFORMED_REPOSITORIES")))
        }.message

        assertEquals(
            "repositories[0].url is required",
            failureOf("product: jvm/lib"),
            "A supported product keeps reporting the repositories it was going to render",
        )
        assertEquals(
            "app: ios/app contains an Xcode/Swift application and cannot be represented by a standalone Gradle module",
            failureOf("product: ios/app"),
        )
        assertEquals(
            "app: unsupported product 'fortran/app'",
            failureOf("product:\n  type: fortran/app\n  platforms: [jvm]"),
        )
    }

    /** A skipped product is dispatched on ahead of the repositories too, so nothing raises them. */
    @Test
    fun aSkippedProductIsLeftOutBeforeItsRepositoriesAreRead() {
        val diagnostics = DiagnosticCollector()
        val project = ProjectInterpreter.interpret(
            project(module("app", "product: jvm/amper-plugin\n$MALFORMED_REPOSITORIES")),
            diagnostics,
        )

        assertEquals(listOf(":"), project.modules.map(GradleModule::gradlePath))
        assertEquals(
            listOf(
                Diagnostic(
                    Diagnostic.Severity.ERROR,
                    "app: Kotlin Toolchain build plugins have no automatic Gradle equivalent; " +
                        "the module was left out of the generated build",
                ),
            ),
            diagnostics.collected(),
        )
    }

    /**
     * `plugins:` is recorded before the product is even dispatched on, so a module that carries
     * both still reports the dropped section — and the product refusal then stops the run.
     */
    @Test
    fun anUnsupportedKeyIsRecordedBeforeTheProductRefusalAndItsRepositories() {
        val diagnostics = DiagnosticCollector()
        val failure = assertFailsWith<ConversionException> {
            ProjectInterpreter.interpret(
                project(
                    module("app", "product: ios/app\nplugins:\n  - ./build-plugin\n$MALFORMED_REPOSITORIES"),
                ),
                diagnostics,
            )
        }

        assertEquals(
            "app: ios/app contains an Xcode/Swift application and cannot be represented by a standalone Gradle module",
            failure.message,
        )
        assertEquals(listOf(Diagnostic(Diagnostic.Severity.ERROR, DROPPED_PLUGINS)), diagnostics.collected())
    }

    /**
     * A `plugins:` section names build plugins the module compiles without, so the section is
     * dropped with an error and the module is still converted.
     */
    @Test
    fun aModuleThatDeclaresPluginsIsStillConverted() {
        val diagnostics = DiagnosticCollector()
        val project = ProjectInterpreter.interpret(
            project(module("app", "product: jvm/lib\nplugins:\n  - ./build-plugin\n")),
            diagnostics,
        )

        assertEquals(listOf(":", ":app"), project.modules.map(GradleModule::gradlePath))
        assertEquals(listOf(Diagnostic(Diagnostic.Severity.ERROR, DROPPED_PLUGINS)), diagnostics.collected())
    }

    @Test
    fun aModuleThatDeclaresMavenPluginsIsStillConverted() {
        val diagnostics = DiagnosticCollector()
        val project = ProjectInterpreter.interpret(
            project(module("app", "product: jvm/lib\nmavenPlugins:\n  - org.jacoco:jacoco-maven-plugin:0.8.14\n")),
            diagnostics,
        )

        assertEquals(listOf(":", ":app"), project.modules.map(GradleModule::gradlePath))
        assertEquals(
            listOf(
                Diagnostic(
                    Diagnostic.Severity.ERROR,
                    "app: 'mavenPlugins' cannot be converted automatically; " +
                        "the section was dropped and needs a hand-written Gradle equivalent",
                ),
            ),
            diagnostics.collected(),
        )
    }

    /**
     * A compiler plugin is declared for the module and not for one of its targets, so it lands on
     * the module rather than inside the product-specific build.
     */
    @Test
    fun aCompilerPluginIsReadOntoTheModuleWhateverTheProductIs() {
        val declaration = """
            settings:
              kotlin:
                compilerPlugins:
                  - id: org.example.plugin
                    dependency: org.example:plugin-compiler:1.0
                    options:
                      moduleId: app
                      mode: strict
        """.trimIndent()
        val expected = listOf(
            CompilerPlugin(
                id = "org.example.plugin",
                dependency = DependencyTarget.Maven("org.example:plugin-compiler:1.0"),
                options = mapOf("moduleId" to "app", "mode" to "strict"),
            ),
        )

        for (product in listOf("product: jvm/lib\n", "product:\n  type: kmp/lib\n  platforms: [jvm]\n")) {
            val project = interpret(project(module("app", product + declaration)))

            assertEquals(expected, project.modules[1].compilerPlugins, "for $product")
        }
    }

    /** The Toolchain takes an external dependency, so a catalog alias resolves like any other. */
    @Test
    fun aCompilerPluginDependencyMayBeACatalogAlias() {
        val project = interpret(
            project(
                module(
                    "app",
                    """
                        product: jvm/lib
                        settings:
                          kotlin:
                            compilerPlugins:
                              - id: org.example.plugin
                                dependency: ${'$'}libs.example.compiler
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(
            listOf(CompilerPlugin(id = "org.example.plugin", dependency = DependencyTarget.Catalog("libs.example.compiler"))),
            project.modules[1].compilerPlugins,
        )
    }

    @Test
    fun aCompilerPluginDependencyThatNamesAModuleIsRefused() {
        val failure = assertFailsWith<ConversionException> {
            interpret(
                project(
                    module(
                        "app",
                        """
                            product: jvm/lib
                            settings:
                              kotlin:
                                compilerPlugins:
                                  - id: org.example.plugin
                                    dependency: //compiler
                        """.trimIndent(),
                    ),
                ),
            )
        }

        assertEquals(
            "app: settings.kotlin.compilerPlugins dependency '//compiler' must be a Maven coordinate " +
                "or a \$libs catalog alias",
            failure.message,
        )
    }

    /** A type with no platforms of its own is rejected while the product itself is being read. */
    @Test
    fun anUnknownProductTypeWithNoPlatformsIsRejectedAsItIsRead() {
        val failure = assertFailsWith<ConversionException> {
            interpret(project(module("app", "product: fortran/app\n")))
        }

        assertEquals("Unsupported product type 'fortran/app'", failure.message)
    }

    /**
     * A `plugins:` section is something the author can act on, so it is recorded before the product
     * type is read: a module whose product then fails the run still reports the dropped section.
     */
    @Test
    fun anUnsupportedKeyIsRecordedBeforeTheProductTypeIsEvenRead() {
        val diagnostics = DiagnosticCollector()
        val failure = assertFailsWith<ConversionException> {
            ProjectInterpreter.interpret(
                project(module("app", "product: fortran/app\nplugins:\n  - ./build-plugin\n")),
                diagnostics,
            )
        }

        assertEquals("Unsupported product type 'fortran/app'", failure.message)
        assertEquals(listOf(Diagnostic(Diagnostic.Severity.ERROR, DROPPED_PLUGINS)), diagnostics.collected())
    }

    @Test
    fun anUnsupportedSettingIsReportedBeforeTheProductTypeIsEvenRead() {
        val failure = assertFailsWith<ConversionException> {
            interpret(project(module("app", "product: fortran/app\nsettings:\n  compose:\n    enabled: true\n")))
        }

        assertEquals("app: 'settings.compose' is not supported yet", failure.message)
    }

    @Test
    fun theCredentialsImportCountsRepositoriesTheModuleOnlyPublishesTo() {
        val project = interpret(
            project(
                module(
                    "app",
                    """
                    product: jvm/lib
                    repositories:
                      - url: https://repo.example.com/releases
                        resolve: false
                        publish: true
                        credentials:
                          file: credentials.properties
                          usernameKey: user
                          passwordKey: secret
                    """.trimIndent(),
                ),
            ),
        )

        val module = project.modules.single { it.gradlePath == ":app" }
        assertTrue(module.requiresCredentialsImport, "A publish-only repository still has its credentials read")
        assertTrue(
            module.repositories.none { it.id == "https://repo.example.com/releases" },
            "A repository that is not resolved from stays out of the resolution list: ${module.repositories}",
        )
    }

    @Test
    fun aMultiplatformModuleReachesTheModelAsAMultiplatformBuild() {
        val project = interpret(
            project(module("shared", "product:\n  type: kmp/lib\n  platforms: [jvm, linuxX64]\n")),
        )

        assertTrue(project.modules[1].build is MultiplatformBuild)
    }

    /**
     * `settings.publishing` becomes a [Publication], and every key without a Gradle equivalent is
     * named rather than dropped.
     *
     * The `jvm/lib` half is the whole section carried; the `kmp/lib` half is the two keys that
     * cannot be: the Kotlin Gradle Plugin names a multiplatform module's artifacts itself, and
     * Gradle has no Central Portal upload.
     */
    @Test
    fun theWholePublishingSectionReachesTheModuleAndTheRestIsReported() {
        val diagnostics = DiagnosticCollector()
        val project = project(
            module(
                "library",
                """
                product: jvm/lib

                repositories:
                  - id: releases
                    url: https://repo.example/releases
                    resolve: false
                    publish: true

                settings:
                  publishing:
                    enabled: true
                    group: example.library
                    artifactId: renamed-library
                    version: 1.2.3
                    publishSources: true
                    signArtifacts: true
                    pom:
                      name: renamed-library
                      description: A library
                      url: https://example.invalid/library
                      licenses:
                        - name: Apache-2.0
                          url: https://www.apache.org/licenses/LICENSE-2.0.txt
                      developers:
                        - id: example
                          name: Example Developer
                      scm: https://example.invalid/library.git
                """.trimIndent(),
            ),
        )

        val module = ProjectInterpreter.interpret(project, diagnostics).modules.single { it.gradlePath == ":library" }

        assertEquals(
            Publication(
                group = "example.library",
                version = "1.2.3",
                artifactId = "renamed-library",
                publishSources = true,
                signArtifacts = true,
                pom = Pom(
                    name = "renamed-library",
                    description = "A library",
                    url = "https://example.invalid/library",
                    licenses = listOf(
                        PomLicense(name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0.txt"),
                    ),
                    developers = listOf(PomDeveloper(id = "example", name = "Example Developer")),
                    // The `scm: <url>` shorthand carries both connection strings.
                    scm = PomScm(
                        url = "https://example.invalid/library.git",
                        connection = "scm:git:https://example.invalid/library.git",
                        developerConnection = "scm:git:https://example.invalid/library.git",
                    ),
                ),
                perTarget = false,
                projectName = "library",
                repositories = listOf(Repository(id = "releases", url = "https://repo.example/releases")),
            ),
            module.publication,
        )
        assertEquals(
            listOf(GradlePlugin.Builtin.MAVEN_PUBLISH, GradlePlugin.Builtin.SIGNING),
            module.plugins.map(PluginDecl::plugin).filterIsInstance<GradlePlugin.Builtin>(),
        )
        assertEquals(emptyList(), diagnostics.collected())
    }

    /**
     * A multiplatform module keeps its base artifact id and reports only what Gradle cannot do.
     *
     * `artifactId` is the *base* name upstream: the Toolchain appends the platform to it for every
     * target but the root one, which is the same shape the Kotlin Gradle Plugin already produces
     * from the Gradle project name. The Central Portal upload is the key with no Gradle equivalent.
     */
    @Test
    fun aMultiplatformModuleKeepsThePomAndReportsWhatItCannotPublish() {
        val diagnostics = DiagnosticCollector()
        val project = project(
            module(
                "shared",
                """
                product:
                  type: kmp/lib
                  platforms: [jvm]

                settings:
                  publishing:
                    enabled: true
                    group: example.shared
                    artifactId: renamed-shared
                    version: 1.2.3
                    mavenCentral: enabled
                    pom:
                      name: renamed-shared
                """.trimIndent(),
            ),
        )

        val module = ProjectInterpreter.interpret(project, diagnostics).modules.single { it.gradlePath == ":shared" }

        assertEquals(
            Publication(
                group = "example.shared",
                version = "1.2.3",
                artifactId = "renamed-shared",
                publishSources = false,
                signArtifacts = false,
                pom = Pom(name = "renamed-shared"),
                perTarget = true,
                projectName = "shared",
            ),
            module.publication,
        )
        assertEquals(
            listOf(
                "shared: settings.publishing.mavenCentral has no Gradle equivalent; the generated build " +
                    "publishes to the repositories it declares and uploads no Central Portal bundle",
            ),
            diagnostics.collected().map(Diagnostic::message),
        )
    }

    /**
     * `enabled` defaults to `false`, so declaring the section is not asking to publish.
     *
     * This is the one default in `settings.publishing` that a converter can get wrong in the
     * expensive direction: a build that publishes a module the project never published ships an
     * artifact nobody asked for. Neither form produces a publication, plugins, or a diagnostic.
     */
    @Test
    fun publishingIsOffUnlessTheModuleTurnsItOn() {
        for (declaration in listOf("    enabled: false\n", "")) {
            val diagnostics = DiagnosticCollector()
            val project = project(
                module(
                    "library",
                    "product: jvm/lib\n\nsettings:\n  publishing:\n$declaration    group: example\n" +
                        "    version: 1.0.0\n",
                ),
            )

            val module = ProjectInterpreter.interpret(project, diagnostics)
                .modules.single { it.gradlePath == ":library" }

            assertNull(module.publication, "for '$declaration'")
            assertEquals(emptyList(), diagnostics.collected(), "for '$declaration'")
            assertEquals(
                emptyList(),
                module.plugins.map(PluginDecl::plugin).filterIsInstance<GradlePlugin.Builtin>(),
                "for '$declaration'",
            )
        }
    }

    /**
     * A publication with no coordinate is one nobody can consume, so it is an error and not a
     * degraded conversion. The Toolchain refuses the same two keys.
     */
    @Test
    fun publishingWithoutAGroupOrAVersionIsAnError() {
        val diagnostics = DiagnosticCollector()
        val project = project(
            module("library", "product: jvm/lib\n\nsettings:\n  publishing:\n    enabled: true\n"),
        )

        val module = ProjectInterpreter.interpret(project, diagnostics).modules.single { it.gradlePath == ":library" }

        assertNull(module.publication)
        assertEquals(
            listOf(
                "library: settings.publishing is enabled without settings.publishing.group and " +
                    "settings.publishing.version; the module was left unpublished",
            ),
            diagnostics.collected().map(Diagnostic::message),
        )
        assertEquals(
            emptyList(),
            module.plugins.map(PluginDecl::plugin).filterIsInstance<GradlePlugin.Builtin>(),
        )
    }

    /** The Toolchain publishes libraries; an application has no consumer to publish it for. */
    @Test
    fun publishingAnApplicationIsRefusedAndNamed() {
        for (product in listOf("jvm/app", "android/app")) {
            val diagnostics = DiagnosticCollector()
            val project = project(
                module(
                    "app",
                    "product: $product\n\nsettings:\n  publishing:\n    enabled: true\n" +
                        "    group: example\n    version: 1.0.0\n",
                ),
            )

            val module = ProjectInterpreter.interpret(project, diagnostics).modules.single { it.gradlePath == ":app" }

            assertNull(module.publication, "for $product")
            assertEquals(
                listOf(
                    "app: settings.publishing is not converted for a '$product'; " +
                        "the Kotlin Toolchain publishes jvm/lib and kmp/lib modules only",
                ),
                diagnostics.collected().map(Diagnostic::message).filter { "publishing" in it },
                "for $product",
            )
        }
    }

    private fun interpret(project: ToolchainProject) =
        ProjectInterpreter.interpret(project, DiagnosticCollector())

    private fun project(vararg modules: ToolchainModule, catalog: Path? = null): ToolchainProject =
        ToolchainProject(
            root = ROOT,
            name = "workspace",
            modules = modules.sortedBy(ToolchainModule::path),
            catalogPath = catalog,
        )

    private fun module(notation: String, yaml: String): ToolchainModule {
        val config = parseYaml(yaml, "$notation/module.yaml")
        val path = ModulePath.parse(notation)
        val directory = path.segments.fold(ROOT) { current, segment -> current / segment }
        return ToolchainModule(
            path = path,
            directory = directory,
            model = YamlBinder.bind(config, if (path.isRoot) directory.name else path.notation),
            layout = ModuleLayout(existingSourceDirs = emptySet(), detectedMainClass = null),
        )
    }

    private companion object {
        private val ROOT: Path = "/workspace".toPath()

        /** A repository with no `url`, which the binder defers and `Repositories` raises. */
        private const val MALFORMED_REPOSITORIES = "repositories:\n  - id: internal\n"

        private const val DROPPED_PLUGINS =
            "app: 'plugins' cannot be converted automatically; " +
                "the section was dropped and needs a hand-written Gradle equivalent"
    }
}
