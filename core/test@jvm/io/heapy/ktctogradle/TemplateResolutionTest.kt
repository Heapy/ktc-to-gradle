package io.heapy.ktctogradle

import io.heapy.ktctogradle.load.strings
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.io.path.absolutePathString
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

        val config = ProjectLoader(okio.FileSystem.SYSTEM)
            .load(root.absolutePathString().toPath()).modules.single().config

        assertEquals(
            listOf("org.example:base:1", "org.example:left:1", "org.example:right:1"),
            config.strings("dependencies"),
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
}
