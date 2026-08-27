package io.heapy.ktctogradle

import io.heapy.ktctogradle.interpret.ProjectInterpreter
import io.heapy.ktctogradle.load.ModuleLayout
import io.heapy.ktctogradle.load.ToolchainModule
import io.heapy.ktctogradle.load.ToolchainProject
import io.heapy.ktctogradle.load.YamlBinder
import io.heapy.ktctogradle.load.parseYaml
import io.heapy.ktctogradle.model.GradleModule
import io.heapy.ktctogradle.model.GradlePlugin
import io.heapy.ktctogradle.model.JvmBuild
import io.heapy.ktctogradle.model.MultiplatformBuild
import io.heapy.ktctogradle.model.PluginDecl
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
