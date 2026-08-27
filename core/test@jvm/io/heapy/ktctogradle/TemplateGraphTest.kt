package io.heapy.ktctogradle

import io.heapy.ktctogradle.load.TemplateGraph
import io.heapy.ktctogradle.load.Value
import io.heapy.ktctogradle.load.string
import io.heapy.ktctogradle.load.value
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The `apply:` graph on its own, without the surrounding project load.
 *
 * These are the failures a user meets while writing templates, so the messages they carry are as
 * much part of the behaviour as the merged configuration is.
 */
class TemplateGraphTest {
    @Test
    fun aTemplateCycleIsReportedAtTheFileThatClosesIt() {
        val root = temporaryRoot("ktc-template-cycle-")
        write(root.resolve("a.module-template.yaml"), "apply: [//b.module-template.yaml]\n")
        write(root.resolve("b.module-template.yaml"), "apply: [//a.module-template.yaml]\n")
        write(root.resolve("module.yaml"), "product: jvm/lib\napply: [//a.module-template.yaml]\n")

        val error = assertFailsWith<ConversionException> { resolve(root) }

        assertEquals("Template cycle detected at a.module-template.yaml", error.message)
    }

    @Test
    fun aMissingTemplateNamesTheFileThatAppliesIt() {
        val root = temporaryRoot("ktc-template-missing-")
        write(root.resolve("module.yaml"), "product: jvm/lib\napply: [//absent.module-template.yaml]\n")

        val error = assertFailsWith<ConversionException> { resolve(root) }

        assertEquals(
            "Template '//absent.module-template.yaml' referenced by module.yaml does not exist",
            error.message,
        )
    }

    /**
     * Neither template applies the other, so neither wins: the message has to name both files, or
     * the user has no way to find out where the two values came from.
     */
    @Test
    fun aScalarConflictAcrossSiblingTemplatesNamesBothSourceFiles() {
        val root = temporaryRoot("ktc-template-both-sources-")
        write(root.resolve("templates/a.module-template.yaml"), "settings: { jvm: { release: 17 } }\n")
        write(root.resolve("templates/b.module-template.yaml"), "settings: { jvm: { release: 21 } }\n")
        write(
            root.resolve("module.yaml"),
            "product: jvm/lib\napply: [//templates/a.module-template.yaml, //templates/b.module-template.yaml]\n",
        )

        val error = assertFailsWith<ConversionException> { resolve(root) }

        assertEquals(
            "Conflicting template values for 'settings.jvm.release' in " +
                "templates/a.module-template.yaml, templates/b.module-template.yaml",
            error.message,
        )
    }

    /** A module overrides the templates it applies, so its own scalar is not a conflict. */
    @Test
    fun aModuleScalarOverridesTheTemplateItApplies() {
        val root = temporaryRoot("ktc-template-override-")
        write(root.resolve("templates/base.module-template.yaml"), "settings: { jvm: { release: 17 } }\n")
        write(
            root.resolve("module.yaml"),
            "product: jvm/lib\napply: [//templates/base.module-template.yaml]\nsettings: { jvm: { release: 21 } }\n",
        )

        assertEquals("21", resolve(root).string("settings.jvm.release"))
    }

    /**
     * A template declares its credentials file next to itself, but the generated script runs from
     * the module directory, so both spellings are rebased onto the consumer.
     */
    @Test
    fun credentialFilePathsAreRewrittenRelativeToTheConsumer() {
        val root = temporaryRoot("ktc-template-credentials-graph-")
        write(root.resolve("secrets/shared.properties"), "shared.username=user\n")
        write(root.resolve("templates/local.properties"), "local.username=user\n")
        write(
            root.resolve("templates/private.module-template.yaml"),
            """
            repositories:
              - id: relative
                url: https://relative.example/maven
                credentials:
                  file: local.properties
                  usernameKey: local.username
                  passwordKey: local.password
              - id: rooted
                url: https://rooted.example/maven
                credentials:
                  file: //secrets/shared.properties
                  usernameKey: shared.username
                  passwordKey: shared.password
            """.trimIndent(),
        )
        write(
            root.resolve("app/module.yaml"),
            "product: jvm/lib\napply: [//templates/private.module-template.yaml]\n",
        )

        val repositories = resolve(root, root.resolve("app/module.yaml")).value("repositories")

        assertEquals(
            listOf("../templates/local.properties", "../secrets/shared.properties"),
            (repositories as Value.Sequence).items.map {
                (it as Value.Mapping).string("credentials.file")
            },
        )
    }

    private fun resolve(root: Path, moduleFile: Path = root.resolve("module.yaml")) =
        TemplateGraph(FileSystem.SYSTEM)
            .effectiveConfig(root.toString().toPath(), moduleFile.toString().toPath())

    private fun temporaryRoot(prefix: String): Path =
        Files.createTempDirectory(prefix).toRealPath()

    private fun write(path: Path, content: String) {
        path.parent.createDirectories()
        path.writeText(content)
    }
}
