package io.heapy.ktctogradle

import io.heapy.ktctogradle.load.ProjectLoader
import io.heapy.ktctogradle.load.RawCredentials
import io.heapy.ktctogradle.load.RawDependency
import io.heapy.ktctogradle.load.RawRepository
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TemplateResolutionTest {
    @Test
    fun sharedNestedTemplateContributesOnce() {
        val root = Files.createTempDirectory("ktc-template-diamond-")
        root.resolve("base.module-template.yaml").writeText("dependencies: [org.example:base:1]\n")
        root.resolve("left.module-template.yaml").writeText(
            "apply: [//base.module-template.yaml]\ndependencies: [org.example:left:1]\n",
        )
        root.resolve("right.module-template.yaml").writeText(
            "apply: [//base.module-template.yaml]\ndependencies: [org.example:right:1]\n",
        )
        root.resolve("module.yaml").writeText(
            "product: jvm/lib\napply: [//left.module-template.yaml, //right.module-template.yaml]\n",
        )

        val model = ProjectLoader(okio.FileSystem.SYSTEM)
            .load(root.absolutePathString().toPath()).modules.single().model

        assertEquals(
            listOf(
                RawDependency("org.example:base:1"),
                RawDependency("org.example:left:1"),
                RawDependency("org.example:right:1"),
            ),
            model.dependencies[""],
        )
    }

    @Test
    fun aTemplateCredentialsFileIsRewrittenRelativeToTheModuleThatAppliesIt() {
        val root = Files.createTempDirectory("ktc-template-credentials-")
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
            "product: jvm/lib\napply:\n  - //templates/private.module-template.yaml\n",
        )

        val model = ProjectLoader(okio.FileSystem.SYSTEM)
            .load(root.absolutePathString().toPath()).modules.single().model

        assertEquals(
            listOf(
                RawRepository(
                    id = "mavenCentral",
                    url = "https://mirror.example/maven",
                    credentials = RawCredentials(
                        file = "../templates/credentials.properties",
                        usernameKey = "mirror.username",
                        passwordKey = "mirror.password",
                    ),
                ),
                RawRepository(id = "mavenGoogle", url = "https://maven.google.com", resolve = false),
            ),
            model.repositories,
        )
    }

    @Test
    fun siblingScalarConflictIsRejected() {
        val root = Files.createTempDirectory("ktc-template-conflict-")
        root.resolve("java17.module-template.yaml").writeText("settings: { jvm: { release: 17 } }\n")
        root.resolve("java21.module-template.yaml").writeText("settings: { jvm: { release: 21 } }\n")
        root.resolve("module.yaml").writeText(
            "product: jvm/lib\napply: [//java17.module-template.yaml, //java21.module-template.yaml]\n",
        )

        val error = assertFailsWith<ConversionException> {
            ProjectLoader(okio.FileSystem.SYSTEM).load(root.absolutePathString().toPath())
        }

        assertTrue(error.message.orEmpty().contains("Conflicting template values for 'settings.jvm.release'"))
    }

    /**
     * Measured against Kotlin Toolchain 0.12.0: the module's `settings@two` replaces the template's
     * `settings@one` because both name the same platforms and the module is the more specific
     * source, leaving `two` and `cross` agreeing on `true`. The converter cannot see which file a
     * qualified value came from, so it must not read the replaced template value as a conflict.
     */
    @Test
    fun aTemplateSectionAModuleReplacesIsNotAConflict() {
        val root = Files.createTempDirectory("ktc-template-qualified-").resolve("shared")
        root.createDirectories()
        root.resolve("base.module-template.yaml").writeText(
            "settings@one: { kotlin: { allWarningsAsErrors: false } }\n",
        )
        root.resolve("module.yaml").writeText(
            """
            product:
              type: kmp/lib
              platforms: [jvm, js, linuxX64]

            apply: [./base.module-template.yaml]

            aliases:
              - one: [jvm, js]
              - two: [jvm, js]
              - cross: [jvm, linuxX64]

            settings@two:
              kotlin:
                allWarningsAsErrors: true

            settings@cross:
              kotlin:
                allWarningsAsErrors: true
            """.trimIndent(),
        )

        val generated = Converter(okio.FileSystem.SYSTEM).generateFiles(root.absolutePathString().toPath())

        assertTrue(generated.files.any { it.path.name == "build.gradle.kts" })
    }

    private fun write(path: Path, content: String) {
        path.parent.createDirectories()
        path.writeText(content)
    }
}
