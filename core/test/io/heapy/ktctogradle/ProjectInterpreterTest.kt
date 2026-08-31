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

    @Test
    fun everyKeyNothingReadsIsReportedWithItsPath() {
        val diagnostics = DiagnosticCollector()
        ProjectInterpreter.interpret(
            project(
                module(
                    "app",
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
                            pom:
                              developers:
                                - id: jane
                                  organisation: Example
                    """.trimIndent(),
                ),
            ),
            diagnostics,
        )

        assertEquals(
            listOf(
                unread("repositories[0].credentials.passwordKy"),
                unread("settings.junti"),
                unread("settings.publishing.pom.developers[0].organisation"),
            ),
            diagnostics.collected(),
        )
    }

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
    fun aCompilerPluginCatalogAliasThatCannotBecomeAKotlinAccessorIsRefused() {
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
                                    dependency: ${'$'}libs.example-compiler
                        """.trimIndent(),
                    ),
                ),
            )
        }

        assertEquals(
            "app: catalog dependency '\$libs.example-compiler' is not a version catalog accessor; " +
                "'\$libs.' takes the dot-separated accessor Gradle generates for the alias, so a " +
                "'ktor-client-core' alias is written '\$libs.ktor.client.core'",
            failure.message,
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

    @Test
    fun anUnknownProductTypeWithNoPlatformsIsRejectedAsItIsRead() {
        val failure = assertFailsWith<ConversionException> {
            interpret(project(module("app", "product: fortran/app\n")))
        }

        assertEquals("Unsupported product type 'fortran/app'", failure.message)
    }

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
                "shared: settings.publishing.mavenCentral is enabled, and Maven Central refuses a " +
                    "publication missing settings.publishing.signArtifacts, " +
                    "settings.publishing.publishSources, settings.publishing.pom.description, " +
                    "settings.publishing.pom.url, settings.publishing.pom.licenses, " +
                    "settings.publishing.pom.developers, settings.publishing.pom.scm; the Kotlin " +
                    "Toolchain checks the same requirements before it uploads",
                JAVADOC_JAR_WARNING,
            ),
            diagnostics.collected().map(Diagnostic::message),
        )
    }

    @Test
    fun mavenCentralNamesEachRequirementThePublicationDoesNotMeet() {
        for ((requirement, _) in CENTRAL_REQUIREMENTS) {
            val diagnostics = DiagnosticCollector()
            val project = project(module("library", centralModule(dropped = requirement)))

            ProjectInterpreter.interpret(project, diagnostics)

            assertEquals(
                listOf(
                    CENTRAL_PORTAL_WARNING,
                    "library: settings.publishing.mavenCentral is enabled, and Maven Central refuses " +
                        "a publication missing $requirement; the Kotlin Toolchain checks the same " +
                        "requirements before it uploads",
                ),
                diagnostics.collected().map(Diagnostic::message),
                "for $requirement",
            )
        }
    }

    @Test
    fun aCompleteCentralPublicationIsReportedOnlyForTheMissingUpload() {
        val diagnostics = DiagnosticCollector()
        val project = project(module("library", centralModule(dropped = null)))

        ProjectInterpreter.interpret(project, diagnostics)

        assertEquals(listOf(CENTRAL_PORTAL_WARNING), diagnostics.collected().map(Diagnostic::message))
    }

    @Test
    fun theModuleDescriptionSatisfiesThePomDescriptionRequirement() {
        val diagnostics = DiagnosticCollector()
        val yaml = "description: A library\n" + centralModule(dropped = "settings.publishing.pom.description")
        val project = project(module("library", yaml))

        ProjectInterpreter.interpret(project, diagnostics)

        assertEquals(listOf(CENTRAL_PORTAL_WARNING), diagnostics.collected().map(Diagnostic::message))
    }

    @Test
    fun aMultiplatformPublicationIsToldAboutTheJavadocJarItCannotBuild() {
        val diagnostics = DiagnosticCollector()
        val yaml = centralModule(dropped = null)
            .replace("product: jvm/lib", "product:\n  type: kmp/lib\n  platforms: [jvm]")
        val project = project(module("shared", yaml))

        ProjectInterpreter.interpret(project, diagnostics)

        assertEquals(
            listOf(
                "shared: settings.publishing.mavenCentral has no Gradle equivalent; the generated build " +
                    "publishes to the repositories it declares and uploads no Central Portal bundle",
                JAVADOC_JAR_WARNING,
            ),
            diagnostics.collected().map(Diagnostic::message),
        )
    }

    @Test
    fun theRequirementsAreCheckedOnlyForAModuleThatAsksForMavenCentral() {
        val diagnostics = DiagnosticCollector()
        val yaml = centralModule(dropped = "settings.publishing.pom.licenses")
            .replace("    mavenCentral: enabled\n", "")
        val project = project(module("library", yaml))

        ProjectInterpreter.interpret(project, diagnostics)

        assertEquals(emptyList(), diagnostics.collected())
    }

    private fun centralModule(dropped: String?): String = buildString {
        append("product: jvm/lib\n\nsettings:\n  publishing:\n    enabled: true\n")
        append("    group: example.library\n    version: 1.2.3\n    mavenCentral: enabled\n")
        for ((requirement, yaml) in CENTRAL_REQUIREMENTS) {
            if (requirement != dropped && !requirement.startsWith("settings.publishing.pom.")) append(yaml)
        }
        append("    pom:\n")
        for ((requirement, yaml) in CENTRAL_REQUIREMENTS) {
            if (requirement != dropped && requirement.startsWith("settings.publishing.pom.")) append(yaml)
        }
    }

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

    @Test
    fun theTestReleaseIsReportedOnlyWhereNoCompilationCanCarryIt() {
        val testSettings = "\n\ntest-settings:\n  jvm:\n    release: 25\n"
        val carried = listOf(
            "product: jvm/lib",
            "product: jvm/app",
            "product:\n  type: kmp/lib\n  platforms: [jvm, linuxX64]",
            "product:\n  type: kmp/lib\n  platforms: [android]\nsettings:\n  android:\n    namespace: example.lib",
        )
        for (yaml in carried) {
            val diagnostics = DiagnosticCollector()
            ProjectInterpreter.interpret(project(module("library", yaml + testSettings)), diagnostics)
            assertEquals(
                emptyList(),
                diagnostics.collected().map(Diagnostic::message).filter { "test-settings" in it },
                "for '$yaml'",
            )
        }

        val dropped = listOf(
            "product: android/app",
            "product:\n  type: kmp/lib\n  platforms: [linuxX64]",
            "product: js/app",
        )
        for (yaml in dropped) {
            val diagnostics = DiagnosticCollector()
            ProjectInterpreter.interpret(project(module("library", yaml + testSettings)), diagnostics)
            assertEquals(
                listOf(
                    "library: test-settings.jvm.release '25' was dropped; this module has no Kotlin JVM " +
                        "test compilation to carry it",
                ),
                diagnostics.collected().map(Diagnostic::message).filter { "test-settings" in it },
                "for '$yaml'",
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

    private fun unread(path: String): Diagnostic =
        Diagnostic(Diagnostic.Severity.WARNING, "app: '$path' is not read by the converter and was dropped")

    private companion object {
        private val ROOT: Path = "/workspace".toPath()

        private const val MALFORMED_REPOSITORIES = "repositories:\n  - id: internal\n"

        private const val DROPPED_PLUGINS =
            "app: 'plugins' cannot be converted automatically; " +
                "the section was dropped and needs a hand-written Gradle equivalent"

        private const val CENTRAL_PORTAL_WARNING =
            "library: settings.publishing.mavenCentral has no Gradle equivalent; the generated build " +
                "publishes to the repositories it declares and uploads no Central Portal bundle"

        private const val JAVADOC_JAR_WARNING =
            "shared: settings.publishing.mavenCentral is enabled, and Maven Central refuses a " +
                "publication without a javadoc jar; the generated build has none, because the Kotlin " +
                "Gradle Plugin builds no javadoc per target and the 'withJavadocJar()' a jvm/lib gets " +
                "has no multiplatform equivalent"

        private val CENTRAL_REQUIREMENTS = listOf(
            "settings.publishing.signArtifacts" to "    signArtifacts: true\n",
            "settings.publishing.publishSources" to "    publishSources: true\n",
            "settings.publishing.pom.description" to "      description: A library\n",
            "settings.publishing.pom.url" to "      url: https://example.invalid/library\n",
            "settings.publishing.pom.licenses" to "      licenses:\n        - name: Apache-2.0\n",
            "settings.publishing.pom.developers" to "      developers:\n        - id: example\n",
            "settings.publishing.pom.scm" to "      scm: https://example.invalid/library.git\n",
        )
    }
}
