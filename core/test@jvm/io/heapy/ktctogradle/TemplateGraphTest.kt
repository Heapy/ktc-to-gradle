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

    @Test
    fun aScalarConflictOnALiteralDottedKeyQuotesTheKeyThatCarriesTheDot() {
        assertEquals(
            "Conflicting template values for 'settings.\"my.app.mode\"' in $SIBLING_TEMPLATES",
            conflictMessage("ktc-template-conflict-dotted-key-") { "settings: { \"my.app.mode\": $it }\n" },
        )
    }

    @Test
    fun aQuoteOrABackslashInAKeyIsEscapedRatherThanClosingTheQuotedSegment() {
        assertEquals(
            "Conflicting template values for 'settings.\"\\\"a\".\"b\\\"\"' in $SIBLING_TEMPLATES",
            conflictMessage("ktc-template-conflict-quoted-key-") { "settings: { '\"a': { 'b\"': $it } }\n" },
        )
        assertEquals(
            "Conflicting template values for 'settings.\"back\\\\slash\"' in $SIBLING_TEMPLATES",
            conflictMessage("ktc-template-conflict-backslash-key-") { "settings: { 'back\\slash': $it }\n" },
        )
    }

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

    @Test
    fun aKeyThatContainsADotIsNotGraftedIntoANestedMapping() {
        val root = temporaryRoot("ktc-template-dotted-key-")
        write(
            root.resolve("module.yaml"),
            """
            product: jvm/lib
            settings:
              jvm:
                test:
                  systemProperties:
                    my.app.mode: fast
                    plain: ok
            """.trimIndent(),
        )

        assertEquals(
            Value.Mapping(mapOf("my.app.mode" to Value.Scalar("fast"), "plain" to Value.Scalar("ok"))),
            resolve(root).value("settings.jvm.test.systemProperties"),
        )
    }

    @Test
    fun aScalarKeyIsNotReplacedByASiblingKeyThatExtendsItWithADot() {
        val root = temporaryRoot("ktc-template-dotted-sibling-")
        write(
            root.resolve("module.yaml"),
            """
            product: jvm/lib
            settings:
              jvm:
                test:
                  systemProperties:
                    a: x
                    a.b: y
            """.trimIndent(),
        )

        assertEquals(
            Value.Mapping(mapOf("a" to Value.Scalar("x"), "a.b" to Value.Scalar("y"))),
            resolve(root).value("settings.jvm.test.systemProperties"),
        )
    }

    @Test
    fun aLiteralDottedKeyAndTheNestedPathThatLooksLikeItStaySeparate() {
        val root = temporaryRoot("ktc-template-dotted-vs-nested-")
        write(
            root.resolve("templates/flat.module-template.yaml"),
            "settings: { jvm: { test: { systemProperties: { \"a.b\": flat } } } }\n",
        )
        write(
            root.resolve("templates/nested.module-template.yaml"),
            "settings: { jvm: { test: { systemProperties: { a: { b: nested } } } } }\n",
        )
        write(
            root.resolve("module.yaml"),
            "product: jvm/lib\napply: [//templates/flat.module-template.yaml, //templates/nested.module-template.yaml]\n",
        )

        assertEquals(
            Value.Mapping(
                mapOf(
                    "a.b" to Value.Scalar("flat"),
                    "a" to Value.Mapping(mapOf("b" to Value.Scalar("nested"))),
                ),
            ),
            resolve(root).value("settings.jvm.test.systemProperties"),
        )
    }

    private fun conflictMessage(prefix: String, body: (String) -> String): String {
        val root = temporaryRoot(prefix)
        write(root.resolve("templates/a.module-template.yaml"), body("fast"))
        write(root.resolve("templates/b.module-template.yaml"), body("slow"))
        write(
            root.resolve("module.yaml"),
            "product: jvm/lib\napply: [//templates/a.module-template.yaml, //templates/b.module-template.yaml]\n",
        )

        return assertFailsWith<ConversionException> { resolve(root) }.message.orEmpty()
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

    private companion object {
        const val SIBLING_TEMPLATES = "templates/a.module-template.yaml, templates/b.module-template.yaml"
    }
}
