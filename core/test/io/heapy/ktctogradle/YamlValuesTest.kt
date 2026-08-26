package io.heapy.ktctogradle

import kotlin.test.Test
import kotlin.test.assertEquals

class YamlValuesTest {
    @Test
    fun parsesToolchainShapesWithKotaml() {
        val yaml = parseYaml(
            """
                product:
                  type: kmp/lib
                  platforms: [jvm, linuxX64]
                dependencies:
                  - //core
                  - org.example:library:1.0: compile-only
                settings:
                  kotlin:
                    version: 2.4.10
            """.trimIndent(),
            "test module",
        )

        assertEquals("kmp/lib", yaml.string("product.type"))
        assertEquals(listOf("jvm", "linuxX64"), yaml.strings("product.platforms"))
        assertEquals("2.4.10", yaml.string("settings.kotlin.version"))
    }

    @Test
    fun higherPrecedenceMappingsOverrideScalarsAndAppendLists() {
        val template = parseYaml("settings: { jvm: { release: 17 } }\ndependencies: [a:b:1]", "template")
        val module = parseYaml("settings: { jvm: { release: 21 } }\ndependencies: [c:d:2]", "module")
        val merged = mergeValues(template, module).asMapping("merged")

        assertEquals("21", merged.string("settings.jvm.release"))
        assertEquals(listOf("a:b:1", "c:d:2"), merged.strings("dependencies"))
    }
}

